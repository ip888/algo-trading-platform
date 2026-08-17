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
 * Verifies that the MTF override in StrategyManager.evaluate() correctly defers
 * BUY signals for momentum assets to MomentumStrategy instead of returning raw MTF BUY.
 *
 * Background: before the fix, MTF confidence > 0.7 returned a BUY directly, bypassing
 * MomentumStrategy's strict entry conditions (RSI sweet spot, SMA50, ATR, momentum
 * consistency). This caused NVDA to be bought in downtrends when MTF had a brief spike.
 */
@DisplayName("StrategyManager — MTF BUY deferral for momentum assets")
class StrategyManagerMtfMomentumTest {

    private BrokerClient mockClient;
    private MultiTimeframeAnalyzer mockMtf;
    private StrategyManager manager;

    // Build bars whose close prices come from the given list (oldest first).
    private static List<Bar> barsFrom(List<Double> closes) {
        List<Bar> bars = new ArrayList<>();
        Instant base = Instant.parse("2026-01-01T00:00:00Z");
        for (int i = 0; i < closes.size(); i++) {
            double c = closes.get(i);
            bars.add(new Bar(base.plusSeconds((long) i * 86400), c, c * 1.01, c * 0.99, c, 1_000_000L));
        }
        return bars;
    }

    // 100 bars that start high and decline sharply — price ends well below SMA50,
    // so MomentumStrategy would refuse to enter (SMA50 gate + RSI not in sweet spot).
    private static List<Bar> downtrendingBars() {
        List<Double> closes = new ArrayList<>();
        for (int i = 0; i < 100; i++) closes.add(300.0 - i * 1.5); // 300 → 151.5
        return barsFrom(closes);
    }

    // 100 bars in a clean uptrend — MomentumStrategy may enter.
    private static List<Bar> uptrendingBars() {
        List<Double> closes = new ArrayList<>();
        for (int i = 0; i < 100; i++) closes.add(100.0 + i * 0.5); // 100 → 149.5
        return barsFrom(closes);
    }

    private static MultiTimeframeAnalysis mtfBuy(String symbol, double confidence) {
        return new MultiTimeframeAnalysis(
            symbol,
            List.of(),
            true,
            MultiTimeframeAnalyzer.TrendDirection.STRONG_UP,
            confidence,
            MultiTimeframeAnalyzer.SignalType.BUY,
            Instant.now()
        );
    }

    private static MultiTimeframeAnalysis mtfSell(String symbol) {
        return new MultiTimeframeAnalysis(
            symbol,
            List.of(),
            true,
            MultiTimeframeAnalyzer.TrendDirection.STRONG_DOWN,
            0.85,
            MultiTimeframeAnalyzer.SignalType.SELL,
            Instant.now()
        );
    }

    private static MultiTimeframeAnalysis mtfHold(String symbol) {
        return new MultiTimeframeAnalysis(
            symbol,
            List.of(),
            false,
            MultiTimeframeAnalyzer.TrendDirection.NEUTRAL,
            0.75,
            MultiTimeframeAnalyzer.SignalType.HOLD,
            Instant.now()
        );
    }

    @BeforeEach
    void setUp() throws Exception {
        // ByteBuddy inline mocking doesn't yet support Java 25 — use SUBCLASS for both.
        mockClient = mock(BrokerClient.class,
            withSettings().mockMaker(org.mockito.MockMakers.SUBCLASS));
        mockMtf = mock(MultiTimeframeAnalyzer.class,
            withSettings().mockMaker(org.mockito.MockMakers.SUBCLASS));
        manager = new StrategyManager(mockClient, mockMtf, null);
    }

    @Nested
    @DisplayName("Momentum asset with high-confidence MTF BUY")
    class MomentumAssetMtfBuy {

        @Test
        @DisplayName("downtrending NVDA: MTF BUY deferred → MomentumStrategy returns HOLD")
        void nvdaDowntrendMtfBuyDeferred() throws Exception {
            // NVDA is in the default momentumAssets set.
            // Price history is sharply downtrending — MomentumStrategy will reject entry.
            when(mockClient.getMarketHistory(eq("NVDA"), anyInt()))
                .thenReturn(downtrendingBars());
            when(mockMtf.analyze("NVDA")).thenReturn(mtfBuy("NVDA", 0.85));

            var signal = manager.evaluate("NVDA", 151.5, 0.0, MarketRegime.WEAK_BULL);

            assertInstanceOf(TradingSignal.Hold.class, signal,
                "Downtrending NVDA: MTF BUY must be deferred to MomentumStrategy, which should HOLD");
        }

