package com.trading.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.trading.api.model.Bar;
import com.trading.api.model.Position;
import com.trading.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Modern HTTP client for Alpaca Markets API.
 * Uses Jackson for JSON parsing and proper logging.
 */
public final class AlpacaClient {
    private static final Logger logger = LoggerFactory.getLogger(AlpacaClient.class);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    
    private final HttpClient httpClient;
    private final Config config;
    private final ObjectMapper objectMapper;
    private final RateLimiter rateLimiter;

    public AlpacaClient(Config config) {
        this.config = config;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(REQUEST_TIMEOUT)
                .build();
        this.objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule());
        
        // Initialize rate limiter
        long delayMs = config.getApiRequestDelayMs();
        boolean adaptive = config.isAdaptiveRateLimitEnabled();
        this.rateLimiter = new RateLimiter(delayMs, adaptive);
                
        logger.info("AlpacaClient initialized for {} with rate limiting: {}ms", 
            config.baseUrl(), delayMs);
    }

    public JsonNode getAccount() throws Exception {
        logger.debug("Fetching account information");
        var response = sendRequest(config.baseUrl() + "/v2/account", "GET");
        return objectMapper.readTree(response);
    }

    /**
     * Validate that the account is ready for trading.
     * Checks account status, trading blocks, and equity.
     * 
     * @return true if account is ready to trade, false otherwise
     */
    public boolean validateAccountForTrading() {
        try {
            var account = getAccount();
            
            // Check account status
            String status = account.get("status").asText();
            if (!"ACTIVE".equals(status)) {
                logger.error("Account status is not ACTIVE: {}", status);
                return false;
            }
            
            // Check if trading is blocked
            boolean tradingBlocked = account.get("trading_blocked").asBoolean(false);
            if (tradingBlocked) {
                logger.error("Trading is blocked on this account");
                return false;
            }
            
            // Check if account transfers are blocked (indicates account issues)
            boolean transfersBlocked = account.get("account_blocked").asBoolean(false);
            if (transfersBlocked) {
                logger.warn("Account has restrictions (account_blocked=true)");
            }
            
            // Get account equity
            double equity = account.get("equity").asDouble(0);
            logger.info("Account validated - Status: {}, Equity: ${}", status, String.format("%.2f", equity));
            
            // Check if account is flagged as pattern day trader
            boolean isPatternDayTrader = account.get("pattern_day_trader").asBoolean(false);
            if (isPatternDayTrader) {
                logger.warn("Account is flagged as Pattern Day Trader - need $25,000 minimum equity");
                if (equity < 25000) {
                    logger.error("PDT account has insufficient equity: ${} (need $25,000)", String.format("%.2f", equity));
                    return false;
                }
            }
            
            return true;
            
        } catch (Exception e) {
            logger.error("Failed to validate account: {}", e.getMessage());
            return false;
        }
    }

    public JsonNode getClock() throws Exception {
        var response = sendRequest(config.baseUrl() + "/v2/clock", "GET");
        return objectMapper.readTree(response);
    }

    public Optional<Position> getPosition(String symbol) {
        try {
            logger.debug("Fetching position for {}", symbol);
            var response = sendRequest(config.baseUrl() + "/v2/positions/" + symbol, "GET");
            var position = objectMapper.readValue(response, Position.class);
            return Optional.of(position);
        } catch (Exception e) {
            logger.debug("No position found for {}", symbol);
            return Optional.empty();
        }
    }

    public List<Position> getPositions() throws Exception {
        logger.debug("Fetching all open positions");
        var response = sendRequest(config.baseUrl() + "/v2/positions", "GET");
        var root = objectMapper.readTree(response);
        
        var positions = new ArrayList<Position>();
        if (root.isArray()) {
            for (var node : root) {
                positions.add(objectMapper.treeToValue(node, Position.class));
            }
        }
        return positions;
    }

    public Optional<Bar> getLatestBar(String symbol) {
        try {
            // Crypto uses different API endpoint (v1beta3/crypto/us)
            // Crypto symbols contain "/" (e.g., BTC/USD, ETH/USD)
            String url;
            if (symbol.contains("/")) {
                // Crypto endpoint - URL encode the symbol
                String encodedSymbol = java.net.URLEncoder.encode(symbol, java.nio.charset.StandardCharsets.UTF_8);
                url = "https://data.alpaca.markets/v1beta3/crypto/us/latest/bars?symbols=" + encodedSymbol;
            } else {
                // Stock endpoint
                url = "https://data.alpaca.markets/v2/stocks/" + symbol + "/bars/latest";
            }
            
            String response = sendRequest(url, "GET");
            JsonNode root = objectMapper.readTree(response);
            
            // Crypto returns bars in a different format: {"bars": {"BTC/USD": {...}}}
            if (symbol.contains("/") && root.has("bars")) {
                JsonNode bars = root.get("bars");
                if (bars.has(symbol)) {
                    return Optional.of(objectMapper.treeToValue(bars.get(symbol), Bar.class));
                }
            } else if (root != null && root.has("bar")) {
                return Optional.of(objectMapper.treeToValue(root.get("bar"), Bar.class));
            }
        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().contains("404")) {
                logger.debug("Latest bar not found for {}: {}", symbol, e.getMessage());
            } else {
                logger.error("Failed to get latest bar for {}", symbol, e);
            }
        }
        return Optional.empty();
    }
    
    public JsonNode getOpenOrders(String symbol) {
        try {
            var url = config.baseUrl() + "/v2/orders?status=open&symbols=" + symbol;
            return objectMapper.readTree(sendRequest(url, "GET"));
        } catch (Exception e) {
            logger.error("Failed to get open orders for {}", symbol, e);
            return objectMapper.createArrayNode();
        }
    }
    
    /**
     * Get recent news articles for a symbol.
     * Uses Alpaca News API for sentiment analysis.
     */
    public JsonNode getNews(String symbol, int limit) {
        try {
            // Use data API base URL for news
            String url = "https://data.alpaca.markets/v1beta1/news?symbols=" + symbol + "&limit=" + limit;
            return objectMapper.readTree(sendRequest(url, "GET"));
        } catch (Exception e) {
            logger.error("Failed to get news for {}", symbol, e);
            return objectMapper.createArrayNode();
        }
    }

    public JsonNode getRecentOrders(String symbol) {
        try {
            // Get last 5 orders (open, closed, rejected)
            var url = config.baseUrl() + "/v2/orders?status=all&limit=5&symbols=" + symbol;
            var response = sendRequest(url, "GET");
            return objectMapper.readTree(response);
        } catch (Exception e) {
            logger.error("Failed to get recent orders for {}", symbol, e);
            return objectMapper.createArrayNode();
        }
    }

    public void cancelOrder(String orderId) {
        try {
            logger.info("Canceling order {}", orderId);
            sendRequest(config.baseUrl() + "/v2/orders/" + orderId, "DELETE");
        } catch (Exception e) {
            logger.error("Failed to cancel order {}", orderId, e);
            throw new RuntimeException("Order cancellation failed", e);
        }
    }

    /**
     * Cancel all open orders.
     */
    public void cancelAllOrders() {
        try {
            logger.info("Canceling ALL open orders");
            sendRequest(config.baseUrl() + "/v2/orders", "DELETE");
        } catch (Exception e) {
            logger.error("Failed to cancel all orders", e);
            throw new RuntimeException("Cancel all orders failed", e);
        }
    }

    public void placeOrder(String symbol, double qty, String side, String type, String timeInForce, Double limitPrice) {
        try {
            var order = objectMapper.createObjectNode()
                .put("symbol", symbol)
                .put("qty", String.format("%.9f", qty)) // Use 9 decimal places for fractional shares
                .put("side", side)
                .put("type", type)
                .put("time_in_force", timeInForce);
            
            if (limitPrice != null) {
                order.put("limit_price", String.format("%.2f", limitPrice));
            }
            
            // Extended hours support (only for limit orders)
            if (config.isExtendedHoursEnabled() && "limit".equals(type)) {
                order.put("extended_hours", true);
            }

            var body = objectMapper.writeValueAsString(order);
            logger.info("Placing order: {}", body);
            sendRequest(config.baseUrl() + "/v2/orders", "POST", body);
        } catch (Exception e) {
            logger.error("Failed to place order", e);
            throw new RuntimeException("Order placement failed", e);
        }
    }

    /**
     * Replace an existing order with updated parameters.
     * Useful for trailing stops or adjusting limit prices.
     */
    public void replaceOrder(String orderId, Double qty, Double limitPrice, Double stopPrice) {
        try {
            var params = objectMapper.createObjectNode();
            
            if (qty != null) {
                params.put("qty", String.format("%.9f", qty));
            }
            if (limitPrice != null) {
                params.put("limit_price", String.format("%.2f", limitPrice));
            }
            if (stopPrice != null) {
                params.put("stop_price", String.format("%.2f", stopPrice));
            }

            var body = objectMapper.writeValueAsString(params);
            logger.info("Replacing order {}: {}", orderId, body);
            sendRequest(config.baseUrl() + "/v2/orders/" + orderId, "PATCH", body);
        } catch (Exception e) {
            logger.error("Failed to replace order {}", orderId, e);
            throw new RuntimeException("Order replacement failed", e);
        }
    }

    public void placeTrailingStopOrder(String symbol, double qty, String side, double trailPercent) {
        try {
            var order = objectMapper.createObjectNode()
                .put("symbol", symbol)
                .put("qty", String.format("%.9f", qty))
                .put("side", side)
                .put("type", "trailing_stop")
                .put("time_in_force", "gtc")
                .put("trail_percent", String.format("%.1f", trailPercent));

            var body = objectMapper.writeValueAsString(order);
            logger.info("Placing trailing stop order: {}", body);
            sendRequest(config.baseUrl() + "/v2/orders", "POST", body);
        } catch (Exception e) {
            logger.error("Failed to place trailing stop order", e);
            throw new RuntimeException("Trailing stop order placement failed", e);
        }
    }

    public void placeBracketOrder(String symbol, double qty, String side, 
                                   double takeProfitPrice, double stopLossPrice, 
                                   Double stopLossLimitPrice, Double limitPrice) {
        try {
            // Create main order object
            var order = objectMapper.createObjectNode()
                .put("symbol", symbol)
                .put("qty", String.format("%.9f", qty))
                .put("side", side)
                .put("type", limitPrice != null ? "limit" : "market");
        // Check if fractional
        boolean isFractional = qty % 1 != 0;
        
        if (isFractional) {
            logger.warn("Fractional quantities do not support Bracket orders. Placing simple DAY order instead. Stops must be managed client-side.");
            // Remove bracket params and force DAY
            order.put("time_in_force", "day");
            // We cannot send take_profit or stop_loss for simple orders
        } else {
            // Whole shares: Use Bracket + GTC
            order.put("time_in_force", "gtc");
            order.put("order_class", "bracket");
            
            // Add Take Profit
            var takeProfit = objectMapper.createObjectNode()
                .put("limit_price", String.format("%.2f", takeProfitPrice));
            order.set("take_profit", takeProfit);

            // Add Stop Loss
            var stopLoss = objectMapper.createObjectNode()
                .put("stop_price", String.format("%.2f", stopLossPrice));
            if (stopLossLimitPrice != null) {
                stopLoss.put("limit_price", String.format("%.2f", stopLossLimitPrice));
            }
            order.set("stop_loss", stopLoss);
        }

            if (limitPrice != null) {
                order.put("limit_price", String.format("%.2f", limitPrice));
                
                // NOTE: Bracket orders do NOT support extended hours on Alpaca.
                // We must NOT add the extended_hours flag here.
            }

            var body = objectMapper.writeValueAsString(order);
            logger.info("Placing bracket order: {}", body);
            sendRequest(config.baseUrl() + "/v2/orders", "POST", body);
        } catch (Exception e) {
            logger.error("Failed to place bracket order", e);
            throw new RuntimeException("Bracket order placement failed", e);
        }
    }

    /**
     * Place a bracket order with automatic take-profit and stop-loss.
     * This is the RECOMMENDED way to place orders for live trading as it provides
     * automatic position protection even if the bot crashes or loses connection.
     * 
     * @param symbol Stock symbol (e.g., "SPY")
     * @param qty Quantity of shares
     * @param side "buy" or "sell"
     * @param takeProfitPrice Limit price for take-profit order
     * @param stopLossPrice Stop price for stop-loss order
     * @param stopLossLimitPrice Optional limit price for stop-loss (null for stop-market)
     * @return Order response JSON
     */
    public String placeBracketOrder(String symbol, double qty, String side, 
                                   double takeProfitPrice, double stopLossPrice, 
                                   Double stopLossLimitPrice) throws Exception {
        logger.info("Placing BRACKET {} order: {} {} shares (TP=${}, SL=${})", 
                   side.toUpperCase(), symbol, qty, 
                   String.format("%.2f", takeProfitPrice),
                   String.format("%.2f", stopLossPrice));
        
        // Build stop_loss object
        String stopLossJson;
        if (stopLossLimitPrice != null) {
            stopLossJson = String.format("""
                "stop_loss": {
                    "stop_price": "%.2f",
                    "limit_price": "%.2f"
                }
                """, stopLossPrice, stopLossLimitPrice);
        } else {
            stopLossJson = String.format("""
                "stop_loss": {
                    "stop_price": "%.2f"
                }
                """, stopLossPrice);
        }
        
        var orderJson = String.format("""
            {
                "symbol": "%s",
                "qty": %.9f,
                "side": "%s",
                "type": "market",
                "time_in_force": "gtc",
                "order_class": "bracket",
                "take_profit": {
                    "limit_price": "%.2f"
                },
                %s
            }
            """, symbol, qty, side, takeProfitPrice, stopLossJson);
        
        return sendRequest(config.baseUrl() + "/v2/orders", "POST", orderJson);
    }
    
    /**
     * Get intraday bars for a symbol
     * @param symbol Stock symbol
     * @param timeframe Timeframe (1Min, 5Min, 15Min, 1Hour, 1Day)
     * @param limit Number of bars to retrieve
     * @return List of bars
     */
    public List<Bar> getBars(String symbol, String timeframe, int limit) throws Exception {
        logger.debug("Fetching {} {} bars for {}", limit, timeframe, symbol);
        
        // Calculate start time based on timeframe
        var now = java.time.ZonedDateTime.now(java.time.ZoneId.of("America/New_York"));
        var start = switch (timeframe) {
            case "1Min" -> now.minusMinutes(limit * 2L);
            case "5Min" -> now.minusMinutes(limit * 10L);
            case "15Min" -> now.minusMinutes(limit * 30L);
            case "1Hour" -> now.minusHours(limit * 2L);
            case "1Day" -> now.minusDays(limit * 2L);
            default -> now.minusHours(limit);
        };
        
        var startStr = start.format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        
        // Crypto uses different API endpoint
        String url;
        if (symbol.contains("/")) {
            String encodedSymbol = java.net.URLEncoder.encode(symbol, java.nio.charset.StandardCharsets.UTF_8);
            url = String.format("https://data.alpaca.markets/v1beta3/crypto/us/bars?symbols=%s&timeframe=%s&limit=%d&start=%s", 
                    encodedSymbol, timeframe, limit, java.net.URLEncoder.encode(startStr, java.nio.charset.StandardCharsets.UTF_8));
        } else {
            url = String.format("https://data.alpaca.markets/v2/stocks/%s/bars?timeframe=%s&feed=iex&limit=%d&start=%s", 
                    symbol, timeframe, limit, java.net.URLEncoder.encode(startStr, java.nio.charset.StandardCharsets.UTF_8));
        }
        
        var response = sendRequest(url, "GET");
        var root = objectMapper.readTree(response);
        
        var bars = new ArrayList<Bar>();
        
        // Crypto returns bars in different format: {"bars": {"BTC/USD": [...]}}
        if (symbol.contains("/")) {
            var barsContainer = root.get("bars");
            if (barsContainer != null && barsContainer.has(symbol)) {
                var barsArray = barsContainer.get(symbol);
                if (barsArray != null && barsArray.isArray()) {
                    for (var barNode : barsArray) {
                        bars.add(objectMapper.treeToValue(barNode, Bar.class));
                    }
                }
            }
        } else {
            var barsArray = root.get("bars");
            if (barsArray != null && barsArray.isArray()) {
                for (var barNode : barsArray) {
                    bars.add(objectMapper.treeToValue(barNode, Bar.class));
                }
            }
        }
        
        logger.debug("Retrieved {} {} bars for {}", bars.size(), timeframe, symbol);
        return bars;
    }

    public List<Bar> getMarketHistory(String symbol, int limit) throws Exception {
        logger.debug("Fetching {} bars for {}", limit, symbol);
        
        // Request more days back to ensure we get enough trading days (skipping weekends/holidays)
        var start = java.time.ZonedDateTime.now(java.time.ZoneId.of("America/New_York"))
                .minusDays(limit * 2L)
                .format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME);
                
        var url = String.format("https://data.alpaca.markets/v2/stocks/%s/bars?timeframe=1Day&feed=iex&limit=%d&start=%s", 
                symbol, limit, java.net.URLEncoder.encode(start, java.nio.charset.StandardCharsets.UTF_8));
        var response = sendRequest(url, "GET");
        var root = objectMapper.readTree(response);
        
        var bars = new ArrayList<Bar>();
        var barsArray = root.get("bars");
        
        if (barsArray != null && barsArray.isArray()) {
            for (var barNode : barsArray) {
                bars.add(objectMapper.treeToValue(barNode, Bar.class));
            }
        }
        
        logger.debug("Retrieved {} bars for {}", bars.size(), symbol);
        return bars;
    }

    private String sendRequest(String url, String method) throws Exception {
        return sendRequest(url, method, null);
    }

    private String sendRequest(String url, String method, String body) throws Exception {
        // Apply rate limiting before making request
        rateLimiter.waitIfNeeded();
        
        var builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("APCA-API-KEY-ID", config.apiKey())
                .header("APCA-API-SECRET-KEY", config.apiSecret())
                .header("Content-Type", "application/json")
                .timeout(REQUEST_TIMEOUT);

        var request = switch (method.toUpperCase()) {
            case "GET" -> builder.GET().build();
            case "POST" -> builder.POST(HttpRequest.BodyPublishers.ofString(body != null ? body : "{}")).build();
            case "DELETE" -> builder.DELETE().build();
            default -> throw new IllegalArgumentException(String.format("Unsupported HTTP method: %s", method));
        };

        var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            // Success - record for adaptive rate limiting
            rateLimiter.recordSuccess();
            return response.body();
        } else if (response.statusCode() == 429) {
            // Rate limit hit - record and back off
            rateLimiter.recordRateLimit();
            var errorMsg = String.format("API Rate Limit (429) - backing off. Current delay: %dms", 
                rateLimiter.getCurrentDelay());
            logger.warn(errorMsg);
            throw new RuntimeException(errorMsg);
        } else {
            var errorMsg = String.format("API Request failed: %d - %s", response.statusCode(), response.body());
            if (response.statusCode() == 404) {
                logger.debug(errorMsg);
            } else {
                logger.error(errorMsg);
            }
            throw new RuntimeException(errorMsg);
        }
    }
}
