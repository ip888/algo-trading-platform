package com.trading.persistence;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for TradeDatabase.wasRecentlyClosed().
 * Verifies that the settlement-lag guard correctly identifies symbols closed
 * within a configurable time window.
 */
@DisplayName("TradeDatabase.wasRecentlyClosed")
class TradeDatabaseRecentlyClosedTest {

    private static final String TEST_DB = "test-recently-closed.db";
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

    private void openAndClose(String symbol, String broker, Instant entryTime, Instant exitTime) {
        db.recordTrade(symbol, "TEST", "main", broker,
            entryTime, 100.0, 1.0, 98.0, 103.0);
        db.closeTrade(symbol, exitTime, 102.0, 2.0, broker);
    }

    @Test
    @DisplayName("returns true when trade was closed within the window")
    void tradeClosedJustNow() {
        openAndClose("NVDA", "alpaca", Instant.now().minusSeconds(120), Instant.now().minusSeconds(5));

        assertTrue(db.wasRecentlyClosed("NVDA", "alpaca", 15 * 60 * 1000L),
            "A trade closed 5 seconds ago must be detected within the 15-minute window");
    }

    @Test
    @DisplayName("returns false when trade was closed before the window")
    void tradeClosedLongAgo() {
        openAndClose("NVDA", "alpaca", Instant.now().minusSeconds(3700), Instant.now().minusSeconds(3600));

        assertFalse(db.wasRecentlyClosed("NVDA", "alpaca", 15 * 60 * 1000L),
            "A trade closed 1 hour ago must NOT be detected within the 15-minute window");
    }

    @Test
    @DisplayName("returns false when no trade exists for the symbol")
    void noTradeExists() {
        assertFalse(db.wasRecentlyClosed("AAPL", "alpaca", 15 * 60 * 1000L),
            "No trade record should return false");
    }

    @Test
    @DisplayName("returns false when broker doesn't match")
    void wrongBroker() {
        openAndClose("GLD", "alpaca", Instant.now().minusSeconds(60), Instant.now().minusSeconds(10));

        assertFalse(db.wasRecentlyClosed("GLD", "tradier", 15 * 60 * 1000L),
            "A closed trade on a different broker must not match");
    }

    @Test
    @DisplayName("returns false when trade is still OPEN")
    void openTradeNotClosed() {
        db.recordTrade("SPY", "TEST", "main", "alpaca",
            Instant.now().minusSeconds(60), 100.0, 1.0, 98.0, 103.0);

        assertFalse(db.wasRecentlyClosed("SPY", "alpaca", 15 * 60 * 1000L),
            "An OPEN trade must not be detected as recently closed");
    }

    @Test
    @DisplayName("returns false when symbol doesn't match (same broker, different symbol)")
    void wrongSymbol() {
        openAndClose("IWM", "alpaca", Instant.now().minusSeconds(60), Instant.now().minusSeconds(10));

        assertFalse(db.wasRecentlyClosed("QQQ", "alpaca", 15 * 60 * 1000L),
            "Different symbol should not match");
    }

    @Test
    @DisplayName("minimum window: 0 ms returns false even for just-closed trades")
    void zeroWindowReturnsFalse() {
        openAndClose("TSLA", "alpaca", Instant.now().minusSeconds(120), Instant.now().minusSeconds(1));

        assertFalse(db.wasRecentlyClosed("TSLA", "alpaca", 0L),
            "Zero-ms window means nothing is 'recent'");
    }
}
