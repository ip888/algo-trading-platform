package com.trading.portfolio;

import com.trading.api.model.Position;
import com.trading.persistence.TradeDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Fix 7 — partial-exit mask preserved on restart:
 * reconcilePortfolioWithBroker() previously hardcoded partialExitsExecuted=0 when
 * restoring positions from the DB, causing already-executed partial exits to
 * re-fire on the next cycle. The mask must be restored from the DB record.
 */
@DisplayName("ProfileManager — partial-exit mask restored from DB on restart")
class ProfileManagerPartialExitRestoreTest extends ProfileManagerTestBase {

    @BeforeEach
    void setUp() throws Exception {
        setUpCommon();
    }

    private void invokeReconcile() throws Exception {
        Method m = ProfileManager.class.getDeclaredMethod(
            "reconcilePortfolioWithBroker", String.class);
        m.setAccessible(true);
        m.invoke(profileManager, "[MAIN]");
    }

    @Test
    @DisplayName("partialExitsExecuted is restored from DB record, not reset to 0")
    void partialMaskRestoredFromDb() throws Exception {
        double entry = 500.0;
        double sl = 492.0;
        double tp = 515.0;
        int savedMask = 3; // bits 0+1 set: first two partial exits already executed

        // Broker holds the position
        var brokerPos = new Position("SPY", 1.0, 505.0, entry, 5.0);
        when(mockClient.getPositions()).thenReturn(List.of(brokerPos));

        // DB has an open record with partialExitsExecuted = 3
        var dbRecord = new TradeDatabase.OpenTradeRecord(
            "SPY", entry, 1.0, sl, tp, Instant.now().minusSeconds(3600), savedMask);
        when(mockDatabase.getOpenTradeRecords("alpaca")).thenReturn(List.of(dbRecord));
        when(mockDatabase.hasOpenTrade("SPY", "alpaca")).thenReturn(true);
        // getDelegate() returns null by default; the try/catch in reconcile handles it gracefully.

        invokeReconcile();

        var restored = portfolio.getPosition("SPY");
        assertTrue(restored.isPresent(), "SPY must be in portfolio after reconcile");
        assertEquals(savedMask, restored.get().partialExitsExecuted(),
            "partialExitsExecuted must be restored to " + savedMask + " from DB, not reset to 0");
    }

    @Test
    @DisplayName("partialExitsExecuted=0 from DB is preserved (not accidentally overwritten)")
    void zeroMaskPreserved() throws Exception {
        var brokerPos = new Position("QQQ", 1.0, 400.0, 395.0, 5.0);
        when(mockClient.getPositions()).thenReturn(List.of(brokerPos));

        var dbRecord = new TradeDatabase.OpenTradeRecord(
            "QQQ", 395.0, 1.0, 389.0, 407.0, Instant.now().minusSeconds(1800), 0);
        when(mockDatabase.getOpenTradeRecords("alpaca")).thenReturn(List.of(dbRecord));
        when(mockDatabase.hasOpenTrade("QQQ", "alpaca")).thenReturn(true);
        // getDelegate() returns null by default; the try/catch in reconcile handles it gracefully.

        invokeReconcile();

        var restored = portfolio.getPosition("QQQ");
        assertTrue(restored.isPresent());
        assertEquals(0, restored.get().partialExitsExecuted(),
            "partialExitsExecuted=0 must remain 0 after reconcile");
    }

    @Test
    @DisplayName("full bitmask (7 = all three partial exits done) is preserved")
    void fullMaskPreserved() throws Exception {
        var brokerPos = new Position("AAPL", 0.5, 155.0, 150.0, 2.5);
        when(mockClient.getPositions()).thenReturn(List.of(brokerPos));

        var dbRecord = new TradeDatabase.OpenTradeRecord(
            "AAPL", 150.0, 0.5, 147.75, 154.5, Instant.now().minusSeconds(7200), 7);
        when(mockDatabase.getOpenTradeRecords("alpaca")).thenReturn(List.of(dbRecord));
        when(mockDatabase.hasOpenTrade("AAPL", "alpaca")).thenReturn(true);
        // getDelegate() returns null by default; the try/catch in reconcile handles it gracefully.

        invokeReconcile();

        var restored = portfolio.getPosition("AAPL");
        assertTrue(restored.isPresent());
        assertEquals(7, restored.get().partialExitsExecuted(),
            "Full bitmask (7) must be preserved — no partial exits should re-trigger");
    }
}
