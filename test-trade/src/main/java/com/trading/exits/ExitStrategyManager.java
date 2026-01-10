package com.trading.exits;

import com.trading.config.Config;
import com.trading.risk.TradePosition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Enhanced Exit Strategy Manager - Implements sophisticated exit logic.
 * 
 * Exit Strategies:
 * 1. Partial Exits - Take profits incrementally (25%, 50%, 75% levels)
 * 2. Volatility Breakout Exits - Exit on abnormal volatility spikes
 * 3. Time Decay Exits - Exit stale losing positions
 * 4. Correlation Exits - Reduce exposure when portfolio is correlated
 * 
 * Goal: Maximize profit capture while minimizing drawdowns.
 */
public class ExitStrategyManager {
    private static final Logger logger = LoggerFactory.getLogger(ExitStrategyManager.class);
    
    private final Config config;
    
    // Partial exit levels (percentage of profit target)
    private static final double PARTIAL_EXIT_LEVEL_1 = 0.25; // 25% of profit target
    private static final double PARTIAL_EXIT_LEVEL_2 = 0.50; // 50% of profit target
    private static final double PARTIAL_EXIT_LEVEL_3 = 0.75; // 75% of profit target
    
    // Partial exit sizes (percentage of position)
    private static final double PARTIAL_EXIT_SIZE_1 = 0.33; // Exit 1/3
    private static final double PARTIAL_EXIT_SIZE_2 = 0.50; // Exit 1/2 of remaining
    private static final double PARTIAL_EXIT_SIZE_3 = 0.50; // Exit 1/2 of remaining
    
    public ExitStrategyManager(Config config) {
        this.config = config;
        logger.info("ExitStrategyManager initialized with enhanced exit strategies");
    }
    
    /**
     * Exit decision record
     */
    public record ExitDecision(
        ExitType type,
        double quantity,      // How much to exit (0.0 to 1.0 = percentage of position)
        String reason,
        boolean isPartial,    // True if partial exit, false if full exit
        double expectedPrice  // Expected exit price (for limit orders)
    ) {
        public static ExitDecision noExit() {
            return new ExitDecision(ExitType.NONE, 0.0, "No exit signal", false, 0.0);
        }
        
        public static ExitDecision fullExit(ExitType type, String reason, double price) {
            return new ExitDecision(type, 1.0, reason, false, price);
        }
        
        public static ExitDecision partialExit(ExitType type, double quantity, String reason, double price) {
            return new ExitDecision(type, quantity, reason, true, price);
        }
    }
    
    /**
     * Exit type enumeration
     */
    public enum ExitType {
        NONE,
        PARTIAL_PROFIT,
        VOLATILITY_SPIKE,
        TIME_DECAY,
        CORRELATION,
        STOP_LOSS,
        TAKE_PROFIT,
        // Phase 2 exit types
        PDT_PARTIAL,      // Feature #17
        VELOCITY_DROP,    // Feature #21
        EOD_LOCK,         // Feature #23
        QUICK_SCALP       // Feature #25
    }
    
    /**
     * Evaluate all exit strategies and return the highest priority exit decision.
     */
    public ExitDecision evaluateExit(TradePosition position, double currentPrice, 
                                     double currentVolatility, Map<String, Double> portfolioPositions) {
        
        // Priority 1: Stop loss (always highest priority)
        if (position.isStopLossHit(currentPrice)) {
            return ExitDecision.fullExit(ExitType.STOP_LOSS, 
                "Stop loss hit", currentPrice);
        }
        
        // Priority 2: Full take profit
        if (position.isTakeProfitHit(currentPrice)) {
            return ExitDecision.fullExit(ExitType.TAKE_PROFIT, 
                "Take profit target hit", currentPrice);
        }
        
        // Priority 3: Partial profit taking
        ExitDecision partialExit = evaluatePartialExit(position, currentPrice);
        if (partialExit.type() != ExitType.NONE) {
            return partialExit;
        }
        
        // Priority 4: Volatility spike exit
        ExitDecision volatilityExit = evaluateVolatilityExit(position, currentPrice, currentVolatility);
        if (volatilityExit.type() != ExitType.NONE) {
            return volatilityExit;
        }
        
        // Priority 5: Time decay exit
        ExitDecision timeExit = evaluateTimeDecayExit(position, currentPrice);
        if (timeExit.type() != ExitType.NONE) {
            return timeExit;
        }
        
        // Priority 6: Correlation exit
        ExitDecision correlationExit = evaluateCorrelationExit(position, portfolioPositions);
        if (correlationExit.type() != ExitType.NONE) {
            return correlationExit;
        }
        
        return ExitDecision.noExit();
    }
    
