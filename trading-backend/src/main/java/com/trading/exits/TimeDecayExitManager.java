package com.trading.exits;

import com.trading.config.Config;
import com.trading.risk.TradePosition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;

/**
 * Time-Decay Exit Manager
 * Exits flat positions that aren't moving after specified time
 */
public class TimeDecayExitManager {
    private static final Logger logger = LoggerFactory.getLogger(TimeDecayExitManager.class);
    
    private final Config config;

    // Overridable clock — replaced by the backtest harness to replay historical hold times.
    // Default behavior (real wall clock) is unchanged for live trading.
    private java.util.function.Supplier<Instant> nowSupplier = Instant::now;

    public TimeDecayExitManager(Config config) {
        this.config = config;
    }

    /** Visible for the backtest harness — injects a fixed/moving clock instead of the real one. */
    public void setNowSupplier(java.util.function.Supplier<Instant> supplier) {
        this.nowSupplier = supplier;
    }

    /**
     * Check if position should be exited due to time decay
     * @param position Position to check
     * @param currentPrice Current price
     * @return true if should exit
     */
    public boolean shouldExit(TradePosition position, double currentPrice) {
        if (!config.isTimeDecayExits()) {
            return false;
        }

        // Calculate how long position has been held (fractional hours — thresholds like
        // 1.5h need finer granularity than whole-hour truncation gives)
        Duration held = Duration.between(position.entryTime(), nowSupplier.get());
        double hoursHeld = held.toMinutes() / 60.0;

        // Check if held long enough
        if (hoursHeld < config.getFlatPositionHours()) {
            return false;
        }
        
        // Calculate P&L percentage
        double pnlPercent = Math.abs(
            ((currentPrice - position.entryPrice()) / position.entryPrice()) * 100.0
        );
        
        // Exit if flat (within threshold)
        if (pnlPercent < config.getFlatPositionThreshold()) {
            logger.info("⏰ {} Time-Decay Exit: Held {}h, P&L only ±{}% (threshold: {}%)",
                position.symbol(), String.format("%.1f", hoursHeld), String.format("%.2f", pnlPercent), String.format("%.2f", config.getFlatPositionThreshold()));
            return true;
        }
        
        return false;
    }
    
    /**
     * Get reason for exit (for logging)
     */
    public String getExitReason(TradePosition position, double currentPrice) {
        Duration held = Duration.between(position.entryTime(), nowSupplier.get());
        double hoursHeld = held.toMinutes() / 60.0;
        double pnlPercent = ((currentPrice - position.entryPrice()) / position.entryPrice()) * 100.0;

        return String.format("Time-decay: Held %.1fh with only %.2f%% P&L",
            hoursHeld, pnlPercent);
    }
}
