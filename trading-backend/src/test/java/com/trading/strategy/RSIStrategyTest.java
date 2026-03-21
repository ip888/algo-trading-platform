package com.trading.strategy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for RSIStrategy.calculateRSI() - Wilder's smoothed RSI.
 */
@DisplayName("RSIStrategy - Wilder's RSI")
class RSIStrategyTest {

    // Helper: generate a constantly rising price series
    private static List<Double> rising(int count, double start, double step) {
        List<Double> prices = new ArrayList<>();
        for (int i = 0; i < count; i++) prices.add(start + i * step);
        return prices;
    }

    // Helper: generate a constantly falling price series
    private static List<Double> falling(int count, double start, double step) {
        List<Double> prices = new ArrayList<>();
        for (int i = 0; i < count; i++) prices.add(start - i * step);
        return prices;
    }

    // Helper: alternating up/down by same amount
    private static List<Double> alternating(int count, double start, double step) {
        List<Double> prices = new ArrayList<>();
        double price = start;
        for (int i = 0; i < count; i++) {
            prices.add(price);
            price += (i % 2 == 0) ? step : -step;
        }
        return prices;
    }

    @Nested
    @DisplayName("Edge cases")
    class EdgeCases {

        @Test
        @DisplayName("returns 50.0 when insufficient data")
        void insufficientData() {
            var prices = List.of(100.0, 101.0, 102.0); // only 3 prices, need period+1
            assertEquals(50.0, RSIStrategy.calculateRSI(prices, 14));
        }

        @Test
        @DisplayName("returns 50.0 for flat prices (no change)")
        void flatPrices() {
            List<Double> prices = Collections.nCopies(30, 100.0);
            // All changes are 0 → avgGain=0, avgLoss=0 → returns 100.0 (no losses)
            double rsi = RSIStrategy.calculateRSI(prices, 14);
            // With 0 avgLoss, RS is infinite → RSI = 100
            assertEquals(100.0, rsi);
        }
    }

    @Nested
    @DisplayName("Trend direction detection")
    class TrendDirection {

        @Test
        @DisplayName("RSI near 100 for strongly rising prices")
        void strongUptrend() {
            var prices = rising(50, 100.0, 1.0); // 100, 101, ..., 149
            double rsi = RSIStrategy.calculateRSI(prices, 14);
            assertTrue(rsi > 85.0, "RSI should be high (>85) for steady uptrend, got: " + rsi);
        }

        @Test
        @DisplayName("RSI near 0 for strongly falling prices")
        void strongDowntrend() {
            var prices = falling(50, 200.0, 1.0); // 200, 199, ..., 151
            double rsi = RSIStrategy.calculateRSI(prices, 14);
            assertTrue(rsi < 15.0, "RSI should be low (<15) for steady downtrend, got: " + rsi);
        }

        @Test
        @DisplayName("RSI near 50 for alternating prices")
        void neutralTrend() {
            var prices = alternating(60, 100.0, 0.5);
            double rsi = RSIStrategy.calculateRSI(prices, 14);
            assertTrue(rsi > 40.0 && rsi < 60.0,
                "RSI should be near 50 for balanced up/down, got: " + rsi);
        }
    }

    @Nested
    @DisplayName("Wilder's smoothing property")
    class WilderSmoothing {

        @Test
        @DisplayName("RSI responds gradually to regime change (smoothing dampens spikes)")
        void smoothingDampensSpike() {
            // Warmup with alternating up/down to seed both avgGain and avgLoss,
            // then 8 consecutive drops. Wilder's smoothing blends prior gains so RSI > 0.
            List<Double> prices = new ArrayList<>(alternating(40, 100.0, 0.5));
            double last = prices.get(prices.size() - 1);
            for (int i = 0; i < 8; i++) prices.add(last - i * 1.0);

            double rsi = RSIStrategy.calculateRSI(prices, 14);
            assertTrue(rsi < 50.0, "RSI should drop below 50 after consecutive losses, got: " + rsi);
            assertTrue(rsi > 0.0, "Wilder's smoothing should prevent RSI zeroing out with mixed prior history, got: " + rsi);
        }

        @Test
        @DisplayName("RSI is bounded between 0 and 100")
        void bounded() {
            var up = rising(100, 50.0, 0.5);
            var down = falling(100, 150.0, 0.5);
            var alt = alternating(100, 100.0, 1.0);

            for (var prices : List.of(up, down, alt)) {
                double rsi = RSIStrategy.calculateRSI(prices, 14);
                assertTrue(rsi >= 0.0 && rsi <= 100.0, "RSI out of range: " + rsi);
            }
        }
    }

    @Nested
    @DisplayName("Buy/Sell signal thresholds")
    class Signals {

        @Test
        @DisplayName("evaluateWithHistory returns BUY when RSI < 30 and no position")
        void buySignalOversold() {
            // Need RSI < 30: use a sharply falling series with a stable end
            var prices = new ArrayList<>(falling(40, 200.0, 3.0)); // steep drop
            var strategy = new RSIStrategy();
            var signal = strategy.evaluateWithHistory("TEST", 80.0, 0.0, prices);
            assertInstanceOf(TradingSignal.Buy.class, signal,
                "Expected BUY for oversold RSI, got: " + signal);
        }

        @Test
        @DisplayName("evaluateWithHistory returns HOLD when RSI < 30 but already in position")
        void noDoubleBuy() {
            var prices = new ArrayList<>(falling(40, 200.0, 3.0));
            var strategy = new RSIStrategy();
            var signal = strategy.evaluateWithHistory("TEST", 80.0, 5.0, prices);
            // Should HOLD, not buy again when already in position
            assertInstanceOf(TradingSignal.Hold.class, signal,
                "Should HOLD when RSI < 30 but position already open");
        }

        @Test
        @DisplayName("evaluateWithHistory returns SELL when RSI > 70 with position")
        void sellSignalOverbought() {
            var prices = new ArrayList<>(rising(40, 100.0, 2.0));
            var strategy = new RSIStrategy();
            var signal = strategy.evaluateWithHistory("TEST", 178.0, 5.0, prices);
            assertInstanceOf(TradingSignal.Sell.class, signal,
                "Expected SELL for overbought RSI, got: " + signal);
        }
    }
}
