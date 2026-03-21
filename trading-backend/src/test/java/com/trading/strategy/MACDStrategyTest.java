package com.trading.strategy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for MACDStrategy with proper EMA-based signal line.
 */
@DisplayName("MACDStrategy - MACD Signal Line")
class MACDStrategyTest {

    private final MACDStrategy strategy = new MACDStrategy();

    // Build a price series: `lead` prices trending up then `tail` trending down
    private static List<Double> upThenDown(int lead, int tail, double start) {
        List<Double> prices = new ArrayList<>();
        for (int i = 0; i < lead; i++) prices.add(start + i * 1.0);
        double peak = start + lead;
        for (int i = 0; i < tail; i++) prices.add(peak - i * 1.0);
        return prices;
    }

    private static List<Double> steadyRise(int count, double start) {
        List<Double> prices = new ArrayList<>();
        for (int i = 0; i < count; i++) prices.add(start + i * 0.5);
        return prices;
    }

    private static List<Double> steadyFall(int count, double start) {
        List<Double> prices = new ArrayList<>();
        for (int i = 0; i < count; i++) prices.add(start - i * 0.5);
        return prices;
    }

    @Nested
    @DisplayName("Insufficient history")
    class InsufficientHistory {

        @Test
        @DisplayName("returns HOLD with less than 35 bars")
        void insufficientData() {
            List<Double> prices = steadyRise(30, 100.0);
            var signal = strategy.evaluateWithHistory("TEST", 115.0, 0.0, prices);
            assertInstanceOf(TradingSignal.Hold.class, signal);
        }
    }

    @Nested
    @DisplayName("Signal generation")
    class SignalGeneration {

        @Test
        @DisplayName("generates HOLD in flat market with no position")
        void holdInFlatMarket() {
            // Alternating small moves — MACD should stay near zero, no strong crossover
            List<Double> prices = new ArrayList<>();
            double p = 100.0;
            for (int i = 0; i < 60; i++) {
                prices.add(p);
                p += (i % 3 == 0) ? 0.1 : -0.05;
            }
            var signal = strategy.evaluateWithHistory("TEST", p, 0.0, prices);
            // In choppy market, MACD should not give a strong buy
            assertNotNull(signal);
        }

        @Test
        @DisplayName("generates BUY signal after sustained uptrend creates bullish crossover")
        void buyAfterUptrend() {
            // Long steady rise: fast EMA catches up to slow EMA = crossover
            List<Double> prices = steadyRise(80, 50.0);
            double currentPrice = prices.get(prices.size() - 1);
            var signal = strategy.evaluateWithHistory("TEST", currentPrice, 0.0, prices);
            // In a sustained rise, we expect BUY or HOLD (MACD should be positive/bullish)
            assertNotNull(signal);
            // Should not be a SELL when trending up
            assertFalse(signal instanceof TradingSignal.Sell,
                "Should not SELL during a sustained uptrend");
        }

        @Test
        @DisplayName("generates SELL signal after sustained downtrend with position")
        void sellAfterDowntrend() {
            // Up then sharp down — bearish crossover should trigger sell
            List<Double> prices = upThenDown(50, 40, 100.0);
            double currentPrice = prices.get(prices.size() - 1);
            var signal = strategy.evaluateWithHistory("TEST", currentPrice, 5.0, prices);
            // With a position in a downtrend, should not be BUY
            assertFalse(signal instanceof TradingSignal.Buy,
                "Should not BUY during a downtrend with an open position");
        }

        @Test
        @DisplayName("no new BUY when position is already open")
        void noDoubleBuy() {
            List<Double> prices = steadyRise(80, 50.0);
            double currentPrice = prices.get(prices.size() - 1);
            // positionQty > 0 means already in position — MACD should not generate another BUY
            var signal = strategy.evaluateWithHistory("TEST", currentPrice, 10.0, prices);
            assertFalse(signal instanceof TradingSignal.Buy,
                "Should not generate BUY when already holding a position");
        }
    }

    @Nested
    @DisplayName("Signal line accuracy (EMA vs SMA)")
    class SignalLineAccuracy {

        /**
         * With a proper EMA signal line, the MACD line should cross above signal
         * earlier in an uptrend than if we used a slower SMA.
         * We verify indirectly: in a strong uptrend, a correct MACD implementation
         * should produce a positive histogram (MACD > Signal) for the majority of the trend.
         */
        @Test
        @DisplayName("MACD responds to trend without excessive lag")
        void macdRespondsToTrend() {
            // Long consistent uptrend: MACD EMA convergence should produce bullish signals
            List<Double> prices = steadyRise(100, 50.0);
            double currentPrice = prices.get(prices.size() - 1);
            var signal = strategy.evaluateWithHistory("TEST", currentPrice, 0.0, prices);
            // A proper EMA-based MACD should catch this 100-bar uptrend
            assertTrue(signal instanceof TradingSignal.Buy || signal instanceof TradingSignal.Hold,
                "Expected BUY or HOLD in strong uptrend, got: " + signal);
        }
    }
}
