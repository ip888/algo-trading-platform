package com.trading.backtest;

import com.trading.analysis.MarketRegimeDetector.MarketRegime;
import com.trading.api.model.Bar;
import com.trading.strategy.StrategyManager;
import com.trading.strategy.TradingSignal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Backtesting engine that replays historical bar data through the strategy pipeline.
 *
 * Simulates the bot's trading cycle: signal evaluation, position sizing, SL/TP,
 * trailing stops, and trade recording. Uses the same StrategyManager that the
 * live bot uses so results are representative of live performance.
 *
 * Usage:
 * <pre>
 *   var bars = alpacaClient.getBars("SPY", "1Day", 252);
 *   var engine = new BacktestEngine(strategyManager);
 *   var result = engine.run(BacktestConfig.defaults("SPY", 1000), bars);
 *   System.out.println(result.summary());
 * </pre>
 */
public final class BacktestEngine {
    private static final Logger logger = LoggerFactory.getLogger(BacktestEngine.class);

    private final StrategyManager strategyManager;

    public BacktestEngine(StrategyManager strategyManager) {
        this.strategyManager = strategyManager;
    }

    /** Backtesting parameters. */
    public record BacktestConfig(
        String symbol,
        double initialCapital,
        double takeProfitPercent,
        double stopLossPercent,
        double trailingStopPercent,
        double riskPerTrade,       // fraction of capital per trade (e.g. 0.02 = 2%)
        MarketRegime regime,
        int warmupBars             // bars to skip for indicator warmup
    ) {
        public static BacktestConfig defaults(String symbol, double capital) {
            return new BacktestConfig(symbol, capital, 0.9, 0.8, 0.4, 0.02,
                MarketRegime.RANGE_BOUND, 50);
        }
    }

    /** A single completed trade. */
    public record BacktestTrade(
        String symbol,
        Instant entryTime,
        Instant exitTime,
        double entryPrice,
        double exitPrice,
        double quantity,
        double pnl,
        String exitReason
    ) {}

    /** Aggregate result of a backtest run. */
    public record BacktestResult(
        String symbol,
        double initialCapital,
        double finalCapital,
        int totalTrades,
        int wins,
        int losses,
        double totalPnL,
        double maxDrawdownPercent,
        List<BacktestTrade> trades
    ) {
        public double winRate() {
            return totalTrades > 0 ? (double) wins / totalTrades : 0;
        }

        public double returnPercent() {
            return initialCapital > 0 ? (finalCapital - initialCapital) / initialCapital * 100 : 0;
        }

        public double profitFactor() {
            double grossWin = trades.stream().filter(t -> t.pnl > 0).mapToDouble(BacktestTrade::pnl).sum();
            double grossLoss = Math.abs(trades.stream().filter(t -> t.pnl < 0).mapToDouble(BacktestTrade::pnl).sum());
            return grossLoss > 0 ? grossWin / grossLoss : grossWin > 0 ? Double.MAX_VALUE : 0;
        }

        public String summary() {
            return String.format(
                """
                === Backtest Result: %s ===
                Capital: $%.2f -> $%.2f (%.2f%%)
                Trades: %d (W: %d / L: %d)
                Win Rate: %.1f%%
                Profit Factor: %.2f
                Max Drawdown: %.2f%%
                Total P&L: $%.2f
                """,
                symbol, initialCapital, finalCapital, returnPercent(),
                totalTrades, wins, losses,
                winRate() * 100, profitFactor(),
                maxDrawdownPercent, totalPnL
            );
        }
    }

