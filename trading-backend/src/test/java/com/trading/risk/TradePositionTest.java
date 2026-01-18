package com.trading.risk;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for TradePosition TP/SL trigger logic.
 * These are critical for trading bot safety - stop-loss and take-profit
 * must trigger correctly to protect capital and lock in profits.
 */
@DisplayName("TradePosition TP/SL Tests")
class TradePositionTest {
    
    private static final double DELTA = 0.0001;
    
    // Test position parameters
    private static final String SYMBOL = "AAPL";
    private static final double ENTRY_PRICE = 150.00;
    private static final double QUANTITY = 100.0;
    private static final double STOP_LOSS = 149.25;  // 0.5% below entry
    private static final double TAKE_PROFIT = 151.125;  // 0.75% above entry
    
    private TradePosition position;
    
    @BeforeEach
    void setUp() {
        position = new TradePosition(
            SYMBOL, ENTRY_PRICE, QUANTITY, 
            STOP_LOSS, TAKE_PROFIT, Instant.now()
        );
    }
    
    @Nested
    @DisplayName("Stop Loss Trigger Tests")
    class StopLossTriggerTests {
        
        @Test
        @DisplayName("Should trigger stop-loss when price equals stop-loss level")
        void shouldTriggerAtExactStopLoss() {
            assertTrue(position.isStopLossHit(STOP_LOSS), 
                "Stop-loss should trigger at exact stop-loss price");
        }
        
        @Test
        @DisplayName("Should trigger stop-loss when price is below stop-loss")
        void shouldTriggerBelowStopLoss() {
            double priceWellBelowStop = STOP_LOSS - 1.0;
            assertTrue(position.isStopLossHit(priceWellBelowStop), 
                "Stop-loss should trigger when price drops below stop");
        }
        
        @Test
        @DisplayName("Should NOT trigger stop-loss when price is above stop-loss")
        void shouldNotTriggerAboveStopLoss() {
            double priceAboveStop = STOP_LOSS + 0.10;
            assertFalse(position.isStopLossHit(priceAboveStop), 
                "Stop-loss should NOT trigger when price is above stop level");
        }
        
        @Test
        @DisplayName("Should NOT trigger stop-loss at entry price")
        void shouldNotTriggerAtEntry() {
            assertFalse(position.isStopLossHit(ENTRY_PRICE), 
                "Stop-loss should NOT trigger at entry price");
        }
        
        @Test
        @DisplayName("Should handle price just above stop-loss (1 cent)")
        void shouldNotTriggerJustAboveStop() {
            double justAboveStop = STOP_LOSS + 0.01;
            assertFalse(position.isStopLossHit(justAboveStop), 
                "Stop-loss should NOT trigger at price just above stop");
        }
        
        @Test
        @DisplayName("Should handle price just below stop-loss (1 cent)")
        void shouldTriggerJustBelowStop() {
            double justBelowStop = STOP_LOSS - 0.01;
            assertTrue(position.isStopLossHit(justBelowStop), 
                "Stop-loss should trigger at price just below stop");
        }
    }
    
    @Nested
    @DisplayName("Take Profit Trigger Tests")
    class TakeProfitTriggerTests {
        
        @Test
        @DisplayName("Should trigger take-profit when price equals take-profit level")
        void shouldTriggerAtExactTakeProfit() {
            assertTrue(position.isTakeProfitHit(TAKE_PROFIT), 
                "Take-profit should trigger at exact TP price");
        }
        
        @Test
        @DisplayName("Should trigger take-profit when price exceeds take-profit")
        void shouldTriggerAboveTakeProfit() {
            double priceAboveTp = TAKE_PROFIT + 1.0;
            assertTrue(position.isTakeProfitHit(priceAboveTp), 
                "Take-profit should trigger when price exceeds TP");
        }
        
        @Test
        @DisplayName("Should NOT trigger take-profit when price is below take-profit")
        void shouldNotTriggerBelowTakeProfit() {
            double priceBelowTp = TAKE_PROFIT - 0.50;
            assertFalse(position.isTakeProfitHit(priceBelowTp), 
                "Take-profit should NOT trigger when price is below TP");
        }
        
        @Test
        @DisplayName("Should NOT trigger take-profit at entry price")
        void shouldNotTriggerAtEntry() {
            assertFalse(position.isTakeProfitHit(ENTRY_PRICE), 
                "Take-profit should NOT trigger at entry price");
        }
        
        @Test
        @DisplayName("Should handle price just below take-profit (1 cent)")
        void shouldNotTriggerJustBelowTp() {
            double justBelowTp = TAKE_PROFIT - 0.01;
            assertFalse(position.isTakeProfitHit(justBelowTp), 
                "Take-profit should NOT trigger at price just below TP");
        }
        
