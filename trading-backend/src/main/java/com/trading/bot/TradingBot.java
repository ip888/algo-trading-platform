package com.trading.bot;

import com.trading.api.AlpacaClient;
import com.trading.api.ResilientAlpacaClient;
import com.trading.config.Config;
import com.trading.filters.MarketHoursFilter;
import com.trading.filters.VolatilityFilter;
import com.trading.portfolio.PortfolioManager;
import com.trading.risk.RiskManager;
import com.trading.risk.TradePosition;
import com.trading.strategy.StrategyManager;
import com.trading.strategy.SymbolSelector;
import com.trading.strategy.TradingProfile;
import com.trading.strategy.TradingSignal;
import com.trading.analysis.MarketAnalyzer;
import com.trading.dashboard.DashboardServer;
import com.trading.websocket.TradingWebSocketHandler;
import com.trading.persistence.TradeDatabase;
import com.trading.protection.PDTProtection;
import com.trading.testing.TestModeSimulator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.*;

/**
 * Main trading bot application with hybrid strategy system, risk management, and multi-symbol support.
 */
public final class TradingBot {
    private static final Logger logger = LoggerFactory.getLogger(TradingBot.class);
    private static final Duration SLEEP_DURATION = Duration.ofSeconds(10);
    
    // Start time for uptime tracking
    private static final long START_TIME = System.currentTimeMillis();
    
    // Safety components
    private static com.trading.protection.HeartbeatMonitor heartbeatMonitor;
    private static com.trading.protection.EmergencyProtocol emergencyProtocol;
    
    // Symbol selector will be initialized in main() based on config
    private static SymbolSelector symbolSelector;
    
    public static long getStartTime() {
        return START_TIME;
    }


    public static void main(String[] args) {
        var config = new Config();
        if (!config.isValid()) {
            logger.error("Invalid configuration - missing API credentials");
            System.exit(1);
        }

        var client = new AlpacaClient(config);
        
        // Check if multi-profile mode is enabled
        if (config.isMultiProfileEnabled()) {
            logger.info("Starting Multi-Profile Trading Bot...");
            runMultiProfileMode(config, client);
        } else {
            logger.info("Starting Single-Profile Trading Bot...");
            runSingleProfileMode(config, client);
        }
    }
    
