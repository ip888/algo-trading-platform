package com.trading.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.DayOfWeek;

/**
 * Risk prediction for trading setups.
 * Combines multiple risk factors to calculate risk scores and recommend position sizing.
 */
public class RiskPredictor {
    private static final Logger logger = LoggerFactory.getLogger(RiskPredictor.class);
    
    // Risk thresholds
    private static final int HIGH_RISK_THRESHOLD = 70;
    private static final int MODERATE_RISK_THRESHOLD = 40;
    
    public RiskPredictor() {
        logger.info("⚡ RiskPredictor initialized");
    }
    
    /**
     * Calculate risk score (0-100) for a trading setup.
     * Higher score = higher risk
     */
    public int calculateRiskScore(TradingSetup setup) {
        int score = 0;
        
        // Market volatility risk (VIX)
        score += calculateVixRisk(setup.vixLevel);
        
        // Symbol volatility risk
        score += calculateSymbolVolatilityRisk(setup.symbolVolatility);
        
        // Time of day risk
        score += calculateTimeRisk(setup.hour, setup.dayOfWeek);
        
        // Portfolio concentration risk
        score += calculateConcentrationRisk(setup.positionCount, setup.maxPositions);
        
        // Recent losses risk
        score += calculateLossStreakRisk(setup.recentLosses);
        
        // Sentiment conflict risk
        score += calculateSentimentRisk(setup.sentimentScore, setup.isBullish);
        
        return Math.min(100, score);
    }
    
    /**
     * Get recommended position size based on risk score.
     */
    public double getRecommendedSize(double baseSize, int riskScore) {
        if (riskScore > HIGH_RISK_THRESHOLD) {
            return baseSize * 0.5; // 50% reduction for high risk
        } else if (riskScore > MODERATE_RISK_THRESHOLD) {
            return baseSize * 0.75; // 25% reduction for moderate risk
        }
        return baseSize; // Full size for low risk
    }
    
    /**
     * Check if trade is too risky to execute.
     */
    public boolean isTooRisky(TradingSetup setup) {
        int riskScore = calculateRiskScore(setup);
        return riskScore > HIGH_RISK_THRESHOLD;
    }
    
    /**
     * Calculate VIX-based risk (0-25 points).
     */
    private int calculateVixRisk(double vix) {
        if (vix > 40) return 25;      // Extreme fear
        if (vix > 30) return 20;      // High volatility
        if (vix > 20) return 10;      // Moderate volatility
        return 5;                     // Low volatility
    }
    
    /**
     * Calculate symbol-specific volatility risk (0-20 points).
     */
    private int calculateSymbolVolatilityRisk(double volatility) {
        // Volatility is typically 0-100 (annualized %)
        if (volatility > 60) return 20;
        if (volatility > 40) return 15;
        if (volatility > 25) return 10;
        return 5;
    }
    
    /**
     * Calculate time-based risk (0-15 points).
     */
    private int calculateTimeRisk(int hour, DayOfWeek day) {
        int risk = 0;
        
        // Hour risk
        if (hour == 9 || hour == 16) {
            risk += 10; // Open/close volatility
        } else if (hour < 10 || hour > 15) {
            risk += 5;  // Early/late session
        }
        
        // Day risk
        if (day == DayOfWeek.MONDAY || day == DayOfWeek.FRIDAY) {
            risk += 5;  // More volatile days
        }
        
        return risk;
    }
    
    /**
     * Calculate portfolio concentration risk (0-15 points).
     */
    private int calculateConcentrationRisk(int currentPositions, int maxPositions) {
        double concentration = (double) currentPositions / maxPositions;
        
        if (concentration > 0.9) return 15;  // Very concentrated
        if (concentration > 0.75) return 10; // Concentrated
        if (concentration > 0.5) return 5;   // Moderate
        return 0;                            // Low concentration
    }
    
    /**
     * Calculate loss streak risk (0-15 points).
     */
    private int calculateLossStreakRisk(int recentLosses) {
        if (recentLosses >= 5) return 15;    // Long losing streak
        if (recentLosses >= 3) return 10;    // Moderate streak
        if (recentLosses >= 2) return 5;     // Short streak
        return 0;                            // No streak
    }
    
    /**
     * Calculate sentiment conflict risk (0-10 points).
     */
    private int calculateSentimentRisk(double sentimentScore, boolean isBullish) {
        // Check if sentiment conflicts with trade direction
        boolean conflict = (isBullish && sentimentScore < -0.2) || 
                          (!isBullish && sentimentScore > 0.2);
        
        if (conflict) {
            double conflictStrength = Math.abs(sentimentScore);
            return (int) (conflictStrength * 10);
        }
        
        return 0; // No conflict
    }
    
    /**
     * Get risk level description.
     */
    public String getRiskLevel(int riskScore) {
        if (riskScore > HIGH_RISK_THRESHOLD) return "HIGH";
        if (riskScore > MODERATE_RISK_THRESHOLD) return "MODERATE";
        return "LOW";
    }
    
    /**
     * Get risk statistics.
     */
    public String getStats(TradingSetup setup) {
        int score = calculateRiskScore(setup);
        return String.format("Risk: %s (%d/100)", getRiskLevel(score), score);
    }
    
    // Trading setup record
    public record TradingSetup(
        double vixLevel,
        double symbolVolatility,
        int hour,
        DayOfWeek dayOfWeek,
        int positionCount,
        int maxPositions,
        int recentLosses,
        double sentimentScore,
        boolean isBullish
    ) {}
}
