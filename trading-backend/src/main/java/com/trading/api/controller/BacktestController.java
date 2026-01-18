package com.trading.api.controller;

import com.trading.backtesting.Backtester;
import io.javalin.Javalin;
import io.javalin.http.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * REST API controller for running backtests.
 */
public final class BacktestController {
    private static final Logger logger = LoggerFactory.getLogger(BacktestController.class);

    public void registerRoutes(Javalin app) {
        app.post("/api/backtest", this::runBacktest);
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
