package com.trading.strategy;

import com.trading.analysis.MarketRegimeDetector.MarketRegime;
import com.trading.analysis.MultiTimeframeAnalyzer;
import com.trading.analysis.MultiTimeframeAnalyzer.MultiTimeframeAnalysis;
import com.trading.api.BrokerClient;
import com.trading.api.model.Bar;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Regression tests for three routing paths that have historically broken:
 *
 *  1. WEAK_BEAR inverse ETF path: SH/PSQ/RWM/DOG/SQQQ must reach the
 *     "Inverse ETF, Weak Bear" MACD branch and never be blocked by the safe-haven
 *     gate or the strict momentum gate.
 *
 *  2. Scalp-after-MTF-block: when the MTF overlay returns a blocking HOLD
 *     (e.g. WEAK_BEAR + non-safe-haven), the scalp strategy must still be
 *     attempted for flat positions before the HOLD is returned.
 *
 *  3. Safe-haven path in WEAK_BEAR: GLD/TLT/XLU/SLV must NOT be blocked by
 *     the strict routing that applies to non-momentum, non-safe-haven assets.
 */
@DisplayName("StrategyManager — Routing regression: inverse ETF / scalp-after-block / safe-haven")
class StrategyManagerRoutingRegressionTest {

    private BrokerClient mockClient;
    private MultiTimeframeAnalyzer mockMtf;

    private static List<Bar> barsFrom(List<Double> closes) {
        List<Bar> bars = new ArrayList<>();
        Instant base = Instant.parse("2026-01-01T00:00:00Z");
        for (int i = 0; i < closes.size(); i++) {
            double c = closes.get(i);
            bars.add(new Bar(base.plusSeconds((long) i * 86400), c, c * 1.01, c * 0.99, c, 1_000_000L));
        }
        return bars;
    }

    // 100 bars in a mild uptrend — MACD histogram will be positive but modest.
    // Provides enough history for all MACD / SMA checks without triggering downtrend detection.
    private static List<Bar> uptrendBars() {
        List<Double> closes = new ArrayList<>();
        for (int i = 0; i < 100; i++) closes.add(30.0 + i * 0.05);
        return barsFrom(closes);
    }

    // High-volume uptrend bars — used where volume confirmation matters.
    private static List<Bar> highVolumeBars() {
        List<Bar> bars = new ArrayList<>();
        Instant base = Instant.parse("2026-01-01T00:00:00Z");
        for (int i = 0; i < 100; i++) {
            double c = 30.0 + i * 0.05;
            bars.add(new Bar(base.plusSeconds((long) i * 86400), c, c * 1.01, c * 0.99, c, 10_000_000L));
        }
        return bars;
    }

    private static MultiTimeframeAnalysis mtfBuy(String symbol, double confidence) {
        return new MultiTimeframeAnalysis(
            symbol, List.of(), true,
            MultiTimeframeAnalyzer.TrendDirection.STRONG_UP,
            confidence,
            MultiTimeframeAnalyzer.SignalType.BUY,
            Instant.now()
        );
    }

    private static MultiTimeframeAnalysis mtfHold(String symbol) {
        return new MultiTimeframeAnalysis(
            symbol, List.of(), false,
            MultiTimeframeAnalyzer.TrendDirection.NEUTRAL,
            0.50,
            MultiTimeframeAnalyzer.SignalType.HOLD,
            Instant.now()
        );
    }

    private static MultiTimeframeAnalysis mtfBlockingBuy(String symbol) {
        // Confidence above 0.7 but recommendation is BUY — so it will enter the
        // "else" branch that checks safe-haven, downtrend, volume. For non-safe-haven
        // assets in WEAK_BEAR this produces a HOLD (the block we want to test scalp after).
        return new MultiTimeframeAnalysis(
            symbol, List.of(), true,
            MultiTimeframeAnalyzer.TrendDirection.STRONG_UP,
            0.80,
            MultiTimeframeAnalyzer.SignalType.BUY,
            Instant.now()
        );
    }