    /**
     * Evaluate partial profit taking strategy.
     * Takes profits incrementally as position moves in our favor.
     */
    private ExitDecision evaluatePartialExit(TradePosition position, double currentPrice) {
        double profitPercent = position.getProfitPercent(currentPrice);
        double targetProfit = (position.takeProfit() - position.entryPrice()) / position.entryPrice();
        
        // Calculate how far we are to profit target (0.0 to 1.0)
        double progressToTarget = profitPercent / targetProfit;
        
        // Check if we've hit any partial exit levels
        if (progressToTarget >= PARTIAL_EXIT_LEVEL_3 && !position.hasPartialExit(3)) {
            return ExitDecision.partialExit(ExitType.PARTIAL_PROFIT, PARTIAL_EXIT_SIZE_3,
                String.format("Partial exit at 75%% profit target (%.1f%% profit)", profitPercent * 100),
                currentPrice);
        }
        
        if (progressToTarget >= PARTIAL_EXIT_LEVEL_2 && !position.hasPartialExit(2)) {
            return ExitDecision.partialExit(ExitType.PARTIAL_PROFIT, PARTIAL_EXIT_SIZE_2,
                String.format("Partial exit at 50%% profit target (%.1f%% profit)", profitPercent * 100),
                currentPrice);
        }
        
        if (progressToTarget >= PARTIAL_EXIT_LEVEL_1 && !position.hasPartialExit(1)) {
            return ExitDecision.partialExit(ExitType.PARTIAL_PROFIT, PARTIAL_EXIT_SIZE_1,
                String.format("Partial exit at 25%% profit target (%.1f%% profit)", profitPercent * 100),
                currentPrice);
        }
        
        return ExitDecision.noExit();
    }
    
    /**
     * Evaluate volatility spike exit.
     * Exit if volatility spikes to abnormal levels.
     */
    private ExitDecision evaluateVolatilityExit(TradePosition position, double currentPrice, 
                                               double currentVolatility) {
        // Normal volatility is around 0.01-0.03 (1-3%)
        // Spike is 2x normal or > 0.05 (5%)
        double volatilityThreshold = 0.05;
        
        if (currentVolatility > volatilityThreshold) {
            double profitPercent = position.getProfitPercent(currentPrice);
            
            // Only exit on volatility spike if we're profitable
            if (profitPercent > 0) {
                return ExitDecision.fullExit(ExitType.VOLATILITY_SPIKE,
                    String.format("Volatility spike (%.1f%%) - securing %.1f%% profit", 
                        currentVolatility * 100, profitPercent * 100),
                    currentPrice);
            }
        }
        
        return ExitDecision.noExit();
    }
    
    /**
     * Evaluate time decay exit.
     * Exit positions that have been open too long without profit.
     */
    private ExitDecision evaluateTimeDecayExit(TradePosition position, double currentPrice) {
        Duration holdTime = Duration.between(position.entryTime(), Instant.now());
        double profitPercent = position.getProfitPercent(currentPrice);
        
        // Exit losing positions after configured max hold time
        long maxHoldHours = config.getMaxHoldTimeHours();
        
        if (holdTime.toHours() >= maxHoldHours && profitPercent < 0) {
            return ExitDecision.fullExit(ExitType.TIME_DECAY,
                String.format("Time decay exit after %d hours (%.1f%% loss)", 
                    holdTime.toHours(), profitPercent * 100),
                currentPrice);
        }
        
        // Also exit break-even positions after 2x max hold time
        if (holdTime.toHours() >= maxHoldHours * 2 && Math.abs(profitPercent) < 0.005) {
            return ExitDecision.fullExit(ExitType.TIME_DECAY,
                String.format("Time decay exit after %d hours (break-even)", 
                    holdTime.toHours()),
                currentPrice);
        }
        
        return ExitDecision.noExit();
    }
    
    /**
     * Evaluate correlation-based exit.
     * Reduce position if portfolio becomes too correlated.
     */
    private ExitDecision evaluateCorrelationExit(TradePosition position, 
                                                 Map<String, Double> portfolioPositions) {
        // If we have too many positions (over-concentrated)
        int maxPositions = config.getPositionSizingMaxCorrelatedPositions();
        
        if (portfolioPositions.size() > maxPositions) {
            // Exit smallest profitable positions to reduce concentration
            double profitPercent = position.getProfitPercent(position.entryPrice()); // Simplified
            
            if (profitPercent > 0.02) { // At least 2% profit
                return ExitDecision.partialExit(ExitType.CORRELATION, 0.50,
                    String.format("Correlation exit - reducing exposure (%d positions)", 
                        portfolioPositions.size()),
                    position.entryPrice());
            }
        }
        
        return ExitDecision.noExit();
    }
    
    /**
     * Get summary of exit strategy configuration.
     */
    public String getSummary() {
        return String.format(
            "Exit Strategies: Partial(%.0f%%/%.0f%%/%.0f%%), TimeDecay(%dh), VolSpike(%.0f%%)",
            PARTIAL_EXIT_LEVEL_1 * 100,
            PARTIAL_EXIT_LEVEL_2 * 100,
            PARTIAL_EXIT_LEVEL_3 * 100,
            config.getMaxHoldTimeHours(),
            5.0 // Volatility threshold
        );
    }
}
