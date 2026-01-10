package com.trading.core.risk;

import com.trading.core.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Advanced position sizing using Kelly Criterion with safety limits.
 * Calculates optimal position sizes based on win rate and risk/reward.
 */
public class PositionSizer {
    private static final Logger logger = LoggerFactory.getLogger(PositionSizer.class);
    
    /**
     * Calculate position size as percentage of portfolio.
     * Uses Kelly Criterion: f* = (p * b - q) / b
     * where p = win rate, q = 1 - p, b = risk/reward ratio
     */
    public static double calculatePositionPercent(double equity, double winRate, double riskReward) {
        String method = Config.getPositionSizingMethod();
        
        switch (method) {
            case "KELLY":
                return calculateKellyPosition(winRate, riskReward);
            case "VOLATILITY":
                return calculateVolatilityPosition(equity);
            case "FIXED":
            default:
                return Config.getDouble("POSITION_SIZING_FIXED_PERCENT", 0.10);
        }
    }
    
    /**
     * Kelly Criterion position sizing (fractional Kelly for safety).
     */
    private static double calculateKellyPosition(double winRate, double riskReward) {
        double p = winRate;
        double q = 1.0 - p;
        double b = riskReward;
        
        // Kelly formula: f* = (p * b - q) / b
        double kellyFull = (p * b - q) / b;
        
        // Apply fractional Kelly (default 25%) for safety
        double fraction = Config.getKellyFraction();
        double kellyFractional = kellyFull * fraction;
        
        // Enforce min/max limits
        double minPercent = Config.getDouble("POSITION_SIZING_MIN_PERCENT", 0.02);
        double maxPercent = Config.getDouble("POSITION_SIZING_MAX_PERCENT", 0.20);
        
        double result = Math.max(minPercent, Math.min(maxPercent, kellyFractional));
        
        logger.debug("Kelly sizing: winRate={}, riskReward={}, kelly={:.4f}, fractional={:.4f}, result={:.4f}",
            winRate, riskReward, kellyFull, kellyFractional, result);
        
        return result;
    }
    
    /**
     * Volatility-based position sizing.
     */
    private static double calculateVolatilityPosition(double equity) {
        double riskPercent = Config.getDouble("POSITION_SIZING_VOLATILITY_RISK_PERCENT", 0.02);
        // In production, this would use ATR or historical volatility
        return riskPercent;
    }
    
    /**
     * Calculate dollar amount for position.
     */
    public static double calculatePositionAmount(double equity) {
        double winRate = Config.getDefaultWinRate();
        double riskReward = Config.getKellyRiskReward();
        double percent = calculatePositionPercent(equity, winRate, riskReward);
        return equity * percent;
    }
    
    /**
     * Calculate number of shares based on position amount and price.
     */
    public static double calculateShares(double equity, double sharePrice) {
        double positionAmount = calculatePositionAmount(equity);
        return positionAmount / sharePrice;
    }
}
