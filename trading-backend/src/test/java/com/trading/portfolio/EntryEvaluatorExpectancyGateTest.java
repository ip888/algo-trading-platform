package com.trading.portfolio;

import com.trading.analysis.MarketRegimeDetector.MarketRegime;
import com.trading.persistence.TradeDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 2026-11-20: the pre-existing rolling-expectancy gate requires BOTH a low win rate AND a
 * negative average return -- structurally unable to catch a symbol that wins more than
 * WINRATE_GATE_MAX_WIN_RATE (35%) of the time but still loses money on average. Confirmed live
 * (Nov 19 WEAK_BULL regime breakdown): XLV 56% WR but net -$2.29/25 trades, XLE 50% WR but net
 * -$1.48/20 trades -- both invisible to the old gate. This covers the new pure-expectancy
 * criterion added alongside it.
 */
@DisplayName("EntryEvaluator — pure-expectancy gate criterion")
class EntryEvaluatorExpectancyGateTest extends ProfileManagerTestBase {

    private EntryEvaluator entryEvaluator;

    @BeforeEach
    void setUp() throws Exception {
        setUpCommon();
        entryEvaluator = (EntryEvaluator) getField("entryEvaluator");
        var riskGate = (RiskGate) getField("riskGate");
        riskGate.setLastEntryEpochMs(0L); // disarm the entry-stagger gate

        when(mockConfig.getMaxPositionsAtOnce()).thenReturn(4); // mockConfig defaults int getters to 0
        when(mockConfig.getEffectiveMaxPositions(anyDouble())).thenReturn(4); // a mock doesn't run the real VIX-scaling logic
        when(mockConfig.getWinRateGateMinTrades()).thenReturn(6);
        when(mockConfig.getWinRateGateMaxWinRate()).thenReturn(0.35);
        when(mockConfig.getWinRateGateWindowDays()).thenReturn(30);
    }

    private void stats(double winRate, int trades, double avgReturnPct) {
        when(mockDatabase.getRecentSymbolStats(eq("XLV"), eq("WEAK_BULL"), anyInt(), anyInt()))
            .thenReturn(new TradeDatabase.SymbolWindowStats(trades, winRate, avgReturnPct, avgReturnPct * trades));
    }

    private EntryEvaluator.Result evaluate() {
        return entryEvaluator.evaluate("XLV", 100.0, 10_000.0, 15.0, MarketRegime.WEAK_BULL, null, "[MAIN]");
    }

    @Test
    @DisplayName("disabled by default: a decent win rate with negative avg return is NOT blocked")
    void disabledByDefault_decentWinRateNotBlocked() {
        when(mockConfig.isExpectancyGateEnabled()).thenReturn(false);
        stats(0.56, 25, -0.0251); // XLV's actual Nov-19 numbers

        var result = evaluate();
        assertFalse(result instanceof EntryEvaluator.Blocked,
            "the old low-win-rate check alone must not catch a 56% win rate");
    }

    @Test
    @DisplayName("enabled: a decent win rate with negative avg return below the threshold IS blocked")
    void enabled_decentWinRateBelowThresholdIsBlocked() {
        when(mockConfig.isExpectancyGateEnabled()).thenReturn(true);
        when(mockConfig.getExpectancyGateMinAvgReturnPct()).thenReturn(-0.02);
        stats(0.56, 25, -0.0251); // below -0.02 threshold despite 56% WR

        var result = evaluate();
        assertInstanceOf(EntryEvaluator.Blocked.class, result);
        assertEquals("negative expectancy in WEAK_BULL", ((EntryEvaluator.Blocked) result).reason());
    }

    @Test
    @DisplayName("enabled: a decent win rate with avg return ABOVE the threshold is not blocked")
    void enabled_aboveThresholdNotBlocked() {
        when(mockConfig.isExpectancyGateEnabled()).thenReturn(true);
        when(mockConfig.getExpectancyGateMinAvgReturnPct()).thenReturn(-0.05);
        stats(0.56, 25, -0.0251); // -0.0251 is above (less negative than) -0.05

        var result = evaluate();
        assertFalse(result instanceof EntryEvaluator.Blocked);
    }

    @Test
    @DisplayName("enabled: the original low-win-rate-AND-negative-return path still blocks as before")
    void enabled_originalLowWinRatePathStillWorks() {
        when(mockConfig.isExpectancyGateEnabled()).thenReturn(true);
        when(mockConfig.getExpectancyGateMinAvgReturnPct()).thenReturn(-0.10); // stricter than this case
        stats(0.20, 10, -0.03); // 20% WR, negative return -- caught by the ORIGINAL check

        var result = evaluate();
        assertInstanceOf(EntryEvaluator.Blocked.class, result);
        assertEquals("low win rate in WEAK_BULL", ((EntryEvaluator.Blocked) result).reason());
    }

    @Test
    @DisplayName("too few trades in the window: neither criterion fires regardless of stats")
    void tooFewTrades_neverBlocks() {
        when(mockConfig.isExpectancyGateEnabled()).thenReturn(true);
        when(mockConfig.getExpectancyGateMinAvgReturnPct()).thenReturn(-0.01);
        stats(0.0, 3, -0.50); // terrible stats, but below WINRATE_GATE_MIN_TRADES(6)

        var result = evaluate();
        assertFalse(result instanceof EntryEvaluator.Blocked);
    }
}
