package com.trading.tracking;

import com.trading.config.Config;
import com.trading.websocket.TradingWebSocketHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Tracks missed trading opportunities for analysis and optimization.
 * Feature #19: Opportunity Tracking
 */
public class OpportunityTracker {
    private static final Logger logger = LoggerFactory.getLogger(OpportunityTracker.class);
    
    private final Config config;
    private final List<MissedOpportunity> missedOpportunities = new ArrayList<>();
    
    public OpportunityTracker(Config config) {
        this.config = config;
    }
    
    /**
     * Record a missed trading opportunity.
     */
    public void recordMissedOpportunity(String symbol, 
                                       double confidence,
                                       String reason,
                                       double estimatedProfit) {
        if (!config.isTrackMissedOpportunities()) {
            return;
        }
        
        var missed = new MissedOpportunity(
            symbol, confidence, reason, 
            estimatedProfit, Instant.now()
        );
        missedOpportunities.add(missed);
        
        // Broadcast to dashboard
        TradingWebSocketHandler.broadcastActivity(
            String.format("⚠️ Missed %s (%.0f%% confidence): %s", 
                symbol, confidence * 100, reason),
            "WARNING"
        );
        
        logger.warn("📊 Missed opportunity: {} ({}% confidence) - {}", 
            symbol, String.format("%.0f", confidence * 100), reason);
    }
    
    /**
     * Get total estimated profit from missed opportunities.
     */
    public double getTotalMissedProfit() {
        return missedOpportunities.stream()
            .mapToDouble(MissedOpportunity::estimatedProfit)
            .sum();
    }
    
    /**
     * Get count of missed opportunities.
     */
    public int getMissedCount() {
        return missedOpportunities.size();
    }
    
    /**
     * Get missed opportunities for a specific symbol.
     */
    public List<MissedOpportunity> getMissedForSymbol(String symbol) {
        return missedOpportunities.stream()
            .filter(m -> m.symbol().equals(symbol))
            .toList();
    }
    
    /**
     * Clear missed opportunities (call daily).
     */
    public void clearDaily() {
        if (!missedOpportunities.isEmpty()) {
            logger.info("📊 Daily missed opportunities: {} total, ${} estimated profit lost",
                missedOpportunities.size(),
                String.format("%.2f", getTotalMissedProfit()));
        }
        missedOpportunities.clear();
    }
    
    /**
     * Represents a missed trading opportunity.
     */
    public record MissedOpportunity(
        String symbol,
        double confidence,
        String reason,
        double estimatedProfit,
        Instant timestamp
    ) {}
}
