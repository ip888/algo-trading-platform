package com.trading.portfolio;

import com.trading.risk.TradePosition;
import com.trading.persistence.TradeDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 2026-09-22 fixes to the partial-exit path. The 2026-09-21 review found every partial exit left
 * the remaining shares with no native broker stop for 17–60+ min (cancelExistingOrders removed it,
 * nothing re-placed it) AND armed pendingExitOrders, which suspended the bot's own polling of the
 * symbol for ~20 min as well.
 */
@DisplayName("ProfileManager — partial exits re-arm the stop and keep monitoring")
class ProfileManagerPartialExitProtectionTest extends ProfileManagerTestBase {

    @BeforeEach
    void setUp() throws Exception {
        setUpCommon();
        when(mockConfig.isMaxLossExitEnabled()).thenReturn(true);
        when(mockConfig.isScaleOutEnabled()).thenReturn(false); // isolate the percentage-of-target partials
    }

    /** Position: entry 100, SL 99, TP 102. Price 100.6 → 30% of TP distance → L1 partial fires. */
    private void placeAtL1(double qty) {
        double entry = 100.0, price = 100.6;
        var pos = new TradePosition("SPY", entry, qty, 99.0, 102.0,
            Instant.now().minusSeconds(600), entry, 0);
        portfolio.setPosition("SPY", Optional.of(pos));
        when(mockClient.getPositions()).thenReturn(List.of(brokerPos("SPY", qty, entry, price)));
        when(mockDatabase.getOpenTradeRecords(anyString())).thenReturn(List.of(
            new TradeDatabase.OpenTradeRecord("SPY", entry, qty, 99.0, 102.0, Instant.now().minusSeconds(600), 0)));
    }

    @Test
    @DisplayName("L1 partial sells 33% and re-places a native stop for the REMAINING 67%")
    void partialExit_rearmsNativeStopForRemainder() throws Exception {
        placeAtL1(9.0);

        invokeRiskExits();

        verify(mockClient).placeOrderDirect(eq("SPY"), eq(2.97), eq("sell"), eq("market"), eq("day"), any());
        var qty = org.mockito.ArgumentCaptor.forClass(Double.class);
        var stop = org.mockito.ArgumentCaptor.forClass(Double.class);
        verify(mockClient).placeNativeStopOrder(eq("SPY"), qty.capture(), stop.capture());
        assertEquals(9.0 - 2.97, qty.getValue(), 1e-9, "stop must cover exactly the shares that remain");
        assertEquals(99.0, stop.getValue(), 1e-9, "tracked stop is preserved");
    }

    @Test
    @DisplayName("partial exit does NOT arm pendingExitOrders (position stays monitored)")
    void partialExit_doesNotArmPendingExit() throws Exception {
        placeAtL1(9.0);

        invokeRiskExits();

        var riskGate = (RiskGate) getField("riskGate");
        assertFalse(riskGate.pendingExitOrders().containsKey("alpaca:SPY"),
            "pending suspends every risk check on the symbol for ~20 min — wrong for a live remainder");
        assertTrue(portfolio.getPosition("SPY").orElseThrow().hasPartialExit(1));
    }

    @Test
    @DisplayName("partial proceeds are banked on the DB lot")
    void partialExit_recordsFillInDatabase() throws Exception {
        placeAtL1(9.0);

        invokeRiskExits();

        verify(mockDatabase).recordPartialExitFill(eq("SPY"), eq("alpaca"), eq(2.97), eq(100.6));
    }

    @Test
    @DisplayName("later partials take a fraction of the LIVE remainder, never more than is held")
    void laterPartial_isFractionOfLiveQuantity() throws Exception {
        // L1 already done (bit 1) and broker shows 6 left. Price 101.6 → 80% of target → L3 fires
        // (also L2 not yet marked, but L3 is evaluated first). 50% of remaining = 3.0, not 50% of the
        // ORIGINAL 9 (4.5, which would be rejected once fewer shares remain).
        double entry = 100.0, price = 101.6;
        var pos = new TradePosition("SPY", entry, 9.0, 99.0, 102.0,
            Instant.now().minusSeconds(600), entry, 1);
        portfolio.setPosition("SPY", Optional.of(pos));
        when(mockClient.getPositions()).thenReturn(List.of(brokerPos("SPY", 6.0, entry, price)));
        when(mockDatabase.getOpenTradeRecords(anyString())).thenReturn(List.of(
            new TradeDatabase.OpenTradeRecord("SPY", entry, 9.0, 99.0, 102.0, Instant.now().minusSeconds(600), 1)));

        invokeRiskExits();

        verify(mockClient).placeOrderDirect(eq("SPY"), eq(3.0), eq("sell"), eq("market"), eq("day"), any());
        verify(mockClient).placeNativeStopOrder(eq("SPY"), eq(3.0), anyDouble());
    }

    @Test
    @DisplayName("a failed stop placement is survivable — partial still recorded, no exception")
    void stopPlacementFailure_isNonFatal() throws Exception {
        placeAtL1(9.0);
        doThrow(new RuntimeException("boom")).when(mockClient)
            .placeNativeStopOrder(anyString(), anyDouble(), anyDouble());

        assertDoesNotThrow(this::invokeRiskExits);
        assertTrue(portfolio.getPosition("SPY").orElseThrow().hasPartialExit(1));
    }

    @Test
    @DisplayName("entry price is re-anchored to the broker's average fill once")
    void entry_reanchoredToBrokerFill() throws Exception {
        // Tracked entry = decision-time quote 100.00; broker actually filled at 100.20.
        double qty = 5.0;
        var pos = new TradePosition("SPY", 100.0, qty, 99.0, 102.0,
            Instant.now().minusSeconds(600), 100.0, 0);
        portfolio.setPosition("SPY", Optional.of(pos));
        when(mockClient.getPositions()).thenReturn(List.of(brokerPos("SPY", qty, 100.20, 100.25)));
        when(mockDatabase.getOpenTradeRecords(anyString())).thenReturn(List.of(
            new TradeDatabase.OpenTradeRecord("SPY", 100.0, qty, 99.0, 102.0, Instant.now().minusSeconds(600), 0)));

        invokeRiskExits();
        invokeRiskExits(); // second cycle must not re-anchor again

        assertEquals(100.20, portfolio.getPosition("SPY").orElseThrow().entryPrice(), 1e-9);
        assertEquals(99.0, portfolio.getPosition("SPY").orElseThrow().stopLoss(), 1e-9,
            "stop stays at the level already resting at the broker");
        verify(mockDatabase, times(1)).updateEntryPrice("SPY", "alpaca", 100.20);
    }
}
