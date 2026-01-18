package com.trading.backtesting;

import com.trading.api.AlpacaClient;
import com.trading.api.model.Bar;
import com.trading.config.Config;
import com.trading.metrics.PerformanceMetrics;
import com.trading.strategy.StrategyManager;
import com.trading.strategy.TradingSignal;
import com.trading.analysis.MarketRegimeDetector.MarketRegime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Enhanced Backtesting Engine with Real Historical Data
 */
public final class Backtester {
    private static final Logger logger = LoggerFactory.getLogger(Backtester.class);
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    
    public record BacktestRequest(
        String symbol, 
        int days, 
        double capital, 
        Double takeProfitPercent,
        Double stopLossPercent,
        Boolean useMock
    ) {
        public double getTakeProfit() { return takeProfitPercent != null ? takeProfitPercent : 0.75; }
        public double getStopLoss() { return stopLossPercent != null ? stopLossPercent : 1.0; }
        public boolean shouldUseMock() { return useMock != null && useMock; }
    }
    
    public record BacktestResult(
        double finalValue,
        double totalReturn,
        double maxDrawdown,
        int totalTrades,
        double winRate,
        double sharpeRatio,
        List<DailyEquity> equityCurve,
        List<TradeRecord> trades,
        BacktestResult comparison // Buy & Hold result
    ) {}
    
    public record DailyEquity(String date, double equity) {}
    public record TradeRecord(String date, String type, double price, int shares, double pnl) {}
    public record HistoricalData(List<Double> prices, List<LocalDateTime> dates) {}

    // CLI Entry Point
    public static void main(String[] args) {
        BacktestRequest request = new BacktestRequest("SPY", 30, 10000, 0.75, 1.0, false);
        var result = run(request);
        System.out.println("Backtest Complete. Return: " + result.totalReturn() + "%");
    }
    
    public static BacktestResult run(BacktestRequest request) {
        logger.info("Starting Backtest: {} for {} days with ${} (TP: {}%, SL: {}%)", 
            request.symbol(), request.days(), request.capital(), 
            request.getTakeProfit(), request.getStopLoss());
        
        try {
            List<Double> prices;
            List<LocalDateTime> dates;
            
            if (request.shouldUseMock()) {
                logger.info("Using mock data (explicitly requested)");
                prices = generateMockData(request.days(), 400.0);
                dates = generateMockDates(request.days());
            } else {
                try {
                    logger.info("Fetching REAL historical data from Alpaca for {}...", request.symbol());
                    var historicalData = fetchHistoricalData(request.symbol(), request.days());
                    prices = historicalData.prices();
                    dates = historicalData.dates();
                    logger.info("Fetched {} days of real price data", prices.size());
                } catch (Exception e) {
                    logger.error("Failed to fetch historical data: {}", e.getMessage());
                    throw new RuntimeException("Could not fetch real historical data for " + request.symbol() + ": " + e.getMessage());
                }
            }
            
            if (prices.isEmpty()) {
                throw new RuntimeException("No price data available for " + request.symbol());
            }
            
            // Run Strategy with TP/SL
            var strategyResult = executeBacktest(prices, dates, request.capital(), request.symbol(), 
                true, request.getTakeProfit(), request.getStopLoss());
            
            // Run Buy & Hold
            var buyHoldResult = executeBacktest(prices, dates, request.capital(), request.symbol(), 
                false, 0, 0);
            
            // Combine
            return new BacktestResult(
                strategyResult.finalValue(),
                strategyResult.totalReturn(),
                strategyResult.maxDrawdown(),
                strategyResult.totalTrades(),
                strategyResult.winRate(),
                strategyResult.sharpeRatio(),
                strategyResult.equityCurve(),
                strategyResult.trades(),
                buyHoldResult
            );
            
        } catch (Exception e) {
            logger.error("Backtest failed", e);
            throw new RuntimeException("Backtest failed: " + e.getMessage());
        }
    }

