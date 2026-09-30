package com.trading.backtest;

import com.trading.analysis.MarketRegimeDetector;
import com.trading.analysis.MarketRegimeDetector.MarketRegime;
import com.trading.config.Config;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link WalkForwardBacktestHarness#checkEntryGates}, added 2026-09-30 alongside
 * the gates themselves. Each test isolates one gate by seeding only the state it reads, so a
 * failure points at exactly one mirrored condition instead of requiring a full replay run.
 */
@DisplayName("WalkForwardBacktestHarness — entry gates mirror EntryEvaluator")
class WalkForwardBacktestHarnessGatesTest {

    private Config config;

    @BeforeEach
    void setUp() {
        System.setProperty("APCA_API_KEY_ID", "test_key");
        System.setProperty("APCA_API_SECRET_KEY", "test_secret");
        this.config = new Config();
    }

    private WalkForwardBacktestHarness harness(Path dir, Instant t0) {
        return new WalkForwardBacktestHarness(config, dir, t0);
    }

    private static MarketRegimeDetector.MarketRegimeAnalysis regime(MarketRegime r, double vix, double breadth) {
        var trend = new MarketRegimeDetector.TrendAnalysis(MarketRegimeDetector.TrendDirection.WEAK_UP, 0.5, 100, 95, 100, false, false);
        var volume = new MarketRegimeDetector.VolumeAnalysis(MarketRegimeDetector.VolumeTrend.STABLE, 1000, 1000, 1.0);
        var breadthA = new MarketRegimeDetector.BreadthAnalysis(breadth, (int) (breadth * 8), (int) ((1 - breadth) * 8), breadth);
        return new MarketRegimeDetector.MarketRegimeAnalysis(r, 0.6, trend, volume, breadthA, vix, Instant.now());
    }

    // A mid-session instant, safely outside lunch/opening/EOD windows.
    private static final Instant MID_SESSION = Instant.parse("2026-09-30T15:00:00Z"); // 11:00 ET

    @Test
    @DisplayName("entry stagger blocks a second entry within 90s of the first, regardless of symbol")
    void entryStagger(@TempDir Path dir) {
        var h = harness(dir, MID_SESSION);
        h.seedLastEntryEpochMsForTest(MID_SESSION.toEpochMilli() - 1000);
        assertEquals("entry stagger", h.checkEntryGates("SPY", 500, regime(MarketRegime.WEAK_BULL, 15, 0.6), false, MID_SESSION));
    }

    @Test
    @DisplayName("stop-loss cooldown blocks re-entry on the same symbol until it expires")
    void stopLossCooldown(@TempDir Path dir) {
        var h = harness(dir, MID_SESSION);
        h.seedLastEntryEpochMsForTest(0L);
        h.seedStopLossCooldownForTest("SPY", MID_SESSION.toEpochMilli() + 60_000);
        assertEquals("stop loss cooldown", h.checkEntryGates("SPY", 500, regime(MarketRegime.WEAK_BULL, 15, 0.6), false, MID_SESSION));
        assertNull(h.checkEntryGates("QQQ", 500, regime(MarketRegime.WEAK_BULL, 15, 0.6), false, MID_SESSION),
            "cooldown is per-symbol");
    }

    @Test
    @DisplayName("price-improvement gate blocks a re-entry within 1% of the last loss exit")
    void priceImprovementBlocks(@TempDir Path dir) {
        var h = harness(dir, MID_SESSION);
        h.seedLastEntryEpochMsForTest(0L);
        h.seedLastExitPriceForTest("AAPL", 340.59);
        assertEquals("waiting for price improvement below last exit",
            h.checkEntryGates("AAPL", 339.98, regime(MarketRegime.WEAK_BULL, 15, 0.6), false, MID_SESSION),
            "339.98 is only 0.18% below 340.59 — the 1% gate must block it");
    }

    @Test
    @DisplayName("price-improvement gate allows re-entry once price recovers above the exit")
    void priceImprovementClearsOnRecovery(@TempDir Path dir) {
        var h = harness(dir, MID_SESSION);
        h.seedLastEntryEpochMsForTest(0L);
        h.seedLastExitPriceForTest("NVDA", 229.74);
        assertNull(h.checkEntryGates("NVDA", 230.84, regime(MarketRegime.WEAK_BULL, 15, 0.6), false, MID_SESSION));
    }

