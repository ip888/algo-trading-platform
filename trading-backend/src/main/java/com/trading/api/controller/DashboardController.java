package com.trading.api.controller;

import com.trading.analysis.MarketAnalyzer;
import com.trading.analysis.MarketRegimeDetector.MarketRegime;
import com.trading.api.ResilientBrokerClient;
import com.trading.backtest.BacktestEngine;
import com.trading.config.Config;
import com.trading.filters.MarketHoursFilter;
import com.trading.filters.VolatilityFilter;
import com.trading.persistence.TradeDatabase;
import com.trading.autonomous.TradeAnalytics;
import com.trading.portfolio.PortfolioManager;
import com.trading.portfolio.ProfileManager;
import com.trading.validation.OrderRequest;
import io.javalin.Javalin;
import io.javalin.http.Context;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * REST API controller for the trading dashboard.
 * Provides endpoints for portfolio, trades, market analysis, and system status.
 */
public final class DashboardController {
    private static final Logger logger = LoggerFactory.getLogger(DashboardController.class);
    private static final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
    
    private final TradeDatabase database;
    private final PortfolioManager portfolio;
    private final MarketAnalyzer marketAnalyzer;
    private final MarketHoursFilter marketHoursFilter;
    private final VolatilityFilter volatilityFilter;
    private final Config config;
    private final TradeAnalytics tradeAnalytics;
    private final ResilientBrokerClient alpacaClient;
    // Cached per-request-free broker clients — created once, reused for every dashboard call.
    // Avoids the "TradierClient initialized" log spam every 2 minutes from health-check threads.
    private final com.trading.api.AlpacaClient cachedAlpacaClient;
    private final com.trading.api.TradierClient cachedTradierClient; // null if Tradier not configured

    public DashboardController(TradeDatabase database, PortfolioManager portfolio,
                              MarketAnalyzer marketAnalyzer, MarketHoursFilter marketHoursFilter,
                              VolatilityFilter volatilityFilter, Config config,
                              TradeAnalytics tradeAnalytics) {
        this(database, portfolio, marketAnalyzer, marketHoursFilter, volatilityFilter, config, tradeAnalytics, null);
    }

    public DashboardController(TradeDatabase database, PortfolioManager portfolio,
                              MarketAnalyzer marketAnalyzer, MarketHoursFilter marketHoursFilter,
                              VolatilityFilter volatilityFilter, Config config,
                              TradeAnalytics tradeAnalytics, ResilientBrokerClient alpacaClient) {
        this.database = database;
        this.portfolio = portfolio;
        this.marketAnalyzer = marketAnalyzer;
        this.marketHoursFilter = marketHoursFilter;
        this.volatilityFilter = volatilityFilter;
        this.config = config;
        this.tradeAnalytics = tradeAnalytics;
        this.alpacaClient = alpacaClient;
        this.cachedAlpacaClient = new com.trading.api.AlpacaClient(config);
        String brokersAlloc = config.getBrokersAllocation();
        this.cachedTradierClient = (brokersAlloc != null && brokersAlloc.toLowerCase().contains("tradier"))
            ? new com.trading.api.TradierClient(config) : null;
    }
    
    /**
     * Register all API routes.
     */
    public void registerRoutes(Javalin app) {
        // Portfolio endpoints
        app.get("/api/portfolio", this::getPortfolio);
        app.get("/api/portfolio/positions", this::getPositions);
        app.get("/api/positions", this::getPositions); // Alias for frontend
        
        // Trade history endpoints
        app.get("/api/trades", this::getTrades);
        app.get("/api/trades/stats", this::getTradeStats);
        app.get("/api/trades/recent", this::getRecentTrades); // NEW: Recent trades for execution archive
        
        // Market analysis endpoints
        app.get("/api/market/analysis", this::getMarketAnalysis);
        app.get("/api/market/status", this::getMarketStatus);
        app.get("/api/market/vix", this::getVIX); // NEW: VIX data
        app.get("/api/market/regime", this::getMarketRegime); // NEW: Market regime
        
        // System status endpoints
        app.get("/api/system/status", this::getSystemStatus);
        app.get("/api/system/config", this::getConfig);
        app.get("/api/config", this::getConfig); // Alias for UI compatibility
        
        // AI metrics endpoint
        app.get("/api/ai/metrics", this::getAIMetrics);
        
        // Analytics endpoints (Task 3)
        app.get("/api/analytics", this::getAnalytics);
        app.get("/api/analytics/journal", this::getTradeJournal);
        
        // Order validation endpoint (demo)
        app.post("/api/orders/validate", this::validateOrder);
        
        // UI-friendly endpoints (aliases)
        app.get("/api/status", this::getBotStatus);
        app.get("/api/account", this::getAccountData);
        
        // Safety Autopilot Endpoints
        app.post("/api/emergency/panic", this::handlePanic);
        app.post("/api/emergency/reset", this::handleReset);
        app.get("/api/emergency/status", this::getEmergencyStatus);
        app.get("/api/heartbeat", this::getHeartbeat);

        app.post("/api/trading/pause", this::handleTradingPause);
        app.post("/api/trading/resume", this::handleTradingResume);
        app.get("/api/trading/paused", this::getTradingPausedStatus);
        
        // Health check endpoint for Docker/Kubernetes
        app.get("/api/health", this::getHealth);
        
        // Watchlist data endpoint (REST fallback for initial load)
        app.get("/api/watchlist", this::getWatchlist);
        
        // Market hours endpoint (REST fallback for initial load)
        app.get("/api/market/hours", this::getMarketHours);
        
        // System health for UI (detailed format)
        app.get("/api/system/health", this::getSystemHealth);
        
        // Profit targets endpoint (REST fallback for initial load)
        app.get("/api/profit-targets", this::getProfitTargets);
        
        // Manual position close endpoint (for take profit issues)
        app.post("/api/positions/{symbol}/close", this::closePosition);

        // Trade export endpoints
        app.get("/api/trades/export/json", this::exportTradesJson);
        app.get("/api/trades/export/csv", this::exportTradesCsv);

        // Diagnostic: Alpaca order history (bypasses local DB)
        app.get("/api/orders/history", this::getOrderHistory);
        app.get("/api/orders/fills", this::getOrderFills);

        // Backtesting endpoint
        app.get("/api/backtest", this::runBacktest);

        // Bot behavior health monitor
        app.get("/api/bot/behavior", this::getBotBehavior);

        // Multi-broker endpoints
        app.get("/api/brokers", this::getBrokers);
        app.get("/api/brokers/status", this::getBrokerStatus);
        app.get("/api/trades/by-broker", this::getTradesByBroker);
    }
    
    /**
     * Close a position manually by symbol.
     * POST /api/positions/{symbol}/close
     */
    private void closePosition(Context ctx) {
        String symbol = ctx.pathParam("symbol");
        logger.info("🔧 Manual close requested for {}", symbol);
        
        try {
            var client = cachedAlpacaClient;
            var positions = client.getPositions();
            
            // Find the position
            var position = positions.stream()
                .filter(p -> p.symbol().equalsIgnoreCase(symbol))
                .findFirst();
            
            if (position.isEmpty()) {
                ctx.status(404).json(Map.of("error", "Position not found: " + symbol));
                return;
            }
            
            var pos = position.get();
            double qty = pos.quantity();
            double entryPrice = pos.avgEntryPrice();
            double currentPrice = Math.abs(pos.marketValue() / qty);
            double pnlDollars = (currentPrice - entryPrice) * qty;
            double pnlPercent = ((currentPrice - entryPrice) / entryPrice) * 100.0;
            
            // Cancel any existing orders first to free up held shares
            try {
                var openOrders = client.getOpenOrders(symbol);
                if (openOrders.isArray()) {
                    for (var order : openOrders) {
                        String orderId = order.get("id").asText();
                        client.cancelOrder(orderId);
                        logger.info("Canceled existing order {} for {} before manual close", orderId, symbol);
                    }
                    if (openOrders.size() > 0) {
                        Thread.sleep(500); // Brief delay for order cancellation to settle
                    }
                }
            } catch (Exception cancelEx) {
                logger.warn("Could not cancel existing orders for {}: {}", symbol, cancelEx.getMessage());
            }

            // Place sell order
            client.placeOrder(symbol, qty, "sell", "market", "day", null);
            
            // Record in DB
            database.closeTrade(symbol, java.time.Instant.now(), currentPrice, pnlDollars, "alpaca");
            
            logger.info("✅ Manually closed {}: qty={}, entry=${}, exit=${}, P&L=${} ({}%)",
                symbol, qty, entryPrice, currentPrice, String.format("%.2f", pnlDollars), String.format("%.2f", pnlPercent));
            
            ctx.json(Map.of(
                "success", true,
                "symbol", symbol,
                "quantity", qty,
                "entryPrice", entryPrice,
                "exitPrice", currentPrice,
                "pnlDollars", pnlDollars,
                "pnlPercent", pnlPercent
            ));
        } catch (Exception e) {
            logger.error("❌ Failed to close {}: {}", symbol, e.getMessage(), e);
            ctx.status(500).json(Map.of("error", e.getMessage()));
        }
    }
    
