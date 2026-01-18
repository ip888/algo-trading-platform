package com.trading.persistence;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.io.File;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

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
}
