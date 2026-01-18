package com.trading.autonomous;

import com.trading.config.Config;
import com.trading.persistence.TradeDatabase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Adaptive Parameter Manager - Automatically tunes trading parameters based on performance.
 * 
 * This system monitors:
 * - Win rates (overall and per symbol)
 * - Kelly Criterion effectiveness
 * - Drawdown levels
 * - Trade frequency
 * - Market volatility
 * 
 * And automatically adjusts:
 * - Kelly Fraction (position sizing aggressiveness)
 * - Max position size limits
 * - Timeframe alignment requirements
 * - Risk per trade
 * 
 * Goal: Fully autonomous operation with minimal manual intervention.
 */
public class AdaptiveParameterManager {
    private static final Logger logger = LoggerFactory.getLogger(AdaptiveParameterManager.class);
    
    private final Config config;
    private final TradeDatabase database;
    
    // Current adaptive parameters (override config)
    private double adaptiveKellyFraction;
    private double adaptiveMaxPositionPercent;
    private int adaptiveMinAligned;
    private boolean adaptiveRequireAlignment;
    
    // Performance tracking
    private Instant lastAdjustment = Instant.now();
    private static final Duration ADJUSTMENT_INTERVAL = Duration.ofHours(24); // Daily adjustments
    private int tradesAtLastAdjustment = 0;
    
    // Thresholds for adjustments
    private static final double HIGH_WIN_RATE = 0.60;
    private static final double GOOD_WIN_RATE = 0.55;
    private static final double ACCEPTABLE_WIN_RATE = 0.50;
    private static final double LOW_WIN_RATE = 0.45;
    private static final double CRITICAL_WIN_RATE = 0.40;
    
    private static final int MIN_TRADES_FOR_ADJUSTMENT = 20;
    
    public AdaptiveParameterManager(Config config, TradeDatabase database) {
        this.config = config;
        this.database = database;
        
        // Initialize with config defaults
        this.adaptiveKellyFraction = config.getPositionSizingKellyFraction();
        this.adaptiveMaxPositionPercent = config.getPositionSizingMaxPercent();
        this.adaptiveMinAligned = config.getMultiTimeframeMinAligned();
        this.adaptiveRequireAlignment = config.isMultiTimeframeRequireAlignment();
        
        logger.info("═══════════════════════════════════════════════════════");
        logger.info("🤖 Adaptive Parameter Manager initialized");
        logger.info("   Initial Kelly Fraction: {}", adaptiveKellyFraction);
        logger.info("   Initial Max Position: {}%", adaptiveMaxPositionPercent * 100);
        logger.info("   Adjustment Interval: {} hours", ADJUSTMENT_INTERVAL.toHours());
        logger.info("═══════════════════════════════════════════════════════");
    }
    
    /**
     * Check if parameters should be adjusted and adjust if needed.
     * Call this periodically (e.g., after each trading cycle).
     */
    public void evaluateAndAdjust() {
        try {
            var stats = database.getTradeStatistics();
            
            // Need minimum trades before adjusting
            if (stats.totalTrades() < MIN_TRADES_FOR_ADJUSTMENT) {
                logger.debug("Adaptive: Only {} trades, need {} for adjustment", 
                    stats.totalTrades(), MIN_TRADES_FOR_ADJUSTMENT);
                return;
            }
            
            // Check if enough time has passed since last adjustment
            Duration timeSinceAdjustment = Duration.between(lastAdjustment, Instant.now());
            if (timeSinceAdjustment.compareTo(ADJUSTMENT_INTERVAL) < 0) {
                return; // Too soon
            }
            
            // Check if we have new trades since last adjustment
            int newTrades = stats.totalTrades() - tradesAtLastAdjustment;
            if (newTrades < 10) {
                logger.debug("Adaptive: Only {} new trades since last adjustment", newTrades);
                return; // Not enough new data
            }
            
            logger.info("🔄 ADAPTIVE TUNING: Evaluating performance...");
            logger.info("   Total Trades: {}", stats.totalTrades());
            logger.info("   Win Rate: {:.1f}%", stats.winRate() * 100);
            logger.info("   Total P&L: ${:.2f}", stats.totalPnL());
            
            // Adjust based on performance
            adjustKellyFraction(stats);
            adjustPositionLimits(stats);
            adjustTimeframeRequirements(stats, newTrades);
            
            // Update tracking
            lastAdjustment = Instant.now();
            tradesAtLastAdjustment = stats.totalTrades();
            
            logger.info("✅ ADAPTIVE TUNING: Complete");
            
        } catch (Exception e) {
            logger.error("Error in adaptive parameter adjustment", e);
        }
    }
    
