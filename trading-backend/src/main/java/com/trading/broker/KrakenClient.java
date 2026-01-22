package com.trading.broker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.trading.config.Config;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;

/**
 * KRAKEN API CLIENT
 * 
 * Crypto trading via Kraken exchange.
 * Supports: BTC/USD, ETH/USD, SOL/USD, and 100+ other crypto pairs.
 * 
 * Features:
 * - HMAC-SHA512 authentication
 * - Spot trading (buy/sell crypto)
 * - Real-time balance checking
 * - Order management
 * 
 * Fees: Maker 0.16%, Taker 0.26%
 */
public class KrakenClient {
    private static final Logger logger = LoggerFactory.getLogger(KrakenClient.class);
    
    private static final String API_BASE_URL = "https://api.kraken.com";
    private static final String API_VERSION = "/0";
    
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String apiKey;
    private final String apiSecret;
    
    // Balance cache to avoid rate limiting issues (30 second TTL - increased for stability)
    private volatile double cachedBalance = 0.0;
    private volatile long balanceCacheTime = 0;
    private static final long BALANCE_CACHE_TTL_MS = 30_000;  // 30 seconds (was 10)
    
    // Nonce tracking - CRITICAL for Kraken API (must be strictly increasing)
    // Using microseconds and tracking last nonce to prevent "Invalid nonce" errors
    private static volatile long lastNonce = 0;
    private static final Object NONCE_LOCK = new Object();
    
    // Rate limiting - Kraken allows ~15 calls/minute for private endpoints
    private static volatile long lastPrivateCallTime = 0;
    private static final long MIN_CALL_INTERVAL_MS = 2000;  // Minimum 2 seconds between calls (more conservative)
    private static volatile int callCountThisMinute = 0;
    private static volatile long minuteStartTime = 0;
    private static final int MAX_CALLS_PER_MINUTE = 8;  // Very conservative limit (Kraken allows 15)
    
    // Backoff for rate limit errors - EXPONENTIAL BACKOFF
    private static volatile long rateLimitBackoffUntil = 0;
    private static volatile long currentBackoffSeconds = 120;  // Start at 2 minutes
    private static final long MAX_BACKOFF_SECONDS = 600;  // Max 10 minutes
    private static volatile long lastSuccessfulCall = System.currentTimeMillis();
    
    public KrakenClient() {
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
        this.objectMapper = new ObjectMapper();
        
        // Load from environment first, then fallback to config.properties
        String envKey = System.getenv("KRAKEN_API_KEY");
        String envSecret = System.getenv("KRAKEN_API_SECRET");
        
        if (envKey != null && envSecret != null) {
            this.apiKey = envKey;
            this.apiSecret = envSecret;
            logger.info("🦑 Kraken Client initialized from environment (API Key: {}...)", apiKey.substring(0, Math.min(8, apiKey.length())));
        } else {
            // Fallback: Load from config.properties
            var configCredentials = loadFromConfig();
            this.apiKey = configCredentials[0];
            this.apiSecret = configCredentials[1];
            
            if (apiKey == null || apiSecret == null) {
                logger.warn("⚠️ Kraken API keys not configured. Set KRAKEN_API_KEY and KRAKEN_API_SECRET.");
            } else {
                logger.info("🦑 Kraken Client initialized from config.properties (API Key: {}...)", apiKey.substring(0, Math.min(8, apiKey.length())));
            }
        }
    }
    
    /**
     * Load Kraken credentials from config.properties file.
     */
    private String[] loadFromConfig() {
        String key = null;
        String secret = null;
        
        try {
            var configFile = java.nio.file.Path.of("config.properties");
            if (java.nio.file.Files.exists(configFile)) {
                var props = new java.util.Properties();
                try (var reader = java.nio.file.Files.newBufferedReader(configFile)) {
                    props.load(reader);
                }
                key = props.getProperty("KRAKEN_API_KEY");
                secret = props.getProperty("KRAKEN_API_SECRET");
                
                if (key != null && secret != null) {
                    logger.debug("Loaded Kraken credentials from config.properties");
                }
            }
        } catch (Exception e) {
            logger.warn("Failed to load Kraken credentials from config.properties: {}", e.getMessage());
        }
        
        return new String[] { key, secret };
    }
    
    // ==================== RATE LIMITING ====================
    // Kraken API limits: 15-20 private calls per minute
    // Source: https://docs.kraken.com/api/docs/guides/global-intro/
    
