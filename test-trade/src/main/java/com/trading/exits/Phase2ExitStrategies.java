package com.trading.exits;

import com.trading.config.Config;
import com.trading.risk.TradePosition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;

/**
 * Phase 2 Exit Strategies - Advanced profit-maximizing exit logic.
 * Features #17, #20, #21, #23, #25
 */
public class Phase2ExitStrategies {
    private static final Logger logger = LoggerFactory.getLogger(Phase2ExitStrategies.class);
    
    private final Config config;
    
    public Phase2ExitStrategies(Config config) {
        this.config = config;
    }
    
    /**
     * Feature #17: PDT-Aware Profit Taking
     * Take partial profits if at PDT limit to avoid violations.
     */
    public ExitStrategyManager.ExitDecision evaluatePDTAwareExit(
            TradePosition position, 
            double currentPrice,
            int dayTradeCount) {
        
        if (!config.isPDTAwareProfitTaking()) {
            return ExitStrategyManager.ExitDecision.noExit();
        }
        
        // Check if we're at PDT limit (3 day trades in 5 days)
        if (dayTradeCount >= 3) {
            double profitPercent = position.getProfitPercent(currentPrice);
            
            // If profitable and position opened today, take partial profit
            Duration holdTime = Duration.between(position.entryTime(), Instant.now());
            boolean isIntraday = holdTime.toHours() < 24;
            
            if (profitPercent >= 0.5 && isIntraday) {
                double exitPercent = config.getPDTPartialExitPercent();
                logger.info("PDT-aware exit: {} at +{}% (day trade #{}/3)",
                    position.symbol(), String.format("%.1f", profitPercent * 100), dayTradeCount);
                
                return ExitStrategyManager.ExitDecision.partialExit(
                    ExitStrategyManager.ExitType.PDT_PARTIAL,
                    position.quantity() * exitPercent,
                    String.format("PDT limit - partial exit at +%.1f%%", profitPercent * 100),
                    currentPrice
                );
            }
        }
        
        return ExitStrategyManager.ExitDecision.noExit();
    }
    
    /**
     * Feature #20: Dynamic Stop Tightening
     * Tighten stops as profit increases to protect gains.
     */
    public double getDynamicStop(TradePosition position, double currentPrice) {
        if (!config.isDynamicStopTighteningEnabled()) {
            return position.stopLoss();
        }
        
        double profitPercent = position.getProfitPercent(currentPrice);
        double entryPrice = position.entryPrice();
        double originalStop = position.stopLoss();
        
        // Level 3: At +2.0% profit, move stop to +1.0%
        if (profitPercent >= config.getStopTightenLevel3Profit()) {
            double newStop = entryPrice * 1.01;
            if (newStop > originalStop) {
                logger.debug("{}: Tightening stop to +1.0% (profit: +{}%)", 
                    position.symbol(), String.format("%.1f", profitPercent * 100));
                return newStop;
            }
        }
        // Level 2: At +1.0% profit, move stop to +0.5%
        else if (profitPercent >= config.getStopTightenLevel2Profit()) {
            double newStop = entryPrice * 1.005;
            if (newStop > originalStop) {
                logger.debug("{}: Tightening stop to +0.5% (profit: +{}%)", 
                    position.symbol(), String.format("%.1f", profitPercent * 100));
                return newStop;
            }
        }
        // Level 1: At +0.5% profit, move stop to breakeven
        else if (profitPercent >= config.getStopTightenLevel1Profit()) {
            if (entryPrice > originalStop) {
                logger.debug("{}: Moving stop to breakeven (profit: +{}%)", 
                    position.symbol(), String.format("%.1f", profitPercent * 100));
                return entryPrice;
            }
        }
        
        return originalStop;
    }
    