    /**
     * Adjust Kelly Fraction based on win rate and profitability.
     */
    private void adjustKellyFraction(TradeDatabase.TradeStatistics stats) {
        double oldKelly = adaptiveKellyFraction;
        double winRate = stats.winRate();
        boolean profitable = stats.totalPnL() > 0;
        
        if (winRate >= HIGH_WIN_RATE && profitable) {
            // Excellent performance - increase aggressiveness
            adaptiveKellyFraction = Math.min(0.30, adaptiveKellyFraction + 0.05);
            logger.info("📈 Kelly Fraction INCREASED: {:.2f} → {:.2f} (Win Rate: {:.1f}%)", 
                oldKelly, adaptiveKellyFraction, winRate * 100);
                
        } else if (winRate >= GOOD_WIN_RATE && profitable) {
            // Good performance - slight increase
            adaptiveKellyFraction = Math.min(0.25, adaptiveKellyFraction + 0.02);
            logger.info("📈 Kelly Fraction increased: {:.2f} → {:.2f} (Win Rate: {:.1f}%)", 
                oldKelly, adaptiveKellyFraction, winRate * 100);
                
        } else if (winRate >= ACCEPTABLE_WIN_RATE) {
            // Acceptable - maintain
            logger.info("➡️  Kelly Fraction maintained: {:.2f} (Win Rate: {:.1f}%)", 
                adaptiveKellyFraction, winRate * 100);
                
        } else if (winRate >= LOW_WIN_RATE) {
            // Below target - decrease slightly
            adaptiveKellyFraction = Math.max(0.15, adaptiveKellyFraction - 0.03);
            logger.warn("📉 Kelly Fraction decreased: {:.2f} → {:.2f} (Win Rate: {:.1f}%)", 
                oldKelly, adaptiveKellyFraction, winRate * 100);
                
        } else {
            // Poor performance - significant decrease
            adaptiveKellyFraction = Math.max(0.10, adaptiveKellyFraction - 0.05);
            logger.warn("⚠️  Kelly Fraction DECREASED: {:.2f} → {:.2f} (Win Rate: {:.1f}%)", 
                oldKelly, adaptiveKellyFraction, winRate * 100);
        }
    }
    
    /**
     * Adjust position size limits based on performance.
     */
    private void adjustPositionLimits(TradeDatabase.TradeStatistics stats) {
        double oldMax = adaptiveMaxPositionPercent;
        double winRate = stats.winRate();
        
        if (winRate >= HIGH_WIN_RATE) {
            // High win rate - allow larger positions
            adaptiveMaxPositionPercent = Math.min(0.25, adaptiveMaxPositionPercent + 0.02);
            logger.info("📈 Max Position increased: {:.0f}% → {:.0f}%", 
                oldMax * 100, adaptiveMaxPositionPercent * 100);
                
        } else if (winRate >= ACCEPTABLE_WIN_RATE) {
            // Acceptable - maintain
            logger.debug("Max Position maintained: {:.0f}%", adaptiveMaxPositionPercent * 100);
            
        } else {
            // Low win rate - reduce position sizes
            adaptiveMaxPositionPercent = Math.max(0.10, adaptiveMaxPositionPercent - 0.03);
            logger.warn("📉 Max Position decreased: {:.0f}% → {:.0f}%", 
                oldMax * 100, adaptiveMaxPositionPercent * 100);
        }
    }
    
    /**
     * Adjust timeframe alignment requirements based on trade frequency.
     */
    private void adjustTimeframeRequirements(TradeDatabase.TradeStatistics stats, int newTrades) {
        double tradesPerDay = newTrades / (ADJUSTMENT_INTERVAL.toHours() / 24.0);
        
        if (tradesPerDay < 3) {
            // Too few trades - relax requirements
            if (adaptiveRequireAlignment) {
                adaptiveRequireAlignment = false;
                logger.info("🔓 Timeframe alignment requirement RELAXED (trades/day: {:.1f})", 
                    tradesPerDay);
            } else if (adaptiveMinAligned > 2) {
                adaptiveMinAligned = 2;
                logger.info("🔓 Min aligned timeframes reduced to 2 (trades/day: {:.1f})", 
                    tradesPerDay);
            }
            
        } else if (tradesPerDay > 15 && stats.winRate() < ACCEPTABLE_WIN_RATE) {
            // Too many trades with poor performance - tighten requirements
            if (!adaptiveRequireAlignment) {
                adaptiveRequireAlignment = true;
                logger.info("🔒 Timeframe alignment requirement ENABLED (trades/day: {:.1f}, win rate: {:.1f}%)", 
                    tradesPerDay, stats.winRate() * 100);
            } else if (adaptiveMinAligned < 3) {
                adaptiveMinAligned = 3;
                logger.info("🔒 Min aligned timeframes increased to 3 (trades/day: {:.1f}, win rate: {:.1f}%)", 
                    tradesPerDay, stats.winRate() * 100);
            }
            
        } else {
            logger.debug("Timeframe requirements maintained (trades/day: {:.1f})", tradesPerDay);
        }
    }
    
    /**
     * Get current adaptive Kelly Fraction (overrides config).
     */
    public double getAdaptiveKellyFraction() {
        return adaptiveKellyFraction;
    }
    
    /**
     * Get current adaptive max position percent (overrides config).
     */
    public double getAdaptiveMaxPositionPercent() {
        return adaptiveMaxPositionPercent;
    }
    
    /**
     * Get current adaptive min aligned timeframes (overrides config).
     */
    public int getAdaptiveMinAligned() {
        return adaptiveMinAligned;
    }
    
    /**
     * Get current adaptive require alignment setting (overrides config).
     */
    public boolean isAdaptiveRequireAlignment() {
        return adaptiveRequireAlignment;
    }
    
    /**
     * Get summary of current adaptive parameters.
     */
    public String getSummary() {
        return String.format(
            "Adaptive Parameters: Kelly=%.2f, MaxPos=%.0f%%, MinAlign=%d, RequireAlign=%s",
            adaptiveKellyFraction,
            adaptiveMaxPositionPercent * 100,
            adaptiveMinAligned,
            adaptiveRequireAlignment
        );
    }
    
    /**
     * Force an immediate evaluation (for testing or manual trigger).
     */
    public void forceEvaluation() {
        lastAdjustment = Instant.now().minus(ADJUSTMENT_INTERVAL);
        evaluateAndAdjust();
    }
}
