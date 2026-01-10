package com.trading.core.strategy;

import com.trading.core.api.AlpacaClient;
import com.trading.core.model.Bar;
import com.trading.core.model.MarketRegime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public class StrategyService {
    private static final Logger logger = LoggerFactory.getLogger(StrategyService.class);
    private final AlpacaClient client;
    private final double rsiLower;
    private final double rsiUpper;
    private final double macdThreshold;

    public StrategyService(AlpacaClient client) {
        this.client = client;
        this.rsiLower = Double.parseDouble(System.getenv().getOrDefault("RSI_LOWER", "30.0"));
        this.rsiUpper = Double.parseDouble(System.getenv().getOrDefault("RSI_UPPER", "70.0"));
        this.macdThreshold = Double.parseDouble(System.getenv().getOrDefault("MACD_THRESHOLD", "0.05"));
        logger.info("🧠 Strategy Loaded: RSI({}-{}), MACD({})", rsiLower, rsiUpper, macdThreshold);
    }

    public record StrategySignal(String symbol, String action, String strategyName, String reason, double price) {}

    /**
     * Generates a signal by fetching live market data.
     */
    public StrategySignal generateSignal(String symbol, MarketRegime regime) {
        try {
            // Fetch necessary history (e.g. 100 bars)
            List<Bar> history = client.getMarketHistoryAsync(symbol, 100).join();
            if (history.isEmpty()) {
                 return new StrategySignal(symbol, "HOLD", "Data Error", "No suitable bars found", 0.0);
            }
            return generateSignal(symbol, regime, history);
        } catch (Exception e) {
            logger.error("Error generating signal for " + symbol, e);
            return new StrategySignal(symbol, "HOLD", "Error", e.getMessage(), 0.0);
        }
    }

    /**
     * Generates a signal from provided historical data (Pure Function).
     * Suitable for Backtesting and Live Trading.
     */
    public StrategySignal generateSignal(String symbol, MarketRegime regime, List<Bar> history) {
        try {
            double price = history.get(history.size() - 1).close();
            List<Double> closes = history.stream().map(Bar::close).toList();

            // Adaptive Scaling Logic based on Regime
            StrategyParams params = getRegimeParams(regime, rsiLower, rsiUpper, macdThreshold);
            double effRsiLower = params.rsiLower();
            double effRsiUpper = params.rsiUpper();
            double effMacdThreshold = params.macdThreshold();

            logger.debug("📡 [{}] Strategy effective thresholds: RSI({}-{}), MACD({})", 
                symbol, String.format("%.1f", effRsiLower), String.format("%.1f", effRsiUpper), String.format("%.3f", effMacdThreshold));

            return switch (regime) {
                case STRONG_BULL, STRONG_BEAR -> executeMACD(symbol, closes, price, effMacdThreshold);
                case RANGE_BOUND -> executeRSI(symbol, closes, price, effRsiLower, effRsiUpper);
                default -> executeRSI(symbol, closes, price, effRsiLower, effRsiUpper); 
            };
        } catch (Exception e) {
            logger.error("Logic error for " + symbol, e);
            return new StrategySignal(symbol, "HOLD", "Logic Error", e.getMessage(), 0.0);
        }
    }
    
    // Explicitly private to prevent usage outside of controlled methods
    private StrategySignal executeRSI(String symbol, List<Double> prices, double currentPrice, double lower, double upper) {
        double rsi = calculateRSI(prices, 14);
        if (rsi < lower) return new StrategySignal(symbol, "BUY", "RSI Mean Reversion", "RSI Oversold: " + rsi, currentPrice);
        if (rsi > upper) return new StrategySignal(symbol, "SELL", "RSI Mean Reversion", "RSI Overbought: " + rsi, currentPrice);
        return new StrategySignal(symbol, "HOLD", "RSI Mean Reversion", "RSI Neutral: " + rsi, currentPrice);
    }

    private StrategySignal executeMACD(String symbol, List<Double> prices, double currentPrice, double threshold) {
        if (prices.size() < 35) return new StrategySignal(symbol, "HOLD", "MACD Trend", "Insufficient Data", currentPrice);

        // MACD Constants
        int fastPeriod = 12;
        int slowPeriod = 26;
        int signalPeriod = 9;

        // Calculate MACD at current index (last one)
        int currentIndex = prices.size() - 1;
        
        double fastEma = calculateEMA(prices, fastPeriod, currentIndex);
        double slowEma = calculateEMA(prices, slowPeriod, currentIndex);
        double macdLine = fastEma - slowEma;
        
        // Approximate Signal Line (SMA of MACD for simplicity/statelessness)
        // In a real system we'd compute full MACD history to get true Signal EMA
        double signalLine = calculateSignalLineApproximation(prices, fastPeriod, slowPeriod, signalPeriod, currentIndex);
        
        double histogram = macdLine - signalLine;
        
        // Logic
        // Buy: Macd > Signal AND Histogram > threshold
        if (macdLine > signalLine && histogram > threshold) {
             return new StrategySignal(symbol, "BUY", "MACD Trend", 
                 String.format("MACD Bullish (%.2f > %.2f)", macdLine, signalLine), currentPrice);
        } else if (macdLine < signalLine) {
             return new StrategySignal(symbol, "SELL", "MACD Trend", 
                 String.format("MACD Bearish (%.2f < %.2f)", macdLine, signalLine), currentPrice);
        }
        
        return new StrategySignal(symbol, "HOLD", "MACD Trend", String.format("Neutral (H: %.2f)", histogram), currentPrice);
    }

    private double calculateEMA(List<Double> prices, int period, int endIndex) {
        if (endIndex < period) return prices.get(endIndex);
        double k = 2.0 / (period + 1);
        double ema = prices.get(endIndex - period + 1); // Start with Price approx
        
        for (int i = endIndex - period + 2; i <= endIndex; i++) {
            ema = prices.get(i) * k + ema * (1 - k);
        }
        return ema;
    }

    private double calculateSignalLineApproximation(List<Double> prices, int fast, int slow, int signal, int endIndex) {
         double sumMacd = 0;
         for (int i = 0; i < signal; i++) {
             int idx = endIndex - i;
             double f = calculateEMA(prices, fast, idx);
             double s = calculateEMA(prices, slow, idx);
             sumMacd += (f - s);
         }
         return sumMacd / signal;
    }

    // Helper: RSI Calculation (Stateless)
    private double calculateRSI(List<Double> prices, int period) {
        if (prices.size() <= period) return 50.0;
        
        double avgGain = 0.0;
        double avgLoss = 0.0;
        
        for (int i = 1; i <= period; i++) {
            double change = prices.get(i) - prices.get(i - 1);
            if (change > 0) avgGain += change;
            else avgLoss += Math.abs(change);
        }
        
        avgGain /= period;
        avgLoss /= period;
        
        for (int i = period + 1; i < prices.size(); i++) {
            double change = prices.get(i) - prices.get(i - 1);
            double gain = change > 0 ? change : 0;
            double loss = change < 0 ? Math.abs(change) : 0;
            
            avgGain = (avgGain * (period - 1) + gain) / period;
            avgLoss = (avgLoss * (period - 1) + loss) / period;
        }
        
        if (avgLoss == 0) return 100.0;
        double rs = avgGain / avgLoss;
        return 100.0 - (100.0 / (1.0 + rs));
    }

    private record StrategyParams(double rsiLower, double rsiUpper, double macdThreshold) {}

    private StrategyParams getRegimeParams(MarketRegime regime, double baseLower, double baseUpper, double baseMacd) {
        return switch (regime) {
            case STRONG_BULL -> new StrategyParams(baseLower, baseUpper + 5.0, baseMacd * 0.5);
            case STRONG_BEAR -> new StrategyParams(baseLower - 10.0, baseUpper, baseMacd * 2.0);
            case HIGH_VOLATILITY -> new StrategyParams(baseLower - 5.0, baseUpper + 5.0, baseMacd * 3.0);
            default -> new StrategyParams(baseLower, baseUpper, baseMacd);
        };
    }
}
