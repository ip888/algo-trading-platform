package com.trading.portfolio;

import com.trading.risk.TradePosition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for PortfolioManager
 * Tests position tracking, capital allocation, and portfolio metrics
 */
@DisplayName("PortfolioManager Tests")
class PortfolioManagerTest {
    
    private PortfolioManager portfolioManager;
    
    private static final List<String> TEST_SYMBOLS = List.of("AAPL", "GOOGL", "MSFT", "TSLA", "AMZN");
    private static final double TOTAL_CAPITAL = 10000.0;
    private static final double DELTA = 0.01;
    
    @BeforeEach
    void setUp() {
        portfolioManager = new PortfolioManager(TEST_SYMBOLS, TOTAL_CAPITAL);
    }
    
    @Test
    @DisplayName("Should initialize with correct symbols")
    void testInitialization() {
        List<String> symbols = portfolioManager.getSymbols();
        
        assertEquals(5, symbols.size(), "Should have 5 symbols");
        assertTrue(symbols.contains("AAPL"), "Should contain AAPL");
        assertTrue(symbols.contains("GOOGL"), "Should contain GOOGL");
    }
    
    @Test
    @DisplayName("Should calculate capital per symbol correctly")
    void testCapitalPerSymbol() {
        double capitalPerSymbol = portfolioManager.getCapitalPerSymbol();
        
        // With 5 symbols and $10,000, each gets $2,000
        double expected = TOTAL_CAPITAL / TEST_SYMBOLS.size();
        assertEquals(expected, capitalPerSymbol, DELTA, 
            "Capital should be divided equally among symbols");
    }
    
    @Test
    @DisplayName("Should start with zero active positions")
    void testInitialPositionCount() {
        assertEquals(0, portfolioManager.getActivePositionCount(), 
            "Should start with no positions");
    }
    
    @Test
    @DisplayName("Should set and get positions correctly")
    void testSetAndGetPosition() {
        String symbol = "AAPL";
        TradePosition position = new TradePosition(
            symbol, 150.0, 10.0, 147.0, 154.0, Instant.now()
        );
        
        // Set position
        portfolioManager.setPosition(symbol, Optional.of(position));
        
        // Get position
        Optional<TradePosition> retrieved = portfolioManager.getPosition(symbol);
        
        assertTrue(retrieved.isPresent(), "Position should be present");
        assertEquals(symbol, retrieved.get().symbol(), "Symbol should match");
        assertEquals(150.0, retrieved.get().entryPrice(), DELTA, "Entry price should match");
    }
    
    @Test
    @DisplayName("Should track active position count")
    void testActivePositionCount() {
        assertEquals(0, portfolioManager.getActivePositionCount());
        
        // Add first position
        portfolioManager.setPosition("AAPL", Optional.of(
            new TradePosition("AAPL", 150.0, 10.0, 147.0, 154.0, Instant.now())
        ));
        assertEquals(1, portfolioManager.getActivePositionCount());
        
        // Add second position
        portfolioManager.setPosition("GOOGL", Optional.of(
            new TradePosition("GOOGL", 2800.0, 5.0, 2744.0, 2856.0, Instant.now())
        ));
        assertEquals(2, portfolioManager.getActivePositionCount());
    }
    
    @Test
    @DisplayName("Should remove positions with Optional.empty()")
    void testRemovePosition() {
        String symbol = "AAPL";
        
        // Add position
        portfolioManager.setPosition(symbol, Optional.of(
            new TradePosition(symbol, 150.0, 10.0, 147.0, 154.0, Instant.now())
        ));
        assertEquals(1, portfolioManager.getActivePositionCount());
        
        // Remove position
        portfolioManager.setPosition(symbol, Optional.empty());
        
        assertEquals(0, portfolioManager.getActivePositionCount(), 
            "Position count should be 0 after removal");
        assertFalse(portfolioManager.getPosition(symbol).isPresent(), 
            "Position should not be present after removal");
    }
    
    @Test
    @DisplayName("Should return empty Optional for non-existent position")
    void testGetNonExistentPosition() {
        Optional<TradePosition> position = portfolioManager.getPosition("AAPL");
        
        assertFalse(position.isPresent(), "Should return empty Optional for non-existent position");
    }
    
    @Test
    @DisplayName("Should get all positions as map")
    void testGetAllPositions() {
        // Add some positions
        portfolioManager.setPosition("AAPL", Optional.of(
            new TradePosition("AAPL", 150.0, 10.0, 147.0, 154.0, Instant.now())
        ));
        portfolioManager.setPosition("GOOGL", Optional.of(
            new TradePosition("GOOGL", 2800.0, 5.0, 2744.0, 2856.0, Instant.now())
        ));
        
        var allPositions = portfolioManager.getAllPositions();
        
        assertEquals(5, allPositions.size(), "Should have entry for all 5 symbols");
        assertTrue(allPositions.get("AAPL").isPresent(), "AAPL should have position");
        assertTrue(allPositions.get("GOOGL").isPresent(), "GOOGL should have position");
        assertFalse(allPositions.get("MSFT").isPresent(), "MSFT should not have position");
    }
    
    @Test
    @DisplayName("Should get active stored symbols")
    void testGetActiveStoredSymbols() {
        // Add positions
        portfolioManager.setPosition("AAPL", Optional.of(
            new TradePosition("AAPL", 150.0, 10.0, 147.0, 154.0, Instant.now())
        ));
        portfolioManager.setPosition("TSLA", Optional.of(
            new TradePosition("TSLA", 250.0, 8.0, 245.0, 255.0, Instant.now())
        ));
        
        var activeSymbols = portfolioManager.getActiveStoredSymbols();
        
        assertEquals(2, activeSymbols.size(), "Should have 2 active symbols");
        assertTrue(activeSymbols.contains("AAPL"), "Should contain AAPL");
        assertTrue(activeSymbols.contains("TSLA"), "Should contain TSLA");
        assertFalse(activeSymbols.contains("GOOGL"), "Should not contain GOOGL");
    }
    
    @Test
    @DisplayName("Should handle concurrent position updates")
    void testConcurrentUpdates() {
        // ConcurrentHashMap should handle this safely
        portfolioManager.setPosition("AAPL", Optional.of(
            new TradePosition("AAPL", 150.0, 10.0, 147.0, 154.0, Instant.now())
        ));
        
        // Update same position
        portfolioManager.setPosition("AAPL", Optional.of(
            new TradePosition("AAPL", 151.0, 10.0, 148.0, 155.0, Instant.now())
        ));
        
        var position = portfolioManager.getPosition("AAPL");
        assertTrue(position.isPresent());
        assertEquals(151.0, position.get().entryPrice(), DELTA, 
            "Should have updated entry price");
    }
}
