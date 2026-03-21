package com.trading.strategy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Relative Strength Index (RSI) Strategy.
 * Standard Mean Reversion strategy:
 * - Buy when RSI < 30 (Oversold)
 * - Sell when RSI > 70 (Overbought)
 */
public final class RSIStrategy implements TradingStrategy {
    private static final Logger logger = LoggerFactory.getLogger(RSIStrategy.class);
    private static final int PERIOD = 14;
    private static final double OVERSOLD = 30.0;
    private static final double OVERBOUGHT = 70.0;

    @Override
    public TradingSignal evaluate(String symbol, double currentPrice, double positionQty) {
        // This method signature doesn't support history, so we rely on the overload
        return new TradingSignal.Hold("RSI requires history");
    }

    public TradingSignal evaluateWithHistory(String symbol, double currentPrice, double positionQty, List<Double> history) {
        if (history.size() <= PERIOD) {
            return new TradingSignal.Hold("Insufficient history for RSI");
        }

        double rsi = calculateRSI(history);
        logger.debug("RSI Strategy: Price={}, RSI={}", currentPrice, String.format("%.2f", rsi));

        if (rsi < OVERSOLD && positionQty == 0) {
            var reason = String.format("RSI Oversold (%.2f < %.0f)", rsi, OVERSOLD);
            logger.info("{}: BUY signal - {}", symbol, reason);
            return new TradingSignal.Buy(reason);
        } else if (rsi > OVERBOUGHT && positionQty > 0) {
            var reason = String.format("RSI Overbought (%.2f > %.0f)", rsi, OVERBOUGHT);
            logger.info("{}: SELL signal - {}", symbol, reason);
            return new TradingSignal.Sell(reason);
        }

        // Provide detailed context for HOLD decision
        String context;
        if (positionQty > 0) {
            context = String.format("RSI=%.1f (have position, waiting for >%.0f to sell)", rsi, OVERBOUGHT);
        } else {
            context = String.format("RSI=%.1f (neutral zone, need <%.0f to buy)", rsi, OVERSOLD);
        }
        return new TradingSignal.Hold(context);
    }

    /**
     * Wilder's smoothed RSI using the last N bars.
     * Seed with simple average of first PERIOD changes, then apply
     * Wilder's exponential smoothing for remaining bars.
     * Operates on the tail of the history to avoid index-0 bias.
     */
    static double calculateRSI(List<Double> prices, int period) {
        int n = prices.size();
        if (n < period + 1) return 50.0;

        // Seed: simple average of first `period` changes starting from (n - period*2)
        // Using 2× period window ensures enough warmup bars for EMA to converge.
        int warmupStart = Math.max(1, n - period * 2);

        double avgGain = 0.0;
        double avgLoss = 0.0;
        // Initial seed over first `period` changes in the warmup window
        int seedEnd = warmupStart + period;
        if (seedEnd > n) seedEnd = n;
        for (int i = warmupStart; i < seedEnd; i++) {
            double change = prices.get(i) - prices.get(i - 1);
            if (change > 0) avgGain += change;
            else avgLoss += Math.abs(change);
        }
        avgGain /= period;
        avgLoss /= period;

        // Apply Wilder's smoothing for remaining bars up to last bar
        for (int i = seedEnd; i < n; i++) {
            double change = prices.get(i) - prices.get(i - 1);
            double gain = change > 0 ? change : 0.0;
            double loss = change < 0 ? Math.abs(change) : 0.0;
            avgGain = (avgGain * (period - 1) + gain) / period;
            avgLoss = (avgLoss * (period - 1) + loss) / period;
        }

        if (avgLoss == 0.0) return 100.0;
        double rs = avgGain / avgLoss;
        return 100.0 - (100.0 / (1.0 + rs));
    }

    private double calculateRSI(List<Double> prices) {
        return calculateRSI(prices, PERIOD);
    }
}
