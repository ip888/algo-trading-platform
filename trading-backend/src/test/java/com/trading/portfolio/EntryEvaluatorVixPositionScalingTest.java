package com.trading.portfolio;

import com.trading.analysis.MarketRegimeDetector.MarketRegime;
import com.trading.risk.TradePosition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 2026-11-05: VIX-aware position-count scaling (catalog item) -- the cap is reduced, never
 * raised, once VIX reaches a configured high threshold. Covers the live wiring in
 * EntryEvaluator; Config.getEffectiveMaxPositions itself is covered directly in ConfigTest.
 */
@DisplayName("EntryEvaluator — VIX-aware position-count scaling")
class EntryEvaluatorVixPositionScalingTest extends ProfileManagerTestBase {

    private EntryEvaluator entryEvaluator;

    @BeforeEach
    void setUp() throws Exception {
        setUpCommon();
        entryEvaluator = (EntryEvaluator) getField("entryEvaluator");
        var riskGate = (RiskGate) getField("riskGate");
        riskGate.setLastEntryEpochMs(0L); // disarm the entry-stagger gate

        when(mockConfig.getMaxPositionsAtOnce()).thenReturn(4);
        when(mockConfig.getEffectiveMaxPositions(anyDouble()))
            .thenAnswer(inv -> mockConfig.getEffectiveMaxPositions(4, inv.getArgument(0)));
        when(mockConfig.getEffectiveMaxPositions(anyInt(), anyDouble())).thenCallRealMethod();
        when(mockConfig.isVixPositionScalingEnabled()).thenReturn(true);
        when(mockConfig.getVixPositionScalingHighThreshold()).thenReturn(25.0);
        when(mockConfig.getVixPositionScalingHighVixMaxPositions()).thenReturn(2);
    }

    private void openPositions(int count) {
        String[] symbols = {"QQQ", "AAPL", "NVDA", "META", "MSFT", "AMD"};
        for (int i = 0; i < count; i++) {
            portfolio.setPosition(symbols[i], Optional.of(
                new TradePosition(symbols[i], 100.0, 1.0, 99.0, 102.0, Instant.now())));
        }
    }

    private EntryEvaluator.Result evaluate(double vix) {
        return entryEvaluator.evaluate("SPY", 100.0, 10_000.0, vix, MarketRegime.WEAK_BULL, null, "[MAIN]");
    }

    @Test
    @DisplayName("below the high-VIX threshold: cap stays at the normal 4, 3 open positions is not blocked")
    void belowThreshold_normalCapApplies() {
        openPositions(3);
        var result = evaluate(15.0);
        assertFalse(result instanceof EntryEvaluator.Blocked,
            "3 open < normal cap of 4 should not block on position limit");
    }

    @Test
    @DisplayName("at/above the high-VIX threshold: cap reduces to 2, so 2 open positions now blocks")
    void aboveThreshold_capReducedToTwo() {
        openPositions(2);
        var result = evaluate(25.0); // at the threshold
        assertInstanceOf(EntryEvaluator.Blocked.class, result);
        assertEquals("max positions reached", ((EntryEvaluator.Blocked) result).reason());
    }

    @Test
    @DisplayName("at/above the high-VIX threshold: 1 open position is still under the reduced cap of 2")
    void aboveThreshold_oneOpenStillUnderReducedCap() {
        openPositions(1);
        var result = evaluate(30.0);
        assertFalse(result instanceof EntryEvaluator.Blocked,
            "1 open < reduced cap of 2 should not block on position limit");
    }

    @Test
    @DisplayName("3 open positions blocks under the normal cap regardless of VIX (scaling never needed here)")
    void normalCapAloneBlocksAtCapacity() {
        openPositions(4);
        var result = evaluate(15.0);
        assertInstanceOf(EntryEvaluator.Blocked.class, result);
        assertEquals("max positions reached", ((EntryEvaluator.Blocked) result).reason());
    }

    @Test
    @DisplayName("scaling disabled: high VIX does not reduce the cap")
    void scalingDisabled_highVixDoesNotReduceCap() {
        when(mockConfig.isVixPositionScalingEnabled()).thenReturn(false);
        openPositions(3);
        var result = evaluate(30.0);
        assertFalse(result instanceof EntryEvaluator.Blocked,
            "scaling disabled -- 3 open < normal cap of 4 should not block even at high VIX");
    }
}
