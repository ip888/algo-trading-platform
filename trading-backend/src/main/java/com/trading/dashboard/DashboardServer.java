package com.trading.dashboard;

import com.trading.analysis.MarketAnalyzer;
import com.trading.api.ResilientAlpacaClient;
import com.trading.api.controller.DashboardController;
import com.trading.autonomous.TradeAnalytics;
import com.trading.broker.KrakenClient;
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
                          ResilientAlpacaClient alpacaClient, KrakenClient krakenClient) {
        
        // Create TradeAnalytics for performance tracking (Task 3)
        var tradeAnalytics = new TradeAnalytics();
        
        this.controller = new DashboardController(database, portfolio, marketAnalyzer,
            marketHoursFilter, volatilityFilter, config, tradeAnalytics, krakenClient);
        
        this.healthCheckService = new HealthCheckService(alpacaClient, database);
        
        this.app = Javalin.create(javalinConfig -> {
            javalinConfig.showJavalinBanner = false;
            
            // Explicitly configure Jackson to handle Map.of, Records, and Time types
            var objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();
            objectMapper.registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
            objectMapper.disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
            objectMapper.configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
            javalinConfig.jsonMapper(new io.javalin.json.JavalinJackson(objectMapper, false));
            
            // Enable CORS for separate dashboard deployment
            javalinConfig.bundledPlugins.enableCors(cors -> cors.addRule(it -> {
                // Use reflectClientOrigin to allow any origin while supporting credentials
                // This reflects the Origin header back, which is safer than anyHost()
                it.reflectClientOrigin = true;
                it.allowCredentials = true;
            }));

            // Serve Static Files (React Dashboard)
            // Expects frontend build to be in src/main/resources/public
            javalinConfig.staticFiles.add(configStatic -> {
                configStatic.hostedPath = "/";
                configStatic.directory = "/public";
                configStatic.location = io.javalin.http.staticfiles.Location.CLASSPATH;
                configStatic.precompress = true;
                configStatic.aliasCheck = null;
            });
            
            // SPA Fallback: Redirect 404s to index.html for client-side routing
            javalinConfig.spaRoot.addFile("/", "/public/index.html", io.javalin.http.staticfiles.Location.CLASSPATH);
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
        
        // Register Kraken (crypto) endpoints
        var krakenController = new com.trading.api.controller.KrakenController();
        krakenController.registerRoutes(app);
        
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
