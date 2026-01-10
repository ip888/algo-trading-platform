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
        
        // Market analysis endpoints
        app.get("/api/market/analysis", this::getMarketAnalysis);
        app.get("/api/market/status", this::getMarketStatus);
        
        // System status endpoints
        app.get("/api/system/status", this::getSystemStatus);
        app.get("/api/system/config", this::getConfig);
        
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
        app.get("/api/heartbeat", this::getHeartbeat);
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
     * Get all current positions from Alpaca.
     * Works in both single-profile and multi-profile modes.
     */
    private void getPositions(Context ctx) {
        try {
            // Query Alpaca directly for positions (works in all modes)
            var client = new com.trading.api.AlpacaClient(config);
            var account = client.getAccount();
            var positions = client.getPositions();
            
            ctx.json(positions);
        } catch (Exception e) {
            logger.error("Failed to fetch positions", e);
            ctx.status(500).json(Map.of("error", "Failed to fetch positions: " + e.getMessage()));
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
        configData.put("vixThreshold", config.getVixThreshold());
        // Don't expose sensitive data like API keys
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
            accountData.put("equity", account.get("equity").asDouble());
            accountData.put("buyingPower", account.get("buying_power").asDouble());
            accountData.put("cash", account.get("cash").asDouble());
            
            // Phase 2: Capital Reserve calculation
            double equity = account.get("equity").asDouble();
            double reservePercent = 0.25; // 25% reserve
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
        logger.error("🚨 MANUAL PANIC BUTTON TRIGGERED FROM DASHBOARD 🚨");
        com.trading.bot.TradingBot.triggerManualPanic("Dashboard Panic Button");
        ctx.json(Map.of("status", "triggered", "message", "Emergency Protocol Executed"));
    }
    
    private void getHeartbeat(Context ctx) {
        var details = com.trading.bot.TradingBot.getHeartbeatDetails();
        boolean isHealthy = com.trading.bot.TradingBot.isSystemHealthy();
        ctx.json(Map.of(
            "status", isHealthy ? "ok" : "critical", 
            "components", details
        ));
    }
}
