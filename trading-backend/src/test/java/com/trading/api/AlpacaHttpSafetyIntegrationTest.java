package com.trading.api;

import com.sun.net.httpserver.HttpServer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trading.config.Config;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AlpacaHttpSafetyIntegrationTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private HttpServer server;
    private AlpacaClient client;
    private AtomicReference<String> method = new AtomicReference<>();
    private AtomicReference<String> body = new AtomicReference<>();
    private volatile int httpStatus = 200;
    private volatile String httpBody = "{}";

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            method.set(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = httpBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(httpStatus, response.length);
            try (var out = exchange.getResponseBody()) { out.write(response); }
        });
        server.start();
        Config config = mock(Config.class, withSettings().mockMaker(org.mockito.MockMakers.SUBCLASS));
        when(config.baseUrl()).thenReturn("http://127.0.0.1:" + server.getAddress().getPort());
        when(config.apiKey()).thenReturn("test-only");
        when(config.apiSecret()).thenReturn("test-only");
        when(config.getApiRequestDelayMs()).thenReturn(0L);
        client = new AlpacaClient(config);
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
    }

    @Test
    void trackedBracketPostsStableClientOrderIdWithBrokerProtection() throws Exception {
        httpBody = "{\"id\":\"broker-1\",\"status\":\"accepted\"}";
        var result = client.placeBracketOrderTracked("SPY", 2, "buy", 515, 490, null, null,
            "atp_deterministic_123");
        assertTrue(result.success());
        assertEquals("POST /v2/orders", method.get());
        var payload = mapper.readTree(body.get());
        assertEquals("atp_deterministic_123", payload.path("client_order_id").asText());
        assertEquals("bracket", payload.path("order_class").asText());
        assertEquals("490.00", payload.path("stop_loss").path("stop_price").asText());
    }

    @Test
    void trackedSellPostsSameIdAndOrderLookupReturnsBrokerStatus() {
        assertDoesNotThrow(() -> client.placeOrderTracked(
            "SPY", 2, "sell", "market", "day", null, "atp_sell_test_123"));
        assertEquals("POST /v2/orders", method.get());
        assertTrue(body.get().contains("\"client_order_id\":\"atp_sell_test_123\""));
        httpBody = "{\"client_order_id\":\"atp_sell_test_123\",\"status\":\"filled\",\"filled_qty\":\"2\"}";
        var order = client.getOrderByClientOrderId("atp_sell_test_123");
        assertEquals("filled", order.path("status").asText());
        assertEquals("GET /v2/orders:by_client_order_id", method.get());
    }

    @Test
    void patchReplacementUsesPatchVerb() {
        client.replaceOrder("abc123", 2.0, null, 490.0);
        assertEquals("PATCH /v2/orders/abc123", method.get());
        assertTrue(body.get().contains("\"stop_price\":\"490.00\""));
    }

    @Test
    void openOrderServerErrorIsNotAnEmptyBook() {
        httpStatus = 500;
        httpBody = "{\"message\":\"temporary outage\"}";
        assertThrows(IllegalStateException.class, () -> client.getOpenOrders("SPY"));
    }

    @Test
    void malformedBrokerPositionsFailClosed() {
        httpBody = "{\"unexpected\":\"object\"}";
        assertThrows(IllegalStateException.class, () -> client.getPositions());
    }

    @Test
    void position404MeansFlatBut500MeansUnknown() {
        httpStatus = 404;
        assertTrue(client.getPosition("SPY").isEmpty());
        httpStatus = 500;
        assertThrows(IllegalStateException.class, () -> client.getPosition("SPY"));
    }
}
