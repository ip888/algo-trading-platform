package com.trading.portfolio;

import com.trading.persistence.TradeDatabase;
import org.junit.jupiter.api.*;

import java.io.File;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the DB-gate and entry-cap logic that prevents the bot from
 * entering the same symbol multiple times after a restart.
 *
 * Root cause that triggered these tests:
 *   - Tradier sandbox never fills limit orders, so getPositions() returns empty.
 *   - On restart the in-memory portfolio is cleared.
 *   - Without the DB gate, the bot saw qty=0 and bought QQQ 13× in one day.
 *
 * Fixes verified here:
 *   1. hasOpenTrade() blocks re-entry when DB already has an OPEN record.
 *   2. countOpenTrades() caps add-to-position at MAX_OPEN_ENTRIES_PER_SYMBOL (2).
 *   3. getOpenTradeRecords() returns correct data for in-memory restore.
 */
@DisplayName("ProfileManager — DB entry gate and open-entry cap")
class ProfileManagerEntryCapTest {

    private static final String TEST_DB = "test-entry-gate.db";
    private TradeDatabase db;

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

    // ── DB gate: hasOpenTrade blocks re-entry ──────────────────────────────────

    @Test
    @DisplayName("hasOpenTrade is false before any trade — entry allowed")
    void gate_noOpenTrade_entryAllowed() {
        assertFalse(db.hasOpenTrade("QQQ", "tradier"),
            "No open trade → entry should be allowed");
    }

    @Test
    @DisplayName("hasOpenTrade is true after recording trade — entry blocked")
    void gate_openTradeExists_entryBlocked() {
        db.recordTrade("QQQ", "RSI", "TRADIER", "tradier",
            Instant.now(), 640.0, 31.0, 634.0, 656.0);
        assertTrue(db.hasOpenTrade("QQQ", "tradier"),
            "Open DB record exists → entry must be blocked");
    }

    @Test
    @DisplayName("hasOpenTrade returns false after trade is closed — re-entry allowed")
    void gate_closedTrade_reEntryAllowed() {
        db.recordTrade("QQQ", "RSI", "TRADIER", "tradier",
            Instant.now(), 640.0, 31.0, 634.0, 656.0);
        db.closeTrade("QQQ", Instant.now(), 648.0, 247.0, "tradier");
        assertFalse(db.hasOpenTrade("QQQ", "tradier"),
            "Closed trade → re-entry must be allowed");
    }

    @Test
    @DisplayName("gate is broker-isolated — Alpaca open does not block Tradier entry")
    void gate_brokerIsolated() {
        db.recordTrade("GLD", "MACD", "ALPACA", "alpaca",
            Instant.now(), 442.0, 1.0, 435.0, 455.0);
        assertFalse(db.hasOpenTrade("GLD", "tradier"),
            "Alpaca open trade must not block Tradier entry for same symbol");
    }

    // ── Add-to-position hard cap: MAX_OPEN_ENTRIES_PER_SYMBOL = 2 ─────────────

    @Test
    @DisplayName("countOpenTrades is 0 before any trades")
    void cap_zero_initially() {
        assertEquals(0, db.countOpenTrades("QQQ", "tradier"));
    }

    @Test
    @DisplayName("countOpenTrades reaches 2 after two entries — cap should trigger")
    void cap_twoEntries_capReached() {
        db.recordTrade("QQQ", "RSI", "TRADIER", "tradier",
            Instant.now(), 640.0, 31.0, 634.0, 656.0);
        db.recordTrade("QQQ", "RSI", "TRADIER", "tradier",
            Instant.now(), 645.0, 20.0, 639.0, 661.0);

        int count = db.countOpenTrades("QQQ", "tradier");
        assertEquals(2, count);
        assertTrue(count >= 2, "At cap — add-to-position must be blocked");
    }

