package com.trading.api.controller;

import com.trading.api.AlpacaClient;
import com.trading.backtest.WalkForwardBacktestHarness;
import com.trading.backtesting.Backtester;
import com.trading.config.Config;
import com.trading.strategy.MACDStrategy;
import com.trading.strategy.MomentumStrategy;
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
        app.get("/api/backtest/regime-sample", this::runRegimeSample);
        app.get("/api/backtest/check-market-history", this::checkMarketHistory);
        app.get("/api/backtest/momentum-diagnose", this::diagnoseMomentum);
        app.get("/api/backtest/scalp-walkforward", this::runScalpWalkForward);
    }

    /**
     * Diagnostic-only: runs the real WalkForwardBacktestHarness with ScalpStrategy force-enabled
     * for this run only (SCALP_STRATEGY_ENABLED=false stays untouched in config.properties/live).
     * Optional query params override individual scalp tuning knobs so different candidate
     * configurations can be tested against real data without a redeploy per attempt. Uses a
     * fresh Config instance and reflectively mutates its own Properties object — isolated from
     * the live trading loop's separately-constructed Config, and env vars (which take priority
     * over the properties file — see Config.getProperty) are untouched either way.
     */
    private void runScalpWalkForward(Context ctx) {
        try {
            String symbolsParam = ctx.queryParamAsClass("symbols", String.class)
                .getOrDefault("SPY,QQQ,IWM,NVDA,AAPL,META,MSFT,AMD,TSLA");
            List<String> symbols = Arrays.stream(symbolsParam.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
            int days = ctx.queryParamAsClass("days", Integer.class).getOrDefault(19);
            double capital = ctx.queryParamAsClass("capital", Double.class).getOrDefault(1180.0);
            int maxPositions = ctx.queryParamAsClass("maxPositions", Integer.class).getOrDefault(4);
            if (days < 1 || days > 60) {
                ctx.status(400).json(Map.of("error", "days must be 1-60"));
                return;
            }

            com.trading.strategy.ScalpStrategy.resetStaticStateForBacktest();
            var config = new Config();
            overrideProperty(config, "SCALP_STRATEGY_ENABLED", "true");
            for (var key : new String[]{"SCALP_RSI_MIN", "SCALP_RSI_MAX", "SCALP_VOLUME_MULTIPLIER",
                    "SCALP_STOP_LOSS_PERCENT", "SCALP_TAKE_PROFIT_PERCENT", "SCALP_MAX_DAILY_TRADES"}) {
                String override = ctx.queryParam(key);
                if (override != null && !override.isBlank()) {
                    overrideProperty(config, key, override);
                }
            }

            // Generic experiment overrides: ?cfg.KEY=value (KEY = an UPPER_SNAKE config key). Applies to this
            // run's private Config only — never the live one. Lets exit/entry settings be swept on the
            // realistic replay (breakeven trigger, flat-position hours, runner, strategy kill-switches...).
            var appliedOverrides = new java.util.LinkedHashMap<String, String>();
            for (var e : ctx.queryParamMap().entrySet()) {
                if (e.getKey().startsWith("cfg.") && !e.getValue().isEmpty()) {
                    String key = e.getKey().substring(4);
                    if (key.matches("[A-Z][A-Z0-9_]{2,80}")) {
                        overrideProperty(config, key, e.getValue().get(0));
                        appliedOverrides.put(key, e.getValue().get(0));
                    }
                }
            }

            var liveClient = new AlpacaClient(config);
            Instant end = Instant.now();
            Instant start = end.minusSeconds(days * 86400L);
            Path cacheDir = Path.of(System.getProperty("java.io.tmpdir"), "backtest-cache-scalp-" + days + "d");
            var harness = new WalkForwardBacktestHarness(config, cacheDir, start);
            // intrabar=false reproduces the pre-2026-09-24 15-min-sampled exits (for comparison only)
            harness.setIntrabarExits(!"false".equalsIgnoreCase(ctx.queryParam("intrabar")));
            harness.loadHistory(liveClient, symbols, days);
            var report = harness.run(symbols, start, end, capital, maxPositions);

            var response = new java.util.LinkedHashMap<String, Object>();
            response.put("requestedDays", days);
            response.put("appliedOverrides", appliedOverrides);
            response.put("scalpConfig", Map.of(
                "rsiMin", config.getScalpRsiBuyMin(), "rsiMax", config.getScalpRsiBuyMax(),
                "volumeMultiplier", config.getScalpVolumeMultiplier(),
                "stopLossPercent", config.getScalpStopLossPercent(), "takeProfitPercent", config.getScalpTakeProfitPercent(),
                "maxDailyTrades", config.getScalpMaxDailyTrades()));
            response.put("regimeStepCounts", harness.getLastRunRegimeCounts());
            response.put("report", report);
            ctx.json(response);
        } catch (Exception e) {
            logger.error("Scalp walk-forward backtest failed", e);
            ctx.status(500).json(Map.of("error", e.getMessage()));
        }
    }

    private static void overrideProperty(Config config, String key, String value) throws Exception {
        var field = Config.class.getDeclaredField("properties");
        field.setAccessible(true);
        var properties = (java.util.Properties) field.get(config);
        properties.setProperty(key, value);
    }

    /**
     * Diagnostic-only: runs MomentumStrategy's real gate logic (WEAK_BULL relaxed path) against
     * real, current daily history for one or more symbols and reports every intermediate gate
     * value — to find exactly which condition blocks entry, rather than guessing from trade
     * outcomes alone.
     */
    private void diagnoseMomentum(Context ctx) {
        try {
            String symbolsParam = ctx.queryParamAsClass("symbols", String.class).getOrDefault("SPY,QQQ,AMD,META,NVDA,TSLA,MSFT,AAPL,XLE,XOP");
            List<String> symbols = Arrays.stream(symbolsParam.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
            var config = new Config();
            var client = new AlpacaClient(config);
            var momentum = new MomentumStrategy(config);

            var results = new java.util.LinkedHashMap<String, Object>();
            for (String symbol : symbols) {
                var bars = client.getMarketHistory(symbol, 100);
                var closes = bars.stream().map(com.trading.api.model.Bar::close).toList();
                double currentPrice = closes.isEmpty() ? 0 : closes.get(closes.size() - 1);
                var gates = momentum.diagnoseWeakBull(currentPrice, closes);
                results.put(symbol, gates);
            }
            ctx.json(results);
        } catch (Exception e) {
            logger.error("Momentum diagnostic failed", e);
            ctx.status(500).json(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Diagnostic-only: calls the exact live method (AlpacaClient.getMarketHistory) that
     * StrategyManager uses for MACD/Momentum's daily `history` list, right now, and reports
     * the actual returned date range — to check whether it really reaches today or silently
     * stops short (a sort=asc + overly-wide start window can exhaust `limit` on old bars).
     */
    private void checkMarketHistory(Context ctx) {
        try {
            String symbol = ctx.queryParamAsClass("symbol", String.class).getOrDefault("SPY");
            int limit = ctx.queryParamAsClass("limit", Integer.class).getOrDefault(100);
            var config = new Config();
            var client = new AlpacaClient(config);
            var bars = client.getMarketHistory(symbol, limit);
            ctx.json(Map.of(
                "symbol", symbol,
                "requestedLimit", limit,
                "actualCount", bars.size(),
                "oldest", bars.isEmpty() ? null : bars.get(0).timestamp().toString(),
                "newest", bars.isEmpty() ? null : bars.get(bars.size() - 1).timestamp().toString(),
                "now", Instant.now().toString()
            ));
        } catch (Exception e) {
            logger.error("check-market-history failed", e);
            ctx.status(500).json(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Diagnostic-only: samples the regime detector at several distinct dates across a window
     * using a FRESH MarketRegimeDetector instance per sample (each with its own empty cache),
     * to isolate whether an observed frozen regime is a caching bug in the shared detector vs.
     * a genuine (if surprising) finding that the real data classifies the same way throughout.
     */
    private void runRegimeSample(Context ctx) {
        try {
            int days = ctx.queryParamAsClass("days", Integer.class).getOrDefault(19);
            int samples = ctx.queryParamAsClass("samples", Integer.class).getOrDefault(8);

            var config = new Config();
            var liveClient = new AlpacaClient(config);
            Instant end = Instant.now();
            Instant start = end.minusSeconds(days * 86400L);

            List<String> regimeSymbols = List.of("SPY", "XLK", "XLF", "XLE", "XLV", "XLI", "XLC", "XLU", "XLB", "VIXY");
            Path cacheDir = Path.of(System.getProperty("java.io.tmpdir"), "backtest-cache-" + days + "d");
            var cache = new com.trading.backtest.HistoricalBarCache(cacheDir);
            var replayClient = new com.trading.backtest.HistoricalReplayBrokerClient(start);
            int dailyLimit = Math.min(10_000, Math.max(400, days + 60));
            for (String sym : regimeSymbols) {
                replayClient.loadBars(sym, "1Day", cache.getOrFetch(liveClient, sym, "1Day", dailyLimit));
                if (!"VIXY".equals(sym)) {
                    replayClient.loadBars(sym, "1Day-history", cache.getOrFetchMarketHistory(liveClient, sym, dailyLimit));
                }
            }

            // Raw bar-range diagnostic: confirms whether the cached series actually spans
            // the requested window, before trusting anything computed from it. Advance to
            // `end` first so the isBefore(simulatedNow) filter doesn't hide anything.
            replayClient.advanceTo(end);
            var rawRanges = new java.util.LinkedHashMap<String, Object>();
            for (String sym : regimeSymbols) {
                var series = replayClient.getBars(sym, "1Day", 10_000);
                if (series.isEmpty()) {
                    rawRanges.put(sym, "EMPTY");
                } else {
                    rawRanges.put(sym, Map.of(
                        "count", series.size(),
                        "oldest", series.get(0).timestamp().toString(),
                        "newest", series.get(series.size() - 1).timestamp().toString()));
                }
            }

            var marketAnalyzer = new com.trading.analysis.MarketAnalyzer(replayClient);
            var results = new ArrayList<Map<String, Object>>();
            long spanSeconds = end.getEpochSecond() - start.getEpochSecond();
            for (int i = 0; i < samples; i++) {
                Instant sampleTime = start.plusSeconds(spanSeconds * i / Math.max(1, samples - 1));
                replayClient.advanceTo(sampleTime);
                // Fresh detector per sample — guarantees no cache carryover between samples.
                var freshDetector = new com.trading.analysis.MarketRegimeDetector(replayClient, config, marketAnalyzer);
                var analysis = freshDetector.getCurrentRegime();
                results.add(Map.of(
                    "sampleTime", sampleTime.toString(),
                    "regime", analysis.regime().name(),
                    "confidence", analysis.confidence(),
                    "vix", analysis.vix()
                ));
            }
            ctx.json(Map.of("samples", results, "rawBarRanges", rawRanges));
        } catch (Exception e) {
            logger.error("Regime sample diagnostic failed", e);
            ctx.status(500).json(Map.of("error", e.getMessage()));
        }
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
            // intrabar=false reproduces the pre-2026-09-24 15-min-sampled exits (for comparison only)
            harness.setIntrabarExits(!"false".equalsIgnoreCase(ctx.queryParam("intrabar")));
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
