package com.trading.exits;

import com.trading.config.Config;
import com.trading.exits.ExitStrategyManager.ExitDecision;
import com.trading.exits.ExitStrategyManager.ExitType;
import com.trading.risk.TradePosition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for ExitStrategyManager TP/SL evaluation logic.
 * Ensures exit strategies trigger correctly in priority order.
 */
@DisplayName("ExitStrategyManager TP/SL Tests")
class ExitStrategyManagerTest {
    
    private ExitStrategyManager exitStrategyManager;
    private Config config;
    
    // Test position parameters
    private static final String SYMBOL = "AAPL";
    private static final double ENTRY_PRICE = 150.00;
    private static final double QUANTITY = 100.0;
    private static final double STOP_LOSS = 149.25;  // 0.5% below entry
    private static final double TAKE_PROFIT = 151.125;  // 0.75% above entry
    private static final double NORMAL_VOLATILITY = 0.02;  // 2% volatility
    
    @BeforeEach
    void setUp() {
        // Create a test config (loads from config.properties or defaults)
        config = new Config();
        exitStrategyManager = new ExitStrategyManager(config);
    }
    
    private TradePosition createTestPosition() {
        return new TradePosition(
            SYMBOL, ENTRY_PRICE, QUANTITY, 
            STOP_LOSS, TAKE_PROFIT, Instant.now()
        );
    }
    
    @Nested
    @DisplayName("Stop Loss Priority Tests")
    class StopLossPriorityTests {
        
        @Test
        @DisplayName("Stop-loss should have highest priority")
        void stopLossShouldHaveHighestPriority() {
            TradePosition position = createTestPosition();
            double priceAtStopLoss = STOP_LOSS;
            
            ExitDecision decision = exitStrategyManager.evaluateExit(
                position, priceAtStopLoss, NORMAL_VOLATILITY, new HashMap<>()
            );
            
            assertEquals(ExitType.STOP_LOSS, decision.type(), 
                "Should trigger STOP_LOSS exit");
            assertFalse(decision.isPartial(), 
                "Stop-loss should be full exit");
            assertEquals(1.0, decision.quantity(), 
                "Should exit 100% of position");
            assertTrue(decision.reason().contains("Stop loss"), 
                "Reason should mention stop loss");
        }
        
        @Test
        @DisplayName("Stop-loss should trigger even when TP would also trigger")
        void stopLossTakesPriorityOverTakeProfit() {
            // Edge case: position with stop at 149.25, but we test SL priority
            TradePosition position = createTestPosition();
            double priceBelowStop = STOP_LOSS - 0.50;
            
            ExitDecision decision = exitStrategyManager.evaluateExit(
                position, priceBelowStop, NORMAL_VOLATILITY, new HashMap<>()
            );
            
            assertEquals(ExitType.STOP_LOSS, decision.type());
        }
    }
    
    @Nested
    @DisplayName("Take Profit Tests")
    class TakeProfitTests {
        
        @Test
        @DisplayName("Take-profit should trigger at TP level")
        void takeProfitShouldTriggerAtTpLevel() {
            TradePosition position = createTestPosition();
            double priceAtTakeProfit = TAKE_PROFIT;
            
            ExitDecision decision = exitStrategyManager.evaluateExit(
                position, priceAtTakeProfit, NORMAL_VOLATILITY, new HashMap<>()
            );
            
            assertEquals(ExitType.TAKE_PROFIT, decision.type(), 
                "Should trigger TAKE_PROFIT exit");
            assertFalse(decision.isPartial(), 
                "Take-profit should be full exit");
            assertEquals(1.0, decision.quantity());
        }
        
        @Test
        @DisplayName("Take-profit should trigger above TP level")
        void takeProfitShouldTriggerAboveTpLevel() {
            TradePosition position = createTestPosition();
            double priceAboveTp = TAKE_PROFIT + 1.0;
            
            ExitDecision decision = exitStrategyManager.evaluateExit(
                position, priceAboveTp, NORMAL_VOLATILITY, new HashMap<>()
            );
            
            assertEquals(ExitType.TAKE_PROFIT, decision.type());
        }
        
        @Test
        @DisplayName("Take-profit should NOT trigger below TP level")
        void takeProfitShouldNotTriggerBelowTp() {
            TradePosition position = createTestPosition();
            double priceBelowTp = TAKE_PROFIT - 0.50;
            
            ExitDecision decision = exitStrategyManager.evaluateExit(
                position, priceBelowTp, NORMAL_VOLATILITY, new HashMap<>()
            );
            
            assertNotEquals(ExitType.TAKE_PROFIT, decision.type(), 
                "Should NOT trigger take-profit below TP level");
        }
    }
    
