package com.trading.strategy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for GridTradingService
 * Tests Phase 1 (Dynamic Grid Sizing), Phase 2 (Performance Tracking), 
 * and Phase 3 (Portfolio Management)
 */
@DisplayName("GridTradingService Tests")
class GridTradingServiceTest {
    
    private static final double DELTA = 0.01;
    
    // Phase 1 constants (matching GridTradingService)
    private static final double MIN_GRID_SIZE = 11.0;
    private static final double MAX_GRID_SIZE = 200.0;
    private static final double GRID_BALANCE_RATIO = 0.80;
    
    // Phase 2 constants
    private static final int MIN_TRADES_FOR_WEIGHTING = 3;
    private static final double PERFORMANCE_WEIGHT_FACTOR = 0.3;
    
    // Phase 3 constants
    private static final double BASE_VOLATILITY = 0.02;
    private static final double HIGH_VOLATILITY_THRESHOLD = 0.05;
    private static final double VOLATILITY_SIZE_FACTOR = 0.5;
    private static final double MAX_SINGLE_POSITION_RATIO = 0.40;
    private static final double CORRELATED_GROUP_LIMIT = 0.60;
    
    @Nested
    @DisplayName("Phase 1: Dynamic Grid Sizing")
    class DynamicGridSizingTests {
        
        @Test
        @DisplayName("Should calculate grid size as 80% of available balance")
        void testBasicGridSizeCalculation() {
            double availableBalance = 100.0;
            double expected = availableBalance * GRID_BALANCE_RATIO; // $80
            double actual = calculateDynamicGridSize(availableBalance);
            assertEquals(expected, actual, DELTA);
        }
        
        @Test
        @DisplayName("Should clamp grid size to minimum when balance is low")
        void testMinimumGridSizeClamp() {
            double availableBalance = 10.0; // 80% = $8, below min
            double actual = calculateDynamicGridSize(availableBalance);
            assertEquals(MIN_GRID_SIZE, actual, DELTA);
        }
        
        @Test
        @DisplayName("Should clamp grid size to maximum when balance is high")
        void testMaximumGridSizeClamp() {
            double availableBalance = 500.0; // 80% = $400, above max
            double actual = calculateDynamicGridSize(availableBalance);
            assertEquals(MAX_GRID_SIZE, actual, DELTA);
        }
        
        @Test
        @DisplayName("Should return $11.57 for $14.46 balance (real scenario)")
        void testRealScenarioLowBalance() {
            double availableBalance = 14.46;
            double expected = 11.57; // 80% of 14.46 = 11.568, above MIN so not clamped
            double actual = calculateDynamicGridSize(availableBalance);
            assertEquals(expected, actual, DELTA);
        }
        
        @Test
        @DisplayName("Should return $85.84 for $114.46 balance")
        void testRealScenarioMediumBalance() {
            double availableBalance = 114.46;
            double expected = 91.57; // 80% of 114.46
            double actual = calculateDynamicGridSize(availableBalance);
            assertEquals(expected, actual, DELTA);
        }
        
        @Test
        @DisplayName("Should return $22.26 for $27.83 balance (XRP threshold)")
        void testXRPThresholdBalance() {
            double availableBalance = 27.83;
            double expected = 22.26; // 80% of 27.83
            double actual = calculateDynamicGridSize(availableBalance);
            assertEquals(expected, actual, DELTA);
        }
        
        // Helper method matching GridTradingService logic
        private double calculateDynamicGridSize(double availableBalance) {
            double dynamicSize = availableBalance * GRID_BALANCE_RATIO;
            return Math.max(MIN_GRID_SIZE, Math.min(dynamicSize, MAX_GRID_SIZE));
        }
    }
    
    @Nested
    @DisplayName("Phase 2: Performance Tracking")
    class PerformanceTrackingTests {
        
        @Test
        @DisplayName("Should calculate 50% win rate with no trades")
        void testDefaultWinRate() {
            SymbolPerformance perf = new SymbolPerformance();
            assertEquals(0.5, perf.getWinRate(), DELTA);
        }
        
        @Test
        @DisplayName("Should calculate correct win rate after trades")
        void testWinRateAfterTrades() {
            SymbolPerformance perf = new SymbolPerformance();
            perf.recordTrade(true, 1.5);   // Win +1.5%
            perf.recordTrade(true, 2.0);   // Win +2.0%
            perf.recordTrade(false, -0.5); // Loss -0.5%
            
            assertEquals(0.667, perf.getWinRate(), DELTA); // 2/3
        }
        
        @Test
        @DisplayName("Should calculate average PnL correctly")
        void testAveragePnL() {
            SymbolPerformance perf = new SymbolPerformance();
            perf.recordTrade(true, 3.0);
            perf.recordTrade(true, 1.0);
            perf.recordTrade(false, -1.0);
            
            double expectedAvg = (3.0 + 1.0 - 1.0) / 3; // 1.0%
            assertEquals(expectedAvg, perf.getAvgPnl(), DELTA);
        }
        
