package com.trading.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.trading.api.model.Bar;
import com.trading.api.model.BracketOrderResult;
import com.trading.api.model.Position;
import com.trading.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Tradier broker integration implementing BrokerClient.
 *
 * Uses the Tradier REST API v1:
 *   Production: https://api.tradier.com/v1
 *   Sandbox:    https://sandbox.tradier.com/v1
 *
 * Authentication: Bearer token via TRADIER_ACCESS_TOKEN env var.
 * Account operations require TRADIER_ACCOUNT_ID env var.
 *
 * Key differences from Alpaca:
 * - Order placement is form-encoded (not JSON)
 * - Uses "duration" instead of "time_in_force" (day, gtc, pre, post)
 * - No fractional shares on cash accounts — qty rounded to nearest int
 * - No native bracket orders — SL/TP handled by ProfileManager (client-side)
 * - Trailing stop orders not universally supported — throws UnsupportedOperationException
 */
public final class TradierClient implements BrokerClient {
    private static final Logger logger = LoggerFactory.getLogger(TradierClient.class);

    private static final String PRODUCTION_BASE = "https://api.tradier.com/v1";
    private static final String SANDBOX_BASE    = "https://sandbox.tradier.com/v1";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final String accessToken;
    private final String accountId;

    public TradierClient(Config config) {
        this.accessToken = config.getTradierAccessToken();
        this.accountId   = config.getTradierAccountId();
        this.baseUrl     = config.isTradierSandbox() ? SANDBOX_BASE : PRODUCTION_BASE;
        this.httpClient  = HttpClient.newBuilder()
            .connectTimeout(REQUEST_TIMEOUT)
            .build();
        this.objectMapper = new ObjectMapper();

        logger.info("TradierClient initialized — baseUrl={}, sandbox={}, accountId={}",
            baseUrl, config.isTradierSandbox(),
            accountId.isBlank() ? "<not set>" : accountId.substring(0, Math.min(4, accountId.length())) + "****");
    }

    // ── HTTP helpers ──────────────────────────────────────────────────────────