    private static BacktestResult executeBacktest(List<Double> prices, List<LocalDateTime> dates, 
            double startCapital, String symbol, boolean isStrategy, double takeProfitPct, double stopLossPct) {
        var metrics = new PerformanceMetrics(startCapital);
        var equityCurve = new ArrayList<DailyEquity>();
        var trades = new ArrayList<TradeRecord>();
        
        double cash = startCapital;
        int shares = 0;
        double entryPrice = 0;
        int winCount = 0;
        int totalTrades = 0;
        
        // Buy & Hold logic setup
        if (!isStrategy) {
            shares = (int) (startCapital / prices.get(0));
            cash = startCapital - (shares * prices.get(0));
            entryPrice = prices.get(0);
        }

        int startIdx = isStrategy ? Math.min(20, prices.size() - 1) : 0;
        
        for (int i = startIdx; i < prices.size(); i++) {
            var currentPrice = prices.get(i);
            var currentDate = dates.get(i);
            
            if (isStrategy) {
                // Check TP/SL if in position
                if (shares > 0) {
                    double returnPct = ((currentPrice - entryPrice) / entryPrice) * 100;
                    
                    // Take Profit hit
                    if (returnPct >= takeProfitPct) {
                        double pnl = (currentPrice - entryPrice) * shares;
                        cash += shares * currentPrice;
                        trades.add(new TradeRecord(currentDate.format(DATE_FMT), "TP_SELL", currentPrice, shares, pnl));
                        metrics.recordTrade(new PerformanceMetrics.Trade(currentDate, symbol, "SELL", shares, currentPrice, pnl));
                        if (pnl > 0) winCount++;
                        totalTrades++;
                        shares = 0;
                        entryPrice = 0;
                        continue;
                    }
                    
                    // Stop Loss hit
                    if (returnPct <= -stopLossPct) {
                        double pnl = (currentPrice - entryPrice) * shares;
                        cash += shares * currentPrice;
                        trades.add(new TradeRecord(currentDate.format(DATE_FMT), "SL_SELL", currentPrice, shares, pnl));
                        metrics.recordTrade(new PerformanceMetrics.Trade(currentDate, symbol, "SELL", shares, currentPrice, pnl));
                        if (pnl > 0) winCount++;
                        totalTrades++;
                        shares = 0;
                        entryPrice = 0;
                        continue;
                    }
                }
                
                // Entry logic using SMA crossover
                var history = prices.subList(Math.max(0, i - 20), i + 1);
                TradingSignal signal = new TradingSignal.Hold("Wait");
                
                if (history.size() >= 20) {
                    double sma20 = history.stream().mapToDouble(d -> d).average().orElse(0.0);
                    double sma5 = history.subList(history.size() - 5, history.size()).stream()
                        .mapToDouble(d -> d).average().orElse(0.0);
                    
                    // Buy when short MA crosses above long MA
                    if (sma5 > sma20 && shares == 0) {
                        signal = new TradingSignal.Buy("SMA5 > SMA20");
                    }
                }

                if (signal instanceof TradingSignal.Buy && shares == 0) {
                    int qty = (int) (cash / currentPrice);
                    if (qty > 0) {
                        shares = qty;
                        cash -= qty * currentPrice;
                        entryPrice = currentPrice;
                        trades.add(new TradeRecord(currentDate.format(DATE_FMT), "BUY", currentPrice, shares, 0));
                    }
                }
            }
            
            double totalValue = cash + (shares * currentPrice);
            equityCurve.add(new DailyEquity(currentDate.format(DATE_FMT), totalValue));
        }
        
        // Close end position
        if (shares > 0) {
             var finalPrice = prices.get(prices.size() - 1);
             double pnl = (finalPrice - entryPrice) * shares;
             cash += shares * finalPrice;
             if (isStrategy) {
                 trades.add(new TradeRecord(dates.get(dates.size()-1).format(DATE_FMT), "CLOSE", finalPrice, shares, pnl));
                 if (pnl > 0) winCount++;
                 totalTrades++;
             }
             shares = 0;
        }
        
        double totalReturn = ((cash - startCapital) / startCapital) * 100;
        double winRate = totalTrades > 0 ? (double) winCount / totalTrades : 0;
        
        return new BacktestResult(
            cash, totalReturn,
            isStrategy ? metrics.getMaxDrawdown() * 100 : 0.0,
            totalTrades,
            winRate * 100,
            isStrategy ? metrics.getSharpeRatio() : 0,
            equityCurve,
            trades,
            null
        );
    }
    
    private static HistoricalData fetchHistoricalData(String symbol, int days) {
        var config = new Config();
        var client = new AlpacaClient(config);
        
        var endDate = LocalDateTime.now();
        var startDate = endDate.minusDays(days + 20); // Extra for indicators
        
        // Convert LocalDateTime to ZonedDateTime or similar as expected by AlpacaClient
        // Assuming AlpacaClient has getBars(symbol, timeframe, limit) or similar
        // Adjusting to typical AlpacaClient usage pattern
        
        List<Double> prices = new ArrayList<>();
        List<LocalDateTime> dates = new ArrayList<>();
        
        try {
            var bars = client.getBars(symbol, "1Day", days + 20);
            for (var bar : bars) {
                prices.add(bar.close());
                dates.add(bar.timestamp().atZone(java.time.ZoneId.systemDefault()).toLocalDateTime());
            }
        } catch (Exception e) {
            logger.error("Error fetching data", e);
            throw new RuntimeException(e);
        }
        
        return new HistoricalData(prices, dates);
    }
    
    private static List<Double> generateMockData(int days, double startPrice) {
        var prices = new ArrayList<Double>();
        var random = new Random();
        double price = startPrice;
        
        for (int i = 0; i < days; i++) {
            prices.add(price);
            price *= (1.0 + (random.nextGaussian() * 0.01));
        }
        return prices;
    }
    
    private static List<LocalDateTime> generateMockDates(int days) {
        var dates = new ArrayList<LocalDateTime>();
        var date = LocalDateTime.now().minusDays(days);
        for (int i = 0; i < days; i++) {
            dates.add(date);
            date = date.plusDays(1);
        }
        return dates;
    }
}
