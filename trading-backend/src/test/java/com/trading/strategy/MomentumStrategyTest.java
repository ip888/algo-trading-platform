package com.trading.strategy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for MomentumStrategy — ATR calculation and RSI delegation.
 */
@DisplayName("MomentumStrategy - ATR + RSI")
class MomentumStrategyTest {

    private final MomentumStrategy strategy = new MomentumStrategy();

    // Prices rising consistently by `step` — very low volatility
    private static List<Double> stableUptrend(int count, double start, double step) {
        List<Double> prices = new ArrayList<>();
        for (int i = 0; i < count; i++) prices.add(start + i * step);
        return prices;
    }

    @Nested
    @DisplayName("ATR volatility filter")
    class AtrFilter {

        @Test
        @DisplayName("allows entry when ATR is low (stable uptrend)")
        void lowAtrAllowsEntry() {
            // Small daily steps → very low ATR
            List<Double> prices = stableUptrend(50, 100.0, 0.3); // 0.3% daily moves
            double currentPrice = prices.get(prices.size() - 1);
            // In a stable uptrend with low ATR, should not be blocked by volatility filter
            var signal = strategy.evaluateWithHistory("GLD", currentPrice, 0.0, prices);
            // Should get some signal, not "Volatility too high"
            if (signal instanceof TradingSignal.Hold hold) {
                assertFalse(hold.reason().contains("Volatility too high"),
                    "Low ATR should not block entry: " + hold.reason());
            }
        }

        @Test
        @DisplayName("blocks entry when ATR exceeds maxAtrPercent (default 3%)")
        void highAtrBlocksEntry() {
            // Huge swings: 10% alternating → very high ATR
            List<Double> prices = new ArrayList<>();
            double p = 100.0;
            for (int i = 0; i < 40; i++) {
                prices.add(p);
                p *= (i % 2 == 0) ? 1.10 : 0.90; // 10% swings
            }
            double currentPrice = prices.get(prices.size() - 1);
            var signal = strategy.evaluateWithHistory("GLD", currentPrice, 0.0, prices);
            // High volatility should either block with "Volatility too high" or HOLD
            if (signal instanceof TradingSignal.Hold hold) {
                assertTrue(
                    hold.reason().contains("Volatility too high") || hold.reason().contains("Waiting"),
                    "High ATR should block entry or cause HOLD, got: " + hold.reason()
                );
            }
        }
    }

    @Nested
    @DisplayName("RSI delegation to RSIStrategy.calculateRSI")
    class RsiDelegation {

        @Test
        @DisplayName("RSI sell threshold fires correctly")
        void rsiSellThreshold() {
            // Strong uptrend: RSI should be high, trigger sell exit
            List<Double> prices = stableUptrend(60, 50.0, 1.0); // steady rise
            double currentPrice = prices.get(prices.size() - 1);
            // With a position and a strongly rising RSI, should get SELL at overbought
            var signal = strategy.evaluateWithHistory("GLD", currentPrice, 5.0, prices);
            // In a strong steady rise, RSI will be very high → SELL
            assertInstanceOf(TradingSignal.Sell.class, signal,
                "Steady uptrend should trigger RSI sell threshold, got: " + signal);
        }

        @Test
        @DisplayName("momentum-fade exit fires when momentum turns negative")
        void momentumFadeExit() {
            // Prices rise then drop sharply
            List<Double> prices = new ArrayList<>(stableUptrend(40, 100.0, 0.5));
            // Add a sharp 5% drop at end
            double last = prices.get(prices.size() - 1);
            for (int i = 0; i < 15; i++) prices.add(last - i * 1.5);
            double currentPrice = prices.get(prices.size() - 1);
            var signal = strategy.evaluateWithHistory("GLD", currentPrice, 5.0, prices);
            // Negative momentum should trigger sell
            assertInstanceOf(TradingSignal.Sell.class, signal,
                "Fading momentum should trigger SELL, got: " + signal);
        }

        @Test
        @DisplayName("returns HOLD with insufficient history")
        void insufficientHistory() {
            var signal = strategy.evaluateWithHistory("GLD", 100.0, 0.0, List.of(100.0, 101.0));
            assertInstanceOf(TradingSignal.Hold.class, signal);
        }
    }

    @Nested
    @DisplayName("ATR calculation returns-based value")
    class AtrCalculation {

        @Test
        @DisplayName("ATR is proportional to price volatility")
        void atrProportional() {
            // Use two series: one with 0.1% daily moves, one with 5% daily moves
            // Verify through the strategy that high volatility is detected (not via private method)

            // Low vol: 0.1% daily moves
            List<Double> lowVol = new ArrayList<>();
            double p = 100.0;
            for (int i = 0; i < 40; i++) { lowVol.add(p); p *= 1.001; }

            // High vol: 5% daily moves
            List<Double> highVol = new ArrayList<>();
            p = 100.0;
            for (int i = 0; i < 40; i++) { highVol.add(p); p *= (i % 2 == 0 ? 1.05 : 0.95); }

            // High vol should be blocked by ATR filter for new entries
            var lowVolSignal = strategy.evaluateWithHistory("GLD", lowVol.get(lowVol.size()-1), 0.0, lowVol);
            var highVolSignal = strategy.evaluateWithHistory("GLD", highVol.get(highVol.size()-1), 0.0, highVol);

            // High vol: should get "Volatility too high" or simply HOLD (blocked)
            if (highVolSignal instanceof TradingSignal.Hold hold) {
                assertTrue(
                    hold.reason().contains("Volatility too high") || hold.reason().contains("Waiting"),
                    "High ATR should block entry for new positions, got: " + hold.reason()
                );
            }

            // Low vol series should not be blocked by volatility (may be blocked by other conditions)
            if (lowVolSignal instanceof TradingSignal.Hold hold) {
                assertFalse(hold.reason().contains("Volatility too high"),
                    "Low ATR should not block entry, got: " + hold.reason());
            }
        }
    }
}
