package com.trading.strategy;

import com.trading.analysis.MarketRegimeDetector.MarketRegime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for StrategyManager:
 * - isShortTermDowntrend (10-bar and 20-bar SMA checks)
 * - Regime routing (WEAK_BEAR → MACD, HIGH_VOL → no new entries)
 */
@DisplayName("StrategyManager - Downtrend Detection + Regime Routing")
class StrategyManagerTest {

    // StrategyManager with no client/MTF (for evaluateWithHistory tests)
    private StrategyManager manager;

    @BeforeEach
    void setUp() {
        // null client is fine for evaluateWithHistory (no HTTP calls)
        manager = new StrategyManager(null, null, null);
    }

    // Build `count` prices rising by `step` per bar
    private static List<Double> rising(int count, double start, double step) {
        List<Double> prices = new ArrayList<>();
        for (int i = 0; i < count; i++) prices.add(start + i * step);
        return prices;
    }

    /**
     * Build a price series where early bars are rising (warm-up), then falls below
     * declining SMA in last N bars.
     */
    private static List<Double> warmUpThenFall(int warmup, int decline) {
        List<Double> prices = new ArrayList<>(rising(warmup, 100.0, 0.5));
        double peak = prices.get(prices.size() - 1);
        for (int i = 0; i < decline; i++) prices.add(peak - i * 1.5);
        return prices;
    }

    @Nested
    @DisplayName("isShortTermDowntrend")
    class IsShortTermDowntrend {

        @Test
        @DisplayName("returns false with fewer than 22 bars")
        void insufficientData() {
            var prices = rising(20, 100.0, 0.5);
            assertFalse(manager.isShortTermDowntrend(prices));
        }

        @Test
        @DisplayName("returns false for a steady uptrend")
        void uptrendNotDetected() {
            var prices = rising(40, 100.0, 0.5);
            assertFalse(manager.isShortTermDowntrend(prices),
                "Steady uptrend should NOT be flagged as downtrend");
        }

        @Test
        @DisplayName("returns true when price falls below declining 10-bar SMA")
        void shortTermDowntrend10Bar() {
            // 30 bars rising, then sharp drop of 15+ bars
            var prices = warmUpThenFall(25, 18);
            assertTrue(manager.isShortTermDowntrend(prices),
                "Price below declining 10-SMA should be detected as downtrend");
        }

        @Test
        @DisplayName("returns true when price falls below declining 20-bar SMA")
        void mediumTermDowntrend20Bar() {
            // Long warmup + gradual decline that crosses 20-SMA but not 10-SMA initially
            List<Double> prices = new ArrayList<>(rising(30, 100.0, 0.2));
            // Add a moderate decline — keeps 10-bar SMA still positive but crosses 20-bar
            double peak = prices.get(prices.size() - 1);
            for (int i = 0; i < 25; i++) prices.add(peak - i * 0.4);
            assertTrue(manager.isShortTermDowntrend(prices),
                "Price below declining 20-SMA should trigger downtrend detection");
        }

        @Test
        @DisplayName("returns false when price is above both SMAs")
        void priceAboveBothSmas() {
            // Flat then slight upward move at the end
            List<Double> prices = new ArrayList<>();
            for (int i = 0; i < 30; i++) prices.add(100.0);
            for (int i = 0; i < 5; i++) prices.add(101.0 + i * 0.5);
            assertFalse(manager.isShortTermDowntrend(prices),
                "Price above SMAs should not be flagged as downtrend");
        }
    }

    @Nested
    @DisplayName("Regime routing")
    class RegimeRouting {

        // Price history long enough for strategy calculations
        private final List<Double> history = rising(100, 50.0, 0.3);
        private final double price = history.get(history.size() - 1);

        @Test
        @DisplayName("WEAK_BEAR routes to MACD (not RSI mean-reversion)")
        void weakBearUsesMacd() {
            manager.evaluateWithHistory("SH", price, 0.0, history, MarketRegime.WEAK_BEAR);
            assertEquals("MACD Trend (Weak Bear)", manager.getActiveStrategy(),
                "WEAK_BEAR should use MACD, not RSI (inverse ETF fix)");
        }