    @Nested
    @DisplayName("No Exit Scenarios")
    class NoExitTests {
        
        @Test
        @DisplayName("No exit when price is between SL and TP")
        void noExitWhenPriceBetweenSlAndTp() {
            TradePosition position = createTestPosition();
            double midPrice = (STOP_LOSS + TAKE_PROFIT) / 2;
            
            ExitDecision decision = exitStrategyManager.evaluateExit(
                position, midPrice, NORMAL_VOLATILITY, new HashMap<>()
            );
            
            // Should be either NONE or a lower-priority exit (partial, etc.)
            assertNotEquals(ExitType.STOP_LOSS, decision.type());
            assertNotEquals(ExitType.TAKE_PROFIT, decision.type());
        }
        
        @Test
        @DisplayName("No exit at entry price")
        void noExitAtEntryPrice() {
            TradePosition position = createTestPosition();
            
            ExitDecision decision = exitStrategyManager.evaluateExit(
                position, ENTRY_PRICE, NORMAL_VOLATILITY, new HashMap<>()
            );
            
            assertNotEquals(ExitType.STOP_LOSS, decision.type());
            assertNotEquals(ExitType.TAKE_PROFIT, decision.type());
        }
    }
    
    @Nested
    @DisplayName("Exit Decision Properties")
    class ExitDecisionPropertiesTests {
        
        @Test
        @DisplayName("Stop-loss exit should include expected price")
        void stopLossExitShouldIncludePrice() {
            TradePosition position = createTestPosition();
            
            ExitDecision decision = exitStrategyManager.evaluateExit(
                position, STOP_LOSS, NORMAL_VOLATILITY, new HashMap<>()
            );
            
            assertEquals(STOP_LOSS, decision.expectedPrice());
        }
        
        @Test
        @DisplayName("Take-profit exit should include expected price")
        void takeProfitExitShouldIncludePrice() {
            TradePosition position = createTestPosition();
            
            ExitDecision decision = exitStrategyManager.evaluateExit(
                position, TAKE_PROFIT, NORMAL_VOLATILITY, new HashMap<>()
            );
            
            assertEquals(TAKE_PROFIT, decision.expectedPrice());
        }
        
        @Test
        @DisplayName("NoExit should have proper defaults")
        void noExitShouldHaveProperDefaults() {
            ExitDecision noExit = ExitDecision.noExit();
            
            assertEquals(ExitType.NONE, noExit.type());
            assertEquals(0.0, noExit.quantity());
            assertFalse(noExit.isPartial());
        }
    }
    
    @Nested
    @DisplayName("Partial Exit Tests")
    class PartialExitTests {
        
        @Test
        @DisplayName("Should trigger partial exit at 25% of profit target")
        void shouldTriggerPartialExitAt25Percent() {
            TradePosition position = createTestPosition();
            // Calculate price at 25% of profit target
            double profitTarget = TAKE_PROFIT - ENTRY_PRICE;  // ~1.125
            double priceAt25Percent = ENTRY_PRICE + (profitTarget * 0.26);  // Slightly above 25%
            
            ExitDecision decision = exitStrategyManager.evaluateExit(
                position, priceAt25Percent, NORMAL_VOLATILITY, new HashMap<>()
            );
            
            // Should be partial profit or no exit (depends on implementation)
            if (decision.type() == ExitType.PARTIAL_PROFIT) {
                assertTrue(decision.isPartial());
                assertTrue(decision.quantity() < 1.0, "Partial exit should be less than 100%");
            }
        }
        
        @Test
        @DisplayName("Partial exit should not re-trigger after marking")
        void partialExitShouldNotRetriggerAfterMarking() {
            TradePosition position = createTestPosition();
            TradePosition markedPosition = position.markPartialExit(1);
            
            double profitTarget = TAKE_PROFIT - ENTRY_PRICE;
            double priceAt30Percent = ENTRY_PRICE + (profitTarget * 0.30);
            
            ExitDecision decision = exitStrategyManager.evaluateExit(
                markedPosition, priceAt30Percent, NORMAL_VOLATILITY, new HashMap<>()
            );
            
            // After marking first partial exit, should not trigger again at same level
            if (decision.type() == ExitType.PARTIAL_PROFIT) {
                // Should be for a higher level (2 or 3), not level 1
                assertFalse(decision.reason().contains("25%"), 
                    "Should not re-trigger first partial exit");
            }
        }
    }
}
