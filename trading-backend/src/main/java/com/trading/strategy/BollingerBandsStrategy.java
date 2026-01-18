package com.trading.strategy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Bollinger Bands Strategy.
 * Mean reversion strategy using volatility bands:
 * - Buy when price < lower band (oversold)
 * - Sell when price > upper band (overbought)
 */
public final class BollingerBandsStrategy implements TradingStrategy {
    private static final Logger logger = LoggerFactory.getLogger(BollingerBandsStrategy.class);
    private static final int PERIOD = 20;
    private static final double NUM_STD_DEV = 2.0;

    @Override
    public TradingSignal evaluate(String symbol, double currentPrice, double positionQty) {
        return new TradingSignal.Hold("Bollinger Bands requires history");
    }

    public TradingSignal evaluateWithHistory(String symbol, double currentPrice, double positionQty, List<Double> history) {
        if (history.size() < PERIOD) {
            return new TradingSignal.Hold("Insufficient history for Bollinger Bands");
        }

        // Calculate SMA
        double sma = history.subList(history.size() - PERIOD, history.size())
                .stream()
                .mapToDouble(Double::doubleValue)
                .average()
                .orElse(0.0);

        // Calculate standard deviation
        double variance = history.subList(history.size() - PERIOD, history.size())
                .stream()
                .mapToDouble(p -> Math.pow(p - sma, 2))
                .average()
                .orElse(0.0);
        
        double stdDev = Math.sqrt(variance);

        // Calculate bands
        double upperBand = sma + (NUM_STD_DEV * stdDev);
        double lowerBand = sma - (NUM_STD_DEV * stdDev);

        logger.debug("Bollinger Bands: Price={}, SMA={}, Upper={}, Lower={}, StdDev={}", 
            currentPrice, String.format("%.2f", sma), String.format("%.2f", upperBand), 
            String.format("%.2f", lowerBand), String.format("%.2f", stdDev));

        // Trading logic
        if (currentPrice < lowerBand && positionQty == 0) {
            var reason = String.format("Price below lower band (%.2f < %.2f)", currentPrice, lowerBand);
            logger.info("{}: BUY signal - {}", symbol, reason);
            return new TradingSignal.Buy(reason);
        } else if (currentPrice > upperBand && positionQty > 0) {
            var reason = String.format("Price above upper band (%.2f > %.2f)", currentPrice, upperBand);
            logger.info("{}: SELL signal - {}", symbol, reason);
            return new TradingSignal.Sell(reason);
        }

        return new TradingSignal.Hold("Within bands");
    }
}
