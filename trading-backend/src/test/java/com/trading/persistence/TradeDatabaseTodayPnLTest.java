package com.trading.persistence;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Fix 9 — getTodayPnL() must return only today's realized P&L (closed trades
 * whose exit_time falls on the current calendar day), not the all-time total.
 */
@DisplayName("TradeDatabase — getTodayPnL filters by today's date")
class TradeDatabaseTodayPnLTest {

    private TradeDatabase db;
    private static final String TEST_DB = "test-today-pnl.db";

    @BeforeEach
    void setUp() {
        new File(TEST_DB).delete();
        db = new TradeDatabase(TEST_DB);
    }

    @AfterEach
    void tearDown() {
        db.close();
        new File(TEST_DB).delete();
    }

    @Test
    @DisplayName("returns 0 when no trades closed today")
    void returnsZeroWhenEmpty() {
        assertEquals(0.0, db.getTodayPnL(), 0.001);
    }

    @Test
    @DisplayName("sums only trades closed today, not older ones")
    void sumsOnlyTodaysTrades() {
        Instant now = Instant.now();
        Instant yesterday = now.minus(1, ChronoUnit.DAYS);

        // Trade closed today
        db.recordTrade("SPY", "MACD", "MAIN", "alpaca",
            now.minus(2, ChronoUnit.HOURS), 500.0, 1.0, 490.0, 515.0);
        db.closeTrade("SPY", now, 515.0, 15.0, "alpaca"); // +$15 today

        // Trade closed yesterday — should be excluded
        db.recordTrade("QQQ", "MACD", "MAIN", "alpaca",
            yesterday.minus(2, ChronoUnit.HOURS), 400.0, 1.0, 390.0, 415.0);
        db.closeTrade("QQQ", yesterday, 390.0, -10.0, "alpaca"); // -$10 yesterday

        double todayPnL = db.getTodayPnL();
        assertEquals(15.0, todayPnL, 0.01,
            "getTodayPnL() must include only the SPY trade closed today, not QQQ from yesterday");
    }

    @Test
    @DisplayName("excludes OPEN trades from today's P&L")
    void excludesOpenTrades() {
        Instant now = Instant.now();
        db.recordTrade("AAPL", "MACD", "MAIN", "alpaca",
            now.minus(1, ChronoUnit.HOURS), 300.0, 1.0, 295.0, 309.0);
        // Not closed — still OPEN

        assertEquals(0.0, db.getTodayPnL(), 0.001,
            "Open trades must not contribute to todayPnL");
    }

    @Test
    @DisplayName("includes multiple trades closed today")
    void sumsMultipleTodayTrades() {
        Instant now = Instant.now();
        db.recordTrade("SPY", "MACD", "MAIN", "alpaca",
            now.minus(3, ChronoUnit.HOURS), 500.0, 2.0, 490.0, 515.0);
        db.closeTrade("SPY", now.minus(1, ChronoUnit.HOURS), 510.0, 20.0, "alpaca");

        db.recordTrade("GLD", "MACD", "MAIN", "alpaca",
            now.minus(2, ChronoUnit.HOURS), 200.0, 1.0, 195.0, 206.0);
        db.closeTrade("GLD", now, 198.0, -2.0, "alpaca");

        double total = db.getTodayPnL();
        assertEquals(18.0, total, 0.01, "20 - 2 = 18 for two trades closed today");
    }

    @Test
    @DisplayName("getTotalPnL still includes all-time closed trades")
    void totalPnLUnaffectedByTodayFilter() {
        Instant now = Instant.now();
        Instant yesterday = now.minus(1, ChronoUnit.DAYS);

        db.recordTrade("SPY", "MACD", "MAIN", "alpaca",
            now.minus(1, ChronoUnit.HOURS), 500.0, 1.0, 490.0, 515.0);
        db.closeTrade("SPY", now, 515.0, 15.0, "alpaca");

        db.recordTrade("QQQ", "MACD", "MAIN", "alpaca",
            yesterday.minus(2, ChronoUnit.HOURS), 400.0, 1.0, 390.0, 415.0);
        db.closeTrade("QQQ", yesterday, 390.0, -10.0, "alpaca");

        assertEquals(5.0, db.getTotalPnL(), 0.01, "totalPnL = 15 + (-10) = 5");
        assertEquals(15.0, db.getTodayPnL(), 0.01, "todayPnL = 15 only");
    }
}
