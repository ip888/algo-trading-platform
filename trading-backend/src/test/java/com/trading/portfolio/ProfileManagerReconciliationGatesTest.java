package com.trading.portfolio;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trading.api.BrokerClient;
import com.trading.persistence.TradeDatabase;
import com.trading.risk.TradePosition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * BUG FIX (2026-09-28): a native stop/TP fill discovered via reconciliation (the broker filled it
 * between polling cycles) used to close the DB row and nothing else — unlike every other exit path,
 * it never armed the price-improvement gate, the consecutive-loss counter, or the circuit breaker.
 * Confirmed live: an AAPL reconciliation stop-out let the bot re-buy AAPL 2h17m later just 0.18% below
 * the exit, which the 1% price-improvement gate would have blocked had it been armed.
 */
@DisplayName("ProfileManager — reconciliation-discovered exits arm the same post-loss gates as any other exit")
class ProfileManagerReconciliationGatesTest extends ProfileManagerTestBase {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        setUpCommon();
    }

    private void invokeReconcile() throws Exception {
        Method m = ProfileManager.class.getDeclaredMethod("reconcilePortfolioWithBroker", String.class);
        m.setAccessible(true);
        m.invoke(profileManager, "[MAIN]");
    }

    /** Broker no longer holds the symbol (stopped out); DB still has the OPEN row. */
    private void setUpOrphan(String symbol, double entryPrice, double qty) throws Exception {
        portfolio.setPosition(symbol, Optional.of(
            new TradePosition(symbol, entryPrice, qty, entryPrice * 0.99, entryPrice * 1.02, Instant.now())));
        when(mockClient.getPositions()).thenReturn(List.of()); // gone from the broker
        when(mockDatabase.hasOpenTrade(symbol, "alpaca")).thenReturn(true);
        when(mockDatabase.getOpenTradeRecords("alpaca")).thenReturn(List.of(
            new TradeDatabase.OpenTradeRecord(symbol, entryPrice, qty, entryPrice * 0.99, entryPrice * 1.02,
                Instant.now(), 0)));
    }

    private void stubOrderHistory(String symbol, double fillPrice) throws Exception {
        var rawClient = mock(BrokerClient.class, withSettings().mockMaker(org.mockito.MockMakers.SUBCLASS));
        when(mockClient.getDelegate()).thenReturn(rawClient);
        var orders = MAPPER.readTree(String.format(
            "[{\"symbol\":\"%s\",\"side\":\"sell\",\"status\":\"filled\",\"filled_avg_price\":\"%s\",\"filled_at\":\"%s\"}]",
            symbol, fillPrice, Instant.now()));
        when(rawClient.getOrderHistory(null, 50)).thenReturn(orders);
    }

    @Test
    @DisplayName("a reconciliation LOSS arms the price-improvement gate (lastExitPrices) and the loss counter")
    void reconciliationLoss_armsPriceImprovementGate() throws Exception {
        setUpOrphan("AAPL", 100.0, 1.0);
        stubOrderHistory("AAPL", 99.0); // sold below entry -> loss

        invokeReconcile();

        var riskGate = (RiskGate) getField("riskGate");
        assertEquals(99.0, riskGate.lastExitPrices().get("AAPL"), 1e-9,
            "without this, a re-entry within 1% of the stop-out price is never blocked");
        assertTrue(riskGate.consecutiveStopLosses().getOrDefault("AAPL", 0) >= 1);
        verify(mockDatabase).closeTrade(eq("AAPL"), any(), eq(99.0), anyDouble(), eq("alpaca"), eq("reconciliation"));
    }

    @Test
    @DisplayName("a reconciliation WIN clears any prior loss gate instead of leaving it armed")
    void reconciliationWin_clearsGate() throws Exception {
        setUpOrphan("AAPL", 100.0, 1.0);
        var riskGate = (RiskGate) getField("riskGate");
        riskGate.lastExitPrices().put("AAPL", 95.0); // stale gate from an earlier loss
        riskGate.consecutiveStopLosses().put("AAPL", 2);
        stubOrderHistory("AAPL", 101.0); // sold above entry -> win

        invokeReconcile();

        assertNull(riskGate.lastExitPrices().get("AAPL"));
        assertNull(riskGate.consecutiveStopLosses().get("AAPL"));
    }

    @Test
    @DisplayName("no open DB record for the symbol -> no gate call, no exception (fails safe)")
    void noOpenRecord_doesNothing() throws Exception {
        portfolio.setPosition("AAPL", Optional.of(new TradePosition("AAPL", 100.0, 1.0, 99.0, 102.0, Instant.now())));
        when(mockClient.getPositions()).thenReturn(List.of());
        when(mockDatabase.hasOpenTrade("AAPL", "alpaca")).thenReturn(true);
        when(mockDatabase.getOpenTradeRecords("alpaca")).thenReturn(List.of()); // record vanished/never indexed
        stubOrderHistory("AAPL", 99.0);

        assertDoesNotThrow(this::invokeReconcile);
        var riskGate = (RiskGate) getField("riskGate");
        assertNull(riskGate.lastExitPrices().get("AAPL"));
    }
}
