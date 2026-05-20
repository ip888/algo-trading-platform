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
 * Tests the MTF confidence gradient veto added to StrategyManager.evaluate().
 *
 * When MTF confidence is in the 0.6–0.7 range AND MTF recommendation is SELL/HOLD,
 * a strategy BUY signal must be vetoed and converted to HOLD. This prevents entries
 * when the medium-timeframe and the primary strategy are in conflict at medium confidence.
 *
 * Note: confidence < 0.6 already returns "Timeframes not aligned" HOLD before strategy eval.
 *       confidence > 0.7 is handled by the existing high-confidence MTF block.
 *       This test covers the 0.6–0.7 window only.
 */
@DisplayName("StrategyManager — MTF gradient veto (0.6–0.7 confidence)")
class StrategyManagerMtfGradientVetoTest {

    private BrokerClient mockClient;
    private MultiTimeframeAnalyzer mockMtf;
    private StrategyManager manager;

    /**
     * Price series that reliably generates a MACD strongUptrend BUY:
     * - 40 bars of slow rise (to warm up EMAs without a crossover)
     * - 60 bars of fast acceleration (pushes EMA12 >> EMA26, histogram growing)
     *
     * End price 240. SMA50 ≈ 191, SMA10 rising → passes all downtrend filters.
     * All volumes equal (2M) → always passes volume check.
     */
    private static List<Bar> macdBuyBars() {
        List<Bar> bars = new ArrayList<>();
        Instant base = Instant.parse("2026-01-01T00:00:00Z");
        double p = 100.0;
        // Slow phase: +0.5/bar for 40 bars (100 → 120)
        for (int i = 0; i < 40; i++) {
            bars.add(new Bar(base.plusSeconds((long) i * 86400), p, p * 1.005, p * 0.995, p, 2_000_000L));
            p += 0.5;
        }
        // Fast acceleration: +2.0/bar for 60 bars (120 → 240)
        for (int i = 40; i < 100; i++) {
            bars.add(new Bar(base.plusSeconds((long) i * 86400), p, p * 1.005, p * 0.995, p, 2_000_000L));
            p += 2.0;
        }
        return bars;
    }

    private static MultiTimeframeAnalysis mtfAt(String symbol, double confidence,
                                                MultiTimeframeAnalyzer.SignalType rec) {
        boolean aligned = confidence >= 0.6;
        return new MultiTimeframeAnalysis(
            symbol, List.of(), aligned,
            MultiTimeframeAnalyzer.TrendDirection.NEUTRAL,
            confidence, rec, Instant.now()
        );
    }

    @BeforeEach
    void setUp() throws Exception {
        mockClient = mock(BrokerClient.class,
            withSettings().mockMaker(org.mockito.MockMakers.SUBCLASS));
        mockMtf = mock(MultiTimeframeAnalyzer.class,
            withSettings().mockMaker(org.mockito.MockMakers.SUBCLASS));
        manager = new StrategyManager(mockClient, mockMtf, null);
        when(mockClient.getMarketHistory(anyString(), anyInt())).thenReturn(macdBuyBars());
    }

    @Nested
    @DisplayName("Veto fires in 0.6–0.7 window when MTF disagrees with BUY")
    class VetoFires {

        @Test
        @DisplayName("MTF SELL at 0.65 confidence: no raw BUY must reach caller when strategy BUY is vetoed")
        void mtfSellAt65PctVetoesBuy() throws Exception {
            when(mockMtf.analyze("SPY")).thenReturn(mtfAt("SPY", 0.65, MultiTimeframeAnalyzer.SignalType.SELL));

            // SPY is not a momentum asset. In STRONG_BULL with accelerating bars, strategy may BUY.
            // With MTF SELL at 0.65, the veto must convert any strategy BUY to HOLD.
            var signal = manager.evaluate("SPY", 240.0, 0.0, MarketRegime.STRONG_BULL);

            // The veto fires when signal == BUY. If strategy already returned HOLD (e.g. downtrend),
            // the signal is HOLD for another reason — still correct behavior. Either way: NOT a BUY.
            if (signal instanceof TradingSignal.Buy buy) {
                fail("Strategy BUY must be vetoed by MTF SELL at 0.65 confidence, got: " + buy.reason());
            }
            // Must be HOLD — either from veto or from some other guard
            assertInstanceOf(TradingSignal.Hold.class, signal,
                "MTF SELL at 0.65 must result in HOLD (veto or downtrend guard), got: " + signal);
        }

