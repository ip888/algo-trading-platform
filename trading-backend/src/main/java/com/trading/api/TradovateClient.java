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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Tradovate broker integration implementing BrokerClient.
 *
 * Base URLs (from official openapi.json):
 *   Demo: https://demo.tradovateapi.com/v1
 *   Live: https://live.tradovateapi.com/v1
 *
 * Authentication: POST /auth/accesstokenrequest
 *   Required fields: name, password, appId, appVersion, cid (API Key ID string), sec
 *   Token lifetime: 90 minutes. Renewal: GET /auth/renewaccesstoken.
 *
 * Market data is WebSocket-only (wss://md.tradovateapi.com/v1/websocket).
 * getBars() / getLatestBar() throw UnsupportedOperationException — use Alpaca for signal data.
 *
 * All automated orders MUST include isAutomated=true per exchange requirements.
 * Bracket orders use POST /order/placeoso (OSO = Order Sends Order).
 *
 * Note: Tradovate API access requires a Live account + paid API subscription.
 * Demo accounts cannot access the REST/WebSocket API.
 */
public final class TradovateClient implements BrokerClient {
    private static final Logger logger = LoggerFactory.getLogger(TradovateClient.class);

    // Correct base URLs from official Tradovate openapi.json
    private static final String LIVE_BASE = "https://live.tradovateapi.com/v1";
    private static final String DEMO_BASE = "https://demo.tradovateapi.com/v1";

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);
    // Tokens expire in 90 min; renew at 80 min to stay ahead
    private static final long RENEWAL_INTERVAL_MINUTES = 80;

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final String username;
    private final String password;
    private final String appId;
    private final String appVersion;
    private final String cid;   // API Key ID (string) — distinct from appId
    private final String sec;   // API secret
    private final AtomicReference<String> accessToken = new AtomicReference<>("");
    private final AtomicLong accountId = new AtomicLong(-1);
    private final ScheduledExecutorService renewalExecutor;

    public TradovateClient(Config config) {
        this.username   = config.getTradovateUsername();
        this.password   = config.getTradovatePassword();
        this.appId      = config.getTradovateAppId();
        this.appVersion = config.getTradovateAppVersion();
        this.cid        = config.getTradovateCid();
        this.sec        = config.getTradovateAppSecret();
        this.baseUrl    = config.isTradovateDemo() ? DEMO_BASE : LIVE_BASE;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(REQUEST_TIMEOUT)
            .build();
        this.objectMapper = new ObjectMapper();

        logger.info("TradovateClient initialized — baseUrl={}, user={}",
            baseUrl, username.isBlank() ? "<not set>" : username);

        this.renewalExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "tradovate-token-renewal");
            t.setDaemon(true);
            return t;
        });

        try {
            authenticate();
            scheduleTokenRenewal();
        } catch (Exception e) {
            logger.warn("TradovateClient: initial authentication failed — will retry on first request: {}",
                e.getMessage());
        }
    }

    // ── Authentication ────────────────────────────────────────────────────────

    private synchronized void authenticate() throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("name",       username);
        body.put("password",   password);
        body.put("appId",      appId);
        body.put("appVersion", appVersion);
        body.put("cid",        cid);   // API Key ID — must be string
        body.put("sec",        sec);

        String json = sendPostJsonRaw(baseUrl + "/auth/accesstokenrequest", body.toString());
        JsonNode resp = objectMapper.readTree(json);
        String token = resp.path("accessToken").asText("");
        if (token.isBlank()) {
            throw new RuntimeException("Tradovate authentication failed — no accessToken in response: " + json);
        }
        accessToken.set(token);
        logger.info("TradovateClient: authenticated successfully");

        // Cache account ID for order placement
        resolveAccountId();
    }

    private void resolveAccountId() {
        try {
            String json = sendGetRaw(baseUrl + "/account/list");
            JsonNode arr = objectMapper.readTree(json);
            if (arr.isArray() && arr.size() > 0) {
                long id = arr.get(0).path("id").asLong(-1);
                if (id > 0) {
                    accountId.set(id);
                    logger.info("TradovateClient: resolved accountId={}", id);
                }
            }
        } catch (Exception e) {
            logger.warn("TradovateClient: failed to resolve accountId: {}", e.getMessage());
        }
    }

    private void renewToken() {
        try {
            // Token renewal uses GET /auth/renewaccesstoken (not POST)
            String json = sendGetRaw(baseUrl + "/auth/renewaccesstoken");
            JsonNode resp = objectMapper.readTree(json);
            String token = resp.path("accessToken").asText("");
            if (!token.isBlank()) {
                accessToken.set(token);
                logger.debug("TradovateClient: access token renewed");
            } else {
                logger.warn("TradovateClient: renewal returned no token — re-authenticating");
                authenticate();
            }
        } catch (Exception e) {
            logger.warn("TradovateClient: token renewal failed, re-authenticating: {}", e.getMessage());
            try {
                authenticate();
            } catch (Exception ex) {
                logger.error("TradovateClient: re-authentication failed: {}", ex.getMessage());
            }
        }
    }

    private void scheduleTokenRenewal() {
        renewalExecutor.scheduleAtFixedRate(
            this::renewToken,
            RENEWAL_INTERVAL_MINUTES,
            RENEWAL_INTERVAL_MINUTES,
            TimeUnit.MINUTES
        );
    }

    // ── HTTP helpers ──────────────────────────────────────────────────────────

    /** Raw GET without retry — used during auth (to avoid infinite recursion). */
    private String sendGetRaw(String url) throws Exception {
        var req = HttpRequest.newBuilder(URI.create(url))
            .header("Authorization", "Bearer " + accessToken.get())
            .header("Accept", "application/json")
            .timeout(REQUEST_TIMEOUT)
            .GET()
            .build();
        var resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() >= 400) {
            throw new RuntimeException("Tradovate GET failed [" + resp.statusCode() + "]: " + resp.body());
        }
        return resp.body();
    }

    /** Raw POST without retry — used during auth. */
    private String sendPostJsonRaw(String url, String jsonBody) throws Exception {
        var req = HttpRequest.newBuilder(URI.create(url))
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .timeout(REQUEST_TIMEOUT)
            .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
            .build();
        var resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() >= 400) {
            throw new RuntimeException("Tradovate POST failed [" + resp.statusCode() + "]: " + resp.body());
        }
        return resp.body();
    }

    private String sendGet(String url) throws Exception {
        try {
            return sendGetRaw(url);
        } catch (RuntimeException e) {
            if (e.getMessage() != null && e.getMessage().contains("[401]")) {
                logger.warn("TradovateClient: 401 on GET {}, re-authenticating…", url);
                authenticate();
                return sendGetRaw(url);
            }
            throw e;
        }
    }

    private String sendPost(String url, String jsonBody) throws Exception {
        try {
            var req = HttpRequest.newBuilder(URI.create(url))
                .header("Authorization", "Bearer " + accessToken.get())
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .timeout(REQUEST_TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();
            var resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 401) {
                logger.warn("TradovateClient: 401 on POST {}, re-authenticating…", url);
                authenticate();
                // retry with fresh token
                var req2 = HttpRequest.newBuilder(URI.create(url))
                    .header("Authorization", "Bearer " + accessToken.get())
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json")
                    .timeout(REQUEST_TIMEOUT)
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();
                var resp2 = httpClient.send(req2, HttpResponse.BodyHandlers.ofString());
                if (resp2.statusCode() >= 400) {
                    throw new RuntimeException("Tradovate POST failed [" + resp2.statusCode() + "]: " + resp2.body());
                }
                return resp2.body();
            }
            if (resp.statusCode() >= 400) {
                throw new RuntimeException("Tradovate POST failed [" + resp.statusCode() + "]: " + resp.body());
            }
            return resp.body();
        } catch (RuntimeException e) {
            throw e;
        }
    }

    // ── Symbol mapping helper ─────────────────────────────────────────────────

    private String toFutures(String symbol) {
        if (FuturesSymbolMapper.isMappedSymbol(symbol)) {
            return FuturesSymbolMapper.toFuturesSymbol(symbol, LocalDate.now());
        }
        return symbol;
    }

    private String fromFutures(String futuresSymbol) {
        return FuturesSymbolMapper.toEquitySymbol(futuresSymbol).orElse(futuresSymbol);
    }

    // ── Account ───────────────────────────────────────────────────────────────

    @Override
    public JsonNode getAccount() throws Exception {
        // Get account list for basic info
        String acctJson = sendGet(baseUrl + "/account/list");
        JsonNode arr = objectMapper.readTree(acctJson);
        JsonNode acct = arr.isArray() && arr.size() > 0 ? arr.get(0) : arr;
        long acctId = acct.path("id").asLong(-1);

        // Get cash balance via dedicated endpoint
        double cash = 0;
        double equity = 0;
        if (acctId > 0) {
            try {
                ObjectNode req = objectMapper.createObjectNode();
                req.put("accountId", acctId);
                String balJson = sendPost(baseUrl + "/cashBalance/getcashbalancesnapshot", req.toString());
                JsonNode bal = objectMapper.readTree(balJson);
                cash   = bal.path("cashBalance").asDouble(0);
                equity = bal.path("netLiquidatingValue").asDouble(cash);
                if (equity == 0) equity = cash;
            } catch (Exception e) {
                logger.warn("TradovateClient.getAccount: failed to fetch cash balance — {}", e.getMessage());
                equity = acct.path("netLiquidatingValue").asDouble(0);
                cash   = equity;
            }
        } else {
            equity = acct.path("netLiquidatingValue").asDouble(0);
            cash   = equity;
        }

        ObjectNode result = objectMapper.createObjectNode();
        result.put("equity",             equity);
        result.put("buying_power",       equity);   // futures: no leverage limit; margin tracked separately
        result.put("cash",               cash);
        result.put("currency",           acct.path("currency").asText("USD"));
        result.put("status",             acct.path("active").asBoolean(false) ? "ACTIVE" : "INACTIVE");
        result.put("trading_blocked",    false);
        result.put("account_blocked",    false);
        result.put("pattern_day_trader", false);   // PDT rules don't apply to futures
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

    @Override
    public JsonNode getClock() throws Exception {
        ZonedDateTime now = ZonedDateTime.now(FuturesMarketHours.CT);
        boolean open = FuturesMarketHours.isOpen(now);

        ObjectNode clock = objectMapper.createObjectNode();
        clock.put("is_open",    open);
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
                if (netPos == 0) continue;
                // contract name is nested under contractId or contract
                String futuresSym = p.path("contractId").path("name").asText(
                    p.path("contract").path("name").asText(""));
                if (futuresSym.isBlank()) continue;
                String equitySym = fromFutures(futuresSym);
                double avgPrice  = p.path("netPrice").asDouble(0);
                double marketVal = netPos * avgPrice;
                result.add(new Position(equitySym, netPos, marketVal, avgPrice, 0.0));
            }
        }
        return result;
    }

    // ── Market Data ───────────────────────────────────────────────────────────

    /**
     * Tradovate provides market data via WebSocket only (wss://md.tradovateapi.com/v1/websocket).
     * There is NO REST endpoint for historical bars.
     *
     * Use Alpaca as the market data source when trading micro futures on Tradovate.
     * Configure BROKER=tradovate for order execution while the signal engine
     * continues to use Alpaca market data (already the default for signal generation).
     */
    @Override
    public Optional<Bar> getLatestBar(String symbol) {
        throw new UnsupportedOperationException(
            "TradovateClient: market data is WebSocket-only. Use Alpaca for bar data. "
            + "Symbol: " + symbol);
    }

    @Override
    public List<Bar> getBars(String symbol, String timeframe, int limit) throws Exception {
        throw new UnsupportedOperationException(
            "TradovateClient: market data is WebSocket-only (no REST bar endpoint). "
            + "Use Alpaca for historical bars. Symbol: " + symbol);
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
            logger.info("Placing Tradovate trailing stop: {} {} qty={} trail={}%",
                action, futuresSym, wholeQty, trailPercent);
            sendPost(baseUrl + "/order/placeorder", body.toString());
        } catch (Exception e) {
            logger.error("Failed to place Tradovate trailing stop for {}", symbol, e);
            throw new RuntimeException("Tradovate trailing stop order failed", e);
        }
    }

    /**
     * Places a bracket order via POST /order/placeoso (OSO = Order Sends Order).
     *
     * The OSO order consists of:
     *   - entry order (market or limit buy/sell)
     *   - bracket1: stop-loss order triggered when entry fills
     *   - bracket2: take-profit limit order triggered when entry fills
     *
     * All orders include isAutomated=true as required by Tradovate for automated trading.
     */
    @Override
    public BracketOrderResult placeBracketOrder(String symbol, double qty, String side,
                                                double takeProfitPrice, double stopLossPrice,
                                                Double stopLossLimitPrice, Double limitPrice) {
        try {
            String futuresSym = toFutures(symbol);
            long wholeQty = Math.max(1, Math.round(Math.abs(qty)));
            String action     = side.equalsIgnoreCase("buy") ? "Buy" : "Sell";
            String exitAction = side.equalsIgnoreCase("buy") ? "Sell" : "Buy";

            // Entry order
            ObjectNode entry = objectMapper.createObjectNode();
            entry.put("action",      action);
            entry.put("symbol",      futuresSym);
            entry.put("orderQty",    wholeQty);
            entry.put("orderType",   limitPrice != null ? "Limit" : "Market");
            entry.put("timeInForce", "DAY");
            entry.put("isAutomated", true);
            if (limitPrice != null) entry.put("price", limitPrice);

            // Bracket 1: Stop-loss
            ObjectNode bracket1 = objectMapper.createObjectNode();
            bracket1.put("action",    exitAction);
            bracket1.put("orderQty",  wholeQty);
            if (stopLossLimitPrice != null) {
                bracket1.put("orderType", "StopLimit");
                bracket1.put("price",     stopLossLimitPrice);
            } else {
                bracket1.put("orderType", "Stop");
            }
            bracket1.put("stopPrice",    stopLossPrice);
            bracket1.put("timeInForce",  "DAY");
            bracket1.put("isAutomated",  true);

            // Bracket 2: Take-profit
            ObjectNode bracket2 = objectMapper.createObjectNode();
            bracket2.put("action",      exitAction);
            bracket2.put("orderQty",    wholeQty);
            bracket2.put("orderType",   "Limit");
            bracket2.put("price",       takeProfitPrice);
            bracket2.put("timeInForce", "DAY");
            bracket2.put("isAutomated", true);

            ObjectNode body = objectMapper.createObjectNode();
            body.set("entryOrder", entry);
            body.set("bracket1",   bracket1);
            body.set("bracket2",   bracket2);

            logger.info("Placing Tradovate OSO bracket: {} {} qty={} tp={} sl={}",
                action, futuresSym, wholeQty, takeProfitPrice, stopLossPrice);
            String respJson = sendPost(baseUrl + "/order/placeoso", body.toString());
            JsonNode resp = objectMapper.readTree(respJson);

            String orderId = resp.path("orderId").asText(
                resp.path("id").asText("unknown"));
            return BracketOrderResult.withBracket(symbol, qty);
        } catch (Exception e) {
            logger.error("Failed to place Tradovate bracket order for {}", symbol, e);
            throw new RuntimeException("Tradovate bracket order placement failed", e);
        }
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
            // orderId is numeric in Tradovate
            try {
                body.put("orderId", Long.parseLong(orderId));
            } catch (NumberFormatException nfe) {
                body.put("orderId", orderId);
            }
            body.put("isAutomated", true);
            logger.info("Cancelling Tradovate order {}", orderId);
            sendPost(baseUrl + "/order/cancelorder", body.toString());
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

    // ── Order body builder ────────────────────────────────────────────────────

    /**
     * Builds a single-order body with isAutomated=true (required for automated orders by Tradovate).
     */
    private ObjectNode buildOrderBody(String futuresSym, String action, long qty,
                                      String orderType, Double limitPrice,
                                      Double stopPrice, Double trailingStop) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("action",      action);
        body.put("symbol",      futuresSym);
        body.put("orderQty",    qty);
        body.put("orderType",   orderType);
        body.put("timeInForce", "DAY");
        body.put("isAutomated", true);   // REQUIRED for automated orders
        if (limitPrice   != null) body.put("price",        limitPrice);
        if (stopPrice    != null) body.put("stopPrice",    stopPrice);
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
