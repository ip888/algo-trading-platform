package com.trading.exits;

import com.trading.config.Config;
import com.trading.risk.TradePosition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;

/**
 * Time-Decay Exit Manager
 * Exits flat positions that aren't moving after specified time
 */
public class TimeDecayExitManager {
    private static final Logger logger = LoggerFactory.getLogger(TimeDecayExitManager.class);
    
    private final Config config;
    
    public TimeDecayExitManager(Config config) {
        this.config = config;
    }
    
    /**
     * Check if position should be exited due to time decay
     * @param position Position to check
     * @param currentPrice Current price
     * @return true if should exit
     */
    public boolean shouldExit(TradePosition position, double currentPrice) {
        if (!config.isTimeDecayExits()) {
            return false;
        }
        
        // Calculate how long position has been held
        Duration held = Duration.between(position.entryTime(), Instant.now());
        long hoursHeld = held.toHours();
        
        // Check if held long enough
        if (hoursHeld < config.getFlatPositionHours()) {
            return false;
        }
        
        // Calculate P&L percentage
        double pnlPercent = Math.abs(
            ((currentPrice - position.entryPrice()) / position.entryPrice()) * 100.0
        );
        
        // Exit if flat (within threshold)
        if (pnlPercent < config.getFlatPositionThreshold()) {
            logger.info("⏰ {} Time-Decay Exit: Held {}h, P&L only ±{:.2f}% (threshold: {:.2f}%)",
                position.symbol(), hoursHeld, pnlPercent, config.getFlatPositionThreshold());
            return true;
        }
        
        return false;
    }
    
    /**
     * Get reason for exit (for logging)
     */
    public String getExitReason(TradePosition position, double currentPrice) {
        Duration held = Duration.between(position.entryTime(), Instant.now());
        double pnlPercent = ((currentPrice - position.entryPrice()) / position.entryPrice()) * 100.0;
        
        return String.format("Time-decay: Held %dh with only %.2f%% P&L",
            held.toHours(), pnlPercent);
    }
}
