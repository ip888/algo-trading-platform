package com.trading.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trading.persistence.TradeDatabase;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DurableSellReconciliationTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void ambiguousTimeoutNeverSubmitsSecondSellAcrossRestart(@TempDir Path dir) throws Exception {
        String dbPath = dir.resolve("sell.db").toString();
        BrokerClient broker = mock(BrokerClient.class, withSettings().mockMaker(org.mockito.MockMakers.SUBCLASS));
        doThrow(new IllegalStateException("response lost after acceptance"))
            .when(broker).placeOrderTracked(eq("SPY"), eq(2.0), eq("sell"),
                eq("market"), eq("day"), isNull(), anyString());

        try (var db = new TradeDatabase(dbPath)) {
            var client = new ResilientBrokerClient(broker, new SimpleMeterRegistry());
            client.enableDurableSellSafety(db, "alpaca");
            assertThrows(RuntimeException.class,
                () -> client.placeOrder("SPY", 2.0, "sell", "market", "day", null));
            assertNotNull(db.getBrokerExitIntent("alpaca", "SPY"));
        }

        // Restart. Broker may have accepted the original POST despite the timeout.
        try (var db = new TradeDatabase(dbPath)) {
            var client = new ResilientBrokerClient(broker, new SimpleMeterRegistry());
            client.enableDurableSellSafety(db, "alpaca");
            when(broker.getOrderByClientOrderId(anyString())).thenReturn(JSON.readTree("""
                {"status":"new","filled_qty":"0"}
                """));
            assertThrows(IllegalStateException.class,
                () -> client.placeOrder("SPY", 2.0, "sell", "market", "day", null));

            when(broker.getOrderByClientOrderId(anyString())).thenReturn(JSON.readTree("""
                {"status":"filled","filled_qty":"2","filled_avg_price":"490.00"}
                """));
            assertDoesNotThrow(() ->
                client.placeOrder("SPY", 2.0, "sell", "market", "day", null));
            assertNull(db.getBrokerExitIntent("alpaca", "SPY"));
        }
        verify(broker, times(1)).placeOrderTracked(eq("SPY"), eq(2.0), eq("sell"),
            eq("market"), eq("day"), isNull(), anyString());
    }

    @Test
    void partiallyFilledSellDoesNotReleaseOrderLock(@TempDir Path dir) throws Exception {
        try (var db = new TradeDatabase(dir.resolve("partial.db").toString())) {
            BrokerClient broker = mock(BrokerClient.class, withSettings().mockMaker(org.mockito.MockMakers.SUBCLASS));
            when(broker.getOrderByClientOrderId(anyString())).thenReturn(JSON.readTree("""
                {"status":"partially_filled","filled_qty":"1"}
                """));
            var client = new ResilientBrokerClient(broker, new SimpleMeterRegistry());
            client.enableDurableSellSafety(db, "alpaca");
            assertThrows(IllegalStateException.class,
                () -> client.placeOrderDirect("SPY", 2, "sell", "market", "day", null));
            assertThrows(IllegalStateException.class,
                () -> client.placeOrderDirect("SPY", 2, "sell", "market", "day", null));
            assertNotNull(db.getBrokerExitIntent("alpaca", "SPY"));
            verify(broker, times(1)).placeOrderTracked(eq("SPY"), eq(2.0), eq("sell"),
                eq("market"), eq("day"), isNull(), anyString());
        }
    }
}