    private final java.util.concurrent.Semaphore rateLimitPermits = new java.util.concurrent.Semaphore(15);
    private final java.util.concurrent.ScheduledExecutorService rateLimitScheduler = 
        java.util.concurrent.Executors.newScheduledThreadPool(1);
    
    /**
     * Execute API call with rate limiting
     */
    private <T> CompletableFuture<T> withRateLimit(java.util.function.Supplier<CompletableFuture<T>> apiCall) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                rateLimitPermits.acquire();
                logger.debug("Rate limit permit acquired ({} remaining)", rateLimitPermits.availablePermits());
                return apiCall.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return CompletableFuture.<T>failedFuture(e);
            }
        }).thenCompose(f -> f)
          .whenComplete((result, error) -> {
              // Release permit after 4 seconds (15 calls/min = 1 call per 4 seconds)
              rateLimitScheduler.schedule(() -> {
                  rateLimitPermits.release();
                  logger.debug("Rate limit permit released ({} available)", rateLimitPermits.availablePermits());
              }, 4, java.util.concurrent.TimeUnit.SECONDS);
          });
    }
    
    /**
     * Enhanced error handling for Kraken API errors
     * Source: https://support.kraken.com/articles/360001491786
     * 
     * CRITICAL: Also triggers EXPONENTIAL rate limit backoff when appropriate
     */
    private String handleKrakenError(String errorCode) {
        // Trigger EXPONENTIAL backoff for rate limit errors
        if (errorCode.contains("Rate limit")) {
            // Double the backoff each time, capped at MAX_BACKOFF_SECONDS
            currentBackoffSeconds = Math.min(currentBackoffSeconds * 2, MAX_BACKOFF_SECONDS);
            rateLimitBackoffUntil = System.currentTimeMillis() + (currentBackoffSeconds * 1000);
            logger.warn("🛑 Rate limit detected! Setting {}-second exponential backoff", currentBackoffSeconds);
        }
        
        return switch (errorCode) {
            case "EOrder:Insufficient funds" -> 
                "Insufficient funds. Check: 1) Open orders locking funds, 2) Deposit holds, 3) Minimum order size";
            case "EAPI:Rate limit exceeded" -> 
                "Rate limit exceeded. Backing off for " + currentBackoffSeconds + " seconds...";
            case "EOrder:Order minimum not met" -> 
                "Order below minimum size. Check pair-specific minimums.";
            case "EGeneral:Temporary lockout" -> 
                "Temporary lockout. Your account may have a hold or restriction.";
            case "EAPI:Invalid nonce" -> 
                "Invalid nonce. Clock sync issue detected.";
            case "EOrder:Unknown order" -> 
                "Order not found. May have been filled or cancelled.";
            case "EService:Unavailable" -> 
                "Kraken service temporarily unavailable. Retrying...";
            default -> "Kraken error: " + errorCode;
        };
    }
    
    /**
     * Get available balance for trading
     * For SPOT trading, we need the actual USD balance, not margin.
     * Uses Balance endpoint to get real cash, supplemented by TradeBalance for equity info.
     * 
     * Source: https://docs.kraken.com/api/docs/rest-api/get-account-balance
     * 
     * Includes caching to avoid rate limiting issues when called multiple times
     */
    public CompletableFuture<Double> getAvailableBalanceAsync() {
        // Return cached balance if still valid
        long now = System.currentTimeMillis();
        if (cachedBalance > 0 && (now - balanceCacheTime) < BALANCE_CACHE_TTL_MS) {
            logger.debug("Using cached balance: ${} (age: {}ms)", 
                String.format("%.2f", cachedBalance), (now - balanceCacheTime));
            return CompletableFuture.completedFuture(cachedBalance);
        }
        
        // First get the actual USD cash balance from Balance endpoint
        return getBalanceAsync()
            .thenCombine(getTradeBalanceAsync(), (balanceJson, tradeBalanceJson) -> {
                try {
                    // Parse actual cash balance (ZUSD)
                    JsonNode balanceResult = objectMapper.readTree(balanceJson).get("result");
                    double zusd = 0.0;
                    if (balanceResult != null) {
                        zusd = balanceResult.has("ZUSD") ? balanceResult.get("ZUSD").asDouble() : 0.0;
                        // Also check for USD.HOLD (funds held in open orders)
                        double usdHold = balanceResult.has("USD.HOLD") ? balanceResult.get("USD.HOLD").asDouble() : 0.0;
                        logger.info("💵 Kraken Cash Balance: ZUSD=${}, USD.HOLD=${}", 
                            String.format("%.2f", zusd), String.format("%.2f", usdHold));
                    }
                    
                    // Parse trade balance for equity info
                    JsonNode tradeResult = objectMapper.readTree(tradeBalanceJson).get("result");
                    if (tradeResult != null) {
                        double eb = tradeResult.has("eb") ? tradeResult.get("eb").asDouble() : 0.0;
                        double tb = tradeResult.has("tb") ? tradeResult.get("tb").asDouble() : 0.0;
                        double mf = tradeResult.has("mf") ? tradeResult.get("mf").asDouble() : 0.0;
                        double m = tradeResult.has("m") ? tradeResult.get("m").asDouble() : 0.0;
                        
                        logger.info("📊 Kraken TradeBalance: eb=${}, tb=${}, mf=${}, margin=${}", 
                            String.format("%.2f", eb), String.format("%.2f", tb), 
                            String.format("%.2f", mf), String.format("%.2f", m));
                    }
                    
                    // For SPOT trading, the actual ZUSD balance is what we can spend
                    // The TradeBalance 'mf' (free margin) is for margin trading
                    double availableBalance = zusd;
                    
                    logger.info("Kraken available balance for SPOT orders: ${}", String.format("%.2f", availableBalance));
                    
                    // Update cache
                    cachedBalance = availableBalance;
                    balanceCacheTime = System.currentTimeMillis();
                    
                    return availableBalance;
                } catch (Exception e) {
                    logger.error("Failed to parse balance", e);
                    return cachedBalance > 0 ? cachedBalance : 0.0;
                }
            });
    }
    
    // ==================== ORDER LIMITS & VALIDATION ====================
    
    /**
     * Kraken minimum order sizes by pair
     * Source: https://support.kraken.com/articles/205893708-minimum-order-size-volume-for-trading
     */
    private static final java.util.Map<String, OrderLimits> PAIR_LIMITS = java.util.Map.of(
        "XXBTZUSD", new OrderLimits(0.0001, 10.0),   // BTC: 0.0001 BTC, $10 min
        "XETHZUSD", new OrderLimits(0.01, 10.0),     // ETH: 0.01 ETH, $10 min
        "SOLUSD", new OrderLimits(0.5, 10.0),        // SOL: 0.5 SOL, $10 min
        "DOGEUSD", new OrderLimits(500.0, 10.0),     // DOGE: 500 DOGE, $10 min
        "XRPUSD", new OrderLimits(10.0, 10.0),       // XRP: 10 XRP, $10 min
        "ADAUSD", new OrderLimits(10.0, 10.0)        // ADA: 10 ADA, $10 min
    );
    
    /**
     * Validate if order meets Kraken minimums
     */
    private boolean validateOrderLimits(String pair, double volume, double price) {
        OrderLimits limits = PAIR_LIMITS.get(pair);
        if (limits == null) {
            logger.warn("⚠️ No limits defined for pair: {}, using defaults", pair);
            limits = new OrderLimits(0.0001, 10.0);
        }
        
        if (!limits.isValid(volume, price)) {
            String error = limits.getValidationError(volume, price);
            logger.error("❌ Order validation failed for {}: {}", pair, error);
            return false;
        }
        
        return true;
    }
    
    /**
     * Check available balance before placing order
     */
    public CompletableFuture<Boolean> canPlaceOrder(String pair, double volume, double price) {
        double requiredCost = volume * price;
        
        return getAvailableBalanceAsync()
            .thenCombine(getOpenOrdersAsync(), (availableBalance, openOrdersJson) -> {
                try {
                    // Parse open orders
                    JsonNode openOrders = objectMapper.readTree(openOrdersJson);
                    
                    // Check for errors in response
                    if (openOrders.has("error") && openOrders.get("error").size() > 0) {
                        String errorCode = openOrders.get("error").get(0).asText();
                        logger.error("Kraken API error: {}", handleKrakenError(errorCode));
                        return false;
                    }
                    
                    int openOrderCount = openOrders.get("result").get("open").size();
                    
                    logger.info("💰 Kraken Balance Check:");
                    logger.info("   Available Balance: ${}", String.format("%.2f", availableBalance));
                    logger.info("   Required: ${}", String.format("%.2f", requiredCost));
                    logger.info("   Open Orders: {}", openOrderCount);
                    
                    // Validate minimums
                    if (!validateOrderLimits(pair, volume, price)) {
                        return false;
                    }
                    
                    // Check sufficient funds
                    if (requiredCost > availableBalance) {
                        logger.error("❌ Insufficient funds: ${} required, ${} available", 
                            String.format("%.2f", requiredCost), String.format("%.2f", availableBalance));
                        
                        if (openOrderCount > 0) {
                            logger.warn("⚠️ {} open orders may be locking funds", openOrderCount);
                        }
                        
                        return false;
                    }
                    
                    logger.info("✅ Order validation passed");
                    return true;
                    
                } catch (Exception e) {
                    logger.error("Failed to validate order", e);
                    return false;
                }
            });
    }
    
    /**
     * Calculate maximum order size based on available balance
     */
    public CompletableFuture<Double> calculateMaxOrderSize(String pair, double price) {
        return getTradeBalanceAsync()
            .thenApply(json -> {
                try {
                    JsonNode tradeBal = objectMapper.readTree(json);
                    double freeMargin = tradeBal.get("result").get("mf").asDouble();
                    
                    // Use 90% of available balance (safety margin)
                    double maxCost = freeMargin * 0.9;
                    double maxVolume = maxCost / price;
                    
                    OrderLimits limits = PAIR_LIMITS.getOrDefault(pair, new OrderLimits(0.0001, 10.0));
                    
                    if (maxVolume < limits.minVolume()) {
                        logger.warn("⚠️ Insufficient funds for minimum order on {}: max {} < min {}", 
                            pair, maxVolume, limits.minVolume());
                        return 0.0;
                    }
                    
                    logger.info("📊 Max order size for {}: {} (${} available)", pair, maxVolume, freeMargin);
                    return maxVolume;
                    
                } catch (Exception e) {
                    logger.error("Failed to calculate max order size", e);
                    return 0.0;
                }
            });
    }
    
    /**
     * Cancel all open orders (to free locked funds)
     */
    public CompletableFuture<String> cancelAllOpenOrdersAsync() {
        return getOpenOrdersAsync()
            .thenCompose(json -> {
                try {
                    JsonNode openOrders = objectMapper.readTree(json);
                    JsonNode orders = openOrders.get("result").get("open");
                    
                    if (orders.size() == 0) {
                        logger.info("✅ No open orders to cancel");
                        return CompletableFuture.completedFuture("No orders");
                    }
                    
                    logger.info("🗑️ Canceling {} open orders...", orders.size());
                    
                    // Cancel all orders
                    var cancelFutures = new java.util.ArrayList<CompletableFuture<String>>();
                    orders.fieldNames().forEachRemaining(txid -> {
                        String postData = "txid=" + txid;
                        cancelFutures.add(privateRequest("/private/CancelOrder", postData));
                    });
                    
                    return CompletableFuture.allOf(cancelFutures.toArray(new CompletableFuture[0]))
                        .thenApply(v -> "Canceled " + orders.size() + " orders");
                        
                } catch (Exception e) {
                    logger.error("Failed to cancel orders", e);
                    return CompletableFuture.completedFuture("ERROR: " + e.getMessage());
                }
            });
    }
    
    // ==================== PUBLIC ENDPOINTS ====================
    
    /**
     * Get server time (test connectivity)
     */
    public CompletableFuture<String> getServerTimeAsync() {
        return publicRequest("/public/Time");
    }
    
    /**
     * Get ticker information for a trading pair
     * @param pair e.g., "XXBTZUSD" (BTC/USD) or "XETHZUSD" (ETH/USD)
     */
    public CompletableFuture<String> getTickerAsync(String pair) {
        return publicRequest("/public/Ticker?pair=" + pair);
    }
    
    /**
     * Get all tradable asset pairs
     */
    public CompletableFuture<String> getAssetPairsAsync() {
        return publicRequest("/public/AssetPairs");
    }
    
    // ==================== PRIVATE ENDPOINTS ====================
    
    /**
     * Get Trade Balance (includes Free Margin 'mf').
     */
    public CompletableFuture<String> getTradeBalanceAsync() {
        return privateRequest("/private/TradeBalance", "");
    }

    /**
     * Get account balance
     */
    public CompletableFuture<String> getBalanceAsync() {
        return privateRequest("/private/Balance", "");
    }
    
    /**
     * Get open positions
     */
    public CompletableFuture<String> getOpenPositionsAsync() {
        return privateRequest("/private/OpenPositions", "docalcs=true");
    }

    /**
     * Get open orders
     */
    public CompletableFuture<String> getOpenOrdersAsync() {
        return privateRequest("/private/OpenOrders", "");
    }
    
    /**
     * Get closed orders
     */
    public CompletableFuture<String> getClosedOrdersAsync() {
        return privateRequest("/private/ClosedOrders", "");
    }
    
    /**
     * Get trade history (for fetching actual entry prices)
     * Returns up to 50 most recent trades
     */
    public CompletableFuture<String> getTradesHistoryAsync() {
        return privateRequest("/private/TradesHistory", "");
    }
    
    /**
     * Get WebSocket authentication token
     * Required for authenticated WebSocket connections (placing orders, getting balance via WS)
     * Token is valid for 15 minutes but doesn't expire once a connection is established
     * @return WebSocket auth token string, or null on error
     */
    public String getWebSocketToken() {
        try {
            String response = privateRequest("/private/GetWebSocketsToken", "").join();
            JsonNode json = objectMapper.readTree(response);
            
            if (json.has("error") && json.get("error").size() > 0) {
                logger.error("Failed to get WebSocket token: {}", json.get("error"));
                return null;
            }
            
            String token = json.path("result").path("token").asText(null);
            if (token != null) {
                logger.info("🔑 Got WebSocket auth token (expires in {} seconds)", 
                    json.path("result").path("expires").asInt(900));
            }
            return token;
        } catch (Exception e) {
            logger.error("Failed to get WebSocket token: {}", e.getMessage());
            return null;
        }
    }
    
    /**
     * Place a market order
     * @param pair Trading pair (e.g., "XXBTZUSD")
     * @param type "buy" or "sell"
     * @param volume Amount to trade
     */
    public CompletableFuture<String> placeMarketOrderAsync(String pair, String type, double volume) {
        String postData = "pair=" + pair + "&type=" + type + "&ordertype=market&volume=" + volume;
        
        logger.info("🦑 Placing Kraken {} order: {} {} @ market", type.toUpperCase(), volume, pair);
        
        return privateRequest("/private/AddOrder", postData)
            .thenApply(response -> {
                try {
                    JsonNode json = objectMapper.readTree(response);
                    if (json.has("error") && json.get("error").size() > 0) {
                        logger.error("Kraken Order Error: {}", json.get("error"));
                        return "ERROR: " + json.get("error").toString();
                    }
                    logger.info("✅ Kraken Order Placed: {}", json.get("result"));
                    return response;
                } catch (Exception e) {
                    logger.error("Failed to parse Kraken response", e);
                    return "ERROR: " + e.getMessage();
                }
            });
    }
    
    /**
     * Place a limit order
     * @param pair Trading pair
     * @param type "buy" or "sell"
     * @param volume Amount
     * @param price Limit price
     */
    public CompletableFuture<String> placeLimitOrderAsync(String pair, String type, double volume, double price) {
        // Default to string format to avoid scientific notation
        return placeLimitOrderAsync(pair, type, String.format("%.8f", volume), String.format("%.8f", price));
    }

    /**
     * Place a limit order (String overload for precise precision)
     */
    public CompletableFuture<String> placeLimitOrderAsync(String pair, String type, String volume, String price) {
        String postData = "pair=" + pair + "&type=" + type + "&ordertype=limit&volume=" + volume + "&price=" + price;
        
        logger.info("🦑 Placing Kraken {} limit order: {} {} @ ${}", type.toUpperCase(), volume, pair, price);
        
        return privateRequest("/private/AddOrder", postData)
            .thenApply(response -> {
                try {
                    JsonNode json = objectMapper.readTree(response);
                    if (json.has("error") && json.get("error").size() > 0) {
                        logger.error("Kraken Order Error: {}", json.get("error"));
                        return "ERROR: " + json.get("error").toString();
                    }
                    logger.info("✅ Kraken Limit Order Placed: {}", json.get("result"));
                    return response;
                } catch (Exception e) {
                    return "ERROR: " + e.getMessage();
                }
            });
    }
    
    /**
     * Cancel an order
     * @param txid Transaction ID of order to cancel
     */
    public CompletableFuture<String> cancelOrderAsync(String txid) {
        String postData = "txid=" + txid;
        logger.info("🦑 Cancelling Kraken Order: {}", txid);
        return privateRequest("/private/CancelOrder", postData);
    }
    
    // ==================== HELPER METHODS ====================
    
    private CompletableFuture<String> publicRequest(String endpoint) {
        String url = API_BASE_URL + API_VERSION + endpoint;
        
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(10))
            .GET()
            .build();
        
        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .orTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .thenApply(HttpResponse::body)
            .exceptionally(ex -> {
                logger.error("Kraken Public Request Failed: {}", ex.getMessage());
                return "{\"error\":[\"" + ex.getMessage() + "\"]}";
            });
    }
    
    private CompletableFuture<String> privateRequest(String endpoint, String postData) {
        if (apiKey == null || apiSecret == null) {
            return CompletableFuture.completedFuture("{\"error\":[\"Kraken API keys not configured\"]}");
        }
        
        // Rate limiting check
        if (!checkRateLimit()) {
            // If in backoff period, return error immediately instead of making the call
            long now = System.currentTimeMillis();
            if (now < rateLimitBackoffUntil) {
                long waitTime = (rateLimitBackoffUntil - now) / 1000;
                logger.debug("⏳ Skipping Kraken call - rate limit backoff: {}s remaining", waitTime);
                return CompletableFuture.completedFuture(
                    "{\"error\":[\"EAPI:Rate limit exceeded\"],\"backoff\":" + waitTime + "}");
            }
            
            // Otherwise just delay (hitting per-minute or per-call limits)
            logger.warn("⚠️ Rate limit protection: delaying Kraken API call");
            try {
                Thread.sleep(MIN_CALL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        
        try {
            // Generate strictly increasing nonce using microseconds
            // This fixes "Invalid nonce" errors common in cloud environments
            long nonce = generateNonce();
            String nonceData = "nonce=" + nonce;
            String fullPostData = nonceData + (postData.isEmpty() ? "" : "&" + postData);
            
            // Generate signature
            String signature = generateSignature(endpoint, nonce, fullPostData);
            
            String url = API_BASE_URL + API_VERSION + endpoint;
            
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(15))  // Increased timeout for reliability
                .header("API-Key", apiKey)
                .header("API-Sign", signature)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(fullPostData))
                .build();
            
            return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .orTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .thenApply(response -> {
                    recordApiCall();
                    String body = response.body();
                    
                    // Check if response is successful (no errors)
                    // Reset exponential backoff on success
                    try {
                        var json = objectMapper.readTree(body);
                        if (!json.has("error") || json.get("error").size() == 0) {
                            // Success! Reset backoff
                            if (currentBackoffSeconds > 120) {
                                logger.info("✅ Kraken API call successful! Resetting backoff to 2 minutes");
                                currentBackoffSeconds = 120;
                            }
                            lastSuccessfulCall = System.currentTimeMillis();
                        }
                    } catch (Exception e) {
                        // Ignore parsing errors here
                    }
                    
                    return body;
                })
                .exceptionally(ex -> {
                    logger.error("Kraken Private Request Failed: {}", ex.getMessage());
                    return "{\"error\":[\"" + ex.getMessage() + "\"]}";
                });
                
        } catch (Exception e) {
            logger.error("Kraken Request Error: {}", e.getMessage());
            return CompletableFuture.completedFuture("{\"error\":[\"" + e.getMessage() + "\"]}");
        }
    }
    
    /**
     * Generate strictly increasing nonce using microseconds.
     * CRITICAL: Kraken requires nonces to be strictly increasing per API key.
     * 
     * INDUSTRY FIX: Use nanoseconds from epoch to ensure nonce is always
     * higher than any previously used nonce, even after instance restarts.
     * This handles Cloud Run scaling, restarts, and clock drift.
     */
    private long generateNonce() {
        synchronized (NONCE_LOCK) {
            // Use nanoseconds divided by 1000 to get microseconds from epoch
            // This gives us ~584 years before overflow (2554 AD)
            // Format: epochMicroseconds (much higher resolution than milliseconds)
            long nonce = System.currentTimeMillis() * 1000L + (System.nanoTime() % 1_000_000L) / 1000L;
            
            // Add a significant offset to ensure we're always higher than previous sessions
            // This handles the case where a new instance starts with lower timestamp
            nonce += 1_000_000_000_000L;  // Add 1 trillion to ensure higher than any previous
            
            // Ensure strictly increasing within this session
            if (nonce <= lastNonce) {
                nonce = lastNonce + 1;
            }
            
            lastNonce = nonce;
            logger.debug("🔑 Generated nonce: {}", nonce);
            return nonce;
        }
    }
    
    /**
     * Check if we should proceed with API call (rate limit protection)
     */
    private boolean checkRateLimit() {
        long now = System.currentTimeMillis();
        
        // Check if we're in backoff period from rate limit error
        if (now < rateLimitBackoffUntil) {
            long waitTime = (rateLimitBackoffUntil - now) / 1000;
            logger.warn("⏳ Rate limit backoff: {} seconds remaining", waitTime);
            return false;
        }
        
        // Reset counter each minute
        if (now - minuteStartTime > 60_000) {
            callCountThisMinute = 0;
            minuteStartTime = now;
        }
        
        // Check if we've exceeded rate limit
        if (callCountThisMinute >= MAX_CALLS_PER_MINUTE) {
            logger.warn("⚠️ Kraken rate limit reached ({}/min). Waiting...", MAX_CALLS_PER_MINUTE);
            return false;
        }
        
        // Check minimum interval between calls
        if (now - lastPrivateCallTime < MIN_CALL_INTERVAL_MS) {
            return false;
        }
        
        return true;
    }
    
    /**
     * Record API call for rate limiting
     */
    private void recordApiCall() {
        lastPrivateCallTime = System.currentTimeMillis();
        callCountThisMinute++;
    }
    
    /**
     * Set backoff period after rate limit error
     */
    public void setRateLimitBackoff(long seconds) {
        rateLimitBackoffUntil = System.currentTimeMillis() + (seconds * 1000);
        logger.warn("⏳ Rate limit backoff set for {} seconds", seconds);
    }
    
    /**
     * Generate Kraken API signature (HMAC-SHA512)
     */
    private String generateSignature(String endpoint, long nonce, String postData) throws Exception {
        // 1. SHA256 hash of nonce + postData
        MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
        byte[] hash = sha256.digest((nonce + postData).getBytes(StandardCharsets.UTF_8));
        
        // 2. Combine endpoint path + hash
        byte[] pathBytes = (API_VERSION + endpoint).getBytes(StandardCharsets.UTF_8);
        byte[] message = new byte[pathBytes.length + hash.length];
        System.arraycopy(pathBytes, 0, message, 0, pathBytes.length);
        System.arraycopy(hash, 0, message, pathBytes.length, hash.length);
        
        // 3. HMAC-SHA512 with decoded secret
        Mac hmac = Mac.getInstance("HmacSHA512");
        byte[] decodedSecret = Base64.getDecoder().decode(apiSecret);
        SecretKeySpec keySpec = new SecretKeySpec(decodedSecret, "HmacSHA512");
        hmac.init(keySpec);
        
        byte[] signature = hmac.doFinal(message);
        
        return Base64.getEncoder().encodeToString(signature);
    }
    
    // ==================== SYMBOL MAPPING ====================
    
    /**
     * Convert common symbol to Kraken format
     * BTC/USD -> XXBTZUSD
     * ETH/USD -> XETHZUSD
     */
    public static String toKrakenSymbol(String symbol) {
        return switch (symbol.toUpperCase()) {
            case "BTC/USD", "BTCUSD" -> "XXBTZUSD";
            case "ETH/USD", "ETHUSD" -> "XETHZUSD";
            case "SOL/USD", "SOLUSD" -> "SOLUSD";
            case "DOGE/USD", "DOGEUSD" -> "XDGUSD";
            case "XRP/USD", "XRPUSD" -> "XXRPZUSD";
            case "ADA/USD", "ADAUSD" -> "ADAUSD";
            case "DOT/USD", "DOTUSD" -> "DOTUSD";
            case "AVAX/USD", "AVAXUSD" -> "AVAXUSD";
            default -> symbol.replace("/", "");
        };
    }
    
    /**
     * Check if Kraken keys are configured
     */
    public boolean isConfigured() {
        return apiKey != null && apiSecret != null;
    }
}
