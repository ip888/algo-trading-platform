package com.trading.portfolio;

import com.trading.analysis.MarketRegimeDetector.MarketRegime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;

import static org.mockito.Mockito.*;

/**
 * 2026-11-10: scalp's RSI/VWAP/volume entry is a momentum-continuation signal, confirmed across
 * its entire live history to be a clear net-negative specifically in RANGE_BOUND regime (21.4% WR
 * / -$4.82 over 14 trades spanning Aug/Oct/Nov) while solidly positive in WEAK_BULL/STRONG_BULL.
 * These tests cover the scalp-priority scan's regime guard (the correlationCap/strategy-firing
 * guard inside StrategyManager's own evaluate() isn't exercised here -- it needs a real ScalpStrategy
 * to fire a signal off real bar data, which is covered indirectly by backtest validation instead).
 */
@DisplayName("ProfileManager.runScalpPriorityScan — RANGE_BOUND exclusion")
class ProfileManagerScalpRangeBoundTest extends ProfileManagerTestBase {

    private Method scanMethod;

    @BeforeEach
    void setUp() throws Exception {
        setUpCommon();
        when(mockConfig.getScalpSymbols()).thenReturn(List.of("SPY", "QQQ"));
        when(mockConfig.getMaxPositionsAtOnce()).thenReturn(4); // mockConfig defaults int getters to 0
        scanMethod = ProfileManager.class.getDeclaredMethod("runScalpPriorityScan",
            Set.class, double.class, double.class, MarketRegime.class, double.class, String.class);
        scanMethod.setAccessible(true);
    }

    private void invokeScan(MarketRegime regime) throws Exception {
        scanMethod.invoke(profileManager, Set.of(), 10_000.0, 10_000.0, regime, 15.0, "[MAIN]");
    }

    @Test
    @DisplayName("flag enabled: RANGE_BOUND never even attempts a bar lookup")
    void rangeBoundDisabled_skipsEntirely() throws Exception {
        when(mockConfig.isScalpRangeBoundDisabled()).thenReturn(true);
        invokeScan(MarketRegime.RANGE_BOUND);
        verify(mockClient, never()).getLatestBar(anyString());
    }

    @Test
    @DisplayName("flag enabled: WEAK_BULL is unaffected, scan still runs")
    void rangeBoundDisabled_doesNotAffectOtherRegimes() throws Exception {
        when(mockConfig.isScalpRangeBoundDisabled()).thenReturn(true);
        invokeScan(MarketRegime.WEAK_BULL);
        verify(mockClient, atLeastOnce()).getLatestBar(anyString());
    }

    @Test
    @DisplayName("flag disabled (default): RANGE_BOUND still scans as before")
    void flagOff_rangeBoundStillScans() throws Exception {
        when(mockConfig.isScalpRangeBoundDisabled()).thenReturn(false);
        invokeScan(MarketRegime.RANGE_BOUND);
        verify(mockClient, atLeastOnce()).getLatestBar(anyString());
    }

    @Test
    @DisplayName("pre-existing STRONG_BEAR exclusion is untouched by this change")
    void strongBearStillExcluded() throws Exception {
        when(mockConfig.isScalpRangeBoundDisabled()).thenReturn(true);
        invokeScan(MarketRegime.STRONG_BEAR);
        verify(mockClient, never()).getLatestBar(anyString());
    }
}