        @Test
        @DisplayName("uptrending GLD: MTF BUY deferred → MomentumStrategy evaluates (no raw return)")
        void gldUptrendMtfBuyDeferred() throws Exception {
            // GLD is also in momentumAssets. With an uptrend, MomentumStrategy may or may not
            // enter (depends on RSI/momentum state), but the signal must NOT be the raw MTF BUY.
            when(mockClient.getMarketHistory(eq("GLD"), anyInt()))
                .thenReturn(uptrendingBars());
            when(mockMtf.analyze("GLD")).thenReturn(mtfBuy("GLD", 0.80));

            var signal = manager.evaluate("GLD", 149.5, 0.0, MarketRegime.STRONG_BULL);

            // If MomentumStrategy returns BUY, its reason must NOT be the raw MTF string.
            if (signal instanceof TradingSignal.Buy buy) {
                assertFalse(buy.reason().contains("Multi-timeframe BUY signal"),
                    "BUY reason must come from MomentumStrategy, not raw MTF: " + buy.reason());
            }
            // HOLD is also acceptable — MomentumStrategy may not have met all conditions.
        }

        @Test
        @DisplayName("low MTF confidence (<= 0.7): falls through to evaluateWithHistory directly")
        void lowConfidenceDoesNotTriggerMtfPath() throws Exception {
            when(mockClient.getMarketHistory(eq("NVDA"), anyInt()))
                .thenReturn(downtrendingBars());
            when(mockMtf.analyze("NVDA")).thenReturn(mtfBuy("NVDA", 0.65));

            // Should not throw and should not return raw MTF BUY
            var signal = manager.evaluate("NVDA", 151.5, 0.0, MarketRegime.WEAK_BULL);
            if (signal instanceof TradingSignal.Buy buy) {
                assertFalse(buy.reason().contains("Multi-timeframe BUY signal"),
                    "Low confidence MTF should not short-circuit to raw BUY");
            }
        }
    }

    @Nested
    @DisplayName("MTF SELL and HOLD are always returned directly")
    class MtfSellAndHold {

        @Test
        @DisplayName("MTF SELL is returned immediately regardless of asset type")
        void mtfSellReturnedDirectly() throws Exception {
            when(mockClient.getMarketHistory(anyString(), anyInt()))
                .thenReturn(uptrendingBars());
            when(mockMtf.analyze("NVDA")).thenReturn(mtfSell("NVDA"));

            var signal = manager.evaluate("NVDA", 149.5, 1.0, MarketRegime.WEAK_BULL);

            assertInstanceOf(TradingSignal.Sell.class, signal,
                "MTF SELL must be returned immediately without deferring to any strategy");
        }

        @Test
        @DisplayName("MTF HOLD is returned immediately regardless of asset type")
        void mtfHoldReturnedDirectly() throws Exception {
            when(mockClient.getMarketHistory(anyString(), anyInt()))
                .thenReturn(uptrendingBars());
            when(mockMtf.analyze("SPY")).thenReturn(mtfHold("SPY"));

            var signal = manager.evaluate("SPY", 149.5, 0.0, MarketRegime.WEAK_BULL);

            assertInstanceOf(TradingSignal.Hold.class, signal,
                "MTF HOLD must be returned immediately");
        }
    }

    // ── MTF Trend Entry gate (STRONG_BULL last-resort BUY conditions) ─────────
    // This block fires when STRONG_BULL + high MTF confidence + positionQty==0.
    // It requires: history.size()>=20, currentPrice > SMA-20, RSI in [38,80].
    // The outer evaluate() adds a day-change gate (blocks BUY when stock is down >0.5%).
    // Tests here verify all four guard conditions and their failure modes.

    // Bars alternating 2 up (0.8) / 2 down (0.4) for moderate uptrend:
    //   avg_gain ≈ 0.4, avg_loss ≈ 0.2 → RS ≈ 2.0 → RSI ≈ 67 (in [38,80])
    //   net gain ≈ 0.2/bar → over 100 bars: price 100 → 120.
    //   SMA-20 of last 20 bars ≈ 118; last bar ≈ 120 → price > SMA-20 ✓
    private static List<Bar> moderateUptrendBars() {
        List<Double> closes = new ArrayList<>();
        double price = 100.0;
        for (int i = 0; i < 100; i++) {
            price += (i % 4 < 2) ? 0.8 : -0.4;  // 2 up, 2 down → net +0.2/bar; RSI ≈ 67
            closes.add(price);
        }
        return barsFrom(closes);
    }

    @Nested
    @DisplayName("MTF Trend Entry gate — STRONG_BULL last-resort conditions")
    class MtfTrendEntryConditions {

