package com.trading.strategy;

import com.trading.analysis.MarketRegimeDetector.MarketRegime;
import com.trading.analysis.MultiTimeframeAnalyzer;
import com.trading.analysis.MultiTimeframeAnalyzer.MultiTimeframeAnalysis;
import com.trading.api.BrokerClient;
import com.trading.api.model.Bar;
import com.trading.config.Config;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** WEAK_BULL_MACD_FALLBACK_DISABLED: no MACD-fallback ENTRIES; a held position still consults MACD. */
@DisplayName("StrategyManager — WEAK_BULL MACD fallback kill switch")
class StrategyManagerMacdFallbackDisabledTest {

    private static List<Bar> uptrend() {
        var bars = new ArrayList<Bar>();
        Instant base = Instant.parse("2026-01-01T00:00:00Z");
        for (int i = 0; i < 100; i++) {
            double c = 100.0 + i * 0.5;
            bars.add(new Bar(base.plusSeconds(i * 86400L), c, c * 1.01, c * 0.99, c, 1_000_000L));
        }
        return bars;
    }

    private StrategyManager manager(boolean disabled) throws Exception {
        var client = mock(BrokerClient.class, withSettings().mockMaker(org.mockito.MockMakers.SUBCLASS));
        var mtf = mock(MultiTimeframeAnalyzer.class, withSettings().mockMaker(org.mockito.MockMakers.SUBCLASS));
        var cfg = mock(Config.class, withSettings().mockMaker(org.mockito.MockMakers.SUBCLASS)
            .defaultAnswer(inv -> {
                Class<?> rt = inv.getMethod().getReturnType();
                if (rt == boolean.class) return false;
                if (rt == int.class) return 0;
                if (rt == long.class) return 0L;
                if (rt == double.class) return 0.0;
                if (rt == java.util.Set.class) return java.util.Set.of();
                if (rt == List.class) return List.of();
                return null;
            }));
        when(cfg.isWeakBullMacdFallbackDisabled()).thenReturn(disabled);
        when(client.getMarketHistory(anyString(), anyInt())).thenReturn(uptrend());
        // aligned, confidence <=0.7 => MTF does not short-circuit, regime routing (WEAK_BULL case) runs
        when(mtf.analyze("SPY")).thenReturn(new MultiTimeframeAnalysis("SPY", List.of(), true,
            MultiTimeframeAnalyzer.TrendDirection.WEAK_UP, 0.65,
            MultiTimeframeAnalyzer.SignalType.BUY, Instant.now()));
        return new StrategyManager(client, mtf, cfg);
    }

    @Test
    @DisplayName("flag on + no position: MACD fallback is never consulted for entry")
    void disabled_noPosition_skipsMacdEntry() throws Exception {
        var m = manager(true);
        m.evaluate("SPY", 149.5, 0.0, MarketRegime.WEAK_BULL);
        assertFalse(m.getActiveStrategy().startsWith("MACD Trend (Weak Bull"),
            "activeStrategy was " + m.getActiveStrategy());
    }

    @Test
    @DisplayName("flag off (default): MACD fallback still runs")
    void enabled_noPosition_consultsMacd() throws Exception {
        var m = manager(false);
        var sig = m.evaluate("SPY", 149.5, 0.0, MarketRegime.WEAK_BULL);
        assertTrue(m.getActiveStrategy().startsWith("MACD Trend (Weak Bull")
                || m.getActiveStrategy().startsWith("MTF Trend Entry"),
            "activeStrategy was " + m.getActiveStrategy() + " signal=" + sig);
    }

    @Test
    @DisplayName("flag on + held position: routing is identical to flag off (SELL path untouched)")
    void disabled_withPosition_sameAsEnabled() throws Exception {
        var on = manager(true);
        var off = manager(false);
        var sigOn = on.evaluate("SPY", 149.5, 1.0, MarketRegime.WEAK_BULL);
        var sigOff = off.evaluate("SPY", 149.5, 1.0, MarketRegime.WEAK_BULL);
        assertEquals(off.getActiveStrategy(), on.getActiveStrategy());
        assertEquals(sigOff.getClass(), sigOn.getClass());
    }
}
