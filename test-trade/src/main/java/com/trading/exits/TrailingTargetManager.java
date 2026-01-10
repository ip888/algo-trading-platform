package com.trading.exits;

import com.trading.config.Config;
import com.trading.risk.TradePosition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Trailing Profit Target Manager
 * Implements multi-level trailing stops to let winners run while protecting gains
 */
public class TrailingTargetManager {
    private static final Logger logger = LoggerFactory.getLogger(TrailingTargetManager.class);
    
    private final Config config;
    private final Map<String, TrailingState> trailingStates = new HashMap<>();
    
    public TrailingTargetManager(Config config) {
        this.config = config;
    }
    
    /**
     * Update trailing stop for a position based on current profit
     * @return New stop loss price, or 0 if no change
     */
    public double updateTrailingStop(TradePosition position, double currentPrice) {
        if (!config.isTrailingTargetsEnabled()) {
            return 0;
        }
        
        String symbol = position.symbol();
        double pnlPercent = ((currentPrice - position.entryPrice()) / position.entryPrice()) * 100.0;
        
        TrailingState state = trailingStates.computeIfAbsent(symbol, k -> new TrailingState());
        
        // Level 2: At +2% profit
        if (pnlPercent >= config.getTrailLevel2Profit()) {
            double trailPercent = config.getTrailLevel2Trail();
            double newStop = currentPrice * (1 - trailPercent / 100.0);
            
            if (newStop > state.currentStop) {
                state.currentStop = newStop;
                state.level = 2;
                state.lockedPercent = config.getTrailLevel2Lock();
                
                logger.info("🎯 {} Level 2 Trail: P&L=+{:.2f}%, Stop=${:.2f} (-{:.2f}%)",
                    symbol, pnlPercent, newStop, trailPercent);
                
                return newStop;
            }
        }
        // Level 1: At +1% profit
        else if (pnlPercent >= config.getTrailLevel1Profit()) {
            double trailPercent = config.getTrailLevel1Trail();
            double newStop = currentPrice * (1 - trailPercent / 100.0);
            
            if (newStop > state.currentStop) {
                state.currentStop = newStop;
                state.level = 1;
                state.lockedPercent = config.getTrailLevel1Lock();
                
                logger.info("🎯 {} Level 1 Trail: P&L=+{:.2f}%, Stop=${:.2f} (-{:.2f}%)",
                    symbol, pnlPercent, newStop, trailPercent);
                
                return newStop;
            }
        }
        
        return 0; // No change
    }
    
    /**
     * Check if should take partial profit at trailing level
     * @return Percentage to exit (0-100), or 0 if no exit
     */
    public double checkPartialExit(String symbol, double pnlPercent) {
        TrailingState state = trailingStates.get(symbol);
        if (state == null || state.partialTaken) {
            return 0;
        }
        
        // Take partial profit at level thresholds
        if (state.level >= 1 && !state.partialTaken) {
            state.partialTaken = true;
            double exitPercent = state.lockedPercent;
            
            logger.info("💰 {} Taking {:.0f}% profit at level {} (P&L: +{:.2f}%)",
                symbol, exitPercent, state.level, pnlPercent);
            
            return exitPercent;
        }
        
        return 0;
    }
    
    /**
     * Get current trailing stop for symbol
     */
    public double getCurrentStop(String symbol) {
        TrailingState state = trailingStates.get(symbol);
        return state != null ? state.currentStop : 0;
    }
    
    /**
     * Remove trailing state when position closed
     */
    public void removePosition(String symbol) {
        trailingStates.remove(symbol);
    }
    
    /**
     * Internal state tracking for each position
     */
    private static class TrailingState {
        double currentStop = 0;
        int level = 0;
        double lockedPercent = 0;
        boolean partialTaken = false;
    }
}