        @Test
        @DisplayName("HIGH_VOLATILITY with no position → HOLD, no new entry")
        void highVolNoPositionHolds() {
            var signal = manager.evaluateWithHistory("SPY", price, 0.0, history, MarketRegime.HIGH_VOLATILITY);
            assertInstanceOf(TradingSignal.Hold.class, signal,
                "HIGH_VOLATILITY with no position should always HOLD");
            var hold = (TradingSignal.Hold) signal;
            assertTrue(hold.reason().contains("High volatility"),
                "Hold reason should mention high volatility, got: " + hold.reason());
        }

        @Test
        @DisplayName("HIGH_VOLATILITY with open position routes to MACD for exit")
        void highVolWithPositionUsesMacd() {
            manager.evaluateWithHistory("SPY", price, 5.0, history, MarketRegime.HIGH_VOLATILITY);
            assertEquals("MACD Exit Only (HighVol)", manager.getActiveStrategy(),
                "HIGH_VOLATILITY with position should use MACD exit strategy");
        }

        @Test
        @DisplayName("STRONG_BULL with momentum asset uses Momentum strategy")
        void strongBullMomentumAsset() {
            manager.evaluateWithHistory("GLD", price, 0.0, history, MarketRegime.STRONG_BULL);
            assertEquals("Momentum (Strong Bull)", manager.getActiveStrategy());
        }

        @Test
        @DisplayName("STRONG_BULL with regular asset uses MACD")
        void strongBullRegularAsset() {
            manager.evaluateWithHistory("SPY", price, 0.0, history, MarketRegime.STRONG_BULL);
            assertEquals("MACD Trend", manager.getActiveStrategy());
        }

        @Test
        @DisplayName("RANGE_BOUND uses Mean Reversion")
        void rangeBoundMeanReversion() {
            manager.evaluateWithHistory("SPY", price, 0.0, history, MarketRegime.RANGE_BOUND);
            assertEquals("Mean Reversion", manager.getActiveStrategy());
        }

        @Test
        @DisplayName("STRONG_BEAR with no position → HOLD (no new long entries in bear market)")
        void strongBearBlocksNewEntries() {
            var signal = manager.evaluateWithHistory("SPY", price, 0.0, history, MarketRegime.STRONG_BEAR);
            assertInstanceOf(TradingSignal.Hold.class, signal,
                "STRONG_BEAR with no position must block new long entries");
            assertEquals("Bear Market Block", manager.getActiveStrategy());
        }

        @Test
        @DisplayName("STRONG_BEAR with open position → MACD exit strategy")
        void strongBearWithPositionUsesMACD() {
            manager.evaluateWithHistory("SPY", price, 5.0, history, MarketRegime.STRONG_BEAR);
            assertEquals("MACD Exit (Strong Bear)", manager.getActiveStrategy(),
                "STRONG_BEAR with existing position should use MACD for exit");
        }
    }

    @Nested
    @DisplayName("50-bar SMA macro downtrend detection")
    class SMA50Downtrend {

        @Test
        @DisplayName("returns true when price is below 50-bar SMA (macro downtrend)")
        void below50SmaDetected() {
            // 50 bars rising to build a high SMA50, then 10 sharp drops below it
            List<Double> prices = new ArrayList<>(rising(50, 100.0, 0.5));
            double peak = prices.get(prices.size() - 1);
            for (int i = 0; i < 10; i++) prices.add(peak - i * 3.0);
            double livePrice = prices.get(prices.size() - 1) - 2.0; // even lower than last bar
            assertTrue(manager.isShortTermDowntrend(prices, livePrice),
                "Price well below 50-SMA should trigger macro downtrend detection");
        }

        @Test
        @DisplayName("returns false when price is above 50-bar SMA in steady uptrend")
        void above50SmaNotDowntrend() {
            var prices = rising(65, 100.0, 0.5);
            double livePrice = prices.get(prices.size() - 1) + 0.5;
            assertFalse(manager.isShortTermDowntrend(prices, livePrice),
                "Price above 50-SMA in uptrend should not be flagged as downtrend");
        }
    }
}
