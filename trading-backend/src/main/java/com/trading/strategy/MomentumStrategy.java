package com.trading.strategy;

import com.trading.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Momentum Strategy for Strong Uptrend Assets (e.g., Gold, Tech leaders).
 * 
 * IMPROVED v2.1 - CONFIGURABLE parameters via config.properties:
 * - RSI ranges for buy/sell
 * - Momentum thresholds
 * - ATR volatility limits
 * - SMA periods
 * 
 * This strategy is designed for assets showing strong bullish momentum:
 * - Buys when RSI is in "sweet spot" (45-60) with RISING RSI and positive momentum
 * - Sells when RSI becomes overbought (>72) or momentum fades
 * 
 * Unlike Mean Reversion, this BUYS INTO STRENGTH rather than weakness.
 * Ideal for trending assets like Gold during inflationary periods.
 */
public final class MomentumStrategy implements TradingStrategy {
    private static final Logger logger = LoggerFactory.getLogger(MomentumStrategy.class);
    
    // Fixed periods (not typically adjusted)
    private static final int RSI_PERIOD = 14;
    private static final int MOMENTUM_PERIOD = 10;
    private static final int SMA_PERIOD = 20;
    private static final int SMA_PERIOD_FAST = 9;
    private static final int ATR_PERIOD = 14;
    
    // CONFIGURABLE parameters (read from config or use defaults)
    private final double rsiBuyMin;
    private final double rsiBuyMax;
    private final double rsiSellThreshold;
    private final double momentumMin;
    private final int momentumConfirmationBars;
    private final double maxAtrPercent;
    private final double maxAboveSmaPercent;
    
    /**
     * Default constructor - uses default values
     */
    public MomentumStrategy() {
        this.rsiBuyMin = 45.0;
        this.rsiBuyMax = 60.0;
        this.rsiSellThreshold = 72.0;
        this.momentumMin = 0.008;  // 0.8%
        this.momentumConfirmationBars = 3;
        this.maxAtrPercent = 3.0;
        this.maxAboveSmaPercent = 2.0;
    }
    
    /**
     * Constructor with Config - reads all parameters from config.properties
     */
    public MomentumStrategy(Config config) {
        this.rsiBuyMin = config.getMomentumRsiBuyMin();
        this.rsiBuyMax = config.getMomentumRsiBuyMax();
        this.rsiSellThreshold = config.getMomentumRsiSellThreshold();
        this.momentumMin = config.getMomentumMinPercent() / 100.0;  // Config is in %, code uses decimal
        this.momentumConfirmationBars = config.getMomentumConfirmationBars();
        this.maxAtrPercent = config.getMomentumMaxAtrPercent();
        this.maxAboveSmaPercent = config.getMomentumMaxAboveSmaPercent();
        
        logger.info("MomentumStrategy initialized with config: RSI({}-{}), Sell@{}, Mom>{}%, ConfirmBars={}, MaxATR={}%, MaxAboveSMA={}%",
            rsiBuyMin, rsiBuyMax, rsiSellThreshold, momentumMin * 100, 
            momentumConfirmationBars, maxAtrPercent, maxAboveSmaPercent);
    }

    @Override
    public TradingSignal evaluate(String symbol, double currentPrice, double positionQty) {
        return new TradingSignal.Hold("Momentum requires history");
    }

