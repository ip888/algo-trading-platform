package com.trading.risk;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;

/**
 * Risk management system for controlling position sizing and capital protection.
 */
public final class RiskManager {
    private static final Logger logger = LoggerFactory.getLogger(RiskManager.class);
    
    // Risk parameters
    private static final double RISK_PER_TRADE = 0.01;  // 1% of capital
    private static final double STOP_LOSS_PCT = 0.02;   // 2% stop-loss
    private static final double TAKE_PROFIT_PCT = 0.04; // 4% take-profit (2:1 reward/risk)
    private static final double MAX_DRAWDOWN = 0.50;    // 50% max drawdown (paper trading - higher tolerance)
    private static final double TRAILING_STOP_PCT = 0.02; // 2% trailing stop
    
    private double peakEquity;
    private final AdvancedPositionSizer positionSizer; // Optional advanced sizing
    
    public RiskManager(double initialCapital) {
        this(initialCapital, null);
    }
    
    public RiskManager(double initialCapital, AdvancedPositionSizer positionSizer) {
        this.peakEquity = initialCapital;
        this.positionSizer = positionSizer;
        
        if (positionSizer != null) {
            logger.info("RiskManager initialized with AdvancedPositionSizer");
        }
    }
    
    /**
     * Calculate safe position size based on account equity and risk percentage.
     * Uses AdvancedPositionSizer if available, otherwise falls back to simple sizing.
     */
    public double calculatePositionSize(double accountEquity, double entryPrice) {
        return calculatePositionSize(accountEquity, entryPrice, 15.0); // Default VIX
    }

    /**
     * Calculate safe position size with volatility adjustment.
     * Uses AdvancedPositionSizer if available for Kelly Criterion-based sizing.
     */
    public double calculatePositionSize(double accountEquity, double entryPrice, double currentVix) {
        return calculatePositionSize(null, accountEquity, entryPrice, currentVix, STOP_LOSS_PCT);
    }
    
    /**
     * Calculate position size with full parameters.
     * This is the main method that uses AdvancedPositionSizer when available.
     */
    public double calculatePositionSize(String symbol, double accountEquity, double entryPrice, 
                                       double currentVix, double stopLossPercent) {
        // Use AdvancedPositionSizer if available
        if (positionSizer != null && symbol != null) {
            // Calculate volatility from VIX (approximate)
            double volatility = currentVix / 100.0; // Convert VIX to decimal
            
            double size = positionSizer.calculatePositionSize(
                symbol, accountEquity, entryPrice, volatility, stopLossPercent
            );
            
            logger.debug("{}: Advanced position sizing - {} shares", symbol, 
                String.format("%.4f", size));
            
            return size;
        }
        
        // Fallback to simple volatility-adjusted sizing
        return calculateVolatilityAdjustedSize(accountEquity, entryPrice, currentVix);
    }

    private double calculateVolatilityAdjustedSize(double accountEquity, double entryPrice, double currentVix) {
        double stopLossPrice = calculateStopLoss(entryPrice);
        double riskPerShare = entryPrice - stopLossPrice;
        double dollarRisk = accountEquity * RISK_PER_TRADE;
        
        // Dynamic Risk Adjustment
        // Base VIX = 20. If VIX is 40, size is halved.
        double volatilityFactor = Math.min(1.0, 20.0 / Math.max(20.0, currentVix));
        dollarRisk *= volatilityFactor;

        double shares = dollarRisk / riskPerShare;
        
        // Round to 9 decimal places (Alpaca's fractional trading precision)
        shares = Math.round(shares * 1_000_000_000.0) / 1_000_000_000.0;
        
        logger.debug("Position sizing: Equity=${}, VIX={}, Factor={}, Shares={}", 
            accountEquity, currentVix, String.format("%.2f", volatilityFactor), shares);
        
        return Math.max(0.001, shares); // Minimum 0.001 shares
    }
    
    /**
     * Calculate stop-loss price (2% below entry for longs).
     */
    public double calculateStopLoss(double entryPrice) {
        return entryPrice * (1.0 - STOP_LOSS_PCT);
    }
    
    /**
     * Calculate take-profit price (4% above entry for longs).
     */
    public double calculateTakeProfit(double entryPrice) {
        return entryPrice * (1.0 + TAKE_PROFIT_PCT);
    }
    
    /**
     * Create a TradePosition with calculated risk parameters.
     * Supports fractional quantities.
     */
    public TradePosition createPosition(String symbol, double entryPrice, double quantity) {
        return new TradePosition(
            symbol,
            entryPrice,
            quantity,
            calculateStopLoss(entryPrice),
            calculateTakeProfit(entryPrice),
            Instant.now()
        );
    }
    
    /**
     * Check if trading should be halted due to excessive drawdown.
     */
    public boolean shouldHaltTrading(double currentEquity) {
        // Update peak equity
        if (currentEquity > peakEquity) {
            peakEquity = currentEquity;
            logger.debug("Peak equity updated to: ${}", String.format("%.2f", peakEquity));
        }
        
        double drawdown = (peakEquity - currentEquity) / peakEquity;
        
        // Auto-reset if peak equity seems wrong (>2x current equity)
        // This catches initialization bugs where peak was set incorrectly
        if (peakEquity > currentEquity * 2.0) {
            logger.warn("⚠️ AUTO-CORRECTION: Peak equity (${}) seems incorrect vs current (${})",
                String.format("%.2f", peakEquity), String.format("%.2f", currentEquity));
            logger.warn("   Resetting peak equity to current value");
            peakEquity = currentEquity;
            drawdown = 0;
            logger.info("✅ AUTO-CORRECTION: Peak equity reset - trading resumed");
        }
        
        if (drawdown > MAX_DRAWDOWN) {
            logger.error("CRITICAL: Max drawdown exceeded! Peak=${}, Current=${}, Drawdown={}%", 
                String.format("%.2f", peakEquity), 
                String.format("%.2f", currentEquity), 
                String.format("%.2f", drawdown * 100));
            return true;
        }
        
        logger.debug("Drawdown check: Peak=${}, Current=${}, Drawdown={}%",
            String.format("%.2f", peakEquity),
            String.format("%.2f", currentEquity),
            String.format("%.2f", drawdown * 100));
        
        return false;
    }
    
    /**
     * Get current drawdown percentage.
     */
    public double getCurrentDrawdown(double currentEquity) {
        if (currentEquity > peakEquity) {
            peakEquity = currentEquity;
        }
        return (peakEquity - currentEquity) / peakEquity;
    }
    
    /**
     * Get the trailing stop percentage.
     */
    public double getTrailingStopPercent() {
        return TRAILING_STOP_PCT;
    }
    
    /**
     * Update position with trailing stop logic.
     */
    public TradePosition updatePositionTrailingStop(TradePosition position, double currentPrice) {
        return position.updateTrailingStop(currentPrice, TRAILING_STOP_PCT);
    }
}
