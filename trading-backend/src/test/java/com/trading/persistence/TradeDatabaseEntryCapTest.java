package com.trading.persistence;

import org.junit.jupiter.api.*;
import java.io.File;
import java.time.Instant;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for new TradeDatabase methods added to support the entry-cap and
 * in-memory portfolio restore fixes:
 *  - countOpenTrades(symbol, broker)
 *  - getOpenTradeRecords(broker)
 *  - getRecentClosedTrades(limit)
 */
@DisplayName("TradeDatabase — entry cap and restore methods")
class TradeDatabaseEntryCapTest {

    private TradeDatabase db;
    private static final String TEST_DB = "test-entry-cap.db";

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

    // ── countOpenTrades ────────────────────────────────────────────────────────

    @Test
    @DisplayName("countOpenTrades returns 0 when no trades exist")
    void countOpen_empty() {
        assertEquals(0, db.countOpenTrades("QQQ", "tradier"));
    }

    @Test
    @DisplayName("countOpenTrades counts only OPEN records for the given symbol+broker")
    void countOpen_multipleEntries() {
        db.recordTrade("QQQ", "RSI", "TRADIER", "tradier", Instant.now(), 640.0, 31.0, 634.0, 656.0);
        db.recordTrade("QQQ", "RSI", "TRADIER", "tradier", Instant.now(), 645.0, 25.0, 639.0, 661.0);
        db.recordTrade("SPY", "MACD", "TRADIER", "tradier", Instant.now(), 700.0, 10.0, 693.0, 721.0);
        db.recordTrade("QQQ", "RSI", "ALPACA", "alpaca", Instant.now(), 641.0, 5.0, 635.0, 657.0);

        assertEquals(2, db.countOpenTrades("QQQ", "tradier"), "Only Tradier QQQ open entries");
        assertEquals(1, db.countOpenTrades("SPY", "tradier"));
        assertEquals(1, db.countOpenTrades("QQQ", "alpaca"));
    }

    @Test
    @DisplayName("countOpenTrades excludes CLOSED trades")
    void countOpen_excludesClosed() {
        db.recordTrade("GLD", "MACD", "ALPACA", "alpaca", Instant.now(), 440.0, 1.0, 433.0, 453.0);
        db.closeTrade("GLD", Instant.now(), 445.0, 5.0, "alpaca");

        assertEquals(0, db.countOpenTrades("GLD", "alpaca"), "Closed trade must not count");
    }

    // ── getOpenTradeRecords ────────────────────────────────────────────────────

    @Test
    @DisplayName("getOpenTradeRecords returns empty list when nothing open")
    void openRecords_empty() {
        assertTrue(db.getOpenTradeRecords("tradier").isEmpty());
    }

    @Test
    @DisplayName("getOpenTradeRecords returns aggregated record per symbol")
    void openRecords_aggregated() {
        db.recordTrade("QQQ", "RSI", "TRADIER", "tradier", Instant.now(), 640.0, 31.0, 634.0, 660.0);
        db.recordTrade("QQQ", "RSI", "TRADIER", "tradier", Instant.now(), 650.0, 20.0, 644.0, 670.0);
        db.recordTrade("SPY", "MACD", "TRADIER", "tradier", Instant.now(), 700.0, 10.0, 693.0, 721.0);

        List<TradeDatabase.OpenTradeRecord> records = db.getOpenTradeRecords("tradier");
        assertEquals(2, records.size(), "One record per symbol");

        var qqq = records.stream().filter(r -> r.symbol().equals("QQQ")).findFirst().orElseThrow();
        assertEquals(51.0, qqq.quantity(), 0.01, "Quantities summed");
        assertTrue(qqq.entryPrice() > 0, "Avg entry price > 0");
    }