    /**
     * Feature #21: Profit Velocity Exit
     * Exit if profit momentum slows significantly.
     */
    public ExitStrategyManager.ExitDecision evaluateProfitVelocityExit(
            TradePosition position, 
            double currentPrice,
            double peakVelocity) {
        
        if (!config.isProfitVelocityExitEnabled()) {
            return ExitStrategyManager.ExitDecision.noExit();
        }
        
        double currentProfit = position.getProfitPercent(currentPrice);
        
        // Only check if we have profit
        if (currentProfit < config.getMinProfitForVelocityExit()) {
            return ExitStrategyManager.ExitDecision.noExit();
        }
        
        // Calculate current velocity (profit per hour)
        Duration holdTime = Duration.between(position.entryTime(), Instant.now());
        double hoursHeld = holdTime.toMinutes() / 60.0;
        if (hoursHeld < 0.25) return ExitStrategyManager.ExitDecision.noExit(); // Need at least 15 min
        
        double currentVelocity = currentProfit / hoursHeld;
        
        // If velocity has dropped significantly from peak
        if (peakVelocity > 0) {
            double velocityDrop = (peakVelocity - currentVelocity) / peakVelocity;
            
            if (velocityDrop > config.getVelocityDropThreshold()) {
                logger.info("Velocity exit: {} - dropped {}% ({}%/hr → {}%/hr)",
                    position.symbol(),
                    String.format("%.0f", velocityDrop * 100),
                    String.format("%.2f", peakVelocity * 100),
                    String.format("%.2f", currentVelocity * 100));
                
                return ExitStrategyManager.ExitDecision.fullExit(
                    ExitStrategyManager.ExitType.VELOCITY_DROP,
                    String.format("Velocity dropped %.0f%% (%.2f%%/hr → %.2f%%/hr)",
                        velocityDrop * 100, peakVelocity * 100, currentVelocity * 100),
                    currentPrice
                );
            }
        }
        
        return ExitStrategyManager.ExitDecision.noExit();
    }
    
    /**
     * Feature #23: EOD Profit Lock
     * Lock in profits 30 minutes before market close.
     */
    public ExitStrategyManager.ExitDecision evaluateEODProfitLock(
            TradePosition position, 
            double currentPrice) {
        
        if (!config.isEODProfitLockEnabled()) {
            return ExitStrategyManager.ExitDecision.noExit();
        }
        
        var now = java.time.ZonedDateTime.now(java.time.ZoneId.of("America/New_York"));
        var currentTime = now.toLocalTime();
        var lockTime = java.time.LocalTime.parse(config.getEODProfitLockTime());
        
        // Check if we're in the EOD lock window
        if (currentTime.isAfter(lockTime)) {
            double profitPercent = position.getProfitPercent(currentPrice);
            
            // Only lock if profitable and held less than min hours
            if (profitPercent > 0) {
                Duration holdTime = Duration.between(position.entryTime(), Instant.now());
                int minHours = config.getEODProfitLockMinHoldHours();
                
                if (holdTime.toHours() < minHours) {
                    logger.info("EOD profit lock: {} at +{}% (held {}h, avoid overnight risk)",
                        position.symbol(),
                        String.format("%.1f", profitPercent * 100),
                        holdTime.toHours());
                    
                    return ExitStrategyManager.ExitDecision.fullExit(
                        ExitStrategyManager.ExitType.EOD_LOCK,
                        String.format("EOD profit lock at +%.1f%% (avoid overnight risk)",
                            profitPercent * 100),
                        currentPrice
                    );
                }
            }
        }
        
        return ExitStrategyManager.ExitDecision.noExit();
    }
    
    /**
     * Feature #25: Quick Profit Scalping
     * Take quick profits on fast-moving positions.
     */
    public ExitStrategyManager.ExitDecision evaluateQuickProfitScalp(
            TradePosition position, 
            double currentPrice) {
        
        if (!config.isQuickProfitScalpingEnabled()) {
            return ExitStrategyManager.ExitDecision.noExit();
        }
        
        double profitPercent = position.getProfitPercent(currentPrice);
        Duration holdTime = Duration.between(position.entryTime(), Instant.now());
        long minutesHeld = holdTime.toMinutes();
        
        // Check 30-minute rule: +1.0% in 30 minutes
        if (minutesHeld <= 30 && profitPercent >= config.getQuickScalp30MinProfit()) {
            logger.info("Quick scalp (30min): {} at +{}% in {} minutes",
                position.symbol(),
                String.format("%.1f", profitPercent * 100),
                minutesHeld);
            
            return ExitStrategyManager.ExitDecision.partialExit(
                ExitStrategyManager.ExitType.QUICK_SCALP,
                position.quantity() * 0.75, // Exit 75%
                String.format("Quick scalp: +%.1f%% in %d minutes", 
                    profitPercent * 100, minutesHeld),
                currentPrice
            );
        }
        
        // Check 15-minute rule: +0.5% in 15 minutes
        if (minutesHeld <= 15 && profitPercent >= config.getQuickScalp15MinProfit()) {
            logger.info("Quick scalp (15min): {} at +{}% in {} minutes",
                position.symbol(),
                String.format("%.1f", profitPercent * 100),
                minutesHeld);
            
            double exitPercent = config.getQuickScalpExitPercent();
            return ExitStrategyManager.ExitDecision.partialExit(
                ExitStrategyManager.ExitType.QUICK_SCALP,
                position.quantity() * exitPercent, // Exit 50%
                String.format("Quick scalp: +%.1f%% in %d minutes", 
                    profitPercent * 100, minutesHeld),
                currentPrice
            );
        }
        
        return ExitStrategyManager.ExitDecision.noExit();
    }
}
