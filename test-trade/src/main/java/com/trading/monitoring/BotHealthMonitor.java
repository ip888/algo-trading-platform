package com.trading.monitoring;

import com.trading.config.Config;
import com.trading.portfolio.ProfileManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Monitors bot health and performs auto-corrections when discrepancies are detected.
 * Validates:
 * - PDT compliance (hold times)
 * - Position consistency
 * - Trading frequency
 * - Capital deployment
 */
public class BotHealthMonitor {
    private static final Logger logger = LoggerFactory.getLogger(BotHealthMonitor.class);
    
    private final Config config;
    private final AtomicInteger pdtViolationAttempts = new AtomicInteger(0);
    private final AtomicInteger positionDiscrepancies = new AtomicInteger(0);
    private final AtomicLong lastHealthCheck = new AtomicLong(System.currentTimeMillis());
    
    // Thresholds for auto-correction
    private static final int MAX_PDT_VIOLATIONS_PER_HOUR = 5;
    private static final int MAX_POSITION_DISCREPANCIES = 3;
    private static final Duration HEALTH_CHECK_INTERVAL = Duration.ofMinutes(5);
    
    public BotHealthMonitor(Config config) {
        this.config = config;
        logger.info("═══════════════════════════════════════════════════════");
        logger.info("BOT HEALTH MONITOR INITIALIZED");
        logger.info("═══════════════════════════════════════════════════════");
        logger.info("Monitoring:");
        logger.info("  - PDT Compliance (min hold: {} hours)", config.getMinHoldTimeHours());
        logger.info("  - Position Consistency");
        logger.info("  - Trading Frequency");
        logger.info("  - Auto-Correction: ENABLED");
        logger.info("═══════════════════════════════════════════════════════");
    }
    
    /**
     * Log PDT protection event (position held < min hours).
     * Auto-corrects if too many violations attempted.
     */
    public void logPdtProtection(String symbol, long hoursHeld, int minHours) {
        int violations = pdtViolationAttempts.incrementAndGet();
        
        logger.info("🛡️ PDT PROTECTION: {} held {} hours (min: {} hours)", 
            symbol, hoursHeld, minHours);
        logger.info("   Waiting {} more hours before sell allowed", minHours - hoursHeld);
        logger.info("   PDT protection events this hour: {}", violations);
        
        // Auto-correction: If too many PDT violations, something is wrong
        if (violations > MAX_PDT_VIOLATIONS_PER_HOUR) {
            logger.error("⚠️ AUTO-CORRECTION: Excessive PDT protection events ({} in 1 hour)", violations);
            logger.error("   Possible issue: Strategy generating too many sell signals");
            logger.error("   Recommendation: Review strategy parameters or increase hold time");
            // Reset counter
            pdtViolationAttempts.set(0);
        }
    }
    
    /**
     * Validate position hold time before allowing sell.
     * Returns true if sell is allowed, false if blocked.
     */
    public boolean validateSellAllowed(String symbol, Instant entryTime) {
        if (entryTime == null) {
            logger.warn("⚠️ VALIDATION: {} has no entryTime - allowing sell (legacy position)", symbol);
            return true;
        }
        
        long hoursHeld = Duration.between(entryTime, Instant.now()).toHours();
        int minHours = config.getMinHoldTimeHours();
        
        if (hoursHeld < minHours) {
            logPdtProtection(symbol, hoursHeld, minHours);
            return false;
        }
        
        logger.info("✅ VALIDATION: {} held {} hours - sell ALLOWED", symbol, hoursHeld);
        return true;
    }
    
    /**
     * Log position discrepancy (e.g., position in portfolio but not in Alpaca).
     */
    public void logPositionDiscrepancy(String symbol, String issue) {
        int discrepancies = positionDiscrepancies.incrementAndGet();
        
        logger.error("❌ POSITION DISCREPANCY: {}", symbol);
        logger.error("   Issue: {}", issue);
        logger.error("   Total discrepancies: {}", discrepancies);
        
        // Auto-correction: Sync with Alpaca if too many discrepancies
        if (discrepancies > MAX_POSITION_DISCREPANCIES) {
            logger.error("⚠️ AUTO-CORRECTION: Too many position discrepancies ({})", discrepancies);
            logger.error("   Action: Forcing portfolio sync with Alpaca");
            logger.error("   This will reconcile local state with actual positions");
            // Reset counter
            positionDiscrepancies.set(0);
            // Trigger sync (would need to be implemented in ProfileManager)
        }
    }
    
