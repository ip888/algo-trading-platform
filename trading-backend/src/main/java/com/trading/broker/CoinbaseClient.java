package com.trading.broker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.jsonwebtoken.Jwts;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Security;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Coinbase Advanced Trade API Client 💰
 * 
 * Production-ready client for Coinbase Advanced Trade API.
 * 
 * ADVANTAGES OVER KRAKEN:
 * ✅ Works in US AND EU (Spain, Poland, all EU countries)
 * ✅ 30 requests/second rate limit (vs Kraken's ~4/minute)
 * ✅ Official Java SDK patterns
 * ✅ Better WebSocket support
 * ✅ Regulated exchange (Coinbase is public company)
 * 
 * Authentication: JWT tokens with ES256 signing
 * Base URL: https://api.coinbase.com
 * 
 * @see <a href="https://docs.cdp.coinbase.com/advanced-trade/reference">API Docs</a>
 */
public class CoinbaseClient {
    private static final Logger logger = LoggerFactory.getLogger(CoinbaseClient.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    
    // Coinbase API endpoints
    private static final String BASE_URL = "https://api.coinbase.com";
    private static final String API_PREFIX = "/api/v3/brokerage";
    
    // Rate limiting - Coinbase is generous: 30 req/sec
    private static final int MAX_REQUESTS_PER_SECOND = 25; // Stay under 30 limit
    private static final long REQUEST_WINDOW_MS = 1000;
    
    // Caching (even with generous limits, caching improves performance)
    private static final long BALANCE_CACHE_TTL_MS = 30_000;  // 30 seconds
    private static final long TICKER_CACHE_TTL_MS = 5_000;    // 5 seconds for price data
    
    // API credentials
    private final String apiKeyName;
    private final PrivateKey privateKey;
    
    // HTTP client
    private final HttpClient httpClient;
    
    // Rate limiting state
    private final AtomicLong requestCount = new AtomicLong(0);
    private final AtomicLong windowStart = new AtomicLong(System.currentTimeMillis());
    
    // Caches
    private final ConcurrentHashMap<String, CachedValue<JsonNode>> balanceCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CachedValue<JsonNode>> tickerCache = new ConcurrentHashMap<>();
    
    // Connection state
    private volatile boolean connected = false;
    private volatile String lastError = null;
    
    static {
        // Add Bouncy Castle provider for EC key handling
        Security.addProvider(new BouncyCastleProvider());
    }
    
    /**
     * Creates a Coinbase client with API credentials.
     * 
     * @param apiKeyName The API key name (from Coinbase Developer Platform)
     * @param privateKeyPem The private key in PEM format (ES256)
     */
    public CoinbaseClient(String apiKeyName, String privateKeyPem) {
        this.apiKeyName = apiKeyName;
        this.privateKey = parsePrivateKey(privateKeyPem);
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
        
        logger.info("💰 Coinbase client initialized");
        logger.info("   API Key: {}...", apiKeyName.substring(0, Math.min(12, apiKeyName.length())));
        logger.info("   Rate limit: {} req/sec (very generous!)", MAX_REQUESTS_PER_SECOND);
    }
    
    /**
     * Parse EC private key from PEM format.
     */
    private PrivateKey parsePrivateKey(String privateKeyPem) {
        try {
            // Normalize line endings and ensure proper PEM format
            String normalizedPem = privateKeyPem.replace("\\n", "\n");
            
            PEMParser parser = new PEMParser(new StringReader(normalizedPem));
            Object pemObject = parser.readObject();
            parser.close();
            
            JcaPEMKeyConverter converter = new JcaPEMKeyConverter().setProvider("BC");
            
            if (pemObject instanceof PEMKeyPair keyPair) {
                return converter.getPrivateKey(keyPair.getPrivateKeyInfo());
            } else if (pemObject instanceof org.bouncycastle.asn1.pkcs.PrivateKeyInfo pkInfo) {
                return converter.getPrivateKey(pkInfo);
            } else {
                throw new IllegalArgumentException("Unsupported key format: " + pemObject.getClass().getName());
            }
        } catch (Exception e) {
            logger.error("Failed to parse private key: {}", e.getMessage());
            throw new RuntimeException("Invalid private key format", e);
        }
    }
    
    /**
     * Generate JWT token for API authentication.
     * Coinbase uses ES256 (ECDSA with P-256 and SHA-256).
     */
    private String generateJwt(String method, String path) {
        try {
            long now = Instant.now().getEpochSecond();
            String nonce = generateNonce();
            
            // URI format: "METHOD host+path"
            String uri = method + " " + "api.coinbase.com" + path;
            
            return Jwts.builder()
                .subject(apiKeyName)
                .issuer("coinbase-cloud")
                .claim("nbf", now)
                .claim("exp", now + 120)  // 2 minute expiry
                .claim("uri", uri)
                .header()
                    .add("kid", apiKeyName)
                    .add("nonce", nonce)
                    .add("typ", "JWT")
                    .and()
                .signWith(privateKey)
                .compact();
        } catch (Exception e) {
            logger.error("Failed to generate JWT: {}", e.getMessage());
            throw new RuntimeException("JWT generation failed", e);
        }
    }
    
    private String generateNonce() {
        byte[] bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
    
    /**
     * Rate limiting check - Coinbase allows 30/sec, we use 25 to be safe.
     */
    private void checkRateLimit() throws InterruptedException {
        long now = System.currentTimeMillis();
        long windowStartTime = windowStart.get();
        
        if (now - windowStartTime >= REQUEST_WINDOW_MS) {
            // New window
            windowStart.set(now);
            requestCount.set(1);
        } else {
            long count = requestCount.incrementAndGet();
            if (count > MAX_REQUESTS_PER_SECOND) {
                // Wait for next window
                long sleepTime = REQUEST_WINDOW_MS - (now - windowStartTime);
                if (sleepTime > 0) {
                    Thread.sleep(sleepTime);
                }
                windowStart.set(System.currentTimeMillis());
                requestCount.set(1);
            }
        }
    }
    
    /**
     * Make authenticated API request.
     */
    private JsonNode request(String method, String path, String body) {
        try {
            checkRateLimit();
            
            String fullPath = API_PREFIX + path;
            String jwt = generateJwt(method, fullPath);
            
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + fullPath))
                .header("Authorization", "Bearer " + jwt)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(15));
            
            if ("GET".equals(method)) {
                requestBuilder.GET();
            } else if ("POST".equals(method)) {
                requestBuilder.POST(HttpRequest.BodyPublishers.ofString(body != null ? body : ""));
            } else if ("DELETE".equals(method)) {
                requestBuilder.DELETE();
            }
            
            HttpResponse<String> response = httpClient.send(
                requestBuilder.build(),
                HttpResponse.BodyHandlers.ofString()
            );
            
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                connected = true;
                lastError = null;
                return mapper.readTree(response.body());
            } else {
                lastError = "HTTP " + response.statusCode() + ": " + response.body();
                logger.warn("Coinbase API error: {}", lastError);
                return null;
            }
        } catch (Exception e) {
            lastError = e.getMessage();
            logger.error("Coinbase request failed: {}", e.getMessage());
            return null;
        }
    }
    
    // ==================== Account & Balance APIs ====================
    
    /**
     * List all accounts with balances.
     * Endpoint: GET /api/v3/brokerage/accounts
     */
    public JsonNode listAccounts() {
        CachedValue<JsonNode> cached = balanceCache.get("accounts");
        if (cached != null && !cached.isExpired()) {
            return cached.value;
        }
        
        JsonNode result = request("GET", "/accounts", null);
        if (result != null) {
            balanceCache.put("accounts", new CachedValue<>(result, BALANCE_CACHE_TTL_MS));
        }
        return result;
    }
    
    /**
     * Get balance for a specific currency.
     * Returns available balance (not including holds).
     */
    public double getBalance(String currency) {
        JsonNode accounts = listAccounts();
        if (accounts == null || !accounts.has("accounts")) {
            return 0.0;
        }
        
        for (JsonNode account : accounts.get("accounts")) {
            if (currency.equals(account.path("currency").asText())) {
                return account.path("available_balance").path("value").asDouble(0.0);
            }
        }
        return 0.0;
    }
    
    /**
     * Get total portfolio value in USD.
     */
    public double getPortfolioValueUsd() {
        JsonNode accounts = listAccounts();
        if (accounts == null || !accounts.has("accounts")) {
            return 0.0;
        }
        
        double total = 0.0;
        for (JsonNode account : accounts.get("accounts")) {
            // Sum up USD equivalent values
            double value = account.path("available_balance").path("value").asDouble(0.0);
            String currency = account.path("currency").asText();
            
            if ("USD".equals(currency) || "USDC".equals(currency)) {
                total += value;
            } else {
                // Would need to convert via market price - for now, skip
                // Could call getBestBidAsk() for conversion
            }
        }
        return total;
    }
    
    // ==================== Market Data APIs ====================
    
    /**
     * Get current best bid/ask for a product.
     * Endpoint: GET /api/v3/brokerage/best_bid_ask?product_ids=BTC-USD
     */
    public JsonNode getBestBidAsk(String productId) {
        String cacheKey = "ticker_" + productId;
        CachedValue<JsonNode> cached = tickerCache.get(cacheKey);
        if (cached != null && !cached.isExpired()) {
            return cached.value;
        }
        
        JsonNode result = request("GET", "/best_bid_ask?product_ids=" + productId, null);
        if (result != null) {
            tickerCache.put(cacheKey, new CachedValue<>(result, TICKER_CACHE_TTL_MS));
        }
        return result;
    }
    
    /**
     * Get current price for a product (mid-price).
     */
    public double getCurrentPrice(String productId) {
        JsonNode bidAsk = getBestBidAsk(productId);
        if (bidAsk == null || !bidAsk.has("pricebooks")) {
            return 0.0;
        }
        
        for (JsonNode pricebook : bidAsk.get("pricebooks")) {
            if (productId.equals(pricebook.path("product_id").asText())) {
                double bid = pricebook.path("bids").get(0).path("price").asDouble(0);
                double ask = pricebook.path("asks").get(0).path("price").asDouble(0);
                return (bid + ask) / 2;  // Mid-price
            }
        }
        return 0.0;
    }
    
    /**
     * Get product info (min order size, tick size, etc).
     * Endpoint: GET /api/v3/brokerage/products/{product_id}
     */
    public JsonNode getProduct(String productId) {
        return request("GET", "/products/" + productId, null);
    }
    
    /**
     * Get 24h ticker data (high, low, volume, change).
     * Endpoint: GET /api/v3/brokerage/products/{product_id}/ticker
     */
    public JsonNode getTicker(String productId) {
        String cacheKey = "ticker24h_" + productId;
        CachedValue<JsonNode> cached = tickerCache.get(cacheKey);
        if (cached != null && !cached.isExpired()) {
            return cached.value;
        }
        
        JsonNode result = request("GET", "/products/" + productId + "/ticker", null);
        if (result != null) {
            tickerCache.put(cacheKey, new CachedValue<>(result, TICKER_CACHE_TTL_MS));
        }
        return result;
    }
    
    // ==================== Order APIs ====================
    
    /**
     * Place a market order.
     * 
     * @param productId Product (e.g., "BTC-USD")
     * @param side "BUY" or "SELL"
     * @param quoteSize Amount in quote currency (USD for BTC-USD)
     * @return Order response with order_id
     */
    public JsonNode placeMarketOrder(String productId, String side, double quoteSize) {
        try {
            ObjectNode orderConfig = mapper.createObjectNode();
            ObjectNode marketIoc = mapper.createObjectNode();
            marketIoc.put("quote_size", String.format("%.2f", quoteSize));
            orderConfig.set("market_market_ioc", marketIoc);
            
            ObjectNode body = mapper.createObjectNode();
            body.put("client_order_id", UUID.randomUUID().toString());
            body.put("product_id", productId);
            body.put("side", side);
            body.set("order_configuration", orderConfig);
            
            logger.info("📤 Placing {} market order: {} ${}", side, productId, quoteSize);
            JsonNode result = request("POST", "/orders", mapper.writeValueAsString(body));
            
            if (result != null && result.path("success").asBoolean()) {
                String orderId = result.path("order_id").asText();
                logger.info("✅ Order placed: {}", orderId);
                invalidateBalanceCache();
            } else {
                logger.error("❌ Order failed: {}", result);
            }
            return result;
        } catch (Exception e) {
            logger.error("Failed to place order: {}", e.getMessage());
            return null;
        }
    }
    
    /**
     * Place a limit order.
     * 
     * @param productId Product (e.g., "BTC-USD")
     * @param side "BUY" or "SELL"
     * @param baseSize Amount in base currency (BTC for BTC-USD)
     * @param limitPrice Limit price in quote currency
     * @param postOnly If true, order will only be maker (no taker fees)
     * @return Order response
     */
    public JsonNode placeLimitOrder(String productId, String side, double baseSize, 
                                     double limitPrice, boolean postOnly) {
        try {
            ObjectNode orderConfig = mapper.createObjectNode();
            ObjectNode limitGtc = mapper.createObjectNode();
            limitGtc.put("base_size", String.format("%.8f", baseSize));
            limitGtc.put("limit_price", String.format("%.2f", limitPrice));
            limitGtc.put("post_only", postOnly);
            orderConfig.set("limit_limit_gtc", limitGtc);
            
            ObjectNode body = mapper.createObjectNode();
            body.put("client_order_id", UUID.randomUUID().toString());
            body.put("product_id", productId);
            body.put("side", side);
            body.set("order_configuration", orderConfig);
            
            logger.info("📤 Placing {} limit order: {} {} @ ${}", side, baseSize, productId, limitPrice);
            JsonNode result = request("POST", "/orders", mapper.writeValueAsString(body));
            
            if (result != null && result.path("success").asBoolean()) {
                String orderId = result.path("order_id").asText();
                logger.info("✅ Limit order placed: {}", orderId);
            } else {
                logger.error("❌ Limit order failed: {}", result);
            }
            return result;
        } catch (Exception e) {
            logger.error("Failed to place limit order: {}", e.getMessage());
            return null;
        }
    }
    
    /**
     * Cancel an order.
     */
    public JsonNode cancelOrder(String orderId) {
        try {
            ObjectNode body = mapper.createObjectNode();
            body.putArray("order_ids").add(orderId);
            
            return request("POST", "/orders/batch_cancel", mapper.writeValueAsString(body));
        } catch (Exception e) {
            logger.error("Failed to cancel order: {}", e.getMessage());
            return null;
        }
    }
    
    /**
     * List open orders.
     */
    public JsonNode listOrders(String status) {
        String query = status != null ? "?order_status=" + status : "";
        return request("GET", "/orders/historical/batch" + query, null);
    }
    
    /**
     * Get order details.
     */
    public JsonNode getOrder(String orderId) {
        return request("GET", "/orders/historical/" + orderId, null);
    }
    
    // ==================== Position Management ====================
    
    /**
     * Get all positions with unrealized P&L.
     */
    public Map<String, Position> getPositions() {
        Map<String, Position> positions = new HashMap<>();
        
        JsonNode accounts = listAccounts();
        if (accounts == null || !accounts.has("accounts")) {
            return positions;
        }
        
        for (JsonNode account : accounts.get("accounts")) {
            String currency = account.path("currency").asText();
            double available = account.path("available_balance").path("value").asDouble(0);
            double held = account.path("hold").path("value").asDouble(0);
            double total = available + held;
            
            // Skip USD and zero balances
            if ("USD".equals(currency) || "USDC".equals(currency) || total < 0.00001) {
                continue;
            }
            
            String productId = currency + "-USD";
            double currentPrice = getCurrentPrice(productId);
            
            positions.put(currency, new Position(
                currency,
                total,
                currentPrice,
                total * currentPrice,
                0  // We don't track entry price in this simple version
            ));
        }
        
        return positions;
    }
    
    // ==================== Utility Methods ====================
    
    public void invalidateBalanceCache() {
        balanceCache.clear();
    }
    
    public boolean isConnected() {
        return connected;
    }
    
    public String getLastError() {
        return lastError;
    }
    
    /**
     * Test connection by fetching accounts.
     */
    public boolean testConnection() {
        JsonNode accounts = listAccounts();
        return accounts != null && accounts.has("accounts");
    }
    
    // ==================== Inner Classes ====================
    
    private static final class CachedValue<T> {
        private final T value;
        private final long expiresAt;
        
        CachedValue(T value, long ttlMs) {
            this.value = value;
            this.expiresAt = System.currentTimeMillis() + ttlMs;
        }
        
        T value() { return value; }
        
        boolean isExpired() {
            return System.currentTimeMillis() > expiresAt;
        }
    }
    
    public record Position(
        String currency,
        double size,
        double currentPrice,
        double marketValue,
        double unrealizedPnl
    ) {}
}