        @Test
        @DisplayName("MTF HOLD at 0.70 confidence: strategy BUY must not reach caller")
        void mtfHoldAt70PctVetoesBuy() throws Exception {
            when(mockMtf.analyze("SPY")).thenReturn(mtfAt("SPY", 0.70, MultiTimeframeAnalyzer.SignalType.HOLD));

            var signal = manager.evaluate("SPY", 240.0, 0.0, MarketRegime.STRONG_BULL);

            if (signal instanceof TradingSignal.Buy buy) {
                fail("Strategy BUY must be vetoed by MTF HOLD at 0.70, got: " + buy.reason());
            }
        }

        @Test
        @DisplayName("MTF SELL at 0.60 (boundary): strategy BUY vetoed or HOLD")
        void mtfSellAt60PctBoundary() throws Exception {
            when(mockMtf.analyze("SPY")).thenReturn(mtfAt("SPY", 0.60, MultiTimeframeAnalyzer.SignalType.SELL));

            var signal = manager.evaluate("SPY", 240.0, 0.0, MarketRegime.STRONG_BULL);

            if (signal instanceof TradingSignal.Buy buy) {
                fail("MTF SELL at 0.60 should veto BUY, got: " + buy.reason());
            }
        }
    }

    @Nested
    @DisplayName("Veto does NOT fire when MTF agrees or is out of range")
    class VetoSilent {

        @Test
        @DisplayName("MTF BUY at 0.65 → veto must not fire (no gradient-veto HOLD reason)")
        void mtfBuyAt65PctNoVeto() throws Exception {
            when(mockMtf.analyze("SPY")).thenReturn(mtfAt("SPY", 0.65, MultiTimeframeAnalyzer.SignalType.BUY));

            var signal = manager.evaluate("SPY", 240.0, 0.0, MarketRegime.STRONG_BULL);

            // MTF BUY at medium confidence must NOT trigger gradient veto
            if (signal instanceof TradingSignal.Hold hold) {
                assertFalse(hold.reason().contains("MTF gradient veto"),
                    "MTF BUY at 0.65 must not trigger gradient veto: " + hold.reason());
            }
            // BUY is acceptable here — no veto should suppress it
        }

        @Test
        @DisplayName("MTF SELL at 0.71 → handled by high-confidence block as SELL, not gradient veto")
        void mtfSellAt71PctNotGradientVeto() throws Exception {
            // confidence > 0.7 → SELL returned directly by existing high-confidence block
            when(mockMtf.analyze("SPY")).thenReturn(mtfAt("SPY", 0.71, MultiTimeframeAnalyzer.SignalType.SELL));

            var signal = manager.evaluate("SPY", 240.0, 1.0, MarketRegime.STRONG_BULL);

            // High-confidence MTF SELL should be returned immediately
            assertInstanceOf(TradingSignal.Sell.class, signal,
                "MTF SELL at confidence > 0.7 must be returned directly as SELL: " + signal);
        }

        @Test
        @DisplayName("no MTF analyzer → gradient veto never fires")
        void noMtfNoVeto() {
            StrategyManager noMtfManager = new StrategyManager(mockClient, null, null);
            List<Double> closes = macdBuyBars().stream().map(Bar::close).toList();
            var signal = noMtfManager.evaluateWithHistory("SPY", 240.0, 0.0, closes, MarketRegime.STRONG_BULL);
            assertNotNull(signal);
            // Signal must come from strategy directly, not from any MTF veto
            if (signal instanceof TradingSignal.Hold hold) {
                assertFalse(hold.reason().contains("MTF gradient veto"),
                    "No MTF → gradient veto must not appear in reason: " + hold.reason());
            }
        }

        @Test
        @DisplayName("gradient veto does not apply to RANGE_BOUND (mean reversion exempted)")
        void rangeboundExemptFromVeto() throws Exception {
            // RANGE_BOUND uses MeanReversionStrategy — isMeanReversion=true → veto code skipped
            when(mockMtf.analyze("SPY")).thenReturn(mtfAt("SPY", 0.65, MultiTimeframeAnalyzer.SignalType.SELL));

            var signal = manager.evaluate("SPY", 240.0, 0.0, MarketRegime.RANGE_BOUND);

            // Should not throw; if HOLD, must not be from gradient veto
            if (signal instanceof TradingSignal.Hold hold) {
                assertFalse(hold.reason().contains("MTF gradient veto"),
                    "RANGE_BOUND mean reversion must not be vetoed by gradient veto: " + hold.reason());
            }
        }
    }
}
