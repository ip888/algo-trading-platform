package com.trading.strategy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Momentum Strategy for Strong Uptrend Assets (e.g., Gold, Tech leaders).
 * 
 * This strategy is designed for assets showing strong bullish momentum:
 * - Buys when RSI is in "sweet spot" (40-65) with positive price momentum
 * - Sells when RSI becomes overbought (>75) or momentum fades
 * 
 * Unlike Mean Reversion, this BUYS INTO STRENGTH rather than weakness.
 * Ideal for trending assets like Gold during inflationary periods.
 */
public final class MomentumStrategy implements TradingStrategy {
    private static final Logger logger = LoggerFactory.getLogger(MomentumStrategy.class);
    
    // RSI parameters
    private static final int RSI_PERIOD = 14;
    private static final double RSI_BUY_MIN = 40.0;    // Not oversold
    private static final double RSI_BUY_MAX = 65.0;    // Not yet overbought (sweet spot)
    private static final double RSI_SELL_THRESHOLD = 75.0;  // Take profit on overbought
    
    // Momentum parameters
    private static final int MOMENTUM_PERIOD = 10;     // 10-bar momentum
    private static final double MOMENTUM_MIN = 0.005;  // 0.5% minimum momentum for entry
    
    // SMA trend filter
    private static final int SMA_PERIOD = 20;

    @Override
    public TradingSignal evaluate(String symbol, double currentPrice, double positionQty) {
        return new TradingSignal.Hold("Momentum requires history");
    }

    public TradingSignal evaluateWithHistory(String symbol, double currentPrice, double positionQty, List<Double> history) {
        if (history.size() < Math.max(RSI_PERIOD + 1, SMA_PERIOD)) {
            return new TradingSignal.Hold("Insufficient history for Momentum Strategy");
        }

        double rsi = calculateRSI(history, RSI_PERIOD);
        double momentum = calculateMomentum(history, MOMENTUM_PERIOD);
        double sma20 = calculateSMA(history, SMA_PERIOD);
        boolean priceAboveSMA = currentPrice > sma20;
        double percentAboveSMA = ((currentPrice - sma20) / sma20) * 100;

        logger.debug("{} Momentum: Price=${} RSI={:.1f} Momentum={:.2f}% AboveSMA={:.2f}%", 
            symbol, 
            String.format("%.2f", currentPrice), 
            rsi, 
            momentum * 100,
            percentAboveSMA);

        // EXIT: RSI overbought - take profit
        if (positionQty > 0 && rsi >= RSI_SELL_THRESHOLD) {
            String reason = String.format("Momentum Exit: RSI overbought (%.1f >= %.1f)", rsi, RSI_SELL_THRESHOLD);
            logger.info("{}: SELL signal - {}", symbol, reason);
            return new TradingSignal.Sell(reason);
        }
        
        // EXIT: Momentum turned negative while in position
        if (positionQty > 0 && momentum < -0.005) {
            String reason = String.format("Momentum Exit: Negative momentum (%.2f%%)", momentum * 100);
            logger.info("{}: SELL signal - {}", symbol, reason);
            return new TradingSignal.Sell(reason);
        }

        // ENTRY: RSI in sweet spot + positive momentum + above SMA
        if (positionQty == 0) {
            boolean rsiInSweetSpot = rsi >= RSI_BUY_MIN && rsi <= RSI_BUY_MAX;
            boolean hasPositiveMomentum = momentum >= MOMENTUM_MIN;
            
            if (rsiInSweetSpot && hasPositiveMomentum && priceAboveSMA) {
                String reason = String.format("Momentum Buy: RSI=%.1f (sweet spot), Momentum=+%.2f%%, %.2f%% above SMA", 
                    rsi, momentum * 100, percentAboveSMA);
                logger.info("{}: BUY signal - {}", symbol, reason);
                return new TradingSignal.Buy(reason);
            }
            
            // Alternative entry: Strong momentum even with higher RSI (trending hard)
            if (rsi > RSI_BUY_MAX && rsi < 72 && momentum >= 0.01 && priceAboveSMA && percentAboveSMA < 3.0) {
                String reason = String.format("Momentum Buy (Trend): RSI=%.1f, Strong Momentum=+%.2f%%", 
                    rsi, momentum * 100);
                logger.info("{}: BUY signal - {}", symbol, reason);
                return new TradingSignal.Buy(reason);
            }
        }

        return new TradingSignal.Hold(String.format("Waiting for entry: RSI=%.1f, Mom=%.2f%%", rsi, momentum * 100));
    }

    private double calculateRSI(List<Double> history, int period) {
        if (history.size() < period + 1) return 50.0;
        
        double gains = 0, losses = 0;
        for (int i = history.size() - period; i < history.size(); i++) {
            double change = history.get(i) - history.get(i - 1);
            if (change > 0) gains += change;
            else losses -= change;
        }
        
        if (losses == 0) return 100.0;
        double rs = (gains / period) / (losses / period);
        return 100.0 - (100.0 / (1.0 + rs));
    }

    private double calculateMomentum(List<Double> history, int period) {
        if (history.size() < period) return 0.0;
        double current = history.get(history.size() - 1);
        double past = history.get(history.size() - period);
        return (current - past) / past;
    }

    private double calculateSMA(List<Double> history, int period) {
        return history.stream()
                .skip(Math.max(0, history.size() - period))
                .mapToDouble(Double::doubleValue)
                .average()
                .orElse(0.0);
    }
}
