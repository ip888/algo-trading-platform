package com.trading.api.controller;

import com.trading.api.AlpacaClient;
import com.trading.backtest.WalkForwardBacktestHarness;
import com.trading.backtesting.Backtester;
import com.trading.config.Config;
import com.trading.strategy.MACDStrategy;
import com.trading.strategy.TradingSignal;
import io.javalin.Javalin;
import io.javalin.http.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * REST API controller for running backtests.
 */
public final class BacktestController {
    private static final Logger logger = LoggerFactory.getLogger(BacktestController.class);

    public void registerRoutes(Javalin app) {
        app.post("/api/backtest", this::runBacktest);
        app.get("/api/backtest/intraday-macd", this::runIntradayMacdSignal);
        app.get("/api/backtest/walkforward", this::runWalkForward);
    }

    /**
     * Runs the real {@link WalkForwardBacktestHarness} — the same StrategyManager, regime
     * detector, MTF analyzer, position sizer, and exit logic that trade live — against real
     * historical Alpaca data for the requested symbols/window. Read-only: writes only to a
     * throwaway backtest.db under the cache dir, places no live orders, touches no live state.
     * First run per symbol/timeframe combo hits the real Alpaca API (needs live credentials,
     * already configured server-side); subsequent runs reuse the on-disk bar cache.
     */
    private void runWalkForward(Context ctx) {
        try {
            String symbolsParam = ctx.queryParam("symbols");
            if (symbolsParam == null || symbolsParam.isBlank()) {
                ctx.status(400).json(Map.of("error", "symbols is required (comma-separated)"));
                return;
            }
            List<String> symbols = Arrays.stream(symbolsParam.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
            int days = ctx.queryParamAsClass("days", Integer.class).getOrDefault(10);
            double capital = ctx.queryParamAsClass("capital", Double.class).getOrDefault(1180.0);
            int maxPositions = ctx.queryParamAsClass("maxPositions", Integer.class).getOrDefault(4);
            if (days < 1 || days > 60) {
                ctx.status(400).json(Map.of("error", "days must be 1-60"));
                return;
            }

            var config = new Config();
            var liveClient = new AlpacaClient(config);
            Instant end = Instant.now();
            Instant start = end.minusSeconds(days * 86400L);

            // Cache key includes `days`: HistoricalBarCache keys files by symbol+timeframe+limit,
            // and loadHistory() only requests fixed limits — without this, two requests with
            // different `days` but the same symbols would silently reuse whichever bar set was
            // fetched first, making the `days` param a no-op after the first call.
            Path cacheDir = Path.of(System.getProperty("java.io.tmpdir"), "backtest-cache-" + days + "d");
            var harness = new WalkForwardBacktestHarness(config, cacheDir, start);
            harness.loadHistory(liveClient, symbols, days);
            var report = harness.run(symbols, start, end, capital, maxPositions);

            var response = new java.util.LinkedHashMap<String, Object>();
            response.put("requestedDays", days);
            response.put("requestedStart", start.toString());
            response.put("requestedEnd", end.toString());
            response.put("regimeStepCounts", harness.getLastRunRegimeCounts());
            response.put("report", report);
            ctx.json(response);
        } catch (Exception e) {
            logger.error("Walk-forward backtest failed", e);
            ctx.status(500).json(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Diagnostic-only, read-only: replays the REAL MACDStrategy against intraday bars
     * (instead of the live pipeline's daily bars) to test whether an intraday-timeframe
     * signal would have avoided the same-day stop-losses a daily-timeframe signal can't,
     * since every live position is forced flat by end-of-day regardless of the daily
     * trend the entry was based on. Does not touch live trading state or place orders.
     */
    private void runIntradayMacdSignal(Context ctx) {
        try {
            String symbol = ctx.queryParam("symbol");
            String timeframe = ctx.queryParamAsClass("timeframe", String.class).getOrDefault("15Min");
            int limit = ctx.queryParamAsClass("limit", Integer.class).getOrDefault(1200);
            if (symbol == null || symbol.isBlank()) {
                ctx.status(400).json(Map.of("error", "symbol is required"));
                return;
            }

            var config = new Config();
            var client = new AlpacaClient(config);
            var bars = client.getBars(symbol, timeframe, limit);
            var macd = new MACDStrategy();

            List<Double> closes = new ArrayList<>();
            List<Map<String, Object>> events = new ArrayList<>();

            double positionQty = 0;
            double entryPrice = 0;
            var et = ZoneId.of("America/New_York");
            java.time.LocalDate lastDay = null;

            for (var bar : bars) {
                closes.add(bar.close());
                var zdt = bar.timestamp().atZone(et);
                var day = zdt.toLocalDate();

                // Mirror the live bot's EOD-flatten: force flat at end of each trading day
                // so this stays an apples-to-apples comparison to how positions are actually held.
                if (lastDay != null && !day.equals(lastDay) && positionQty > 0) {
                    double pnlPct = (bar.close() - entryPrice) / entryPrice * 100.0;
                    events.add(Map.of("time", zdt.toString(), "action", "EOD_FLATTEN",
                        "price", bar.close(), "pnlPercent", round2(pnlPct)));
                    positionQty = 0;
                    entryPrice = 0;
                }
                lastDay = day;

                if (closes.size() <= 36) continue; // MACD warmup (SLOW_PERIOD+SIGNAL_PERIOD+1)

                var signal = macd.evaluateWithHistory(symbol, bar.close(), positionQty, closes, 0.0);
                if (signal instanceof TradingSignal.Buy buy && positionQty == 0) {
                    positionQty = 1;
                    entryPrice = bar.close();
                    events.add(Map.of("time", zdt.toString(), "action", "BUY",
                        "price", bar.close(), "reason", buy.reason()));
                } else if (signal instanceof TradingSignal.Sell sell && positionQty > 0) {
                    double pnlPct = (bar.close() - entryPrice) / entryPrice * 100.0;
                    events.add(Map.of("time", zdt.toString(), "action", "SELL",
                        "price", bar.close(), "reason", sell.reason(), "pnlPercent", round2(pnlPct)));
                    positionQty = 0;
                    entryPrice = 0;
                }
            }

            ctx.json(Map.of(
                "symbol", symbol,
                "timeframe", timeframe,
                "barsFetched", bars.size(),
                "events", events
            ));
        } catch (Exception e) {
            logger.error("Intraday MACD diagnostic failed", e);
            ctx.status(500).json(Map.of("error", e.getMessage()));
        }
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
    
    private void runBacktest(Context ctx) {
        try {
            var request = ctx.bodyAsClass(Backtester.BacktestRequest.class);
            
            logger.info("Received backtest request for {} ({} days)", request.symbol(), request.days());
            
            // Validate
            if (request.days() > 365) {
                ctx.status(400).json(Map.of("error", "Max 365 days allowed"));
                return;
            }
            if (request.days() < 5) {
                ctx.status(400).json(Map.of("error", "Min 5 days required"));
                return;
            }
            
            var result = Backtester.run(request);
            ctx.json(result);
            
        } catch (Exception e) {
            logger.error("Backtest request failed", e);
            ctx.status(500).json(Map.of("error", e.getMessage()));
        }
    }
}