    @Test
    @DisplayName("getOpenTradeRecords excludes closed trades")
    void openRecords_excludesClosed() {
        db.recordTrade("SLV", "RSI", "TRADIER", "tradier", Instant.now(), 71.0, 100.0, 70.0, 73.0);
        db.closeTrade("SLV", Instant.now(), 70.0, -100.0, "tradier");

        assertTrue(db.getOpenTradeRecords("tradier").isEmpty());
    }

    @Test
    @DisplayName("getOpenTradeRecords is broker-isolated")
    void openRecords_brokerIsolated() {
        db.recordTrade("GLD", "MACD", "ALPACA", "alpaca", Instant.now(), 442.0, 0.5, 435.0, 455.0);
        db.recordTrade("GLD", "MACD", "TRADIER", "tradier", Instant.now(), 443.0, 5.0, 436.0, 456.0);

        assertEquals(1, db.getOpenTradeRecords("alpaca").size());
        assertEquals(1, db.getOpenTradeRecords("tradier").size());
    }

    // ── getRecentClosedTrades ──────────────────────────────────────────────────

    @Test
    @DisplayName("getRecentClosedTrades returns empty when no closed trades")
    void recentClosed_empty() {
        db.recordTrade("SPY", "MACD", "ALPACA", "alpaca", Instant.now(), 700.0, 1.0, 693.0, 721.0);
        // Trade is OPEN — should not appear
        assertTrue(db.getRecentClosedTrades(10).isEmpty());
    }

    @Test
    @DisplayName("getRecentClosedTrades returns only CLOSED trades with pnl")
    void recentClosed_onlyClosed() {
        db.recordTrade("IWM", "MACD", "ALPACA", "alpaca", Instant.now(), 269.0, 1.0, 265.0, 277.0);
        db.closeTrade("IWM", Instant.now(), 275.0, 6.0, "alpaca");

        db.recordTrade("SPY", "MACD", "ALPACA", "alpaca", Instant.now(), 700.0, 1.0, 693.0, 721.0);
        // SPY stays OPEN

        var closed = db.getRecentClosedTrades(10);
        assertEquals(1, closed.size());
        assertEquals("IWM", closed.get(0).get("symbol"));
        assertTrue(closed.get(0).get("pnl") instanceof Number);
        assertEquals(6.0, ((Number) closed.get(0).get("pnl")).doubleValue(), 0.01);
    }

    @Test
    @DisplayName("getRecentClosedTrades respects limit")
    void recentClosed_limit() {
        for (int i = 0; i < 5; i++) {
            db.recordTrade("SPY", "MACD", "ALPACA", "alpaca", Instant.now(), 700.0 + i, 1.0, 693.0, 721.0);
            db.closeTrade("SPY", Instant.now(), 705.0 + i, 5.0, "alpaca");
        }
        assertEquals(3, db.getRecentClosedTrades(3).size());
    }

    @Test
    @DisplayName("win rate calculation works correctly with getRecentClosedTrades")
    void winRate_calculation() {
        // 2 wins, 1 loss
        db.recordTrade("IWM", "MACD", "ALPACA", "alpaca", Instant.now(), 269.0, 1.0, 265.0, 277.0);
        db.closeTrade("IWM", Instant.now(), 275.0, 6.0, "alpaca");
        db.recordTrade("SPY", "MACD", "ALPACA", "alpaca", Instant.now(), 700.0, 1.0, 693.0, 721.0);
        db.closeTrade("SPY", Instant.now(), 708.0, 8.0, "alpaca");
        db.recordTrade("SLV", "RSI", "TRADIER", "tradier", Instant.now(), 71.0, 100.0, 70.0, 73.0);
        db.closeTrade("SLV", Instant.now(), 70.0, -100.0, "tradier");

        var closed = db.getRecentClosedTrades(10);
        long wins   = closed.stream().filter(t -> t.get("pnl") instanceof Number n && n.doubleValue() > 0).count();
        long losses = closed.stream().filter(t -> t.get("pnl") instanceof Number n && n.doubleValue() < 0).count();
        assertEquals(2, wins);
        assertEquals(1, losses);
    }
}