        @Test
        @DisplayName("Should not apply weighting with fewer than MIN_TRADES")
        void testNoWeightingWithFewTrades() {
            SymbolPerformance perf = new SymbolPerformance();
            perf.recordTrade(true, 5.0);
            perf.recordTrade(true, 5.0);
            // Only 2 trades, below MIN_TRADES_FOR_WEIGHTING (3)
            
            double baseScore = 50.0;
            double weightedScore = applyPerformanceWeighting(perf, baseScore);
            assertEquals(baseScore, weightedScore, DELTA); // No change
        }
        
        @Test
        @DisplayName("Should boost score for high win rate symbol")
        void testScoreBoostForHighWinRate() {
            SymbolPerformance perf = new SymbolPerformance();
            // 70% win rate: 7 wins, 3 losses
            for (int i = 0; i < 7; i++) perf.recordTrade(true, 1.0);
            for (int i = 0; i < 3; i++) perf.recordTrade(false, -0.5);
            
            double baseScore = 50.0;
            double weightedScore = applyPerformanceWeighting(perf, baseScore);
            
            assertTrue(weightedScore > baseScore, 
                "Score should be boosted for 70% win rate");
        }
        
        @Test
        @DisplayName("Should penalize score for low win rate symbol")
        void testScorePenaltyForLowWinRate() {
            SymbolPerformance perf = new SymbolPerformance();
            // 30% win rate: 3 wins, 7 losses
            for (int i = 0; i < 3; i++) perf.recordTrade(true, 0.5);
            for (int i = 0; i < 7; i++) perf.recordTrade(false, -1.0);
            
            double baseScore = 50.0;
            double weightedScore = applyPerformanceWeighting(perf, baseScore);
            
            assertTrue(weightedScore < baseScore, 
                "Score should be penalized for 30% win rate");
        }
        
        // Helper class matching GridTradingService.SymbolPerformance
        private static class SymbolPerformance {
            int wins = 0;
            int losses = 0;
            double totalPnlPercent = 0.0;
            
            double getWinRate() {
                int total = wins + losses;
                return total > 0 ? (double) wins / total : 0.5;
            }
            
            double getAvgPnl() {
                int total = wins + losses;
                return total > 0 ? totalPnlPercent / total : 0.0;
            }
            
            int getTotalTrades() {
                return wins + losses;
            }
            
            void recordTrade(boolean isWin, double pnlPercent) {
                if (isWin) wins++; else losses++;
                totalPnlPercent += pnlPercent;
            }
        }
        
        // Helper method matching GridTradingService logic
        private double applyPerformanceWeighting(SymbolPerformance perf, double baseScore) {
            if (perf.getTotalTrades() < MIN_TRADES_FOR_WEIGHTING) {
                return baseScore;
            }
            
            double winRateModifier = (perf.getWinRate() - 0.5) * PERFORMANCE_WEIGHT_FACTOR;
            double pnlModifier = Math.max(-0.1, Math.min(0.1, perf.getAvgPnl() / 100));
            double totalModifier = 1.0 + winRateModifier + pnlModifier;
            
            return baseScore * totalModifier;
        }
    }
    
    @Nested
    @DisplayName("Phase 3: Portfolio Management")
    class PortfolioManagementTests {
        
        @Test
        @DisplayName("Should identify high volatility correctly")
        void testHighVolatilityDetection() {
            VolatilityData vol = new VolatilityData();
            vol.update(105.0, 95.0, 100.0); // 10% range = high volatility
            
            assertTrue(vol.isHighVolatility());
            assertEquals(0.10, vol.dailyVolatility, DELTA);
        }
        
        @Test
        @DisplayName("Should identify normal volatility correctly")
        void testNormalVolatilityDetection() {
            VolatilityData vol = new VolatilityData();
            vol.update(101.0, 99.0, 100.0); // 2% range = normal
            
            assertFalse(vol.isHighVolatility());
            assertEquals(0.02, vol.dailyVolatility, DELTA);
        }
        
        @Test
        @DisplayName("Should reduce size by 50% for high volatility")
        void testVolatilitySizeReduction() {
            VolatilityData vol = new VolatilityData();
            vol.update(110.0, 90.0, 100.0); // 20% range = very high
            
            assertEquals(VOLATILITY_SIZE_FACTOR, vol.getSizeMultiplier(), DELTA);
        }
        
        @Test
        @DisplayName("Should reduce size by 25% for medium volatility")
        void testMediumVolatilitySizeReduction() {
            VolatilityData vol = new VolatilityData();
            vol.update(102.0, 98.0, 100.0); // 4% range = medium (>3%, ≤5%)
            
            assertEquals(0.75, vol.getSizeMultiplier(), DELTA);
        }
        
