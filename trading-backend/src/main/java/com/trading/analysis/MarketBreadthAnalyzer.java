package com.trading.analysis;

import com.trading.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Market Breadth Filter
 * Analyzes market-wide advance/decline to determine market health
 */
public class MarketBreadthAnalyzer {
    private static final Logger logger = LoggerFactory.getLogger(MarketBreadthAnalyzer.class);
    
    private final Config config;
    
    // Simulated breadth data (in production, would query real data)
    private double currentBreadth = 0.55; // 55% advancing
    
    public MarketBreadthAnalyzer(Config config) {
        this.config = config;
    }
    
    /**
     * Check if market breadth is healthy enough to trade
     * @return true if breadth meets minimum threshold
     */
    public boolean isMarketHealthy() {
        if (!config.isMarketBreadthFilter()) {
            return true; // Filter disabled
        }
        
        double minBreadth = config.getMinBreadthRatio();
        boolean healthy = currentBreadth >= minBreadth;
        
        if (!healthy) {
            logger.warn("⚠️ Market breadth too low: {}% (min: {}%) - Skipping new entries",
                String.format("%.1f", currentBreadth * 100), String.format("%.1f", minBreadth * 100));
        } else {
            logger.debug("✅ Market breadth healthy: {}%", String.format("%.1f", currentBreadth * 100));
        }
        
        return healthy;
    }
    
    /**
     * Get current market breadth ratio
     * @return Ratio of advancing stocks (0.0 to 1.0)
     */
    public double getCurrentBreadth() {
        return currentBreadth;
    }
    
    /**
     * Update breadth with the real advance/decline ratio from MarketRegimeDetector.
     * Called each cycle with regimeAnalysis.breadth().strength() so the filter
     * uses actual 5-day sector breadth instead of random simulation.
     */
    public void updateBreadth(double realBreadth) {
        this.currentBreadth = realBreadth;
        logger.debug("Market breadth updated (real): {}%", String.format("%.1f", currentBreadth * 100));
    }

    /** Legacy no-arg form kept for backward compatibility — no longer used by ProfileManager. */
    public void updateBreadth() {
        logger.debug("updateBreadth() no-arg called — ignoring (real breadth supplied by regime detector)");
    }
}
