package com.trading.strategy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for MeanReversionStrategy:
 * - BUY at lower Bollinger Band
 * - SELL at SMA + 1.5σ (not at SMA — to capture more of the reversion move)
 * - HOLD between bands
 */
@DisplayName("MeanReversionStrategy — Bollinger Band mean reversion")
class MeanReversionStrategyTest {

    private final MeanReversionStrategy strategy = new MeanReversionStrategy();

    /** Flat prices around a mean — produces tight Bollinger Bands. */
    private static List<Double> flat(int count, double value) {
        return Collections.nCopies(count, value);
    }

    @Nested
    @DisplayName("Entry signals")
    class Entry {

        @Test
        @DisplayName("returns HOLD with insufficient history")
        void insufficientHistory() {
            var prices = flat(15, 100.0); // < PERIOD (20)
            var signal = strategy.evaluateWithHistory("TEST", 100.0, 0.0, prices);
            assertInstanceOf(TradingSignal.Hold.class, signal);
        }

        @Test
        @DisplayName("returns BUY when price is at the lower Bollinger Band")
        void buyAtLowerBand() {
            // 30 bars alternating ±2 around 100 to create std-dev, then a sharp drop
            List<Double> prices = new ArrayList<>();
            for (int i = 0; i < 30; i++) prices.add(100.0 + (i % 2 == 0 ? 2.0 : -2.0));
            // Price well below the lower band (SMA - 2.5*stdDev)
            double lowerBandApprox = 100.0 - 2.5 * 2.0; // ~95
            double belowBand = lowerBandApprox - 1.0;    // ~94
            var signal = strategy.evaluateWithHistory("TEST", belowBand, 0.0, prices);
            assertInstanceOf(TradingSignal.Buy.class, signal,
                "Should BUY when price is below lower Bollinger Band, got: " + signal);
        }

        @Test
        @DisplayName("returns HOLD when price is between bands")
        void holdBetweenBands() {
            List<Double> prices = new ArrayList<>();
            for (int i = 0; i < 30; i++) prices.add(100.0 + (i % 2 == 0 ? 2.0 : -2.0));
            // Price right at the SMA — between bands
            var signal = strategy.evaluateWithHistory("TEST", 100.0, 0.0, prices);
            assertInstanceOf(TradingSignal.Hold.class, signal,
                "Price at SMA with no position should HOLD, got: " + signal);
        }

        @Test
        @DisplayName("no BUY when position already open")
        void noBuyWithPosition() {
            List<Double> prices = new ArrayList<>();
            for (int i = 0; i < 30; i++) prices.add(100.0 + (i % 2 == 0 ? 2.0 : -2.0));
            double belowBand = 93.0;
            var signal = strategy.evaluateWithHistory("TEST", belowBand, 5.0, prices);
            assertFalse(signal instanceof TradingSignal.Buy,
                "Should not BUY again when already holding a position");
        }
    }

    @Nested
    @DisplayName("Exit signals — SMA+1.5σ target (not bare SMA)")
    class Exit {

        @Test
        @DisplayName("returns SELL when price reaches SMA + 1.5σ (reversion target)")
        void sellAtReversionTarget() {
            // 30 bars alternating ±2 → SMA ≈ 100, stdDev ≈ 2
            List<Double> prices = new ArrayList<>();
            for (int i = 0; i < 30; i++) prices.add(100.0 + (i % 2 == 0 ? 2.0 : -2.0));
            // SMA + 1.5 * stdDev ≈ 100 + 1.5 * 2 = 103
            double reversionTarget = 103.5; // above SMA + 1.5σ
            var signal = strategy.evaluateWithHistory("TEST", reversionTarget, 5.0, prices);
            assertInstanceOf(TradingSignal.Sell.class, signal,
                "Should SELL when price reaches SMA+1.5σ reversion target, got: " + signal);
        }

        @Test
        @DisplayName("holds at SMA — does not exit prematurely (waits for SMA+1.5σ)")
        void holdAtSmaNotEarlyExit() {
            // Old behaviour exited at SMA. New behaviour should hold until SMA+1.5σ.
            List<Double> prices = new ArrayList<>();
            for (int i = 0; i < 30; i++) prices.add(100.0 + (i % 2 == 0 ? 2.0 : -2.0));
            // Price exactly at SMA (100.0) — below the new exit target (~103)
            var signal = strategy.evaluateWithHistory("TEST", 100.0, 5.0, prices);
            assertInstanceOf(TradingSignal.Hold.class, signal,
                "Should HOLD at SMA — wait for SMA+1.5σ target before exiting, got: " + signal);
        }

        @Test
        @DisplayName("SELL reason mentions reversion target")
        void sellReasonDescriptive() {
            List<Double> prices = new ArrayList<>();
            for (int i = 0; i < 30; i++) prices.add(100.0 + (i % 2 == 0 ? 2.0 : -2.0));
            var signal = strategy.evaluateWithHistory("TEST", 104.0, 5.0, prices);
            if (signal instanceof TradingSignal.Sell sell) {
                assertTrue(sell.reason().toLowerCase().contains("mean reversion") ||
                           sell.reason().toLowerCase().contains("target"),
                    "Sell reason should describe reversion target, got: " + sell.reason());
            }
        }
    }
}
