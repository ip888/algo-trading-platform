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
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Tradovate broker integration implementing BrokerClient.
 *
 * Uses the Tradovate REST API v1:
 *   Live: https://live.tradovate.com/v1/
 *   Demo: https://demo.tradovate.com/v1/
 *
 * Authentication: POST /auth/accesstokenrequest → Bearer token.
 * On 401, re-authenticates once and retries.
 *
 * Equity symbols (SPY, QQQ, …) are automatically mapped to front-month
 * micro-futures contracts via FuturesSymbolMapper.
 */
public final class TradovateClient implements BrokerClient {
    private static final Logger logger = LoggerFactory.getLogger(TradovateClient.class);

    private static final String LIVE_BASE = "https://live.tradovate.com/v1";
    private static final String DEMO_BASE = "https://demo.tradovate.com/v1";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final String username;
    private final String password;
    private final String appId;
    private final String appSecret;
    private final AtomicReference<String> accessToken = new AtomicReference<>("");

    public TradovateClient(Config config) {
        this.username  = config.getTradovateUsername();
        this.password  = config.getTradovatePassword();
        this.appId     = config.getTradovateAppId();
        this.appSecret = config.getTradovateAppSecret();
        this.baseUrl   = config.isTradovateDemo() ? DEMO_BASE : LIVE_BASE;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(REQUEST_TIMEOUT)
            .build();
        this.objectMapper = new ObjectMapper();

        logger.info("TradovateClient initialized — baseUrl={}, user={}",
            baseUrl, username.isBlank() ? "<not set>" : username);

        try {
            authenticate();
        } catch (Exception e) {
            logger.warn("TradovateClient: initial authentication failed — will retry on first request: {}", e.getMessage());
        }
    }

    // ── Authentication ────────────────────────────────────────────────────────

    private void authenticate() throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("name",       username);
        body.put("password",   password);
        body.put("appId",      appId);
        body.put("appVersion", "1.0");
        body.put("cid",        appId);
        body.put("sec",        appSecret);