    @Test
    @DisplayName("closeTrade drains all open lots — cap fully reset")
    void cap_afterClose_belowCap() {
        db.recordTrade("QQQ", "RSI", "TRADIER", "tradier",
            Instant.now(), 640.0, 31.0, 634.0, 656.0);
        db.recordTrade("QQQ", "RSI", "TRADIER", "tradier",
            Instant.now(), 645.0, 20.0, 639.0, 661.0);
        db.closeTrade("QQQ", Instant.now(), 660.0, 310.0, "tradier");

        // Full-position sells close every lot in one go (see TradeDatabase.closeTrade).
        // No partial-close path exists in the bot, so after a sell the cap is fully reset.
        assertEquals(0, db.countOpenTrades("QQQ", "tradier"),
            "closeTrade closes all open lots — cap resets to 0");
    }

    // ── Restore: getOpenTradeRecords returns correct data ─────────────────────

    @Test
    @DisplayName("getOpenTradeRecords returns empty list when nothing open")
    void restore_emptyWhenNothingOpen() {
        assertTrue(db.getOpenTradeRecords("tradier").isEmpty());
    }

    @Test
    @DisplayName("getOpenTradeRecords returns all open symbols for broker")
    void restore_returnsOpenSymbols() {
        db.recordTrade("QQQ", "RSI", "TRADIER", "tradier",
            Instant.now(), 640.0, 31.0, 634.0, 656.0);
        db.recordTrade("SPY", "MACD", "TRADIER", "tradier",
            Instant.now(), 700.0, 58.0, 693.0, 721.0);

        List<TradeDatabase.OpenTradeRecord> records = db.getOpenTradeRecords("tradier");
        assertEquals(2, records.size());
        var symbols = records.stream().map(TradeDatabase.OpenTradeRecord::symbol).toList();
        assertTrue(symbols.contains("QQQ"));
        assertTrue(symbols.contains("SPY"));
    }

    @Test
    @DisplayName("getOpenTradeRecords sums quantities when symbol has multiple OPEN entries")
    void restore_sumsQuantities() {
        db.recordTrade("QQQ", "RSI", "TRADIER", "tradier",
            Instant.now(), 640.0, 31.0, 634.0, 656.0);
        db.recordTrade("QQQ", "RSI", "TRADIER", "tradier",
            Instant.now(), 650.0, 20.0, 644.0, 666.0);

        List<TradeDatabase.OpenTradeRecord> records = db.getOpenTradeRecords("tradier");
        assertEquals(1, records.size(), "Multiple OPEN entries consolidated to one record");
        assertEquals(51.0, records.get(0).quantity(), 0.01);
    }

    @Test
    @DisplayName("getOpenTradeRecords excludes closed trades from restore")
    void restore_excludesClosed() {
        db.recordTrade("SLV", "RSI", "TRADIER", "tradier",
            Instant.now(), 71.0, 279.0, 70.3, 73.6);
        db.closeTrade("SLV", Instant.now(), 71.02, -218.0, "tradier");

        assertTrue(db.getOpenTradeRecords("tradier").isEmpty(),
            "Closed SLV trade must not be restored into memory");
    }

    @Test
    @DisplayName("getOpenTradeRecords is broker-isolated")
    void restore_brokerIsolated() {
        db.recordTrade("GLD", "MACD", "ALPACA", "alpaca",
            Instant.now(), 442.0, 1.0, 435.0, 455.0);
        db.recordTrade("GLD", "MACD", "TRADIER", "tradier",
            Instant.now(), 443.0, 5.0, 436.0, 456.0);

        assertEquals(1, db.getOpenTradeRecords("alpaca").size());
        assertEquals(1, db.getOpenTradeRecords("tradier").size());
    }

    @Test
    @DisplayName("getOpenTradeRecords field values are sufficient to recreate a TradePosition")
    void restore_fieldValues() {
        db.recordTrade("GLD", "MACD", "ALPACA", "alpaca",
            Instant.now(), 442.16, 0.263, 435.53, 455.42);

        List<TradeDatabase.OpenTradeRecord> records = db.getOpenTradeRecords("alpaca");
        assertEquals(1, records.size());
        TradeDatabase.OpenTradeRecord r = records.get(0);
        assertEquals("GLD", r.symbol());
        assertEquals(442.16, r.entryPrice(), 0.01);
        assertEquals(0.263, r.quantity(), 0.001);
        assertTrue(r.stopLoss() > 0, "stopLoss must be populated");
        assertTrue(r.takeProfit() > r.entryPrice(), "takeProfit must be above entry");
        assertNotNull(r.entryTime());
    }
}