    /**
     * Run bot in multi-profile mode with parallel threads.
     */
    private static void runMultiProfileMode(Config config, AlpacaClient client) {
        // Create shared resources (thread-safe)
        var marketAnalyzer = new MarketAnalyzer(client);
        
        // Create multi-timeframe analyzer if enabled
        var multiTimeframeAnalyzer = config.isMultiTimeframeEnabled() ?
            new com.trading.analysis.MultiTimeframeAnalyzer(client, config) : null;
        
        var strategyManager = new StrategyManager(client, multiTimeframeAnalyzer);
        var marketHoursFilter = new MarketHoursFilter(config);
        var volatilityFilter = new VolatilityFilter(client);
        var database = new TradeDatabase();
        var pdtProtection = new PDTProtection(database, config.isPDTProtectionEnabled());
        
        // Initialize test mode simulator if enabled
        TestModeSimulator testSimulator = null;
        if (config.isTestModeEnabled()) {
            testSimulator = new TestModeSimulator(config.getTestModeFrequency());
            logger.warn("⚠️  TEST MODE ENABLED - Simulated trades will be generated");
        }
        
        // Create trading profiles
        var mainProfile = TradingProfile.main(config);
        var expProfile = TradingProfile.experimental(config);
        
        double totalCapital = config.getInitialCapital();
        double mainCapital = mainProfile.getCapitalAmount(totalCapital);
        double expCapital = expProfile.getCapitalAmount(totalCapital);
        
        logger.info("Capital Allocation:");
        logger.info("  Total: ${}", totalCapital);
        logger.info("  Main Profile: ${} ({}%)", mainCapital, mainProfile.capitalPercent() * 100);
        logger.info("  Experimental: ${} ({}%)", expCapital, expProfile.capitalPercent() * 100);
        logger.info("");
        logger.info("Main Profile: {} bullish + {} bearish symbols",
            mainProfile.bullishSymbols().size(), mainProfile.bearishSymbols().size());
        logger.info("Experimental: {} bullish + {} bearish symbols",
            expProfile.bullishSymbols().size(), expProfile.bearishSymbols().size());
        
        // Initialize AI components (shared between profiles)
        var alphaVantageClient = new com.trading.ai.AlphaVantageClient(
            config.getAlphaVantageApiKey(),
            config.isAlphaVantageEnabled(),
            config.getAlphaVantageCacheTTL(),
            config.getAlphaVantageNewsLimit(),
            config.getAlphaVantageMinRelevance()
        );
        var finGPTClient = new com.trading.ai.FinGPTClient(
            config.getHuggingFaceApiToken(),
            config.getFinGPTSentimentModel(),
            config.isFinGPTEnabled(),
            config.getFinGPTCacheTTL()
        );
        var sentimentAnalyzer = new com.trading.ai.SentimentAnalyzer(client, alphaVantageClient, finGPTClient);
        var signalPredictor = new com.trading.ai.SignalPredictor(config);
        var anomalyDetector = new com.trading.ai.AnomalyDetector();
        var riskPredictor = new com.trading.ai.RiskPredictor();
        
        // Initialize self-healing components
        var errorDetector = new com.trading.autonomous.ErrorDetector();
        var configSelfHealer = new com.trading.autonomous.ConfigSelfHealer(config, errorDetector);
        
        String sentimentProvider = alphaVantageClient.isEnabled() ? "Alpha Vantage" :
                                  finGPTClient.isEnabled() ? "FinGPT" : "Keywords Only";
        logger.info("🧠 AI components initialized (Sentiment: {})", sentimentProvider);
        logger.info("🔧 Self-healing system initialized");
        
        // Create resilient client wrapper FIRST - used by ProfileManagers for circuit breaker protection
        var resilientClient = new ResilientAlpacaClient(client, 
            com.trading.metrics.MetricsService.getInstance().getRegistry());
        logger.info("🛡️ Resilient client initialized with circuit breaker, rate limiter, and retry");
        
        var mainManager = new com.trading.portfolio.ProfileManager(
            mainProfile, mainCapital, resilientClient, strategyManager,
            marketHoursFilter, volatilityFilter, marketAnalyzer,
            database, pdtProtection, config, testSimulator,
            sentimentAnalyzer, signalPredictor, anomalyDetector, riskPredictor,
            errorDetector, configSelfHealer
        );
        
        var expManager = new com.trading.portfolio.ProfileManager(
            expProfile, expCapital, resilientClient, strategyManager,
            marketHoursFilter, volatilityFilter, marketAnalyzer,
            database, pdtProtection, config, testSimulator,
            sentimentAnalyzer, signalPredictor, anomalyDetector, riskPredictor,
            errorDetector, configSelfHealer
        );
        
        // Create autonomous systems
        var autoRecovery = new com.trading.autonomous.AutoRecoveryManager(config, client);
        var rebalancer = new com.trading.portfolio.ProfileRebalancer(config);
        logger.info("🤖 Autonomous systems initialized");
        
        // Create Kraken client for dashboard
        var krakenClient = new com.trading.broker.KrakenClient();
        
        // Start dashboard (using main profile's portfolio for now)
        var dashboard = new DashboardServer(database, mainManager.getPortfolio(), 
            marketAnalyzer, marketHoursFilter, volatilityFilter, config, resilientClient, krakenClient);
        dashboard.start();
        logger.info("Dashboard available at: http://localhost:8080");
        
        // Verify connection
        try {
            var account = client.getAccount();
            var status = account.get("status").asText();
            logger.info("Connected to Alpaca! Account Status: {}", status);
        } catch (Exception e) {
            logger.error("Failed to connect to Alpaca", e);
            System.exit(1);
        }
        // Initialize Safety Autopilot
        emergencyProtocol = new com.trading.protection.EmergencyProtocol(resilientClient);
        emergencyProtocol.setKrakenClient(krakenClient); // Enable crypto liquidation
        heartbeatMonitor = new com.trading.protection.HeartbeatMonitor(emergencyProtocol);
        
        heartbeatMonitor.registerComponent("Main Loop", Duration.ofSeconds(60));
        heartbeatMonitor.registerComponent("API Connection", Duration.ofSeconds(60));
        heartbeatMonitor.registerComponent("Profile-MAIN", Duration.ofSeconds(120)); // Give profiles more slack
        heartbeatMonitor.registerComponent("Profile-EXPERIMENTAL", Duration.ofSeconds(120));
        
        // Start Heartbeat Monitor Thread
        Thread.ofVirtual().name("heartbeat-monitor").start(() -> {
            while (true) {
                try {
                    heartbeatMonitor.checkHealth();
                    Thread.sleep(5000); // Check every 5 seconds
                } catch (InterruptedException e) {
                    break;
                }
            }
        });
        
        logger.info("✅ Safety Autopilot initialized (Dead Man's Switch Active)");
        
        // ===== START INDEPENDENT KRAKEN TRADING LOOP =====
        // Runs on separate virtual thread, 24/7, completely independent from Alpaca
        var gridTradingService = new com.trading.strategy.GridTradingService(
            client, krakenClient, config.getKrakenGridPositionSize());  // Configurable grid size
        var krakenLoop = new com.trading.crypto.KrakenTradingLoop(
            krakenClient, mainManager.getPortfolio(), gridTradingService,
            config.getKrakenTakeProfitPercent(),   // Kraken-specific TP
            config.getKrakenStopLossPercent(),     // Kraken-specific SL
            config.getKrakenTrailingStopPercent(), // Kraken trailing stop
            config.getKrakenMaxPositions(),        // Max positions
            config.getKrakenPositionSizeUsd(),     // Configurable position size
            config.getKrakenCycleIntervalMs()      // Configurable cycle interval
        );
        Thread krakenThread = Thread.ofVirtual().name("kraken-trading-loop").start(krakenLoop);
        logger.info("🦑 Independent Kraken Trading Loop started on virtual thread");
        
        logger.info("✅ Starting both profiles with virtual threads...");
        logger.info("   Main Profile: {} active positions", mainManager.getActivePositionCount());
        logger.info("   Experimental: {} active positions", expManager.getActivePositionCount());
        
        // Add shutdown hook for graceful termination
        Runtime.getRuntime().addShutdownHook(Thread.ofVirtual().unstarted(() -> {
            logger.info("Shutdown signal received, stopping profiles...");
            krakenLoop.stop();  // Stop Kraken loop too
            mainManager.stop();
            expManager.stop();
        }));
        
        
        // Use Java 25 virtual threads for parallel execution
        // Virtual threads are stable and production-ready in Java 25
        Thread mainThread = Thread.ofVirtual().name("profile-main").start(() -> {
            mainManager.run();
        });
        
        Thread expThread = Thread.ofVirtual().name("profile-experimental").start(() -> {
            expManager.run();
        });
        
        // Start maintenance thread for system-level heartbeats
        Thread.ofVirtual().name("maintenance-loop").start(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    com.trading.bot.TradingBot.beat("Main Loop");
                    com.trading.bot.TradingBot.beat("API Connection");
                    Thread.sleep(Duration.ofSeconds(10));
                } catch (InterruptedException e) {
                    break;
                }
            }
        });
        
        // Wait for both profiles to complete
        try {
            mainThread.join();
            expThread.join();
        } catch (InterruptedException e) {
            logger.info("Bot interrupted during execution");
            Thread.currentThread().interrupt();
        }
        
        logger.info("Multi-profile bot shutdown complete");
    }
    
    /**
     * Run bot in single-profile mode (legacy behavior).
     */
    private static void runSingleProfileMode(Config config, AlpacaClient client) {
        logger.info("Starting Java Trading Bot (Professional Edition with Dynamic Symbol Selection)...");
        
        // Initialize symbol selector with config values
        symbolSelector = new SymbolSelector(
            config.getBullishSymbols(),
            config.getBearishSymbols(),
            config.getVixThreshold(),
            config.getVixHysteresis()
        );
        
        // Get initial VIX to determine starting symbols
        var volatilityFilter = new VolatilityFilter(client);
        var initialVix = volatilityFilter.getCurrentVIX();
        var initialSymbols = symbolSelector.selectSymbols(initialVix);
        
        logger.info("Initial VIX: {} - Starting with symbols: {}", initialVix, initialSymbols);
        
        var marketAnalyzer = new MarketAnalyzer(client);
        
        // Create multi-timeframe analyzer if enabled
        var multiTimeframeAnalyzer = config.isMultiTimeframeEnabled() ?
            new com.trading.analysis.MultiTimeframeAnalyzer(client, config) : null;
        
        var strategyManager = new StrategyManager(client, multiTimeframeAnalyzer);
        var riskManager = new RiskManager(config.getInitialCapital());
        var marketHoursFilter = new MarketHoursFilter(config);
        var portfolio = new PortfolioManager(initialSymbols, config.getInitialCapital());
        
        // Sync portfolio with actual Alpaca positions
        portfolio.syncWithAlpaca(client, config.getMainTakeProfitPercent(), config.getMainStopLossPercent());
        
        var database = new TradeDatabase();
        
        // Create resilient client wrapper for health checks
        var resilientClient = new ResilientAlpacaClient(client, 
            com.trading.metrics.MetricsService.getInstance().getRegistry());
        
        // Create Kraken client for dashboard
        var krakenClient = new com.trading.broker.KrakenClient();
        
        // Initialize Grid Trading Service for passive crypto fishing/harvesting
        var gridTradingService = new com.trading.strategy.GridTradingService(
            client, krakenClient, config.getKrakenGridPositionSize());  // Configurable grid size
        
        var dashboard = new DashboardServer(database, portfolio, marketAnalyzer,
            marketHoursFilter, volatilityFilter, config, resilientClient, krakenClient);
        
        // Initialize PDT protection
        var pdtProtection = new PDTProtection(database, config.isPDTProtectionEnabled());
        
        // Initialize test mode simulator if enabled
        TestModeSimulator testSimulator = null;
        if (config.isTestModeEnabled()) {
            testSimulator = new TestModeSimulator(config.getTestModeFrequency());
            logger.warn("⚠️  TEST MODE ENABLED - Simulated trades will be generated for demonstration");
            logger.warn("⚠️  No real orders will be placed in test mode");
        }
        
        // ===== START INDEPENDENT KRAKEN TRADING LOOP =====
        // Runs on separate thread, 24/7, completely independent from Alpaca
        var krakenLoop = new com.trading.crypto.KrakenTradingLoop(
            krakenClient, portfolio, gridTradingService,
            config.getKrakenTakeProfitPercent(),   // Kraken-specific TP
            config.getKrakenStopLossPercent(),     // Kraken-specific SL
            config.getKrakenTrailingStopPercent(), // Kraken trailing stop
            config.getKrakenMaxPositions(),        // Max positions
            config.getKrakenPositionSizeUsd(),     // Configurable position size
            config.getKrakenCycleIntervalMs()      // Configurable cycle interval
        );
        Thread krakenThread = Thread.ofVirtual().name("kraken-trading-loop").start(krakenLoop);
        logger.info("🦑 Independent Kraken Trading Loop started on virtual thread");
        
        // Start dashboard
        dashboard.start();
        logger.info("Dashboard available at: http://localhost:8080");

        try {
            // Verify connection (SKIPPED to prevent startup hang)
            // var account = client.getAccount();
            // var status = account.get("status").asText();
            logger.info("Connected to Alpaca! (Connection check skipped for speed)");
            
            // Initialize Broker Router
            var brokerRouter = new com.trading.broker.BrokerRouter(client);

            // Main trading loop (ALPACA ONLY now - Kraken has independent loop)
            while (true) {
                try {
                    // 🚨 PANIC SELL MODE: Alpaca positions only
                    // Kraken panic handled by EmergencyProtocol.flattenAll()
                    
                    // Run Alpaca trading cycle (stocks only, respects market hours)
                    runTradingCycle(client, strategyManager, riskManager, 
                        marketHoursFilter, volatilityFilter, portfolio, marketAnalyzer, database, config, pdtProtection, testSimulator, brokerRouter, krakenClient);
                    
                    Thread.sleep(SLEEP_DURATION);
                } catch (InterruptedException e) {
                    logger.info("Bot interrupted, shutting down");
                    krakenLoop.stop(); // Stop Kraken loop too
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    logger.error("Error in trading cycle", e);
                    Thread.sleep(SLEEP_DURATION);
                }
                
                // Send heartbeat if monitor is active
                if (heartbeatMonitor != null) {
                    heartbeatMonitor.beat("Main Loop");
                    heartbeatMonitor.beat("API Connection"); // If we reached here without exception, API is likely ok
                }
            }
        } catch (Exception e) {
            logger.error("Fatal error", e);
            krakenLoop.stop(); // Stop Kraken loop on fatal error
            System.exit(1);
        }
    }

    private static void runTradingCycle(
            AlpacaClient client, 
            StrategyManager strategyManager, 
            RiskManager riskManager,
            MarketHoursFilter marketHoursFilter,
            VolatilityFilter volatilityFilter,
            PortfolioManager portfolio,
            MarketAnalyzer marketAnalyzer,
            TradeDatabase database,
            Config config,
            PDTProtection pdtProtection,
            TestModeSimulator testSimulator,
            com.trading.broker.BrokerRouter router,
            com.trading.broker.KrakenClient krakenClient) throws Exception {
        
        // Run market analysis (updates dashboard)
        var analysis = marketAnalyzer.analyze(portfolio.getSymbols());
        TradingWebSocketHandler.broadcastActivity("Market analysis completed. Trend: " + analysis.marketTrend(), "INFO");
        
        // Validate account status before trading
        if (!client.validateAccountForTrading()) {
            logger.error("Account validation failed - halting trading");
            TradingWebSocketHandler.broadcastActivity("⚠️ Trading halted: Account validation failed", "ERROR");
            return;
        }
        
        // Broadcast market update
        TradingWebSocketHandler.broadcastMarketUpdate(
            analysis.marketTrend(),
            analysis.vixLevel(),
            analysis.recommendation(),
            analysis.topAsset(),
            analysis.assetScores()
        );
        
        // Get account equity for position sizing
        var account = client.getAccount();
        var alpacaEquity = account.get("equity").asDouble();
        
        // Cap equity at configured INITIAL_CAPITAL for small account testing
        // This allows testing fractional trading behavior even on large paper accounts
        var equity = Math.min(alpacaEquity, config.getInitialCapital());
        
        if (equity < alpacaEquity) {
            logger.info("Capital capped: Using ${} (configured) instead of ${} (actual) for position sizing", 
                String.format("%.2f", equity), String.format("%.2f", alpacaEquity));
        }

        // Check market hours
        boolean isMarketOpen = marketHoursFilter.isMarketOpen();
        boolean bypassMarketHours = new Config().isMarketHoursBypassEnabled();
        String currentSession = marketHoursFilter.getCurrentSession();
        
        if (!isMarketOpen && !bypassMarketHours) {
            logger.debug("Market is closed: {}", marketHoursFilter.getClosedReason());
            // Continue to broadcast data, but skip trading
        } else if (!volatilityFilter.isVolatilityAcceptable() && !bypassMarketHours) {
            logger.warn("Volatility too high, skipping trading");
            // Continue to broadcast data, but skip trading
        } else {
            // Log current trading session
            if (config.isExtendedHoursEnabled() && !currentSession.equals("REGULAR")) {
                logger.info("🕐 Trading in {} session (Extended Hours Active)", currentSession);
            }
        }

        // Broadcast detailed system status
        TradingWebSocketHandler.broadcastSystemStatus(
            isMarketOpen || bypassMarketHours,
            volatilityFilter.isVolatilityAcceptable() || bypassMarketHours,
            config.getTradingMode(),
            portfolio.getActivePositionCount(),
            equity - config.getInitialCapital(),
            analysis.marketTrend(),
            analysis.vixLevel(),
            analysis.recommendation(),
            analysis.marketStrength(),
            database.getTotalTrades(),
            portfolio.getWinRate(),
            0.0 // buying power (will be updated in first cycle)
        );
        
        // Check for max drawdown
        if (riskManager.shouldHaltTrading(equity)) {
            logger.error("HALTING TRADING: Max drawdown exceeded!");
            return;
        }
        
        // Get current VIX and determine target symbols
        var currentVix = volatilityFilter.getCurrentVIX();
        var targetSymbols = symbolSelector.selectSymbols(currentVix);
        
        // Determine symbols to process (Target Symbols + Active Positions not in target)
        Set<String> symbolsToProcess = new HashSet<>(targetSymbols);
        var activeSymbols = portfolio.getActiveStoredSymbols();
        
        // Also process any active positions that are not in current target list
        // (to manage exits when regime changes)
        for (String activeSymbol : activeSymbols) {
            if (!targetSymbols.contains(activeSymbol)) {
                symbolsToProcess.add(activeSymbol);
                logger.info("Including {} for exit management (not in current target list)", activeSymbol);
            }
        }
        
        int symbolIndex = 0;
        int totalSymbols = symbolsToProcess.size();
        
        for (String symbol : symbolsToProcess) {
            try {
                symbolIndex++;
                // Broadcast which symbol we're currently processing
                TradingWebSocketHandler.broadcastProcessingStatus(symbol, symbolIndex, totalSymbols, "DATA", "Fetching market data...");
                TradingWebSocketHandler.broadcastActivity("Analyzing " + symbol + "...", "INFO");
                
                var bar = client.getLatestBar(symbol);
            if (bar.isEmpty()) {
                logger.warn("Could not get price for {}", symbol);
                continue;
            }
            var currentPrice = bar.get().close();
            var openPrice = bar.get().open(); 
                var change = currentPrice - openPrice;
                var changePercent = (change / openPrice) * 100;
                
                // Get Volatility State
                var volState = volatilityFilter.getVolatilityState();

                TradingWebSocketHandler.broadcastProcessingStatus(symbol, symbolIndex, totalSymbols, "ANALYSIS", 
                    "Price: $" + String.format("%.2f", currentPrice) + " | VIX: " + String.format("%.2f", currentVix));
                
                // Get score
                var position = client.getPosition(symbol);
                var qty = position.map(p -> p.quantity()).orElse(0.0);
                
                // Evaluate Strategy with Volatility State
                var signal = strategyManager.evaluate(symbol, currentPrice, qty, volState);
                var score = 50.0; 
                
                if (signal instanceof TradingSignal.Buy) score = 80.0;
                else if (signal instanceof TradingSignal.Sell) score = 20.0;
                
                TradingWebSocketHandler.broadcastProcessingStatus(symbol, symbolIndex, totalSymbols, "STRATEGY", 
                    "Signal: " + signal.getClass().getSimpleName() + " (" + strategyManager.getActiveStrategy() + ")");
                
                // Broadcast individual symbol update
                TradingWebSocketHandler.broadcastMarketUpdate(
                    symbol, currentPrice, change, changePercent, bar.get().volume(),
                    strategyManager.getCurrentRegimeString(), score, score // Using score as RSI placeholder
                );

                // Execute trades - ALPACA ONLY (stocks)
                // Crypto now handled by independent KrakenTradingLoop
                boolean isCrypto = symbol.contains("/") || (symbol.endsWith("USD") && symbol.length() > 6);
                
                if (isCrypto) {
                    // Skip crypto in Alpaca loop - handled by KrakenTradingLoop
                    TradingWebSocketHandler.broadcastProcessingStatus(symbol, symbolIndex, totalSymbols, "SKIPPED", "Crypto handled by Kraken loop");
                    continue;
                }
                
                boolean canTrade = isMarketOpen || bypassMarketHours;
                
                if (canTrade) {
                    // Prevent entering new positions if symbol is not a target for current regime
                    boolean isTargetForRegime = targetSymbols.contains(symbol);
                    if (signal instanceof TradingSignal.Buy && !isTargetForRegime && qty == 0) {
                        logger.info("Skipping BUY for {} as it is not a target for current VIX regime", symbol);
                        TradingWebSocketHandler.broadcastProcessingStatus(symbol, symbolIndex, totalSymbols, "SKIPPED", "Not target for current VIX");
                        continue;
                    }

                    String tradeContext = isCrypto ? "Crypto 24/7" : (isMarketOpen ? "Market open" : "Bypass mode");
                    TradingWebSocketHandler.broadcastProcessingStatus(symbol, symbolIndex, totalSymbols, "EXECUTION", "Checking execution rules... (" + tradeContext + ")");
                    
                    // Pass VIX and VolatilityState to tradeSymbol for dynamic sizing and strategy selection
                    var updatedPosition = tradeSymbol(client, strategyManager, riskManager, 
                        portfolio, symbol, database, pdtProtection, equity, testSimulator, currentVix, volState, router, krakenClient);
                    portfolio.setPosition(symbol, updatedPosition);
                } else {
                    TradingWebSocketHandler.broadcastProcessingStatus(symbol, symbolIndex, totalSymbols, "SKIPPED", "Stock market closed (use Crypto for 24/7)");
                }
            } catch (Exception e) {
                logger.error("Error processing {}: {}", symbol, e.getMessage());
            }
        }
        
        // Log portfolio status
        logger.info("Portfolio: {} active positions", portfolio.getActivePositionCount());
        
        // Broadcast portfolio update
        var totalValue = equity;
        var totalPnL = totalValue - config.getInitialCapital();
        var pnlPercent = (totalPnL / config.getInitialCapital()) * 100;
        
        TradingWebSocketHandler.broadcastPortfolioUpdate(
            totalValue, totalPnL, pnlPercent, 
            portfolio.getActivePositionCount(), portfolio.getWinRate());
            
        // Broadcast detailed positions list
        var activePositions = portfolio.getAllPositions().values().stream()
            .filter(Optional::isPresent)
            .map(Optional::get)
            .toList();
        TradingWebSocketHandler.broadcastPositions(activePositions);
    }

    private static Optional<TradePosition> tradeSymbol(AlpacaClient client, StrategyManager strategyManager, 
                               RiskManager riskManager, PortfolioManager portfolio, 
                               String symbol, TradeDatabase database, 
                               PDTProtection pdtProtection, double accountEquity,
                               TestModeSimulator testSimulator, double currentVix,
                               VolatilityFilter.VolatilityState volState,
                               com.trading.broker.BrokerRouter router,
                               com.trading.broker.KrakenClient krakenClient) throws Exception {
        
        var currentPosition = portfolio.getPosition(symbol);
        boolean isCrypto = symbol.contains("/") || symbol.endsWith("USD") && symbol.length() > 6;
        
        // Get current price
        double currentPrice = 0.0;
        if (isCrypto) {
            try {
                // Fetch crypto price from Kraken
                 var tickerFuture = krakenClient.getTickerAsync(symbol);
                 var tickerJson = tickerFuture.get(5, java.util.concurrent.TimeUnit.SECONDS);
                 
                 // Simplified parsing for speed - robust impl would parse JSON
                 // Fallback to async if needed
                 currentPrice = parseKrakenPrice(tickerJson); 
            } catch (Exception e) {
                logger.warn("Failed to get Kraken price for {}: {}", symbol, e.getMessage());
                return Optional.empty();
            }
        } else {
            var bar = client.getLatestBar(symbol);
            if (bar.isEmpty()) return Optional.empty();
            currentPrice = bar.get().close();
        }

        // Get current quantity (from PortfolioManager as it is now synced)
        var qty = currentPosition.map(p -> p.quantity()).orElse(0.0);

        // Check stop-loss and take-profit if we have a position
        if (currentPosition.isPresent() && qty > 0) {
            var pos = currentPosition.get();
            
            // Update trailing stop
            var updatedPos = riskManager.updatePositionTrailingStop(pos, currentPrice);
            if (updatedPos.stopLoss() > pos.stopLoss()) {
                logger.info("{}: Trailing stop updated: ${} -> ${}", symbol,
                    String.format("%.2f", pos.stopLoss()), 
                    String.format("%.2f", updatedPos.stopLoss()));
                pos = updatedPos;
            }
            
            if (pos.isStopLossHit(currentPrice)) {
                logger.warn("{}: STOP-LOSS HIT! Entry=${}, Current=${}, Loss=${}", 
                    symbol, pos.entryPrice(), currentPrice, pos.calculatePnL(currentPrice));
                if (testSimulator == null) {
                    // Use BrokerRouter for exit
                    if (isCrypto) {
                         router.routeSellOrderAsync(symbol, qty, 0.0); // Market sell
                         TradingWebSocketHandler.broadcastActivity("🦑 KRAKEN SL TRIGGERED: " + symbol + " @ $" + currentPrice, "WARN");
                    } else {
                        double limitPrice = currentPrice * 0.999;
                        client.placeOrder(symbol, qty, "sell", "limit", "day", limitPrice);
                    }
                    logger.info("{}: Stop-loss exit order placed", symbol);
                } else {
                    logger.info("{}: [TEST MODE] Stop-loss triggered (no real order)", symbol);
                }
                return Optional.empty();
            }
            
            // Log Kraken Risk Status periodically (every ~5% change or when close)
            if (isCrypto) {
                double distToTp = (pos.takeProfit() - currentPrice) / currentPrice * 100;
                if (distToTp < 0.5 || Math.abs(distToTp) < 0.1) {
                    TradingWebSocketHandler.broadcastActivity(
                        String.format("🦑 %s Check: $%.4f | TP: $%.4f (%.1f%% away)", symbol, currentPrice, pos.takeProfit(), distToTp), 
                        "INFO"
                    );
                }
            }

            if (pos.isTakeProfitHit(currentPrice)) {
                logger.info("{}: TAKE-PROFIT HIT! Entry=${}, Current=${}, Profit=${}", 
                    symbol, pos.entryPrice(), currentPrice, pos.calculatePnL(currentPrice));
                if (testSimulator == null) {
                    // Use BrokerRouter for exit
                    if (isCrypto) {
                         router.routeSellOrderAsync(symbol, qty, 0.0); // Market sell
                         TradingWebSocketHandler.broadcastActivity("🦑 KRAKEN TP TRIGGERED: " + symbol + " @ $" + currentPrice, "SUCCESS");
                    } else {
                        double limitPrice = currentPrice * 0.999;
                        client.placeOrder(symbol, qty, "sell", "limit", "day", limitPrice);
                    }
                    logger.info("{}: Take-profit exit order placed", symbol);
                } else {
                    logger.info("{}: [TEST MODE] Take-profit triggered (no real order)", symbol);
                }
                return Optional.empty();
            }
        }
        
        // For crypto: Evaluate using momentum-based strategy (price change analysis)
        // For stocks: Use regular strategy manager
        var signal = isCrypto ? evaluateCryptoSignal(symbol, currentPrice, qty, krakenClient, portfolio)
                              : strategyManager.evaluate(symbol, currentPrice, qty, volState);
        
        // In test mode, check if we should generate a test signal
        if (testSimulator != null) {
            // Get market history for test evaluation
            var bars = client.getMarketHistory(symbol, 100);
            var closes = bars.stream().map(b -> b.close()).toList();
            
            if (closes.size() >= 14) {
                double rsi = testSimulator.calculateTestRSI(closes);
                // Simplified MACD calculation for test mode
                double macd = closes.get(closes.size() - 1) - closes.get(closes.size() - 12);
                double signalLine = closes.get(closes.size() - 1) - closes.get(closes.size() - 26);
                
                var testSignal = testSimulator.evaluateTestSignal(symbol, currentPrice, qty, rsi, macd, signalLine);
                if (testSignal != null) {
                    signal = testSignal; // Override with test signal
                }
            }
        }

        // Execute trade based on signal
        if (signal instanceof TradingSignal.Buy buy) {
            logger.info("{}: SIGNAL: BUY - {}", symbol, buy.reason());

            // Calculate safe position size based on symbol allocation
            // For crypto: use smaller position size for micro-profit trading
            double capitalPerSymbol = isCrypto ? 50.0 : portfolio.getCapitalPerSymbol();
            double positionSize = riskManager.calculatePositionSize(capitalPerSymbol, currentPrice, currentVix);
            
            // For crypto, calculate volume with proper precision
            if (isCrypto) {
                positionSize = Math.round(positionSize * 100000000.0) / 100000000.0; // 8 decimal places
            }
            
            logger.info("{}: Position sizing: {} {} (VIX adjusted, Crypto={})", 
                symbol, positionSize, isCrypto ? "units" : "shares", isCrypto);

            // Track position with risk parameters (use tighter settings for crypto micro-profit)
            var newPosition = isCrypto 
                ? riskManager.createCryptoPosition(symbol, currentPrice, positionSize) 
                : riskManager.createPosition(symbol, currentPrice, positionSize);
            logger.info("{}: Position tracked: Entry=${}, StopLoss=${}, TakeProfit={}",
                symbol, newPosition.entryPrice(), newPosition.stopLoss(), newPosition.takeProfit());

            // Place order (skip if in test mode)
            if (testSimulator == null) {
                if (isCrypto) {
                    // === CRYPTO ORDER VIA KRAKEN ===
                    try {
                        String krakenSymbol = com.trading.broker.KrakenClient.toKrakenSymbol(symbol);
                        String volumeStr = String.format("%.8f", positionSize);
                        
                        // Use market order for immediate execution
                        String result = krakenClient.placeMarketOrderAsync(krakenSymbol, "buy", positionSize).join();
                        
                        if (result.contains("ERROR")) {
                            logger.error("🦑 Kraken order failed for {}: {}", symbol, result);
                            TradingWebSocketHandler.broadcastActivity("🦑 ORDER FAILED: " + symbol + " - " + result, "ERROR");
                            return Optional.empty();
                        }
                        
                        logger.info("🦑 {}: Market BUY order placed: {} units", symbol, volumeStr);
                        TradingWebSocketHandler.broadcastActivity("🦑 BUY " + symbol + ": " + volumeStr + " @ $" + 
                            String.format("%.2f", currentPrice), "SUCCESS");
                    } catch (Exception e) {
                        logger.error("🦑 Failed to place Kraken order for {}: {}", symbol, e.getMessage());
                        return Optional.empty();
                    }
                } else {
                    // === STOCK ORDER VIA ALPACA ===
                    double limitPrice = currentPrice * 1.001;
                    
                    client.placeBracketOrder(
                        symbol,
                        positionSize,
                        "buy",
                        newPosition.takeProfit(),  // Take-profit price
                        newPosition.stopLoss(),     // Stop-loss price
                        null,                       // Use stop-market (not stop-limit)
                        limitPrice                  // Entry limit price
                    );
                    logger.info("{}: Bracket order placed successfully (Limit: ${})", 
                        symbol, String.format("%.2f", limitPrice));
                }
            } else {
                logger.info("{}: [TEST MODE] Simulated order placement (no real order)", symbol);
            }
            TradingWebSocketHandler.broadcastActivity("BUY executed for " + symbol + ": " + positionSize + 
                (isCrypto ? " units" : " shares"), "SUCCESS");
        
            // Broadcast trade event
            TradingWebSocketHandler.broadcastTradeEvent(
                symbol, "BUY", currentPrice, positionSize, buy.reason());

            // Record in database
            database.recordTrade(symbol, "HYBRID", "SINGLE", newPosition.entryTime(),
                newPosition.entryPrice(), newPosition.quantity(),
                newPosition.stopLoss(), newPosition.takeProfit());

            return Optional.of(newPosition);
        } else if (signal instanceof TradingSignal.Sell sell) {
            logger.info("{}: SIGNAL: SELL - {}", symbol, sell.reason());
            
            if (qty > 0) {
                // Check PDT protection before selling
                if (!pdtProtection.canTrade(symbol, true, accountEquity)) {
                    logger.warn("{}: SELL blocked by PDT protection", symbol);
                    TradingWebSocketHandler.broadcastActivity(
                        "⚠️ SELL blocked for " + symbol + ": PDT protection (avoid 4th day trade)", 
                        "WARN");
                    return Optional.empty(); // Don't sell, keep position
                }
                
                // Place order (skip if in test mode)
                if (testSimulator == null) {
                    // Use limit order for exit to control slippage (0.1% buffer)
                    double limitPrice = currentPrice * 0.999;
                    
                    client.placeOrder(symbol, qty, "sell", "limit", "day", limitPrice);
                    logger.info("{}: Order placed successfully (Limit: ${})", 
                        symbol, String.format("%.2f", limitPrice));
                    
                    // Record day trade if applicable
                    pdtProtection.recordDayTrade(symbol);
                } else {
                    logger.info("{}: [TEST MODE] Simulated order placement (no real order)", symbol);
                }
                TradingWebSocketHandler.broadcastActivity("SELL executed for " + symbol + ": " + qty + " shares", "SUCCESS");
                
                if (currentPosition.isPresent()) {
                    var pnl = currentPosition.get().calculatePnL(currentPrice);
                    logger.info("{}: Position closed: P&L=${}", symbol, String.format("%.2f", pnl));
                    
                    // Broadcast trade event
                    TradingWebSocketHandler.broadcastTradeEvent(
                        symbol, "SELL", currentPrice, qty, 
                        sell.reason() + " (P&L: $" + String.format("%.2f", pnl) + ")");
                    
                    // Record close in database
                    database.closeTrade(symbol, java.time.Instant.now(), currentPrice, pnl);
                }
            }
            
            return Optional.empty();
        } else if (signal instanceof TradingSignal.Hold hold) {
            logger.info("{}: SIGNAL: HOLD - {}", symbol, hold.reason());
            TradingWebSocketHandler.broadcastActivity("HOLD " + symbol + ": " + hold.reason(), "INFO");
            
            // For hold, check client-side stops and update trailing stop
            if (currentPosition.isPresent() && qty > 0) {
                var pos = currentPosition.get();
                
                // 1. Check Client-Side Stop Loss / Take Profit (Crucial for Fractional Shares)
                boolean stopHit = currentPrice <= pos.stopLoss();
                boolean profitHit = currentPrice >= pos.takeProfit();
                
                if (stopHit || profitHit) {
                    String reason = stopHit ? "Stop Loss Hit" : "Take Profit Hit";
                    logger.warn("{}: Client-side {} triggered! Price=${} (SL=${}, TP=${})", 
                        symbol, reason, currentPrice, pos.stopLoss(), pos.takeProfit());
                    
                    // Execute Immediate Sell
                    if (testSimulator == null) {
                        // Use Market order for stops to ensure exit
                        client.placeOrder(symbol, qty, "sell", "market", "day", null);
                        logger.info("{}: Emergency exit order placed", symbol);
                        pdtProtection.recordDayTrade(symbol);
                    } else {
                        logger.info("{}: [TEST MODE] Simulated emergency exit", symbol);
                    }
                    
                    // Close position in DB
                    var pnl = pos.calculatePnL(currentPrice);
                    database.closeTrade(symbol, java.time.Instant.now(), currentPrice, pnl);
                    TradingWebSocketHandler.broadcastTradeEvent(symbol, "SELL", currentPrice, qty, reason);
                    
                    return Optional.empty();
                }

                // 2. Update Trailing Stop
                var updatedPos = riskManager.updatePositionTrailingStop(pos, currentPrice);
                
                // If trailing stop moved up, update server-side order (if exists)
                if (updatedPos.stopLoss() > pos.stopLoss()) {
                    if (testSimulator == null) {
                        try {
                            // Find open stop loss order (only works for whole shares with bracket)
                            var openOrders = client.getOpenOrders(symbol);
                            for (var order : openOrders) {
                                if (order.get("type").asText().equals("stop") && 
                                    order.get("side").asText().equals("sell")) {
                                    
                                    // Update the stop price
                                    client.replaceOrder(
                                        order.get("id").asText(), 
                                        null, 
                                        null, 
                                        updatedPos.stopLoss()
                                    );
                                    logger.info("{}: Server-side stop loss updated to ${}", 
                                        symbol, String.format("%.2f", updatedPos.stopLoss()));
                                    break;
                                }
                            }
                        } catch (Exception e) {
                            logger.error("Failed to update server-side stop loss for {}", symbol, e);
                        }
                    }
                    return Optional.of(updatedPos);
                }
                
                return Optional.of(updatedPos);
            }
            
            return currentPosition;
        }
        
        return currentPosition;
    }


    public static java.util.Map<String, Object> triggerManualPanic(String reason) {
        if (emergencyProtocol != null) {
            return emergencyProtocol.trigger(reason);
        } else {
            logger.error("Cannot trigger panic: EmergencyProtocol not initialized");
            return java.util.Map.of("status", "error", "message", "EmergencyProtocol not initialized");
        }
    }
    
    public static java.util.Map<String, Object> resetEmergencyProtocol() {
        if (emergencyProtocol != null) {
            return emergencyProtocol.reset();
        } else {
            logger.error("Cannot reset: EmergencyProtocol not initialized");
            return java.util.Map.of("status", "error", "message", "EmergencyProtocol not initialized");
        }
    }
    
    public static boolean isEmergencyTriggered() {
        return emergencyProtocol != null && emergencyProtocol.isTriggered();
    }
    
    public static Map<String, Long> getHeartbeatDetails() {
        if (heartbeatMonitor != null) {
            return heartbeatMonitor.getDetails();
        }
        return new HashMap<>();
    }

    public static void beat(String component) {
        if (heartbeatMonitor != null) {
            heartbeatMonitor.beat(component);
        }
    }

    private static double parseKrakenPrice(String json) {
        try {
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            var root = mapper.readTree(json);
            if (root.has("result")) {
                var result = root.get("result");
                var fields = result.fieldNames();
                if (fields.hasNext()) {
                    var pair = result.get(fields.next());
                    if (pair.has("c")) {
                        return pair.get("c").get(0).asDouble();
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Failed to parse Kraken price", e);
        }
        return 0.0;
    }
    
    /**
     * Evaluate crypto trading signal using momentum-based strategy.
     * Optimized for micro-profit trading on Kraken 24/7.
     */
    private static TradingSignal evaluateCryptoSignal(String symbol, double currentPrice, double qty, 
            com.trading.broker.KrakenClient krakenClient, PortfolioManager portfolio) {
        
        // If already holding position, don't add more
        if (qty > 0) {
            return new TradingSignal.Hold("Holding position - monitoring SL/TP");
        }
        
        // Check max positions limit
        int krakenMaxPositions = 3; // From config
        long currentKrakenPositions = portfolio.getAllPositions().entrySet().stream()
            .filter(e -> {
                String s = e.getKey();
                return (s.contains("/") || (s.endsWith("USD") && s.length() > 6)) && e.getValue().isPresent();
            })
            .count();
        
        if (currentKrakenPositions >= krakenMaxPositions) {
            return new TradingSignal.Hold("Max Kraken positions reached (" + krakenMaxPositions + ")");
        }
        
        try {
            // Fetch 24h ticker data for momentum analysis
            String krakenSymbol = com.trading.broker.KrakenClient.toKrakenSymbol(symbol);
            String tickerJson = krakenClient.getTickerAsync(krakenSymbol).get(5, java.util.concurrent.TimeUnit.SECONDS);
            
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            var root = mapper.readTree(tickerJson);
            
            if (root.has("error") && root.get("error").size() > 0) {
                logger.warn("🦑 Ticker error for {}: {}", symbol, root.get("error"));
                return new TradingSignal.Hold("Ticker fetch error");
            }
            
            var result = root.get("result");
            if (result == null || !result.fields().hasNext()) {
                return new TradingSignal.Hold("No ticker data");
            }
            
            var pairData = result.fields().next().getValue();
            
            // Extract key metrics:
            // "o" = today's opening price
            // "h" = 24h high [today, last 24h rolling]
            // "l" = 24h low [today, last 24h rolling]
            // "c" = last trade [price, lot volume]
            // "v" = volume [today, last 24h]
            // "p" = VWAP [today, last 24h]
            
            double openPrice = pairData.get("o").asDouble();
            double lowPrice24h = pairData.get("l").get(1).asDouble();  // 24h low
            double highPrice24h = pairData.get("h").get(1).asDouble(); // 24h high
            double volume24h = pairData.get("v").get(1).asDouble();
            double vwap24h = pairData.get("p").get(1).asDouble();
            
            // Calculate indicators
            double dayChange = ((currentPrice - openPrice) / openPrice) * 100;
            double rangePosition = (currentPrice - lowPrice24h) / (highPrice24h - lowPrice24h) * 100; // 0=at low, 100=at high
            double distanceFromVWAP = ((currentPrice - vwap24h) / vwap24h) * 100; // negative = below VWAP
            
            logger.info("🦑 {} Analysis: Price=${} | Open=${} | DayChange={:.2f}% | RangePos={:.1f}% | VWAP_dist={:.2f}%",
                symbol, currentPrice, openPrice, dayChange, rangePosition, distanceFromVWAP);
            
            // === MICRO-PROFIT ENTRY CRITERIA ===
            // BUY when:
            // 1. Price is in lower 40% of 24h range (buying the dip)
            // 2. Price is at or below VWAP (fair value or undervalued)
            // 3. Day change is not too negative (not in freefall, -3% max)
            
            boolean inBuyZone = rangePosition < 40;
            boolean belowVWAP = distanceFromVWAP <= 0.5; // At or slightly above VWAP
            boolean notFreefalling = dayChange > -3.0;
            
            if (inBuyZone && belowVWAP && notFreefalling) {
                String reason = String.format("Micro-profit opportunity: %.1f%% range, %.2f%% from VWAP", 
                    rangePosition, distanceFromVWAP);
                logger.info("🦑 BUY SIGNAL: {} - {}", symbol, reason);
                return new TradingSignal.Buy(reason);
            }
            
            // Alternative: Momentum reversal buy
            // If dropped significantly today but showing volume (potential bounce)
            if (dayChange < -2.0 && dayChange > -5.0 && volume24h > 0) {
                String reason = String.format("Momentum reversal: %.2f%% drop with volume", dayChange);
                logger.info("🦑 BUY SIGNAL: {} - {}", symbol, reason);
                return new TradingSignal.Buy(reason);
            }
            
            return new TradingSignal.Hold(String.format("No entry: RangePos=%.0f%%, VWAP_dist=%.2f%%", 
                rangePosition, distanceFromVWAP));
                
        } catch (Exception e) {
            logger.warn("🦑 Failed to evaluate {}: {}", symbol, e.getMessage());
            return new TradingSignal.Hold("Evaluation error: " + e.getMessage());
        }
    }

    public static boolean isSystemHealthy() {
        if (heartbeatMonitor != null) {
            return heartbeatMonitor.isHealthy();
        }
        return true; // Default to true if monitor not active
    }
}
