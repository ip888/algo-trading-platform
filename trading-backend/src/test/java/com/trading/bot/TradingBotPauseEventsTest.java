package com.trading.bot;

import com.trading.persistence.TradeDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 2026-11-19: pauseTrading()/resumeTrading() (the manual dashboard pause -- checked in
 * ProfileManager BEFORE the heartbeat-pause guard) previously had no persisted trace at all, only
 * a log line. A day with zero trades, zero blocked-entries, but a perfectly healthy heartbeat
 * (confirmed via system_events on 2026-11-19) could not be distinguished from this state. See
 * decision_log.md, Nov 19 2026 entry.
 */
@DisplayName("TradingBot pause/resume — persisted events")
class TradingBotPauseEventsTest {

    private static final String DB = "test-pause-events.db";
    private TradeDatabase db;

    @BeforeEach
    void setUp() {
        TradingBot.installEventLogger(null); // detach any prior test's (about-to-be-deleted) db first
        TradingBot.resumeTrading(); // ensure a known starting state regardless of test order
        new File(DB).delete();
        db = new TradeDatabase(DB);
        TradingBot.installEventLogger(db);
    }

    @AfterEach
    void tearDown() {
        TradingBot.resumeTrading();
        db.close();
        new File(DB).delete();
    }

    @Test
    @DisplayName("pausing persists a trading_paused event")
    void pauseIsLogged() {
        TradingBot.pauseTrading();

        var events = db.getSystemEvents(7, 100);
        assertEquals(1, events.size());
        assertEquals("trading_paused", events.get(0).get("category"));
    }

    @Test
    @DisplayName("resuming after a pause persists a trading_resumed event")
    void resumeIsLogged() {
        TradingBot.pauseTrading();
        TradingBot.resumeTrading();

        var events = db.getSystemEvents(7, 100); // newest first
        assertEquals(2, events.size());
        assertEquals("trading_resumed", events.get(0).get("category"));
        assertEquals("trading_paused", events.get(1).get("category"));
    }

    @Test
    @DisplayName("a redundant pause call (already paused) does not log a duplicate event")
    void redundantPauseDoesNotDuplicate() {
        TradingBot.pauseTrading();
        TradingBot.pauseTrading(); // already paused -- compareAndSet fails, no-op

        assertEquals(1, db.getSystemEvents(7, 100).size());
    }
}