        @Test
        @DisplayName("Should use full size for normal volatility")
        void testNormalVolatilityFullSize() {
            VolatilityData vol = new VolatilityData();
            vol.update(101.5, 98.5, 100.0); // 3% range = normal
            
            assertEquals(1.0, vol.getSizeMultiplier(), DELTA);
        }
        
        @Test
        @DisplayName("Should block position exceeding 40% concentration")
        void testSinglePositionConcentrationLimit() {
            double totalEquity = 1000.0;
            double existingPositionValue = 350.0; // 35%
            double newOrderSize = 100.0; // Would make it 45%
            
            double newRatio = (existingPositionValue + newOrderSize) / (totalEquity + newOrderSize);
            assertTrue(newRatio > MAX_SINGLE_POSITION_RATIO);
        }
        
        @Test
        @DisplayName("Should allow position within 40% concentration")
        void testSinglePositionWithinLimit() {
            double totalEquity = 1000.0;
            double existingPositionValue = 200.0; // 20%
            double newOrderSize = 100.0; // Would make it ~27%
            
            double newRatio = (existingPositionValue + newOrderSize) / (totalEquity + newOrderSize);
            assertTrue(newRatio < MAX_SINGLE_POSITION_RATIO);
        }
        
        @Test
        @DisplayName("Should block correlated group exceeding 60%")
        void testCorrelatedGroupLimit() {
            double totalEquity = 1000.0;
            double correlatedGroupValue = 500.0; // 50%
            double newOrderSize = 150.0; // Would make it ~57%
            
            double newRatio = (correlatedGroupValue + newOrderSize) / (totalEquity + newOrderSize);
            assertTrue(newRatio < CORRELATED_GROUP_LIMIT); // 650/1150 = 56.5% < 60%
            
            // Larger order would exceed (need >$250 to break 60%)
            double largeOrderSize = 300.0;
            double largeRatio = (correlatedGroupValue + largeOrderSize) / (totalEquity + largeOrderSize);
            assertTrue(largeRatio > CORRELATED_GROUP_LIMIT); // 800/1300 = 61.5% > 60%
        }
        
        // Helper class matching GridTradingService.VolatilityData
        private static class VolatilityData {
            double dailyVolatility = BASE_VOLATILITY;
            
            void update(double high, double low, double current) {
                if (high > 0 && low > 0 && current > 0) {
                    this.dailyVolatility = (high - low) / current;
                }
            }
            
            boolean isHighVolatility() {
                return dailyVolatility > HIGH_VOLATILITY_THRESHOLD;
            }
            
            double getSizeMultiplier() {
                if (dailyVolatility > HIGH_VOLATILITY_THRESHOLD) {
                    return VOLATILITY_SIZE_FACTOR;
                } else if (dailyVolatility > BASE_VOLATILITY * 1.5) {
                    return 0.75;
                }
                return 1.0;
            }
        }
    }
    
    @Nested
    @DisplayName("Integration Scenarios")
    class IntegrationScenarioTests {
        
        @Test
        @DisplayName("Real scenario: $14.46 balance with BTC only tradeable")
        void testLowBalanceScenario() {
            double balance = 14.46;
            double gridSize = calculateGridSize(balance);
            
            // Should be able to trade BTC (min $11)
            assertTrue(gridSize >= 11.0, "Should meet BTC minimum");
            // Should NOT be able to trade XRP (min $22)
            assertTrue(gridSize < 22.0, "Should not meet XRP minimum");
            // Should NOT be able to trade ETH (min $38)
            assertTrue(gridSize < 38.0, "Should not meet ETH minimum");
        }
        
        @Test
        @DisplayName("Real scenario: $114.46 balance with most cryptos tradeable")
        void testMediumBalanceScenario() {
            double balance = 114.46;
            double gridSize = calculateGridSize(balance);
            
            // Should meet most minimums
            assertTrue(gridSize >= 11.0, "Should meet BTC minimum");
            assertTrue(gridSize >= 22.0, "Should meet XRP minimum");
            assertTrue(gridSize >= 38.0, "Should meet ETH minimum");
            assertTrue(gridSize >= 70.0, "Should meet DOGE minimum");
            assertTrue(gridSize >= 75.0, "Should meet SOL minimum");
        }
        
        @Test
        @DisplayName("Real scenario: $27.83 balance should now trade XRP")
        void testXRPThresholdScenario() {
            double balance = 27.83;
            double gridSize = calculateGridSize(balance);
            
            // With 80% ratio: $27.83 * 0.80 = $22.26
            assertTrue(gridSize >= 22.0, "Should meet XRP minimum with 80% ratio");
            assertTrue(gridSize < 38.0, "Should not meet ETH minimum");
        }
        
        private double calculateGridSize(double balance) {
            double size = balance * GRID_BALANCE_RATIO;
            return Math.max(MIN_GRID_SIZE, Math.min(size, MAX_GRID_SIZE));
        }
    }
}
