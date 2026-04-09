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

import java.io.Closeable;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * IBKR (Interactive Brokers) Client Portal Web API integration implementing BrokerClient.
 *
 * Uses the IBKR Web API (OAuth 2.0) at https://api.ibkr.com/v1/api with a pre-obtained
 * access token from env var IBKR_ACCESS_TOKEN.
 *
 * Key notes:
 * - All equity orders require a numeric contract ID (conid). Symbols are resolved to conids
 *   on first use and cached in {@link #conidCache}.
 * - IBKR order confirmations may require a second POST to /iserver/reply/{replyId}.
 * - A background thread calls POST /tickle every 55 seconds to keep the session alive.
 * - Native bracket orders are not supported; {@link BracketOrderResult#withoutBracket} is returned.
 */
public final class IBKRClient implements BrokerClient, Closeable {
    private static final Logger logger = LoggerFactory.getLogger(IBKRClient.class);

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);
    private static final ZoneId ET = ZoneId.of("America/New_York");

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    final String baseUrl;
    private final String accessToken;
    private final String accountId;

    /** Symbol → IBKR contract ID cache. Thread-safe for concurrent strategy threads. */
    final ConcurrentHashMap<String, Long> conidCache = new ConcurrentHashMap<>();

    /** Background executor that sends /tickle keepalive requests every 55 seconds. */
    private final ScheduledExecutorService keepaliveExecutor;

    public IBKRClient(Config config) {
        this.accessToken = config.getIBKRAccessToken();
        this.accountId   = config.getIBKRAccountId();
        this.baseUrl     = config.getIBKRBaseUrl();
        this.httpClient  = HttpClient.newBuilder()
            .connectTimeout(REQUEST_TIMEOUT)
            .build();
        this.objectMapper = new ObjectMapper();

        // Start session keepalive thread
        this.keepaliveExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ibkr-keepalive");
            t.setDaemon(true);
            return t;
        });
        keepaliveExecutor.scheduleAtFixedRate(this::tickle, 55, 55, TimeUnit.SECONDS);

        logger.info("IBKRClient initialized — baseUrl={}, accountId={}",
            baseUrl,
            accountId.isBlank() ? "<not set>" : accountId.substring(0, Math.min(4, accountId.length())) + "****");
    }

    // ── Session keepalive ─────────────────────────────────────────────────────

    private void tickle() {
        try {
            sendPost(baseUrl + "/tickle", "{}");
            logger.debug("IBKR session tickle sent");
        } catch (Exception e) {
            logger.warn("IBKR tickle failed: {}", e.getMessage());
        }
    }

    @Override
    public void close() {
        keepaliveExecutor.shutdownNow();
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
            throw new RuntimeException("IBKR GET failed [" + resp.statusCode() + "]: " + resp.body());
        }
        return resp.body();
    }

    private String sendPost(String url, String jsonBody) throws Exception {
        var req = HttpRequest.newBuilder(URI.create(url))
            .header("Authorization", "Bearer " + accessToken)
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .timeout(REQUEST_TIMEOUT)
            .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
            .build();
        var resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() >= 400) {
            throw new RuntimeException("IBKR POST failed [" + resp.statusCode() + "]: " + resp.body());
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
            throw new RuntimeException("IBKR DELETE failed [" + resp.statusCode() + "]: " + resp.body());
        }
        return resp.body();
    }

    // ── Symbol → conid resolution ─────────────────────────────────────────────

    /**
     * Resolves a stock symbol to an IBKR contract ID (conid).
     * Results are cached in {@link #conidCache} to avoid repeated API calls.
     */
    private long resolveConid(String symbol) throws Exception {
        Long cached = conidCache.get(symbol);
        if (cached != null) return cached;

        String url = baseUrl + "/iserver/secdef/search?symbol=" + symbol
            + "&secType=STK&exchange=SMART";
        String json = sendGet(url);
        JsonNode root = objectMapper.readTree(json);

        long conid;
        if (root.isArray() && root.size() > 0) {
            conid = root.get(0).path("conid").asLong(0);
        } else {
            conid = root.path("conid").asLong(0);
        }

        if (conid <= 0) {
            throw new RuntimeException("IBKR: could not resolve conid for symbol " + symbol);
        }

        conidCache.put(symbol, conid);
        logger.debug("Resolved conid for {}: {}", symbol, conid);
        return conid;
    }

    // ── Account ───────────────────────────────────────────────────────────────

    /**
     * Returns account summary normalized to a structure compatible with other clients:
     * { equity, buying_power, cash, status, trading_blocked, account_blocked, pattern_day_trader }
     */
    @Override
    public JsonNode getAccount() throws Exception {
        String json = sendGet(baseUrl + "/portfolio/" + accountId + "/summary");
        JsonNode root = objectMapper.readTree(json);

        ObjectNode result = objectMapper.createObjectNode();
        result.put("equity",             getIBKRAmount(root, "netliquidation"));
        result.put("buying_power",       getIBKRAmount(root, "availablefunds"));
        result.put("cash",               getIBKRAmount(root, "availablefunds"));
        result.put("status",             "ACTIVE");
        result.put("trading_blocked",    false);
        result.put("account_blocked",    false);
        result.put("pattern_day_trader", false);
        return result;
    }

    /**
     * Extracts a numeric amount from IBKR's portfolio summary response.
     * IBKR returns fields as objects: { "amount": 12345.67, "currency": "USD", ... }
     */
    private double getIBKRAmount(JsonNode root, String field) {
        JsonNode node = root.path(field);
        if (node.isObject()) {
            return node.path("amount").asDouble(0);
        }
        return node.asDouble(0);
    }

    @Override
    public boolean validateAccountForTrading() {
        try {
            String json = sendGet(baseUrl + "/iserver/auth/status");
            JsonNode root = objectMapper.readTree(json);
            boolean authenticated = root.path("authenticated").asBoolean(false);
            if (!authenticated) {
                logger.warn("IBKR auth status: not authenticated");
                return false;
            }
            JsonNode account = getAccount();
            return account.path("equity").asDouble(0) > 0;
        } catch (Exception e) {
            logger.error("Failed to validate IBKR account: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Synthesizes a market clock based on US equity market hours (9:30 AM–4:00 PM ET, Mon–Fri).
     */
    @Override
    public JsonNode getClock() throws Exception {
        ZonedDateTime now = ZonedDateTime.now(ET);
        boolean isOpen = isMarketOpen(now);

        ObjectNode clock = objectMapper.createObjectNode();
        clock.put("is_open", isOpen);
        clock.put("next_open", isOpen ? "" : nextOpenTime(now).toString());
        clock.put("next_close", isOpen ? nextCloseTime(now).toString() : "");
        return clock;
    }

    static boolean isMarketOpen(ZonedDateTime now) {
        DayOfWeek day = now.getDayOfWeek();
        if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) return false;
        LocalTime time = now.toLocalTime();
        LocalTime marketOpen  = LocalTime.of(9, 30);
        LocalTime marketClose = LocalTime.of(16, 0);
        return !time.isBefore(marketOpen) && time.isBefore(marketClose);
    }

    private static ZonedDateTime nextOpenTime(ZonedDateTime from) {
        ZonedDateTime candidate = from.toLocalDate().atTime(9, 30).atZone(ET);
        if (!candidate.isAfter(from)) candidate = candidate.plusDays(1);
        while (candidate.getDayOfWeek() == DayOfWeek.SATURDAY
            || candidate.getDayOfWeek() == DayOfWeek.SUNDAY) {
            candidate = candidate.plusDays(1);
        }
        return candidate;
    }

    private static ZonedDateTime nextCloseTime(ZonedDateTime from) {
        ZonedDateTime candidate = from.toLocalDate().atTime(16, 0).atZone(ET);
        if (!candidate.isAfter(from)) candidate = candidate.plusDays(1);
        while (candidate.getDayOfWeek() == DayOfWeek.SATURDAY
            || candidate.getDayOfWeek() == DayOfWeek.SUNDAY) {
            candidate = candidate.plusDays(1);
        }
        return candidate;
    }

    // ── Positions ─────────────────────────────────────────────────────────────

    @Override
    public Optional<Position> getPosition(String symbol) {
        try {
            return getPositions().stream()
                .filter(p -> symbol.equalsIgnoreCase(p.symbol()))
                .findFirst();
        } catch (Exception e) {
            logger.error("Failed to get IBKR position for {}", symbol, e);
            return Optional.empty();
        }
    }

    @Override
    public List<Position> getPositions() throws Exception {
        String json = sendGet(baseUrl + "/portfolio/" + accountId + "/positions/0");
        JsonNode root = objectMapper.readTree(json);
        List<Position> result = new ArrayList<>();

        if (root.isArray()) {
            for (JsonNode p : root) {
                String sym   = p.path("ticker").asText(p.path("contractDesc").asText());
                double qty   = p.path("position").asDouble();
                double avg   = p.path("avgCost").asDouble();
                double cost  = qty * avg;
                double unrealized = p.path("unrealizedPnl").asDouble(0);
                if (!sym.isBlank()) {
                    result.add(new Position(sym, qty, cost, avg, unrealized));
                    // Populate conid cache from position data
                    long conid = p.path("conid").asLong(0);
                    if (conid > 0) conidCache.put(sym, conid);
                }
            }
        }
        return result;
    }

    // ── Market Data ───────────────────────────────────────────────────────────

    /**
     * Gets the latest snapshot quote and synthesizes a Bar.
     * IBKR snapshot fields: 31=last, 84=bid, 86=ask, 7295=open, 7296=high, 7293=low, 7282=volume.
     */
    @Override
    public Optional<Bar> getLatestBar(String symbol) {
        try {
            long conid = resolveConid(symbol);
            // Request snapshot with relevant fields
            String url = baseUrl + "/iserver/marketdata/snapshot?conids=" + conid
                + "&fields=31,84,86,7295,7296,7293,7282";
            String json = sendGet(url);
            JsonNode root = objectMapper.readTree(json);

            JsonNode snap = root.isArray() && root.size() > 0 ? root.get(0) : root;

            double last   = parseField(snap, "31");
            double bid    = parseField(snap, "84");
            double ask    = parseField(snap, "86");
            double open   = parseField(snap, "7295");
            double high   = parseField(snap, "7296");
            double low    = parseField(snap, "7293");
            long   volume = (long) parseField(snap, "7282");

            double close = last > 0 ? last : (bid + ask) / 2.0;
            if (close <= 0) return Optional.empty();
            if (open  <= 0) open  = close;
            if (high  <= 0) high  = close;
            if (low   <= 0) low   = close;

            return Optional.of(new Bar(Instant.now(), open, high, low, close, volume));
        } catch (Exception e) {
            logger.error("Failed to get latest bar for {} from IBKR", symbol, e);
            return Optional.empty();
        }
    }

    private double parseField(JsonNode node, String field) {
        JsonNode f = node.path(field);
        if (f.isNumber()) return f.asDouble(0);
        if (f.isTextual()) {
            String text = f.asText("").replaceAll("[^0-9.]", "");
            try { return Double.parseDouble(text); } catch (NumberFormatException ignored) {}
        }
        return 0.0;
    }

    @Override
    public List<Bar> getBars(String symbol, String timeframe, int limit) throws Exception {
        long conid = resolveConid(symbol);
        boolean intraday = timeframe != null
            && !timeframe.equalsIgnoreCase("1Day")
            && !timeframe.equalsIgnoreCase("1D");
        String barSize = intraday ? "5min" : "1d";
        int days = intraday ? 1 : Math.max(1, limit / 5 + 1); // rough day estimate
        String period = days + "d";

        String url = baseUrl + "/iserver/marketdata/history?conid=" + conid
            + "&period=" + period + "&bar=" + barSize;
        String json = sendGet(url);
        JsonNode root = objectMapper.readTree(json);
        JsonNode data = root.path("data");

        List<Bar> bars = new ArrayList<>();
        if (data.isArray()) {
            for (JsonNode d : data) {
                double o = d.path("o").asDouble(0);
                double h = d.path("h").asDouble(0);
                double l = d.path("l").asDouble(0);
                double c = d.path("c").asDouble(0);
                long   v = d.path("v").asLong(0);
                long   t = d.path("t").asLong(0);
                Instant ts = t > 0 ? Instant.ofEpochMilli(t) : Instant.now();
                if (c > 0) bars.add(new Bar(ts, o, h, l, c, v));
            }
        }
        int from = Math.max(0, bars.size() - limit);
        return bars.subList(from, bars.size());
    }

    @Override
    public List<Bar> getMarketHistory(String symbol, int limit) throws Exception {
        return getBars(symbol, "1Day", limit);
    }

    // ── Orders ────────────────────────────────────────────────────────────────

    /**
     * Places an IBKR order.
     * Handles the IBKR "confirmation required" response by auto-confirming via /iserver/reply/{id}.
     */
    @Override
    public void placeOrder(String symbol, double qty, String side, String type,
                           String timeInForce, Double limitPrice) {
        try {
            long conid = resolveConid(symbol);
            long wholeQty = Math.round(qty);
            if (wholeQty <= 0) {
                logger.warn("IBKRClient.placeOrder: rounded qty {} → {} (skipping zero-qty order for {})",
                    qty, wholeQty, symbol);
                return;
            }

            String ibkrSide  = "buy".equalsIgnoreCase(side) ? "BUY" : "SELL";
            String ibkrType  = translateOrderType(type);
            String ibkrTif   = translateTimeInForce(timeInForce);

            ObjectNode order = objectMapper.createObjectNode();
            order.put("conid",     conid);
            order.put("orderType", ibkrType);
            order.put("side",      ibkrSide);
            order.put("quantity",  wholeQty);
            order.put("tif",       ibkrTif);
            if (limitPrice != null && (ibkrType.equals("LMT") || ibkrType.equals("STP LMT"))) {
                order.put("price", limitPrice);
            }
            if (limitPrice != null && ibkrType.equals("STP")) {
                order.put("auxPrice", limitPrice);
            }

            logger.info("Placing IBKR order: {} {} {} qty={} tif={}", ibkrSide, ibkrType, symbol, wholeQty, ibkrTif);
            String responseBody = sendPost(baseUrl + "/iserver/order/" + accountId,
                objectMapper.writeValueAsString(order));

            handleOrderConfirmation(responseBody);
        } catch (Exception e) {
            logger.error("Failed to place IBKR order for {}", symbol, e);
            throw new RuntimeException("IBKR order placement failed", e);
        }
    }

    /**
     * IBKR may respond with a confirmation request array.
     * Auto-confirms by POSTing {"confirmed": true} to /iserver/reply/{replyId}.
     */
    private void handleOrderConfirmation(String responseBody) throws Exception {
        JsonNode resp = objectMapper.readTree(responseBody);
        // IBKR returns an array on confirmation required: [{"id": "...", "message": [...]}]
        if (resp.isArray()) {
            for (JsonNode item : resp) {
                String replyId = item.path("id").asText();
                if (!replyId.isEmpty() && item.has("message")) {
                    logger.debug("IBKR order requires confirmation (replyId={}), auto-confirming", replyId);
                    String confirm = "{\"confirmed\": true}";
                    sendPost(baseUrl + "/iserver/reply/" + replyId, confirm);
                }
            }
        }
    }

    private String translateOrderType(String type) {
        if (type == null) return "MKT";
        return switch (type.toLowerCase()) {
            case "limit"      -> "LMT";
            case "stop"       -> "STP";
            case "stop_limit" -> "STP LMT";
            default           -> "MKT";
        };
    }

    private String translateTimeInForce(String tif) {
        if (tif == null) return "DAY";
        return switch (tif.toLowerCase()) {
            case "gtc" -> "GTC";
            default    -> "DAY";
        };
    }

    @Override
    public void cancelOrder(String orderId) {
        try {
            logger.info("Cancelling IBKR order {}", orderId);
            sendDelete(baseUrl + "/iserver/order/" + accountId + "/" + orderId);
        } catch (Exception e) {
            logger.error("Failed to cancel IBKR order {}", orderId, e);
            throw new RuntimeException("IBKR order cancellation failed", e);
        }
    }

    @Override
    public void cancelAllOrders() {
        try {
            String json = sendGet(baseUrl + "/iserver/account/orders");
            JsonNode root = objectMapper.readTree(json);
            JsonNode orders = root.path("orders");
            if (orders.isArray()) {
                for (JsonNode o : orders) {
                    String status = o.path("status").asText();
                    if (isOpenStatus(status)) {
                        cancelOrder(o.path("orderId").asText());
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Failed to cancel all IBKR orders", e);
            throw new RuntimeException("IBKR cancelAllOrders failed", e);
        }
    }

    private boolean isOpenStatus(String status) {
        if (status == null) return false;
        return switch (status.toUpperCase()) {
            case "SUBMITTED", "PRESUBMITTED", "PARTIALLY_FILLED", "ACTIVE" -> true;
            default -> false;
        };
    }

    @Override
    public void replaceOrder(String orderId, Double qty, Double limitPrice, Double stopPrice) {
        try {
            ObjectNode body = objectMapper.createObjectNode();
            if (qty        != null) body.put("quantity", Math.round(qty));
            if (limitPrice != null) body.put("price",    limitPrice);
            if (stopPrice  != null) body.put("auxPrice", stopPrice);
            logger.info("Replacing IBKR order {}", orderId);
            sendPost(baseUrl + "/iserver/order/" + accountId + "/" + orderId,
                objectMapper.writeValueAsString(body));
        } catch (Exception e) {
            logger.error("Failed to replace IBKR order {}", orderId, e);
            throw new RuntimeException("IBKR replaceOrder failed", e);
        }
    }

    @Override
    public void placeNativeStopOrder(String symbol, double qty, double stopPrice) throws Exception {
        long conid    = resolveConid(symbol);
        long wholeQty = Math.round(qty);

        ObjectNode order = objectMapper.createObjectNode();
        order.put("conid",     conid);
        order.put("orderType", "STP");
        order.put("side",      "SELL");
        order.put("quantity",  wholeQty);
        order.put("tif",       "GTC");
        order.put("auxPrice",  stopPrice);

        logger.info("Placing IBKR native stop order: {} qty={} stop={}", symbol, wholeQty, stopPrice);
        String responseBody = sendPost(baseUrl + "/iserver/order/" + accountId,
            objectMapper.writeValueAsString(order));
        handleOrderConfirmation(responseBody);
    }

    @Override
    public void placeTrailingStopOrder(String symbol, double qty, String side, double trailPercent) {
        try {
            long conid    = resolveConid(symbol);
            long wholeQty = Math.round(qty);
            String ibkrSide = "buy".equalsIgnoreCase(side) ? "BUY" : "SELL";

            ObjectNode order = objectMapper.createObjectNode();
            order.put("conid",           conid);
            order.put("orderType",       "TRAIL");
            order.put("side",            ibkrSide);
            order.put("quantity",        wholeQty);
            order.put("tif",             "GTC");
            order.put("trailingPercent", trailPercent);

            logger.info("Placing IBKR trailing stop: {} {} qty={} trail={}%", ibkrSide, symbol, wholeQty, trailPercent);
            String responseBody = sendPost(baseUrl + "/iserver/order/" + accountId,
                objectMapper.writeValueAsString(order));
            handleOrderConfirmation(responseBody);
        } catch (Exception e) {
            logger.error("Failed to place IBKR trailing stop for {}", symbol, e);
            throw new RuntimeException("IBKR trailing stop order failed", e);
        }
    }

    /**
     * IBKR bracket orders require complex OCA groups. Not implemented here.
     * Returns withoutBracket() so ProfileManager falls back to client-side monitoring.
     */
    @Override
    public BracketOrderResult placeBracketOrder(String symbol, double qty, String side,
                                                double takeProfitPrice, double stopLossPrice,
                                                Double stopLossLimitPrice, Double limitPrice) {
        logger.warn("IBKRClient: bracket orders not supported — placing simple {} order for {}. "
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

    // ── Open Orders / Order History ───────────────────────────────────────────

    @Override
    public JsonNode getOpenOrders(String symbol) {
        try {
            String json = sendGet(baseUrl + "/iserver/account/orders");
            JsonNode root = objectMapper.readTree(json);
            JsonNode orders = root.path("orders");
            ArrayNode result = objectMapper.createArrayNode();

            if (orders.isArray()) {
                for (JsonNode o : orders) {
                    if (symbol.equalsIgnoreCase(o.path("ticker").asText())
                            && isOpenStatus(o.path("status").asText())) {
                        ObjectNode normalized = objectMapper.createObjectNode();
                        normalized.put("id",   o.path("orderId").asText());
                        normalized.put("type", translateIBKROrderType(o.path("orderType").asText()));
                        normalized.put("side", o.path("side").asText().toLowerCase());
                        result.add(normalized);
                    }
                }
            }
            return result;
        } catch (Exception e) {
            logger.error("Failed to get open orders for {} from IBKR", symbol, e);
            return objectMapper.createArrayNode();
        }
    }

    private String translateIBKROrderType(String ibkrType) {
        if (ibkrType == null) return "market";
        return switch (ibkrType.toUpperCase()) {
            case "LMT"     -> "limit";
            case "STP"     -> "stop";
            case "STP LMT" -> "stop_limit";
            case "TRAIL"   -> "trailing_stop";
            default        -> "market";
        };
    }

    @Override
    public JsonNode getRecentOrders(String symbol) {
        return getOpenOrders(symbol);
    }

    @Override
    public JsonNode getOrderHistory(String symbol, int limit) {
        return getOpenOrders(symbol);
    }

    // ── Unsupported operations ────────────────────────────────────────────────

    @Override
    public JsonNode getNews(String symbol, int limit) {
        throw new UnsupportedOperationException("IBKRClient does not support getNews()");
    }

    @Override
    public JsonNode getAccountActivities(String activityType, int limit) {
        throw new UnsupportedOperationException("IBKRClient does not support getAccountActivities()");
    }
}
