package com.trading.persistence;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("TradeDatabase — rolling symbol stats + fill updates")
class TradeDatabaseRollingStatsTest {

    private static final String DB = "test-rolling-stats.db";
    private TradeDatabase db;

    @BeforeEach void setUp() { new File(DB).delete(); db = new TradeDatabase(DB); }
    @AfterEach void tearDown() { db.close(); new File(DB).delete(); }

    private void trade(String sym, String regime, Instant exit, double entry, double exitPx) {
        db.recordTradeWithContext(sym, "MACD", "MAIN", "alpaca", exit.minus(1, ChronoUnit.HOURS),
            entry, 1.0, entry * 0.99, entry * 1.02, regime, 10.0, 0.5);
        db.closeTrade(sym, exit, exitPx, 0.0, "alpaca", "time_decay");
    }

    @Test
    @DisplayName("old trades age out of the window, so a blocked symbol recovers on its own")
    void oldTradesAgeOut() {
        Instant now = Instant.now();
        for (int i = 0; i < 6; i++) trade("TSLA", "WEAK_BULL", now.minus(60 + i, ChronoUnit.DAYS), 100, 99); // old losers
        assertNull(db.getRecentSymbolStats("TSLA", "WEAK_BULL", 30, 12), "60-day-old trades are outside a 30d window");

        for (int i = 0; i < 6; i++) trade("TSLA", "WEAK_BULL", now.minus(i + 1, ChronoUnit.DAYS), 100, 99);
        var s = db.getRecentSymbolStats("TSLA", "WEAK_BULL", 30, 12);
        assertEquals(6, s.trades());
        assertEquals(0.0, s.winRate(), 1e-9);
        assertTrue(s.avgReturnPct() < 0);
    }

    @Test
    @DisplayName("payoff-aware: low win rate but positive average return is NOT reported as negative expectancy")
    void payoffAware() {
        Instant now = Instant.now();
        trade("AAPL", "WEAK_BULL", now.minus(1, ChronoUnit.DAYS), 100, 105);   // +5
        trade("AAPL", "WEAK_BULL", now.minus(2, ChronoUnit.DAYS), 100, 99);    // -1
        trade("AAPL", "WEAK_BULL", now.minus(3, ChronoUnit.DAYS), 100, 99);    // -1
        trade("AAPL", "WEAK_BULL", now.minus(4, ChronoUnit.DAYS), 100, 99);    // -1
        var s = db.getRecentSymbolStats("AAPL", "WEAK_BULL", 30, 12);
        assertEquals(0.25, s.winRate(), 1e-9);
        assertTrue(s.avgReturnPct() > 0, "25% wins but a 5:1 payoff is profitable — the gate must not block it");
    }

    @Test
    @DisplayName("other regimes and 'recovered' rows are excluded")
    void regimeAndRecoveredFiltered() {
        Instant now = Instant.now();
        trade("QQQ", "RANGE_BOUND", now.minus(1, ChronoUnit.DAYS), 100, 90);
        assertNull(db.getRecentSymbolStats("QQQ", "WEAK_BULL", 30, 12));
        assertNotNull(db.getRecentSymbolStats("QQQ", null, 30, 12), "null regime = any regime");
    }

    @Test
    @DisplayName("updateTradeFills rewrites a closed trade; getClosedTradesBetween filters by exit time")
    void fillUpdateAndRange() {
        Instant exit = Instant.parse("2026-09-25T15:00:00Z");
        trade("SPY", "WEAK_BULL", exit, 100, 101);
        var rows = db.getClosedTradesBetween("alpaca", Instant.parse("2026-09-25T04:00:00Z"), Instant.parse("2026-09-26T04:00:00Z"));
        assertEquals(1, rows.size());
        db.updateTradeFills(rows.get(0).id(), 100.05, 100.90, 0.85);
        var after = db.getClosedTradesBetween("alpaca", Instant.parse("2026-09-25T04:00:00Z"), Instant.parse("2026-09-26T04:00:00Z")).get(0);
        assertEquals(100.05, after.entryPrice(), 1e-9);
        assertEquals(0.85, after.pnl(), 1e-9);
        assertTrue(db.getClosedTradesBetween("alpaca", Instant.parse("2026-09-26T04:00:00Z"), Instant.parse("2026-09-27T04:00:00Z")).isEmpty());
    }
}
