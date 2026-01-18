package com.trading.protection;

import com.trading.persistence.TradeDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import java.io.File;
import java.time.LocalDate;
import java.time.ZoneId;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for PDT Protection with real database
 * Critical safety mechanism - must be thoroughly tested
 */
@DisplayName("PDT Protection Tests")
class PDTProtectionTest {
    
    private PDTProtection pdtProtection;
    private TradeDatabase database;
    private static final double ACCOUNT_EQUITY_ABOVE_25K = 30000.0;
    private static final double ACCOUNT_EQUITY_BELOW_25K = 20000.0;
    private static final String TEST_DB_PATH = "test-trades.db";
    
    @BeforeEach
    void setUp() {
        // Delete test database if it exists
        new File(TEST_DB_PATH).delete();
        
        // Create fresh database for each test
        database = new TradeDatabase(TEST_DB_PATH);
        pdtProtection = new PDTProtection(database, true);
    }
    
    @AfterEach
    void tearDown() {
        // Clean up test database
        new File(TEST_DB_PATH).delete();
    }
    
    @Test
    @DisplayName("Should allow trading when PDT protection disabled")
    void testPDTDisabled() {
        var pdtDisabled = new PDTProtection(database, false);
        
        boolean canTrade = pdtDisabled.canTrade("AAPL", true, ACCOUNT_EQUITY_BELOW_25K);
        
        assertTrue(canTrade, "Should allow trading when PDT protection is disabled");
    }
    
    @Test
    @DisplayName("Should allow trading for accounts above $25k")
    void testAccountAbove25k() {
        boolean canTrade = pdtProtection.canTrade("AAPL", true, ACCOUNT_EQUITY_ABOVE_25K);
        
        assertTrue(canTrade, "Should allow trading for accounts above $25k");
    }
    
    @Test
    @DisplayName("Should allow first 3 day trades")
    void testFirst3DayTrades() {
        LocalDate today = LocalDate.now(ZoneId.of("America/New_York"));
        
        // Simulate 3 day trades by recording buys and sells same day
        database.recordTrade("AAPL", 10.0, 150.0, "buy", today);
        database.recordTrade("AAPL", 10.0, 151.0, "sell", today);
        
        database.recordTrade("GOOGL", 5.0, 2800.0, "buy", today);
        database.recordTrade("GOOGL", 5.0, 2810.0, "sell", today);
        
        database.recordTrade("MSFT", 20.0, 380.0, "buy", today);
        database.recordTrade("MSFT", 20.0, 382.0, "sell", today);
        
        // 4th trade should still be allowed (we're checking before executing)
        boolean canTrade = pdtProtection.canTrade("TSLA", true, ACCOUNT_EQUITY_BELOW_25K);
        
        assertTrue(canTrade, "Should allow up to 3 day trades");
    }
    
    @Test
    @DisplayName("Should block 4th day trade for accounts below $25k")
    void testBlock4thDayTrade() {
        LocalDate today = LocalDate.now(ZoneId.of("America/New_York"));
        
        // Record 3 completed day trades
        database.recordTrade("AAPL", 10.0, 150.0, "buy", today);
        database.recordTrade("AAPL", 10.0, 151.0, "sell", today);
        
        database.recordTrade("GOOGL", 5.0, 2800.0, "buy", today);
        database.recordTrade("GOOGL", 5.0, 2810.0, "sell", today);
        
        database.recordTrade("MSFT", 20.0, 380.0, "buy", today);
        database.recordTrade("MSFT", 20.0, 382.0, "sell", today);
        
        // Now buy TSLA today (setting up for potential 4th day trade)
        database.recordTrade("TSLA", 15.0, 250.0, "buy", today);
        
        // Try to sell TSLA same day (would be 4th day trade)
        boolean canTrade = pdtProtection.canTrade("TSLA", true, ACCOUNT_EQUITY_BELOW_25K);
        
        assertFalse(canTrade, "Should block 4th day trade for accounts below $25k");
    }
    
    @Test
    @DisplayName("Should allow buy orders even with 3 day trades")
    void testAllowBuyOrders() {
        LocalDate today = LocalDate.now(ZoneId.of("America/New_York"));
        
        // Record 3 day trades
        database.recordTrade("AAPL", 10.0, 150.0, "buy", today);
        database.recordTrade("AAPL", 10.0, 151.0, "sell", today);
        
        database.recordTrade("GOOGL", 5.0, 2800.0, "buy", today);
        database.recordTrade("GOOGL", 5.0, 2810.0, "sell", today);
        
        database.recordTrade("MSFT", 20.0, 380.0, "buy", today);
        database.recordTrade("MSFT", 20.0, 382.0, "sell", today);
        
        // Buy order (not a day trade) should be allowed
        boolean canTrade = pdtProtection.canTrade("TSLA", false, ACCOUNT_EQUITY_BELOW_25K);
        
        assertTrue(canTrade, "Should allow buy orders even with 3 day trades");
    }
    
    @Test
    @DisplayName("Should track day trades per symbol")
    void testPerSymbolTracking() {
        LocalDate today = LocalDate.now(ZoneId.of("America/New_York"));
        
        // 3 day trades on different symbols
        database.recordTrade("AAPL", 10.0, 150.0, "buy", today);
        database.recordTrade("AAPL", 10.0, 151.0, "sell", today);
        
        database.recordTrade("AAPL", 10.0, 149.0, "buy", today);
        database.recordTrade("AAPL", 10.0, 150.0, "sell", today);
        
        database.recordTrade("GOOGL", 5.0, 2800.0, "buy", today);
        database.recordTrade("GOOGL", 5.0, 2810.0, "sell", today);
        
        // Buy MSFT to set up potential 4th day trade
        database.recordTrade("MSFT", 20.0, 380.0, "buy", today);
        
        // Should block 4th day trade regardless of symbol
        boolean canTrade = pdtProtection.canTrade("MSFT", true, ACCOUNT_EQUITY_BELOW_25K);
        
        assertFalse(canTrade, "Should block after 3 day trades regardless of symbol");
    }
    
    @Test
    @DisplayName("Should not count overnight holds as day trades")
    void testOvernightHold() {
        LocalDate today = LocalDate.now(ZoneId.of("America/New_York"));
        LocalDate yesterday = today.minusDays(1);
        
        // Buy yesterday, sell today - NOT a day trade
        database.recordTrade("AAPL", 10.0, 150.0, "buy", yesterday);
        database.recordTrade("AAPL", 10.0, 151.0, "sell", today);
        
        // Should still allow trades
        boolean canTrade = pdtProtection.canTrade("GOOGL", true, ACCOUNT_EQUITY_BELOW_25K);
        
        assertTrue(canTrade, "Overnight holds should not count as day trades");
    }
}
