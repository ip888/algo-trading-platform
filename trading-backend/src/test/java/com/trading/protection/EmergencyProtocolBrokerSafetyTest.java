package com.trading.protection;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trading.api.BrokerClient;
import com.trading.api.ResilientBrokerClient;
import com.trading.api.model.Position;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EmergencyProtocolBrokerSafetyTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    private static BrokerClient fakeBroker() {
        return mock(BrokerClient.class,
            withSettings().mockMaker(org.mockito.MockMakers.SUBCLASS));
    }

    @Test
    void pendingEntryThatMayHaveFilledBlocksPanicSell() throws Exception {
        BrokerClient broker = fakeBroker();
        when(broker.getOpenOrders(null)).thenReturn(JSON.readTree("""
            [{"id":"entry-123","symbol":"SPY","side":"buy","status":"new","qty":"1"}]
            """));
        when(broker.getOrderById("entry-123")).thenReturn(JSON.readTree("""
            {"id":"entry-123","status":"pending_cancel","filled_qty":"0"}
            """));
        var protocol = new EmergencyProtocol(
            new ResilientBrokerClient(broker, new SimpleMeterRegistry()));
        var report = protocol.trigger("test pending entry fill race");
        assertEquals(false, report.get("success"));
        assertEquals(false, report.get("alpacaOrdersCancelled"));
        verify(broker).cancelAllOrders();
        verify(broker, never()).placeOrder(anyString(), anyDouble(), anyString(),
            anyString(), anyString(), any());
    }

    @Test
    void filledProtectiveStopDuringGlobalCancelBlocksDoubleSell() throws Exception {
        BrokerClient broker = fakeBroker();
        when(broker.getOpenOrders(null)).thenReturn(JSON.readTree("""
            [{"id":"stop-456","symbol":"SPY","side":"sell","status":"new","qty":"2"}]
            """));
        when(broker.getOrderById("stop-456")).thenReturn(JSON.readTree("""
            {"id":"stop-456","status":"filled","filled_qty":"2"}
            """));
        var protocol = new EmergencyProtocol(
            new ResilientBrokerClient(broker, new SimpleMeterRegistry()));
        var report = protocol.trigger("stop filled during cancellation");
        assertEquals(false, report.get("success"));
        verify(broker, never()).placeOrder(anyString(), anyDouble(), anyString(),
            anyString(), anyString(), any());
    }

    @Test
    void emptyBrokerOrderBookDoesNotForceFalseFlatten(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)
        throws Exception {
        BrokerClient broker = fakeBroker();
        when(broker.getOpenOrders(null)).thenReturn(JSON.readTree("[]"));
        when(broker.getPositions()).thenReturn(java.util.List.of());
        var protocol = new EmergencyProtocol(
            new ResilientBrokerClient(broker, new SimpleMeterRegistry()));
        var report = protocol.trigger("empty book");
        assertEquals(true, report.get("success"));
        verify(broker).cancelAllOrders();
        verify(broker, never()).placeOrder(anyString(), anyDouble(), anyString(),
            anyString(), anyString(), any());
    }
}
