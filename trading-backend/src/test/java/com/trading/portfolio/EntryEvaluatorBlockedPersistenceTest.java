package com.trading.portfolio;

import com.trading.analysis.MarketRegimeDetector.MarketRegime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Only ~8 of EntryEvaluator's ~19 gates used to record their rejection (riskGate.blockedBuys +
 * blocked_entries table); the rest just returned Blocked, so the dashboard panel and post-session
 * analysis silently missed most of them. evaluate() now records every Blocked at its single exit,
 * de-duplicated so a standing block does not write a row per 10-20s cycle.
 */
@DisplayName("EntryEvaluator — every gate rejection is persisted (de-duplicated)")
class EntryEvaluatorBlockedPersistenceTest extends ProfileManagerTestBase {

    private EntryEvaluator entryEvaluator;
    private RiskGate riskGate;

    @BeforeEach
    void setUp() throws Exception {
        setUpCommon();
        entryEvaluator = (EntryEvaluator) getField("entryEvaluator");
        riskGate = (RiskGate) getField("riskGate");
        // Arm the entry-stagger gate — it returns Blocked WITHOUT calling blockBuy() itself.
        riskGate.setLastEntryEpochMs(System.currentTimeMillis());
    }

    private EntryEvaluator.Result evaluate(String symbol) {
        return entryEvaluator.evaluate(symbol, 100.0, 10_000.0, 15.0, MarketRegime.WEAK_BULL, null, "[MAIN]");
    }

    @Test
    @DisplayName("a gate that never called blockBuy() is now recorded in blocked_entries + blockedBuys")
    void unrecordedGate_isPersisted() {
        var result = evaluate("SPY");

        assertInstanceOf(EntryEvaluator.Blocked.class, result);
        verify(mockDatabase, times(1)).saveBlockedEntry(eq("SPY"), anyString(),
            startsWith("entry stagger"), eq(100.0), eq("WEAK_BULL"), eq(15.0));
        assertTrue(riskGate.blockedBuys().get("SPY").startsWith("entry stagger"));
    }

    @Test
    @DisplayName("the same standing block (countdown digits differ) is written once per window, per symbol")
    void standingBlock_isDeduplicated() {
        evaluate("SPY");
        evaluate("SPY");
        evaluate("SPY");
        verify(mockDatabase, times(1)).saveBlockedEntry(eq("SPY"), anyString(), anyString(),
            anyDouble(), anyString(), anyDouble());

        evaluate("QQQ"); // a different symbol is a different block
        verify(mockDatabase, times(1)).saveBlockedEntry(eq("QQQ"), anyString(), anyString(),
            anyDouble(), anyString(), anyDouble());
    }

    @Test
    @DisplayName("a passing evaluation records nothing")
    void pass_recordsNothing() {
        riskGate.setLastEntryEpochMs(0L); // stagger no longer applies
        evaluate("SPY");
        // (whatever it returns, no stagger block may be recorded)
        verify(mockDatabase, never()).saveBlockedEntry(anyString(), anyString(),
            startsWith("entry stagger"), anyDouble(), anyString(), anyDouble());
    }
}
