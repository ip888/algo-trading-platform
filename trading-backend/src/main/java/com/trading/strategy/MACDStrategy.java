package com.trading.strategy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Moving Average Convergence Divergence (MACD) Strategy.
 * Standard Trend Following strategy:
 * - Buy when MACD crosses ABOVE Signal line (Bullish Crossover)
 * - Sell when MACD crosses BELOW Signal line (Bearish Crossover)
 */
public final class MACDStrategy implements TradingStrategy {
    private static final Logger logger = LoggerFactory.getLogger(MACDStrategy.class);
    private static final int FAST_PERIOD = 12;
    private static final int SLOW_PERIOD = 26;
    private static final int SIGNAL_PERIOD = 9;

    @Override
    public TradingSignal evaluate(String symbol, double currentPrice, double positionQty) {
        return new TradingSignal.Hold("MACD requires history");
    }

    public TradingSignal evaluateWithHistory(String symbol, double currentPrice, double positionQty, List<Double> history) {
        if (history.size() <= SLOW_PERIOD + SIGNAL_PERIOD) {
            return new TradingSignal.Hold("Insufficient history for MACD");
        }

        // We need the last TWO values to detect a crossover
        // Index -1 is current, Index -2 is previous
        double[] macdValuesCurrent = calculateMACD(history, history.size() - 1);
        double[] macdValuesPrev = calculateMACD(history, history.size() - 2);

        double macdLine = macdValuesCurrent[0];
        double signalLine = macdValuesCurrent[1];
        double prevMacdLine = macdValuesPrev[0];
        double prevSignalLine = macdValuesPrev[1];
        
        double histogram = macdLine - signalLine;

        logger.debug("MACD Strategy: Price={}, MACD={}, Signal={}, Hist={}", 
            currentPrice, String.format("%.2f", macdLine), String.format("%.2f", signalLine), String.format("%.2f", histogram));

        // Bullish Crossover: MACD crosses ABOVE Signal
        boolean bullishCrossover = prevMacdLine <= prevSignalLine && macdLine > signalLine;
        
        // Trend Following Entry: MACD is already above Signal AND Histogram is increasing (momentum building)
        boolean strongUptrend = macdLine > signalLine && histogram > (prevMacdLine - prevSignalLine) && histogram > 0.05;

        // Bearish Crossover: MACD crosses BELOW Signal
        boolean bearishCrossover = prevMacdLine >= prevSignalLine && macdLine < signalLine;

        if ((bullishCrossover || strongUptrend) && positionQty == 0) {
            String type = bullishCrossover ? "Crossover" : "Trend Follow";
            var reason = String.format("MACD Buy (%s): %.2f / %.2f", type, macdLine, signalLine);
            logger.info("{}: BUY signal - {}", symbol, reason);
            return new TradingSignal.Buy(reason);
        } else if (bearishCrossover && positionQty > 0) {
            var reason = String.format("MACD Bearish Crossover (%.2f / %.2f)", macdLine, signalLine);
            logger.info("{}: SELL signal - {}", symbol, reason);
            return new TradingSignal.Sell(reason);
        }

        // Provide detailed context for HOLD decision
        String trend = histogram > 0 ? "bullish" : "bearish";
        String context;
        if (positionQty > 0) {
            context = String.format("MACD=%.2f, Signal=%.2f (%s, have position, waiting for crossover to sell)", 
                macdLine, signalLine, trend);
        } else {
            context = String.format("MACD=%.2f, Signal=%.2f (%s, no crossover detected)", 
                macdLine, signalLine, trend);
        }
        return new TradingSignal.Hold(context);
    }

    private double[] calculateMACD(List<Double> prices, int index) {
        // This is a simplified calculation for the specific index
        // In a real optimized system we would maintain state, but for this we recalculate
        // We need enough data before 'index' to calculate EMAs
        
        double fastEma = calculateEMA(prices, FAST_PERIOD, index);
        double slowEma = calculateEMA(prices, SLOW_PERIOD, index);
        double macdLine = fastEma - slowEma;
        
        // To get the signal line, we technically need the MACD history
        // For simplicity in this stateless version, we approximate the signal line 
        // by calculating the EMA of the MACD difference over the last SIGNAL_PERIOD points
        // This is computationally expensive but correct for this architecture
        
        double signalLine = calculateSignalLine(prices, SIGNAL_PERIOD, index);
        
        return new double[]{macdLine, signalLine};
    }

    private double calculateEMA(List<Double> prices, int period, int endIndex) {
        double k = 2.0 / (period + 1);
        double ema = prices.get(endIndex - period + 1); // Start with SMA approximation
        
        for (int i = endIndex - period + 2; i <= endIndex; i++) {
            ema = prices.get(i) * k + ema * (1 - k);
        }
        return ema;
    }
    
    private double calculateSignalLine(List<Double> prices, int period, int endIndex) {
        // Calculate MACD line for the last 'period' points
        double k = 2.0 / (period + 1);
        double signal = 0;
        
        // We need to build up the signal line EMA
        // This is getting complex to do statelessly. 
        // Let's use a simpler approximation: Signal Line is just SMA of MACD for now to avoid O(N^2)
        // Or better, let's just calculate the last few MACDs
        
        double sumMacd = 0;
        for (int i = 0; i < period; i++) {
            double fast = calculateEMA(prices, FAST_PERIOD, endIndex - i);
            double slow = calculateEMA(prices, SLOW_PERIOD, endIndex - i);
            sumMacd += (fast - slow);
        }
        
        return sumMacd / period; // SMA of MACD as proxy for Signal Line
    }
}
