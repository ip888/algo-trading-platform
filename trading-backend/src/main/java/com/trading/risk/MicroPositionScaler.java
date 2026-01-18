package com.trading.risk;

import com.trading.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Feature #18: Micro-Position Scaling
 * Start small, scale up winners.
 */
public class MicroPositionScaler {
    private static final Logger logger = LoggerFactory.getLogger(MicroPositionScaler.class);
    
    private final Config config;
    
    public MicroPositionScaler(Config config) {
        this.config = config;
    }
    
    /**
     * Apply micro-scaling to position size.
     * 
     * @param baseSize The calculated position size
     * @param position Existing position (null for new entry)
     * @param currentPrice Current market price
     * @return Scaled position size
     */
    public double applyMicroScaling(double baseSize, TradePosition position, double currentPrice) {
        if (!config.isMicroPositionScalingEnabled()) {
            return baseSize;
        }
        
        // Initial entry: Start with 50% of calculated size
        if (position == null) {
            double initialSize = baseSize * config.getMicroScalingInitialPercent();
            logger.debug("Micro-scaling: Initial entry at 50% ({} shares)", 
                String.format("%.2f", initialSize));
            return initialSize;
        }
        
        // Calculate current profit
        double profitPercent = position.getProfitPercent(currentPrice);
        
        // Level 2: At +1.0% profit, add final 25% (total 100%)
        if (profitPercent >= config.getMicroScalingLevel2Profit()) {
            double remainingSize = baseSize * 0.25; // Final 25%
            if (remainingSize > 0.001) {
                logger.info("Micro-scaling: {} adding final 25% at +{}% profit ({} shares)",
                    position.symbol(),
                    String.format("%.1f", profitPercent * 100),
                    String.format("%.2f", remainingSize));
                return remainingSize;
            }
        }
        // Level 1: At +0.5% profit, add 25% more (total 75%)
        else if (profitPercent >= config.getMicroScalingLevel1Profit()) {
            double scaleUpSize = baseSize * 0.25; // Second 25%
            if (scaleUpSize > 0.001) {
                logger.info("Micro-scaling: {} adding 25% at +{}% profit ({} shares)",
                    position.symbol(),
                    String.format("%.1f", profitPercent * 100),
                    String.format("%.2f", scaleUpSize));
                return scaleUpSize;
            }
        }
        
        return 0; // No more scaling needed
    }
    
    /**
     * Check if position needs scaling up.
     */
    public boolean needsScaling(TradePosition position, double currentPrice) {
        if (!config.isMicroPositionScalingEnabled()) {
            return false;
        }
        
        double profitPercent = position.getProfitPercent(currentPrice);
        
        // Check if we've hit scaling levels
        return profitPercent >= config.getMicroScalingLevel1Profit() ||
               profitPercent >= config.getMicroScalingLevel2Profit();
    }
}
