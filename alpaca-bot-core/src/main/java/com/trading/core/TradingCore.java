package com.trading.core;

import io.javalin.Javalin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.net.http.HttpClient;
import java.util.concurrent.Executors;
import com.trading.core.analysis.MarketDiscoveryService;
import com.trading.core.analysis.BacktestService;
import com.trading.core.analysis.MarketAnalysisService;
import com.trading.core.strategy.StrategyService;
import com.trading.core.api.AlpacaClient;
import com.trading.core.execution.OrderExecutionService;
import com.trading.core.websocket.TradingWebSocketHandler;

/**
 * The "Core" - Java 25 Tech Excellence
 * Uses Virtual Threads for high-concurrency signal processing.
 */
public class TradingCore {
    private static final Logger logger = LoggerFactory.getLogger(TradingCore.class);
    private static final com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
    private static MarketDiscoveryService discoveryService;
    private static BacktestService backtestService;
    private static AlpacaClient clientRef;
    private static MarketAnalysisService analysisRef;
    private static OrderExecutionService executionRef;
    private static com.trading.core.market.MarketHoursService marketHoursService;
    private static com.trading.core.health.HealthMonitor healthMonitor;
    private static volatile boolean preMarketCheckDone = false;

    public static void main(String[] args) {
        long startTime = System.currentTimeMillis();
        
        System.out.println("🚀 [MAIN] Starting Alpaca Bot Core...");
        logger.info("🚀 Starting Alpaca Bot Core (Java 25 LTS)...");

        // 1. Start HTTP Server ASAP to pass Cloud Run health checks
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
        System.out.println("DEBUG: Starting Javalin on port " + port);
        
        Javalin app = Javalin.create(config -> {
            config.showJavalinBanner = false;
            config.bundledPlugins.enableCors(cors -> {
                cors.addRule(it -> {
                    it.anyHost();
                });
            });
        }).start(port);

        System.out.println("DEBUG: Javalin started. Defining basic routes...");

        app.get("/", ctx -> ctx.result("Alpaca Bot Core - Java 25. Use /api/heartbeat for status."));
        app.get("/healthz", ctx -> ctx.result("OK"));
        app.get("/api/heartbeat", ctx -> ctx.result("OK"));

        // 2. Initialize Components
        System.out.println("DEBUG: Initializing components...");
        clientRef = new AlpacaClient();
        System.out.println("DEBUG: AlpacaClient initialized.");
        var strategyService = new StrategyService(clientRef);
        System.out.println("DEBUG: StrategyService initialized.");
        analysisRef = new MarketAnalysisService(clientRef);
        System.out.println("DEBUG: MarketAnalysisService initialized.");
        discoveryService = new MarketDiscoveryService(analysisRef);
        System.out.println("DEBUG: MarketDiscoveryService initialized.");
        backtestService = new BacktestService(clientRef, strategyService);
        System.out.println("DEBUG: BacktestService initialized.");
        executionRef = new OrderExecutionService(clientRef);
        System.out.println("DEBUG: OrderExecutionService initialized.");
        
        // Initialize Market Hours & Health Monitor
        marketHoursService = new com.trading.core.market.MarketHoursService(clientRef);
        healthMonitor = new com.trading.core.health.HealthMonitor(clientRef, marketHoursService);
        System.out.println("DEBUG: MarketHoursService and HealthMonitor initialized.");

        // Global Error Handling
        app.exception(Exception.class, (e, ctx) -> {
            logger.error("🚨 Unhandled Exception in API: {}", e.getMessage(), e);
            var error = new java.util.HashMap<String, Object>();
            error.put("error", true);
            error.put("message", e.getMessage());
            error.put("type", e.getClass().getSimpleName());
            ctx.status(500).json(error);
        });

        // WebSocket for UI (Restored and Improved)
        app.ws("/trading", ws -> {
            ws.onConnect(ctx -> {
                com.trading.core.websocket.TradingWebSocketHandler.onConnect(ctx);
                syncStateToUI();
            });
            ws.onClose(com.trading.core.websocket.TradingWebSocketHandler::onClose);
        });

        // 3. Define Endpoints
        app.get("/health", ctx -> ctx.result("Core Online 🧠 (Java 25)"));
        
        app.get("/api/account", ctx -> {
            ctx.async(() -> {
                try {
                    String jsonStr = clientRef.getAccountAsync().join();
                    var json = mapper.readTree(jsonStr);
                    double equity = json.has("equity") ? json.get("equity").asDouble() : 0.0;
                    double buyingPower = json.has("buying_power") ? json.get("buying_power").asDouble() : 0.0;
                    double cash = json.has("cash") ? json.get("cash").asDouble() : 0.0;
                    
                    // Use Config class for dynamic parameters from config.properties
                    var response = new java.util.HashMap<String, Object>();
                    response.put("equity", equity);
                    response.put("buyingPower", buyingPower);
                    response.put("cash", cash);
                    response.put("capitalReserve", equity * (com.trading.core.config.Config.getMainProfileCapitalPercent() / 100.0));
                    response.put("deployableCapital", equity * (com.trading.core.config.Config.getExperimentalProfileCapitalPercent() / 100.0));
                    response.put("mainTakeProfitPercent", com.trading.core.config.Config.getMainTakeProfitPercent());
                    response.put("mainStopLossPercent", com.trading.core.config.Config.getMainStopLossPercent());
                    response.put("experimentalTakeProfitPercent", com.trading.core.config.Config.getExperimentalTakeProfitPercent());
                    response.put("experimentalStopLossPercent", com.trading.core.config.Config.getExperimentalStopLossPercent());
                    response.put("tradingMode", com.trading.core.config.Config.getTradingMode());
                    response.put("positionSizingMethod", com.trading.core.config.Config.getPositionSizingMethod());
                    response.put("pdtProtectionEnabled", com.trading.core.config.Config.isPdtProtectionEnabled());
                    
                    ctx.contentType("application/json");
                    ctx.result(mapper.writeValueAsString(response));
                } catch (Exception e) {
                    logger.error("Account API error", e);
                    ctx.status(500).result("{\"error\":\"" + e.getMessage() + "\"}");
                }
            });
        });

        app.get("/api/positions", ctx -> {
            ctx.async(() -> {
                try {
                    String jsonStr = clientRef.getPositionsAsync().join();
                    ctx.contentType("application/json");
                    // Handle empty or null response by returning empty array
                    if (jsonStr == null || jsonStr.isEmpty() || jsonStr.isBlank()) {
                        ctx.result("[]");
                    } else {
                        ctx.result(jsonStr);
                    }
                } catch (Exception e) {
                    logger.error("Positions API error", e);
                    ctx.contentType("application/json");
                    ctx.result("[]");
                }
            });
        });

        app.get("/api/market/status", ctx -> {
            var symbols = discoveryService.getActiveWatchlist();
            var results = analysisRef.analyzeMarket(symbols);
            
            var response = new java.util.HashMap<String, Object>();
            if (!results.isEmpty()) {
                var first = results.get(0);
                response.put("currentVIX", 18.5); 
                response.put("regime", first.regime());
                response.put("isOpen", true);
            }
            ctx.json(response);
        });

        // Expose all smart features and config to UI
        app.get("/api/config", ctx -> {
            var config = new java.util.HashMap<String, Object>();
            
            // Trading Mode
            config.put("tradingMode", com.trading.core.config.Config.getTradingMode());
            config.put("initialCapital", com.trading.core.config.Config.getInitialCapital());
            
            // Main Profile Settings
            var mainProfile = new java.util.HashMap<String, Object>();
            mainProfile.put("takeProfitPercent", com.trading.core.config.Config.getMainTakeProfitPercent());
            mainProfile.put("stopLossPercent", com.trading.core.config.Config.getMainStopLossPercent());
            mainProfile.put("trailingStopPercent", com.trading.core.config.Config.getMainTrailingStopPercent());
            mainProfile.put("capitalPercent", com.trading.core.config.Config.getMainProfileCapitalPercent());
            mainProfile.put("bullishSymbols", com.trading.core.config.Config.getMainBullishSymbols());
            mainProfile.put("bearishSymbols", com.trading.core.config.Config.getMainBearishSymbols());
            config.put("mainProfile", mainProfile);
            
            // Experimental Profile Settings
            var expProfile = new java.util.HashMap<String, Object>();
            expProfile.put("takeProfitPercent", com.trading.core.config.Config.getExperimentalTakeProfitPercent());
            expProfile.put("stopLossPercent", com.trading.core.config.Config.getExperimentalStopLossPercent());
            expProfile.put("trailingStopPercent", com.trading.core.config.Config.getExperimentalTrailingStopPercent());
            expProfile.put("capitalPercent", com.trading.core.config.Config.getExperimentalProfileCapitalPercent());
            expProfile.put("bullishSymbols", com.trading.core.config.Config.getExperimentalBullishSymbols());
            expProfile.put("bearishSymbols", com.trading.core.config.Config.getExperimentalBearishSymbols());
            config.put("experimentalProfile", expProfile);
            
            // Risk Management
            var risk = new java.util.HashMap<String, Object>();
            risk.put("maxLossPercent", com.trading.core.config.Config.getMaxLossPercent());
            risk.put("maxLossExitEnabled", com.trading.core.config.Config.isMaxLossExitEnabled());
            risk.put("portfolioStopLossPercent", com.trading.core.config.Config.getPortfolioStopLossPercent());
            risk.put("portfolioStopLossEnabled", com.trading.core.config.Config.isPortfolioStopLossEnabled());
            risk.put("pdtProtectionEnabled", com.trading.core.config.Config.isPdtProtectionEnabled());
            config.put("riskManagement", risk);
            
            // Position Sizing (Kelly Criterion)
            var sizing = new java.util.HashMap<String, Object>();
            sizing.put("method", com.trading.core.config.Config.getPositionSizingMethod());
            sizing.put("kellyFraction", com.trading.core.config.Config.getKellyFraction());
            sizing.put("kellyRiskReward", com.trading.core.config.Config.getKellyRiskReward());
            sizing.put("defaultWinRate", com.trading.core.config.Config.getDefaultWinRate());
            config.put("positionSizing", sizing);
            
            // Advanced Features
            var features = new java.util.HashMap<String, Object>();
            features.put("regimeDetectionEnabled", com.trading.core.config.Config.isRegimeDetectionEnabled());
            features.put("multiTimeframeEnabled", com.trading.core.config.Config.isMultiTimeframeEnabled());
            features.put("multiProfileEnabled", com.trading.core.config.Config.isMultiProfileEnabled());
            features.put("currentRegime", com.trading.core.analysis.RegimeDetector.getCurrentRegime().name());
            config.put("advancedFeatures", features);
            
            // Rate Limiting
            var rateLimit = new java.util.HashMap<String, Object>();
            rateLimit.put("apiRequestDelayMs", com.trading.core.config.Config.getApiRequestDelayMs());
            rateLimit.put("symbolBatchSize", com.trading.core.config.Config.getSymbolBatchSize());
            config.put("rateLimiting", rateLimit);
            
            // VIX Settings
            var vix = new java.util.HashMap<String, Object>();
            vix.put("threshold", com.trading.core.config.Config.getVixThreshold());
            vix.put("hysteresis", com.trading.core.config.Config.getVixHysteresis());
            config.put("vixSettings", vix);
            
            ctx.json(config);
        });

        app.post("/api/backtest", ctx -> {
            try {
                var req = ctx.bodyAsClass(BacktestService.BacktestRequest.class);
                ctx.future(() -> backtestService.runSimulation(req).thenAccept(ctx::json));
            } catch (Exception e) {
                logger.error("Backtest Request Error", e);
                ctx.status(400).result("Invalid Backtest Request: " + e.getMessage());
            }
        });

        System.out.println("DEBUG: All routes defined.");
        logger.info("✅ Core active on port {}", port);
        logger.info("⚡ Startup time: {}ms", (System.currentTimeMillis() - startTime));

        // 4. Start Loops
        boolean autonomous = Boolean.parseBoolean(System.getenv().getOrDefault("AUTONOMOUS_TRADING", "true"));
        if (autonomous) {
            startAutonomousLoop(clientRef, analysisRef, executionRef);
            startDiscoveryLoop();
            startHealthMonitorLoop();
        }

        startHeartbeatSender();
    }

