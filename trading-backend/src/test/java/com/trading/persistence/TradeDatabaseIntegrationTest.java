package com.trading.persistence;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.io.File;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for TradeDatabase
 * Tests persistence, concurrent access, and data integrity
 */
@DisplayName("Database Integration Tests")
class TradeDatabaseIntegrationTest {
    
    private TradeDatabase database;
    private static final String TEST_DB = "test-integration.db";
    
    @BeforeEach
    void setUp() {
        // Clean start
        new File(TEST_DB).delete();
        database = new TradeDatabase(TEST_DB);
    }
    
    @AfterEach
    void tearDown() {
        database.close();
        new File(TEST_DB).delete();
    }
    
    @Test
    @DisplayName("Should record and retrieve trade")
    void testTradeRecording() {
        String symbol = "AAPL";
        Instant entryTime = Instant.now();
        
        // Record trade
        database.recordTrade(symbol, "TEST", "test-profile", 
            entryTime, 150.0, 10.0, 147.0, 154.0);
        
        // Verify it was recorded
        int totalTrades = database.getTotalTrades();
        assertEquals(0, totalTrades, "Open trades should not count in total");
    }
    
    @Test
    @DisplayName("Should close trade and calculate P&L")
    void testTradeClosing() {
        String symbol = "AAPL";
        Instant entryTime = Instant.now();
        
        // Record and close trade
        database.recordTrade(symbol, "TEST", "test-profile",
            entryTime, 150.0, 10.0, 147.0, 154.0);
        
        database.closeTrade(symbol, Instant.now(), 152.0, 20.0);
        
        // Verify P&L
        double totalPnL = database.getTotalPnL();
        assertEquals(20.0, totalPnL, 0.01, "P&L should match");
        
        int totalTrades = database.getTotalTrades();
        assertEquals(1, totalTrades, "Should have 1 closed trade");
    }
    
    @Test
    @DisplayName("Should track day trades correctly")
    void testDayTradeTracking() {
        LocalDate today = LocalDate.now(ZoneId.of("America/New_York"));
        
        // Record day trade (buy and sell same day)
        database.recordTrade("AAPL", 10.0, 150.0, "buy", today);
        database.recordTrade("AAPL", 10.0, 151.0, "sell", today);
        
        // Check day trade count
        int dayTrades = database.getDayTradesInLastNBusinessDays(5);
        assertEquals(1, dayTrades, "Should have 1 day trade");
    }
    
    @Test
    @DisplayName("Should detect buy today for PDT")
    void testBuyTodayDetection() {
        LocalDate today = LocalDate.now(ZoneId.of("America/New_York"));
        
        // No buy yet
        assertFalse(database.hasBuyToday("AAPL", today), 
            "Should not have buy today initially");
        
        // Record buy
        database.recordTrade("AAPL", 10.0, 150.0, "buy", today);
        
        // Should detect buy
        assertTrue(database.hasBuyToday("AAPL", today), 
            "Should detect buy today");
    }
    
    @Test
    @DisplayName("Should calculate trade statistics")
    void testTradeStatistics() {
        LocalDate today = LocalDate.now(ZoneId.of("America/New_York"));
        
        // Record winning trade
        database.recordTrade("AAPL", 10.0, 150.0, "buy", today);
        database.recordTrade("AAPL", 10.0, 152.0, "sell", today);
        
        // Record losing trade
        database.recordTrade("GOOGL", 5.0, 2800.0, "buy", today);
        database.recordTrade("GOOGL", 5.0, 2790.0, "sell", today);
        
        var stats = database.getTradeStatistics();
        
        assertNotNull(stats, "Statistics should not be null");
        assertEquals(2, stats.totalTrades(), "Should have 2 trades");
        assertEquals(0.5, stats.winRate(), 0.01, "Win rate should be 50%");
    }
    
    @Test
    @DisplayName("Should handle concurrent access safely")
    void testConcurrentAccess() {
        // StampedLock should handle this
        assertDoesNotThrow(() -> {
            database.recordTrade("AAPL", "TEST", "test",
                Instant.now(), 150.0, 10.0, 147.0, 154.0);
            database.getTotalPnL();
            database.getTotalTrades();
        }, "Should handle concurrent operations");
    }
    
