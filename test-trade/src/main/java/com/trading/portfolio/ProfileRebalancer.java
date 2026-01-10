package com.trading.portfolio;

import com.trading.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * Handles automated rebalancing of capital between MAIN and EXPERIMENTAL profiles
 * based on weekly performance.
 */
public class ProfileRebalancer {
    private static final Logger logger = LoggerFactory.getLogger(ProfileRebalancer.class);
    private static final ZoneId EST = ZoneId.of("America/New_York");
    
    private final Config config;
    private LocalDateTime lastRebalance;
    
    // Current allocations (will be adjusted based on performance)
    private int mainAllocation = 60;  // Default 60%
    private int expAllocation = 40;   // Default 40%
    
    public ProfileRebalancer(Config config) {
        this.config = config;
        this.lastRebalance = LocalDateTime.now(EST).minusWeeks(1); // Initialize to last week
    }
    
    /**
     * Check if it's time to rebalance and do so if needed.
     * Rebalances weekly at configured hour (default: 4 AM EST).
     * Can rebalance on any weekday if it's been more than 7 days since last rebalance.
     */
    public void rebalanceIfNeeded(ProfileManager mainProfile, ProfileManager expProfile) {
        if (!config.isAutoRebalancingEnabled()) {
            return; // Rebalancing disabled
        }
        
        LocalDateTime now = LocalDateTime.now(EST);
        
        // Check if it's a weekday
        if (now.getDayOfWeek() == DayOfWeek.SATURDAY || now.getDayOfWeek() == DayOfWeek.SUNDAY) {
            return; // Don't rebalance on weekends
        }
        
        // Check if it's the configured hour
        if (now.getHour() != config.getRebalancingHour()) {
            return; // Only rebalance at configured hour (default: 4 AM)
        }
        
        // Check if we already rebalanced recently (within last 6 days)
        if (lastRebalance.isAfter(now.minusDays(6))) {
            logger.debug("Rebalancing skipped - last rebalance was {} (less than 7 days ago)", 
                lastRebalance.toLocalDate());
            return; // Already rebalanced this week
        }
        
        logger.info("═══════════════════════════════════════════════════════");
        logger.info("AUTOMATED REBALANCING - {} {} at {}:00 EST", 
            now.getDayOfWeek(), now.toLocalDate(), now.getHour());
        logger.info("Last Rebalance: {}", lastRebalance.toLocalDate());
        logger.info("═══════════════════════════════════════════════════════");
        
        // Calculate weekly returns for each profile
        double mainReturn = calculateWeeklyReturn(mainProfile);
        double expReturn = calculateWeeklyReturn(expProfile);
        
        logger.info("Last Week Performance:");
        logger.info("  MAIN Profile: {:.2f}%", mainReturn);
        logger.info("  EXPERIMENTAL Profile: {:.2f}%", expReturn);
        
        // Store old allocation
        int oldMainAllocation = mainAllocation;
        int oldExpAllocation = expAllocation;
        
        // Adjust allocation based on performance
        if (mainReturn > expReturn + 1.0) {
            // MAIN significantly outperforming (>1% better)
            mainAllocation = 70;
            expAllocation = 30;
            logger.info("MAIN outperforming - Increasing allocation");
        } else if (expReturn > mainReturn + 1.0) {
            // EXP significantly outperforming (>1% better)
            mainAllocation = 50;
            expAllocation = 50;
            logger.info("EXPERIMENTAL outperforming - Increasing allocation");
        } else {
            // Similar performance - reset to default
            mainAllocation = 60;
            expAllocation = 40;
            logger.info("Similar performance - Resetting to default allocation");
        }
        
        logger.info("New Allocation:");
        logger.info("  MAIN: {}% → {}%", oldMainAllocation, mainAllocation);
        logger.info("  EXPERIMENTAL: {}% → {}%", oldExpAllocation, expAllocation);
        logger.info("═══════════════════════════════════════════════════════");
        
        // Update last rebalance time
        lastRebalance = now;
        
        // TODO: Actually redistribute capital between profiles
        // This would require modifying the ProfileManager to accept new capital allocations
    }
    
    /**
     * Calculate weekly return for a profile.
     * Returns percentage gain/loss over the last 7 days.
     * 
     * TODO: This is a placeholder implementation.
     * In the future, track actual weekly P&L in ProfileManager.
     */
    private double calculateWeeklyReturn(ProfileManager profile) {
        // Placeholder: Return 0 for now
        // This will be enhanced when ProfileManager tracks historical performance
        logger.debug("Weekly return calculation not yet implemented - using placeholder");
        return 0.0;
    }
    
    public int getMainAllocation() {
        return mainAllocation;
    }
    
    public int getExpAllocation() {
        return expAllocation;
    }
}
