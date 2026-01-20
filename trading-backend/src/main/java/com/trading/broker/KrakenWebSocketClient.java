package com.trading.broker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * KRAKEN WEBSOCKET CLIENT
 * 
 * Real-time market data via WebSocket - NO RATE LIMITS!
 * Uses Kraken's public WebSocket API v2 for:
 * - Real-time ticker/price updates
 * - OHLC candles
 * - Order book updates
 * 
 * This eliminates the rate limit issues from REST API polling.
 * 
 * Endpoint: wss://ws.kraken.com/v2
 * Docs: https://docs.kraken.com/api/docs/websocket-v2/ticker
 */
public class KrakenWebSocketClient implements WebSocket.Listener {
    private static final Logger logger = LoggerFactory.getLogger(KrakenWebSocketClient.class);
    
    private static final String WS_URL = "wss://ws.kraken.com/v2";
    private static final Duration RECONNECT_DELAY = Duration.ofSeconds(5);
    private static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(30);
    
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicReference<WebSocket> webSocket = new AtomicReference<>();
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final AtomicBoolean shouldReconnect = new AtomicBoolean(true);
    
    // Real-time price cache - updated by WebSocket, no rate limits!
    private final ConcurrentHashMap<String, TickerData> tickerCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Instant> lastUpdate = new ConcurrentHashMap<>();
    
    // Subscribed symbols
    private final List<String> symbols;
    
