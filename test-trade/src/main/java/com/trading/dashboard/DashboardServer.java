package com.trading.dashboard;

import com.trading.analysis.MarketAnalyzer;
import com.trading.api.ResilientAlpacaClient;
import com.trading.api.controller.DashboardController;
import com.trading.autonomous.TradeAnalytics;
import com.trading.config.Config;
import com.trading.filters.MarketHoursFilter;
import com.trading.filters.VolatilityFilter;
import com.trading.health.HealthCheckService;
import com.trading.persistence.TradeDatabase;
import com.trading.portfolio.PortfolioManager;
import com.trading.websocket.TradingWebSocketHandler;
import io.javalin.Javalin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Modern dashboard server - serves REST API and static React frontend.
 * WebSocket handled separately via TradingWebSocketHandler.
 */
public final class DashboardServer {
    private static final Logger logger = LoggerFactory.getLogger(DashboardServer.class);
    private static final int PORT = 8080;
    
    private final Javalin app;
    private final DashboardController controller;
    private final HealthCheckService healthCheckService;
    
    public DashboardServer(TradeDatabase database, PortfolioManager portfolio,
                          MarketAnalyzer marketAnalyzer, MarketHoursFilter marketHoursFilter,
                          VolatilityFilter volatilityFilter, Config config,
                          ResilientAlpacaClient alpacaClient) {
        
        // Create TradeAnalytics for performance tracking (Task 3)
        var tradeAnalytics = new TradeAnalytics();
        
        this.controller = new DashboardController(database, portfolio, marketAnalyzer,
            marketHoursFilter, volatilityFilter, config, tradeAnalytics);
        
        this.healthCheckService = new HealthCheckService(alpacaClient, database);
        
        this.app = Javalin.create(javalinConfig -> {
            javalinConfig.showJavalinBanner = false;
            javalinConfig.plugins.enableCors(cors -> cors.add(it -> it.anyHost()));
            
            // Note: For development, run React with 'npm run dev' separately
            // For production, run 'npm run build' and uncomment below:
            // javalinConfig.staticFiles.add("/dashboard/dist");
        });
        
        // Configure WebSocket endpoint
        app.ws("/trading", ws -> {
            ws.onConnect(TradingWebSocketHandler::onConnect);
            ws.onMessage(TradingWebSocketHandler::onMessage);
            ws.onClose(TradingWebSocketHandler::onClose);
            ws.onError(TradingWebSocketHandler::onError);
        });
        
        // Register REST API routes
        controller.registerRoutes(app);
        
        // Register Backtesting endpoint
        var backtestController = new com.trading.api.controller.BacktestController();
        backtestController.registerRoutes(app);
        
        // Production endpoints
        app.get("/metrics", ctx -> {
            ctx.contentType("text/plain; version=0.0.4");
            ctx.result(com.trading.metrics.MetricsService.getInstance().scrape());
        });
        
        // Enhanced health check endpoint
        app.get("/health", ctx -> {
            var health = healthCheckService.getHealth();
            ctx.contentType("application/json");
            ctx.result(health.toJson());
            
            // Set HTTP status based on health
            ctx.status(switch (health.status()) {
                case UP -> 200;
                case DEGRADED -> 200; // Still operational
                case DOWN -> 503; // Service unavailable
            });
        });
        
        // Fallback to index.html for SPA routing
        app.get("/*", ctx -> {
            if (!ctx.path().startsWith("/api")) {
                ctx.result("React dashboard coming soon! API endpoints ready at /api/*");
            }
        });
    }
    
    public void start() {
        app.start(PORT);
        logger.info("🚀 Modern Dashboard Server started at http://localhost:{}", PORT);
        logger.info("   REST API: http://localhost:{}/api/*", PORT);
        logger.info("   WebSocket: ws://localhost:{}/trading", PORT);
        logger.info("   Health: http://localhost:{}/health", PORT);
        logger.info("   Metrics: http://localhost:{}/metrics", PORT);
    }
    
    public void stop() {
        app.stop();
        logger.info("Dashboard stopped");
    }
}
