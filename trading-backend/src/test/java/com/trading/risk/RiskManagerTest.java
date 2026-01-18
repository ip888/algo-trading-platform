package com.trading.risk;

import com.trading.config.TradingConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for RiskManager
 * Tests position sizing, stop-loss/take-profit calculation, and risk limits
 * Uses test configuration with 0.5% SL, 0.75% TP
 */
@DisplayName("RiskManager Tests")
class RiskManagerTest {
    
    private RiskManager riskManager;
    private TradingConfig testConfig;
    private static final double INITIAL_CAPITAL = 10000.0;
    private static final double DELTA = 0.01; // For double comparisons
    
    // Test configuration values (matching config.properties)
    private static final double TEST_STOP_LOSS_PCT = 0.5;    // 0.5%
    private static final double TEST_TAKE_PROFIT_PCT = 0.75; // 0.75%
    private static final double TEST_TRAILING_STOP_PCT = 0.5;
    private static final double TEST_RISK_PER_TRADE = 1.0;   // 1%
    private static final double TEST_MAX_DRAWDOWN = 50.0;    // 50%
    
    @BeforeEach
    void setUp() {
        // Create test config with explicit values
        Properties props = new Properties();
        props.setProperty("MAIN_STOP_LOSS_PERCENT", String.valueOf(TEST_STOP_LOSS_PCT));
        props.setProperty("MAIN_TAKE_PROFIT_PERCENT", String.valueOf(TEST_TAKE_PROFIT_PCT));
        props.setProperty("MAIN_TRAILING_STOP_PERCENT", String.valueOf(TEST_TRAILING_STOP_PCT));
        props.setProperty("RISK_PER_TRADE_PERCENT", String.valueOf(TEST_RISK_PER_TRADE));
        props.setProperty("MAX_DRAWDOWN_PERCENT", String.valueOf(TEST_MAX_DRAWDOWN));
        
        testConfig = TradingConfig.forTest(props);
        riskManager = new RiskManager(INITIAL_CAPITAL, null, testConfig);
    }
    
    @Test
    @DisplayName("Should calculate position size based on capital and price")
    void testCalculatePositionSize() {
        double capitalPerSymbol = 1000.0;
        double price = 100.0;
        double vix = 15.0; // Low volatility
        
        double positionSize = riskManager.calculatePositionSize(capitalPerSymbol, price, vix);
        
        // Position size should be positive and reasonable
        // With 1% risk of $1000 = $10 risk, and 0.5% stop-loss = $0.50 risk per share
        // Expected: $10 / $0.50 = 20 shares
        assertTrue(positionSize > 0, "Position size should be positive");
        assertTrue(positionSize > 10.0 && positionSize <= 25.0, 
            "Position size should be ~20 shares for 1% risk with 0.5% SL (got: " + positionSize + ")");
    }
    
    @Test
    @DisplayName("Should reduce position size when VIX is high")
    void testPositionSizeWithHighVIX() {
        double capitalPerSymbol = 1000.0;
        double price = 100.0;
        double lowVix = 12.0;
        double highVix = 30.0;
        
        double normalSize = riskManager.calculatePositionSize(capitalPerSymbol, price, lowVix);
        double reducedSize = riskManager.calculatePositionSize(capitalPerSymbol, price, highVix);
        
        assertTrue(reducedSize < normalSize, "Position size should be smaller with high VIX");
    }
    
    @Test
    @DisplayName("Should create position with correct stop-loss and take-profit")
    void testCreatePosition() {
        String symbol = "AAPL";
        double entryPrice = 150.0;
        double quantity = 10.0;
        
        var position = riskManager.createPosition(symbol, entryPrice, quantity);
        
        assertNotNull(position, "Position should not be null");
        assertEquals(symbol, position.symbol(), "Symbol should match");
        assertEquals(entryPrice, position.entryPrice(), DELTA, "Entry price should match");
        assertEquals(quantity, position.quantity(), DELTA, "Quantity should match");
        
        // Stop-loss should be 0.5% below entry price (from config)
        double expectedStopLoss = entryPrice * (1.0 - TEST_STOP_LOSS_PCT / 100.0); // 0.5% below
        assertEquals(expectedStopLoss, position.stopLoss(), DELTA, "Stop-loss should be 0.5% below entry");
        
        // Take-profit should be 0.75% above entry price (from config)
        double expectedTakeProfit = entryPrice * (1.0 + TEST_TAKE_PROFIT_PCT / 100.0); // 0.75% above
        assertEquals(expectedTakeProfit, position.takeProfit(), DELTA, "Take-profit should be 0.75% above entry");
    }
    
    @Test
    @DisplayName("Should update trailing stop when price increases")
    void testUpdateTrailingStop() {
        var position = riskManager.createPosition("AAPL", 100.0, 10.0);
        double originalStopLoss = position.stopLoss();
        
        // Price increases significantly
        double newPrice = 120.0;
        var updatedPosition = riskManager.updatePositionTrailingStop(position, newPrice);
        
        assertTrue(updatedPosition.stopLoss() > originalStopLoss, 
            "Trailing stop should move up when price increases");
    }
    
    @Test
    @DisplayName("Should not lower trailing stop when price decreases")
    void testTrailingStopDoesNotLower() {
        var position = riskManager.createPosition("AAPL", 100.0, 10.0);
        double originalStopLoss = position.stopLoss();
        
        // Price decreases
        double newPrice = 95.0;
        var updatedPosition = riskManager.updatePositionTrailingStop(position, newPrice);
        
        assertEquals(originalStopLoss, updatedPosition.stopLoss(), DELTA,
            "Trailing stop should not move down when price decreases");
    }
    
    @Test
    @DisplayName("Should halt trading when max drawdown exceeded")
    void testMaxDrawdownHalt() {
        // RiskManager uses 50% MAX_DRAWDOWN threshold for paper trading
        // Auto-reset threshold is 3x (67% loss), so 55% loss should trigger halt but not auto-reset
        double currentEquity = INITIAL_CAPITAL * 0.45; // 55% loss - exceeds 50% threshold
        
        boolean shouldHalt = riskManager.shouldHaltTrading(currentEquity);
        
        assertTrue(shouldHalt, "Should halt trading when drawdown exceeds 50%");
    }
    
    @Test
    @DisplayName("Should not halt trading with acceptable drawdown")
    void testAcceptableDrawdown() {
        double currentEquity = INITIAL_CAPITAL * 0.95; // 5% loss
        
        boolean shouldHalt = riskManager.shouldHaltTrading(currentEquity);
        
        assertFalse(shouldHalt, "Should not halt trading with 5% drawdown");
    }
    
    @Test
    @DisplayName("Should handle zero price gracefully")
    void testZeroPrice() {
        double capitalPerSymbol = 1000.0;
        double price = 0.0;
        double vix = 15.0;
        
        double positionSize = riskManager.calculatePositionSize(capitalPerSymbol, price, vix);
        
        // With the fix, zero price should return 0 position size (not throw or return huge number)
        assertEquals(0.0, positionSize, 0.001, "Position size should be 0 for zero price");
    }
    
    @Test
    @DisplayName("Should handle negative VIX gracefully")
    void testNegativeVIX() {
        double capitalPerSymbol = 1000.0;
        double price = 100.0;
        double vix = -5.0;
        
        // Should not throw exception
        assertDoesNotThrow(() -> {
            riskManager.calculatePositionSize(capitalPerSymbol, price, vix);
        }, "Should handle negative VIX without throwing");
    }
}