    /**
     * Profit targets endpoint for UI - returns position profit tracking data.
     */
    private void getProfitTargets(Context ctx) {
        var targets = new java.util.ArrayList<Map<String, Object>>();
        
        try {
            var client = cachedAlpacaClient;
            var positions = client.getPositions();
            double takeProfitPercent = config.getMainTakeProfitPercent();
            
            for (var pos : positions) {
                var target = new HashMap<String, Object>();
                double entryPrice = pos.avgEntryPrice();
                double currentPrice = Math.abs(pos.marketValue() / pos.quantity());
                double pnlPercent = ((currentPrice - entryPrice) / entryPrice) * 100.0;
                double distancePercent = takeProfitPercent - pnlPercent;
                
                target.put("symbol", pos.symbol());
                target.put("currentPnlPercent", pnlPercent);
                target.put("targetPercent", takeProfitPercent);
                target.put("distancePercent", distancePercent);
                target.put("eta", distancePercent < 0.5 ? "Soon" : "-");
                targets.add(target);
            }
        } catch (Exception e) {
            // Return empty array if can't fetch positions
        }
        
        ctx.json(targets);
    }
    
    /**
     * Market hours endpoint for UI - returns detailed market phase info.
     */
    private void getMarketHours(Context ctx) {
        var hours = new HashMap<String, Object>();
        boolean isOpen = marketHoursFilter.isMarketOpen();
        hours.put("isOpen", isOpen);
        
        // Determine phase based on current time
        var now = java.time.ZonedDateTime.now(java.time.ZoneId.of("America/New_York"));
        int hour = now.getHour();
        int minute = now.getMinute();
        int currentMinutes = hour * 60 + minute;
        
        String phase;
        int minutesToOpen = 0;
        int minutesToClose = 0;
        
        // Market hours: 9:30 AM - 4:00 PM ET
        int marketOpen = 9 * 60 + 30;  // 9:30 AM = 570 minutes
        int marketClose = 16 * 60;      // 4:00 PM = 960 minutes
        int preMarketStart = 4 * 60;    // 4:00 AM
        int postMarketEnd = 20 * 60;    // 8:00 PM
        
        if (now.getDayOfWeek() == java.time.DayOfWeek.SATURDAY || now.getDayOfWeek() == java.time.DayOfWeek.SUNDAY) {
            phase = "CLOSED";
            // Calculate minutes to Monday 9:30 AM
            int daysUntilMonday = now.getDayOfWeek() == java.time.DayOfWeek.SATURDAY ? 2 : 1;
            minutesToOpen = daysUntilMonday * 24 * 60 + (marketOpen - currentMinutes);
            if (minutesToOpen < 0) minutesToOpen += 24 * 60;
        } else if (currentMinutes >= marketOpen && currentMinutes < marketClose) {
            phase = "OPEN";
            minutesToClose = marketClose - currentMinutes;
        } else if (currentMinutes >= preMarketStart && currentMinutes < marketOpen) {
            phase = "PRE_MARKET";
            minutesToOpen = marketOpen - currentMinutes;
        } else if (currentMinutes >= marketClose && currentMinutes < postMarketEnd) {
            phase = "POST_MARKET";
            // Calculate to next day 9:30 AM
            minutesToOpen = (24 * 60 - currentMinutes) + marketOpen;
        } else {
            phase = "CLOSED";
            if (currentMinutes < preMarketStart) {
                minutesToOpen = marketOpen - currentMinutes;
            } else {
                minutesToOpen = (24 * 60 - currentMinutes) + marketOpen;
            }
        }
        
        hours.put("phase", phase);
        hours.put("minutesToOpen", minutesToOpen);
        hours.put("minutesToClose", minutesToClose);
        hours.put("nextOpen", now.plusMinutes(minutesToOpen).toString());
        hours.put("nextClose", now.plusMinutes(minutesToClose).toString());
        
        ctx.json(hours);
    }
    
    /**
     * System health endpoint for UI - returns detailed component status.
     */
    private void getSystemHealth(Context ctx) {
        var health = new HashMap<String, Object>();
        var details = com.trading.bot.TradingBot.getHeartbeatDetails();
        boolean isHealthy = com.trading.bot.TradingBot.isSystemHealthy();
        long uptimeMs = System.currentTimeMillis() - com.trading.bot.TradingBot.getStartTime();
        
        health.put("overall", isHealthy ? "HEALTHY" : "DEGRADED");
        health.put("uptimeSeconds", uptimeMs / 1000);
        health.put("timestamp", java.time.Instant.now().toString());
        health.put("recommendation", isHealthy ? "All systems operational" : "Check component status");
        
        // Convert component details to expected format
        var components = new java.util.ArrayList<Map<String, Object>>();
        for (var entry : details.entrySet()) {
            var comp = new HashMap<String, Object>();
            comp.put("component", entry.getKey());
            // If the value is a number (seconds since last heartbeat), determine status
            if (entry.getValue() instanceof Number) {
                int seconds = ((Number) entry.getValue()).intValue();
                comp.put("status", seconds < 60 ? "HEALTHY" : seconds < 300 ? "DEGRADED" : "CRITICAL");
                comp.put("message", "Last heartbeat: " + seconds + "s ago");
            } else {
                comp.put("status", "HEALTHY");
                comp.put("message", String.valueOf(entry.getValue()));
            }
            components.add(comp);
        }
        health.put("components", components);
        
        ctx.json(health);
    }
    
    /**
     * Health check endpoint for container orchestration.
     * Returns 200 if bot is healthy, 503 if unhealthy.
     */
    private void getHealth(Context ctx) {
        try {
            boolean isHealthy = com.trading.bot.TradingBot.isSystemHealthy();
            var details = com.trading.bot.TradingBot.getHeartbeatDetails();
            
            if (isHealthy) {
                ctx.status(200).json(Map.of(
                    "status", "healthy",
                    "timestamp", java.time.Instant.now().toString(),
                    "components", details
                ));
            } else {
                ctx.status(503).json(Map.of(
                    "status", "unhealthy",
                    "timestamp", java.time.Instant.now().toString(),
                    "components", details
                ));
            }
        } catch (Exception e) {
            ctx.status(503).json(Map.of(
                "status", "error",
                "message", e.getMessage()
            ));
        }
    }
    
    /**
     * Validate order request with Bean Validation.
     */
    private void validateOrder(Context ctx) {
        try {
            var orderRequest = ctx.bodyAsClass(OrderRequest.class);
            
            // Validate using Bean Validation
            var violations = validator.validate(orderRequest);
            
            if (!violations.isEmpty()) {
                var errors = violations.stream()
                    .map(v -> Map.of(
                        "field", v.getPropertyPath().toString(),
                        "message", v.getMessage()
                    ))
                    .toList();
                
                ctx.status(400).json(Map.of(
                    "valid", false,
                    "errors", errors
                ));
                return;
            }
            
            // Additional business validation
            try {
                orderRequest.validateLimitOrder();
                orderRequest.validateStopOrder();
            } catch (IllegalArgumentException e) {
                ctx.status(400).json(Map.of(
                    "valid", false,
                    "errors", java.util.List.of(Map.of(
                        "field", "type",
                        "message", e.getMessage()
                    ))
                ));
                return;
            }
            
            ctx.json(Map.of(
                "valid", true,
                "message", "Order request is valid"
            ));
            
            logger.info("Order validation successful: {} {} @ {}",
                orderRequest.side(), orderRequest.symbol(), orderRequest.quantity());
            
        } catch (Exception e) {
            logger.error("Order validation failed", e);
            ctx.status(400).json(Map.of(
                "valid", false,
                "error", e.getMessage()
            ));
        }
    }
    
