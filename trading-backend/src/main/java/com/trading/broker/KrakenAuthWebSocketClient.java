package com.trading.broker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * KRAKEN AUTHENTICATED WEBSOCKET CLIENT
 * 
 * Real-time trading via WebSocket V2 - NO RATE LIMITS!
 * Uses Kraken's authenticated WebSocket API for:
 * - Placing orders (add_order) - instant, no rate limit
 * - Balance updates (balances channel) - real-time push
 * - Execution reports (executions channel) - trade confirmations
 * 
 * This eliminates rate limit issues for trading operations.
 * 
 * Endpoint: wss://ws-auth.kraken.com/v2
 * Docs: https://docs.kraken.com/api/docs/websocket-v2/add_order
 * 
 * Flow:
 * 1. Get token via REST: POST /0/private/GetWebSocketsToken
 * 2. Connect to wss://ws-auth.kraken.com/v2
 * 3. Subscribe to balances, executions with token
 * 4. Place orders with token
 */
public class KrakenAuthWebSocketClient implements WebSocket.Listener {
    private static final Logger logger = LoggerFactory.getLogger(KrakenAuthWebSocketClient.class);
    
    private static final String WS_AUTH_URL = "wss://ws-auth.kraken.com/v2";
    private static final Duration TOKEN_REFRESH_INTERVAL = Duration.ofMinutes(30);  // 30 min - less aggressive
    
    // Exponential backoff for reconnects: 60s -> 120s -> 300s -> 600s (max 10 min)
    private static final long INITIAL_RECONNECT_DELAY_SEC = 60;   // Start at 1 minute
    private static final long MAX_RECONNECT_DELAY_SEC = 600;  // 10 minutes max
    private volatile long currentReconnectDelaySec = INITIAL_RECONNECT_DELAY_SEC;
    private volatile boolean rateLimitHit = false;
    
    // Token caching - don't request new token if we have a valid one
    private volatile long tokenObtainedTime = 0;
    private static final long TOKEN_VALID_DURATION_MS = 14 * 60 * 1000;  // 14 minutes (Kraken tokens last 15)
    
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicReference<WebSocket> webSocket = new AtomicReference<>();
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final AtomicBoolean shouldReconnect = new AtomicBoolean(true);
    private final AtomicReference<String> authToken = new AtomicReference<>();
    
    // KrakenClient for getting auth token
    private final KrakenClient krakenClient;
    
    // Real-time balance cache - updated by WebSocket
    private final ConcurrentHashMap<String, Double> balanceCache = new ConcurrentHashMap<>();
    private volatile Instant lastBalanceUpdate = Instant.EPOCH;
    
    // Open orders cache - track pending orders without REST API
    private final ConcurrentHashMap<String, OpenOrder> openOrdersCache = new ConcurrentHashMap<>();
    private volatile Instant lastOrdersUpdate = Instant.EPOCH;
    
    // Order result callbacks - keyed by request ID
    private final ConcurrentHashMap<Integer, CompletableFuture<OrderResult>> pendingOrders = new ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicInteger requestIdCounter = new java.util.concurrent.atomic.AtomicInteger(1);
    
    // Execution callbacks
    private Consumer<ExecutionReport> executionCallback;
    
