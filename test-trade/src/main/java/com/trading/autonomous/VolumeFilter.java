package com.trading.autonomous;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Validates volume before entering trades to ensure sufficient liquidity.
 * Filters out low-volume setups that may have poor execution.
 */
public class VolumeFilter {
    private static final Logger logger = LoggerFactory.getLogger(VolumeFilter.class);
    
    // Minimum volume multiplier vs average
    private static final double MIN_VOLUME_MULTIPLIER = 0.5; // 50% of average
    private static final double HIGH_VOLUME_MULTIPLIER = 2.0; // 2x average = high confidence
    
    public VolumeFilter() {
        logger.info("🧠 VolumeFilter initialized - Volume confirmation enabled");
    }
    
    /**
     * Check if current volume is sufficient for trading.
     * 
     * @param currentVolume Current bar volume
     * @param averageVolume 20-day average volume
     * @return true if volume is acceptable
     */
    public boolean isVolumeSufficient(long currentVolume, long averageVolume) {
        if (averageVolume == 0) {
            logger.warn("⚠️ VOLUME: No average volume data available");
            return true; // Allow trade if no data
        }
        
        double volumeRatio = (double) currentVolume / averageVolume;
        
        if (volumeRatio < MIN_VOLUME_MULTIPLIER) {
            logger.warn("🧠 VOLUME: Insufficient volume - {}x average (min: {}x)",
                String.format("%.2f", volumeRatio), MIN_VOLUME_MULTIPLIER);
            return false;
        }
        
        if (volumeRatio > HIGH_VOLUME_MULTIPLIER) {
            logger.info("🧠 VOLUME: High volume detected - {}x average (high confidence)",
                String.format("%.2f", volumeRatio));
        }
        
        return true;
    }
    
    /**
     * Get volume confidence score (0-100).
     */
    public int getVolumeConfidence(long currentVolume, long averageVolume) {
        if (averageVolume == 0) {
            return 50; // Neutral if no data
        }
        
        double volumeRatio = (double) currentVolume / averageVolume;
        
        // Score based on volume ratio
        if (volumeRatio < MIN_VOLUME_MULTIPLIER) {
            return 0; // No confidence
        } else if (volumeRatio > HIGH_VOLUME_MULTIPLIER) {
            return 100; // Maximum confidence
        } else {
            // Linear scale between min and high
            return (int) ((volumeRatio - MIN_VOLUME_MULTIPLIER) / 
                (HIGH_VOLUME_MULTIPLIER - MIN_VOLUME_MULTIPLIER) * 100);
        }
    }
}