        String json = sendPostJson(baseUrl + "/auth/accesstokenrequest", body.toString(), false);
        JsonNode resp = objectMapper.readTree(json);
        String token = resp.path("accessToken").asText("");
        if (token.isBlank()) {
            throw new RuntimeException("Tradovate authentication failed — no accessToken in response: " + json);
        }
        accessToken.set(token);
        logger.info("TradovateClient: authenticated successfully");
    }

    // ── HTTP helpers ──────────────────────────────────────────────────────────

    private String sendGet(String url) throws Exception {
        return sendGetWithRetry(url, true);
    }

    private String sendGetWithRetry(String url, boolean retryOn401) throws Exception {
        var req = HttpRequest.newBuilder(URI.create(url))
            .header("Authorization", "Bearer " + accessToken.get())
            .header("Accept", "application/json")
            .timeout(REQUEST_TIMEOUT)
            .GET()
            .build();
        var resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() == 401 && retryOn401) {
            logger.warn("TradovateClient: 401 on GET {}, re-authenticating…", url);
            authenticate();
            return sendGetWithRetry(url, false);
        }
        if (resp.statusCode() >= 400) {
            throw new RuntimeException("Tradovate GET failed [" + resp.statusCode() + "]: " + resp.body());
        }
        return resp.body();
    }

    private String sendPostJson(String url, String jsonBody, boolean retryOn401) throws Exception {
        var req = HttpRequest.newBuilder(URI.create(url))
            .header("Authorization", "Bearer " + accessToken.get())
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .timeout(REQUEST_TIMEOUT)
            .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
            .build();
        var resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() == 401 && retryOn401) {
            logger.warn("TradovateClient: 401 on POST {}, re-authenticating…", url);
            authenticate();
            return sendPostJson(url, jsonBody, false);
        }
        if (resp.statusCode() >= 400) {
            throw new RuntimeException("Tradovate POST failed [" + resp.statusCode() + "]: " + resp.body());
        }
        return resp.body();
    }

    private String sendPost(String url, String jsonBody) throws Exception {
        return sendPostJson(url, jsonBody, true);
    }

    // ── Symbol mapping helper ─────────────────────────────────────────────────

    private String toFutures(String symbol) {
        if (FuturesSymbolMapper.isMappedSymbol(symbol)) {
            return FuturesSymbolMapper.toFuturesSymbol(symbol, LocalDate.now());
        }
        return symbol; // pass through unmapped symbols (already a futures symbol)
    }

    private String fromFutures(String futuresSymbol) {
        return FuturesSymbolMapper.toEquitySymbol(futuresSymbol).orElse(futuresSymbol);
    }

    // ── Account ───────────────────────────────────────────────────────────────

    @Override
    public JsonNode getAccount() throws Exception {
        String json = sendGet(baseUrl + "/account/list");
        JsonNode arr = objectMapper.readTree(json);
        JsonNode acct = arr.isArray() && arr.size() > 0 ? arr.get(0) : arr;

        ObjectNode result = objectMapper.createObjectNode();
        result.put("equity",       acct.path("netLiquidatingValue").asDouble(0));
        result.put("buying_power", acct.path("initialMargin").asDouble(0));
        result.put("cash",         acct.path("totalCashValue").asDouble(0));
        result.put("currency",     acct.path("currency").asText("USD"));
        result.put("status",       acct.path("active").asBoolean(false) ? "ACTIVE" : "INACTIVE");
        result.put("trading_blocked",   false);
        result.put("account_blocked",   false);
        result.put("pattern_day_trader", false);
        return result;
    }

    @Override
    public boolean validateAccountForTrading() {
        try {
            JsonNode account = getAccount();
            boolean active = "ACTIVE".equals(account.path("status").asText());
            double equity  = account.path("equity").asDouble(0);
            if (!active) {
                logger.warn("TradovateClient.validateAccountForTrading: account is not active");
                return false;
            }
            if (equity <= 0) {
                logger.warn("TradovateClient.validateAccountForTrading: insufficient margin (equity={})", equity);
                return false;
            }
            return true;
        } catch (Exception e) {
            logger.error("Failed to validate Tradovate account: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Synthesizes a clock response based on current time and CME Globex market hours.
     */
    @Override
    public JsonNode getClock() throws Exception {
        ZonedDateTime now = ZonedDateTime.now(FuturesMarketHours.CT);
        boolean open = FuturesMarketHours.isOpen(now);

        ObjectNode clock = objectMapper.createObjectNode();
        clock.put("is_open", open);
        clock.put("next_open",  FuturesMarketHours.nextOpen(now).toInstant().toString());
        clock.put("next_close", FuturesMarketHours.nextClose(now).toInstant().toString());
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
            logger.error("Failed to get Tradovate position for {}", symbol, e);
            return Optional.empty();
        }
    }

    @Override
    public List<Position> getPositions() throws Exception {
        String json = sendGet(baseUrl + "/position/list");
        JsonNode arr = objectMapper.readTree(json);
        List<Position> result = new ArrayList<>();
        if (arr.isArray()) {
            for (JsonNode p : arr) {
                double netPos = p.path("netPos").asDouble(0);
                if (netPos == 0) continue; // skip flat positions
                String futuresSym = p.path("contractId").path("name").asText(
                    p.path("contract").path("name").asText(""));
                String equitySym = fromFutures(futuresSym);
                double avgPrice  = p.path("netPrice").asDouble(0);
                double marketVal = netPos * avgPrice;
                result.add(new Position(equitySym, netPos, marketVal, avgPrice, 0.0));
            }
        }
        return result;
    }

    // ── Market Data ───────────────────────────────────────────────────────────

    @Override
    public Optional<Bar> getLatestBar(String symbol) {
        try {
            List<Bar> bars = getBars(symbol, "1Min", 1);
            return bars.isEmpty() ? Optional.empty() : Optional.of(bars.get(bars.size() - 1));
        } catch (Exception e) {
            logger.error("Failed to get latest bar for {} from Tradovate", symbol, e);
            return Optional.empty();
        }
    }

    @Override
    public List<Bar> getBars(String symbol, String timeframe, int limit) throws Exception {
        String futuresSym = toFutures(symbol);
        boolean isDaily = timeframe == null
            || timeframe.equalsIgnoreCase("1Day")
            || timeframe.equalsIgnoreCase("1D");
        int elementSize = isDaily ? 1440 : 1;

        ObjectNode chartDesc = objectMapper.createObjectNode();
        chartDesc.put("underlyingType", "MinuteBar");
        chartDesc.put("elementSize", elementSize);
        chartDesc.put("elementSizeUnit", "UnderlyingUnits");
        chartDesc.put("withHistogram", false);

        ObjectNode timeRange = objectMapper.createObjectNode();
        timeRange.put("lastNDays", isDaily ? Math.max(1, limit / 252 + 1) : 1);

        ObjectNode req = objectMapper.createObjectNode();
        req.put("symbol", futuresSym);
        req.set("chartDescription", chartDesc);
        req.set("timeRange", timeRange);

        String json = sendPost(baseUrl + "/md/getChart", req.toString());
        JsonNode root = objectMapper.readTree(json);
        JsonNode bars = root.path("charts");
        if (!bars.isArray()) bars = root.path("historicalData").path("bars");
        if (!bars.isArray()) bars = root; // fallback: top-level array

        List<Bar> result = new ArrayList<>();
        if (bars.isArray()) {
            for (JsonNode b : bars) {
                double close = b.path("close").asDouble(0);
                if (close <= 0) continue;
                Instant ts = Instant.ofEpochMilli(b.path("timestamp").asLong(0));
                result.add(new Bar(
                    ts,
                    b.path("open").asDouble(close),
                    b.path("high").asDouble(close),
                    b.path("low").asDouble(close),
                    close,
                    b.path("upVolume").asLong(0) + b.path("downVolume").asLong(0)
                ));
            }
        }
        int from = Math.max(0, result.size() - limit);
        return result.subList(from, result.size());
    }

    @Override
    public List<Bar> getMarketHistory(String symbol, int limit) throws Exception {
        return getBars(symbol, "1Day", limit);
    }

    // ── Orders ────────────────────────────────────────────────────────────────

    @Override
    public JsonNode getOpenOrders(String symbol) {
        try {
            String futuresSym = toFutures(symbol);
            String json = sendGet(baseUrl + "/order/list");
            JsonNode arr = objectMapper.readTree(json);
            ArrayNode result = objectMapper.createArrayNode();
            if (arr.isArray()) {
                for (JsonNode o : arr) {
                    String orderSym = o.path("contractId").path("name").asText(
                        o.path("contract").path("name").asText(""));
                    String status = o.path("ordStatus").asText("");
                    if (futuresSym.equalsIgnoreCase(orderSym) && isWorkingStatus(status)) {
                        result.add(normalizeOrder(o));
                    }
                }
            }
            return result;
        } catch (Exception e) {
            logger.error("Failed to get open orders for {} from Tradovate", symbol, e);
            return objectMapper.createArrayNode();
        }
    }

    private boolean isWorkingStatus(String status) {
        return "Working".equalsIgnoreCase(status)
            || "PendingNew".equalsIgnoreCase(status)
            || "PartiallyFilled".equalsIgnoreCase(status);
    }

    private ObjectNode normalizeOrder(JsonNode o) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("id",   o.path("id").asText());
        node.put("type", o.path("orderType").asText());
        return node;
    }

    @Override
    public void placeOrder(String symbol, double qty, String side, String type,
                           String timeInForce, Double limitPrice) {
        try {
            String futuresSym = toFutures(symbol);
            long wholeQty = Math.max(1, Math.round(Math.abs(qty)));
            String action = side.equalsIgnoreCase("buy") ? "Buy" : "Sell";
            String orderType = translateOrderType(type);

            ObjectNode body = buildOrderBody(futuresSym, action, wholeQty, orderType, limitPrice, null, null);
            logger.info("Placing Tradovate order: {} {} {} qty={}", action, orderType, futuresSym, wholeQty);
            sendPost(baseUrl + "/order/placeorder", body.toString());
        } catch (Exception e) {
            logger.error("Failed to place Tradovate order for {}", symbol, e);
            throw new RuntimeException("Tradovate order placement failed", e);
        }
    }

    @Override
    public void placeNativeStopOrder(String symbol, double qty, double stopPrice) throws Exception {
        String futuresSym = toFutures(symbol);
        long wholeQty = Math.max(1, Math.round(Math.abs(qty)));
        ObjectNode body = buildOrderBody(futuresSym, "Sell", wholeQty, "Stop", null, stopPrice, null);
        logger.info("Placing Tradovate native stop order: {} qty={} stop={}", futuresSym, wholeQty, stopPrice);
        sendPost(baseUrl + "/order/placeorder", body.toString());
    }

    @Override
    public void placeTrailingStopOrder(String symbol, double qty, String side, double trailPercent) {
        try {
            String futuresSym = toFutures(symbol);
            long wholeQty = Math.max(1, Math.round(Math.abs(qty)));
            String action = side.equalsIgnoreCase("buy") ? "Buy" : "Sell";

            ObjectNode body = buildOrderBody(futuresSym, action, wholeQty, "TrailingStop", null, null, trailPercent);
            logger.info("Placing Tradovate trailing stop order: {} {} qty={} trail={}%",
                action, futuresSym, wholeQty, trailPercent);
            sendPost(baseUrl + "/order/placeorder", body.toString());
        } catch (Exception e) {
            logger.error("Failed to place Tradovate trailing stop for {}", symbol, e);
            throw new RuntimeException("Tradovate trailing stop order failed", e);
        }
    }

    private ObjectNode buildOrderBody(String futuresSym, String action, long qty,
                                      String orderType, Double limitPrice,
                                      Double stopPrice, Double trailingStop) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("action",      action);
        body.put("symbol",      futuresSym);
        body.put("orderQty",    qty);
        body.put("orderType",   orderType);
        body.put("timeInForce", "DAY");
        if (limitPrice  != null) body.put("price",        limitPrice);
        if (stopPrice   != null) body.put("stopPrice",    stopPrice);
        if (trailingStop != null) body.put("trailingStop", trailingStop);
        return body;
    }

    private String translateOrderType(String type) {
        if (type == null) return "Market";
        return switch (type.toLowerCase()) {
            case "limit"      -> "Limit";
            case "stop"       -> "Stop";
            case "stop_limit" -> "StopLimit";
            default           -> "Market";
        };
    }

    /**
     * Tradovate has no native bracket orders for micro futures.
     * Places a simple order and returns withoutBracket() so the caller falls back to
     * client-side SL/TP monitoring.
     */
    @Override
    public BracketOrderResult placeBracketOrder(String symbol, double qty, String side,
                                                double takeProfitPrice, double stopLossPrice,
                                                Double stopLossLimitPrice, Double limitPrice) {
        logger.warn("TradovateClient: bracket orders not supported — placing simple {} order for {}. "
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

    @Override
    public void cancelOrder(String orderId) {
        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("orderId", Long.parseLong(orderId));
            logger.info("Cancelling Tradovate order {}", orderId);
            sendPost(baseUrl + "/order/cancelorder", body.toString());
        } catch (NumberFormatException e) {
            // orderId may be a string id
            try {
                ObjectNode body = objectMapper.createObjectNode();
                body.put("orderId", orderId);
                sendPost(baseUrl + "/order/cancelorder", body.toString());
            } catch (Exception ex) {
                logger.error("Failed to cancel Tradovate order {}", orderId, ex);
                throw new RuntimeException("Tradovate order cancellation failed", ex);
            }
        } catch (Exception e) {
            logger.error("Failed to cancel Tradovate order {}", orderId, e);
            throw new RuntimeException("Tradovate order cancellation failed", e);
        }
    }

    @Override
    public void cancelAllOrders() {
        try {
            String json = sendGet(baseUrl + "/order/list");
            JsonNode arr = objectMapper.readTree(json);
            if (arr.isArray()) {
                for (JsonNode o : arr) {
                    if (isWorkingStatus(o.path("ordStatus").asText(""))) {
                        cancelOrder(o.path("id").asText());
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Failed to cancel all Tradovate orders", e);
            throw new RuntimeException("Tradovate cancelAllOrders failed", e);
        }
    }

    @Override
    public void replaceOrder(String orderId, Double qty, Double limitPrice, Double stopPrice) {
        throw new UnsupportedOperationException(
            "TradovateClient does not support order replacement. Cancel and re-place instead.");
    }

    // ── News / Order History / Account Activities ─────────────────────────────

    @Override
    public JsonNode getNews(String symbol, int limit) {
        throw new UnsupportedOperationException("TradovateClient does not support news.");
    }

    @Override
    public JsonNode getRecentOrders(String symbol) {
        try {
            String futuresSym = toFutures(symbol);
            String json = sendGet(baseUrl + "/order/list");
            JsonNode arr = objectMapper.readTree(json);
            ArrayNode result = objectMapper.createArrayNode();
            if (arr.isArray()) {
                for (JsonNode o : arr) {
                    String orderSym = o.path("contractId").path("name").asText(
                        o.path("contract").path("name").asText(""));
                    if (futuresSym.equalsIgnoreCase(orderSym)) {
                        result.add(o);
                        if (result.size() >= 5) break;
                    }
                }
            }
            return result;
        } catch (Exception e) {
            logger.error("Failed to get recent Tradovate orders for {}", symbol, e);
            return objectMapper.createArrayNode();
        }
    }

    @Override
    public JsonNode getOrderHistory(String symbol, int limit) {
        return getRecentOrders(symbol);
    }

    @Override
    public JsonNode getAccountActivities(String activityType, int limit) {
        throw new UnsupportedOperationException("TradovateClient does not support account activities.");
    }
}