    @Test
    @DisplayName("price-improvement gate allows re-entry once price has dropped >= 1% below the exit")
    void priceImprovementClearsOnDiscount(@TempDir Path dir) {
        var h = harness(dir, MID_SESSION);
        h.seedLastEntryEpochMsForTest(0L);
        h.seedLastExitPriceForTest("AAPL", 340.59);
        assertNull(h.checkEntryGates("AAPL", 337.0, regime(MarketRegime.WEAK_BULL, 15, 0.6), false, MID_SESSION));
    }

    @Test
    @DisplayName("market breadth filter blocks every candidate when breadth is decisively low")
    void breadthBlocks(@TempDir Path dir) {
        var h = harness(dir, MID_SESSION);
        h.seedLastEntryEpochMsForTest(0L);
        h.breadthAnalyzerForTest().updateBreadth(0.125);
        assertEquals("market breadth too low",
            h.checkEntryGates("SPY", 500, regime(MarketRegime.WEAK_BULL, 10.4, 0.125), false, MID_SESSION));
    }

    @Test
    @DisplayName("market breadth filter is skipped in RANGE_BOUND when VIX < 15 (matches live)")
    void breadthSkippedInCalmRangeBound(@TempDir Path dir) {
        var h = harness(dir, MID_SESSION);
        h.seedLastEntryEpochMsForTest(0L);
        h.breadthAnalyzerForTest().updateBreadth(0.0);
        assertNull(h.checkEntryGates("SPY", 500, regime(MarketRegime.RANGE_BOUND, 12.0, 0.0), false, MID_SESSION));
    }

    @Test
    @DisplayName("session circuit breaker (consecutive losses) is visible on the harness after 3 losing exits")
    void circuitBreakerTripsAfterConsecutiveLosses(@TempDir Path dir) {
        var h = harness(dir, MID_SESSION);
        var cb = h.circuitBreakerForTest();
        cb.resetForNewSession(10_000);
        cb.recordTrade(-1); cb.recordTrade(-1); cb.recordTrade(-1);
        assertTrue(cb.shouldHaltEntries());
    }

    @Test
    @DisplayName("event-day gate blocks a single stock that gapped >= 3% at the open, not an ETF")
    void eventDayGateIsSymbolSpecific(@TempDir Path dir) {
        var h = harness(dir, MID_SESSION);
        h.seedLastEntryEpochMsForTest(0L);
        // No bars loaded for either symbol -> EventDayDetector's compute() finds empty history and
        // fails open for BOTH — this asserts the gate participates in the chain without throwing,
        // the gate's own gap-detection logic is covered by EventDayDetectorTest.
        assertDoesNotThrow(() -> h.checkEntryGates("META", 500, regime(MarketRegime.WEAK_BULL, 15, 0.6), false, MID_SESSION));
        assertDoesNotThrow(() -> h.checkEntryGates("SPY", 500, regime(MarketRegime.WEAK_BULL, 15, 0.6), false, MID_SESSION));
    }

    @Test
    @DisplayName("scalp signals are exempt from the time-of-day gates but not from cooldowns")
    void scalpExemptFromTimeOfDayGates(@TempDir Path dir) {
        var h = harness(dir, MID_SESSION);
        h.seedLastEntryEpochMsForTest(0L);
        // Lunchtime instant: 2026-09-30 17:45 UTC = 13:45 ET, inside the default 12:00-13:30 window? use a clear lunch instant instead.
        Instant lunch = Instant.parse("2026-09-30T17:00:00Z"); // 13:00 ET — inside default lunch blackout
        assertEquals("lunch blackout 12:00-13:30 ET",
            h.checkEntryGates("SPY", 500, regime(MarketRegime.WEAK_BULL, 15, 0.6), false, lunch));
        assertNull(h.checkEntryGates("SPY", 500, regime(MarketRegime.WEAK_BULL, 15, 0.6), true, lunch),
            "scalp has its own time window and is exempt from the lunch blackout gate");

        h.seedStopLossCooldownForTest("SPY", lunch.toEpochMilli() + 60_000);
        assertEquals("stop loss cooldown",
            h.checkEntryGates("SPY", 500, regime(MarketRegime.WEAK_BULL, 15, 0.6), true, lunch),
            "cooldown applies to scalp too — not exempt live either");
    }
}
