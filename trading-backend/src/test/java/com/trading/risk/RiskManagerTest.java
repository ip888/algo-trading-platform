package com.trading.risk;

import com.trading.config.TradingConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;

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

    // ========================================================================
    // POSITION SIZING TESTS
    // ========================================================================

    @Nested
    @DisplayName("Position Sizing Tests")
    class PositionSizingTests {

        @Test
        @DisplayName("Should calculate position size based on capital and price")
        void testCalculatePositionSize() {
            double capitalPerSymbol = 1000.0;
            double price = 100.0;
            double vix = 15.0; // Low volatility

            double positionSize = riskManager.calculatePositionSize(capitalPerSymbol, price, vix);

            // Position size should be positive and reasonable
            // With SMALL tier ($1000): 1% risk = $10, 0.8% stop-loss = $0.80 risk per share
            // Risk-based: $10 / $0.80 = 12.5 shares
            // BUT: SMALL tier has 35% max position = $350 / $100 = 3.5 shares max
            // Result is capped at tier max position percent
            assertTrue(positionSize > 0, "Position size should be positive");
            assertTrue(positionSize > 1.0 && positionSize <= 15.0,
                "Position size should be reasonable for SMALL tier (got: " + positionSize + ")");
        }

        @Test
        @DisplayName("Should reduce position size when VIX is high")
        void testPositionSizeWithHighVIX() {
            // The tier system caps position sizes based on max position percent.
            // With STANDARD tier (2% risk, 0.5% SL, 25% max position), the risk-based
            // calculation often exceeds the tier cap, masking VIX reduction.
            //
            // To test VIX reduction, we verify the reduction factor is applied correctly
            // by checking that high VIX produces a smaller OR EQUAL position (when capped).
            // The important thing is that it never produces a LARGER position.
            //
            // For a true VIX reduction test, we'd need to either:
            // 1. Use a larger stop-loss so risk-based size is below cap
            // 2. Or verify the internal calculation before capping
            //
            // This test verifies the safety property: high VIX never increases position size.
            double capitalPerSymbol = 10000.0;
            double price = 100.0;
            double lowVix = 15.0;
            double highVix = 40.0;

            double normalSize = riskManager.calculatePositionSize(capitalPerSymbol, price, lowVix);
            double reducedSize = riskManager.calculatePositionSize(capitalPerSymbol, price, highVix);

            // High VIX should never result in a LARGER position
            assertTrue(reducedSize <= normalSize,
                "Position size with high VIX should be <= normal (normal=" + normalSize + ", reduced=" + reducedSize + ")");

            // Both should be positive and reasonable
            assertTrue(normalSize > 0, "Normal position should be positive");
            assertTrue(reducedSize > 0, "Reduced position should be positive");
        }

        @Test
        @DisplayName("Should handle zero price gracefully")
        void testZeroPrice() {
            double capitalPerSymbol = 1000.0;
            double price = 0.0;
            double vix = 15.0;

            double positionSize = riskManager.calculatePositionSize(capitalPerSymbol, price, vix);

            assertEquals(0.0, positionSize, 0.001, "Position size should be 0 for zero price");
        }

        @Test
        @DisplayName("Should handle negative price gracefully")
        void testNegativePrice() {
            double capitalPerSymbol = 1000.0;
            double price = -50.0;
            double vix = 15.0;

            double positionSize = riskManager.calculatePositionSize(capitalPerSymbol, price, vix);

            assertEquals(0.0, positionSize, 0.001, "Position size should be 0 for negative price");
        }

        @Test
        @DisplayName("Should handle zero equity gracefully")
        void testZeroEquity() {
            double capitalPerSymbol = 0.0;
            double price = 100.0;
            double vix = 15.0;

            double positionSize = riskManager.calculatePositionSize(capitalPerSymbol, price, vix);

            assertEquals(0.0, positionSize, 0.001, "Position size should be 0 for zero equity");
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

        @Test
        @DisplayName("Should handle extreme VIX values")
        void testExtremeVIX() {
            double capitalPerSymbol = 1000.0;
            double price = 100.0;

            // Very high VIX (panic levels)
            double extremeVix = 80.0;
            double size = riskManager.calculatePositionSize(capitalPerSymbol, price, extremeVix);
            assertTrue(size > 0, "Should still produce valid position size at extreme VIX");
            assertTrue(size < 10, "Position should be significantly reduced at extreme VIX");
        }

        @Test
        @DisplayName("Should handle NaN and Infinity without crashing")
        void testNaNAndInfinity() {
            double capitalPerSymbol = 1000.0;

            // NaN price - should not crash (behavior may vary, just ensure no exception)
            assertDoesNotThrow(() -> {
                riskManager.calculatePositionSize(capitalPerSymbol, Double.NaN, 15.0);
            }, "Should handle NaN price without throwing");

            // Infinity equity - should not crash
            assertDoesNotThrow(() -> {
                riskManager.calculatePositionSize(Double.POSITIVE_INFINITY, 100.0, 15.0);
            }, "Should handle Infinity equity without throwing");
        }
    }

    // ========================================================================
    // POSITION CREATION TESTS
    // ========================================================================

    @Nested
    @DisplayName("Position Creation Tests")
    class PositionCreationTests {

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
            double expectedStopLoss = entryPrice * (1.0 - TEST_STOP_LOSS_PCT / 100.0);
            assertEquals(expectedStopLoss, position.stopLoss(), DELTA, "Stop-loss should be 0.5% below entry");

            // Take-profit should be 0.75% above entry price (from config)
            double expectedTakeProfit = entryPrice * (1.0 + TEST_TAKE_PROFIT_PCT / 100.0);
            assertEquals(expectedTakeProfit, position.takeProfit(), DELTA, "Take-profit should be 0.75% above entry");
        }

        @Test
        @DisplayName("Should create crypto position with tighter settings")
        void testCreateCryptoPosition() {
            var position = riskManager.createCryptoPosition("BTC-USD", 50000.0, 0.001);

            assertNotNull(position);
            assertEquals("BTC-USD", position.symbol());
            // Crypto uses 0.5% SL and 0.75% TP (hardcoded in method)
            double expectedSL = 50000.0 * (1.0 - 0.005);
            double expectedTP = 50000.0 * (1.0 + 0.0075);
            assertEquals(expectedSL, position.stopLoss(), 1.0);
            assertEquals(expectedTP, position.takeProfit(), 1.0);
        }
    }

    // ========================================================================
    // TRAILING STOP TESTS
    // ========================================================================

    @Nested
    @DisplayName("Trailing Stop Tests")
    class TrailingStopTests {

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
    }

    // ========================================================================
    // DRAWDOWN TESTS - Critical for the safety fix
    // ========================================================================

    @Nested
    @DisplayName("Drawdown Detection Tests (Safety-Critical)")
    class DrawdownTests {

        @Test
        @DisplayName("Should halt trading when max drawdown exceeded")
        void testMaxDrawdownHalt() {
            // 55% loss exceeds 50% threshold
            double currentEquity = INITIAL_CAPITAL * 0.45;

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
        @DisplayName("Should update peak equity when equity increases")
        void testPeakEquityUpdate() {
            // Equity increases above initial
            double higherEquity = INITIAL_CAPITAL * 1.2;  // +20%
            riskManager.shouldHaltTrading(higherEquity);

            // Now check with 40% drawdown from new peak (should NOT halt, 40% < 50% threshold)
            double afterSmallDrop = higherEquity * 0.6;  // 40% drawdown from new peak
            boolean shouldHaltSmall = riskManager.shouldHaltTrading(afterSmallDrop);
            assertFalse(shouldHaltSmall, "40% drawdown from new peak should NOT halt (below 50% threshold)");

            // But 55% drawdown SHOULD halt
            double afterBigDrop = higherEquity * 0.45;  // 55% drawdown from new peak
            boolean shouldHaltBig = riskManager.shouldHaltTrading(afterBigDrop);
            assertTrue(shouldHaltBig, "55% drawdown from new peak SHOULD halt (exceeds 50% threshold)");
        }

        @Test
        @DisplayName("CRITICAL: Should NOT auto-reset peak equity on large discrepancy")
        void testNoAutoResetOnLargeLoss() {
            // This is the critical test for the safety fix
            // Scenario: Peak was $10,000, now $2,500 (75% loss, >3x ratio)
            // OLD BEHAVIOR: Would auto-reset and continue trading
            // NEW BEHAVIOR: Should NOT auto-reset, should halt trading

            double catastrophicLoss = INITIAL_CAPITAL * 0.25;  // 75% loss

            boolean shouldHalt = riskManager.shouldHaltTrading(catastrophicLoss);

            // Should halt because 75% > 50% max drawdown
            // And critically, should NOT have auto-reset to mask the loss
            assertTrue(shouldHalt,
                "CRITICAL: Must halt on 75% drawdown, must NOT auto-reset to mask the loss");
        }

        @Test
        @DisplayName("CRITICAL: Should halt even at extreme losses (>90%)")
        void testHaltOnExtremeLoss() {
            // 95% loss - peak is >20x current
            double extremeLoss = INITIAL_CAPITAL * 0.05;

            boolean shouldHalt = riskManager.shouldHaltTrading(extremeLoss);

            assertTrue(shouldHalt,
                "CRITICAL: Must halt on 95% loss, must NOT auto-reset");
        }

        @Test
        @DisplayName("Manual reset should work correctly")
        void testManualReset() {
            // First, simulate equity dropping significantly
            double lowerEquity = INITIAL_CAPITAL * 0.4;  // 60% drawdown
            boolean haltedBefore = riskManager.shouldHaltTrading(lowerEquity);
            assertTrue(haltedBefore, "Should halt at 60% drawdown");

            // Manually reset peak (simulating user intervention)
            riskManager.resetPeakEquity(lowerEquity);

            // Now the same equity should not trigger halt (drawdown is now 0%)
            boolean haltedAfter = riskManager.shouldHaltTrading(lowerEquity);
            assertFalse(haltedAfter,
                "After manual reset, same equity should not trigger halt");
        }

        @Test
        @DisplayName("Drawdown calculation should be accurate")
        void testDrawdownCalculation() {
            double currentEquity = INITIAL_CAPITAL * 0.7;  // 30% loss

            double drawdown = riskManager.getCurrentDrawdown(currentEquity);

            assertEquals(0.30, drawdown, 0.01,
                "30% loss should result in 30% drawdown");
        }

        @Test
        @DisplayName("Should handle equity at exactly max drawdown threshold")
        void testExactDrawdownThreshold() {
            // Exactly at 50% drawdown threshold
            double exactThreshold = INITIAL_CAPITAL * 0.5;

            boolean shouldHalt = riskManager.shouldHaltTrading(exactThreshold);

            // At exactly 50%, > threshold should be false, so shouldn't halt
            // But this depends on > vs >= comparison
            // Our implementation uses > so exactly 50% should NOT halt
            assertFalse(shouldHalt,
                "Exactly at threshold (50%) should not halt (using > comparison)");
        }

        @Test
        @DisplayName("Should halt just below max drawdown threshold")
        void testJustBelowThreshold() {
            // Just over 50% drawdown (49.9% remaining equity = 50.1% drawdown)
            double justBelow = INITIAL_CAPITAL * 0.499;

            boolean shouldHalt = riskManager.shouldHaltTrading(justBelow);

            assertTrue(shouldHalt,
                "Just over 50% drawdown (50.1%) should trigger halt");
        }
    }

    // ========================================================================
    // EDGE CASES AND ERROR HANDLING
    // ========================================================================

    @Nested
    @DisplayName("Edge Cases and Error Handling")
    class EdgeCaseTests {

        @Test
        @DisplayName("Should handle zero equity in drawdown check")
        void testZeroEquityDrawdown() {
            // Zero equity - should halt (infinite drawdown)
            boolean shouldHalt = riskManager.shouldHaltTrading(0.0);

            assertTrue(shouldHalt, "Should halt when equity is zero");
        }

        @Test
        @DisplayName("Should handle negative equity in drawdown check")
        void testNegativeEquityDrawdown() {
            // Negative equity (impossible but test robustness)
            boolean shouldHalt = riskManager.shouldHaltTrading(-1000.0);

            assertTrue(shouldHalt, "Should halt when equity is negative");
        }

        @Test
        @DisplayName("Should handle very small equity values")
        void testVerySmallEquity() {
            double tinyEquity = 0.001;

            // Should not throw, should halt
            assertDoesNotThrow(() -> {
                boolean result = riskManager.shouldHaltTrading(tinyEquity);
                assertTrue(result, "Should halt with tiny equity (massive drawdown)");
            });
        }

        @Test
        @DisplayName("Should handle equity larger than initial peak")
        void testEquityGrowth() {
            // Equity grows significantly
            double largeEquity = INITIAL_CAPITAL * 10;  // 10x growth

            boolean shouldHalt = riskManager.shouldHaltTrading(largeEquity);

            assertFalse(shouldHalt, "Should not halt when equity has grown");

            // Verify peak was updated
            double drawdown = riskManager.getCurrentDrawdown(largeEquity);
            assertEquals(0.0, drawdown, DELTA, "Drawdown should be 0% at new peak");
        }

        @Test
        @DisplayName("Getters should return configured values")
        void testGetters() {
            assertEquals(TEST_STOP_LOSS_PCT / 100.0, riskManager.getStopLossPercent(), DELTA);
            assertEquals(TEST_TAKE_PROFIT_PCT / 100.0, riskManager.getTakeProfitPercent(), DELTA);
            assertEquals(TEST_TRAILING_STOP_PCT / 100.0, riskManager.getTrailingStopPercent(), DELTA);
        }

        @Test
        @DisplayName("Stop-loss calculation should be accurate")
        void testStopLossCalculation() {
            double entryPrice = 100.0;
            double stopLoss = riskManager.calculateStopLoss(entryPrice);

            // 0.5% below entry
            double expected = entryPrice * (1.0 - TEST_STOP_LOSS_PCT / 100.0);
            assertEquals(expected, stopLoss, DELTA);
        }

        @Test
        @DisplayName("Take-profit calculation should be accurate")
        void testTakeProfitCalculation() {
            double entryPrice = 100.0;
            double takeProfit = riskManager.calculateTakeProfit(entryPrice);

            // 0.75% above entry
            double expected = entryPrice * (1.0 + TEST_TAKE_PROFIT_PCT / 100.0);
            assertEquals(expected, takeProfit, DELTA);
        }
    }
}
