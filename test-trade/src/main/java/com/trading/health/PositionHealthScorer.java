package com.trading.health;

import com.trading.risk.TradePosition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;

/**
 * Position Health Scorer
 * Scores each position's health for better exit decisions
 */
public class PositionHealthScorer {
    private static final Logger logger = LoggerFactory.getLogger(PositionHealthScorer.class);
    
    /**
     * Score position health (0-100)
     * Higher score = healthier position
     */
    public double scorePosition(TradePosition position, double currentPrice, double momentum) {
        double score = 50; // Start neutral
        
        // P&L factor (±30 points)
        double pnlPercent = ((currentPrice - position.entryPrice()) / position.entryPrice()) * 100.0;
        if (pnlPercent > 2.0) {
            score += 30; // Strong profit
        } else if (pnlPercent > 1.0) {
            score += 20;
        } else if (pnlPercent > 0) {
            score += 10;
        } else if (pnlPercent < -2.0) {
            score -= 30; // Large loss
        } else if (pnlPercent < -1.0) {
            score -= 20;
        } else if (pnlPercent < 0) {
            score -= 10;
        }
        
        // Time held factor (±15 points)
        Duration held = Duration.between(position.entryTime(), Instant.now());
        long hoursHeld = held.toHours();
        if (hoursHeld > 48) {
            score -= 15; // Held too long
        } else if (hoursHeld > 24) {
            score -= 10;
        } else if (hoursHeld < 2) {
            score += 5; // Fresh position
        }
        
        // Momentum factor (±20 points)
        if (momentum > 2.0) {
            score += 20; // Strong momentum
        } else if (momentum > 1.0) {
            score += 10;
        } else if (momentum < -2.0) {
            score -= 20; // Negative momentum
        } else if (momentum < -1.0) {
            score -= 10;
        }
        
        // Ensure within bounds
        score = Math.max(0, Math.min(100, score));
        
        logger.debug("{} Health Score: {:.0f} (P&L:{:.2f}% Held:{}h Mom:{:.2f}%)",
            position.symbol(), score, pnlPercent, hoursHeld, momentum);
        
        return score;
    }
    
    /**
     * Determine if position should be closed based on health
     */
    public boolean shouldCloseUnhealthy(double healthScore) {
        return healthScore < 30; // Close if very unhealthy
    }
}