        @Test
        @DisplayName("Should handle price just above take-profit (1 cent)")
        void shouldTriggerJustAboveTp() {
            double justAboveTp = TAKE_PROFIT + 0.01;
            assertTrue(position.isTakeProfitHit(justAboveTp), 
                "Take-profit should trigger at price just above TP");
        }
    }
    
    @Nested
    @DisplayName("Profit/Loss Calculation Tests")
    class PnLCalculationTests {
        
        @Test
        @DisplayName("Should calculate positive P&L correctly")
        void shouldCalculatePositivePnL() {
            double currentPrice = 152.00;  // $2 above entry
            double expectedPnL = (152.00 - 150.00) * QUANTITY;  // $200 profit
            assertEquals(expectedPnL, position.calculatePnL(currentPrice), DELTA);
        }
        
        @Test
        @DisplayName("Should calculate negative P&L correctly")
        void shouldCalculateNegativePnL() {
            double currentPrice = 148.00;  // $2 below entry
            double expectedPnL = (148.00 - 150.00) * QUANTITY;  // -$200 loss
            assertEquals(expectedPnL, position.calculatePnL(currentPrice), DELTA);
        }
        
        @Test
        @DisplayName("Should calculate zero P&L at entry price")
        void shouldCalculateZeroPnLAtEntry() {
            assertEquals(0.0, position.calculatePnL(ENTRY_PRICE), DELTA);
        }
        
        @Test
        @DisplayName("Should calculate P&L at stop-loss level")
        void shouldCalculatePnLAtStopLoss() {
            double expectedLoss = (STOP_LOSS - ENTRY_PRICE) * QUANTITY;  // About -$75
            assertEquals(expectedLoss, position.calculatePnL(STOP_LOSS), DELTA);
        }
        
        @Test
        @DisplayName("Should calculate P&L at take-profit level")
        void shouldCalculatePnLAtTakeProfit() {
            double expectedProfit = (TAKE_PROFIT - ENTRY_PRICE) * QUANTITY;  // About $112.50
            assertEquals(expectedProfit, position.calculatePnL(TAKE_PROFIT), DELTA);
        }
    }
    
    @Nested
    @DisplayName("Profit Percent Calculation Tests")
    class ProfitPercentTests {
        
        @Test
        @DisplayName("Should calculate correct profit percent at take-profit")
        void shouldCalculateProfitPercentAtTp() {
            double expectedPercent = (TAKE_PROFIT - ENTRY_PRICE) / ENTRY_PRICE;  // 0.75%
            assertEquals(expectedPercent, position.getProfitPercent(TAKE_PROFIT), DELTA);
        }
        
        @Test
        @DisplayName("Should calculate correct loss percent at stop-loss")
        void shouldCalculateLossPercentAtSl() {
            double expectedPercent = (STOP_LOSS - ENTRY_PRICE) / ENTRY_PRICE;  // -0.5%
            assertEquals(expectedPercent, position.getProfitPercent(STOP_LOSS), DELTA);
        }
        
        @Test
        @DisplayName("Should calculate 1% profit correctly")
        void shouldCalculateOnePercentProfit() {
            double priceAt1Percent = ENTRY_PRICE * 1.01;  // +1%
            assertEquals(0.01, position.getProfitPercent(priceAt1Percent), DELTA);
        }
        
        @Test
        @DisplayName("Should calculate 5% loss correctly")
        void shouldCalculateFivePercentLoss() {
            double priceAt5PercentLoss = ENTRY_PRICE * 0.95;  // -5%
            assertEquals(-0.05, position.getProfitPercent(priceAt5PercentLoss), DELTA);
        }
    }
    
    @Nested
    @DisplayName("Trailing Stop Tests")
    class TrailingStopTests {
        
        @Test
        @DisplayName("Should update trailing stop when price rises")
        void shouldUpdateTrailingStopOnPriceRise() {
            double newHighPrice = ENTRY_PRICE * 1.02;  // +2%
            double trailPercent = 0.005;  // 0.5% trail
            
            TradePosition updatedPos = position.updateTrailingStop(newHighPrice, trailPercent);
            
            double expectedNewStop = newHighPrice * (1.0 - trailPercent);
            assertEquals(expectedNewStop, updatedPos.stopLoss(), DELTA);
            assertTrue(updatedPos.stopLoss() > position.stopLoss(), 
                "Trailing stop should be higher than original stop");
        }
        
