package com.trading.sizing;

import com.trading.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Adaptive Position Sizer
 * Adjusts position size based on ML confidence, volatility, and market conditions
 */
public class AdaptivePositionSizer {
    private static final Logger logger = LoggerFactory.getLogger(AdaptivePositionSizer.class);
    
    private final Config config;
    
    public AdaptivePositionSizer(Config config) {
        this.config = config;
    }
    
    /**
     * Calculate adaptive position size
     * @param equity Total account equity
     * @param mlScore ML entry score (0-100)
     * @param vix Current VIX level
     * @return Position size in dollars
     */
    public double calculateSize(double equity, double mlScore, double vix) {
        if (!config.isAdaptiveSizingEnabled()) {
            // Fall back to base size
            return equity * (config.getAdaptiveSizeBase() / 100.0);
        }
        
        // Start with base size
        double sizePercent = config.getAdaptiveSizeBase();
        
        // Adjust based on ML confidence
        if (mlScore >= config.getAdaptiveSizeHighConfidence()) {
            sizePercent = config.getAdaptiveSizeMax(); // 8%
            logger.debug("High confidence ({}), using max size: {}%", String.format("%.1f", mlScore), String.format("%.1f", sizePercent));
        } else if (mlScore >= 75) {
            sizePercent = 6.0; // Medium-high confidence
            logger.debug("Medium-high confidence ({}), using {}%", String.format("%.1f", mlScore), String.format("%.1f", sizePercent));
        } else if (mlScore < 70) {
            sizePercent = config.getAdaptiveSizeMin(); // 2%
            logger.debug("Low confidence ({}), using min size: {}%", String.format("%.1f", mlScore), String.format("%.1f", sizePercent));
        }
        
        // Reduce size in high volatility
        if (vix > config.getAdaptiveSizeVixThreshold()) {
            sizePercent *= 0.5; // Cut in half
            logger.info("⚠️ High VIX ({}), reducing size to {}%", String.format("%.1f", vix), String.format("%.1f", sizePercent));
        }
        
        // Ensure within bounds
        sizePercent = Math.max(config.getAdaptiveSizeMin(), 
                              Math.min(config.getAdaptiveSizeMax(), sizePercent));
        
        double dollarSize = equity * (sizePercent / 100.0);
        
        logger.debug("Adaptive size: {}% (${}) - ML:{} VIX:{}",
            String.format("%.1f", sizePercent), String.format("%.2f", dollarSize), String.format("%.1f", mlScore), String.format("%.1f", vix));
        
        return dollarSize;
    }
    
    /**
     * Adjust size for correlation with existing positions
     * @param baseSize Initial calculated size
     * @param maxCorrelation Highest correlation with existing positions
     * @return Adjusted size
     */
    public double adjustForCorrelation(double baseSize, double maxCorrelation) {
        if (!config.isCorrelationSizingEnabled()) {
            return baseSize;
        }
        
        if (maxCorrelation > config.getCorrelationThreshold()) {
            double reduction = config.getCorrelationSizeReduction();
            double adjustedSize = baseSize * reduction;
            
            logger.info("📊 High correlation ({}), reducing size by {}%: ${} → ${}",
                String.format("%.2f", maxCorrelation), String.format("%.0f", (1 - reduction) * 100), String.format("%.2f", baseSize), String.format("%.2f", adjustedSize));
            
            return adjustedSize;
        }
        
        return baseSize;
    }
}
