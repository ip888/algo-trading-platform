package com.trading.strategy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Trend Following strategy - buys when price is above SMA.
 */
public final class TrendFollowingStrategy implements TradingStrategy {
    private static final Logger logger = LoggerFactory.getLogger(TrendFollowingStrategy.class);
    
    private double movingAverage;

    public void setMovingAverage(double sma) {
        this.movingAverage = sma;
    }

    @Override
    public TradingSignal evaluate(String symbol, double currentPrice, double positionQty) {
        return evaluateWithSMA(symbol, currentPrice, positionQty, movingAverage);
    }

    public TradingSignal evaluateWithSMA(String symbol, double currentPrice, double positionQty, double sma) {
        logger.debug("Trend Strategy: Price={}, SMA={}", currentPrice, sma);

        if (currentPrice > sma && positionQty == 0) {
            var reason = String.format("Price above SMA (%.2f > %.2f)", currentPrice, sma);
            logger.info("{}: BUY signal - {}", symbol, reason);
            return new TradingSignal.Buy(reason);
        } else if (currentPrice < sma && positionQty > 0) {
            var reason = String.format("Price below SMA (%.2f < %.2f)", currentPrice, sma);
            logger.info("{}: SELL signal - {}", symbol, reason);
            return new TradingSignal.Sell(reason);
        }

        return new TradingSignal.Hold("No trend signal");
    }
}