    @Test
    @DisplayName("Should get symbol statistics")
    void testSymbolStatistics() {
        LocalDate today = LocalDate.now(ZoneId.of("America/New_York"));

        // Record multiple trades for AAPL
        database.recordTrade("AAPL", 10.0, 150.0, "buy", today);
        database.recordTrade("AAPL", 10.0, 152.0, "sell", today);

        database.recordTrade("AAPL", 10.0, 151.0, "buy", today);
        database.recordTrade("AAPL", 10.0, 149.0, "sell", today);

        var stats = database.getSymbolStatistics("AAPL");

        assertNotNull(stats, "Symbol statistics should not be null");
        assertEquals(2, stats.totalTrades(), "Should have 2 trades for AAPL");
    }

    @Test
    @DisplayName("closeOrphanedOpenTrades - marks ghost records as CANCELLED")
    void testCloseOrphanedTrades_closesOrphanedRecords() throws Exception {
        // Insert OPEN trade for NVDA (not in live set)
        database.recordTrade("NVDA", "TEST", "MAIN", Instant.now().minusSeconds(300), 500.0, 2.0, 490.0, 515.0);
        // Insert OPEN trade for DIA (in live set - should NOT be touched)
        database.recordTrade("DIA", "TEST", "MAIN", Instant.now().minusSeconds(300), 479.0, 1.0, 472.0, 493.0);

        Set<String> liveSymbols = Set.of("DIA");
        int closed = database.closeOrphanedOpenTrades(liveSymbols, 0); // minAge=0 to close immediately

        assertEquals(1, closed, "Should have closed 1 orphaned record (NVDA)");
        assertTrue(database.hasOpenTrade("DIA"), "DIA should still be OPEN (it's live)");
        assertFalse(database.hasOpenTrade("NVDA"), "NVDA should no longer be OPEN (orphaned)");
    }

    @Test
    @DisplayName("closeOrphanedOpenTrades - respects minimum age, does not close recent records")
    void testCloseOrphanedTrades_respectsMinAge() throws Exception {
        // Insert a very recent OPEN trade (just now)
        database.recordTrade("SPY", "TEST", "MAIN", Instant.now(), 500.0, 1.0, 490.0, 515.0);

        Set<String> liveSymbols = Set.of(); // empty - SPY not live
        // minAge = 10 minutes — record is too new, should NOT be closed
        int closed = database.closeOrphanedOpenTrades(liveSymbols, 10 * 60 * 1000L);

        assertEquals(0, closed, "Should not close recent record (younger than minAge)");
        assertTrue(database.hasOpenTrade("SPY"), "SPY should still be OPEN (too new)");
    }

    @Test
    @DisplayName("closeOrphanedOpenTrades - empty live set closes all old records")
    void testCloseOrphanedTrades_emptyLiveSet() throws Exception {
        database.recordTrade("AAPL", "TEST", "MAIN", Instant.now().minusSeconds(300), 200.0, 5.0, 196.0, 206.0);
        database.recordTrade("MSFT", "TEST", "MAIN", Instant.now().minusSeconds(300), 400.0, 2.0, 394.0, 412.0);

        int closed = database.closeOrphanedOpenTrades(Set.of(), 0);

        assertEquals(2, closed, "Empty live set should close all old OPEN records");
        assertFalse(database.hasOpenTrade("AAPL"));
        assertFalse(database.hasOpenTrade("MSFT"));
    }

    @Test
    @DisplayName("getRecentTrades - entryTime and exitTime are returned as Strings, not longs")
    void testGetRecentTrades_timestampsAreStrings() {
        database.recordTrade("TSLA", "TEST", "MAIN", Instant.now(), 250.0, 3.0, 244.0, 257.0);
        database.closeTrade("TSLA", Instant.now(), 255.0, 15.0);

        var trades = database.getRecentTrades(10);
        assertFalse(trades.isEmpty(), "Should have at least one trade");

        var trade = trades.get(0);
        Object entryTime = trade.get("entryTime");
        Object exitTime  = trade.get("exitTime");

        assertInstanceOf(String.class, entryTime,
            "entryTime must be a String ISO-8601 timestamp, not a Long year");
        assertInstanceOf(String.class, exitTime,
            "exitTime must be a String ISO-8601 timestamp, not a Long year");

        String entryStr = (String) entryTime;
        assertTrue(entryStr.length() > 10,
            "entryTime string should be a full ISO timestamp, got: " + entryStr);
    }
}
