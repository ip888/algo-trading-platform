package com.trading.strategy;

import com.trading.api.model.Bar;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Mean Reversion Strategy for High Volatility.
 * Buys when price is significantly below the mean (Bollinger Band Lower).
 * Sells when price returns to mean or hits Upper Band.
 */
public final class MeanReversionStrategy implements TradingStrategy {
    private static final Logger logger = LoggerFactory.getLogger(MeanReversionStrategy.class);
    private static final int PERIOD = 20;
    private static final double STD_DEV_MULTIPLIER = 2.5; // Wider bands for high volatility

    @Override
    public TradingSignal evaluate(String symbol, double currentPrice, double positionQty) {
        return new TradingSignal.Hold("Mean Reversion requires history");
    }

    public TradingSignal evaluateWithHistory(String symbol, double currentPrice, double positionQty, List<Double> history) {
        if (history.size() < PERIOD) {
            return new TradingSignal.Hold("Insufficient history for Mean Reversion");
        }

        // Calculate Bollinger Bands
        double sma = calculateSMA(history, PERIOD);
        double stdDev = calculateStdDev(history, sma, PERIOD);
        double upperBand = sma + (stdDev * STD_DEV_MULTIPLIER);
        double lowerBand = sma - (stdDev * STD_DEV_MULTIPLIER);

        logger.debug("{} Mean Reversion: Price=${} SMA=${} Lower=${} Upper=${}", 
            symbol, String.format("%.2f", currentPrice), 
            String.format("%.2f", sma), 
            String.format("%.2f", lowerBand), 
            String.format("%.2f", upperBand));

        // Logic: Buy the dip, Sell the rip
        if (positionQty == 0) {
            if (currentPrice <= lowerBand) {
                return new TradingSignal.Buy("Price below Lower Band (Oversold)");
            }
        } else {
            // Exit target: SMA + 1.5σ — captures ~60% of the full reversion (lower→upper band).
            // Exiting at the SMA alone (old behaviour) leaves the upper half of the move on the table.
            // Stop-loss from the position protects the downside if price stalls before this target.
            double reachableTarget = sma + stdDev * 1.5;
            if (currentPrice >= reachableTarget) {
                String reason = String.format(
                    "Mean Reversion: price $%.2f reached SMA+1.5σ target $%.2f — taking profit",
                    currentPrice, reachableTarget);
                logger.info("{}: SELL — {}", symbol, reason);
                return new TradingSignal.Sell(reason);
            }
        }

        return new TradingSignal.Hold(String.format(
            "Waiting: price $%.2f, lower $%.2f, target $%.2f",
            currentPrice, lowerBand, sma + stdDev * 1.5));
    }

    private double calculateSMA(List<Double> history, int period) {
        return history.stream()
                .skip(Math.max(0, history.size() - period))
                .mapToDouble(Double::doubleValue)
                .average()
                .orElse(0.0);
    }

    private double calculateStdDev(List<Double> history, double mean, int period) {
        double sumSqDiff = history.stream()
                .skip(Math.max(0, history.size() - period))
                .mapToDouble(p -> Math.pow(p - mean, 2))
                .sum();
        return Math.sqrt(sumSqDiff / period);
    }
}