    private void getPortfolio(Context ctx) {
        var response = new HashMap<String, Object>();
        response.put("activePositions", portfolio.getActivePositionCount());
        response.put("symbols", portfolio.getSymbols());
        response.put("capitalPerSymbol", portfolio.getCapitalPerSymbol());
        ctx.json(response);
    }
    
    /**
     * Helper to create a mutable map (robust serialization).
     */
    private Map<String, Object> createMap(Object... args) {
        Map<String, Object> map = new HashMap<>();
        for (int i = 0; i < args.length; i += 2) {
            if (i + 1 < args.length) {
                map.put(String.valueOf(args[i]), args[i + 1]);
            }
        }
        return map;
    }

    /**
     * Get all current positions from Alpaca.
     * Works in both single-profile and multi-profile modes.
     */
    private void getPositions(Context ctx) {
        try {
            // Query Alpaca directly for positions
            var client = cachedAlpacaClient;
            var alpacaPositions = client.getPositions();
            
            // Convert to list of maps to inject custom fields
            var result = new java.util.ArrayList<Map<String, Object>>();
            
            for (var pos : alpacaPositions) {
                Map<String, Object> positionMap = new java.util.HashMap<>();
                // Map standard Alpaca fields using Record accessors
                positionMap.put("symbol", pos.symbol());
                positionMap.put("qty", pos.quantity());
                positionMap.put("quantity", pos.quantity()); // Frontend expects this alias
                positionMap.put("entry_price", pos.avgEntryPrice());
                positionMap.put("entryPrice", pos.avgEntryPrice());
                
                // Derived fields since Record is minimal
                double currentPrice = pos.quantity() != 0 ? pos.marketValue() / pos.quantity() : 0.0;
                double costBasis = pos.quantity() * pos.avgEntryPrice();
                double plpc = costBasis != 0 ? pos.unrealizedPL() / costBasis : 0.0;
                
                positionMap.put("current_price", currentPrice);
                positionMap.put("currentPrice", currentPrice);
                positionMap.put("unrealized_pl", pos.unrealizedPL());
                positionMap.put("unrealized_plpc", plpc);
                positionMap.put("market_value", pos.marketValue());
                positionMap.put("broker", "alpaca");
                positionMap.put("platform", "alpaca");

                // Inject Risk Management Data (TP/SL) from PortfolioManager
                // Falls back to config-derived values so dashboard always shows protection levels
                var managedPos = portfolio.getPosition(pos.symbol());
                double entryPx = pos.avgEntryPrice();
                double sl, tp;
                if (managedPos.isPresent() && managedPos.get().stopLoss() > 0) {
                    sl = managedPos.get().stopLoss();
                    tp = managedPos.get().takeProfit();
                } else {
                    // Fallback: compute from MAIN profile config so dashboard is never blank
                    sl = entryPx * (1.0 - config.getMainStopLossPercent() / 100.0);
                    tp = entryPx * (1.0 + config.getMainTakeProfitPercent() / 100.0);
                }
                positionMap.put("stopLoss", sl);
                positionMap.put("takeProfit", tp);
                positionMap.put("stop_loss", sl);
                positionMap.put("take_profit", tp);
                result.add(positionMap);
            }

            // Fetch Tradier positions if configured
            String brokersAlloc = config.getBrokersAllocation();
            if (brokersAlloc != null && brokersAlloc.toLowerCase().contains("tradier")) {
                try {
                    var tradierPositions = cachedTradierClient.getPositions();
                    for (var pos : tradierPositions) {
                        Map<String, Object> positionMap = new java.util.HashMap<>();
                        positionMap.put("symbol", pos.symbol());
                        positionMap.put("qty", pos.quantity());
                        positionMap.put("quantity", pos.quantity());
                        positionMap.put("entry_price", pos.avgEntryPrice());
                        positionMap.put("entryPrice", pos.avgEntryPrice());
                        double currentPrice = pos.quantity() != 0 ? pos.marketValue() / pos.quantity() : 0.0;
                        double costBasis = pos.quantity() * pos.avgEntryPrice();
                        double plpc = costBasis != 0 ? pos.unrealizedPL() / costBasis : 0.0;
                        positionMap.put("current_price", currentPrice);
                        positionMap.put("currentPrice", currentPrice);
                        positionMap.put("unrealized_pl", pos.unrealizedPL());
                        positionMap.put("unrealized_plpc", plpc);
                        positionMap.put("market_value", pos.marketValue());
                        positionMap.put("broker", "tradier");
                        positionMap.put("platform", "tradier");
                        double entryPx = pos.avgEntryPrice();
                        positionMap.put("stopLoss", entryPx * (1.0 - config.getMainStopLossPercent() / 100.0));
                        positionMap.put("takeProfit", entryPx * (1.0 + config.getMainTakeProfitPercent() / 100.0));
                        result.add(positionMap);
                    }
                } catch (Exception te) {
                    logger.warn("Failed to fetch Tradier positions for dashboard: {}", te.getMessage());
                }
            }

            ctx.json(result);
        } catch (Exception e) {
            logger.error("Failed to fetch positions", e);
            ctx.status(500).json(createMap("error", "Failed to fetch positions: " + e.getMessage()));
        }
    }
    
    private void getTrades(Context ctx) {
        int limit = ctx.queryParamAsClass("limit", Integer.class).getOrDefault(100);
        var stats = new HashMap<String, Object>();
        stats.put("totalTrades", database.getTotalTrades());
        stats.put("totalPnL", database.getTotalPnL());
        stats.put("limit", limit);
        ctx.json(stats);
    }
    
    private void getTradeStats(Context ctx) {
        var stats = new HashMap<String, Object>();
        stats.put("totalTrades", database.getTotalTrades());
        stats.put("totalPnL", database.getTotalPnL());
        stats.put("winRate", portfolio.getWinRate());
        ctx.json(stats);
    }
    
    private void getMarketAnalysis(Context ctx) {
        var analysis = marketAnalyzer.analyze(portfolio.getSymbols());
        ctx.json(analysis);
    }
    
    private void getMarketStatus(Context ctx) {
        var status = new HashMap<String, Object>();
        status.put("isOpen", marketHoursFilter.isMarketOpen());
        status.put("volatilityAcceptable", volatilityFilter.isVolatilityAcceptable());
        status.put("currentVIX", volatilityFilter.getCurrentVIX());
        ctx.json(status);
    }
    
    private void getSystemStatus(Context ctx) {
        var status = new HashMap<String, Object>();
        status.put("tradingMode", config.getTradingMode());
        status.put("pdtProtectionEnabled", config.isPDTProtectionEnabled());
        status.put("testModeEnabled", config.isTestModeEnabled());
        status.put("activePositions", portfolio.getActivePositionCount());
        ctx.json(status);
    }
    
