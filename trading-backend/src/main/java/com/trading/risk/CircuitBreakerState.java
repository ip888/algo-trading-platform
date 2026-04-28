package com.trading.risk;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Per-broker session circuit breaker.
 *
 * Two trip conditions:
 *   1. Consecutive losing trades ≥ {@code maxConsecutiveLosses}
 *   2. Session $-drawdown / sessionStartEquity ≥ {@code maxDrawdownPct}
 *
 * When tripped, {@link #shouldHaltEntries()} returns true until {@link #resetForNewSession(double)}
 * is called (typically at NY session open). All counters auto-reset on a new NY trading date.
 *
 * Thread-safe: a single {@link ReentrantLock} guards all mutable state. Reads acquire the lock
 * to publish a consistent snapshot.
 */
public final class CircuitBreakerState {

    private static final ZoneId NY = ZoneId.of("America/New_York");

    private final int maxConsecutiveLosses;
    private final double maxDrawdownPct;

    private final ReentrantLock lock = new ReentrantLock();

    private int consecutiveLosses = 0;
    private double sessionStartEquity = 0.0;
    private double sessionLowEquity = 0.0;
    private LocalDate sessionDate = LocalDate.now(NY);
    private TripReason tripped = null;

    public enum TripReason { CONSECUTIVE_LOSSES, SESSION_DRAWDOWN }

    public CircuitBreakerState(int maxConsecutiveLosses, double maxDrawdownPct) {
        this.maxConsecutiveLosses = Math.max(1, maxConsecutiveLosses);
        this.maxDrawdownPct = Math.max(0.0, maxDrawdownPct);
    }

    public void resetForNewSession(double currentEquity) {
        lock.lock();
        try {
            consecutiveLosses = 0;
            sessionStartEquity = currentEquity;
            sessionLowEquity = currentEquity;
            sessionDate = LocalDate.now(NY);
            tripped = null;
        } finally {
            lock.unlock();
        }
    }

    /** Auto-reset if NY date has rolled over (caller passes current equity). */
    public void rolloverIfNewDay(double currentEquity) {
        lock.lock();
        try {
            LocalDate today = LocalDate.now(NY);
            if (!today.equals(sessionDate)) {
                consecutiveLosses = 0;
                sessionStartEquity = currentEquity;
                sessionLowEquity = currentEquity;
                sessionDate = today;
                tripped = null;
            }
        } finally {
            lock.unlock();
        }
    }

    /** Record an outcome ($-pnl signed). Loss = pnl < 0; pnl == 0 leaves the streak unchanged. */
    public void recordTrade(double pnl) {
        lock.lock();
        try {
            if (pnl < 0) {
                consecutiveLosses++;
                if (consecutiveLosses >= maxConsecutiveLosses && tripped == null) {
                    tripped = TripReason.CONSECUTIVE_LOSSES;
                }
            } else if (pnl > 0) {
                consecutiveLosses = 0;
            }
        } finally {
            lock.unlock();
        }
    }

    /** Update session low watermark; trip on session drawdown. */
    public void updateEquity(double currentEquity) {
        lock.lock();
        try {
            if (currentEquity < sessionLowEquity) {
                sessionLowEquity = currentEquity;
            }
            if (sessionStartEquity > 0 && tripped == null) {
                double drawdown = (sessionStartEquity - sessionLowEquity) / sessionStartEquity;
                if (drawdown >= maxDrawdownPct) {
                    tripped = TripReason.SESSION_DRAWDOWN;
                }
            }
        } finally {
            lock.unlock();
        }
    }

    public boolean shouldHaltEntries() {
        lock.lock();
        try {
            return tripped != null;
        } finally {
            lock.unlock();
        }
    }

    public TripReason tripReason() {
        lock.lock();
        try {
            return tripped;
        } finally {
            lock.unlock();
        }
    }

    public int getConsecutiveLosses() {
        lock.lock();
        try {
            return consecutiveLosses;
        } finally {
            lock.unlock();
        }
    }

    public double getSessionDrawdownPct() {
        lock.lock();
        try {
            if (sessionStartEquity <= 0) return 0.0;
            return (sessionStartEquity - sessionLowEquity) / sessionStartEquity;
        } finally {
            lock.unlock();
        }
    }

    public ZonedDateTime sessionStartedAt() {
        lock.lock();
        try {
            return sessionDate.atStartOfDay(NY);
        } finally {
            lock.unlock();
        }
    }
}