    private String sendGet(String url) throws Exception {
        var req = HttpRequest.newBuilder(URI.create(url))
            .header("Authorization", "Bearer " + accessToken)
            .header("Accept", "application/json")
            .timeout(REQUEST_TIMEOUT)
            .GET()
            .build();
        var resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() >= 400) {
            throw new RuntimeException("Tradier GET failed [" + resp.statusCode() + "]: " + resp.body());
        }
        return resp.body();
    }

    private String sendPost(String url, String formBody) throws Exception {
        var req = HttpRequest.newBuilder(URI.create(url))
            .header("Authorization", "Bearer " + accessToken)
            .header("Accept", "application/json")
            .header("Content-Type", "application/x-www-form-urlencoded")
            .timeout(REQUEST_TIMEOUT)
            .POST(HttpRequest.BodyPublishers.ofString(formBody))
            .build();
        var resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() >= 400) {
            throw new RuntimeException("Tradier POST failed [" + resp.statusCode() + "]: " + resp.body());
        }
        return resp.body();
    }

    private String sendDelete(String url) throws Exception {
        var req = HttpRequest.newBuilder(URI.create(url))
            .header("Authorization", "Bearer " + accessToken)
            .header("Accept", "application/json")
            .timeout(REQUEST_TIMEOUT)
            .DELETE()
            .build();
        var resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() >= 400) {
            throw new RuntimeException("Tradier DELETE failed [" + resp.statusCode() + "]: " + resp.body());
        }
        return resp.body();
    }

    /** Encode a form parameter value for application/x-www-form-urlencoded. */
    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    // ── Account ───────────────────────────────────────────────────────────────

    /**
     * Returns account balances normalized to a structure compatible with Alpaca:
     * { equity, buying_power, status, trading_blocked, account_blocked, pattern_day_trader }
     */
    @Override
    public JsonNode getAccount() throws Exception {
        String json = sendGet(baseUrl + "/accounts/" + accountId + "/balances");
        JsonNode root = objectMapper.readTree(json);
        JsonNode balances = root.path("balances");

        ObjectNode result = objectMapper.createObjectNode();
        result.put("equity",            balances.path("total_equity").asDouble(0));
        result.put("buying_power",      balances.path("cash").path("cash_available").asDouble(
                                         balances.path("buying_power").asDouble(0)));
        result.put("status",            "ACTIVE");
        result.put("trading_blocked",   false);
        result.put("account_blocked",   false);
        result.put("pattern_day_trader", false);
        return result;
    }

    @Override
    public boolean validateAccountForTrading() {
        try {
            JsonNode account = getAccount();
            return account.path("equity").asDouble(0) > 0;
        } catch (Exception e) {
            logger.error("Failed to validate Tradier account: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Tradier does not have a clock endpoint — synthesize from account profile.
     */
    @Override
    public JsonNode getClock() throws Exception {
        // Return a minimal clock node; ProfileManager only checks is_open from this
        ObjectNode clock = objectMapper.createObjectNode();
        clock.put("is_open", true);
        clock.put("next_open", "");
        clock.put("next_close", "");
        return clock;
    }

    // ── Positions ─────────────────────────────────────────────────────────────

    @Override
    public Optional<Position> getPosition(String symbol) {
        try {
            return getPositions().stream()
                .filter(p -> symbol.equalsIgnoreCase(p.symbol()))
                .findFirst();
        } catch (Exception e) {
            logger.error("Failed to get Tradier position for {}", symbol, e);
            return Optional.empty();
        }
    }

    /**
     * Normalizes Tradier's nested position response to a flat List<Position>.
     *
     * Tradier response:
     *   { "positions": { "position": [ {...}, {...} ] } }  — array
     *   { "positions": { "position": { ... } } }           — single object (not array)
     *   { "positions": "null" }                             — no positions
     */
    @Override
    public List<Position> getPositions() throws Exception {
        String json = sendGet(baseUrl + "/accounts/" + accountId + "/positions");
        JsonNode root = objectMapper.readTree(json);
        JsonNode positionsNode = root.path("positions");

        if (positionsNode.isMissingNode() || positionsNode.isNull()
                || positionsNode.asText("").equals("null")) {
            return List.of();
        }

        JsonNode positionNode = positionsNode.path("position");
        List<Position> result = new ArrayList<>();

        if (positionNode.isArray()) {
            for (JsonNode p : positionNode) {
                result.add(parsePosition(p));
            }
        } else if (positionNode.isObject()) {
            result.add(parsePosition(positionNode));
        }
        return result;
    }

    private Position parsePosition(JsonNode p) {
        String symbol   = p.path("symbol").asText();
        double qty      = p.path("quantity").asDouble();
        double costBasis = p.path("cost_basis").asDouble();
        double avgPrice = qty > 0 ? costBasis / qty : 0.0;
        // Tradier doesn't provide real-time unrealized PL in position response
        return new Position(symbol, qty, costBasis, avgPrice, 0.0);
    }

    // ── Market Data ───────────────────────────────────────────────────────────

    /**
     * Gets the latest quote and synthesizes a Bar from bid/ask/last/high/low/open fields.
     */
    @Override
    public Optional<Bar> getLatestBar(String symbol) {
        try {
            String json = sendGet(baseUrl + "/markets/quotes?symbols=" + enc(symbol) + "&greeks=false");
            JsonNode root = objectMapper.readTree(json);
            JsonNode quote = root.path("quotes").path("quote");

            if (quote.isMissingNode() || quote.isNull()) return Optional.empty();

            double close = quote.path("last").asDouble(0);
            if (close <= 0) close = quote.path("ask").asDouble(0);
            if (close <= 0) return Optional.empty();

            double open  = quote.path("open").asDouble(close);
            double high  = quote.path("high").asDouble(close);
            double low   = quote.path("low").asDouble(close);
            long   vol   = quote.path("volume").asLong(0);

            return Optional.of(new Bar(Instant.now(), open, high, low, close, vol));
        } catch (Exception e) {
            logger.error("Failed to get latest bar for {} from Tradier", symbol, e);
            return Optional.empty();
        }
    }

    /**
     * Fetches historical bars.
     * timeframe: "1Min","5Min","15Min" → intraday timesales; "1Day" → daily history.
     */
    @Override
    public List<Bar> getBars(String symbol, String timeframe, int limit) throws Exception {
        if (timeframe == null || timeframe.equalsIgnoreCase("1Day")
                || timeframe.equalsIgnoreCase("1D")) {
            return getDailyBars(symbol, limit);
        }
        return getIntradayBars(symbol, timeframe, limit);
    }

    private List<Bar> getDailyBars(String symbol, int limit) throws Exception {
        LocalDate end   = LocalDate.now();
        LocalDate start = end.minusDays(limit * 2L); // buffer for weekends/holidays
        String url = baseUrl + "/markets/history?symbol=" + enc(symbol)
            + "&interval=daily"
            + "&start=" + start.format(DateTimeFormatter.ISO_DATE)
            + "&end="   + end.format(DateTimeFormatter.ISO_DATE);
        String json = sendGet(url);
        JsonNode root = objectMapper.readTree(json);
        JsonNode days = root.path("history").path("day");
        List<Bar> bars = new ArrayList<>();
        if (days.isArray()) {
            for (JsonNode d : days) {
                bars.add(parseDailyBar(d));
            }
        } else if (days.isObject()) {
            bars.add(parseDailyBar(days));
        }
        // Return last `limit` bars
        int from = Math.max(0, bars.size() - limit);
        return bars.subList(from, bars.size());
    }

    private Bar parseDailyBar(JsonNode d) {
        Instant ts    = Instant.parse(d.path("date").asText("1970-01-01") + "T00:00:00Z");
        double  open  = d.path("open").asDouble();
        double  high  = d.path("high").asDouble();
        double  low   = d.path("low").asDouble();
        double  close = d.path("close").asDouble();
        long    vol   = d.path("volume").asLong(0);
        return new Bar(ts, open, high, low, close, vol);
    }

    private List<Bar> getIntradayBars(String symbol, String timeframe, int limit) throws Exception {
        // Translate "1Min","5Min","15Min" → "1min","5min","15min"
        String interval = timeframe.toLowerCase().replace("min", "min");
        if (interval.equals("1min")) interval = "1min";
        else if (interval.equals("5min")) interval = "5min";
        else if (interval.equals("15min")) interval = "15min";
        else interval = "5min"; // default

        String url = baseUrl + "/markets/timesales?symbol=" + enc(symbol)
            + "&interval=" + interval;
        String json = sendGet(url);
        JsonNode root = objectMapper.readTree(json);
        JsonNode series = root.path("series").path("data");
        List<Bar> bars = new ArrayList<>();
        if (series.isArray()) {
            for (JsonNode d : series) {
                double close = d.path("close").asDouble();
                if (close > 0) {
                    bars.add(new Bar(
                        Instant.parse(d.path("time").asText().replace(" ", "T") + "Z"),
                        d.path("open").asDouble(close),
                        d.path("high").asDouble(close),
                        d.path("low").asDouble(close),
                        close,
                        d.path("volume").asLong(0)
                    ));
                }
            }
        }
        int from = Math.max(0, bars.size() - limit);
        return bars.subList(from, bars.size());
    }

    @Override
    public List<Bar> getMarketHistory(String symbol, int limit) throws Exception {
        return getDailyBars(symbol, limit);
    }

    // ── Orders ────────────────────────────────────────────────────────────────

    /**
     * Returns open orders for a symbol as a JsonNode array, using the same field structure
     * as Alpaca (id, type) so ProfileManager's cancel-before-sell logic works unchanged.
     */
    @Override
    public JsonNode getOpenOrders(String symbol) {
        try {
            String json = sendGet(baseUrl + "/accounts/" + accountId + "/orders");
            JsonNode root = objectMapper.readTree(json);
            JsonNode orders = root.path("orders").path("order");
            ArrayNode result = objectMapper.createArrayNode();

            if (orders.isArray()) {
                for (JsonNode o : orders) {
                    if (symbol.equalsIgnoreCase(o.path("symbol").asText())
                            && isOpenStatus(o.path("status").asText())) {
                        result.add(normalizeOrder(o));
                    }
                }
            } else if (orders.isObject()) {
                if (symbol.equalsIgnoreCase(orders.path("symbol").asText())
                        && isOpenStatus(orders.path("status").asText())) {
                    result.add(normalizeOrder(orders));
                }
            }
            return result;
        } catch (Exception e) {
            logger.error("Failed to get open orders for {} from Tradier", symbol, e);
            return objectMapper.createArrayNode();
        }
    }

    private boolean isOpenStatus(String status) {
        return "open".equalsIgnoreCase(status)
            || "pending".equalsIgnoreCase(status)
            || "partially_filled".equalsIgnoreCase(status);
    }

    private ObjectNode normalizeOrder(JsonNode o) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("id",   o.path("id").asText());
        node.put("type", o.path("type").asText());
        return node;
    }

    /**
     * Places an equity order via form-encoded POST.
     * Translates time_in_force → duration (day, gtc, pre, post).
     * Rounds qty to nearest whole share (Tradier cash accounts — no fractional).
     */
    @Override
    public void placeOrder(String symbol, double qty, String side, String type,
                           String timeInForce, Double limitPrice) {
        long wholeQty = Math.round(qty);
        if (wholeQty <= 0) {
            logger.warn("TradierClient.placeOrder: rounded qty {} → {} (skipping zero-qty order for {})",
                qty, wholeQty, symbol);
            return;
        }

        String duration = translateTimeInForce(timeInForce);
        String orderType = translateOrderType(type);

        StringBuilder form = new StringBuilder();
        form.append("class=equity")
            .append("&symbol=").append(enc(symbol))
            .append("&side=").append(enc(side))
            .append("&quantity=").append(wholeQty)
            .append("&type=").append(enc(orderType))
            .append("&duration=").append(enc(duration));

        if (limitPrice != null) {
            form.append("&price=").append(String.format("%.2f", limitPrice));
        }

        try {
            logger.info("Placing Tradier order: {} {} {} qty={} duration={}", side, orderType, symbol, wholeQty, duration);
            sendPost(baseUrl + "/accounts/" + accountId + "/orders", form.toString());
        } catch (Exception e) {
            logger.error("Failed to place Tradier order for {}", symbol, e);
            throw new RuntimeException("Tradier order placement failed", e);
        }
    }

    /** Translates Alpaca time_in_force values to Tradier duration values. */
    private String translateTimeInForce(String tif) {
        if (tif == null) return "day";
        return switch (tif.toLowerCase()) {
            case "gtc"  -> "gtc";
            case "opg"  -> "pre";
            case "cls"  -> "post";
            default     -> "day";
        };
    }

    /** Translates Alpaca order type values to Tradier order type values. */
    private String translateOrderType(String type) {
        if (type == null) return "market";
        return switch (type.toLowerCase()) {
            case "limit"       -> "limit";
            case "stop"        -> "stop";
            case "stop_limit"  -> "stop_limit";
            default            -> "market";
        };
    }

    @Override
    public void cancelOrder(String orderId) {
        try {
            logger.info("Cancelling Tradier order {}", orderId);
            sendDelete(baseUrl + "/accounts/" + accountId + "/orders/" + orderId);
        } catch (Exception e) {
            logger.error("Failed to cancel Tradier order {}", orderId, e);
            throw new RuntimeException("Tradier order cancellation failed", e);
        }
    }

    @Override
    public void cancelAllOrders() {
        try {
            String json = sendGet(baseUrl + "/accounts/" + accountId + "/orders");
            JsonNode root = objectMapper.readTree(json);
            JsonNode orders = root.path("orders").path("order");

            if (orders.isArray()) {
                for (JsonNode o : orders) {
                    if (isOpenStatus(o.path("status").asText())) {
                        cancelOrder(o.path("id").asText());
                    }
                }
            } else if (orders.isObject() && isOpenStatus(orders.path("status").asText())) {
                cancelOrder(orders.path("id").asText());
            }
        } catch (Exception e) {
            logger.error("Failed to cancel all Tradier orders", e);
            throw new RuntimeException("Tradier cancelAllOrders failed", e);
        }
    }

    @Override
    public void replaceOrder(String orderId, Double qty, Double limitPrice, Double stopPrice) {
        try {
            StringBuilder form = new StringBuilder();
            if (qty != null)        form.append("quantity=").append(Math.round(qty));
            if (limitPrice != null) {
                if (form.length() > 0) form.append("&");
                form.append("price=").append(String.format("%.2f", limitPrice));
            }
            if (stopPrice != null) {
                if (form.length() > 0) form.append("&");
                form.append("stop=").append(String.format("%.2f", stopPrice));
            }
            logger.info("Replacing Tradier order {}", orderId);
            sendPost(baseUrl + "/accounts/" + accountId + "/orders/" + orderId, form.toString());
        } catch (Exception e) {
            logger.error("Failed to replace Tradier order {}", orderId, e);
            throw new RuntimeException("Tradier replaceOrder failed", e);
        }
    }

    /**
     * Places a native stop-market sell order.
     * Maps directly to Tradier stop order with duration=gtc.
     */
    @Override
    public void placeNativeStopOrder(String symbol, double qty, double stopPrice) throws Exception {
        long wholeQty = Math.round(qty);
        String form = "class=equity"
            + "&symbol=" + enc(symbol)
            + "&side=sell"
            + "&quantity=" + wholeQty
            + "&type=stop"
            + "&duration=gtc"
            + "&stop=" + String.format("%.2f", stopPrice);
        logger.info("Placing Tradier native stop order: {} qty={} stop={}", symbol, wholeQty, stopPrice);
        sendPost(baseUrl + "/accounts/" + accountId + "/orders", form);
    }

    /**
     * Tradier does not universally support trailing stop orders.
     * Throw UnsupportedOperationException — callers should use client-side monitoring instead.
     */
    @Override
    public void placeTrailingStopOrder(String symbol, double qty, String side, double trailPercent) {
        throw new UnsupportedOperationException(
            "TradierClient does not support trailing stop orders. Use client-side SL/TP monitoring.");
    }

    /**
     * Tradier has no native bracket orders.
     * Returns withoutBracket() so ProfileManager falls back to client-side monitoring.
     */
    @Override
    public BracketOrderResult placeBracketOrder(String symbol, double qty, String side,
                                                double takeProfitPrice, double stopLossPrice,
                                                Double stopLossLimitPrice, Double limitPrice) {
        logger.warn("TradierClient: bracket orders not supported — placing simple {} order for {}. "
            + "Position requires client-side SL/TP monitoring.", side, symbol);
        placeOrder(symbol, qty, side, limitPrice != null ? "limit" : "market", "day", limitPrice);
        return BracketOrderResult.withoutBracket(symbol, qty);
    }

    @Override
    public String placeBracketOrder(String symbol, double qty, String side,
                                    double takeProfitPrice, double stopLossPrice,
                                    Double stopLossLimitPrice) throws Exception {
        BracketOrderResult result = placeBracketOrder(symbol, qty, side,
            takeProfitPrice, stopLossPrice, stopLossLimitPrice, null);
        return result.message();
    }

    // ── News / Order History / Account Activities ─────────────────────────────

    @Override
    public JsonNode getNews(String symbol, int limit) {
        // Tradier does not provide a news endpoint in the standard v1 API.
        logger.debug("TradierClient.getNews: not supported, returning empty array");
        return objectMapper.createArrayNode();
    }

    @Override
    public JsonNode getRecentOrders(String symbol) {
        try {
            String json = sendGet(baseUrl + "/accounts/" + accountId + "/orders");
            JsonNode root = objectMapper.readTree(json);
            JsonNode orders = root.path("orders").path("order");
            ArrayNode result = objectMapper.createArrayNode();
            if (orders.isArray()) {
                for (JsonNode o : orders) {
                    if (symbol.equalsIgnoreCase(o.path("symbol").asText())) {
                        result.add(o);
                        if (result.size() >= 5) break;
                    }
                }
            } else if (orders.isObject()
                    && symbol.equalsIgnoreCase(orders.path("symbol").asText())) {
                result.add(orders);
            }
            return result;
        } catch (Exception e) {
            logger.error("Failed to get recent Tradier orders for {}", symbol, e);
            return objectMapper.createArrayNode();
        }
    }

    @Override
    public JsonNode getOrderHistory(String symbol, int limit) {
        return getRecentOrders(symbol);
    }

    @Override
    public JsonNode getAccountActivities(String activityType, int limit) {
        // Tradier gain/loss history approximates account activities
        try {
            String json = sendGet(baseUrl + "/accounts/" + accountId + "/gainloss");
            return objectMapper.readTree(json);
        } catch (Exception e) {
            logger.error("Failed to get Tradier account activities", e);
            return objectMapper.createArrayNode();
        }
    }
}
