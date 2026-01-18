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
            logger.warn("⚠️ Market breadth too low: {:.1f}% (min: {:.1f}%) - Skipping new entries",
                currentBreadth * 100, minBreadth * 100);
        } else {
            logger.debug("✅ Market breadth healthy: {:.1f}%", currentBreadth * 100);
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
     * Update breadth data (called periodically)
     * In production, this would query real market data
     */
    public void updateBreadth() {
        // Simulate breadth fluctuation
        // In production: query NYSE/NASDAQ advance/decline data
        currentBreadth = 0.50 + (Math.random() * 0.3); // 50-80%
        
        logger.debug("Market breadth updated: {:.1f}%", currentBreadth * 100);
    }
}
