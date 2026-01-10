package com.trading.risk;

import java.time.Instant;

/**
 * Immutable record representing an active trade position with risk parameters.
 * Supports trailing stop-loss to lock in profits and PDT-compliant hold time tracking.
 */
public record TradePosition(
    String symbol,
    double entryPrice,
    double quantity,
    double stopLoss,
    double takeProfit,
    Instant entryTime,
    double highestPrice,  // Tracks the highest price seen for trailing stop
    int partialExitsExecuted  // Tracks which partial exits have been executed (bitmask: 1=first, 2=second, 4=third)
) {
    public TradePosition {
        if (quantity <= 0) {
            throw new IllegalArgumentException("Quantity must be positive");
        }
        if (entryPrice <= 0) {
            throw new IllegalArgumentException("Entry price must be positive");
        }
        if (stopLoss >= entryPrice) {
            throw new IllegalArgumentException("Stop-loss must be below entry price for long positions");
        }
        if (takeProfit <= entryPrice) {
            throw new IllegalArgumentException("Take-profit must be above entry price for long positions");
        }
    }
    
    /**
     * Convenience constructor without highestPrice and partialExits (defaults to entryPrice and 0).
     */
    public TradePosition(String symbol, double entryPrice, double quantity, 
                         double stopLoss, double takeProfit, Instant entryTime) {
        this(symbol, entryPrice, quantity, stopLoss, takeProfit, entryTime, entryPrice, 0);
    }
    
    /**
     * Get current profit percent (positive if winning, negative if losing).
     */
    public double getProfitPercent(double currentPrice) {
        return (currentPrice - entryPrice) / entryPrice;
    }
    
    /**
     * Check if a specific partial exit has been executed.
     * @param exitLevel 1, 2, or 3 for first, second, or third partial exit
     */
    public boolean hasPartialExit(int exitLevel) {
        int mask = 1 << (exitLevel - 1); // Convert 1,2,3 to bitmask 1,2,4
        return (partialExitsExecuted & mask) != 0;
    }
    
    /**
     * Mark a partial exit as executed.
     * Returns new TradePosition with updated partialExitsExecuted.
     */
    public TradePosition markPartialExit(int exitLevel) {
        int mask = 1 << (exitLevel - 1);
        int newPartialExits = partialExitsExecuted | mask;
        return new TradePosition(
            symbol, entryPrice, quantity, stopLoss, takeProfit, 
            entryTime, highestPrice, newPartialExits
        );
    }
    
    /**
     * Checks if the stop-loss has been hit.
     */
    public boolean isStopLossHit(double currentPrice) {
        return currentPrice <= stopLoss;
    }
    
    /**
     * Checks if the take-profit has been hit.
     */
    public boolean isTakeProfitHit(double currentPrice) {
        return currentPrice >= takeProfit;
    }
    
    /**
     * Calculates current profit/loss.
     */
    public double calculatePnL(double currentPrice) {
        return (currentPrice - entryPrice) * quantity;
    }
    
    /**
     * Updates the trailing stop-loss if price has moved higher.
     * Returns a new TradePosition with updated stop-loss.
     */
    public TradePosition updateTrailingStop(double currentPrice, double trailPercent) {
        double newHighest = Math.max(highestPrice, currentPrice);
        
        // Calculate trailing stop: trail by trailPercent below highest price
        double newStopLoss = newHighest * (1.0 - trailPercent);
        
        // Only update if new stop is higher than current stop
        double updatedStopLoss = Math.max(stopLoss, newStopLoss);
        
        return new TradePosition(
            symbol, entryPrice, quantity, updatedStopLoss, 
            takeProfit, entryTime, newHighest, partialExitsExecuted
        );
    }
    
    /**
     * Check if position has been held for minimum required time (PDT compliance).
     * @param minHoldHours Minimum hold time in hours
     * @return true if position can be sold
     */
    public boolean canSell(int minHoldHours) {
        if (entryTime == null) {
            return true; // Legacy positions without entryTime can be sold
        }
        
        long hoursHeld = java.time.Duration.between(entryTime, Instant.now()).toHours();
        return hoursHeld >= minHoldHours;
    }
    
    /**
     * Get hours held since entry.
     */
    public long getHoursHeld() {
        if (entryTime == null) {
            return 0;
        }
        return java.time.Duration.between(entryTime, Instant.now()).toHours();
    }
    
    /**
     * Check if position loss exceeds maximum allowed.
     * @param currentPrice Current market price
     * @param maxLossPercent Maximum loss percent (positive number, e.g., 10.0 for 10%)
     * @return true if loss exceeds limit
     */
    public boolean isMaxLossExceeded(double currentPrice, double maxLossPercent) {
        double lossPercent = ((currentPrice - entryPrice) / entryPrice) * 100;
        return lossPercent < -maxLossPercent;
    }
    
    /**
     * Get current loss percent (negative if losing).
     */
    public double getLossPercent(double currentPrice) {
        return ((currentPrice - entryPrice) / entryPrice) * 100;
    }
    
    /**
     * Check if position has been held too long.
     * @param maxHoldHours Maximum hold time in hours
     * @return true if hold time exceeded
     */
    public boolean isHoldTimeLimitExceeded(int maxHoldHours) {
        return getHoursHeld() > maxHoldHours;
    }
}
