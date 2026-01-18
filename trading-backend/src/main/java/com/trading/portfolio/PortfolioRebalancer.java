package com.trading.portfolio;

import com.trading.analysis.CorrelationCalculator;
import com.trading.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Portfolio Rebalancer - Maintains optimal portfolio allocation.
 * 
 * Features:
 * - Detect allocation drift from targets
 * - Rebalance based on time or drift thresholds
 * - Manage sector exposure limits
 * - Reduce correlation when portfolio becomes concentrated
 * - Generate rebalancing orders
 * 
 * Goal: Maintain diversified, balanced portfolio aligned with strategy.
 */
public class PortfolioRebalancer {
    private static final Logger logger = LoggerFactory.getLogger(PortfolioRebalancer.class);
    
    private final Config config;
    private final CorrelationCalculator correlationCalculator;
    
    private Instant lastRebalance = Instant.now();
    private static final Duration REBALANCE_INTERVAL = Duration.ofDays(7); // Weekly rebalancing
    private static final double DRIFT_THRESHOLD = 0.15; // 15% drift triggers rebalance
    
    public PortfolioRebalancer(Config config, CorrelationCalculator correlationCalculator) {
        this.config = config;
        this.correlationCalculator = correlationCalculator;
        logger.info("PortfolioRebalancer initialized (interval: {} days, drift threshold: {}%)",
            REBALANCE_INTERVAL.toDays(), DRIFT_THRESHOLD * 100);
    }
    
    /**
     * Allocation drift record
     */
    public record AllocationDrift(
        String symbol,
        double currentAllocation,
        double targetAllocation,
        double drift,
        boolean needsRebalance
    ) {}
    
    /**
     * Rebalancing recommendation
     */
    public record RebalanceRecommendation(
        boolean shouldRebalance,
        String reason,
        List<AllocationDrift> drifts,
        Map<String, Double> targetAllocations,
        double maxDrift
    ) {}
    
    /**
     * Check if portfolio needs rebalancing.
     */
    public RebalanceRecommendation checkRebalanceNeeded(
            Map<String, Double> currentPositions,
            double totalEquity) {
        
        // Calculate current allocations
        Map<String, Double> currentAllocations = new HashMap<>();
        for (var entry : currentPositions.entrySet()) {
            double allocation = entry.getValue() / totalEquity;
            currentAllocations.put(entry.getKey(), allocation);
        }
        
        // Target: equal weight allocation
        int numPositions = currentPositions.size();
        double targetAllocation = numPositions > 0 ? 1.0 / numPositions : 0.0;
        
        // Calculate drifts
        List<AllocationDrift> drifts = new ArrayList<>();
        double maxDrift = 0.0;
        
        for (var entry : currentAllocations.entrySet()) {
            String symbol = entry.getKey();
            double current = entry.getValue();
            double drift = Math.abs(current - targetAllocation);
            
            drifts.add(new AllocationDrift(
                symbol, current, targetAllocation, drift, drift > DRIFT_THRESHOLD
            ));
            
            maxDrift = Math.max(maxDrift, drift);
        }
        
        // Check time-based rebalancing
        Duration timeSinceRebalance = Duration.between(lastRebalance, Instant.now());
        boolean timeToRebalance = timeSinceRebalance.compareTo(REBALANCE_INTERVAL) >= 0;
        
        // Check drift-based rebalancing
        boolean driftExceeded = maxDrift > DRIFT_THRESHOLD;
        
        // Determine if rebalancing is needed
        boolean shouldRebalance = timeToRebalance || driftExceeded;
        
        String reason;
        if (driftExceeded) {
            reason = String.format("Allocation drift exceeded threshold (%.1f%% > %.1f%%)", 
                maxDrift * 100, DRIFT_THRESHOLD * 100);
        } else if (timeToRebalance) {
            reason = String.format("Scheduled rebalance (%d days since last)", 
                timeSinceRebalance.toDays());
        } else {
            reason = "No rebalancing needed";
        }
        
        if (shouldRebalance) {
            logger.info("🔄 REBALANCING RECOMMENDED: {}", reason);
            logger.info("   Max drift: {:.1f}%, Target allocation: {:.1f}% per position",
                maxDrift * 100, targetAllocation * 100);
        }
        
        // Create target allocations map
        Map<String, Double> targetAllocations = new HashMap<>();
        for (String symbol : currentPositions.keySet()) {
            targetAllocations.put(symbol, targetAllocation);
        }
        
        return new RebalanceRecommendation(
            shouldRebalance,
            reason,
            drifts,
            targetAllocations,
            maxDrift
        );
    }
    
    /**
     * Generate rebalancing orders to achieve target allocations.
     */
    public Map<String, Double> generateRebalanceOrders(
            Map<String, Double> currentPositions,
            Map<String, Double> currentPrices,
            double totalEquity) {
        
        Map<String, Double> orders = new HashMap<>();
        
        int numPositions = currentPositions.size();
        double targetValue = totalEquity / numPositions;
        
        for (var entry : currentPositions.entrySet()) {
            String symbol = entry.getKey();
            double currentValue = entry.getValue();
            double currentPrice = currentPrices.getOrDefault(symbol, 0.0);
            
            if (currentPrice == 0.0) {
                continue; // Skip if no price available
            }
            
            double targetShares = targetValue / currentPrice;
            double currentShares = currentValue / currentPrice;
            double sharesToTrade = targetShares - currentShares;
            
            // Only create order if change is significant (> 1% of position)
            if (Math.abs(sharesToTrade) > currentShares * 0.01) {
                orders.put(symbol, sharesToTrade);
                
                logger.info("   {}: {} {} shares (current: {:.2f}, target: {:.2f})",
                    symbol,
                    sharesToTrade > 0 ? "BUY" : "SELL",
                    Math.abs(sharesToTrade),
                    currentShares,
                    targetShares);
            }
        }
        
        return orders;
    }
    
    /**
     * Mark rebalancing as complete.
     */
    public void markRebalanceComplete() {
        lastRebalance = Instant.now();
        logger.info("✅ Rebalancing marked complete");
    }
    
    /**
     * Check sector exposure and suggest reductions if needed.
     */
    public List<String> checkSectorExposure(Map<String, String> symbolSectors,
                                            Map<String, Double> currentAllocations) {
        List<String> warnings = new ArrayList<>();
        
        // Calculate sector allocations
        Map<String, Double> sectorAllocations = new HashMap<>();
        for (var entry : currentAllocations.entrySet()) {
            String symbol = entry.getKey();
            String sector = symbolSectors.getOrDefault(symbol, "Unknown");
            
            sectorAllocations.merge(sector, entry.getValue(), Double::sum);
        }
        
        // Check for over-concentration (> 40% in one sector)
        double maxSectorAllocation = 0.40;
        for (var entry : sectorAllocations.entrySet()) {
            if (entry.getValue() > maxSectorAllocation) {
                warnings.add(String.format("Sector %s over-allocated: %.1f%% (max: %.1f%%)",
                    entry.getKey(), entry.getValue() * 100, maxSectorAllocation * 100));
            }
        }
        
        return warnings;
    }
    
    /**
     * Force immediate rebalancing check (for testing or manual trigger).
     */
    public void forceRebalanceCheck() {
        lastRebalance = Instant.now().minus(REBALANCE_INTERVAL);
        logger.info("Forced rebalance check - next check will trigger rebalancing");
    }
}
