package com.trading.analysis;

import com.trading.config.Config;
import com.trading.api.model.Bar;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Volume Profile Analyzer
 * Identifies high-volume price levels for better entry timing
 */
public class VolumeProfileAnalyzer {
    private static final Logger logger = LoggerFactory.getLogger(VolumeProfileAnalyzer.class);
    
    private final Config config;
    
    public VolumeProfileAnalyzer(Config config) {
        this.config = config;
    }
    
    /**
     * Check if current price is near a high-volume support/resistance level
     * @param symbol Stock symbol
     * @param currentPrice Current price
     * @param bars Recent price bars
     * @return true if price is near high-volume node
     */
    public boolean isGoodEntryPrice(String symbol, double currentPrice, List<Bar> bars) {
        if (!config.isVolumeProfileEnabled() || bars.size() < 20) {
            return true; // Filter disabled or insufficient data
        }
        
        var volumeNodes = calculateVolumeNodes(bars);
        double threshold = config.getVolumeNodeThreshold();
        
        // Check if current price is within threshold of any high-volume node
        for (VolumeNode node : volumeNodes) {
            double distance = Math.abs(currentPrice - node.price) / currentPrice;
            
            if (distance < threshold) {
                logger.info("✅ {} Good entry price ${:.2f} near volume node ${:.2f} (vol: {})",
                    symbol, currentPrice, node.price, node.volume);
                return true;
            }
        }
        
        logger.debug("⚠️ {} Price ${:.2f} not near volume support", symbol, currentPrice);
        return false; // Not near support
    }
    
    /**
     * Calculate high-volume price nodes
     */
    private List<VolumeNode> calculateVolumeNodes(List<Bar> bars) {
        List<VolumeNode> nodes = new ArrayList<>();
        
        // Group prices into buckets and sum volume
        // Simplified: just find top 3 volume bars
        bars.stream()
            .sorted((a, b) -> Long.compare(b.volume(), a.volume()))
            .limit(3)
            .forEach(bar -> nodes.add(new VolumeNode(bar.close(), bar.volume())));
        
        return nodes;
    }
    
    /**
     * Volume node data class
     */
    private static class VolumeNode {
        final double price;
        final long volume;
        
        VolumeNode(double price, long volume) {
            this.price = price;
            this.volume = volume;
        }
    }
}
