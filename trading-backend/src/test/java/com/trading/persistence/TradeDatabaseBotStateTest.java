package com.trading.persistence;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the bot_state key-value table.
 *
 * bot_state persists in-memory safety state (cooldowns, circuit breakers) so it
 * survives restarts and redeployments. These tests use an in-memory SQLite DB
 * to verify save / load / prefix-scan / delete behaviour.
 */
@DisplayName("TradeDatabase — bot_state persistence")
class TradeDatabaseBotStateTest {

    private TradeDatabase db;

    @BeforeEach
    void setUp() {
        db = new TradeDatabase(":memory:");
    }

    @Test
    @DisplayName("saveBotState + loadBotState: round-trip returns exact value")
    void saveAndLoad() {
        db.saveBotState("cooldown:SPY", "1749060000000,2");
        assertEquals("1749060000000,2", db.loadBotState("cooldown:SPY"));
    }

    @Test
    @DisplayName("loadBotState: missing key returns null")
    void loadMissingKeyReturnsNull() {
        assertNull(db.loadBotState("cooldown:NVDA"));
    }

    @Test
    @DisplayName("saveBotState: upsert overwrites existing value")
    void upsertOverwrites() {
        db.saveBotState("consec_sl:AAPL", "1");
        db.saveBotState("consec_sl:AAPL", "3");
        assertEquals("3", db.loadBotState("consec_sl:AAPL"),
            "Second save must overwrite the first");
    }

    @Test
    @DisplayName("loadBotStateWithPrefix: returns all matching keys")
    void prefixScan() {
        db.saveBotState("cooldown:SPY",  "111,1");
        db.saveBotState("cooldown:QQQ",  "222,2");
        db.saveBotState("consec_sl:SPY", "1");

        var result = db.loadBotStateWithPrefix("cooldown:");
        assertEquals(2, result.size(), "Only 'cooldown:' keys should match");
        assertEquals("111,1", result.get("cooldown:SPY"));
        assertEquals("222,2", result.get("cooldown:QQQ"));
        assertFalse(result.containsKey("consec_sl:SPY"));
    }

    @Test
    @DisplayName("deleteBotState: removes key, load returns null afterwards")
    void deleteRemovesKey() {
        db.saveBotState("cooldown:GLD", "999,1");
        db.deleteBotState("cooldown:GLD");
        assertNull(db.loadBotState("cooldown:GLD"),
            "Key must be absent after deletion");
    }

    @Test
    @DisplayName("multiple symbols: each key is isolated")
    void multipleSymbolsIsolated() {
        db.saveBotState("cooldown:SPY",  "100,1");
        db.saveBotState("cooldown:AMZN", "200,2");
        db.saveBotState("cooldown:ORCL", "300,3");

        assertEquals("100,1", db.loadBotState("cooldown:SPY"));
        assertEquals("200,2", db.loadBotState("cooldown:AMZN"));
        assertEquals("300,3", db.loadBotState("cooldown:ORCL"));
    }
}
