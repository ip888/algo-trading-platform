package com.trading.core.api;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AlpacaClient {
    private static final Logger logger = LoggerFactory.getLogger(AlpacaClient.class);
    private final HttpClient httpClient;
    private final String baseUrl;
    private final String dataUrl;
    private final String keyId;
    private final String secretKey;
    private final ObjectMapper objectMapper;

    public AlpacaClient() {
        this.httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_2) // Use HTTP/2 for performance
            .build();
        
        this.keyId = System.getenv("APCA_API_KEY_ID");
        this.secretKey = System.getenv("APCA_API_SECRET_KEY");
        
        // Auto-detect environment if not explicitly set
        String envBaseUrl = System.getenv("APCA_API_BASE_URL");
        if (envBaseUrl != null && !envBaseUrl.isBlank()) {
            this.baseUrl = envBaseUrl;
            logger.info("🌍 Using explicit API Base URL: {}", this.baseUrl);
        } else if (this.keyId != null && this.keyId.startsWith("AK")) {
            this.baseUrl = "https://api.alpaca.markets";
            logger.info("🌍 Detected LIVE Key (AK prefix) - Defaulting to LIVE API: {}", this.baseUrl);
        } else {
            this.baseUrl = "https://paper-api.alpaca.markets";
            logger.info("🌍 Using Default/Paper API Base URL: {}", this.baseUrl);
        }

        this.dataUrl = System.getenv().getOrDefault("APCA_API_DATA_URL", "https://data.alpaca.markets/v2");
        this.objectMapper = new ObjectMapper();

        if (this.keyId == null || this.secretKey == null) {
            logger.error("🛑 Alpaca API Keys are MISSING! Requests will fail. Set APCA_API_KEY_ID and APCA_API_SECRET_KEY.");
        }
    }

    private void checkKeys() {
        if (this.keyId == null || this.secretKey == null) {
            throw new RuntimeException("Alpaca API keys not configured. Check environment variables.");
        }
    }

    public java.util.concurrent.CompletableFuture<List<com.trading.core.model.Bar>> getMarketHistoryAsync(String symbol, int limit) {
        checkKeys();
        // Calculate start date: today - (limit * 2) days to account for weekends/holidays
        String start = java.time.ZonedDateTime.now(java.time.ZoneId.of("UTC"))
            .minusDays(limit * 2L + 10) // Buffer
            .format(java.time.format.DateTimeFormatter.ISO_INSTANT);
            
        String url = dataUrl + "/stocks/" + symbol + "/bars?timeframe=1D&limit=" + limit + "&start=" + start;
        
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("APCA-API-KEY-ID", keyId)
            .header("APCA-API-SECRET-KEY", secretKey)
            .GET()
            .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenApply(response -> {
                if (response.statusCode() != 200) {
                    throw new RuntimeException("Failed to fetch history for " + symbol + ": " + response.body());
                }
                try {
                    var bars = new java.util.ArrayList<com.trading.core.model.Bar>();
                    var json = objectMapper.readTree(response.body());
                    
                    if (json.has("bars") && json.get("bars").isArray()) {
                        for (var node : json.get("bars")) {
                            bars.add(new com.trading.core.model.Bar(
                                java.time.Instant.parse(node.get("t").asText()),
                                node.get("o").asDouble(),
                                node.get("h").asDouble(),
                                node.get("l").asDouble(),
                                node.get("c").asDouble(),
                                node.get("v").asLong()
                            ));
                        }
                    }
                    logger.info("📊 Parsed {} bars for symbol: {}", bars.size(), symbol);
                    return bars;
                } catch (Exception e) {
                    logger.error("❌ Failed to parse Alpaca response: {}", response.body());
                    throw new RuntimeException("Error parsing history", e);
                }
            });
    }

    public CompletableFuture<com.trading.core.model.Bar> getLatestBarAsync(String symbol) {
        checkKeys();
        String url = dataUrl + "/stocks/" + symbol + "/bars/latest";
        
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("APCA-API-KEY-ID", keyId)
            .header("APCA-API-SECRET-KEY", secretKey)
            .GET()
            .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenApply(response -> {
                if (response.statusCode() != 200) {
                    throw new RuntimeException("Failed to fetch bar for " + symbol + ": " + response.body());
                }
                try {
                    var json = objectMapper.readTree(response.body());
                    var barNode = json.get("bar");
                    return new com.trading.core.model.Bar(
                        java.time.Instant.parse(barNode.get("t").asText()),
                        barNode.get("o").asDouble(),
                        barNode.get("h").asDouble(),
                        barNode.get("l").asDouble(),
                        barNode.get("c").asDouble(),
                        barNode.get("v").asLong()
                    );
                } catch (Exception e) {
                    throw new RuntimeException("Error parsing bar", e);
                }
            });
    }

    public CompletableFuture<String> postOrderAsync(String symbol, int qty, String side, String type, String timeInForce) {
        // "Tech Excellence": Post order via JSON
        String url = baseUrl + "/v2/orders";
        
        try {
            var orderNode = objectMapper.createObjectNode();
            orderNode.put("symbol", symbol);
            orderNode.put("qty", qty);
            orderNode.put("side", side.toLowerCase());
            orderNode.put("type", type.toLowerCase());
            orderNode.put("time_in_force", timeInForce.toLowerCase());

            String body = objectMapper.writeValueAsString(orderNode);
            
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("APCA-API-KEY-ID", keyId)
                .header("APCA-API-SECRET-KEY", secretKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

            return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() != 200 && response.statusCode() != 201) {
                        logger.error("Order Failed: {}", response.body());
                        return "ERROR: " + response.body();
                    }
                    return response.body(); // Returns Order JSON
                });
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    public CompletableFuture<String> getAccountAsync() {
        checkKeys();
        String url = baseUrl + "/v2/account";
        logger.info("📡 Requesting account from: {}", url);
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("APCA-API-KEY-ID", keyId)
            .header("APCA-API-SECRET-KEY", secretKey)
            .GET()
            .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenApply(response -> {
                if (response.statusCode() != 200) {
                    logger.error("Account Fetch Failed: {}", response.body());
                    return "{}";
                }
                return response.body();
            });
    }

    public CompletableFuture<String> getPositionsAsync() {
        checkKeys();
        String url = baseUrl + "/v2/positions";
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("APCA-API-KEY-ID", keyId)
            .header("APCA-API-SECRET-KEY", secretKey)
            .GET()
            .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenApply(response -> {
                if (response.statusCode() != 200) {
                    logger.error("Positions Fetch Failed: {}", response.body());
                    return "[]";
                }
                return response.body();
            });
    }

    /**
     * Get market clock (open/close times).
     */
    public CompletableFuture<String> getClockAsync() {
        checkKeys();
        String url = baseUrl + "/v2/clock";
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("APCA-API-KEY-ID", keyId)
            .header("APCA-API-SECRET-KEY", secretKey)
            .GET()
            .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenApply(response -> {
                if (response.statusCode() != 200) {
                    logger.error("Clock Fetch Failed: {}", response.body());
                    return "{}";
                }
                return response.body();
            });
    }

    /**
     * Cancel all open orders (emergency use).
     */
    public CompletableFuture<String> cancelAllOrdersAsync() {
        checkKeys();
        String url = baseUrl + "/v2/orders";
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("APCA-API-KEY-ID", keyId)
            .header("APCA-API-SECRET-KEY", secretKey)
            .DELETE()
            .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenApply(response -> {
                logger.info("Cancel All Orders Response: {} - {}", response.statusCode(), response.body());
                return response.body();
            });
    }

    /**
     * Close all positions (emergency use).
     */
    public CompletableFuture<String> closeAllPositionsAsync() {
        checkKeys();
        String url = baseUrl + "/v2/positions?cancel_orders=true";
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("APCA-API-KEY-ID", keyId)
            .header("APCA-API-SECRET-KEY", secretKey)
            .DELETE()
            .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenApply(response -> {
                logger.info("Close All Positions Response: {} - {}", response.statusCode(), response.body());
                return response.body();
            });
    }
}