    public static void syncStateToUI() {
        if (clientRef == null) return;
        logger.info("🔄 On-Demand Sync: Synchronizing state to UI...");
        
        clientRef.getAccountAsync().thenAccept(accStr -> {
            try {
                logger.info("📡 [Sync] Raw Account Response: {}", accStr);
                var json = mapper.readTree(accStr);
                double equity = json.has("equity") ? json.get("equity").asDouble() : 0.0;
                double buyingPower = json.has("buying_power") ? json.get("buying_power").asDouble() : 0.0;
                double cash = json.has("cash") ? json.get("cash").asDouble() : 0.0;
                com.trading.core.websocket.TradingWebSocketHandler.broadcastAccountData(
                    equity, buyingPower, cash, equity * 0.25, equity * 0.75);
            } catch (Exception e) { logger.error("🚨 Account Sync Error", e); }
        }).exceptionally(e -> { logger.error("🚨 Account Async Failure", e); return null; });

        clientRef.getPositionsAsync().thenAccept(posStr -> {
            try {
                logger.info("📡 [Sync] Raw Positions Response: {}", posStr);
                var json = (com.fasterxml.jackson.databind.node.ArrayNode) mapper.readTree(posStr);
                com.trading.core.websocket.TradingWebSocketHandler.broadcastPositions(json);
                com.trading.core.websocket.TradingWebSocketHandler.broadcastLog("Core Sync: Linked to " + json.size() + " active positions.", "SUCCESS");
            } catch (Exception e) { logger.error("🚨 Positions Sync Error", e); }
        }).exceptionally(e -> { logger.error("🚨 Positions Async Failure", e); return null; });
    }

