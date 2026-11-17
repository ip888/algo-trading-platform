package com.trading.persistence;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 2026-11-17: added after a quiet day (Nov 16) proved undiagnosable -- HeartbeatMonitor's
 * pause/resume state was in-memory only, and Fly's log buffer holds roughly the last 40 minutes.
 * This is the persistence layer that lets such a day be looked back on afterward.
 */
@DisplayName("TradeDatabase — system_events")
class SystemEventsTest {

    private static final String DB = "test-system-events.db";
    private TradeDatabase db;

    @BeforeEach
    void setUp() {
        new File(DB).delete();
        db = new TradeDatabase(DB);
    }

    @AfterEach
    void tearDown() { db.close(); new File(DB).delete(); }

    @Test
    @DisplayName("saved events come back newest-first with category and detail intact")
    void savesAndRetrieves() {
        db.saveSystemEvent("heartbeat_pause", "soft-paused new entries -- Profile-ALPACA (305s stale)");
        db.saveSystemEvent("heartbeat_resume", "auto-resumed new entries (auto-resume 1/3 today)");

        var events = db.getSystemEvents(7, 100);

        assertEquals(2, events.size());
        assertEquals("heartbeat_resume", events.get(0).get("category"), "newest first");
        assertEquals("heartbeat_pause", events.get(1).get("category"));
        assertTrue(events.get(1).get("detail").toString().contains("Profile-ALPACA"));
    }

    @Test
    @DisplayName("no events yet -- returns an empty list, never throws")
    void emptyWhenNoEvents() {
        assertTrue(db.getSystemEvents(7, 100).isEmpty());
    }

    @Test
    @DisplayName("respects the limit parameter")
    void respectsLimit() {
        for (int i = 0; i < 5; i++) db.saveSystemEvent("heartbeat_pause", "event " + i);
        assertEquals(2, db.getSystemEvents(7, 2).size());
    }
}