    public TradingSignal evaluateWithHistory(String symbol, double currentPrice, double positionQty, List<Double> history) {
        if (history.size() < Math.max(RSI_PERIOD + 1, SMA_PERIOD + 5)) {
            return new TradingSignal.Hold("Insufficient history for Momentum Strategy");
        }

        double rsi = calculateRSI(history, RSI_PERIOD);
        double rsiPrev = calculateRSI(history.subList(0, history.size() - 1), RSI_PERIOD);
        boolean rsiRising = rsi > rsiPrev;
        
        double momentum = calculateMomentum(history, MOMENTUM_PERIOD);
        boolean momentumConsistent = isMomentumConsistent(history, momentumConfirmationBars);
        
        double sma20 = calculateSMA(history, SMA_PERIOD);
        double sma9 = calculateSMA(history, SMA_PERIOD_FAST);
        boolean priceAboveSMA = currentPrice > sma20;
        boolean fastAboveSlow = sma9 > sma20; // SMA9 > SMA20 = bullish crossover
        double percentAboveSMA = ((currentPrice - sma20) / sma20) * 100;
        
        // Calculate ATR for volatility check
        double atrPercent = calculateATRPercent(history, ATR_PERIOD, currentPrice);
        boolean volatilityOK = atrPercent < maxAtrPercent;

        logger.debug("{} Momentum: Price=${} RSI={:.1f}({}) Mom={:.2f}%({}) AboveSMA={:.2f}% ATR={:.2f}%", 
            symbol, 
            String.format("%.2f", currentPrice), 
            rsi, rsiRising ? "↑" : "↓",
            momentum * 100, momentumConsistent ? "consistent" : "weak",
            percentAboveSMA,
            atrPercent);

        // EXIT: RSI overbought - take profit (exit earlier at 72 instead of 75)
        if (positionQty > 0 && rsi >= rsiSellThreshold) {
            String reason = String.format("Momentum Exit: RSI overbought (%.1f >= %.1f)", rsi, rsiSellThreshold);
            logger.info("{}: SELL signal - {}", symbol, reason);
            return new TradingSignal.Sell(reason);
        }
        
        // EXIT: Momentum turned negative while in position
        if (positionQty > 0 && momentum < -0.003) { // Tighter exit at -0.3%
            String reason = String.format("Momentum Exit: Fading momentum (%.2f%%)", momentum * 100);
            logger.info("{}: SELL signal - {}", symbol, reason);
            return new TradingSignal.Sell(reason);
        }
        
        // EXIT: RSI falling sharply (trend reversal)
        if (positionQty > 0 && rsi < rsiPrev - 5) { // RSI dropped 5+ points
            String reason = String.format("Momentum Exit: RSI falling sharply (%.1f → %.1f)", rsiPrev, rsi);
            logger.info("{}: SELL signal - {}", symbol, reason);
            return new TradingSignal.Sell(reason);
        }

        // ENTRY: Stricter criteria
        if (positionQty == 0) {
            // Skip high volatility periods
            if (!volatilityOK) {
                logger.debug("{}: Skipping - ATR too high ({:.2f}% > {:.1f}%)", 
                    symbol, atrPercent, maxAtrPercent);
                return new TradingSignal.Hold(String.format("Volatility too high: ATR=%.2f%%", atrPercent));
            }
            
            boolean rsiInSweetSpot = rsi >= rsiBuyMin && rsi <= rsiBuyMax;
            boolean hasPositiveMomentum = momentum >= momentumMin;
            
            // IMPROVED ENTRY: Require ALL conditions
            // 1. RSI in sweet spot AND rising
            // 2. Momentum positive AND consistent
            // 3. Price above SMA20
            // 4. SMA9 > SMA20 (trend confirmation)
            // 5. Not too far above SMA (avoid chasing)
            if (rsiInSweetSpot && rsiRising && 
                hasPositiveMomentum && momentumConsistent && 
                priceAboveSMA && fastAboveSlow &&
                percentAboveSMA < maxAboveSmaPercent) { // Don't buy if already too far above SMA
                
                String reason = String.format("Momentum Buy: RSI=%.1f↑, Mom=+%.2f%% (consistent), %.1f%% above SMA", 
                    rsi, momentum * 100, percentAboveSMA);
                logger.info("{}: BUY signal - {}", symbol, reason);
                return new TradingSignal.Buy(reason);
            }
            
            // Log why we're not entering
            if (rsiInSweetSpot && hasPositiveMomentum) {
                if (!rsiRising) {
                    logger.debug("{}: No entry - RSI not rising ({}→{})", symbol, rsiPrev, rsi);
                } else if (!momentumConsistent) {
                    logger.debug("{}: No entry - Momentum not consistent", symbol);
                } else if (!fastAboveSlow) {
                    logger.debug("{}: No entry - SMA9 < SMA20 (bearish)", symbol);
                } else if (percentAboveSMA >= maxAboveSmaPercent) {
                    logger.debug("{}: No entry - Too far above SMA ({}%)", symbol, percentAboveSMA);
                }
            }
        }

        return new TradingSignal.Hold(String.format("Waiting: RSI=%.1f%s, Mom=%.2f%%", 
            rsi, rsiRising ? "↑" : "↓", momentum * 100));
    }
    
    /**
     * Check if momentum has been consistently positive for N bars
     */
    private boolean isMomentumConsistent(List<Double> history, int bars) {
        if (history.size() < MOMENTUM_PERIOD + bars) return false;
        
        for (int i = 0; i < bars; i++) {
            int endIdx = history.size() - i;
            int startIdx = endIdx - MOMENTUM_PERIOD;
            if (startIdx < 0) return false;
            
            double current = history.get(endIdx - 1);
            double past = history.get(startIdx);
            double momentum = (current - past) / past;
            
            if (momentum < 0.002) return false; // Each bar must show >0.2% momentum
        }
        return true;
    }
    
    /**
     * Calculate ATR as percentage of price (volatility measure)
     */
    private double calculateATRPercent(List<Double> history, int period, double currentPrice) {
        if (history.size() < period + 1) return 0.0;
        
        double sumTR = 0;
        for (int i = history.size() - period; i < history.size(); i++) {
            double high = history.get(i);  // Approximation since we don't have OHLC
            double low = history.get(i) * 0.98;  // Assume 2% intraday range
            double prevClose = history.get(i - 1);
            
            double tr = Math.max(high - low, 
                        Math.max(Math.abs(high - prevClose), Math.abs(low - prevClose)));
            sumTR += tr;
        }
        
        double atr = sumTR / period;
        return (atr / currentPrice) * 100;
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
