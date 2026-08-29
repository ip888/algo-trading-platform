package com.trading.backtest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trading.api.BrokerClient;
import com.trading.api.model.Bar;
import com.trading.api.model.BracketOrderResult;
import com.trading.api.model.Position;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A {@link BrokerClient} backed by pre-fetched historical bars instead of live Alpaca calls,
 * so {@link com.trading.strategy.StrategyManager}, {@link com.trading.analysis.MarketRegimeDetector},
 * {@link com.trading.analysis.MultiTimeframeAnalyzer}, and the concrete strategies (MACD, ORB, Scalp,
 * Momentum, MeanReversion) run completely unmodified against historical data — none of them are
 * touched by this class, they just receive a different {@code BrokerClient} implementation.
 *
 * <p><b>Scope:</b> only the read-side methods the signal pipeline actually calls
 * ({@link #getBars}, {@link #getMarketHistory}, {@link #getLatestBar}) are implemented with real
 * replay semantics. Order placement, position, and account methods are intentionally
 * unimplemented — {@link WalkForwardBacktestHarness} tracks simulated positions and fills itself
 * rather than routing them through the broker interface, so a call to any of those methods here
 * indicates a bug (something reaching for live-trading behavior during a backtest) and fails loudly
 * rather than silently returning fabricated data.
 *
 * <p>Call {@link #advanceTo(Instant)} to move the simulated clock forward between bar-steps —
 * every subsequent {@code getBars}/{@code getMarketHistory}/{@code getLatestBar} call only sees
 * data with a timestamp strictly before that point, exactly like the live bot never seeing the
 * future.
 */
public final class HistoricalReplayBrokerClient implements BrokerClient {

    private final Map<String, Map<String, List<Bar>>> barsBySymbolAndTimeframe;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private volatile Instant simulatedNow;

    public HistoricalReplayBrokerClient(Instant startingNow) {
        this.barsBySymbolAndTimeframe = new HashMap<>();
        this.simulatedNow = startingNow;
    }

    /** Load a symbol+timeframe's full historical bar series (ascending by timestamp) into the replay set. */
    public void loadBars(String symbol, String timeframe, List<Bar> bars) {
        barsBySymbolAndTimeframe
            .computeIfAbsent(symbol, s -> new HashMap<>())
            .put(timeframe, bars);
    }

    /** Advance the simulated clock. All subsequent reads are scoped to strictly before this instant. */
    public void advanceTo(Instant now) {
        this.simulatedNow = now;
    }

    public Instant getSimulatedNow() {
        return simulatedNow;
    }

    @Override
    public List<Bar> getBars(String symbol, String timeframe, int limit) {
        List<Bar> all = seriesFor(symbol, timeframe);
        List<Bar> visible = new ArrayList<>();
        for (Bar b : all) {
            if (b.timestamp().isBefore(simulatedNow)) {
                visible.add(b);
            } else {
                break; // series is ascending — nothing after this point is visible either
            }
        }
        int from = Math.max(0, visible.size() - limit);
        return new ArrayList<>(visible.subList(from, visible.size()));
    }

    @Override
    public List<Bar> getMarketHistory(String symbol, int limit) {
        // Prefer a dedicated daily-history series if one was loaded (matches AlpacaClient's
        // separate getMarketHistory endpoint); fall back to the 1Day timeframe series otherwise.
        List<Bar> all = barsBySymbolAndTimeframe.getOrDefault(symbol, Map.of())
            .getOrDefault("1Day-history", seriesFor(symbol, "1Day"));
        List<Bar> visible = new ArrayList<>();
        for (Bar b : all) {
            if (b.timestamp().isBefore(simulatedNow)) {
                visible.add(b);
            } else {
                break;
            }
        }
        int from = Math.max(0, visible.size() - limit);
        return new ArrayList<>(visible.subList(from, visible.size()));
    }

    @Override
    public Optional<Bar> getLatestBar(String symbol) {
        // Use the finest-grained loaded timeframe for this symbol as the "latest tick" proxy.
        Map<String, List<Bar>> byTimeframe = barsBySymbolAndTimeframe.get(symbol);
        if (byTimeframe == null) return Optional.empty();
        for (String tf : List.of("1Min", "5Min", "15Min", "1Hour", "1Day")) {
            List<Bar> series = byTimeframe.get(tf);
            if (series == null) continue;
            Bar latest = null;
            for (Bar b : series) {
                if (b.timestamp().isBefore(simulatedNow)) latest = b; else break;
            }
            if (latest != null) return Optional.of(latest);
        }
        return Optional.empty();
    }

    private List<Bar> seriesFor(String symbol, String timeframe) {
        return barsBySymbolAndTimeframe.getOrDefault(symbol, Map.of()).getOrDefault(timeframe, List.of());
    }

    // ── Everything below is intentionally unsupported in replay mode ──────────────────────
    // The harness simulates positions/fills/account state itself; nothing in the signal
    // pipeline (StrategyManager and friends) calls any of these. A call here means something
    // unexpected is reaching for live-trading behavior mid-backtest.

    private static UnsupportedOperationException notSupported(String method) {
        return new UnsupportedOperationException(
            method + " is not supported by HistoricalReplayBrokerClient — the backtest harness "
            + "tracks simulated positions/orders/account state itself. If a new code path needs "
            + "this during replay, it belongs in WalkForwardBacktestHarness, not here.");
    }

    @Override public JsonNode getAccount() { throw notSupported("getAccount"); }
    @Override public boolean validateAccountForTrading() { throw notSupported("validateAccountForTrading"); }
    @Override public JsonNode getClock() { throw notSupported("getClock"); }
    @Override public Optional<Position> getPosition(String symbol) { throw notSupported("getPosition"); }
    @Override public List<Position> getPositions() { throw notSupported("getPositions"); }
    @Override public JsonNode getOpenOrders(String symbol) { throw notSupported("getOpenOrders"); }
    @Override public JsonNode getNews(String symbol, int limit) { return objectMapper.createObjectNode(); }
    @Override public JsonNode getRecentOrders(String symbol) { throw notSupported("getRecentOrders"); }
    @Override public JsonNode getOrderHistory(String symbol, int limit) { throw notSupported("getOrderHistory"); }
    @Override public JsonNode getAccountActivities(String activityType, int limit) { throw notSupported("getAccountActivities"); }
    @Override public void cancelOrder(String orderId) { throw notSupported("cancelOrder"); }
    @Override public void cancelAllOrders() { throw notSupported("cancelAllOrders"); }
    @Override public void placeOrder(String symbol, double qty, String side, String type, String timeInForce, Double limitPrice) { throw notSupported("placeOrder"); }
    @Override public void replaceOrder(String orderId, Double qty, Double limitPrice, Double stopPrice) { throw notSupported("replaceOrder"); }
    @Override public void placeNativeStopOrder(String symbol, double qty, double stopPrice) { throw notSupported("placeNativeStopOrder"); }
    @Override public void placeTrailingStopOrder(String symbol, double qty, String side, double trailPercent) { throw notSupported("placeTrailingStopOrder"); }
    @Override public BracketOrderResult placeBracketOrder(String symbol, double qty, String side, double takeProfitPrice, double stopLossPrice, Double stopLossLimitPrice, Double limitPrice) { throw notSupported("placeBracketOrder"); }
    @Override public String placeBracketOrder(String symbol, double qty, String side, double takeProfitPrice, double stopLossPrice, Double stopLossLimitPrice) { throw notSupported("placeBracketOrder"); }
}
