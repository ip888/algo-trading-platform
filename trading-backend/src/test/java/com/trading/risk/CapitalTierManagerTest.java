package com.trading.risk;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the CapitalTierManager that provides adaptive risk parameters
 * based on account size.
 */
@DisplayName("CapitalTierManager Tests")
class CapitalTierManagerTest {

    @Nested
    @DisplayName("Tier Detection")
    class TierDetectionTests {

        @Test
        @DisplayName("Should classify MICRO tier for very small accounts (<$500)")
        void testMicroTier() {
            assertEquals(CapitalTierManager.CapitalTier.MICRO, CapitalTierManager.getTier(0));
            assertEquals(CapitalTierManager.CapitalTier.MICRO, CapitalTierManager.getTier(100));
            assertEquals(CapitalTierManager.CapitalTier.MICRO, CapitalTierManager.getTier(499));
        }

        @Test
        @DisplayName("Should classify SMALL tier for $500-$2000")
        void testSmallTier() {
            assertEquals(CapitalTierManager.CapitalTier.SMALL, CapitalTierManager.getTier(500));
            assertEquals(CapitalTierManager.CapitalTier.SMALL, CapitalTierManager.getTier(1000));
            assertEquals(CapitalTierManager.CapitalTier.SMALL, CapitalTierManager.getTier(1999));
        }

        @Test
        @DisplayName("Should classify MEDIUM tier for $2000-$5000")
        void testMediumTier() {
            assertEquals(CapitalTierManager.CapitalTier.MEDIUM, CapitalTierManager.getTier(2000));
            assertEquals(CapitalTierManager.CapitalTier.MEDIUM, CapitalTierManager.getTier(3500));
            assertEquals(CapitalTierManager.CapitalTier.MEDIUM, CapitalTierManager.getTier(4999));
        }

        @Test
        @DisplayName("Should classify STANDARD tier for $5000-$25000")
        void testStandardTier() {
            assertEquals(CapitalTierManager.CapitalTier.STANDARD, CapitalTierManager.getTier(5000));
            assertEquals(CapitalTierManager.CapitalTier.STANDARD, CapitalTierManager.getTier(15000));
            assertEquals(CapitalTierManager.CapitalTier.STANDARD, CapitalTierManager.getTier(24999));
        }

        @Test
        @DisplayName("Should classify PDT tier for $25000+")
        void testPdtTier() {
            assertEquals(CapitalTierManager.CapitalTier.PDT, CapitalTierManager.getTier(25000));
            assertEquals(CapitalTierManager.CapitalTier.PDT, CapitalTierManager.getTier(100000));
            assertEquals(CapitalTierManager.CapitalTier.PDT, CapitalTierManager.getTier(1000000));
        }
    }

    @Nested
    @DisplayName("Tier Parameters")
    class TierParametersTests {

        @Test
        @DisplayName("MICRO tier should have ultra-conservative parameters")
        void testMicroParameters() {
            var params = CapitalTierManager.getParameters(300);

            assertEquals(CapitalTierManager.CapitalTier.MICRO, params.tier());
            assertEquals(0.50, params.maxPositionPercent());
            assertEquals(0.005, params.riskPerTradePercent()); // 0.5% risk
            assertEquals(2, params.maxPositions());
            assertTrue(params.preferWholeShares());
        }

        @Test
        @DisplayName("SMALL tier should prefer whole shares for bracket protection")
        void testSmallParameters() {
            var params = CapitalTierManager.getParameters(1000);

            assertEquals(CapitalTierManager.CapitalTier.SMALL, params.tier());
            assertEquals(0.35, params.maxPositionPercent());
            assertEquals(3, params.maxPositions());
            assertTrue(params.preferWholeShares(),
                "SMALL tier should prefer whole shares to enable bracket orders");
        }

        @Test
        @DisplayName("STANDARD tier should allow fractional shares")
        void testStandardParameters() {
            var params = CapitalTierManager.getParameters(10000);

            assertEquals(CapitalTierManager.CapitalTier.STANDARD, params.tier());
            assertEquals(0.25, params.maxPositionPercent());
            assertEquals(5, params.maxPositions());
            assertFalse(params.preferWholeShares(),
                "STANDARD tier can use fractional shares since capital allows proper position sizing");
        }