    // Reconnection scheduler
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1, r -> {
        Thread t = new Thread(r, "kraken-ws-scheduler");
        t.setDaemon(true);
        return t;
    });
    
    // Message buffer for fragmented messages
    private final StringBuilder messageBuffer = new StringBuilder();
    
    /**
     * Ticker data from WebSocket
     */
    public record TickerData(
        String symbol,
        double bid,
        double ask,
        double last,
        double volume24h,
        double high24h,
        double low24h,
        double vwap24h,
        double change24h,
        Instant timestamp
    ) {
        public double mid() {
            return (bid + ask) / 2;
        }
    }
    
    public KrakenWebSocketClient(List<String> symbols) {
        this.symbols = symbols;
        logger.info("🦑 Kraken WebSocket client created for symbols: {}", symbols);
    }
    
    /**
     * Connect to Kraken WebSocket
     */
    public CompletableFuture<Void> connect() {
        logger.info("🔌 Connecting to Kraken WebSocket: {}", WS_URL);
        
        return HttpClient.newHttpClient()
            .newWebSocketBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .buildAsync(URI.create(WS_URL), this)
            .thenAccept(ws -> {
                webSocket.set(ws);
                connected.set(true);
                logger.info("✅ Kraken WebSocket connected!");
                
                // Subscribe to ticker for all symbols
                subscribeToTickers();
                
                // Start heartbeat
                startHeartbeat();
            })
            .exceptionally(e -> {
                logger.error("❌ WebSocket connection failed: {}", e.getMessage());
                scheduleReconnect();
                return null;
            });
    }
    
    /**
     * Subscribe to real-time ticker updates
     */
    private void subscribeToTickers() {
        try {
            // Symbols are already in "BTC/USD" format from CRYPTO_SYMBOLS list
            // Kraken WebSocket v2 uses this format directly
            List<String> wsSymbols = symbols.stream()
                .map(this::toWebSocketSymbol)
                .toList();
            
            // Kraken WebSocket v2 subscription format
            // Docs: https://docs.kraken.com/api/docs/websocket-v2/ticker
            String subscribeMsg = objectMapper.writeValueAsString(Map.of(
                "method", "subscribe",
                "params", Map.of(
                    "channel", "ticker",
                    "symbol", wsSymbols
                )
            ));
            
            WebSocket ws = webSocket.get();
            if (ws != null) {
                ws.sendText(subscribeMsg, true);
                logger.info("📡 Subscribed to tickers: {} - message: {}", wsSymbols, subscribeMsg);
            }
        } catch (Exception e) {
            logger.error("Failed to subscribe to tickers: {}", e.getMessage());
        }
    }
    
    /**
     * Convert symbol to WebSocket format
     * Handles both REST format (XXBTZUSD) and display format (BTC/USD)
     */
    private String toWebSocketSymbol(String symbol) {
        // If already in "X/USD" format, just standardize
        if (symbol.contains("/")) {
            // BTC/USD -> XBT/USD (Kraken uses XBT for Bitcoin)
            return switch (symbol.toUpperCase()) {
                case "BTC/USD" -> "XBT/USD";
                default -> symbol.toUpperCase();
            };
        }
        
        // Handle REST API formats
        return switch (symbol) {
            case "XXBTZUSD", "XBTUSD" -> "XBT/USD";
            case "XETHZUSD", "ETHUSD" -> "ETH/USD";
            case "XXRPZUSD", "XRPUSD" -> "XRP/USD";
            case "XSOLUSD", "SOLUSD" -> "SOL/USD";
            case "XDOGEUSD", "DOGEUSD" -> "DOGE/USD";
            case "XXLMZUSD", "XLMUSD" -> "XLM/USD";
            case "XADAUSD", "ADAUSD" -> "ADA/USD";
            case "XDOTUSD", "DOTUSD" -> "DOT/USD";
            case "XAVAXUSD", "AVAXUSD" -> "AVAX/USD";
            case "XMATICUSD", "MATICUSD" -> "MATIC/USD";
            case "PAXGUSD" -> "PAXG/USD";  // Tokenized gold
            default -> {
                // Generic conversion: remove leading X and Z, add /
                String s = symbol;
                if (s.startsWith("X") && s.length() > 4) s = s.substring(1);
                if (s.contains("Z") && s.endsWith("USD")) s = s.replace("ZUSD", "/USD");
                else if (s.endsWith("USD")) s = s.replace("USD", "/USD");
                yield s;
            }
        };
    }
    
    /**
     * Convert WebSocket symbol back to REST format
     */
    private String toRestSymbol(String wsSymbol) {
        return switch (wsSymbol) {
            case "XBT/USD" -> "XXBTZUSD";
            case "ETH/USD" -> "XETHZUSD";
            case "XRP/USD" -> "XXRPZUSD";
            case "SOL/USD" -> "SOLUSD";
            case "DOGE/USD" -> "XDOGEUSD";
            case "XLM/USD" -> "XXLMZUSD";
            case "ADA/USD" -> "ADAUSD";
            case "DOT/USD" -> "DOTUSD";
            case "AVAX/USD" -> "AVAXUSD";
            case "MATIC/USD" -> "MATICUSD";
            default -> wsSymbol.replace("/", "");
        };
    }
    
    /**
     * Start heartbeat to keep connection alive
     */
    private void startHeartbeat() {
        scheduler.scheduleAtFixedRate(() -> {
            if (connected.get()) {
                try {
                    WebSocket ws = webSocket.get();
                    if (ws != null) {
                        ws.sendPing(ByteBuffer.allocate(0));
                    }
                } catch (Exception e) {
                    logger.warn("Heartbeat failed: {}", e.getMessage());
                }
            }
        }, HEARTBEAT_INTERVAL.toSeconds(), HEARTBEAT_INTERVAL.toSeconds(), TimeUnit.SECONDS);
    }
    
    /**
     * Get real-time price for symbol (from WebSocket cache)
     * Returns null if no data available yet
     */
    public TickerData getTicker(String symbol) {
        // Try the symbol directly first
        TickerData data = tickerCache.get(symbol);
        
        // Try WebSocket format (BTC/USD -> XBT/USD)
        if (data == null) {
            data = tickerCache.get(toWebSocketSymbol(symbol));
        }
        
        // Try REST format
        if (data == null) {
            data = tickerCache.get(toRestSymbol(symbol));
        }
        
        // Special case: BTC/USD is XBT/USD in Kraken
        if (data == null && symbol.contains("BTC")) {
            data = tickerCache.get("XBT/USD");
        }
        
        return data;
    }
    
    /**
     * Get last price for symbol
     */
    public Double getLastPrice(String symbol) {
        TickerData ticker = getTicker(symbol);
        if (ticker != null) {
            logger.debug("📡 {} price from WebSocket: ${} (age: {}s)", 
                symbol, ticker.last(), 
                java.time.Duration.between(ticker.timestamp(), Instant.now()).toSeconds());
        }
        return ticker != null ? ticker.last() : null;
    }
    
    /**
     * Check if we have fresh data for symbol (less than 10 seconds old)
     */
    public boolean hasFreshData(String symbol) {
        Instant last = lastUpdate.get(symbol);
        if (last == null) last = lastUpdate.get(toWebSocketSymbol(symbol));
        return last != null && Duration.between(last, Instant.now()).toSeconds() < 10;
    }
    
    /**
     * Check if connected
     */
    public boolean isConnected() {
        return connected.get();
    }
    
    // ==================== WebSocket.Listener Implementation ====================
    
    @Override
    public void onOpen(WebSocket webSocket) {
        logger.info("🔓 WebSocket opened");
        webSocket.request(1);
    }
    
    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
        messageBuffer.append(data);
        
        if (last) {
            processMessage(messageBuffer.toString());
            messageBuffer.setLength(0);
        }
        
        webSocket.request(1);
        return null;
    }
    
    @Override
    public CompletionStage<?> onPong(WebSocket webSocket, ByteBuffer message) {
        logger.debug("Pong received");
        webSocket.request(1);
        return null;
    }
    
    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
        logger.warn("🔒 WebSocket closed: {} - {}", statusCode, reason);
        connected.set(false);
        if (shouldReconnect.get()) {
            scheduleReconnect();
        }
        return null;
    }
    
    @Override
    public void onError(WebSocket webSocket, Throwable error) {
        logger.error("❌ WebSocket error: {}", error.getMessage());
        connected.set(false);
        if (shouldReconnect.get()) {
            scheduleReconnect();
        }
    }
    
    /**
     * Process incoming WebSocket message
     */
    private void processMessage(String message) {
        try {
            JsonNode root = objectMapper.readTree(message);
            
            // Check message type
            String channel = root.path("channel").asText();
            
            if ("ticker".equals(channel)) {
                processTickerUpdate(root);
            } else if ("heartbeat".equals(channel)) {
                logger.debug("💓 Heartbeat from Kraken");
            } else if ("status".equals(channel)) {
                String status = root.path("data").path(0).path("system").asText();
                logger.info("📊 Kraken system status: {}", status);
            } else if (root.has("method")) {
                // Subscription response
                String method = root.get("method").asText();
                boolean success = root.path("success").asBoolean(true);
                logger.info("📬 {} response: success={} - {}", method, success, 
                    root.has("error") ? root.get("error").asText() : "ok");
            } else if (root.has("error")) {
                logger.warn("⚠️ WebSocket error: {}", root.get("error").asText());
            } else {
                // Log unknown message types for debugging
                logger.debug("📩 Unknown message type: {}", message.substring(0, Math.min(200, message.length())));
            }
        } catch (Exception e) {
            logger.debug("Failed to parse message: {}", e.getMessage());
        }
    }
    
    /**
     * Process ticker update from WebSocket
     */
    private void processTickerUpdate(JsonNode root) {
        try {
            JsonNode dataArray = root.path("data");
            if (dataArray.isArray()) {
                for (JsonNode ticker : dataArray) {
                    String symbol = ticker.path("symbol").asText();
                    
                    TickerData data = new TickerData(
                        symbol,
                        ticker.path("bid").asDouble(),
                        ticker.path("ask").asDouble(),
                        ticker.path("last").asDouble(),
                        ticker.path("volume").asDouble(),
                        ticker.path("high").asDouble(),
                        ticker.path("low").asDouble(),
                        ticker.path("vwap").asDouble(),
                        ticker.path("change_pct").asDouble(),
                        Instant.now()
                    );
                    
                    tickerCache.put(symbol, data);
                    tickerCache.put(toRestSymbol(symbol), data);  // Also store with REST key
                    lastUpdate.put(symbol, Instant.now());
                    
                    logger.debug("📈 {} ${} (bid={}, ask={}, vol={})", 
                        symbol, data.last(), data.bid(), data.ask(), data.volume24h());
                }
            }
        } catch (Exception e) {
            logger.warn("Failed to process ticker update: {}", e.getMessage());
        }
    }
    
    /**
     * Schedule reconnection
     */
    private void scheduleReconnect() {
        if (shouldReconnect.get()) {
            logger.info("🔄 Scheduling reconnect in {} seconds...", RECONNECT_DELAY.toSeconds());
            scheduler.schedule(this::connect, RECONNECT_DELAY.toSeconds(), TimeUnit.SECONDS);
        }
    }
    
    /**
     * Disconnect and cleanup
     */
    public void disconnect() {
        shouldReconnect.set(false);
        connected.set(false);
        
        WebSocket ws = webSocket.get();
        if (ws != null) {
            ws.sendClose(WebSocket.NORMAL_CLOSURE, "Shutting down");
        }
        
        scheduler.shutdown();
        logger.info("🔌 Kraken WebSocket disconnected");
    }
    
    /**
     * Get all cached tickers
     */
    public Map<String, TickerData> getAllTickers() {
        return Map.copyOf(tickerCache);
    }
}