    private void getConfig(Context ctx) {
        var configData = new HashMap<String, Object>();
        configData.put("tradingMode", config.getTradingMode());
        configData.put("initialCapital", config.getInitialCapital());
        
        // Main Profile
        var mainProfile = new HashMap<String, Object>();
        mainProfile.put("takeProfitPercent", config.getMainTakeProfitPercent());
        mainProfile.put("stopLossPercent", config.getMainStopLossPercent());
        mainProfile.put("trailingStopPercent", config.getMainTrailingStopPercent());
        mainProfile.put("capitalPercent", config.getMainProfileCapitalPercent());
        mainProfile.put("bullishSymbols", config.getMainBullishSymbols());
        mainProfile.put("bearishSymbols", config.getMainBearishSymbols());
        configData.put("mainProfile", mainProfile);
        
        // Experimental Profile
        var expProfile = new HashMap<String, Object>();
        expProfile.put("takeProfitPercent", config.getExperimentalTakeProfitPercent());
        expProfile.put("stopLossPercent", config.getExperimentalStopLossPercent());
        expProfile.put("trailingStopPercent", config.getExperimentalTrailingStopPercent());
        expProfile.put("capitalPercent", config.getExperimentalProfileCapitalPercent());
        expProfile.put("bullishSymbols", config.getExperimentalBullishSymbols());
        expProfile.put("bearishSymbols", config.getExperimentalBearishSymbols());
        configData.put("experimentalProfile", expProfile);
        
        // Risk Management
        var riskMgmt = new HashMap<String, Object>();
        riskMgmt.put("maxLossPercent", config.getMaxLossPercent());
        riskMgmt.put("maxLossExitEnabled", config.isMaxLossExitEnabled());
        riskMgmt.put("portfolioStopLossPercent", config.getPortfolioStopLossPercent());
        riskMgmt.put("portfolioStopLossEnabled", config.isPortfolioStopLossEnabled());
        riskMgmt.put("pdtProtectionEnabled", config.isPDTProtectionEnabled());
        configData.put("riskManagement", riskMgmt);
        
        // Position Sizing
        var posSizing = new HashMap<String, Object>();
        posSizing.put("method", config.getPositionSizingMethod());
        posSizing.put("kellyFraction", config.getPositionSizingKellyFraction());
        posSizing.put("kellyRiskReward", config.getPositionSizingKellyRiskReward());
        posSizing.put("defaultWinRate", config.getPositionSizingDefaultWinRate());
        configData.put("positionSizing", posSizing);
        
        // Advanced Features
        var advanced = new HashMap<String, Object>();
        advanced.put("regimeDetectionEnabled", config.isRegimeDetectionEnabled());
        advanced.put("multiTimeframeEnabled", config.isMultiTimeframeEnabled());
        advanced.put("multiProfileEnabled", config.isMultiProfileEnabled());
        advanced.put("currentRegime", volatilityFilter != null ? 
            (volatilityFilter.getCurrentVIX() > config.getVixThreshold() ? "BEARISH" : "BULLISH") : "UNKNOWN");
        configData.put("advancedFeatures", advanced);
        
        // VIX Settings
        var vixSettings = new HashMap<String, Object>();
        vixSettings.put("threshold", config.getVixThreshold());
        vixSettings.put("hysteresis", config.getVixHysteresis());
        configData.put("vixSettings", vixSettings);
        
        // Rate Limiting
        var rateLimiting = new HashMap<String, Object>();
        rateLimiting.put("apiRequestDelayMs", config.getApiRequestDelayMs());
        rateLimiting.put("symbolBatchSize", config.getSymbolBatchSize());
        configData.put("rateLimiting", rateLimiting);
        
        ctx.json(configData);
    }
    
    private void getAIMetrics(Context ctx) {
        var tracker = com.trading.ai.AIMetricsTracker.getInstance();
        
        var metrics = new HashMap<String, Object>();
        metrics.put("sentimentScore", tracker.getLastSentimentScore());
        metrics.put("sentimentSymbol", tracker.getLastSentimentSymbol());
        metrics.put("mlWinProbability", tracker.getLastMLProbability());
        metrics.put("anomalySeverity", tracker.getLastAnomalySeverity());
        metrics.put("anomalyAction", tracker.getLastAnomalyAction());
        metrics.put("riskScore", tracker.getLastRiskScore());
        metrics.put("tradesFiltered", tracker.getTradesFilteredToday());
        
        ctx.json(metrics);
    }
    
    /**
     * Get bot status for UI (includes Phase 2 metrics).
     */
    private void getBotStatus(Context ctx) {
        try {
            var status = new HashMap<String, Object>();
            
            // Basic status
            status.put("marketStatus", marketHoursFilter.isMarketOpen() ? "OPEN" : "CLOSED");
            String regime = com.trading.portfolio.ProfileManager.getLatestRegimeSnapshot();
            status.put("regime", regime.isEmpty() ? "UNKNOWN" : regime);
            double vix = com.trading.portfolio.ProfileManager.getLatestVixSnapshot();
            if (vix <= 0 && volatilityFilter != null) vix = volatilityFilter.getCurrentVIX();
            status.put("vix", vix);
            status.put("tradingMode", config.getTradingMode());
            
            // Phase 2 metrics
            status.put("pdtProtectionEnabled", config.isPDTProtectionEnabled());
            status.put("capitalReserveEnabled", true); // Always enabled in Phase 2
            status.put("capitalReservePercent", 0.25); // 25% reserve
            status.put("opportunityTrackingEnabled", true); // Always enabled in Phase 2
            
            // Active positions
            status.put("activePositions", portfolio.getActivePositionCount());
            status.put("maxPositions", config.getMaxPositionsAtOnce());
            
            ctx.json(status);
        } catch (Exception e) {
            logger.error("Failed to get bot status", e);
            ctx.status(500).json(Map.of("error", "Failed to get status: " + e.getMessage()));
        }
    }
    
    /**
     * Get account data for UI (includes Phase 2 capital reserve info).
     */
    private void getAccountData(Context ctx) {
        try {
            var client = cachedAlpacaClient;
            var account = client.getAccount();
            
            var accountData = new HashMap<String, Object>();
            double equity = account.get("equity").asDouble();
            double lastEquity = account.has("last_equity") ? account.get("last_equity").asDouble() : equity;
            
            accountData.put("equity", equity);
            accountData.put("lastEquity", lastEquity);
            accountData.put("buyingPower", account.get("buying_power").asDouble());
            accountData.put("cash", account.get("cash").asDouble());
            
            // Phase 2: Capital Reserve calculation
            double reservePercent = config.getSmartCapitalReservePercent();
            double reserveAmount = equity * reservePercent;
            double deployableCapital = equity * (1 - reservePercent);
            
            accountData.put("capitalReserve", reserveAmount);
            accountData.put("deployableCapital", deployableCapital);
            accountData.put("capitalReservePercent", reservePercent * 100);
            
            // Risk parameters
            accountData.put("mainTakeProfitPercent", config.getMainTakeProfitPercent());
            accountData.put("experimentalTakeProfitPercent", config.getExperimentalTakeProfitPercent());
            accountData.put("stopLossPercent", config.getMainStopLossPercent());

            // Session start capital (set once at bot startup from live Alpaca equity)
            double sessionStart = com.trading.bot.TradingBot.getSessionStartCapital();
            accountData.put("sessionStartCapital", sessionStart > 0 ? sessionStart : equity);

            // Capital tier classification + parameters
            var tier = com.trading.risk.CapitalTierManager.getTier(equity);
            var tierParams = com.trading.risk.CapitalTierManager.getParameters(equity);
            accountData.put("capitalTier", tier.name());
            accountData.put("tierMaxPositionPercent", tierParams.maxPositionPercent() * 100);
            accountData.put("tierRiskPerTradePercent", tierParams.riskPerTradePercent() * 100);
            accountData.put("tierMaxPositions", tierParams.maxPositions());
            accountData.put("tierMinPositionValue", tierParams.minPositionValue());
            accountData.put("tierPreferWholeShares", tierParams.preferWholeShares());

            // EOD exit config
            accountData.put("eodExitEnabled", config.isEodExitEnabled());
            accountData.put("eodProfitLockEnabled", config.isEODProfitLockEnabled());

            ctx.json(accountData);
        } catch (Exception e) {
            logger.error("Failed to get account data", e);
            ctx.status(500).json(Map.of("error", "Failed to get account: " + e.getMessage()));
        }
    }
    
    /**
     * Get comprehensive trade analytics and insights (Task 3).
     */
    private void getAnalytics(Context ctx) {
        try {
            var analytics = new HashMap<String, Object>();
            
            // Database statistics
            var dbStats = database.getTradeStatistics();
            analytics.put("totalTrades", dbStats.totalTrades());
            analytics.put("winRate", dbStats.winRate());
            analytics.put("totalPnL", dbStats.totalPnL());
            
            // TradeAnalytics insights
            if (tradeAnalytics != null) {
                analytics.put("bestTradingHours", tradeAnalytics.getBestTradingHours());
                analytics.put("worstTradingHours", tradeAnalytics.getWorstTradingHours());
                analytics.put("bestSymbols", tradeAnalytics.getBestSymbols());
                analytics.put("symbolsToAvoid", tradeAnalytics.getSymbolsToAvoid());
            }
            
            // Performance metrics
            double pnl = dbStats.totalPnL();
            double capital = config.getInitialCapital();
            analytics.put("returnPercent", capital > 0 ? (pnl / capital) * 100 : 0);
            
            // Daily P&L (approximate)
            analytics.put("todayPnL", pnl); // TODO: Filter by today's date
            
            ctx.json(analytics);
        } catch (Exception e) {
            logger.error("Failed to get analytics", e);
            ctx.status(500).json(Map.of("error", "Failed to get analytics: " + e.getMessage()));
        }
    }
    
