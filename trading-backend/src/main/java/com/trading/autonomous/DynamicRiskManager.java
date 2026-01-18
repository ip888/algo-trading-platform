package com.trading.autonomous;

import com.trading.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Dynamic risk management that adapts position sizing and risk parameters
 * based on recent trading performance.
 */
public class DynamicRiskManager {
    private static final Logger logger = LoggerFactory.getLogger(DynamicRiskManager.class);
    
    private final Config config;
    
    // Performance tracking
    private final AtomicInteger recentWins = new AtomicInteger(0);
    private final AtomicInteger recentLosses = new AtomicInteger(0);
    private double basePositionSizeMultiplier = 1.0;
    
    // Thresholds for adaptation
    private static final double HIGH_WIN_RATE_THRESHOLD = 0.75;  // 75%
    private static final double LOW_WIN_RATE_THRESHOLD = 0.60;   // 60%
    private static final double POSITION_SIZE_INCREASE = 1.20;   // +20%
    private static final double POSITION_SIZE_DECREASE = 0.80;   // -20%
    
    public DynamicRiskManager(Config config) {
        this.config = config;
        logger.info("🧠 DynamicRiskManager initialized - Adaptive risk enabled");
    }
    
    /**
     * Record trade outcome and update win rate.
     */
    public void recordTrade(boolean isWin) {
        if (isWin) {
            recentWins.incrementAndGet();
        } else {
            recentLosses.incrementAndGet();
        }
        
        // Adapt position sizing based on performance
        adaptPositionSizing();
    }
    
    /**
     * Get current win rate from last 100 trades.
     */
    public double getWinRate() {
        int wins = recentWins.get();
        int losses = recentLosses.get();
        int total = wins + losses;
        
        if (total == 0) {
            return 0.5; // Default 50% if no trades yet
        }
        
        return (double) wins / total;
    }
    
    /**
     * Adapt position sizing based on win rate.
     */
    private void adaptPositionSizing() {
        double winRate = getWinRate();
        double oldMultiplier = basePositionSizeMultiplier;
        
        if (winRate > HIGH_WIN_RATE_THRESHOLD) {
            // Winning streak - increase position size
            basePositionSizeMultiplier = POSITION_SIZE_INCREASE;
            if (oldMultiplier != basePositionSizeMultiplier) {
                logger.info("🧠 ADAPTATION: Win rate {}% - INCREASING position size by 20%",
                    String.format("%.1f", winRate * 100));
            }
        } else if (winRate < LOW_WIN_RATE_THRESHOLD) {
            // Losing streak - decrease position size
            basePositionSizeMultiplier = POSITION_SIZE_DECREASE;
            if (oldMultiplier != basePositionSizeMultiplier) {
                logger.warn("🧠 ADAPTATION: Win rate {}% - DECREASING position size by 20%",
                    String.format("%.1f", winRate * 100));
            }
        } else {
            // Normal performance - standard sizing
            basePositionSizeMultiplier = 1.0;
        }
    }
    
    /**
     * Get adjusted position size multiplier.
     */
    public double getPositionSizeMultiplier() {
        return basePositionSizeMultiplier;
    }
    
    /**
     * Calculate adaptive stop-loss based on volatility.
     */
    public double getAdaptiveStopLoss(double baseStopLoss, double currentVix) {
        // Tighten stops in high volatility
        if (currentVix > 30) {
            double tightened = baseStopLoss * 0.75; // 25% tighter
            logger.debug("🧠 ADAPTATION: High VIX ({}) - tightening stop-loss to {}%",
                String.format("%.1f", currentVix), String.format("%.2f", tightened));
            return tightened;
        }
        
        // Widen stops in low volatility
        if (currentVix < 15) {
            double widened = baseStopLoss * 1.25; // 25% wider
            logger.debug("🧠 ADAPTATION: Low VIX ({}) - widening stop-loss to {}%",
                String.format("%.1f", currentVix), String.format("%.2f", widened));
            return widened;
        }
        
        return baseStopLoss;
    }
    
    /**
     * Calculate adaptive take-profit based on trend strength.
     */
    public double getAdaptiveTakeProfit(double baseTakeProfit, boolean strongTrend) {
        if (strongTrend) {
            double widened = baseTakeProfit * 1.5; // 50% wider to capture more profit
            logger.debug("🧠 ADAPTATION: Strong trend detected - widening take-profit to {}%",
                String.format("%.2f", widened));
            return widened;
        }
        
        return baseTakeProfit;
    }
    
    /**
     * Reset performance counters (call weekly).
     */
    public void resetCounters() {
        logger.info("🧠 ADAPTATION: Resetting performance counters");
        logger.info("   Final win rate: {}%", String.format("%.1f", getWinRate() * 100));
        recentWins.set(0);
        recentLosses.set(0);
        basePositionSizeMultiplier = 1.0;
    }
    
    /**
     * Get adaptation statistics.
     */
    public String getStats() {
        return String.format("Win Rate: %.1f%%, Position Multiplier: %.2fx",
            getWinRate() * 100, basePositionSizeMultiplier);
    }
}
