package com.trading.risk;

import com.trading.config.Config;
import com.trading.persistence.TradeDatabase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Feature #24: Adaptive Position Limits
 * Allow more positions when win rate is high.
 */
public class AdaptivePositionLimitManager {
    private static final Logger logger = LoggerFactory.getLogger(AdaptivePositionLimitManager.class);
    
    private final Config config;
    private final TradeDatabase database;
    
    private double cachedWinRate = 0.50; // Default 50%
    private long lastUpdateTime = 0;
    private static final long UPDATE_INTERVAL_MS = 300000; // 5 minutes
    
    public AdaptivePositionLimitManager(Config config, TradeDatabase database) {
        this.config = config;
        this.database = database;
    }
    
    /**
     * Get current position limit based on win rate.
     */
    public int getCurrentPositionLimit() {
        if (!config.isAdaptivePositionLimitsEnabled()) {
            return config.getMaxPositionsAtOnce();
        }
        
        // Update win rate periodically
        updateWinRateIfNeeded();
        
        // Use config method that calculates limit based on win rate
        int limit = config.getAdaptivePositionLimit(cachedWinRate);
        
        logger.debug("Adaptive position limit: {} (win rate: {}%)", 
            limit, String.format("%.1f", cachedWinRate * 100));
        
        return limit;
    }
    
    /**
     * Update win rate from database if needed.
     */
    private void updateWinRateIfNeeded() {
        long now = System.currentTimeMillis();
        
        if (now - lastUpdateTime > UPDATE_INTERVAL_MS) {
            try {
                var stats = database.getTradeStatistics();
                cachedWinRate = stats.winRate();
                lastUpdateTime = now;
                
                logger.debug("Updated win rate: {}% (from {} trades)",
                    String.format("%.1f", cachedWinRate * 100),
                    stats.totalTrades());
            } catch (Exception e) {
                logger.warn("Failed to update win rate, using cached value", e);
            }
        }
    }
    
    /**
     * Check if we can open a new position.
     */
    public boolean canOpenNewPosition(int currentPositionCount) {
        int limit = getCurrentPositionLimit();
        boolean canOpen = currentPositionCount < limit;
        
        if (!canOpen) {
            logger.info("Position limit reached: {}/{} (win rate: {}%)",
                currentPositionCount, limit, 
                String.format("%.1f", cachedWinRate * 100));
        }
        
        return canOpen;
    }
    
    /**
     * Get current win rate.
     */
    public double getCurrentWinRate() {
        updateWinRateIfNeeded();
        return cachedWinRate;
    }
}
