package com.trading.risk;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("CircuitBreakerState Tests (Tier 3.10)")
class CircuitBreakerStateTest {

    @Test
    @DisplayName("trips after N consecutive losses")
    void tripsOnConsecutiveLosses() {
        var cb = new CircuitBreakerState(3, 0.05);
        cb.resetForNewSession(10_000);
        cb.recordTrade(-100);
        cb.recordTrade(-100);
        assertFalse(cb.shouldHaltEntries());
        cb.recordTrade(-100);
        assertTrue(cb.shouldHaltEntries());
        assertEquals(CircuitBreakerState.TripReason.CONSECUTIVE_LOSSES, cb.tripReason());
    }

    @Test
    @DisplayName("a winning trade resets the loss streak")
    void winResetsStreak() {
        var cb = new CircuitBreakerState(3, 0.05);
        cb.resetForNewSession(10_000);
        cb.recordTrade(-50);
        cb.recordTrade(-50);
        cb.recordTrade(+100);   // win
        cb.recordTrade(-50);
        cb.recordTrade(-50);
        assertFalse(cb.shouldHaltEntries(), "two losses < 3 should not trip");
    }

    @Test
    @DisplayName("trips on session drawdown")
    void tripsOnDrawdown() {
        var cb = new CircuitBreakerState(10, 0.05); // 5% DD
        cb.resetForNewSession(10_000);
        cb.updateEquity(9_700); // 3% DD
        assertFalse(cb.shouldHaltEntries());
        cb.updateEquity(9_400); // 6% DD
        assertTrue(cb.shouldHaltEntries());
        assertEquals(CircuitBreakerState.TripReason.SESSION_DRAWDOWN, cb.tripReason());
    }

    @Test
    @DisplayName("breakeven trades leave the streak untouched")
    void breakevenIsNeutral() {
        var cb = new CircuitBreakerState(2, 0.10);
        cb.resetForNewSession(10_000);
        cb.recordTrade(-50);
        cb.recordTrade(0.0);     // breakeven
        cb.recordTrade(-50);
        assertTrue(cb.shouldHaltEntries(), "two losses with breakeven in between should still trip");
    }

    @Test
    @DisplayName("resetForNewSession clears all state")
    void resetClears() {
        var cb = new CircuitBreakerState(2, 0.05);
        cb.resetForNewSession(10_000);
        cb.recordTrade(-100);
        cb.recordTrade(-100);
        assertTrue(cb.shouldHaltEntries());
        cb.resetForNewSession(11_000);
        assertFalse(cb.shouldHaltEntries());
        assertEquals(0, cb.getConsecutiveLosses());
    }

    @Test
    @DisplayName("drawdown pct uses the session start equity baseline")
    void drawdownReadsBaseline() {
        var cb = new CircuitBreakerState(10, 0.10);
        cb.resetForNewSession(10_000);
        cb.updateEquity(9_500);
        assertEquals(0.05, cb.getSessionDrawdownPct(), 1e-9);
    }
}