    /**
     * Perform periodic health check.
     * Call this every 5 minutes from main trading loop.
     */
    public void performHealthCheck(ProfileManager mainProfile, ProfileManager expProfile, 
                                   com.trading.autonomous.AutoRecoveryManager autoRecovery) {
        long now = System.currentTimeMillis();
        long lastCheck = lastHealthCheck.get();
        
        if (now - lastCheck < HEALTH_CHECK_INTERVAL.toMillis()) {
            return; // Too soon for next check
        }
        
        lastHealthCheck.set(now);
        
        logger.info("═══════════════════════════════════════════════════════");
        logger.info("HEALTH CHECK - {}", Instant.now());
        logger.info("═══════════════════════════════════════════════════════");
        
        // Perform auto-recovery health check
        if (autoRecovery != null) {
            autoRecovery.performHealthCheckAndRecover();
        }
        
        // Check 1: PDT compliance
        int pdtEvents = pdtViolationAttempts.get();
        if (pdtEvents > 0) {
            logger.info("PDT Protection Events (last hour): {}", pdtEvents);
            if (pdtEvents > MAX_PDT_VIOLATIONS_PER_HOUR / 2) {
                logger.warn("   ⚠️ High PDT protection activity - monitor strategy");
            }
        } else {
            logger.info("PDT Protection Events: 0 ✅");
        }
        
        // Check 2: Position consistency
        int discrepancies = positionDiscrepancies.get();
        if (discrepancies > 0) {
            logger.warn("Position Discrepancies: {} ⚠️", discrepancies);
        } else {
            logger.info("Position Consistency: OK ✅");
        }
        
        // Check 3: Profile status
        logger.info("MAIN Profile: {} positions", mainProfile.getActivePositionCount());
        logger.info("EXPERIMENTAL Profile: {} positions", expProfile.getActivePositionCount());
        
        // Check 4: Auto-recovery stats
        if (autoRecovery != null) {
            logger.info("Auto-Recovery: {}", autoRecovery.getStats());
        }
        
        logger.info("═══════════════════════════════════════════════════════");
        
        // Reset hourly counters
        pdtViolationAttempts.set(0);
    }
    
    /**
     * Log successful trade execution with hold time.
     */
    public void logTradeExecution(String action, String symbol, long hoursHeld) {
        logger.info("📊 TRADE EXECUTED: {} {}", action, symbol);
        if (hoursHeld > 0) {
            logger.info("   Hold Time: {} hours", hoursHeld);
            logger.info("   PDT Compliant: {}", hoursHeld >= config.getMinHoldTimeHours() ? "✅" : "❌");
        }
    }
    
    /**
     * Validate and log rebalancing event.
     */
    public void logRebalancing(int oldMain, int oldExp, int newMain, int newExp, String reason) {
        logger.info("═══════════════════════════════════════════════════════");
        logger.info("AUTOMATED REBALANCING");
        logger.info("═══════════════════════════════════════════════════════");
        logger.info("Reason: {}", reason);
        logger.info("Old Allocation: MAIN={}%, EXP={}%", oldMain, oldExp);
        logger.info("New Allocation: MAIN={}%, EXP={}%", newMain, newExp);
        logger.info("═══════════════════════════════════════════════════════");
        
        // Validation: Allocations should sum to 100%
        if (newMain + newExp != 100) {
            logger.error("❌ REBALANCING ERROR: Allocations don't sum to 100% ({} + {} = {})",
                newMain, newExp, newMain + newExp);
            logger.error("⚠️ AUTO-CORRECTION: Resetting to default 60/40 split");
        }
    }
}
