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
    
    // Safety components
    private static com.trading.protection.HeartbeatMonitor heartbeatMonitor;
    private static com.trading.protection.EmergencyProtocol emergencyProtocol;
    
    // Symbol selector will be initialized in main() based on config
    private static SymbolSelector symbolSelector;


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
        
        var mainManager = new com.trading.portfolio.ProfileManager(
            mainProfile, mainCapital, client, strategyManager,
            marketHoursFilter, volatilityFilter, marketAnalyzer,
            database, pdtProtection, config, testSimulator,
            sentimentAnalyzer, signalPredictor, anomalyDetector, riskPredictor,
            errorDetector, configSelfHealer
        );
        
        var expManager = new com.trading.portfolio.ProfileManager(
            expProfile, expCapital, client, strategyManager,
            marketHoursFilter, volatilityFilter, marketAnalyzer,
            database, pdtProtection, config, testSimulator,
            sentimentAnalyzer, signalPredictor, anomalyDetector, riskPredictor,
            errorDetector, configSelfHealer
        );
        
        // Create autonomous systems
        var autoRecovery = new com.trading.autonomous.AutoRecoveryManager(config, client);
        var rebalancer = new com.trading.portfolio.ProfileRebalancer(config);
        logger.info("🤖 Autonomous systems initialized");
        
        // Create resilient client wrapper for health checks
        var resilientClient = new ResilientAlpacaClient(client, 
            com.trading.metrics.MetricsService.getInstance().getRegistry());
        
        // Start dashboard (using main profile's portfolio for now)
        var dashboard = new DashboardServer(database, mainManager.getPortfolio(), 
            marketAnalyzer, marketHoursFilter, volatilityFilter, config, resilientClient);
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
        
        logger.info("✅ Starting both profiles with virtual threads...");
        logger.info("   Main Profile: {} active positions", mainManager.getActivePositionCount());
        logger.info("   Experimental: {} active positions", expManager.getActivePositionCount());
        
        // Add shutdown hook for graceful termination
        Runtime.getRuntime().addShutdownHook(Thread.ofVirtual().unstarted(() -> {
            logger.info("Shutdown signal received, stopping profiles...");
            mainManager.stop();
            expManager.stop();
        }));
        
        // Use StructuredTaskScope for automatic cleanup and error propagation
        // This is Java 23's structured concurrency - treats multiple threads as a single unit
        try (var scope = new java.util.concurrent.StructuredTaskScope.ShutdownOnFailure()) {
            // Fork both profile tasks as virtual threads
            var mainFuture = scope.fork(() -> {
                mainManager.run();
                return null;
            });
            
            var expFuture = scope.fork(() -> {
                expManager.run();
                return null;
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
            
            // Wait for all tasks to complete
            scope.join();
            
            // Throw if any task failed
            scope.throwIfFailed();
            
        } catch (InterruptedException e) {
            logger.info("Bot interrupted during execution");
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            logger.error("Profile execution failed", e);
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
        
        var dashboard = new DashboardServer(database, portfolio, marketAnalyzer,
            marketHoursFilter, volatilityFilter, config, resilientClient);
        
        // Initialize PDT protection
        var pdtProtection = new PDTProtection(database, config.isPDTProtectionEnabled());
        
        // Initialize test mode simulator if enabled
        TestModeSimulator testSimulator = null;
        if (config.isTestModeEnabled()) {
            testSimulator = new TestModeSimulator(config.getTestModeFrequency());
            logger.warn("⚠️  TEST MODE ENABLED - Simulated trades will be generated for demonstration");
            logger.warn("⚠️  No real orders will be placed in test mode");
        }
        
        // Start dashboard
        dashboard.start();
        logger.info("Dashboard available at: http://localhost:8080");

        try {
            // Verify connection
            var account = client.getAccount();
            var status = account.get("status").asText();
            logger.info("Connected to Alpaca! Account Status: {}", status);

            // Main trading loop
            while (true) {
                try {
                    runTradingCycle(client, strategyManager, riskManager, 
                        marketHoursFilter, volatilityFilter, portfolio, marketAnalyzer, database, config, pdtProtection, testSimulator);
                    Thread.sleep(SLEEP_DURATION);
                } catch (InterruptedException e) {
                    logger.info("Bot interrupted, shutting down");
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
            TestModeSimulator testSimulator) throws Exception {
        
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

                // Execute trades
                // We no longer block on volatility, but we do check market hours
                if (isMarketOpen || bypassMarketHours) {
                    
                    // Prevent entering new positions if symbol is not a target for current regime
                    boolean isTargetForRegime = targetSymbols.contains(symbol);
                    if (signal instanceof TradingSignal.Buy && !isTargetForRegime && qty == 0) {
                        logger.info("Skipping BUY for {} as it is not a target for current VIX regime", symbol);
                        TradingWebSocketHandler.broadcastProcessingStatus(symbol, symbolIndex, totalSymbols, "SKIPPED", "Not target for current VIX");
                        continue;
                    }

                    TradingWebSocketHandler.broadcastProcessingStatus(symbol, symbolIndex, totalSymbols, "EXECUTION", "Checking execution rules...");
                    
                    // Pass VIX and VolatilityState to tradeSymbol for dynamic sizing and strategy selection
                    var updatedPosition = tradeSymbol(client, strategyManager, riskManager, 
                        portfolio, symbol, database, pdtProtection, equity, testSimulator, currentVix, volState);
                    portfolio.setPosition(symbol, updatedPosition);
                } else {
                    TradingWebSocketHandler.broadcastProcessingStatus(symbol, symbolIndex, totalSymbols, "SKIPPED", "Market closed");
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
                               VolatilityFilter.VolatilityState volState) throws Exception {
        
        var currentPosition = portfolio.getPosition(symbol);
        
        // Get current price
        var bar = client.getLatestBar(symbol);
        var currentPrice = bar.get().close();

        // Get current position from broker
        var position = client.getPosition(symbol);
        var qty = position.map(p -> p.quantity()).orElse(0.0);

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
                    // Use limit order for exit to control slippage (0.1% buffer)
                    double limitPrice = currentPrice * 0.999;
                    client.placeOrder(symbol, qty, "sell", "limit", "day", limitPrice);
                    logger.info("{}: Stop-loss exit order placed (Limit: ${})", 
                        symbol, String.format("%.2f", limitPrice));
                } else {
                    logger.info("{}: [TEST MODE] Stop-loss triggered (no real order)", symbol);
                }
                return Optional.empty();
            }
            
            if (pos.isTakeProfitHit(currentPrice)) {
                logger.info("{}: TAKE-PROFIT HIT! Entry=${}, Current=${}, Profit=${}", 
                    symbol, pos.entryPrice(), currentPrice, pos.calculatePnL(currentPrice));
                if (testSimulator == null) {
                    // Use limit order for exit to control slippage (0.1% buffer)
                    double limitPrice = currentPrice * 0.999;
                    client.placeOrder(symbol, qty, "sell", "limit", "day", limitPrice);
                    logger.info("{}: Take-profit exit order placed (Limit: ${})", 
                        symbol, String.format("%.2f", limitPrice));
                } else {
                    logger.info("{}: [TEST MODE] Take-profit triggered (no real order)", symbol);
                }
                return Optional.empty();
            }
        }

        // Evaluate strategy with Volatility State
        var signal = strategyManager.evaluate(symbol, currentPrice, qty, volState);
        
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
            // Pass VIX to risk manager for volatility adjustment
            double positionSize = riskManager.calculatePositionSize(
                portfolio.getCapitalPerSymbol(), currentPrice, currentVix);
            logger.info("{}: Position sizing: {} shares (VIX adjusted)", symbol, positionSize);

            // Track position with risk parameters
            var newPosition = riskManager.createPosition(symbol, currentPrice, positionSize);
            logger.info("{}: Position tracked: Entry=${}, StopLoss=${}, TakeProfit={}",
                symbol, newPosition.entryPrice(), newPosition.stopLoss(), newPosition.takeProfit());

            // Place bracket order with automatic stop-loss and take-profit (skip if in test mode)
            if (testSimulator == null) {
                // Use limit order for entry to control slippage (0.1% buffer)
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
            } else {
                logger.info("{}: [TEST MODE] Simulated bracket order placement (no real order)", symbol);
            }
            TradingWebSocketHandler.broadcastActivity("BUY executed for " + symbol + ": " + positionSize + " shares", "SUCCESS");
        
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


    public static void triggerManualPanic(String reason) {
        if (emergencyProtocol != null) {
            emergencyProtocol.trigger(reason);
        } else {
            logger.error("Cannot trigger panic: EmergencyProtocol not initialized");
        }
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

    public static boolean isSystemHealthy() {
        if (heartbeatMonitor != null) {
            return heartbeatMonitor.isHealthy();
        }
        return true; // Default to true if monitor not active
    }
}
