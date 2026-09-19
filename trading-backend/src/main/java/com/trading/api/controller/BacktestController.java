package com.trading.api.controller;

import com.trading.api.AlpacaClient;
import com.trading.backtesting.Backtester;
import com.trading.config.Config;
import com.trading.strategy.MACDStrategy;
import com.trading.strategy.TradingSignal;
import io.javalin.Javalin;
import io.javalin.http.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.ZoneId;
import java.util.ArrayList;
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