        @Test
        @DisplayName("Should NOT lower trailing stop when price drops")
        void shouldNotLowerTrailingStopOnPriceDrop() {
            // First, move price up to establish higher stop
            double peakPrice = ENTRY_PRICE * 1.05;
            double trailPercent = 0.005;
            TradePosition atPeak = position.updateTrailingStop(peakPrice, trailPercent);
            
            // Then price drops
            double lowerPrice = ENTRY_PRICE * 1.02;
            TradePosition afterDrop = atPeak.updateTrailingStop(lowerPrice, trailPercent);
            
            assertEquals(atPeak.stopLoss(), afterDrop.stopLoss(), DELTA,
                "Stop should remain at peak level, not follow price down");
        }
        
        @Test
        @DisplayName("Should track highest price correctly")
        void shouldTrackHighestPrice() {
            double price1 = ENTRY_PRICE * 1.01;
            double price2 = ENTRY_PRICE * 1.03;  // Higher
            double price3 = ENTRY_PRICE * 1.02;  // Lower than price2
            
            TradePosition p1 = position.updateTrailingStop(price1, 0.005);
            TradePosition p2 = p1.updateTrailingStop(price2, 0.005);
            TradePosition p3 = p2.updateTrailingStop(price3, 0.005);
            
            assertEquals(price2, p3.highestPrice(), DELTA,
                "Highest price should remain at peak");
        }
    }
    
    @Nested
    @DisplayName("Edge Cases and Validation")
    class EdgeCasesTests {
        
        @Test
        @DisplayName("Should reject negative quantity")
        void shouldRejectNegativeQuantity() {
            assertThrows(IllegalArgumentException.class, () -> {
                new TradePosition(SYMBOL, ENTRY_PRICE, -10, STOP_LOSS, TAKE_PROFIT, Instant.now());
            }, "Should reject negative quantity");
        }
        
        @Test
        @DisplayName("Should reject zero quantity")
        void shouldRejectZeroQuantity() {
            assertThrows(IllegalArgumentException.class, () -> {
                new TradePosition(SYMBOL, ENTRY_PRICE, 0, STOP_LOSS, TAKE_PROFIT, Instant.now());
            }, "Should reject zero quantity");
        }
        
        @Test
        @DisplayName("Should reject negative entry price")
        void shouldRejectNegativeEntryPrice() {
            assertThrows(IllegalArgumentException.class, () -> {
                new TradePosition(SYMBOL, -100, QUANTITY, STOP_LOSS, TAKE_PROFIT, Instant.now());
            }, "Should reject negative entry price");
        }
        
        @Test
        @DisplayName("Should reject take-profit at or below entry")
        void shouldRejectTpAtOrBelowEntry() {
            assertThrows(IllegalArgumentException.class, () -> {
                new TradePosition(SYMBOL, ENTRY_PRICE, QUANTITY, STOP_LOSS, ENTRY_PRICE, Instant.now());
            }, "Should reject TP at entry price");
            
            assertThrows(IllegalArgumentException.class, () -> {
                new TradePosition(SYMBOL, ENTRY_PRICE, QUANTITY, STOP_LOSS, ENTRY_PRICE - 1, Instant.now());
            }, "Should reject TP below entry price");
        }
        
        @Test
        @DisplayName("Should handle very small quantities (fractional shares)")
        void shouldHandleFractionalShares() {
            TradePosition fractionalPos = new TradePosition(
                SYMBOL, ENTRY_PRICE, 0.001, STOP_LOSS, TAKE_PROFIT, Instant.now()
            );
            assertNotNull(fractionalPos);
            assertEquals(0.001, fractionalPos.quantity(), DELTA);
        }
        
        @Test
        @DisplayName("Should handle very large price swings")
        void shouldHandleLargePriceSwings() {
            // 50% crash
            double crashPrice = ENTRY_PRICE * 0.5;
            assertTrue(position.isStopLossHit(crashPrice));
            
            // 100% gain
            double doublePrice = ENTRY_PRICE * 2.0;
            assertTrue(position.isTakeProfitHit(doublePrice));
        }
    }
    
    @Nested
    @DisplayName("Partial Exit Tracking")
    class PartialExitTests {
        
        @Test
        @DisplayName("Should track first partial exit")
        void shouldTrackFirstPartialExit() {
            assertFalse(position.hasPartialExit(1));
            TradePosition marked = position.markPartialExit(1);
            assertTrue(marked.hasPartialExit(1));
            assertFalse(marked.hasPartialExit(2));
            assertFalse(marked.hasPartialExit(3));
        }
        
        @Test
        @DisplayName("Should track multiple partial exits")
        void shouldTrackMultiplePartialExits() {
            TradePosition p1 = position.markPartialExit(1);
            TradePosition p2 = p1.markPartialExit(2);
            TradePosition p3 = p2.markPartialExit(3);
            
            assertTrue(p3.hasPartialExit(1));
            assertTrue(p3.hasPartialExit(2));
            assertTrue(p3.hasPartialExit(3));
        }
    }
}
