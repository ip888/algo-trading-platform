package com.trading.exits;

import com.trading.config.Config;
import com.trading.risk.TradePosition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
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
        double expectedPrice, // Expected exit price (for limit orders)
        int partialLevel      // 1/2/3 for partial profit levels, 0 otherwise
    ) {
        public static ExitDecision noExit() {
            return new ExitDecision(ExitType.NONE, 0.0, "No exit signal", false, 0.0, 0);
        }

        public static ExitDecision fullExit(ExitType type, String reason, double price) {
            return new ExitDecision(type, 1.0, reason, false, price, 0);
        }

        public static ExitDecision partialExit(ExitType type, double quantity, String reason, double price) {
            return new ExitDecision(type, quantity, reason, true, price, 0);
        }

        public static ExitDecision partialProfitExit(int level, double quantity, String reason, double price) {
            return new ExitDecision(ExitType.PARTIAL_PROFIT, quantity, reason, true, price, level);
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
        QUICK_SCALP,      // Feature #25
        // Tier 2 / Tier 3 (Apr 2026)
        SCALE_OUT_1R,     // Tier 2.6 — partial profit at +1R
        TIME_STOP,        // Tier 2.7 — N bars with no progress → exit
        // META-incident hardening
        CATASTROPHIC_LOSS, // Trumps everything: position loss ≥ catastrophicLossPercent
        EARNINGS_PROTECTION // Pre-earnings force-exit (≥ N hours before announcement)
    }

    // Bitmask slot used by markPartialExit to track that the 1R scale-out has been done.
    // 1/2/3 are taken by the percentage-of-target partials; we use slot 4.
    private static final int SCALE_OUT_1R_LEVEL = 4;
    
    /**
     * Evaluate all exit strategies and return the highest priority exit decision.
     */
    public ExitDecision evaluateExit(TradePosition position, double currentPrice,
                                     double currentVolatility, Map<String, Double> portfolioPositions) {

        // Priority 0: Catastrophic loss (trumps everything).
        // Last-line defense for the META scenario — a broker-side stop that silently
        // failed (e.g. fractional position) would let a position drift far past the
        // strategy stop. This check forces a market exit regardless of other rules.
        if (config.isCatastrophicLossExitEnabled()) {
            double lossPercent = position.getProfitPercent(currentPrice) * 100.0;
            double threshold = config.getCatastrophicLossPercent();
            if (lossPercent <= -threshold) {
                return ExitDecision.fullExit(ExitType.CATASTROPHIC_LOSS,
                    String.format("Catastrophic loss %.2f%% (≥ %.1f%% threshold)",
                        Math.abs(lossPercent), threshold),
                    currentPrice);
            }
        }

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
        
        // Priority 2.5: Scale-out at +1R (Tier 2.6).
        // Banking part of the position at +1R locks in a guaranteed profit and dramatically
        // improves expectancy by neutralizing the back-half if price reverses to break-even.
        ExitDecision scaleOut = evaluateScaleOutAtR(position, currentPrice);
        if (scaleOut.type() != ExitType.NONE) {
            return scaleOut;
        }

        // Priority 3: Partial profit taking
        ExitDecision partialExit = evaluatePartialExit(position, currentPrice);
        if (partialExit.type() != ExitType.NONE) {
            return partialExit;
        }

        // Priority 3.5: Time-based stop (Tier 2.7).
        // If a position has been open ≥ N bars (default 3 daily bars ≈ 3 trading days)
        // and price hasn't moved more than X * R in either direction, abandon — the
        // setup is dead, free the capital. Distinct from time-decay which only
        // touches profitable positions.
        ExitDecision timeStop = evaluateTimeStop(position, currentPrice);
        if (timeStop.type() != ExitType.NONE) {
            return timeStop;
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
        ExitDecision correlationExit = evaluateCorrelationExit(position, currentPrice, portfolioPositions);
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
            return ExitDecision.partialProfitExit(3, PARTIAL_EXIT_SIZE_3,
                String.format("Partial exit at 75%% profit target (%.1f%% profit)", profitPercent * 100),
                currentPrice);
        }

        if (progressToTarget >= PARTIAL_EXIT_LEVEL_2 && !position.hasPartialExit(2)) {
            return ExitDecision.partialProfitExit(2, PARTIAL_EXIT_SIZE_2,
                String.format("Partial exit at 50%% profit target (%.1f%% profit)", profitPercent * 100),
                currentPrice);
        }

        if (progressToTarget >= PARTIAL_EXIT_LEVEL_1 && !position.hasPartialExit(1)) {
            return ExitDecision.partialProfitExit(1, PARTIAL_EXIT_SIZE_1,
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
        
        // Exit PROFITABLE positions after configured max hold time (lock in gains)
        // Do NOT sell losing positions on time alone — let them recover.
        // Losers are still protected by stop-loss (emergency at -2.5%) and strategy SELL signals.
        long maxHoldHours = config.getMaxHoldTimeHours();

        if (holdTime.toHours() >= maxHoldHours && profitPercent > 0.001) {
            return ExitDecision.fullExit(ExitType.TIME_DECAY,
                String.format("Time decay exit after %d hours (+%.1f%% profit locked)",
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
    private ExitDecision evaluateCorrelationExit(TradePosition position, double currentPrice,
                                                 Map<String, Double> portfolioPositions) {
        // If we have too many positions (over-concentrated)
        int maxPositions = config.getPositionSizingMaxCorrelatedPositions();

        if (portfolioPositions.size() > maxPositions) {
            double profitPercent = position.getProfitPercent(currentPrice);

            if (profitPercent > 0.02) { // At least 2% profit
                return ExitDecision.partialExit(ExitType.CORRELATION, 0.50,
                    String.format("Correlation exit - reducing exposure (%d positions)",
                        portfolioPositions.size()),
                    currentPrice);
            }
        }

        return ExitDecision.noExit();
    }
    
    /**
     * Tier 2.6 — Scale-out at +1R.
     *
     * R is the per-share risk at entry: R = entry − stop. When price reaches entry + R,
     * sell {@code config.getScaleOutFraction()} of the position (default 50%). The remaining
     * shares ride toward the take-profit with the trailing stop now covering full risk.
     *
     * Fires at most once per position; tracked via {@code SCALE_OUT_1R_LEVEL} bitmask slot.
     */
    private ExitDecision evaluateScaleOutAtR(TradePosition position, double currentPrice) {
        if (!config.isScaleOutEnabled()) return ExitDecision.noExit();
        if (position.hasPartialExit(SCALE_OUT_1R_LEVEL)) return ExitDecision.noExit();

        double r = position.entryPrice() - position.stopLoss();
        if (r <= 0) return ExitDecision.noExit();

        double trigger = position.entryPrice() + config.getScaleOutTriggerR() * r;
        if (currentPrice < trigger) return ExitDecision.noExit();

        double fraction = config.getScaleOutFraction();
        return new ExitDecision(
            ExitType.SCALE_OUT_1R,
            fraction,
            String.format("Scale-out at +%.2fR ($%.2f → $%.2f, locking %.0f%%)",
                config.getScaleOutTriggerR(), position.entryPrice(), currentPrice, fraction * 100),
            true,
            currentPrice,
            SCALE_OUT_1R_LEVEL
        );
    }

    /**
     * Tier 2.7 — Time-based stop.
     *
     * If a position has been open for at least {@code config.getTimeStopBars()} trading
     * days and price hasn't moved more than {@code config.getTimeStopMaxMoveR()} × R
     * in either direction, exit — the setup is dead, the capital is better deployed
     * elsewhere. This catches the 'limped sideways for a week' scenario that the
     * existing TIME_DECAY check (which requires profitability) misses.
     */
    private ExitDecision evaluateTimeStop(TradePosition position, double currentPrice) {
        if (!config.isTimeStopEnabled()) return ExitDecision.noExit();

        // Approximate "bars" as trading days held. 24h hold = ~1 daily bar.
        long hoursHeld = Duration.between(position.entryTime(), Instant.now()).toHours();
        long requiredHours = config.getTimeStopBars() * 24L;
        if (hoursHeld < requiredHours) return ExitDecision.noExit();

        double r = position.entryPrice() - position.stopLoss();
        if (r <= 0) return ExitDecision.noExit();

        double moveAbs = Math.abs(currentPrice - position.entryPrice());
        double moveInR = moveAbs / r;
        if (moveInR > config.getTimeStopMaxMoveR()) return ExitDecision.noExit();

        double profitPercent = position.getProfitPercent(currentPrice);
        return ExitDecision.fullExit(ExitType.TIME_STOP,
            String.format("Time stop: %dh held, only %.2fR move (%.2f%% pnl) — abandoning",
                hoursHeld, moveInR, profitPercent * 100),
            currentPrice);
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
