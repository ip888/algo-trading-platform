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

    private double calculateRSI(List<Double> prices) {
        double avgGain = 0.0;
        double avgLoss = 0.0;

        // Calculate initial average gain/loss
        for (int i = 1; i <= PERIOD; i++) {
            double change = prices.get(i) - prices.get(i - 1);
            if (change > 0) {
                avgGain += change;
            } else {
                avgLoss += Math.abs(change);
            }
        }

        avgGain /= PERIOD;
        avgLoss /= PERIOD;

        // Calculate smoothed averages for the rest of the data
        // Note: We are calculating RSI for the *last* point in the history
        // Ideally we would do this incrementally, but for this batch processing we iterate through
        for (int i = PERIOD + 1; i < prices.size(); i++) {
            double change = prices.get(i) - prices.get(i - 1);
            double gain = change > 0 ? change : 0;
            double loss = change < 0 ? Math.abs(change) : 0;

            avgGain = (avgGain * (PERIOD - 1) + gain) / PERIOD;
            avgLoss = (avgLoss * (PERIOD - 1) + loss) / PERIOD;
        }

        if (avgLoss == 0) {
            return 100.0;
        }

        double rs = avgGain / avgLoss;
        return 100.0 - (100.0 / (1.0 + rs));
    }
}
