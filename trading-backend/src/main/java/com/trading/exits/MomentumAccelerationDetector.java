package com.trading.exits;

import com.trading.config.Config;
import com.trading.api.model.Bar;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Momentum Acceleration Detector
 * Detects parabolic price moves and triggers profit-taking
 */
public class MomentumAccelerationDetector {
    private static final Logger logger = LoggerFactory.getLogger(MomentumAccelerationDetector.class);
    
    private final Config config;
    
    public MomentumAccelerationDetector(Config config) {
        this.config = config;
    }
    
    /**
     * Check if price is accelerating (spike detected)
     * @param symbol Stock symbol
     * @param recentBars Recent 5-minute bars
     * @return Percentage to exit (0-100), or 0 if no spike
     */
    public double checkAcceleration(String symbol, List<Bar> recentBars) {
        if (!config.isMomentumAccelerationExits()) {
            return 0;
        }
        
        if (recentBars.size() < 2) {
            return 0;
        }
        
        // Get price change over last 5 minutes
        Bar oldBar = recentBars.get(recentBars.size() - 2);
        Bar newBar = recentBars.get(recentBars.size() - 1);
        
        double priceChange = ((newBar.close() - oldBar.close()) / oldBar.close()) * 100.0;
        double absChange = Math.abs(priceChange);
        
        // Check if exceeds threshold
        if (absChange > config.getAccelerationThreshold()) {
            double exitPercent = config.getAccelerationExitPercent();
            
            logger.info("🚀 {} Momentum Spike: {:.2f}% in 5min! Taking {:.0f}% profit",
                symbol, priceChange, exitPercent);
            
            return exitPercent;
        }
        
        return 0;
    }
    
    /**
     * Calculate momentum over multiple timeframes
     * @return Momentum score (positive = bullish, negative = bearish)
     */
    public double calculateMomentum(List<Bar> bars) {
        if (bars.size() < 10) {
            return 0;
        }
        
        // Calculate momentum over last 5 bars
        double oldPrice = bars.get(bars.size() - 6).close();
        double newPrice = bars.get(bars.size() - 1).close();
        
        return ((newPrice - oldPrice) / oldPrice) * 100.0;
    }
}