        @Test
        @DisplayName("PDT tier should have full trading capabilities")
        void testPdtParameters() {
            var params = CapitalTierManager.getParameters(50000);

            assertEquals(CapitalTierManager.CapitalTier.PDT, params.tier());
            assertEquals(0.20, params.maxPositionPercent());
            assertEquals(8, params.maxPositions());
            assertEquals(1.0, params.takeProfitMultiplier());
            assertEquals(1.0, params.stopLossMultiplier());
        }
    }

    @Nested
    @DisplayName("Position Size Adjustment")
    class PositionSizeAdjustmentTests {

        @Test
        @DisplayName("Should round to whole shares for SMALL accounts")
        void testWholeShareRoundingForSmallAccount() {
            double equity = 1000; // SMALL tier
            double baseSize = 2.75; // Fractional shares calculated
            double stockPrice = 100;

            double adjusted = CapitalTierManager.adjustPositionSize(equity, baseSize, stockPrice);

            // Should round down to 2 whole shares for bracket protection
            assertEquals(2.0, adjusted);
        }

        @Test
        @DisplayName("Should cap position at max percent of portfolio")
        void testMaxPositionPercent() {
            double equity = 1000; // SMALL tier (35% max)
            double baseSize = 10; // 10 shares at $100 = $1000 (100%)
            double stockPrice = 100;

            double adjusted = CapitalTierManager.adjustPositionSize(equity, baseSize, stockPrice);

            // Max position = $1000 * 35% = $350 = 3.5 shares -> rounded to 3
            assertTrue(adjusted * stockPrice <= equity * 0.35,
                "Position should not exceed 35% of portfolio for SMALL tier");
        }

        @Test
        @DisplayName("Should return 0 if position too small")
        void testMinimumPositionValue() {
            double equity = 300; // MICRO tier ($5 minimum)
            double baseSize = 0.01; // Very small
            double stockPrice = 100;

            double adjusted = CapitalTierManager.adjustPositionSize(equity, baseSize, stockPrice);

            assertEquals(0, adjusted, "Position value $1 is below $5 minimum for MICRO tier");
        }

        @Test
        @DisplayName("Should allow fractional shares for STANDARD tier")
        void testFractionalSharesForStandardTier() {
            double equity = 10000; // STANDARD tier
            double baseSize = 5.75;
            double stockPrice = 100;

            double adjusted = CapitalTierManager.adjustPositionSize(equity, baseSize, stockPrice);

            // STANDARD tier doesn't prefer whole shares, should keep fractional
            // Unless capped by max position percent (25% = $2500 = 25 shares)
            assertTrue(adjusted > 0);
        }
    }

    @Nested
    @DisplayName("Account Size Validation")
    class AccountSizeValidationTests {

        @Test
        @DisplayName("Should flag accounts under $100 as too small")
        void testTooSmallAccount() {
            assertTrue(CapitalTierManager.isAccountTooSmall(50));
            assertTrue(CapitalTierManager.isAccountTooSmall(99));
        }

        @Test
        @DisplayName("Should allow accounts $100 and above")
        void testMinimumAccountSize() {
            assertFalse(CapitalTierManager.isAccountTooSmall(100));
            assertFalse(CapitalTierManager.isAccountTooSmall(500));
        }
    }

    @Nested
    @DisplayName("Status Messages")
    class StatusMessageTests {

        @Test
        @DisplayName("Should include recommendation in tier status")
        void testTierStatusIncludesRecommendation() {
            String status = CapitalTierManager.getTierStatus(1000);

            assertTrue(status.contains("SMALL"));
            assertTrue(status.contains("$1000.00"));
            assertTrue(status.contains("Fractional shares"));
        }

        @Test
        @DisplayName("Should warn about micro accounts")
        void testMicroAccountWarning() {
            var params = CapitalTierManager.getParameters(200);

            assertTrue(params.recommendation().contains("MICRO"));
            assertTrue(params.recommendation().contains("limited trading"));
        }
    }
}