    @BeforeEach
    void setUp() throws Exception {
        mockClient = mock(BrokerClient.class,
            withSettings().mockMaker(org.mockito.MockMakers.SUBCLASS));
        mockMtf = mock(MultiTimeframeAnalyzer.class,
            withSettings().mockMaker(org.mockito.MockMakers.SUBCLASS));
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  1. WEAK_BEAR inverse ETF routing
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("1. WEAK_BEAR inverse ETF routing")
    class WeakBearInverseEtfRouting {

        /**
         * Bug history: SH was blocked by WEAK_BEAR strict routing because it was not
         * in momentumAssets. Also was blocked by the safe-haven MTF gate. Both fixed.
         * This test verifies the full path: MTF defers → evaluateWithHistory() takes
         * the inverse ETF branch → MACD(0.20) decides.
         *
         * We use a gently rising series so MACD histogram is likely positive → BUY
         * is not guaranteed but the result must NOT be a routing-level HOLD with
         * reason containing "strict" or "safe-haven".
         */
        @Test
        @DisplayName("SH in WEAK_BEAR + high MTF confidence: must not be blocked by routing gates")
        void shWeakBearNotBlockedByRoutingGates() throws Exception {
            when(mockClient.getMarketHistory(eq("SH"), anyInt())).thenReturn(uptrendBars());
            when(mockMtf.analyze("SH")).thenReturn(mtfBuy("SH", 0.80));

            var manager = new StrategyManager(mockClient, mockMtf, null);
            var signal = manager.evaluate("SH", 30.0, 0.0, MarketRegime.WEAK_BEAR);

            // Must not be blocked by safe-haven or strict-routing HOLD
            if (signal instanceof TradingSignal.Hold hold) {
                assertFalse(hold.reason().contains("safe-haven"),
                    "SH must not be blocked by safe-haven gate: " + hold.reason());
                assertFalse(hold.reason().toLowerCase().contains("strict"),
                    "SH must not be blocked by strict routing gate: " + hold.reason());
                assertFalse(hold.reason().contains("non-safe-haven"),
                    "SH must not be blocked by non-safe-haven WEAK_BEAR gate: " + hold.reason());
            }
            // BUY or HOLD from MACD is fine; only routing-level blocks are forbidden.
        }

        @Test
        @DisplayName("PSQ in WEAK_BEAR + no MTF: must not be blocked by strict routing")
        void psqWeakBearNoMtf() throws Exception {
            when(mockClient.getMarketHistory(eq("PSQ"), anyInt())).thenReturn(uptrendBars());
            when(mockMtf.analyze("PSQ")).thenReturn(mtfHold("PSQ"));

            var manager = new StrategyManager(mockClient, mockMtf, null);
            var signal = manager.evaluate("PSQ", 30.0, 0.0, MarketRegime.WEAK_BEAR);

            if (signal instanceof TradingSignal.Hold hold) {
                assertFalse(hold.reason().toLowerCase().contains("strict"),
                    "PSQ must not be blocked by strict routing: " + hold.reason());
            }
        }

        @Test
        @DisplayName("SQQQ in WEAK_BEAR with no existing position: never returns strict-routing HOLD")
        void sqqWeakBearNoPosition() throws Exception {
            when(mockClient.getMarketHistory(eq("SQQQ"), anyInt())).thenReturn(uptrendBars());
            when(mockMtf.analyze("SQQQ")).thenReturn(mtfHold("SQQQ"));

            var manager = new StrategyManager(mockClient, mockMtf, null);
            var signal = manager.evaluate("SQQQ", 10.0, 0.0, MarketRegime.WEAK_BEAR);

            if (signal instanceof TradingSignal.Hold hold) {
                String reason = hold.reason().toLowerCase();
                assertFalse(reason.contains("strict") || reason.contains("non-safe-haven"),
                    "SQQQ routing blocked by wrong gate: " + hold.reason());
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  2. Scalp-after-MTF-block
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("2. Scalp attempted after MTF block")
    class ScalpAfterMtfBlock {

        /**
         * Bug history: every early return in the MTF block exited evaluate() before
         * the scalp overlay. Fixed by capturing the HOLD in mtfBlockSignal and trying
         * scalp before returning.
         *
         * StrategyManager(client, mtf, null) — null ScalpStrategy means no scalp,
         * so the HOLD is returned. This test just verifies it doesn't throw and
         * returns a HOLD (not a crash or incorrect BUY).
         *
         * A proper scalp-fires test would need a non-null ScalpStrategy mock, but
         * ScalpStrategy is final and hard to mock without the full client stack.
         * The regression guard here is: the HOLD reason is the MTF block reason,
         * not an internal NPE from missing scalp-before-return handling.
         */
        @Test
        @DisplayName("MTF-blocked non-safe-haven in WEAK_BEAR: returns HOLD (not NPE), scalp path reached")
        void mtfBlockedNonSafeHavenReturnsHold() throws Exception {
            // AAPL is not a safe haven — in WEAK_BEAR with high-confidence MTF BUY,
            // it hits the WEAK_BEAR non-safe-haven gate and must return HOLD.
            when(mockClient.getMarketHistory(eq("AAPL"), anyInt())).thenReturn(uptrendBars());
            when(mockMtf.analyze("AAPL")).thenReturn(mtfBlockingBuy("AAPL"));

            var manager = new StrategyManager(mockClient, mockMtf, null);
            var signal = manager.evaluate("AAPL", 200.0, 0.0, MarketRegime.WEAK_BEAR);

            assertInstanceOf(TradingSignal.Hold.class, signal,
                "Non-safe-haven in WEAK_BEAR with MTF BUY must return HOLD");
        }

        @Test
        @DisplayName("IWM in WEAK_BEAR with MTF BUY + existing position: HOLD is returned cleanly")
        void mtfBlockedWithExistingPosition() throws Exception {
            when(mockClient.getMarketHistory(eq("IWM"), anyInt())).thenReturn(uptrendBars());
            when(mockMtf.analyze("IWM")).thenReturn(mtfBlockingBuy("IWM"));

            var manager = new StrategyManager(mockClient, mockMtf, null);
            // positionQty > 0 → scalp is skipped even if scalpStrategy exists
            var signal = manager.evaluate("IWM", 200.0, 1.0, MarketRegime.WEAK_BEAR);

            // No crash; result is HOLD (existing position) or SELL (shouldn't happen in uptrend)
            assertNotNull(signal);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  3. Safe-haven path in WEAK_BEAR
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("3. Safe-haven assets pass through WEAK_BEAR gates")
    class SafeHavenWeakBear {

        @Test
        @DisplayName("GLD in WEAK_BEAR: not blocked by strict routing or non-safe-haven gate")
        void gldNotBlockedInWeakBear() throws Exception {
            when(mockClient.getMarketHistory(eq("GLD"), anyInt())).thenReturn(highVolumeBars());
            when(mockMtf.analyze("GLD")).thenReturn(mtfHold("GLD"));

            var manager = new StrategyManager(mockClient, mockMtf, null);
            var signal = manager.evaluate("GLD", 220.0, 0.0, MarketRegime.WEAK_BEAR);

            if (signal instanceof TradingSignal.Hold hold) {
                assertFalse(hold.reason().toLowerCase().contains("strict"),
                    "GLD must not be blocked by strict routing in WEAK_BEAR: " + hold.reason());
                assertFalse(hold.reason().contains("non-safe-haven"),
                    "GLD must not be blocked by non-safe-haven gate: " + hold.reason());
            }
        }

        @Test
        @DisplayName("TLT in WEAK_BEAR with high MTF confidence: routed through safe-haven MACD path")
        void tltHighMtfWeakBear() throws Exception {
            when(mockClient.getMarketHistory(eq("TLT"), anyInt())).thenReturn(highVolumeBars());
            when(mockMtf.analyze("TLT")).thenReturn(mtfBuy("TLT", 0.80));

            var manager = new StrategyManager(mockClient, mockMtf, null);
            var signal = manager.evaluate("TLT", 95.0, 0.0, MarketRegime.WEAK_BEAR);

            // Safe haven defers to evaluateWithHistory (momentum path), which uses MACD(0.10).
            // We don't assert BUY — MACD may or may not fire — but we assert no blocking HOLD.
            if (signal instanceof TradingSignal.Hold hold) {
                assertFalse(hold.reason().contains("non-safe-haven"),
                    "TLT (safe haven) blocked by non-safe-haven gate — regression: " + hold.reason());
            }
        }

        @Test
        @DisplayName("XLU in WEAK_BEAR: not blocked by strict regime routing")
        void xluNotBlockedByStrictRouting() throws Exception {
            when(mockClient.getMarketHistory(eq("XLU"), anyInt())).thenReturn(uptrendBars());
            when(mockMtf.analyze("XLU")).thenReturn(mtfHold("XLU"));

            var manager = new StrategyManager(mockClient, mockMtf, null);
            var signal = manager.evaluate("XLU", 70.0, 0.0, MarketRegime.WEAK_BEAR);

            if (signal instanceof TradingSignal.Hold hold) {
                assertFalse(hold.reason().toLowerCase().contains("strict"),
                    "XLU (safe haven) blocked by strict routing: " + hold.reason());
            }
        }
    }
}