    /**
     * Get trade journal with detailed trade history (Task 3).
     */
    private void getTradeJournal(Context ctx) {
        try {
            var journal = new HashMap<String, Object>();
            
            // Basic stats
            journal.put("totalTrades", database.getTotalTrades());
            journal.put("totalPnL", database.getTotalPnL());
            
            // Full analytics report from TradeAnalytics
            if (tradeAnalytics != null) {
                journal.put("report", tradeAnalytics.getAnalyticsReport());
            }
            
            ctx.json(journal);
        } catch (Exception e) {
            logger.error("Failed to get trade journal", e);
            ctx.status(500).json(Map.of("error", "Failed to get journal: " + e.getMessage()));
        }
    }
    
    private void handlePanic(Context ctx) {
        String reason = ctx.queryParam("reason");
        if (reason == null || reason.isBlank()) {
            reason = "Dashboard Panic Button";
        }
        
        logger.error("🚨 MANUAL PANIC BUTTON TRIGGERED FROM DASHBOARD 🚨");
        logger.error("🚨 Reason: {} 🚨", reason);
        
        var result = com.trading.bot.TradingBot.triggerManualPanic(reason);
        
        // Broadcast to all connected clients
        com.trading.websocket.TradingWebSocketHandler.broadcastActivity(
            "🚨 EMERGENCY PROTOCOL ACTIVATED: " + reason, "CRITICAL");
        
        ctx.json(result);
    }
    
    private void handleReset(Context ctx) {
        logger.warn("🔄 EMERGENCY RESET REQUESTED FROM DASHBOARD");
        
        var result = com.trading.bot.TradingBot.resetEmergencyProtocol();
        
        com.trading.websocket.TradingWebSocketHandler.broadcastActivity(
            "🔄 Emergency Protocol RESET - System back to normal", "INFO");
        
        ctx.json(result);
    }
    
    private void getEmergencyStatus(Context ctx) {
        boolean triggered = com.trading.bot.TradingBot.isEmergencyTriggered();
        ctx.json(Map.of(
            "triggered", triggered,
            "status", triggered ? "EMERGENCY_ACTIVE" : "NORMAL"
        ));
    }
    
    private void getHeartbeat(Context ctx) {
        var details = com.trading.bot.TradingBot.getHeartbeatDetails();
        boolean isHealthy = com.trading.bot.TradingBot.isSystemHealthy();
        ctx.json(Map.of(
            "status", isHealthy ? "ok" : "critical",
            "components", details
        ));
    }

    private void handleTradingPause(Context ctx) {
        com.trading.bot.TradingBot.pauseTrading();
        com.trading.websocket.TradingWebSocketHandler.broadcastActivity(
            "⏸ TRADING PAUSED — no new entries until resumed", "WARN");
        ctx.json(Map.of("paused", true));
    }

    private void handleTradingResume(Context ctx) {
        com.trading.bot.TradingBot.resumeTrading();
        com.trading.websocket.TradingWebSocketHandler.broadcastActivity(
            "▶ TRADING RESUMED — new entries re-enabled", "INFO");
        ctx.json(Map.of("paused", false));
    }

    private void getTradingPausedStatus(Context ctx) {
        ctx.json(Map.of("paused", com.trading.bot.TradingBot.isTradingPaused()));
    }
    
    /**
     * Get current VIX (volatility index) value.
     */
    private void getVIX(Context ctx) {
        try {
            var symbols = portfolio.getSymbols().isEmpty() 
                ? java.util.List.of("SPY", "QQQ", "VIX") 
                : portfolio.getSymbols();
            
            double vix = 20.0; // Safe default
            String level = "NORMAL";
            
            try {
                var analysis = marketAnalyzer.analyze(symbols);
                if (analysis != null) {
                    vix = analysis.vix();
                    // Handle NaN or Infinite which breaks JSON
                    if (Double.isNaN(vix) || Double.isInfinite(vix)) {
                        vix = 20.0; 
                    }
                    level = vix < 15 ? "LOW" : vix < 20 ? "NORMAL" : vix < 30 ? "ELEVATED" : "HIGH";
                }
            } catch (Exception e) {
               logger.warn("Market analysis failed, using defaults", e);
            }
            
            ctx.json(createMap(
                "vix", vix,
                "level", level,
                "timestamp", System.currentTimeMillis()
            ));
        } catch (Throwable e) {
            // Ultimate fallback for any error (including NoClassDefFoundError)
            logger.error("Critical error in getVIX", e);
            ctx.json(createMap("vix", 20.0, "level", "NORMAL", "timestamp", System.currentTimeMillis()));
        }
    }
    
    /**
     * Get current market regime.
     */
    private void getMarketRegime(Context ctx) {
        try {
            // Use default symbols if portfolio is empty
            var symbols = portfolio.getSymbols().isEmpty() 
                ? java.util.List.of("SPY", "QQQ", "VIX") 
                : portfolio.getSymbols();
            
            String regime = "SIDEWAYS";
            double vix = 20.0;
            double strength = 50.0;
            String trend = "NEUTRAL";
            
            try {
                var analysis = marketAnalyzer.analyze(symbols);
                if (analysis != null) {
                    regime = analysis.trend().toString();
                    vix = analysis.vix();
                    strength = analysis.marketStrength();
                    trend = analysis.trend().toString();
                }
            } catch (Exception e) {
                logger.warn("Market analysis failed, using defaults", e);
            }
            
            ctx.json(createMap(
                "regime", regime,
                "vix", vix,
                "marketStrength", strength,
                "trend", trend,
                "timestamp", System.currentTimeMillis()
            ));
        } catch (Exception e) {
            // Ultimate fallback
            ctx.json(createMap("regime", "SIDEWAYS", "vix", 20.0, "marketStrength", 50.0, "trend", "NEUTRAL", "timestamp", System.currentTimeMillis()));
        }
    }
    
    /**
     * Get recent trades for execution archive.
     */
    private void getRecentTrades(Context ctx) {
        try {
            int limit = Integer.parseInt(ctx.queryParamAsClass("limit", String.class).getOrDefault("20"));
            var trades = database.getRecentTrades(limit);
            
            ctx.json(trades);
        } catch (Exception e) {
            logger.error("Failed to get recent trades", e);
            ctx.status(500).json(Map.of("error", "Failed to fetch recent trades"));
        }
    }
    