    // Reconnection scheduler
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1, r -> {
        Thread t = new Thread(r, "kraken-auth-ws-scheduler");
        t.setDaemon(true);
        return t;
    });
    
    // Message buffer for fragmented messages
    private final StringBuilder messageBuffer = new StringBuilder();
    
    /**
     * Order result from WebSocket
     */
    public record OrderResult(
        boolean success,
        String orderId,
        String error,
        String clOrdId
    ) {}
    
    /**
     * Execution report from WebSocket
     */
    public record ExecutionReport(
        String orderId,
        String symbol,
        String side,
        double qty,
        double price,
        double cost,
        double fee,
        String execType,
        Instant timestamp
    ) {}
    
    /**
     * Open order from WebSocket
     */
    public record OpenOrder(
        String orderId,
        String symbol,
        String side,
        String orderType,
        double qty,
        double filledQty,
        double limitPrice,
        String status,
        Instant timestamp
    ) {}
    
    public KrakenAuthWebSocketClient(KrakenClient krakenClient) {
        this.krakenClient = krakenClient;
    }
    
    /**
     * Connect to authenticated WebSocket
     */
    public CompletableFuture<Void> connect() {
        return CompletableFuture.runAsync(() -> {
            try {
                // Step 1: Get auth token
                String token = krakenClient.getWebSocketToken();
                if (token == null || token.isEmpty()) {
                    throw new RuntimeException("Failed to get WebSocket auth token");
                }
                authToken.set(token);
                logger.info("🔑 Got WebSocket auth token");
                
                // Step 2: Connect to authenticated endpoint
                HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();
                
                WebSocket ws = client.newWebSocketBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .buildAsync(URI.create(WS_AUTH_URL), this)
                    .join();
                
                webSocket.set(ws);
                connected.set(true);
                logger.info("🔐 Connected to Kraken Authenticated WebSocket: {}", WS_AUTH_URL);
                
                // Schedule token refresh
                scheduler.scheduleAtFixedRate(this::refreshToken, 
                    TOKEN_REFRESH_INTERVAL.toMinutes(), 
                    TOKEN_REFRESH_INTERVAL.toMinutes(), 
                    TimeUnit.MINUTES);
                
            } catch (Exception e) {
                logger.error("Failed to connect to Kraken auth WebSocket: {}", e.getMessage());
                scheduleReconnect();
                throw new RuntimeException(e);
            }
        });
    }
    
    /**
     * Subscribe to balance updates - real-time, no polling!
     */
    public void subscribeToBalances() {
        String token = authToken.get();
        if (token == null) {
            logger.error("Cannot subscribe to balances: no auth token");
            return;
        }
        
        try {
            String msg = objectMapper.writeValueAsString(Map.of(
                "method", "subscribe",
                "params", Map.of(
                    "channel", "balances",
                    "token", token,
                    "snap_balances", true
                )
            ));
            
            WebSocket ws = webSocket.get();
            if (ws != null) {
                ws.sendText(msg, true);
                logger.info("📊 Subscribed to balances channel");
            }
        } catch (Exception e) {
            logger.error("Failed to subscribe to balances: {}", e.getMessage());
        }
    }
    
    /**
     * Subscribe to execution reports - trade confirmations
     */
    public void subscribeToExecutions() {
        String token = authToken.get();
        if (token == null) {
            logger.error("Cannot subscribe to executions: no auth token");
            return;
        }
        
        try {
            String msg = objectMapper.writeValueAsString(Map.of(
                "method", "subscribe",
                "params", Map.of(
                    "channel", "executions",
                    "token", token,
                    "snap_orders", false,
                    "snap_trades", true
                )
            ));
            
            WebSocket ws = webSocket.get();
            if (ws != null) {
                ws.sendText(msg, true);
                logger.info("📈 Subscribed to executions channel");
            }
        } catch (Exception e) {
            logger.error("Failed to subscribe to executions: {}", e.getMessage());
        }
    }
    
    /**
     * Subscribe to open orders - track positions without REST API
     */
    public void subscribeToOpenOrders() {
        String token = authToken.get();
        if (token == null) {
            logger.error("Cannot subscribe to openOrders: no auth token");
            return;
        }
        
        try {
            String msg = objectMapper.writeValueAsString(Map.of(
                "method", "subscribe",
                "params", Map.of(
                    "channel", "openOrders",
                    "token", token,
                    "snapshot", true
                )
            ));
            
            WebSocket ws = webSocket.get();
            if (ws != null) {
                ws.sendText(msg, true);
                logger.info("📋 Subscribed to openOrders channel");
            }
        } catch (Exception e) {
            logger.error("Failed to subscribe to openOrders: {}", e.getMessage());
        }
    }
    
    /**
     * Check if rate limit was recently hit
     */
    public boolean isRateLimited() {
        return rateLimitHit;
    }
    
    /**
     * Reset reconnect delay (call when conditions improve)
     */
    public void resetReconnectDelay() {
        currentReconnectDelaySec = INITIAL_RECONNECT_DELAY_SEC;
        rateLimitHit = false;
    }
    
    /**
     * Place market order via WebSocket - NO RATE LIMIT!
     */
    public CompletableFuture<OrderResult> placeMarketOrder(String symbol, String side, double quantity) {
        String token = authToken.get();
        if (token == null) {
            return CompletableFuture.completedFuture(
                new OrderResult(false, null, "No auth token", null));
        }
        
        int reqId = requestIdCounter.incrementAndGet();
        CompletableFuture<OrderResult> future = new CompletableFuture<>();
        pendingOrders.put(reqId, future);
        
        // Timeout after 30 seconds
        scheduler.schedule(() -> {
            CompletableFuture<OrderResult> pending = pendingOrders.remove(reqId);
            if (pending != null && !pending.isDone()) {
                pending.complete(new OrderResult(false, null, "Order timeout", null));
            }
        }, 30, TimeUnit.SECONDS);
        
        try {
            String clOrdId = UUID.randomUUID().toString().replace("-", "").substring(0, 18);
            
            String msg = objectMapper.writeValueAsString(Map.of(
                "method", "add_order",
                "params", Map.of(
                    "order_type", "market",
                    "side", side.toLowerCase(),
                    "symbol", symbol,
                    "order_qty", quantity,
                    "token", token,
                    "cl_ord_id", clOrdId
                ),
                "req_id", reqId
            ));
            
            WebSocket ws = webSocket.get();
            if (ws != null) {
                ws.sendText(msg, true);
                logger.info("📤 WS Order sent: {} {} {} x{} (reqId={})", 
                    side, symbol, quantity, clOrdId, reqId);
            } else {
                future.complete(new OrderResult(false, null, "WebSocket not connected", null));
                pendingOrders.remove(reqId);
            }
        } catch (Exception e) {
            logger.error("Failed to place order via WebSocket: {}", e.getMessage());
            future.complete(new OrderResult(false, null, e.getMessage(), null));
            pendingOrders.remove(reqId);
        }
        
        return future;
    }
    
    /**
     * Place limit order via WebSocket
     */
    public CompletableFuture<OrderResult> placeLimitOrder(String symbol, String side, double quantity, double price) {
        String token = authToken.get();
        if (token == null) {
            return CompletableFuture.completedFuture(
                new OrderResult(false, null, "No auth token", null));
        }
        
        int reqId = requestIdCounter.incrementAndGet();
        CompletableFuture<OrderResult> future = new CompletableFuture<>();
        pendingOrders.put(reqId, future);
        
        scheduler.schedule(() -> {
            CompletableFuture<OrderResult> pending = pendingOrders.remove(reqId);
            if (pending != null && !pending.isDone()) {
                pending.complete(new OrderResult(false, null, "Order timeout", null));
            }
        }, 30, TimeUnit.SECONDS);
        
        try {
            String clOrdId = UUID.randomUUID().toString().replace("-", "").substring(0, 18);
            
            String msg = objectMapper.writeValueAsString(Map.of(
                "method", "add_order",
                "params", Map.of(
                    "order_type", "limit",
                    "side", side.toLowerCase(),
                    "symbol", symbol,
                    "order_qty", quantity,
                    "limit_price", price,
                    "token", token,
                    "cl_ord_id", clOrdId
                ),
                "req_id", reqId
            ));
            
            WebSocket ws = webSocket.get();
            if (ws != null) {
                ws.sendText(msg, true);
                logger.info("📤 WS Limit Order: {} {} {} x{} @ ${}", 
                    side, symbol, quantity, price, clOrdId);
            } else {
                future.complete(new OrderResult(false, null, "WebSocket not connected", null));
                pendingOrders.remove(reqId);
            }
        } catch (Exception e) {
            logger.error("Failed to place limit order via WebSocket: {}", e.getMessage());
            future.complete(new OrderResult(false, null, e.getMessage(), null));
            pendingOrders.remove(reqId);
        }
        
        return future;
    }
    
    /**
     * Get cached balance (from WebSocket updates)
     */
    public double getBalance(String asset) {
        return balanceCache.getOrDefault(asset, 0.0);
    }
    
    /**
     * Get USD balance
     */
    public double getUsdBalance() {
        // Kraken uses ZUSD or USD
        double usd = balanceCache.getOrDefault("USD", 0.0);
        double zusd = balanceCache.getOrDefault("ZUSD", 0.0);
        return Math.max(usd, zusd);
    }
    
    /**
     * Get all balances
     */
    public Map<String, Double> getAllBalances() {
        return new ConcurrentHashMap<>(balanceCache);
    }
    
    /**
     * Check if balance cache is fresh
     */
    public boolean isBalanceFresh() {
        return Duration.between(lastBalanceUpdate, Instant.now()).toSeconds() < 60;
    }
    
    /**
     * Set execution callback
     */
    public void setExecutionCallback(Consumer<ExecutionReport> callback) {
        this.executionCallback = callback;
    }
    
    // ==================== WebSocket.Listener Implementation ====================
    
    @Override
    public void onOpen(WebSocket ws) {
        logger.info("🔐 Kraken Auth WebSocket opened");
        ws.request(1);
    }
    
    @Override
    public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
        messageBuffer.append(data);
        
        if (last) {
            String fullMessage = messageBuffer.toString();
            messageBuffer.setLength(0);
            processMessage(fullMessage);
        }
        
        ws.request(1);
        return null;
    }
    
    @Override
    public CompletionStage<?> onPing(WebSocket ws, java.nio.ByteBuffer message) {
        ws.sendPong(message);
        return null;
    }
    
    @Override
    public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
        logger.warn("🔌 Kraken Auth WebSocket closed: {} - {}", statusCode, reason);
        connected.set(false);
        if (shouldReconnect.get()) {
            scheduleReconnect();
        }
        return null;
    }
    
    @Override
    public void onError(WebSocket ws, Throwable error) {
        logger.error("❌ Kraken Auth WebSocket error: {}", error.getMessage());
        connected.set(false);
        if (shouldReconnect.get()) {
            scheduleReconnect();
        }
    }
    
    // ==================== Message Processing ====================
    
    private void processMessage(String message) {
        try {
            JsonNode root = objectMapper.readTree(message);
            
            // Handle subscription confirmations
            if (root.has("method") && root.get("method").asText().equals("subscribe")) {
                if (root.has("success") && root.get("success").asBoolean()) {
                    logger.info("✅ Subscription confirmed: {}", 
                        root.path("result").path("channel").asText());
                }
                return;
            }
            
            // Handle order responses
            if (root.has("method") && root.get("method").asText().equals("add_order")) {
                handleOrderResponse(root);
                return;
            }
            
            // Handle channel updates
            if (root.has("channel")) {
                String channel = root.get("channel").asText();
                
                switch (channel) {
                    case "balances" -> handleBalanceUpdate(root);
                    case "executions" -> handleExecutionUpdate(root);
                    case "openOrders" -> handleOpenOrdersUpdate(root);
                    case "heartbeat" -> logger.debug("💓 Auth heartbeat");
                    default -> logger.debug("Unknown channel: {}", channel);
                }
                return;
            }
            
            // Handle status messages
            if (root.has("type") && root.get("type").asText().equals("update")) {
                // General update, check for channel in data
                if (root.has("data")) {
                    logger.debug("📨 Update: {}", message);
                }
            }
            
        } catch (Exception e) {
            logger.warn("Failed to parse message: {}", e.getMessage());
        }
    }
    
    private void handleOrderResponse(JsonNode root) {
        int reqId = root.path("req_id").asInt(-1);
        boolean success = root.path("success").asBoolean(false);
        String orderId = root.path("result").path("order_id").asText(null);
        String error = root.path("error").asText(null);
        String clOrdId = root.path("result").path("cl_ord_id").asText(null);
        
        CompletableFuture<OrderResult> future = pendingOrders.remove(reqId);
        if (future != null) {
            OrderResult result = new OrderResult(success, orderId, error, clOrdId);
            future.complete(result);
            
            if (success) {
                logger.info("✅ WS Order confirmed: {} (orderId={})", clOrdId, orderId);
            } else {
                logger.error("❌ WS Order failed: {} - {}", clOrdId, error);
            }
        }
    }
    
    private void handleBalanceUpdate(JsonNode root) {
        try {
            JsonNode data = root.path("data");
            if (data.isArray()) {
                for (JsonNode balance : data) {
                    String asset = balance.path("asset").asText();
                    double available = balance.path("balance").asDouble(0);
                    balanceCache.put(asset, available);
                }
                lastBalanceUpdate = Instant.now();
                logger.debug("📊 Balance update: {} assets", balanceCache.size());
            }
        } catch (Exception e) {
            logger.warn("Failed to parse balance update: {}", e.getMessage());
        }
    }
    
    private void handleExecutionUpdate(JsonNode root) {
        try {
            JsonNode data = root.path("data");
            if (data.isArray()) {
                for (JsonNode exec : data) {
                    ExecutionReport report = new ExecutionReport(
                        exec.path("order_id").asText(),
                        exec.path("symbol").asText(),
                        exec.path("side").asText(),
                        exec.path("last_qty").asDouble(0),
                        exec.path("last_price").asDouble(0),
                        exec.path("cost").asDouble(0),
                        exec.path("fee").asDouble(0),
                        exec.path("exec_type").asText(),
                        Instant.now()
                    );
                    
                    logger.info("📈 Execution: {} {} {} @ ${} | Fee: ${}", 
                        report.side(), report.symbol(), report.qty(), 
                        report.price(), report.fee());
                    
                    if (executionCallback != null) {
                        executionCallback.accept(report);
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("Failed to parse execution update: {}", e.getMessage());
        }
    }
    
    private void handleOpenOrdersUpdate(JsonNode root) {
        try {
            JsonNode data = root.path("data");
            if (data.isArray()) {
                for (JsonNode order : data) {
                    String orderId = order.path("order_id").asText();
                    String status = order.path("order_status").asText();
                    
                    // Remove filled/cancelled orders
                    if (status.equals("filled") || status.equals("canceled") || status.equals("expired")) {
                        openOrdersCache.remove(orderId);
                        logger.debug("📋 Order removed: {} ({})", orderId, status);
                        continue;
                    }
                    
                    OpenOrder openOrder = new OpenOrder(
                        orderId,
                        order.path("symbol").asText(),
                        order.path("side").asText(),
                        order.path("order_type").asText(),
                        order.path("order_qty").asDouble(0),
                        order.path("filled_qty").asDouble(0),
                        order.path("limit_price").asDouble(0),
                        status,
                        Instant.now()
                    );
                    
                    openOrdersCache.put(orderId, openOrder);
                    logger.debug("📋 Order update: {} {} {} x{} @ ${}", 
                        openOrder.side(), openOrder.symbol(), openOrder.qty(), openOrder.limitPrice());
                }
                lastOrdersUpdate = Instant.now();
            }
        } catch (Exception e) {
            logger.warn("Failed to parse open orders update: {}", e.getMessage());
        }
    }
    
    /**
     * Get all open orders (from WebSocket cache - no REST API needed!)
     */
    public Map<String, OpenOrder> getOpenOrders() {
        return new ConcurrentHashMap<>(openOrdersCache);
    }
    
    /**
     * Check if open orders cache is fresh
     */
    public boolean isOrdersCacheFresh() {
        return Duration.between(lastOrdersUpdate, Instant.now()).toSeconds() < 60;
    }
    
    // ==================== Connection Management ====================
    
    private void scheduleReconnect() {
        if (shouldReconnect.get()) {
            // Check if we're in hard pause mode - don't even try to reconnect
            if (KrakenClient.isInHardPauseMode()) {
                long pauseRemaining = KrakenClient.getRemainingPauseSeconds();
                logger.info("⏸️ Hard pause active - delaying reconnect for {} seconds", pauseRemaining + 30);
                // Schedule reconnect after hard pause expires (plus 30s buffer)
                scheduler.schedule(this::scheduleReconnect, pauseRemaining + 30, TimeUnit.SECONDS);
                return;
            }
            
            // Use exponential backoff: double the delay each time, cap at max
            long delay = currentReconnectDelaySec;
            
            // If rate limited, use longer delay
            if (rateLimitHit) {
                delay = Math.max(delay, 120);  // At least 2 minutes when rate limited
                logger.warn("⏳ Rate limit detected - waiting {} seconds before reconnect", delay);
            } else {
                logger.info("🔄 Scheduling reconnect in {} seconds (exponential backoff)", delay);
            }
            
            scheduler.schedule(() -> {
                try {
                    // Double-check hard pause before connecting
                    if (KrakenClient.isInHardPauseMode()) {
                        logger.info("⏸️ Hard pause still active, rescheduling...");
                        scheduleReconnect();
                        return;
                    }
                    
                    connect().join();
                    // Re-subscribe after reconnect
                    subscribeToBalances();
                    subscribeToExecutions();
                    subscribeToOpenOrders();  // Track positions via WebSocket
                    
                    // Reset backoff on successful connect
                    currentReconnectDelaySec = INITIAL_RECONNECT_DELAY_SEC;
                    rateLimitHit = false;
                    logger.info("✅ Auth WebSocket reconnected successfully!");
                } catch (Exception e) {
                    // Increase backoff for next attempt
                    currentReconnectDelaySec = Math.min(currentReconnectDelaySec * 2, MAX_RECONNECT_DELAY_SEC);
                    
                    // Check if rate limited
                    if (e.getMessage() != null && e.getMessage().contains("Rate limit")) {
                        rateLimitHit = true;
                        currentReconnectDelaySec = MAX_RECONNECT_DELAY_SEC;  // Max delay on rate limit
                    }
                    
                    logger.error("Reconnect failed: {} (next attempt in {}s)", 
                        e.getMessage(), currentReconnectDelaySec);
                    scheduleReconnect();  // Schedule next attempt with increased delay
                }
            }, delay, TimeUnit.SECONDS);
        }
    }
    
    private void refreshToken() {
        try {
            // Don't refresh if in hard pause mode
            if (KrakenClient.isInHardPauseMode()) {
                logger.debug("⏸️ Skipping token refresh - hard pause active");
                return;
            }
            
            // Don't refresh if token is still valid (14 minutes)
            long now = System.currentTimeMillis();
            if (tokenObtainedTime > 0 && (now - tokenObtainedTime) < TOKEN_VALID_DURATION_MS) {
                logger.debug("⏸️ Skipping token refresh - token still valid ({}s remaining)", 
                    (TOKEN_VALID_DURATION_MS - (now - tokenObtainedTime)) / 1000);
                return;
            }
            
            String newToken = krakenClient.getWebSocketToken();
            if (newToken != null && !newToken.isEmpty()) {
                authToken.set(newToken);
                tokenObtainedTime = System.currentTimeMillis();
                logger.info("🔄 Refreshed WebSocket auth token");
            }
        } catch (Exception e) {
            logger.error("Failed to refresh token: {}", e.getMessage());
        }
    }
    
    public boolean isConnected() {
        return connected.get() && webSocket.get() != null;
    }
    
    public void disconnect() {
        shouldReconnect.set(false);
        connected.set(false);
        WebSocket ws = webSocket.getAndSet(null);
        if (ws != null) {
            ws.sendClose(WebSocket.NORMAL_CLOSURE, "Client disconnect");
        }
        scheduler.shutdown();
        logger.info("🔌 Kraken Auth WebSocket disconnected");
    }
}
