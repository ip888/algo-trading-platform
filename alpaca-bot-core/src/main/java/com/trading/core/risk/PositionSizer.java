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
        
        return switch (method) {
            case "KELLY" -> calculateKellyPosition(winRate, riskReward);
            case "VOLATILITY" -> calculateVolatilityPosition(equity);
            case "FIXED" -> Config.getDouble("POSITION_SIZING_FIXED_PERCENT", 0.10);
            default -> Config.getDouble("POSITION_SIZING_FIXED_PERCENT", 0.10);
        };
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
     * Volatility-based position sizing using Market Regime.
     * Self-Correcting: Automatically reduces exposure in high volatility/bear markets.
     */
    private static double calculateVolatilityPosition(double equity) {
        com.trading.core.model.MarketRegime regime = com.trading.core.analysis.RegimeDetector.getCurrentRegime();
        
        // Dynamic Risk Scaling based on Regime
        return switch (regime) {
            case HIGH_VOLATILITY -> 0.0;     // 🛡️ CASH IS KING (No new positions)
            case STRONG_BEAR -> 0.0;         // 🛡️ CASH IS KING
            case WEAK_BEAR -> 0.02;          // 🔎 Small probe bets only
            case RANGE_BOUND -> 0.05;        // ⚖️ Standard size
            case WEAK_BULL -> 0.08;          // 📈 Increasing conviction
            case STRONG_BULL -> 0.15;        // 🚀 Max aggression
            default -> 0.02;                 // Safety default
        };
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