    /**
     * Get current watchlist with bot status per symbol.
     * Returns which symbols are held, targeted, blocked, or cooling down.
     */
    private void getWatchlist(Context ctx) {
        try {
            // Combine all configured symbols
            var watchlistSet = new java.util.HashSet<String>();
            watchlistSet.addAll(config.getBullishSymbols());
            watchlistSet.addAll(config.getBearishSymbols());
            watchlistSet.addAll(config.getMainBullishSymbols());
            watchlistSet.addAll(config.getMainBearishSymbols());

            // Current VIX / regime from bot snapshot
            double vix = com.trading.portfolio.ProfileManager.getLatestVixSnapshot();
            if (vix <= 0 && volatilityFilter != null) vix = volatilityFilter.getCurrentVIX();
            String regime = com.trading.portfolio.ProfileManager.getLatestRegimeSnapshot();

            // Per-symbol bot state
            var blockedBuys  = com.trading.portfolio.ProfileManager.getBlockedBuys();
            var cooldowns    = com.trading.portfolio.ProfileManager.getActiveCooldowns();
            String targetStr = com.trading.portfolio.ProfileManager.getLatestTargetSymbolsSnapshot();
            var targetSymbols = new java.util.HashSet<>(java.util.Arrays.asList(targetStr.split(",")));
            targetSymbols.remove("");

            // Currently held symbols from Alpaca — include entry price + unrealized P&L for display
            var heldSymbols    = new java.util.HashSet<String>();
            var heldEntryPrice = new java.util.HashMap<String, Double>();
            var heldUnrealPct  = new java.util.HashMap<String, Double>();
            try {
                if (alpacaClient != null) {
                    for (var pos : alpacaClient.getPositions()) {
                        heldSymbols.add(pos.symbol());
                        heldEntryPrice.put(pos.symbol(), pos.avgEntryPrice());
                        heldUnrealPct.put(pos.symbol(), pos.unrealizedPL() / (pos.avgEntryPrice() * pos.quantity()) * 100.0);
                    }
                }
            } catch (Exception ignored) { /* non-critical */ }

            // Always include held positions and active targets in the watchlist
            // even if they're not in the static config symbol lists
            watchlistSet.addAll(heldSymbols);
            watchlistSet.addAll(targetSymbols);

            long now = System.currentTimeMillis();
            var result = new java.util.ArrayList<Map<String, Object>>();

            for (String symbol : watchlistSet) {
                boolean inPosition  = heldSymbols.contains(symbol);
                boolean isTarget    = targetSymbols.contains(symbol);
                boolean blocked     = blockedBuys.containsKey(symbol);
                String  blockReason = blocked ? blockedBuys.get(symbol) : null;
                Long    cdExpiry    = cooldowns.get(symbol);
                boolean inCooldown  = cdExpiry != null && cdExpiry > now;
                long    cdMinutes   = inCooldown ? Math.max(0, (cdExpiry - now) / 60000) : 0;

                var item = new java.util.HashMap<String, Object>();
                item.put("symbol",          symbol);
                item.put("inPosition",      inPosition);
                item.put("isTarget",        isTarget);
                item.put("blocked",         blocked);
                item.put("blockReason",     blockReason);
                item.put("inCooldown",      inCooldown);
                item.put("cooldownMinutes", cdMinutes);
                // For held positions: include entry price and unrealized P&L% for display
                if (inPosition && heldEntryPrice.containsKey(symbol)) {
                    item.put("entryPrice",  heldEntryPrice.get(symbol));
                    item.put("unrealPct",   heldUnrealPct.getOrDefault(symbol, 0.0));
                }
                result.add(item);
            }

            ctx.json(Map.of(
                "watchlist", result,
                "vix",       vix,
                "regime",    regime,
                "targets",   targetStr,
                "timestamp", System.currentTimeMillis()
            ));
        } catch (Exception e) {
            logger.error("Failed to get watchlist", e);
            ctx.status(500).json(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Export trades as JSON.
     * GET /api/trades/export/json?status=CLOSED
     */
    private void exportTradesJson(Context ctx) {
        String status = ctx.queryParam("status");
        var trades = database.exportTrades(status);
        ctx.header("Content-Disposition", "attachment; filename=\"trades.json\"");
        ctx.json(trades);
    }

    /**
     * Export trades as CSV.
     * GET /api/trades/export/csv?status=CLOSED
     */
    private void exportTradesCsv(Context ctx) {
        String status = ctx.queryParam("status");
        String csv = database.exportTradesAsCsv(status);
        ctx.header("Content-Type", "text/csv");
        ctx.header("Content-Disposition", "attachment; filename=\"trades.csv\"");
        ctx.result(csv);
    }

    /**
     * GET /api/orders/history?symbol=GOOGL&limit=50
     * Returns Alpaca order history directly (bypasses local DB).
     */
    private void getOrderHistory(Context ctx) {
        String symbol = ctx.queryParam("symbol");
        int limit = ctx.queryParamAsClass("limit", Integer.class).getOrDefault(50);
        try {
            var client = cachedAlpacaClient;
            var orders = client.getOrderHistory(symbol, limit);
            ctx.json(orders);
        } catch (Exception e) {
            ctx.status(500).json(Map.of("error", e.getMessage()));
        }
    }

    /**
     * GET /api/orders/fills?limit=50
     * Returns recent fill activities from Alpaca.
     */
    private void getOrderFills(Context ctx) {
        int limit = ctx.queryParamAsClass("limit", Integer.class).getOrDefault(50);
        try {
            var client = cachedAlpacaClient;
            var fills = client.getAccountActivities("FILL", limit);
            ctx.json(fills);
        } catch (Exception e) {
            ctx.status(500).json(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Run a backtest on historical data.
     * GET /api/backtest?symbol=SPY&days=90&capital=1000&regime=RANGE_BOUND
     */
    /**
     * GET /api/bot/behavior
     * Returns traffic-light health indicators for the trading bot.
     */
    private void getBotBehavior(Context ctx) {
        try {
            var response = new HashMap<String, Object>();

            // --- Circuit Breaker ---
            String cbState = alpacaClient != null ? alpacaClient.getCircuitBreakerState() : "UNKNOWN";
            boolean circuitClosed = "CLOSED".equals(cbState);
            response.put("circuitBreakerState", cbState);

            // --- Active Cooldowns ---
            var cooldowns = ProfileManager.getActiveCooldowns();
            List<Map<String, Object>> cooldownList = new ArrayList<>();
            long now = System.currentTimeMillis();
            for (var entry : cooldowns.entrySet()) {
                long remainingMs = entry.getValue() - now;
                cooldownList.add(Map.of(
                    "symbol", entry.getKey(),
                    "expiresAt", entry.getValue(),
                    "remainingMinutes", Math.max(0, remainingMs / 60000)
                ));
            }
            response.put("activeCooldowns", cooldownList);

            // --- Consecutive Stop Losses ---
            var consecSL = ProfileManager.getConsecutiveStopLosses();
            response.put("consecutiveStopLosses", consecSL);
            int maxConsecSL = consecSL.values().stream().mapToInt(Integer::intValue).max().orElse(0);

            // --- Recent Trades (last 10 CLOSED only) ---
            // Use getRecentClosedTrades so OPEN trades (which may be many) don't crowd out
            // the list and make win-rate appear as 0% / "no completed trades yet".
            var closedTrades = database.getRecentClosedTrades(10);
            long wins = closedTrades.stream()
                .filter(t -> t.get("pnl") instanceof Number n && n.doubleValue() > 0)
                .count();
            long losses = closedTrades.stream()
                .filter(t -> t.get("pnl") instanceof Number n && n.doubleValue() < 0)
                .count();

            // Detect churn: same symbol appearing 4+ times in recent closed trades
            var symbolCounts = new HashMap<String, Integer>();
            for (var trade : closedTrades) {
                String sym = String.valueOf(trade.get("symbol"));
                symbolCounts.merge(sym, 1, Integer::sum);
            }
            boolean churning = symbolCounts.values().stream().anyMatch(count -> count >= 4);

            response.put("recentTradesWins", wins);
            response.put("recentTradeLosses", losses);
            response.put("recentTradeSymbolCounts", symbolCounts);
            response.put("openPositionsCount", portfolio.getActivePositionCount());

            // --- Health Checks (traffic-light signals) ---
            var checks = new ArrayList<Map<String, Object>>();

            checks.add(Map.of(
                "name", "Circuit Breaker",
                "status", circuitClosed ? "GREEN" : "RED",
                "detail", circuitClosed ? "API circuit breaker CLOSED (normal)" : "Circuit breaker OPEN — API calls blocked (" + cbState + ")"
            ));

            checks.add(Map.of(
                "name", "Stop-Loss Cooldowns",
                "status", cooldownList.isEmpty() ? "GREEN" : "YELLOW",
                "detail", cooldownList.isEmpty()
                    ? "No symbols in cooldown"
                    : cooldownList.size() + " symbol(s) cooling down: " + cooldownList.stream().map(c -> c.get("symbol")).toList()
            ));

            checks.add(Map.of(
                "name", "Consecutive Stop Losses",
                "status", maxConsecSL == 0 ? "GREEN" : maxConsecSL <= 2 ? "YELLOW" : "RED",
                "detail", maxConsecSL == 0
                    ? "No consecutive stop losses"
                    : "Max consecutive SLs: " + maxConsecSL + " — " + (maxConsecSL > 2 ? "circuit breaker risk!" : "monitor closely")
            ));

            checks.add(Map.of(
                "name", "Churn Detection",
                "status", churning ? "RED" : "GREEN",
                "detail", churning
                    ? "Possible churn: " + symbolCounts.entrySet().stream().filter(e -> e.getValue() >= 4).map(Map.Entry::getKey).toList() + " traded 4+ times recently"
                    : "No churn detected in last 10 trades"
            ));

            long winRatePct = closedTrades.isEmpty() ? 0 : (wins * 100) / closedTrades.size();
            checks.add(Map.of(
                "name", "Recent Win Rate",
                "status", closedTrades.isEmpty() ? "GREEN" : winRatePct >= 50 ? "GREEN" : winRatePct >= 30 ? "YELLOW" : "RED",
                "detail", closedTrades.isEmpty()
                    ? "No completed trades yet"
                    : wins + "W / " + losses + "L in last " + closedTrades.size() + " closed trades (" + winRatePct + "%)"
            ));

            boolean emergencyActive = com.trading.bot.TradingBot.isEmergencyTriggered();
            checks.add(Map.of(
                "name", "Emergency Protocol",
                "status", emergencyActive ? "RED" : "GREEN",
                "detail", emergencyActive ? "EMERGENCY STOP ACTIVE — bot halted!" : "Normal — no emergency triggered"
            ));

            // --- PDT (Pattern Day Trader) status ---
            int pdtCount = com.trading.portfolio.ProfileManager.getPdtDayTradeCount();
            long pdtBlockedUntil = com.trading.portfolio.ProfileManager.getPdtBlockedUntil();
            boolean pdtBlocked = pdtBlockedUntil > now;
            boolean pdtWarning = !pdtBlocked && pdtCount >= 2;
            String pdtStatus = pdtBlocked ? "RED" : pdtWarning ? "YELLOW" : "GREEN";
            String pdtDetail;
            if (pdtBlocked) {
                long minsLeft = (pdtBlockedUntil - now) / 60000;
                pdtDetail = String.format("PDT EXHAUSTED — all sells blocked for %dm. Positions protected by native GTC stops.", minsLeft);
            } else if (pdtWarning) {
                pdtDetail = String.format("%d/3 day trades used — buys blocked, last slot reserved for exits", pdtCount);
            } else {
                pdtDetail = pdtCount + "/3 day trades used today";
            }
            checks.add(Map.of("name", "PDT Day Trades", "status", pdtStatus, "detail", pdtDetail));
            response.put("pdtDayTradeCount", pdtCount);
            response.put("pdtBlockedUntilMs", pdtBlocked ? pdtBlockedUntil : 0);

            // Urgent exit queue: failed protective sells pending retry
            var urgentExits = com.trading.portfolio.ProfileManager.getUrgentExitQueue();
            checks.add(Map.of(
                "name", "Urgent Exit Queue",
                "status", urgentExits.isEmpty() ? "GREEN" : "RED",
                "detail", urgentExits.isEmpty()
                    ? "No failed exits pending retry"
                    : urgentExits.size() + " exit(s) failed, retrying: " + String.join(", ", urgentExits.keySet())
            ));

            // Blocked buys: symbols currently blocked from entry (gap-down, price gate)
            var blockedBuys = com.trading.portfolio.ProfileManager.getBlockedBuys();
            if (!blockedBuys.isEmpty()) {
                checks.add(Map.of(
                    "name", "Blocked Entries",
                    "status", "YELLOW",
                    "detail", blockedBuys.size() + " symbol(s) blocked: " +
                        blockedBuys.entrySet().stream()
                            .map(e -> e.getKey() + " (" + e.getValue() + ")")
                            .collect(java.util.stream.Collectors.joining(", "))
                ));
            }

            // --- VIX / Market Regime ---
            double vixLevel = com.trading.portfolio.ProfileManager.getLatestVixSnapshot();
            String regime = com.trading.portfolio.ProfileManager.getLatestRegimeSnapshot();
            String targetSyms = com.trading.portfolio.ProfileManager.getLatestTargetSymbolsSnapshot();
            double vixThreshold = config.getVixThreshold();
            boolean vixElevated = vixLevel > 0 && vixLevel >= vixThreshold;
            String vixStatus = vixLevel == 0 ? "YELLOW" : vixElevated ? "YELLOW" : "GREEN";
            String vixDetail = vixLevel == 0
                ? "VIX not yet fetched"
                : String.format("VIX %.1f (threshold %.0f) — %s mode — targets: %s",
                    vixLevel, vixThreshold,
                    vixElevated ? "BEARISH" : "BULLISH",
                    targetSyms.isEmpty() ? "none" : targetSyms);
            checks.add(Map.of("name", "VIX / Market Regime", "status", vixStatus, "detail", vixDetail));
            response.put("vixLevel", vixLevel);
            response.put("marketRegime", regime);
            response.put("targetSymbols", targetSyms);

            // --- Per-symbol post-loss cooldown registry (Tier 1.1) ---
            var postLossCooldowns = com.trading.portfolio.ProfileManager.getPostLossCooldowns();
            List<Map<String, Object>> postLossList = new ArrayList<>();
            for (var entry : postLossCooldowns.entrySet()) {
                long remainingHours = Math.max(0, (entry.getValue() - now) / 3_600_000L);
                postLossList.add(Map.of(
                    "symbol", entry.getKey(),
                    "expiresAt", entry.getValue(),
                    "remainingHours", remainingHours
                ));
            }
            response.put("postLossCooldowns", postLossList);
            checks.add(Map.of(
                "name", "Post-Loss Cooldowns (Tier 1.1)",
                "status", postLossList.isEmpty() ? "GREEN" : "YELLOW",
                "detail", postLossList.isEmpty()
                    ? "No symbols in post-loss cooldown"
                    : postLossList.size() + " symbol(s) cooling down: "
                        + postLossList.stream()
                            .map(c -> c.get("symbol") + " (" + c.get("remainingHours") + "h)")
                            .toList()
            ));

            // --- Per-broker circuit breaker (Tier 3.10) ---
            var cbSnap = com.trading.portfolio.ProfileManager.getCircuitBreakerSnapshot();
            response.put("circuitBreakers", cbSnap);
            boolean anyCbTripped = com.trading.portfolio.ProfileManager.isAnyCircuitBreakerTripped();
            String cbDetail;
            if (cbSnap.isEmpty()) {
                cbDetail = "No broker circuit breakers active yet";
            } else if (anyCbTripped) {
                cbDetail = cbSnap.entrySet().stream()
                    .filter(e -> Boolean.TRUE.equals(e.getValue().get("tripped")))
                    .map(e -> e.getKey() + " TRIPPED (" + e.getValue().get("tripReason") + ")")
                    .collect(java.util.stream.Collectors.joining(", "));
            } else {
                cbDetail = cbSnap.entrySet().stream()
                    .map(e -> e.getKey() + ": " + e.getValue().get("consecutiveLosses") + "L streak, "
                        + String.format("%.1f%%", ((Number) e.getValue().get("sessionDrawdownPct")).doubleValue() * 100) + " DD")
                    .collect(java.util.stream.Collectors.joining(" | "));
            }
            checks.add(Map.of(
                "name", "Session Circuit Breaker (Tier 3.10)",
                "status", anyCbTripped ? "RED" : "GREEN",
                "detail", cbDetail
            ));

            // --- Portfolio Stop Loss Gate ---
            boolean portfolioHalt = com.trading.portfolio.ProfileManager.isPortfolioStopLossHaltActive();
            boolean drawdownHalt = com.trading.portfolio.ProfileManager.isMaxDrawdownHaltActive();
            String entryGateStatus = (portfolioHalt || drawdownHalt) ? "RED" : "GREEN";
            String entryGateDetail;
            if (portfolioHalt && drawdownHalt) {
                entryGateDetail = "BOTH portfolio stop loss AND max drawdown halts active — no new entries";
            } else if (portfolioHalt) {
                entryGateDetail = "Portfolio stop loss triggered — new entries halted (exits still running)";
            } else if (drawdownHalt) {
                entryGateDetail = "Max drawdown from peak exceeded — new entries halted";
            } else {
                entryGateDetail = "Entry gates open";
            }
            checks.add(Map.of("name", "Entry Gates", "status", entryGateStatus, "detail", entryGateDetail));

            // --- Market Hours ---
            boolean isMarketOpen = marketHoursFilter.isMarketOpen();
            checks.add(Map.of(
                "name", "Market Hours",
                "status", isMarketOpen ? "GREEN" : "YELLOW",
                "detail", isMarketOpen ? "Market open — normal trading" : "Market closed — risk exits still run, new entries blocked"
            ));

            response.put("healthChecks", checks);
            response.put("urgentExits", urgentExits);
            response.put("blockedBuys", blockedBuys);

            // Overall health summary
            boolean allGreen = checks.stream().allMatch(c -> "GREEN".equals(c.get("status")));
            boolean anyRed = checks.stream().anyMatch(c -> "RED".equals(c.get("status")));
            response.put("overallStatus", allGreen ? "GREEN" : anyRed ? "RED" : "YELLOW");
            response.put("timestamp", System.currentTimeMillis());

            ctx.json(response);
        } catch (Exception e) {
            logger.error("Failed to get bot behavior", e);
            ctx.status(500).json(Map.of("error", e.getMessage()));
        }
    }

    /**
     * GET /api/brokers
     * Returns active brokers parsed from the BROKERS env var.
     */
    private void getBrokers(Context ctx) {
        try {
            String brokersAllocation = config.getBrokersAllocation();
            List<Map<String, Object>> brokerList = new ArrayList<>();
            boolean multiBroker = brokersAllocation != null && !brokersAllocation.isBlank();
            if (multiBroker) {
                for (String part : brokersAllocation.split(",")) {
                    String trimmed = part.trim();
                    if (trimmed.isEmpty()) continue;
                    String[] kv = trimmed.split(":", 2);
                    String name = kv[0].trim().toLowerCase();
                    double allocation = kv.length == 2 ? Double.parseDouble(kv[1].trim()) : 100.0;
                    boolean sandbox = "tradier".equals(name) && config.isTradierSandbox();
                    brokerList.add(Map.of("name", name, "allocation", allocation, "sandbox", sandbox));
                }
            }
            ctx.json(Map.of("brokers", brokerList, "multiBroker", multiBroker));
        } catch (Exception e) {
            logger.error("Failed to get brokers", e);
            ctx.status(500).json(Map.of("error", e.getMessage()));
        }
    }

    /**
     * GET /api/brokers/status
     * Returns live connectivity and account summary for each configured broker.
     */
    private void getBrokerStatus(Context ctx) {
        var brokers = new java.util.ArrayList<Map<String, Object>>();
        String brokersAlloc = config.getBrokersAllocation();

        // Always include Alpaca
        try {
            var account = cachedAlpacaClient.getAccount();
            Map<String, Object> alpacaInfo = new java.util.HashMap<>();
            alpacaInfo.put("name", "alpaca");
            alpacaInfo.put("sandbox", false);
            alpacaInfo.put("connected", true);
            alpacaInfo.put("equity", account.path("equity").asDouble(0));
            alpacaInfo.put("cash", account.path("cash").asDouble(0));
            alpacaInfo.put("buyingPower", account.path("buying_power").asDouble(0));
            brokers.add(alpacaInfo);
        } catch (Exception e) {
            Map<String, Object> alpacaInfo = new java.util.HashMap<>();
            alpacaInfo.put("name", "alpaca");
            alpacaInfo.put("connected", false);
            alpacaInfo.put("error", e.getMessage());
            brokers.add(alpacaInfo);
        }

        // Include Tradier if configured
        if (brokersAlloc != null && brokersAlloc.toLowerCase().contains("tradier")) {
            try {
                var account = cachedTradierClient.getAccount();
                Map<String, Object> tradierInfo = new java.util.HashMap<>();
                tradierInfo.put("name", "tradier");
                tradierInfo.put("sandbox", config.isTradierSandbox());
                tradierInfo.put("connected", true);
                tradierInfo.put("equity", account.path("equity").asDouble(0));
                tradierInfo.put("cash", account.path("cash").asDouble(0));
                tradierInfo.put("buyingPower", account.path("buying_power").asDouble(0));
                brokers.add(tradierInfo);
            } catch (Exception e) {
                Map<String, Object> tradierInfo = new java.util.HashMap<>();
                tradierInfo.put("name", "tradier");
                tradierInfo.put("sandbox", config.isTradierSandbox());
                tradierInfo.put("connected", false);
                tradierInfo.put("error", e.getMessage());
                brokers.add(tradierInfo);
            }
        }

        ctx.json(Map.of(
            "brokers", brokers,
            "multiBroker", brokersAlloc != null && !brokersAlloc.isBlank() && brokersAlloc.contains(":")
        ));
    }

    /**
     * GET /api/trades/by-broker?broker=tradier
     * Returns trades filtered by broker, or all trades grouped by broker if no param given.
     */
    private void getTradesByBroker(Context ctx) {
        try {
            String brokerParam = ctx.queryParam("broker");
            var allTrades = database.exportTrades(null);
            if (brokerParam != null && !brokerParam.isBlank()) {
                var filtered = allTrades.stream()
                    .filter(t -> brokerParam.equalsIgnoreCase(String.valueOf(t.get("broker"))))
                    .toList();
                ctx.json(filtered);
            } else {
                // Group by broker
                var grouped = new java.util.LinkedHashMap<String, List<Map<String, Object>>>();
                for (var trade : allTrades) {
                    String broker = String.valueOf(trade.getOrDefault("broker", "alpaca"));
                    grouped.computeIfAbsent(broker, k -> new ArrayList<>()).add(trade);
                }
                ctx.json(grouped);
            }
        } catch (Exception e) {
            logger.error("Failed to get trades by broker", e);
            ctx.status(500).json(Map.of("error", e.getMessage()));
        }
    }

    private void runBacktest(Context ctx) {
        String symbol = ctx.queryParamAsClass("symbol", String.class).getOrDefault("SPY");
        int days = ctx.queryParamAsClass("days", Integer.class).getOrDefault(90);
        double capital = ctx.queryParamAsClass("capital", Double.class).getOrDefault(1000.0);
        String regimeStr = ctx.queryParamAsClass("regime", String.class).getOrDefault("RANGE_BOUND");

        try {
            MarketRegime regime = MarketRegime.valueOf(regimeStr);
            var client = cachedAlpacaClient;
            var strategyManager = new com.trading.strategy.StrategyManager(client, null, config);
            var engine = new BacktestEngine(strategyManager);

            var bars = client.getBars(symbol, "1Day", days);
            if (bars.isEmpty()) {
                ctx.status(400).json(Map.of("error", "No historical data for " + symbol));
                return;
            }

            var btConfig = new BacktestEngine.BacktestConfig(
                symbol, capital, 0.9, 0.8, 0.4, 0.02, regime, 50
            );
            var result = engine.run(btConfig, bars);

            var response = new HashMap<String, Object>();
            response.put("symbol", result.symbol());
            response.put("initialCapital", result.initialCapital());
            response.put("finalCapital", result.finalCapital());
            response.put("returnPercent", result.returnPercent());
            response.put("totalTrades", result.totalTrades());
            response.put("wins", result.wins());
            response.put("losses", result.losses());
            response.put("winRate", result.winRate());
            response.put("profitFactor", result.profitFactor());
            response.put("maxDrawdownPercent", result.maxDrawdownPercent());
            response.put("totalPnL", result.totalPnL());
            response.put("trades", result.trades().stream().map(t -> Map.of(
                "entryTime", t.entryTime() != null ? t.entryTime().toString() : "",
                "exitTime", t.exitTime() != null ? t.exitTime().toString() : "",
                "entryPrice", t.entryPrice(),
                "exitPrice", t.exitPrice(),
                "quantity", t.quantity(),
                "pnl", t.pnl(),
                "exitReason", t.exitReason()
            )).toList());
            ctx.json(response);
        } catch (IllegalArgumentException e) {
            ctx.status(400).json(Map.of("error", "Invalid regime: " + regimeStr));
        } catch (Exception e) {
            logger.error("Backtest failed", e);
            ctx.status(500).json(Map.of("error", e.getMessage()));
        }
    }
}