    /**
     * Run a backtest on the provided bars.
     * Bars should be in chronological order (oldest first).
     */
    public BacktestResult run(BacktestConfig cfg, List<Bar> bars) {
        if (bars.size() < cfg.warmupBars() + 10) {
            logger.warn("Insufficient bars for backtest: {} (need at least {})",
                bars.size(), cfg.warmupBars() + 10);
            return new BacktestResult(cfg.symbol(), cfg.initialCapital(), cfg.initialCapital(),
                0, 0, 0, 0, 0, List.of());
        }

        double capital = cfg.initialCapital();
        double peakCapital = capital;
        double maxDrawdown = 0;
        List<BacktestTrade> trades = new ArrayList<>();

        // Position tracking
        boolean inPosition = false;
        double entryPrice = 0;
        double quantity = 0;
        double stopLoss = 0;
        double takeProfit = 0;
        double trailingStop = 0;
        Instant entryTime = null;

        for (int i = cfg.warmupBars(); i < bars.size(); i++) {
            Bar bar = bars.get(i);
            double price = bar.close();

            // Build price history window for strategy evaluation
            List<Double> history = bars.subList(Math.max(0, i - 100), i + 1)
                .stream().map(Bar::close).toList();

            if (inPosition) {
                // Check stop-loss
                if (price <= stopLoss) {
                    double pnl = (price - entryPrice) * quantity;
                    capital += pnl;
                    trades.add(new BacktestTrade(cfg.symbol(), entryTime, bar.timestamp(),
                        entryPrice, price, quantity, pnl, "STOP_LOSS"));
                    inPosition = false;
                    continue;
                }

                // Check take-profit
                if (price >= takeProfit) {
                    double pnl = (price - entryPrice) * quantity;
                    capital += pnl;
                    trades.add(new BacktestTrade(cfg.symbol(), entryTime, bar.timestamp(),
                        entryPrice, price, quantity, pnl, "TAKE_PROFIT"));
                    inPosition = false;
                    continue;
                }

                // Update trailing stop
                double newTrail = price * (1.0 - cfg.trailingStopPercent() / 100.0);
                if (newTrail > trailingStop) {
                    trailingStop = newTrail;
                    if (trailingStop > stopLoss) {
                        stopLoss = trailingStop;
                    }
                }

                // Evaluate for sell signal
                TradingSignal signal = strategyManager.evaluateWithHistory(
                    cfg.symbol(), price, quantity, history, cfg.regime());
                if (signal instanceof TradingSignal.Sell) {
                    double pnl = (price - entryPrice) * quantity;
                    capital += pnl;
                    trades.add(new BacktestTrade(cfg.symbol(), entryTime, bar.timestamp(),
                        entryPrice, price, quantity, pnl, "SIGNAL_SELL"));
                    inPosition = false;
                }
            } else {
                // Evaluate for buy signal
                TradingSignal signal = strategyManager.evaluateWithHistory(
                    cfg.symbol(), price, 0, history, cfg.regime());
                if (signal instanceof TradingSignal.Buy && capital > 0) {
                    // Position sizing: risk-based
                    double riskAmount = capital * cfg.riskPerTrade();
                    double slDistance = price * cfg.stopLossPercent() / 100.0;
                    quantity = slDistance > 0 ? riskAmount / slDistance : 0;

                    // Cap at available capital
                    double maxQty = capital / price;
                    quantity = Math.min(quantity, maxQty);

                    if (quantity * price < 1.0) continue; // skip sub-$1 orders

                    entryPrice = price;
                    entryTime = bar.timestamp();
                    stopLoss = price * (1.0 - cfg.stopLossPercent() / 100.0);
                    takeProfit = price * (1.0 + cfg.takeProfitPercent() / 100.0);
                    trailingStop = stopLoss;
                    capital -= quantity * price; // allocate capital
                    inPosition = true;
                }
            }

            // Track drawdown
            double equity = inPosition ? capital + quantity * price : capital;
            if (equity > peakCapital) peakCapital = equity;
            double dd = (peakCapital - equity) / peakCapital * 100;
            if (dd > maxDrawdown) maxDrawdown = dd;
        }

        // Close any open position at last bar price
        if (inPosition) {
            double lastPrice = bars.getLast().close();
            double pnl = (lastPrice - entryPrice) * quantity;
            capital += pnl;
            trades.add(new BacktestTrade(cfg.symbol(), entryTime, bars.getLast().timestamp(),
                entryPrice, lastPrice, quantity, pnl, "END_OF_DATA"));
        }

        int wins = (int) trades.stream().filter(t -> t.pnl > 0).count();
        int losses = (int) trades.stream().filter(t -> t.pnl <= 0).count();
        double totalPnL = trades.stream().mapToDouble(BacktestTrade::pnl).sum();

        var result = new BacktestResult(cfg.symbol(), cfg.initialCapital(), capital,
            trades.size(), wins, losses, totalPnL, maxDrawdown, trades);

        logger.info("Backtest complete: {}", result.summary());
        return result;
    }
}