    private static void startDiscoveryLoop() {
        var scheduler = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().factory());
        scheduler.scheduleAtFixedRate(() -> {
            try {
                discoveryService.refreshWatchlist();
            } catch (Exception e) {
                logger.error("Discovery Error", e);
            }
        }, 0, 30, java.util.concurrent.TimeUnit.MINUTES);
    }

    private static void startHeartbeatSender() {
        var scheduler = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().factory());
        String cortexUrl = System.getenv().getOrDefault("CORTEX_URL", "https://watchdog-worker.ihorpetroff.workers.dev");
        var client = java.net.http.HttpClient.newHttpClient();

        scheduler.scheduleAtFixedRate(() -> {
            try {
                var request = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(cortexUrl + "/heartbeat"))
                    .POST(java.net.http.HttpRequest.BodyPublishers.noBody())
                    .build();
                client.sendAsync(request, java.net.http.HttpResponse.BodyHandlers.ofString());
            } catch (Exception e) {
                logger.error("Heartbeat Error", e);
            }
        }, 5, 60, java.util.concurrent.TimeUnit.SECONDS);
    }

    private static void startAutonomousLoop(
            AlpacaClient client,
            com.trading.core.analysis.MarketAnalysisService analysisService,
            com.trading.core.execution.OrderExecutionService executionService) {
        
        var scheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().factory());
        
        scheduler.scheduleAtFixedRate(() -> {
            try {
                System.out.println("⏰ [Loop] Heartbeat: Synchronizing Data...");
                
                // 1. Fetch Account & Positions
                client.getAccountAsync().thenAccept(accStr -> {
                    try {
                        System.out.println("📡 [Loop] Raw Account Response: " + accStr);
                        var json = mapper.readTree(accStr);
                        double equity = json.has("equity") ? json.get("equity").asDouble() : 0.0;
                        double buyingPower = json.has("buying_power") ? json.get("buying_power").asDouble() : 0.0;
                        double cash = json.has("cash") ? json.get("cash").asDouble() : 0.0;
                        com.trading.core.websocket.TradingWebSocketHandler.broadcastAccountData(
                            equity, buyingPower, cash, equity * 0.25, equity * 0.75);
                    } catch (Exception e) { System.err.println("🚨 Account Loop Error: " + e.getMessage()); }
                }).exceptionally(e -> { System.err.println("🚨 Account Loop Async Failure: " + e.getMessage()); return null; });

                client.getPositionsAsync().thenAccept(posStr -> {
                    try {
                        var json = (com.fasterxml.jackson.databind.node.ArrayNode) mapper.readTree(posStr);
                        com.trading.core.websocket.TradingWebSocketHandler.broadcastPositions(json);
                        
                        // Calculate total P&L and Profit Targets for status
                        double totalPnl = 0;
                        var targetsNode = mapper.createArrayNode();
                        
                        for (var node : json) {
                            double unrealizedPL = node.has("unrealized_pl") ? node.get("unrealized_pl").asDouble() : 0;
                            double unrealizedPLPC = node.has("unrealized_plpc") ? node.get("unrealized_plpc").asDouble() * 100 : 0;
                            totalPnl += unrealizedPL;
                            
                            String symbol = node.get("symbol").asText();
                            
                            // Mocking distance for UI demonstration based on unrealized P&L
                            // Assuming a 2% target by default
                            double target = 2.0; 
                            double distance = target - unrealizedPLPC;
                            
                            var targetObj = mapper.createObjectNode();
                            targetObj.put("symbol", symbol);
                            targetObj.put("currentPnlPercent", unrealizedPLPC);
                            targetObj.put("targetPercent", target);
                            targetObj.put("distancePercent", distance);
                            targetObj.put("eta", distance < 0.5 ? "Soon" : "Scanning");
                            targetsNode.add(targetObj);
                        }
                        
                        // Broadcast targets
                        com.fasterxml.jackson.databind.node.ObjectNode targetsRoot = mapper.createObjectNode();
                        targetsRoot.put("type", "profit_targets");
                        com.fasterxml.jackson.databind.node.ObjectNode targetsData = mapper.createObjectNode();
                        targetsData.set("targets", targetsNode);
                        targetsRoot.set("data", targetsData);
                        com.trading.core.websocket.TradingWebSocketHandler.broadcast(targetsRoot);
                        
                        // 2. Perform Analysis & Strategy
                        var symbols = discoveryService.getActiveWatchlist();
                        var results = analysisService.analyzeMarket(symbols);
                        var executionResults = executionService.executeSignals(results);

                        boolean hasResults = !results.isEmpty();
                        String regime = hasResults ? results.get(0).regime() : "SCANNING...";
                        String recommendation = hasResults && !executionResults.isEmpty() ? executionResults.get(0) : "HOLD";
                        double volatility = hasResults ? 18.5 : 0.0; // Fallback VIX for now or real if available
                        
                        com.trading.core.websocket.TradingWebSocketHandler.broadcastSystemStatus(
                            "ACTIVE", 
                            regime, 
                            volatility, 
                            recommendation,
                            json.size(),
                            totalPnl
                        );

                        if (hasResults) {
                            com.trading.core.websocket.TradingWebSocketHandler.broadcastMarketUpdate(results);
                        }
                    } catch (Exception e) { logger.error("Positions Sync Error", e); }
                });

            } catch (Exception e) {
                logger.error("High-Level Loop Error", e);
            }
        }, 5, 30, java.util.concurrent.TimeUnit.SECONDS);
    }

    /**
     * Health Monitor Loop - Self-monitoring and market hours awareness.
     * Runs every 60 seconds.
     */
    private static void startHealthMonitorLoop() {
        var scheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().factory());
        
        scheduler.scheduleAtFixedRate(() -> {
            try {
                // 1. Check market phase
                var clock = marketHoursService.getMarketClock();
                var phase = clock.phase();
                
                // Broadcast market status to UI
                broadcastMarketStatus(clock);
                
                // 2. Pre-market readiness check (15-30 min before open)
                if (phase == com.trading.core.market.MarketHoursService.MarketPhase.PRE_MARKET 
                    && clock.minutesToOpen() >= 0 && clock.minutesToOpen() <= 30 
                    && !preMarketCheckDone) {
                    
                    logger.info("🔔 Pre-market window detected. Running readiness check...");
                    var report = marketHoursService.performReadinessCheck();
                    broadcastReadinessReport(report);
                    
                    if (report.ready()) {
                        healthMonitor.logEvent("PRE_MARKET_READY", 
                            com.trading.core.health.HealthMonitor.HealthStatus.HEALTHY,
                            "Bot ready for trading day", "Trading will activate when market opens");
                        preMarketCheckDone = true;
                    } else {
                        healthMonitor.logEvent("PRE_MARKET_NOT_READY", 
                            com.trading.core.health.HealthMonitor.HealthStatus.DEGRADED,
                            "Pre-market check failed: " + report.message(), "Check configuration and connections");
                    }
                }
                
                // Reset pre-market check flag when market closes
                if (phase == com.trading.core.market.MarketHoursService.MarketPhase.CLOSED 
                    || phase == com.trading.core.market.MarketHoursService.MarketPhase.POST_MARKET) {
                    preMarketCheckDone = false;
                }
                
                // 3. Perform health check
                var health = healthMonitor.checkHealth();
                
                // Handle critical health issues
                if (health.overall() == com.trading.core.health.HealthMonitor.HealthStatus.EMERGENCY) {
                    logger.error("🚨 EMERGENCY HEALTH STATUS - Initiating emergency flatten");
                    healthMonitor.emergencyFlatten();
                }
                
                // Broadcast health to UI
                broadcastHealthStatus(health);
                
                // Record data update for freshness tracking
                healthMonitor.recordDataUpdate();
                
            } catch (Exception e) {
                logger.error("Health Monitor Loop Error", e);
            }
        }, 10, 60, java.util.concurrent.TimeUnit.SECONDS);
        
        logger.info("🏥 Health Monitor Loop started (60s interval)");
    }
    
    private static void broadcastMarketStatus(com.trading.core.market.MarketHoursService.MarketClock clock) {
        try {
            var node = mapper.createObjectNode();
            node.put("type", "market_hours");
            var data = mapper.createObjectNode();
            data.put("isOpen", clock.isOpen());
            data.put("phase", clock.phase().name());
            data.put("nextOpen", clock.nextOpen());
            data.put("nextClose", clock.nextClose());
            data.put("minutesToOpen", clock.minutesToOpen());
            data.put("minutesToClose", clock.minutesToClose());
            node.set("data", data);
            com.trading.core.websocket.TradingWebSocketHandler.broadcast(node);
        } catch (Exception e) {
            logger.error("Failed to broadcast market status", e);
        }
    }
    
    private static void broadcastReadinessReport(com.trading.core.market.MarketHoursService.ReadinessReport report) {
        try {
            var node = mapper.createObjectNode();
            node.put("type", "readiness_report");
            var data = mapper.createObjectNode();
            data.put("ready", report.ready());
            data.put("alpacaConnected", report.alpacaConnected());
            data.put("configLoaded", report.configLoaded());
            data.put("watchlistReady", report.watchlistReady());
            data.put("message", report.message());
            data.put("timestamp", report.timestamp().toString());
            node.set("data", data);
            com.trading.core.websocket.TradingWebSocketHandler.broadcast(node);
        } catch (Exception e) {
            logger.error("Failed to broadcast readiness report", e);
        }
    }
    
    private static void broadcastHealthStatus(com.trading.core.health.HealthMonitor.HealthReport report) {
        try {
            var node = mapper.createObjectNode();
            node.put("type", "health_status");
            var data = mapper.createObjectNode();
            data.put("overall", report.overall().name());
            data.put("recommendation", report.recommendation());
            data.put("uptimeSeconds", report.uptimeSeconds());
            data.put("timestamp", report.timestamp().toString());
            
            var componentsArray = mapper.createArrayNode();
            for (var comp : report.components()) {
                var compNode = mapper.createObjectNode();
                compNode.put("component", comp.component());
                compNode.put("status", comp.status().name());
                compNode.put("message", comp.message());
                componentsArray.add(compNode);
            }
            data.set("components", componentsArray);
            
            node.set("data", data);
            com.trading.core.websocket.TradingWebSocketHandler.broadcast(node);
        } catch (Exception e) {
            logger.error("Failed to broadcast health status", e);
        }
    }
}
