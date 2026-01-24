package com.trading.api.controller;

import com.trading.analysis.MarketAnalyzer;
import com.trading.config.Config;
import com.trading.filters.MarketHoursFilter;
import com.trading.filters.VolatilityFilter;
import com.trading.persistence.TradeDatabase;
import com.trading.autonomous.TradeAnalytics;
import com.trading.portfolio.PortfolioManager;
import com.trading.validation.OrderRequest;
import io.javalin.Javalin;
import io.javalin.http.Context;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
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
    
    public DashboardController(TradeDatabase database, PortfolioManager portfolio,
                              MarketAnalyzer marketAnalyzer, MarketHoursFilter marketHoursFilter,
                              VolatilityFilter volatilityFilter, Config config,
                              TradeAnalytics tradeAnalytics) {
        this.database = database;
        this.portfolio = portfolio;
        this.marketAnalyzer = marketAnalyzer;
        this.marketHoursFilter = marketHoursFilter;
        this.volatilityFilter = volatilityFilter;
        this.config = config;
        this.tradeAnalytics = tradeAnalytics;
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
    }
    
    /**
     * Profit targets endpoint for UI - returns position profit tracking data.
     */
    private void getProfitTargets(Context ctx) {
        var targets = new java.util.ArrayList<Map<String, Object>>();
        
        try {
            var client = new com.trading.api.AlpacaClient(config);
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
            var client = new com.trading.api.AlpacaClient(config);
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
                positionMap.put("platform", "alpaca");
                
                // Inject Risk Management Data (TP/SL) from PortfolioManager
                var managedPos = portfolio.getPosition(pos.symbol());
                if (managedPos.isPresent()) {
                     positionMap.put("stopLoss", managedPos.get().stopLoss());
                     positionMap.put("takeProfit", managedPos.get().takeProfit());
                     // Add snake_case aliases if some clients use them
                     positionMap.put("stop_loss", managedPos.get().stopLoss());
                     positionMap.put("take_profit", managedPos.get().takeProfit());
                } else {
                     positionMap.put("stopLoss", null);
                     positionMap.put("takeProfit", null);
                }
                result.add(positionMap);
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
            status.put("regime", "WEAK_BULL"); // TODO: Get from regime detector
            status.put("vix", volatilityFilter.getCurrentVIX());
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
            var client = new com.trading.api.AlpacaClient(config);
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
     * Get current watchlist with market data for UI initial load.
     * Provides REST fallback when WebSocket hasn't pushed data yet.
     */
    private void getWatchlist(Context ctx) {
        try {
            // Combine bullish, bearish symbols into watchlist
            var watchlist = new java.util.HashSet<String>();
            watchlist.addAll(config.getBullishSymbols());
            watchlist.addAll(config.getBearishSymbols());
            watchlist.addAll(config.getMainBullishSymbols());
            watchlist.addAll(config.getMainBearishSymbols());
            
            // Get current VIX level
            double vix = volatilityFilter != null ? volatilityFilter.getCurrentVIX() : 15.0;
            String regime = vix > 25 ? "HIGH_VIX" : (vix < 15 ? "LOW_VIX" : "NORMAL");
            
            var result = new java.util.ArrayList<Map<String, Object>>();
            
            for (String symbol : watchlist) {
                var item = new java.util.HashMap<String, Object>();
                item.put("symbol", symbol);
                
                // All symbols are stocks now (Alpaca only)
                item.put("type", "STOCK");
                item.put("tradingHours", "Market Hours");
                
                // Stocks will get prices through WebSocket in main trading loop
                item.put("price", 0.0);
                
                // Add regime-based recommendation
                item.put("regime", regime);
                item.put("score", 50); // Neutral score
                item.put("trend", "NEUTRAL");
                
                result.add(item);
            }
            
            ctx.json(Map.of(
                "watchlist", result,
                "vix", vix,
                "regime", regime,
                "timestamp", System.currentTimeMillis()
            ));
        } catch (Exception e) {
            logger.error("Failed to get watchlist", e);
            ctx.status(500).json(Map.of("error", e.getMessage()));
        }
    }
}