        @Test
        @DisplayName("STRONG_BULL + downtrend (price < SMA-20, RSI < 38) → HOLD even with high-confidence MTF BUY")
        void strongBull_downtrendBlocked() throws Exception {
            // Aug 17 scenario: OIH RSI=26.6, GLD RSI=26.3, XLE RSI=35.9 — all recovering from
            // daily sell-offs. High 15-min MTF confidence BUT daily SMA-20 and RSI block entry.
            when(mockClient.getMarketHistory(eq("GLD"), anyInt())).thenReturn(downtrendingBars());
            when(mockMtf.analyze("GLD")).thenReturn(mtfBuy("GLD", 0.83));

            var signal = manager.evaluate("GLD", 151.5, 0.0, MarketRegime.STRONG_BULL);

            assertInstanceOf(TradingSignal.Hold.class, signal,
                "Downtrending GLD (price < SMA-20, RSI likely < 38) must HOLD — " + signal);
        }

        @Test
        @DisplayName("STRONG_BULL + moderate uptrend + stock down >0.5% on the day → day-change gate blocks BUY")
        void strongBull_dayChangeTooNegative_blocked() throws Exception {
            // All internal MTF Trend Entry conditions pass (SMA-20, RSI 38-80) but the outer
            // evaluate() day-change guard catches a -0.6% move → blocks the BUY.
            List<Bar> bars = moderateUptrendBars();
            double yesterdayClose = bars.get(bars.size() - 1).close(); // last bar = "yesterday"
            double currentPrice = yesterdayClose * (1.0 - 0.006);     // -0.6% today → triggers gate

            when(mockClient.getMarketHistory(eq("GLD"), anyInt())).thenReturn(bars);
            when(mockMtf.analyze("GLD")).thenReturn(mtfBuy("GLD", 0.83));

            var signal = manager.evaluate("GLD", currentPrice, 0.0, MarketRegime.STRONG_BULL);

            assertInstanceOf(TradingSignal.Hold.class, signal,
                "GLD down -0.6% on the day must be blocked by day-change gate — " + signal);
        }

        @Test
        @DisplayName("STRONG_BULL + moderate uptrend + flat on day → MTF Trend Entry fires BUY")
        void strongBull_goodConditions_buyFires() throws Exception {
            // Moderate uptrend: RSI ≈ 67 (in [38,80]), price > SMA-20, 0% day change.
            List<Bar> bars = moderateUptrendBars();
            double currentPrice = bars.get(bars.size() - 1).close(); // exactly yesterday's close

            when(mockClient.getMarketHistory(eq("GLD"), anyInt())).thenReturn(bars);
            when(mockMtf.analyze("GLD")).thenReturn(mtfBuy("GLD", 0.83));

            var signal = manager.evaluate("GLD", currentPrice, 0.0, MarketRegime.STRONG_BULL);

            assertInstanceOf(TradingSignal.Buy.class, signal,
                "STRONG_BULL + good uptrend conditions + 0% day change → MTF Trend Entry should BUY — " + signal);
        }

        @Test
        @DisplayName("STRONG_BULL + existing position (positionQty > 0) → no re-entry via MTF Trend Entry")
        void strongBull_existingPosition_noReEntry() throws Exception {
            List<Bar> bars = moderateUptrendBars();
            double currentPrice = bars.get(bars.size() - 1).close();

            when(mockClient.getMarketHistory(eq("GLD"), anyInt())).thenReturn(bars);
            when(mockMtf.analyze("GLD")).thenReturn(mtfBuy("GLD", 0.83));

            // positionQty = 1.5 → MTF Trend Entry gate requires positionQty == 0
            var signal = manager.evaluate("GLD", currentPrice, 1.5, MarketRegime.STRONG_BULL);

            assertFalse(signal instanceof TradingSignal.Buy,
                "MTF Trend Entry requires positionQty==0; should not fire while holding — " + signal);
        }
    }

    @Nested
    @DisplayName("Non-momentum asset with high-confidence MTF BUY")
    class NonMomentumAssetMtfBuy {

        @Test
        @DisplayName("uptrending SPY: MTF BUY returned directly (not deferred)")
        void spyUptrendMtfBuyDirect() throws Exception {
            // SPY is not a momentum asset — MTF BUY should be used directly (after downtrend/volume checks).
            when(mockClient.getMarketHistory(eq("SPY"), anyInt()))
                .thenReturn(uptrendingBars());
            when(mockMtf.analyze("SPY")).thenReturn(mtfBuy("SPY", 0.85));

            var signal = manager.evaluate("SPY", 149.5, 0.0, MarketRegime.WEAK_BULL);

            // For a clean uptrend + high volume, should be BUY with MTF reason
            if (signal instanceof TradingSignal.Buy buy) {
                assertTrue(buy.reason().contains("Multi-timeframe BUY"),
                    "Non-momentum asset MTF BUY should be returned directly, reason: " + buy.reason());
            }
            // If volume check blocks it, HOLD is acceptable too — but not a deferred strategy signal
        }
    }
}
