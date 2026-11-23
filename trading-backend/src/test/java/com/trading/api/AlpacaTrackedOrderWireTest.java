package com.trading.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.trading.config.Config;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Loopback-only, credential-free wire-contract tests for Alpaca order submission. */
class AlpacaTrackedOrderWireTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    private Config config(int port) {
        var cfg = mock(Config.class, withSettings().mockMaker(org.mockito.MockMakers.SUBCLASS));
        when(cfg.baseUrl()).thenReturn("http://127.0.0.1:" + port);
        when(cfg.apiKey()).thenReturn("local-test-only");
        when(cfg.apiSecret()).thenReturn("local-test-only");
        when(cfg.getApiRequestDelayMs()).thenReturn(0L);
        when(cfg.isAdaptiveRateLimitEnabled()).thenReturn(false);
        return cfg;
    }

    @Test
    void trackedBracketSendsClientOrderIdAndServerSideStops() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var requests = new AtomicInteger();
        var requestBody = new AtomicReference<String>();
        server.createContext("/v2/orders", exchange -> {
            requests.incrementAndGet();
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            var response = "{\"id\":\"broker-order-1\",\"status\":\"new\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            try (var stream = exchange.getResponseBody()) { stream.write(response); }
        });
        server.start();
        try {
            var client = new AlpacaClient(config(server.getAddress().getPort()));
            var result = client.placeBracketOrderTracked("SPY", 2, "buy", 105, 95,
                null, null, "atp_wire_test_1");
            assertTrue(result.success());
            assertEquals(1, requests.get());
            var body = JSON.readTree(requestBody.get());
            assertEquals("atp_wire_test_1", body.path("client_order_id").asText());
            assertEquals("bracket", body.path("order_class").asText());
            assertEquals("95.00", body.path("stop_loss").path("stop_price").asText());
            assertEquals("105.00", body.path("take_profit").path("limit_price").asText());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void lostHttpResponseDoesNotRetryTrackedBracket() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var requests = new AtomicInteger();
        server.createContext("/v2/orders", exchange -> {
            requests.incrementAndGet();
            exchange.close(); // request accepted by fake server, response lost
        });
        server.start();
        try {
            var client = new AlpacaClient(config(server.getAddress().getPort()));
            var result = client.placeBracketOrderTracked("SPY", 1, "buy", 105, 95,
                null, null, "atp_wire_timeout_1");
            assertFalse(result.success());
            assertEquals(1, requests.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void exactOrderLookupUsesClientOrderId() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var query = new AtomicReference<String>();
        server.createContext("/v2/orders:by_client_order_id", exchange -> {
            query.set(exchange.getRequestURI().getQuery());
            var response = "{\"status\":\"filled\",\"filled_qty\":\"1\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            try (var stream = exchange.getResponseBody()) { stream.write(response); }
        });
        server.start();
        try {
            var client = new AlpacaClient(config(server.getAddress().getPort()));
            assertEquals("filled", client.getOrderByClientOrderId("atp_wire_lookup")
                .path("status").asText());
            assertEquals("client_order_id=atp_wire_lookup", query.get());
        } finally {
            server.stop(0);
        }
    }
}
