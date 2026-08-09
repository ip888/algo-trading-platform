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
                
                logger.info("🎯 {} Level 2 Trail: P&L=+{}%, Stop=${} (-{}%)",
                    symbol, String.format("%.2f", pnlPercent), String.format("%.2f", newStop), String.format("%.2f", trailPercent));
                
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
                
                logger.info("🎯 {} Level 1 Trail: P&L=+{}%, Stop=${} (-{}%)",
                    symbol, String.format("%.2f", pnlPercent), String.format("%.2f", newStop), String.format("%.2f", trailPercent));
                
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
            
            logger.info("💰 {} Taking {}% profit at level {} (P&L: +{}%)",
                symbol, String.format("%.0f", exitPercent), state.level, String.format("%.2f", pnlPercent));
            
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
     * Encode current state as a compact string for DB persistence.
     * Format: "currentStop,level,lockedPercent,partialTaken"
     * Returns null if no state exists for the symbol.
     */
    public String getEncodedState(String symbol) {
        TrailingState state = trailingStates.get(symbol);
        if (state == null || state.currentStop <= 0) return null;
        return String.format("%.6f,%d,%.6f,%b",
            state.currentStop, state.level, state.lockedPercent, state.partialTaken);
    }

    /**
     * Restore trailing state from a persisted encoded string (see getEncodedState).
     * Called during startup to recover state lost on JVM restart.
     */
    public void restoreFromEncoded(String symbol, String encoded) {
        if (encoded == null || encoded.isBlank()) return;
        try {
            String[] parts = encoded.split(",", 4);
            if (parts.length < 4) return;
            double currentStop = Double.parseDouble(parts[0]);
            int level = Integer.parseInt(parts[1]);
            double lockedPercent = Double.parseDouble(parts[2]);
            boolean partialTaken = Boolean.parseBoolean(parts[3]);
            TrailingState state = trailingStates.computeIfAbsent(symbol, k -> new TrailingState());
            state.currentStop = currentStop;
            state.level = level;
            state.lockedPercent = lockedPercent;
            state.partialTaken = partialTaken;
            logger.info("Restored trailing state for {}: stop=${} level={} locked={}% partialTaken={}",
                symbol, String.format("%.2f", currentStop), level,
                String.format("%.1f", lockedPercent), partialTaken);
        } catch (Exception e) {
            logger.warn("Failed to restore trailing state for {} from '{}': {}", symbol, encoded, e.getMessage());
        }
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
