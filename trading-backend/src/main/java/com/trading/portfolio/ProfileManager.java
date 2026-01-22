package com.trading.portfolio;

import com.trading.ai.AnomalyDetector;
import com.trading.ai.RiskPredictor;
import com.trading.ai.SentimentAnalyzer;
import com.trading.ai.SignalPredictor;
import com.trading.api.ResilientAlpacaClient;
import com.trading.analysis.MarketAnalyzer;
import com.trading.config.Config;
import com.trading.filters.MarketHoursFilter;
import com.trading.filters.VolatilityFilter;
import com.trading.persistence.TradeDatabase;
import com.trading.protection.PDTProtection;
import com.trading.risk.RiskManager;
import com.trading.risk.PortfolioRiskManager;
import com.trading.risk.TradePosition;
import com.trading.analysis.MarketRegimeDetector;
import com.trading.analysis.MarketRegimeDetector.MarketRegime;
import com.trading.strategy.StrategyManager;
import com.trading.strategy.SymbolSelector;
import com.trading.strategy.TradingProfile;
import com.trading.strategy.TradingSignal;
import com.trading.testing.TestModeSimulator;
import com.trading.websocket.TradingWebSocketHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Manages trading operations for a single profile in a dedicated thread.
 * Each ProfileManager has isolated resources to prevent race conditions.
 */
public class ProfileManager implements Runnable {
    private static final Logger logger = LoggerFactory.getLogger(ProfileManager.class);
    private static final Duration SLEEP_DURATION = Duration.ofSeconds(10);
    
    private final TradingProfile profile;
    private final double capital;
    
    // Isolated resources (not shared between profiles)
    private final PortfolioManager portfolio;
    private final RiskManager riskManager;
    private final PortfolioRiskManager portfolioRiskManager;
    private final SymbolSelector symbolSelector;
    private final MarketRegimeDetector regimeDetector;
    private final com.trading.autonomous.AdaptiveParameterManager adaptiveManager;
    private final com.trading.exits.ExitStrategyManager exitStrategyManager;
    private final com.trading.exits.Phase2ExitStrategies phase2ExitStrategies;
    private final com.trading.analysis.CorrelationCalculator correlationCalculator;
    private final com.trading.portfolio.PortfolioRebalancer portfolioRebalancer;
    
    // Phase 3 Features
    private final com.trading.scoring.MLEntryScorer mlEntryScorer;
    private final com.trading.exits.TrailingTargetManager trailingTargetManager;
    private final com.trading.sizing.AdaptivePositionSizer adaptivePositionSizer;
    private final com.trading.exits.TimeDecayExitManager timeDecayExitManager;
    private final com.trading.exits.MomentumAccelerationDetector momentumDetector;
    private final com.trading.analysis.MarketBreadthAnalyzer marketBreadthAnalyzer;
    private final com.trading.analysis.VolumeProfileAnalyzer volumeProfileAnalyzer;
    private final com.trading.health.PositionHealthScorer healthScorer;
    private final com.trading.execution.SmartOrderRouter smartOrderRouter;
    private final com.trading.lending.StockLendingTracker lendingTracker;
    private final com.trading.options.OptionsStrategyManager optionsManager;
    
    // Shared resources (thread-safe)
    private final ResilientAlpacaClient client;
    private final StrategyManager strategyManager;
    private final MarketHoursFilter marketHoursFilter;
    private final VolatilityFilter volatilityFilter;
    private final MarketAnalyzer marketAnalyzer;
    private final TradeDatabase database;
    private final PDTProtection pdtProtection;
    private final Config config;
    private final TestModeSimulator testSimulator;
    
    // AI Components (optional - graceful degradation if null)
    private final SentimentAnalyzer sentimentAnalyzer;
    private final SignalPredictor signalPredictor;
    private final AnomalyDetector anomalyDetector;
    private final RiskPredictor riskPredictor;
    
    // Self-healing components
    private final com.trading.autonomous.ErrorDetector errorDetector;
    private final com.trading.autonomous.ConfigSelfHealer configSelfHealer;
    
    // Daily profit target tracking
    private double todayPnL = 0.0;
    private java.time.LocalDate lastResetDate = java.time.LocalDate.now();
    
    // Re-entry cooldown after stop loss (prevent immediate re-buy after SL)
    // Key = symbol, Value = timestamp when cooldown expires
    private final java.util.concurrent.ConcurrentHashMap<String, Long> stopLossCooldowns = new java.util.concurrent.ConcurrentHashMap<>();
    // Cooldown period read from config.getStopLossCooldownMs() - default 30 minutes
    
    private volatile boolean running = true;
    
    public ProfileManager(
            TradingProfile profile,
            double capital,
            ResilientAlpacaClient client,
            StrategyManager strategyManager,
            MarketHoursFilter marketHoursFilter,
            VolatilityFilter volatilityFilter,
            MarketAnalyzer marketAnalyzer,
            TradeDatabase database,
            PDTProtection pdtProtection,
            Config config,
            TestModeSimulator testSimulator,
            SentimentAnalyzer sentimentAnalyzer,
            SignalPredictor signalPredictor,
            AnomalyDetector anomalyDetector,
            RiskPredictor riskPredictor,
            com.trading.autonomous.ErrorDetector errorDetector,
            com.trading.autonomous.ConfigSelfHealer configSelfHealer) {
        
        this.profile = profile;
        this.capital = capital;
        this.client = client;
        this.strategyManager = strategyManager;
        this.marketHoursFilter = marketHoursFilter;
        this.volatilityFilter = volatilityFilter;
        this.marketAnalyzer = marketAnalyzer;
        this.database = database;
        this.pdtProtection = pdtProtection;
        this.config = config;
        this.testSimulator = testSimulator;
        this.sentimentAnalyzer = sentimentAnalyzer;
        this.signalPredictor = signalPredictor;
        this.anomalyDetector = anomalyDetector;
        this.riskPredictor = riskPredictor;
        this.errorDetector = errorDetector;
        this.configSelfHealer = configSelfHealer;
        
        // Create isolated resources for this profile
        this.symbolSelector = new SymbolSelector(
            profile.bullishSymbols(),
            profile.bearishSymbols(),
            profile.vixThreshold(),
            profile.vixHysteresis()
        );
        
        // Initialize with VIX-based symbol selection
        var initialVix = volatilityFilter.getCurrentVIX();
        var initialSymbols = symbolSelector.selectSymbols(initialVix);
        
        this.portfolio = new PortfolioManager(initialSymbols, capital);        
        // Sync existing positions from Alpaca using profile-specific risk settings
        portfolio.syncWithAlpaca(client.getDelegate(), profile.takeProfitPercent(), profile.stopLossPercent());
        
        // Create adaptive parameter manager for autonomous tuning
        this.adaptiveManager = new com.trading.autonomous.AdaptiveParameterManager(config, database);
        
        // Create advanced position sizer
        var positionSizer = new com.trading.risk.AdvancedPositionSizer(config, database);
        positionSizer.setAdaptiveManager(adaptiveManager);
        
        this.riskManager = new RiskManager(capital, positionSizer);
        this.portfolioRiskManager = new PortfolioRiskManager(config, capital);
        this.regimeDetector = new MarketRegimeDetector(client.getDelegate(), config, marketAnalyzer);
        
        // Create enhanced exit and portfolio management components
        this.exitStrategyManager = new com.trading.exits.ExitStrategyManager(config);
        this.phase2ExitStrategies = new com.trading.exits.Phase2ExitStrategies(config);
        this.correlationCalculator = new com.trading.analysis.CorrelationCalculator(client.getDelegate());
        this.portfolioRebalancer = new com.trading.portfolio.PortfolioRebalancer(config, correlationCalculator);
        
        // Create Phase 3 components
        this.mlEntryScorer = new com.trading.scoring.MLEntryScorer(config, marketAnalyzer, sentimentAnalyzer);
        this.trailingTargetManager = new com.trading.exits.TrailingTargetManager(config);
        this.adaptivePositionSizer = new com.trading.sizing.AdaptivePositionSizer(config);
        this.timeDecayExitManager = new com.trading.exits.TimeDecayExitManager(config);
        this.momentumDetector = new com.trading.exits.MomentumAccelerationDetector(config);
        this.marketBreadthAnalyzer = new com.trading.analysis.MarketBreadthAnalyzer(config);
        this.volumeProfileAnalyzer = new com.trading.analysis.VolumeProfileAnalyzer(config);
        this.healthScorer = new com.trading.health.PositionHealthScorer();
        this.smartOrderRouter = new com.trading.execution.SmartOrderRouter(config, client.getDelegate());
        this.lendingTracker = new com.trading.lending.StockLendingTracker(config);
        this.optionsManager = new com.trading.options.OptionsStrategyManager(config);
        
        logger.info("═══════════════════════════════════════════════════════");
        logger.info("[{}] Profile initialized with ${} capital, {} symbols",
            profile.name(), String.format("%.2f", capital), initialSymbols.size());
        logger.info("[{}] RiskManager peak equity set to: ${}", 
            profile.name(), String.format("%.2f", capital));
        logger.info("[{}] Synced {} positions from Alpaca",
            profile.name(), portfolio.getActivePositionCount());
        
        // Log AI component status
        if (sentimentAnalyzer != null) logger.info("[{}] 🧠 AI: Sentiment Analysis ENABLED", profile.name());
        if (signalPredictor != null) logger.info("[{}] 🤖 AI: ML Prediction ENABLED", profile.name());
        if (anomalyDetector != null) logger.info("[{}] 🔍 AI: Anomaly Detection ENABLED", profile.name());
        if (riskPredictor != null) logger.info("[{}] ⚡ AI: Risk Prediction ENABLED", profile.name());
        
        logger.info("═══════════════════════════════════════════════════════");
    }
    
    @Override
    public void run() {
        logger.info("[{}] Profile thread started", profile.name());
        
        // Position sync already done in constructor - no need to sync again
        logger.info("[{}] Starting trading with {} active positions", 
            profile.name(), portfolio.getActivePositionCount());
        
        while (running && !Thread.currentThread().isInterrupted()) {
            try {
                runTradingCycle();
                // Send heartbeat to safety system
                com.trading.bot.TradingBot.beat("Profile-" + profile.name());
                Thread.sleep(SLEEP_DURATION);
            } catch (InterruptedException e) {
                logger.info("[{}] Profile thread interrupted", profile.name());
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                logger.error("[{}] Error in trading cycle", profile.name(), e);
                try {
                    Thread.sleep(SLEEP_DURATION);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        
        logger.info("[{}] Profile thread stopped", profile.name());
    }
    
    private void runTradingCycle() throws Exception {
        String profilePrefix = "[" + profile.name() + "]";
        
        // Get current market regime (uses advanced detection if enabled)
        MarketRegime regime;
        List<String> targetSymbols;
        double currentVix;
        
        if (config.isRegimeDetectionEnabled()) {
            var regimeAnalysis = regimeDetector.getCurrentRegime();
            regime = regimeAnalysis.regime();
            currentVix = regimeAnalysis.vix();
            targetSymbols = symbolSelector.selectSymbols(regime);
            
            logger.debug("{} {}", profilePrefix, regimeAnalysis.getSummary());
        } else {
            // Fallback to simple VIX-based selection
            currentVix = volatilityFilter.getCurrentVIX();
            targetSymbols = symbolSelector.selectSymbols(currentVix);
            regime = currentVix >= 20.0 ? MarketRegime.STRONG_BEAR : MarketRegime.STRONG_BULL;
        }
        
        // Get account equity (cash + position values) for accurate P&L calculation
        var account = client.getAccount();
        var accountEquity = account.get("equity").asDouble();
        var buyingPower = account.get("buying_power").asDouble();
        
        // Use actual equity for P&L calculation, capped at configured capital for position sizing
        var equity = Math.min(accountEquity, capital);
        
        logger.debug("{} Account equity: ${}, Buying power: ${}, Using: ${}", 
            profilePrefix, 
            String.format("%.2f", accountEquity),
            String.format("%.2f", buyingPower), 
            String.format("%.2f", equity));
        
        // ========== PORTFOLIO-LEVEL STOP LOSS CHECK ==========
        if (portfolioRiskManager.shouldHaltTrading(accountEquity)) {
            logger.error("{} 🛑 PORTFOLIO STOP LOSS - Halting all trading", profilePrefix);
            
            // Assess and log portfolio risk
            var risk = portfolioRiskManager.assessRisk(client.getDelegate(), accountEquity);
            logger.error("{} {}", profilePrefix, risk.getSummary());
            
            TradingWebSocketHandler.broadcastActivity(
                String.format("[%s] PORTFOLIO STOP LOSS HIT - Trading halted", profile.name()),
                "ERROR"
            );
            
            return; // Skip trading cycle
        }
        
        // ========== CHECK ALL ALPACA POSITIONS FOR RISK EXITS ==========
        // This checks ALL positions in the account, including those not in current target symbols
        // (e.g., positions from previous market regimes that are no longer being tracked)
        checkAllPositionsForRiskExits(profilePrefix);
        
        // ========== CLEANUP EXCESS POSITIONS ==========
        // Auto-close worst positions if over limit
        cleanupExcessPositions(profilePrefix);
        
        // ========== END OF DAY EXIT (3:30 PM) ==========
        // Close all positions before market close to avoid overnight risk
        if (config.isEodExitEnabled()) {
            checkAndExecuteEodExit(profilePrefix);
        }
        
        // ========== CHECK ALL POSITIONS FOR PROFIT TARGETS ==========
        // CRITICAL: Check ALL positions for take-profit/stop-loss, not just current targets
        // This ensures positions from previous regimes are still monitored for exits
        checkAllPositionsForProfitTargets(profilePrefix);
        
        // Check market hours (crypto trades 24/7, so check if we have any non-crypto symbols)
        boolean isMarketOpen = marketHoursFilter.isMarketOpen();
        boolean bypassMarketHours = config.isMarketHoursBypassEnabled();
        boolean hasCryptoSymbols = targetSymbols.stream().anyMatch(s -> s.contains("/"));
        
        if (!isMarketOpen && !bypassMarketHours && !hasCryptoSymbols) {
            logger.debug("{} Market is closed (no crypto symbols to trade)", profilePrefix);
            return;
        }
        
        // If market closed but has crypto, we'll filter to crypto-only below
        boolean cryptoOnlyMode = !isMarketOpen && !bypassMarketHours && hasCryptoSymbols;
        
        // Check entry timing (avoid first 15 minutes)
        if (!isGoodEntryTime()) {
            logger.debug("{} Not in entry window - skipping new entries", profilePrefix);
            return;
        }
        
        // Check for max drawdown
        if (riskManager.shouldHaltTrading(equity)) {
            logger.error("{} HALTING TRADING: Max drawdown exceeded!", profilePrefix);
            return;
        }
        
        // Determine symbols to process (target + active positions not in target)
        Set<String> symbolsToProcess = new HashSet<>(targetSymbols);
        var activeSymbols = portfolio.getActiveStoredSymbols();
        
        for (String activeSymbol : activeSymbols) {
            if (!targetSymbols.contains(activeSymbol)) {
                symbolsToProcess.add(activeSymbol);
                logger.debug("{} Including {} for exit management", profilePrefix, activeSymbol);
            }
        }
        
        // If in crypto-only mode (market closed), filter to only crypto symbols
        if (cryptoOnlyMode) {
            symbolsToProcess = symbolsToProcess.stream()
                .filter(s -> s.contains("/"))  // Crypto pairs contain "/"
                .collect(java.util.stream.Collectors.toSet());
            if (!symbolsToProcess.isEmpty()) {
                logger.info("{} 🌙 Market closed - trading {} crypto symbols 24/7", 
                    profilePrefix, symbolsToProcess.size());
            }
        }
        
        logger.debug("{} Processing {} symbols", profilePrefix, symbolsToProcess.size());
        
        // Trade each symbol
        for (String symbol : symbolsToProcess) {
            try {
                tradeSymbol(symbol, targetSymbols, equity, buyingPower, regime, currentVix, profilePrefix);
            } catch (Exception e) {
                logger.error("{} Error processing {}", profilePrefix, symbol, e);
                
                // ========== AUTONOMOUS ERROR DETECTION & HEALING ==========
                if (errorDetector != null && configSelfHealer != null) {
                    try {
                        // Analyze error
                        var analysis = errorDetector.analyze(e, "Trading " + symbol);
                        
                        // Trigger self-healing if needed
                        if (analysis.shouldHeal()) {
                            logger.warn("🔧 Triggering self-heal for: {}", analysis.getSummary());
                            configSelfHealer.heal(analysis).thenAccept(result -> {
                                if (result.success()) {
                                    logger.info("✅ Self-heal completed: {}", result.message());
                                } else {
                                    logger.warn("⚠️ Self-heal failed: {}", result.message());
                                }
                            });
                        }
                    } catch (Exception healError) {
                        logger.error("❌ Error during self-healing", healError);
                    }
                }
            }
        }
        
        // Log portfolio status
        logger.info("{} Portfolio: {} active positions", 
            profilePrefix, portfolio.getActivePositionCount());
        
        // Evaluate and adjust parameters autonomously
        adaptiveManager.evaluateAndAdjust();
        
        // Analyze portfolio correlation (weekly)
        if (portfolio.getActivePositionCount() >= 2) {
            try {
                var symbols = portfolio.getSymbols();
                var correlation = correlationCalculator.analyzePortfolio(symbols);
                
                if (!correlation.highCorrelations().isEmpty()) {
                    logger.warn("{} ⚠️ High correlation detected in portfolio (diversification: {:.2f})",
                        profilePrefix, correlation.diversificationScore());
                }
                
                logger.debug("{} Portfolio diversification score: {:.2f}", 
                    profilePrefix, correlation.diversificationScore());
            } catch (Exception e) {
                logger.debug("{} Could not analyze correlation: {}", profilePrefix, e.getMessage());
            }
        }
        
        // Check portfolio rebalancing needs
        try {
            var positions = client.getPositions();
            Map<String, Double> currentPositions = new HashMap<>();
            for (var pos : positions) {
                currentPositions.put(pos.symbol(), pos.marketValue());
            }
            
            var rebalanceRec = portfolioRebalancer.checkRebalanceNeeded(currentPositions, equity);
            
            if (rebalanceRec.shouldRebalance()) {
                logger.info("{} 🔄 {}", profilePrefix, rebalanceRec.reason());
                // Note: Actual rebalancing execution would go here
                // For now, just log the recommendation
            }
        } catch (Exception e) {
            logger.debug("{} Could not check rebalancing: {}", profilePrefix, e.getMessage());
        }
        
        // Broadcast updates for dashboard widgets
        broadcastProfileUpdate(equity);
        // Broadcast account data (equity, buying power, profit targets from config)
        broadcastAccountData(equity);
        // Broadcast system status and market analysis for dashboard
        broadcastSystemStatus(isMarketOpen, currentVix);
        broadcastMarketAnalysis();  // Populate Asset Rankings widget
        
        // Broadcast Real-Time Focus Dashboard data
        broadcastBotStatusData(isMarketOpen, currentVix);
        broadcastProfitTargetsData();
    }
    
    private void broadcastMarketAnalysis() {
        try {
            var analysis = marketAnalyzer.analyze(portfolio.getSymbols());
            var assetScores = analysis.assetScores();
            
            // Broadcast individual symbol updates for Asset Rankings widget
            for (var entry : assetScores.entrySet()) {
                var score = entry.getValue();
                TradingWebSocketHandler.broadcastMarketUpdate(
                    score.symbol(),
                    score.price(),
                    score.change(),
                    score.changePercent(),
                    score.volume(),
                    score.trend(),
                    score.overallScore(),
                    score.momentumScore() // This is RSI-based
                );
            }
        } catch (Exception e) {
            logger.debug("[{}] Failed to broadcast market analysis", profile.name(), e);
        }
    }

    
    private void broadcastSystemStatus(boolean isMarketOpen, double currentVix) {
        try {
            var analysis = marketAnalyzer.analyze(portfolio.getSymbols());
            var stats = database.getTradeStatistics();
            boolean volatilityOk = currentVix < 30.0;
            
            // Fetch current buying power from Alpaca
            double buyingPower = 0.0;
            try {
                var account = client.getAccount();
                buyingPower = account.get("buying_power").asDouble();
            } catch (Exception e) {
                logger.debug("[{}] Failed to fetch buying power", profile.name(), e);
            }
            
            // Broadcast system status for System Status widget
            TradingWebSocketHandler.broadcastSystemStatus(
                isMarketOpen,
                volatilityOk,
                config.getTradingMode(),
                portfolio.getActivePositionCount(),
                stats.totalPnL(),
                analysis.trend().toString(),
                currentVix,
                analysis.recommendation(),
                analysis.marketStrength(),
                stats.totalTrades(),
                stats.winRate(),
                buyingPower
            );
        } catch (Exception e) {
            logger.debug("[{}] Failed to broadcast system status", profile.name(), e);
        }
    }
    
    private void tradeSymbol(String symbol, List<String> targetSymbols, 
                            double equity, double buyingPower, MarketRegime regime, double currentVix, String profilePrefix) throws Exception {
        
        // Broadcast processing status for dashboard
        int symbolIndex = new ArrayList<>(targetSymbols).indexOf(symbol) + 1;
        int totalSymbols = targetSymbols.size();
        TradingWebSocketHandler.broadcastProcessingStatus(
            symbol, symbolIndex, totalSymbols, "ANALYSIS", "Processing " + symbol
        );
        
        var currentPosition = portfolio.getPosition(symbol);
        
        // Get current price
        var bar = client.getLatestBar(symbol);
        var currentPrice = bar.get().close();
        
        // Get position quantity
        var qty = currentPosition.map(TradePosition::quantity).orElse(0.0);
        
        // ========== ADVANCED RISK MANAGEMENT: MAX LOSS & TIME-BASED EXITS ==========
        if (currentPosition.isPresent() && qty > 0) {
            var pos = currentPosition.get();
            
            // Check 1: Max Loss Exit
            if (config.isMaxLossExitEnabled() && 
                pos.isMaxLossExceeded(currentPrice, config.getMaxLossPercent())) {
                
                double lossPercent = pos.getLossPercent(currentPrice);
                logger.warn("{} ⚠️ MAX LOSS EXIT: {} down {:.2f}% (limit: -{:.1f}%)", 
                    profilePrefix, symbol, lossPercent, config.getMaxLossPercent());
                
                // Force sell with market order for immediate execution
                try {
                    client.placeOrder(symbol, qty, "sell", "market", "day", null);
                    logger.info("{} ✅ Max loss exit order placed for {}", profilePrefix, symbol);
                    
                    // Record trade close
                    database.closeTrade(symbol, Instant.now(), currentPrice, pos.calculatePnL(currentPrice));
                    
                    TradingWebSocketHandler.broadcastActivity(
                        String.format("[%s] MAX LOSS EXIT: %s (%.2f%% loss)", 
                            profile.name(), symbol, lossPercent),
                        "WARN"
                    );
                    
                    return;
                } catch (Exception e) {
                    logger.error("{} Failed to place max loss exit order for {}", profilePrefix, symbol, e);
                }
            }
            
            // Check 2: Time-Based Exit (if not profitable)
            if (pos.isHoldTimeLimitExceeded(config.getMaxHoldTimeHours())) {
                double pnl = pos.calculatePnL(currentPrice);
                
                // Only exit if position is losing or flat
                if (pnl <= 0) {
                    logger.warn("{} ⏰ TIME-BASED EXIT: {} held for {} hours (limit: {})", 
                        profilePrefix, symbol, pos.getHoursHeld(), config.getMaxHoldTimeHours());
                    
                    try {
                        client.placeOrder(symbol, qty, "sell", "market", "day", null);
                        logger.info("{} ✅ Time-based exit order placed for {}", profilePrefix, symbol);
                        
                        // Record trade close
                        database.closeTrade(symbol, Instant.now(), currentPrice, pnl);
                        
                        TradingWebSocketHandler.broadcastActivity(
                            String.format("[%s] TIME-BASED EXIT: %s (held %d hours)", 
                                profile.name(), symbol, pos.getHoursHeld()),
                            "INFO"
                        );
                        
                        return;
                    } catch (Exception e) {
                        logger.error("{} Failed to place time-based exit order for {}", profilePrefix, symbol, e);
                    }
                }
            }
        }
        
        // Evaluate strategy using regime
        var signal = strategyManager.evaluate(symbol, currentPrice, qty, regime);
        
        // Handle signal
        if (signal instanceof TradingSignal.Buy buy) {
            // Only buy if symbol is target for current regime
            boolean isTarget = targetSymbols.contains(symbol);
            if (!isTarget && qty == 0) {
                logger.debug("{} Skipping BUY for {} (not target for current VIX)", 
                    profilePrefix, symbol);
                return;
            }
            
            // Allow buying if:
            // 1. We don't have a position (qty == 0), OR
            // 2. We have a position but BUY signal is strong (can add to position)
            if (qty == 0 || (isTarget && buyingPower > 1.0)) {
                handleBuy(symbol, currentPrice, equity, buyingPower, currentVix, profilePrefix);
            } else if (qty > 0) {
                logger.debug("{} Skipping BUY for {} (already have position, low buying power: ${})", 
                    profilePrefix, symbol, String.format("%.2f", buyingPower));
            }
        } else if (signal instanceof TradingSignal.Sell sell) {
            if (qty > 0) {
                // Check if position has been held long enough (PDT compliance)
                int minHoldHours = config.getMinHoldTimeHours();
                TradePosition position = currentPosition.get();
                if (!position.canSell(minHoldHours)) {
                    long hoursHeld = position.getHoursHeld();
                    logger.info("{} {}: Cannot sell yet - held {} hours (min: {} hours)",
                        profilePrefix, symbol, hoursHeld, minHoldHours);
                    logger.info("{} {}: PDT Protection - waiting {} more hours",
                        profilePrefix, symbol, minHoldHours - hoursHeld);
                    return; // Keep position, don't sell yet
                }
                
                logger.info("{} {}: SELL signal - {} (held {} hours)",
                    profilePrefix, symbol, sell.reason(), position.getHoursHeld());
                
                // Check PDT protection
                // Assuming 'pdtProtection' is a member variable of the class
                if (!pdtProtection.canTrade(symbol, true, equity)) {
                    logger.warn("{} {}: SELL blocked by PDT protection", profilePrefix, symbol);
                    return;
                }
                handleSell(symbol, currentPrice, currentPosition.get(), profilePrefix);
            }
        } else {
            // HOLD - update trailing stop if position exists
            if (currentPosition.isPresent()) {
                updateTrailingStop(symbol, currentPrice, currentPosition.get(), profilePrefix);
            }
        }
    }
    
    private void handleBuy(String symbol, double currentPrice, double equity, 
                          double buyingPower, double currentVix, String profilePrefix) throws Exception {
        
        // ========== STOP LOSS COOLDOWN CHECK ==========
        // Prevent immediate re-entry after stop loss (this was causing repeated losses)
        Long cooldownExpiry = stopLossCooldowns.get(symbol);
        if (cooldownExpiry != null && System.currentTimeMillis() < cooldownExpiry) {
            long remainingMin = (cooldownExpiry - System.currentTimeMillis()) / 60000;
            logger.info("{} {} on STOP LOSS COOLDOWN - {} more minutes before re-entry allowed", 
                profilePrefix, symbol, remainingMin);
            TradingWebSocketHandler.broadcastActivity(
                String.format("[%s] ⏳ %s cooldown: %d min remaining after stop loss", 
                    profile.name(), symbol, remainingMin),
                "INFO"
            );
            return;
        }
        
        // ========== POSITION LIMIT CHECK ==========
        // Check position limit BEFORE calculating position size or running AI
        if (portfolio.getActivePositionCount() >= config.getMaxPositionsAtOnce()) {
            logger.warn("{} ⚠️ Max positions reached ({}/{}), skipping new entry for {}", 
                profilePrefix, 
                portfolio.getActivePositionCount(),
                config.getMaxPositionsAtOnce(),
                symbol);
            
            TradingWebSocketHandler.broadcastActivity(
                String.format("[%s] ⚠️ SKIPPED: %s (max %d positions reached)", 
                    profile.name(), symbol, config.getMaxPositionsAtOnce()),
                "WARN"
            );
            return;
        }
        
        // ========== AI COMPONENT 1: SENTIMENT ANALYSIS ==========
        if (sentimentAnalyzer != null) {
            try {
                boolean isBullish = profile.strategyType().equals("BULLISH");
                double sentimentScore = sentimentAnalyzer.getSentimentScore(symbol);
                
                // Record sentiment for dashboard
                com.trading.ai.AIMetricsTracker.getInstance().recordSentiment(symbol, sentimentScore);
                
                if (!sentimentAnalyzer.isSentimentPositive(symbol, isBullish)) {
                    logger.info("{} {}: ❌ AI FILTER - Negative sentiment, skipping trade", 
                        profilePrefix, symbol);
                    com.trading.ai.AIMetricsTracker.getInstance().incrementTradesFiltered();
                    return;
                }
                logger.debug("{} {}: ✅ Sentiment check passed", profilePrefix, symbol);
            } catch (Exception e) {
                logger.warn("{} {}: Sentiment analysis failed, continuing: {}", 
                    profilePrefix, symbol, e.getMessage());
            }
        }
        
        // ========== PHASE 3: MARKET BREADTH FILTER ==========
        if (!marketBreadthAnalyzer.isMarketHealthy()) {
            logger.info("{} {}: ❌ PHASE 3 FILTER - Market breadth too low, skipping trade", 
                profilePrefix, symbol);
            return;
        }
        
        // Broadcast market breadth to UI
        double breadth = marketBreadthAnalyzer.getCurrentBreadth();
        TradingWebSocketHandler.broadcastPhase3Event(
            "PHASE3_MARKET_BREADTH",
            String.format("{\"breadth\":%.2f}", breadth)
        );
        
        // ========== PHASE 3: ML ENTRY SCORING ==========
        if (config.isMLEntryScoringEnabled()) {
            try {
                // Get recent bars for ML analysis
                var bars = client.getBars(symbol, "15Min", 50);
                double mlScore = mlEntryScorer.scoreEntry(symbol, currentPrice, bars);
                
                if (!mlEntryScorer.meetsThreshold(mlScore)) {
                    logger.info("{} {}: ❌ PHASE 3 FILTER - ML score too low: {} (min: {}), skipping", 
                        profilePrefix, symbol, String.format("%.1f", mlScore), String.format("%.1f", config.getMLMinScore()));
                    return;
                }
                
                logger.info("{} {}: ✅ PHASE 3 - ML score: {} (passed)", 
                    profilePrefix, symbol, String.format("%.1f", mlScore));
                
                // Broadcast ML score to UI
                TradingWebSocketHandler.broadcastPhase3Event(
                    "PHASE3_ML_SCORE",
                    String.format("{\"symbol\":\"%s\",\"score\":%.1f}", symbol, mlScore)
                );
                    
            } catch (Exception e) {
                logger.warn("{} {}: ML scoring failed, continuing: {}", 
                    profilePrefix, symbol, e.getMessage());
            }
        }
        
        // ========== PHASE 3: VOLUME PROFILE CHECK ==========
        if (config.isVolumeProfileEnabled()) {
            try {
                var bars = client.getBars(symbol, "15Min", 50);
                if (!volumeProfileAnalyzer.isGoodEntryPrice(symbol, currentPrice, bars)) {
                    logger.info("{} {}: ⚠️ PHASE 3 - Price not near volume support, proceeding with caution", 
                        profilePrefix, symbol);
                }
            } catch (Exception e) {
                logger.debug("{} {}: Volume profile check failed: {}", 
                    profilePrefix, symbol, e.getMessage());
            }
        }
        
        // ========== AI COMPONENT 2: ML PREDICTION ==========
        if (signalPredictor != null) {
            try {
                var now = LocalDateTime.now();
                var setup = new com.trading.ai.SignalPredictor.TradingSetup(
                    currentVix,
                    now.getHour(),
                    now.getDayOfWeek(),
                    1.0, // volume ratio (would need real data)
                    0.65, // recent win rate (would use actual from analytics)
                    80 // pattern confidence
                );
                
                double winProb = signalPredictor.predictWinProbability(setup);
                
                // Record ML prediction for dashboard
                com.trading.ai.AIMetricsTracker.getInstance().recordMLPrediction(winProb * 100);
                
                // Use config threshold instead of hardcoded 0.60
                double minWinRate = config.getPositionSizingDefaultWinRate();
                if (winProb < minWinRate) {
                    logger.info("{} {}: ❌ AI FILTER - Low win probability: {}%, skipping", 
                        profilePrefix, symbol, String.format("%.1f", winProb * 100));
                    com.trading.ai.AIMetricsTracker.getInstance().incrementTradesFiltered();
                    return;
                }
                logger.debug("{} {}: ✅ ML prediction: {}% win probability", 
                    profilePrefix, symbol, String.format("%.1f", winProb * 100));
            } catch (Exception e) {
                logger.warn("{} {}: ML prediction failed, continuing: {}", 
                    profilePrefix, symbol, e.getMessage());
            }
        }
        
        // Calculate position size using ACTUAL BUYING POWER (not configured capital)
        // This prevents "insufficient buying power" errors
        double availableCapital = Math.min(buyingPower * 0.95, equity); // Use 95% of buying power for safety
        // ========== POSITION SIZING ==========
        double positionSize = riskManager.calculatePositionSize(
            availableCapital, 
            currentPrice, 
            profile.stopLossPercent() / 100.0
        );
        
        // ========== PHASE 3: ADAPTIVE POSITION SIZING ==========
        if (config.isAdaptiveSizingEnabled()) {
            try {
                // Get ML score for sizing
                var bars = client.getBars(symbol, "15Min", 50);
                double mlScore = mlEntryScorer.scoreEntry(symbol, currentPrice, bars);
                
                // Calculate adaptive size based on ML confidence and VIX
                double adaptiveSize = adaptivePositionSizer.calculateSize(equity, mlScore, currentVix);
                
                // Check correlation with existing positions
                double maxCorrelation = getMaxCorrelation(symbol);
                adaptiveSize = adaptivePositionSizer.adjustForCorrelation(adaptiveSize, maxCorrelation);
                
                // Use adaptive size instead of basic size
                positionSize = Math.min(positionSize, adaptiveSize);
                
                logger.info("{} {}: 📊 PHASE 3 - Adaptive sizing: ${:.2f} (ML:{:.1f} VIX:{:.1f} Corr:{:.2f})",
                    profilePrefix, symbol, positionSize, mlScore, currentVix, maxCorrelation);
                
                // Broadcast adaptive sizing to UI
                TradingWebSocketHandler.broadcastPhase3Event(
                    "PHASE3_ADAPTIVE_SIZE",
                    String.format("{\"symbol\":\"%s\",\"size\":%.2f,\"mlScore\":%.1f,\"vix\":%.1f}", 
                        symbol, positionSize, mlScore, currentVix)
                );
                    
            } catch (Exception e) {
                logger.warn("{} {}: Adaptive sizing failed, using basic size: {}", 
                    profilePrefix, symbol, e.getMessage());
            }
        }
        
        logger.info("{} {}: 💰 Position sizing: Available=${}, Calculated={} shares", 
            profilePrefix, symbol, 
            String.format("%.2f", availableCapital),
            String.format("%.3f", positionSize));
        
        // ========== AI COMPONENT 3: ANOMALY DETECTION ==========
        if (anomalyDetector != null) {
            try {
                // Check for anomalies
                anomalyDetector.isAnomaly("vix", currentVix);
                
                var action = anomalyDetector.getRecommendedAction();
                int severity = anomalyDetector.getAnomalySeverity();
                
                // Record anomaly for dashboard
                com.trading.ai.AIMetricsTracker.getInstance().recordAnomaly(severity, action.toString());
                
                switch (action) {
                    case HALT_TRADING:
                        logger.warn("{} {}: ⚠️ ANOMALY - Halting trading (severity: {})", 
                            profilePrefix, symbol, severity);
                        com.trading.ai.AIMetricsTracker.getInstance().incrementTradesFiltered();
                        return;
                    case REDUCE_SIZE:
                        positionSize *= 0.5;
                        logger.info("{} {}: ⚠️ ANOMALY - Reducing position size 50% (severity: {})", 
                            profilePrefix, symbol, severity);
                        break;
                    case TIGHTEN_STOPS:
                        logger.info("{} {}: ⚠️ ANOMALY - Consider tightening stops (severity: {})", 
                            profilePrefix, symbol, severity);
                        break;
                    default:
                        // CONTINUE - no action needed
                        break;
                }
            } catch (Exception e) {
                logger.warn("{} {}: Anomaly detection failed, continuing: {}", 
                    profilePrefix, symbol, e.getMessage());
            }
        }
        
        // ========== AI COMPONENT 4: RISK PREDICTION ==========
        if (riskPredictor != null) {
            try {
                var now = LocalDateTime.now();
                var riskSetup = new com.trading.ai.RiskPredictor.TradingSetup(
                    currentVix,
                    30.0, // symbol volatility (would need real data)
                    now.getHour(),
                    now.getDayOfWeek(),
                    portfolio.getActivePositionCount(),
                    10, // max positions
                    0, // recent losses (would use actual from analytics)
                    0.0, // sentiment score (would use actual if available)
                    profile.strategyType().equals("BULLISH")
                );
                
                int riskScore = riskPredictor.calculateRiskScore(riskSetup);
                
                // Record risk for dashboard
                com.trading.ai.AIMetricsTracker.getInstance().recordRisk(riskScore);
                
                if (riskPredictor.isTooRisky(riskSetup)) {
                    logger.info("{} {}: ❌ AI FILTER - Risk too high: {}/100, skipping", 
                        profilePrefix, symbol, riskScore);
                    com.trading.ai.AIMetricsTracker.getInstance().incrementTradesFiltered();
                    return;
                }
                
                // Adjust position size based on risk
                double originalSize = positionSize;
                positionSize = riskPredictor.getRecommendedSize(positionSize, riskScore);
                
                if (positionSize < originalSize) {
                    logger.info("{} {}: ⚡ Risk-adjusted position: {} → {} shares (risk: {}/100)", 
                        profilePrefix, symbol, 
                        String.format("%.2f", originalSize),
                        String.format("%.2f", positionSize),
                        riskScore);
                }
            } catch (Exception e) {
                logger.warn("{} {}: Risk prediction failed, continuing: {}", 
                    profilePrefix, symbol, e.getMessage());
            }
        }
        
        // Adjust for VIX (traditional logic)
        if (currentVix > 25) {
            positionSize *= 0.7; // Reduce size in high volatility
        }
        
        logger.info("{} {}: 🎯 Final position sizing: {} shares (VIX adjusted)", 
            profilePrefix, symbol, positionSize);
        
        // Create position with profile-specific risk parameters
        var stopLoss = currentPrice * (1.0 - profile.stopLossPercent() / 100.0);
        var takeProfit = currentPrice * (1.0 + profile.takeProfitPercent() / 100.0);
        
        var newPosition = new TradePosition(
            symbol,
            currentPrice,
            positionSize,
            stopLoss,
            takeProfit,
            java.time.Instant.now()
        );
        
        logger.info("{} {}: Position tracked: Entry=${}, StopLoss=${}, TakeProfit={}",
            profilePrefix, symbol, currentPrice, stopLoss, takeProfit);
        
        // Place order (skip if in test mode)
        if (testSimulator == null) {
            // Calculate order value
            double orderValue = positionSize * currentPrice;
            
            // Check minimum order amount ($1.00)
            if (orderValue < 1.00) {
                logger.warn("{} {}: ⚠️ Order value ${} is below minimum $1.00 - SKIPPING", 
                    profilePrefix, symbol, String.format("%.2f", orderValue));
                
                // Broadcast to UI with capital increase recommendation
                TradingWebSocketHandler.broadcastActivity(
                    String.format("[%s] ⚠️ SKIPPED: %s order ($%.2f < $1.00 minimum) - " +
                        "💡 Recommendation: Increase capital for better position sizing", 
                        profile.name(), symbol, orderValue),
                    "WARN"
                );
                
                // Don't track position since order wasn't placed
                return;
            }
            
            // Broadcast order attempt to UI
            TradingWebSocketHandler.broadcastActivity(
                String.format("[%s] 🔄 Attempting to BUY %s: %.3f shares @ $%.2f (Cost: $%.2f)", 
                    profile.name(), symbol, positionSize, currentPrice, orderValue),
                "INFO"
            );
            
            try {
                client.placeBracketOrder(symbol, positionSize, "buy", 
                    takeProfit, stopLoss, null, null);
                logger.info("{} {}: ✅ Bracket order placed successfully", profilePrefix, symbol);
                
                // Broadcast success to UI
                TradingWebSocketHandler.broadcastActivity(
                    String.format("[%s] ✅ BUY ORDER FILLED: %s %.3f shares @ $%.2f", 
                        profile.name(), symbol, positionSize, currentPrice),
                    "SUCCESS"
                );
            } catch (Exception e) {
                logger.warn("{} {}: Bracket order failed, placing market order: {}", 
                    profilePrefix, symbol, e.getMessage());
                
                try {
                    client.placeOrder(symbol, positionSize, "buy", "market", "day", null);
                    broadcastOrderData(symbol, positionSize, "buy", "market", "filled", currentPrice);
                    
                    // Broadcast success to UI
                    TradingWebSocketHandler.broadcastActivity(
                        String.format("[%s] ✅ BUY ORDER FILLED (Market): %s %.3f shares @ $%.2f", 
                            profile.name(), symbol, positionSize, currentPrice),
                        "SUCCESS"
                    );
                } catch (Exception marketOrderError) {
                    // SELF-HEALING: Check if it's insufficient buying power
                    if (marketOrderError.getMessage().contains("insufficient buying power")) {
                        logger.error("{} {}: ❌ Order FAILED - Insufficient buying power", 
                            profilePrefix, symbol);
                        
                        // Broadcast failure to UI with details
                        TradingWebSocketHandler.broadcastActivity(
                            String.format("[%s] ❌ BUY ORDER FAILED: %s - Insufficient buying power (Tried: $%.2f, Available: $%.2f)", 
                                profile.name(), symbol, positionSize * currentPrice, buyingPower),
                            "ERROR"
                        );
                        
                        // Don't update portfolio or record trade - order failed
                        return;
                    } else {
                        // Other error - broadcast and rethrow
                        TradingWebSocketHandler.broadcastActivity(
                            String.format("[%s] ❌ BUY ORDER FAILED: %s - %s", 
                                profile.name(), symbol, marketOrderError.getMessage()),
                            "ERROR"
                        );
                        throw marketOrderError;
                    }
                }
            }
        }
        
        // Update portfolio
        portfolio.setPosition(symbol, Optional.of(newPosition));
        
        // Record trade
        database.recordTrade(
            symbol,
            profile.strategyType(),
            profile.name(),  // Add profile name for tracking
            newPosition.entryTime(),
            currentPrice,
            positionSize,
            stopLoss,
            takeProfit
        );
        
        // Broadcast trade event
        TradingWebSocketHandler.broadcastTradeEvent(
            symbol, "BUY", currentPrice, positionSize, 
            profile.name() + " Profile"
        );
    }
    
    private void handleSell(String symbol, double currentPrice, TradePosition position,
                           String profilePrefix) throws Exception {
        
        double pnl = position.calculatePnL(currentPrice);
        
        logger.info("{} {}: SELLING - Entry=${}, Exit=${}, P&L=${}",
            profilePrefix, symbol, position.entryPrice(), currentPrice, 
            String.format("%.2f", pnl));
        
        // Place sell order (skip if in test mode)
        if (testSimulator == null) {
            client.placeOrder(symbol, position.quantity(), "sell", "market", "day", null);
        }
        
        // Update portfolio
        portfolio.setPosition(symbol, Optional.empty());
        
        // Close trade in database
        database.closeTrade(symbol, java.time.Instant.now(), currentPrice, pnl);
        
        // Broadcast trade event
        TradingWebSocketHandler.broadcastTradeEvent(
            symbol, "SELL", currentPrice, position.quantity(),
            profile.name() + " Profile (P&L: $" + String.format("%.2f", pnl) + ")"
        );
    }
    
    private void updateTrailingStop(String symbol, double currentPrice, TradePosition position,
                                    String profilePrefix) {
        
        double trailingStopPercent = profile.trailingStopPercent() / 100.0;
        
        // Use the built-in updateTrailingStop method which handles validation correctly
        var updatedPosition = position.updateTrailingStop(currentPrice, trailingStopPercent);
        
        // Only update if stop-loss actually changed
        if (updatedPosition.stopLoss() > position.stopLoss()) {
            portfolio.setPosition(symbol, Optional.of(updatedPosition));
            
            logger.info("{} {}: Trailing stop updated: ${} -> ${}",
                profilePrefix, symbol, 
                String.format("%.2f", position.stopLoss()),
                String.format("%.2f", updatedPosition.stopLoss()));
        }
    }
    
    /**
     * Check ALL Alpaca positions for max loss and time-based exits.
     * This includes positions not in current target symbols (e.g., from previous market regimes).
     * Only the MAIN profile should execute exits to avoid duplicate orders.
     */
    private void checkAllPositionsForRiskExits(String profilePrefix) {
        // Only MAIN profile should check and exit all positions
        // This prevents duplicate exit orders from multiple profiles
        if (!profile.name().equals("MAIN")) {
            return;
        }
        
        if (!config.isMaxLossExitEnabled()) {
            return; // Feature disabled
        }
        
        try {
            var allPositions = client.getPositions();
            
            // Get current portfolio positions for correlation analysis
            Map<String, Double> portfolioPositions = new HashMap<>();
            for (var pos : allPositions) {
                portfolioPositions.put(pos.symbol(), pos.marketValue());
            }
            
            for (var alpacaPos : allPositions) {
                String symbol = alpacaPos.symbol();
                double currentPrice = alpacaPos.marketValue() / alpacaPos.quantity();
                double entryPrice = alpacaPos.avgEntryPrice();
                double qty = alpacaPos.quantity();
                
                // Get tracked position if exists
                var trackedPos = portfolio.getPosition(symbol);
                
                // Use enhanced exit strategy if position is tracked
                if (trackedPos.isPresent()) {
                    TradePosition position = trackedPos.get();
                    
                    // Calculate current volatility (simplified - using price movement)
                    double volatility = Math.abs(currentPrice - entryPrice) / entryPrice;
                    
                    // Evaluate exit decision using enhanced strategy
                    var exitDecision = exitStrategyManager.evaluateExit(
                        position, currentPrice, volatility, portfolioPositions
                    );
                    
                    if (exitDecision.type() != com.trading.exits.ExitStrategyManager.ExitType.NONE) {
                        double qtyToExit = position.quantity() * exitDecision.quantity();
                        
                        logger.info("{} 🎯 ENHANCED EXIT: {} - {}", 
                            profilePrefix, symbol, exitDecision.reason());
                        
                        try {
                            client.placeOrder(symbol, qtyToExit, "sell", "market", "day", null);
                            
                            if (exitDecision.isPartial()) {
                                logger.info("{} ✅ Partial exit executed: {} ({:.1f}% of position)", 
                                    profilePrefix, symbol, exitDecision.quantity() * 100);
                            } else {
                                logger.info("{} ✅ Full exit executed: {}", profilePrefix, symbol);
                            }
                            
                            TradingWebSocketHandler.broadcastActivity(
                                String.format("[%s] %s EXIT: %s - %s", 
                                    profile.name(),
                                    exitDecision.isPartial() ? "PARTIAL" : "FULL",
                                    symbol, 
                                    exitDecision.reason()),
                                exitDecision.isPartial() ? "INFO" : "WARN"
                            );
                            
                            // ========== PHASE 3: TRAILING PROFIT TARGETS ==========
                            if (config.isTrailingTargetsEnabled()) {
                                double newStop = trailingTargetManager.updateTrailingStop(position, currentPrice);
                                if (newStop > 0) {
                                    logger.info("{} 🎯 PHASE 3 - Trailing stop updated: {} @ ${:.2f}", 
                                        profilePrefix, symbol, newStop);
                                    
                                    // Broadcast trailing stop update
                                    TradingWebSocketHandler.broadcastPhase3Event(
                                        "PHASE3_TRAILING_STOP",
                                        String.format("{\"symbol\":\"%s\",\"stop\":%.2f}", symbol, newStop)
                                    );
                                }
                                
                                double partialExit = trailingTargetManager.checkPartialExit(symbol, 
                                    ((currentPrice - entryPrice) / entryPrice) * 100);
                                if (partialExit > 0) {
                                    double partialQty = qty * (partialExit / 100.0);
                                    client.placeOrder(symbol, partialQty, "sell", "market", "day", null);
                                    logger.info("{} 💰 PHASE 3 - Trailing partial exit: {} ({:.0f}%)", 
                                        profilePrefix, symbol, partialExit);
                                }
                            }
                            
                            // ========== PHASE 3: TIME-DECAY EXITS ==========
                            if (config.isTimeDecayExits() && timeDecayExitManager.shouldExit(position, currentPrice)) {
                                String reason = timeDecayExitManager.getExitReason(position, currentPrice);
                                logger.info("{} ⏰ PHASE 3 - Time-decay exit: {} - {}", 
                                    profilePrefix, symbol, reason);
                                client.placeOrder(symbol, qty, "sell", "market", "day", null);
                                trailingTargetManager.removePosition(symbol);
                                continue;
                            }
                            
                            // ========== PHASE 3: MOMENTUM ACCELERATION EXITS ==========
                            if (config.isMomentumAccelerationExits()) {
                                try {
                                    var bars = client.getBars(symbol, "5Min", 5);
                                    double exitPercent = momentumDetector.checkAcceleration(symbol, bars);
                                    if (exitPercent > 0) {
                                        double exitQty = qty * (exitPercent / 100.0);
                                        client.placeOrder(symbol, exitQty, "sell", "market", "day", null);
                                        logger.info("{} 🚀 PHASE 3 - Momentum spike exit: {} ({:.0f}%)", 
                                            profilePrefix, symbol, exitPercent);
                                        
                                        // Broadcast momentum spike
                                        TradingWebSocketHandler.broadcastPhase3Event(
                                            "PHASE3_MOMENTUM_SPIKE",
                                            String.format("{\"symbol\":\"%s\",\"percent\":%.0f}", symbol, exitPercent)
                                        );
                                    }
                                } catch (Exception e) {
                                    logger.debug("{} Momentum check failed: {}", profilePrefix, e.getMessage());
                                }
                            }
                            
                            // ========== PHASE 3: POSITION HEALTH SCORING ==========
                            if (config.isPositionHealthScoring()) {
                                try {
                                    var bars = client.getBars(symbol, "15Min", 10);
                                    double momentum = momentumDetector.calculateMomentum(bars);
                                    double healthScore = healthScorer.scorePosition(position, currentPrice, momentum);
                                    
                                    if (healthScorer.shouldCloseUnhealthy(healthScore)) {
                                        logger.warn("{} ⚠️ PHASE 3 - Unhealthy position: {} (score: {:.0f}), closing", 
                                            profilePrefix, symbol, healthScore);
                                        client.placeOrder(symbol, qty, "sell", "market", "day", null);
                                        trailingTargetManager.removePosition(symbol);
                                        continue;
                                    }
                                } catch (Exception e) {
                                    logger.debug("{} Health scoring failed: {}", profilePrefix, e.getMessage());
                                }
                            }
                            
                            continue;
                        } catch (Exception e) {
                            logger.error("{} Failed to place enhanced exit order for {}", 
                                profilePrefix, symbol, e);
                        }
                    }
                    continue; // Position handled by enhanced strategy
                }
                
                // Fallback: Handle untracked positions with simple max loss logic
                double lossPercent = ((currentPrice - entryPrice) / entryPrice) * 100;
                
                if (lossPercent <= -config.getMaxLossPercent()) {
                    logger.warn("{} ⚠️ MAX LOSS EXIT (untracked): {} down {:.2f}% (limit: -{:.1f}%)", 
                        profilePrefix, symbol, Math.abs(lossPercent), config.getMaxLossPercent());
                    
                    try {
                        client.placeOrder(symbol, qty, "sell", "market", "day", null);
                        logger.info("{} ✅ Max loss exit order placed for untracked position {}", 
                            profilePrefix, symbol);
                        
                        TradingWebSocketHandler.broadcastActivity(
                            String.format("[%s] MAX LOSS EXIT (untracked): %s (%.2f%% loss)", 
                                profile.name(), symbol, Math.abs(lossPercent)),
                            "WARN"
                        );
                    } catch (Exception e) {
                        logger.error("{} Failed to place max loss exit order for {}", 
                            profilePrefix, symbol, e);
                    }
                }
            }
        } catch (Exception e) {
            logger.error("{} Error checking all positions for risk exits", profilePrefix, e);
        }
    }
    
    private void broadcastProfileUpdate(double equity) {
        var totalPnL = equity - capital;
        var pnlPercent = (totalPnL / capital) * 100;
        
        // Note: This broadcasts to all clients, but includes profile name in message
        // Dashboard can filter/aggregate by profile
        TradingWebSocketHandler.broadcastActivity(
            String.format("[%s] Portfolio: $%.2f | P&L: $%.2f (%.2f%%) | Positions: %d",
                profile.name(), equity, totalPnL, pnlPercent, 
                portfolio.getActivePositionCount()),
            "INFO"
        );
    }

    /**
     * Broadcast order data, including new orders, fills, and cancellations.
     */
    private void broadcastOrderData(String symbol, double quantity, String side, String type, String status, Double price) {
        TradingWebSocketHandler.broadcastOrderUpdate(
            profile.name(), symbol, quantity, side, type, status, price
        );
    }
    
    private void broadcastAccountData(double profileEquity) {
        try {
            // Fetch TOTAL account data from Alpaca
            double totalEquity = profileEquity;
            double lastEquity = profileEquity; // Previous day's equity
            double buyingPower = 0.0;
            double cash = 0.0;
            
            try {
                var account = client.getAccount();
                totalEquity = account.get("equity").asDouble();
                lastEquity = account.has("last_equity") ? account.get("last_equity").asDouble() : totalEquity;
                buyingPower = account.get("buying_power").asDouble();
                cash = account.get("cash").asDouble();
            } catch (Exception e) {
                logger.debug("[{}] Failed to fetch account data, using profile equity", profile.name(), e);
            }
            
            // Calculate capital allocation
            double capitalReserve = totalEquity * config.getSmartCapitalReservePercent();
            double deployableCapital = totalEquity - capitalReserve;
            
            // Get profit targets from config
            double mainTakeProfit = config.getMainTakeProfitPercent();
            double expTakeProfit = config.getExperimentalTakeProfitPercent();
            double stopLoss = profile.stopLossPercent();
            
            TradingWebSocketHandler.broadcastAccountData(
                totalEquity,
                lastEquity,
                buyingPower,
                cash,
                capitalReserve,
                deployableCapital,
                mainTakeProfit,
                expTakeProfit,
                stopLoss
            );
        } catch (Exception e) {
            logger.debug("[{}] Failed to broadcast account data", profile.name(), e);
        }
    }
    
    public void stop() {
        running = false;
        logger.info("[{}] Stop requested", profile.name());
    }
    
    public TradingProfile getProfile() {
        return profile;
    }
    
    public PortfolioManager getPortfolio() {
        return portfolio;
    }
    
    public int getActivePositionCount() {
        return portfolio.getActivePositionCount();
    }
    
    /**
     * Check and apply breakeven stop: move stop loss to entry price when position reaches +0.3% profit.
     * This ensures we never lose money after going green.
     */
    private void checkBreakevenStop(String profilePrefix, String symbol, double entryPrice, double pnlPercent, double qty) {
        if (!config.isBreakevenStopEnabled()) {
            return;
        }
        
        double triggerPercent = config.getBreakevenTriggerPercent();
        
        if (pnlPercent >= triggerPercent) {
            logger.info("{} Breakeven stop triggered for {} at +{}% (trigger: +{}%)",
                profilePrefix, symbol, String.format("%.2f", pnlPercent), triggerPercent);
            
            try {
                // Cancel any existing stop loss orders
                var openOrders = client.getOpenOrders(symbol);
                for (var order : openOrders) {
                    String orderType = order.get("type").asText();
                    if ("stop".equals(orderType) || "stop_limit".equals(orderType)) {
                        String orderId = order.get("id").asText();
                        client.cancelOrder(orderId);
                        logger.info("{} Canceled existing stop order {} for {}",
                            profilePrefix, orderId, symbol);
                    }
                }
                
                // Place new stop at breakeven (entry price)
                client.placeOrder(symbol, qty, "sell", "stop", "day", entryPrice);
                
                logger.info("{} ✅ Breakeven stop placed for {} at ${} (entry price)",
                    profilePrefix, symbol, String.format("%.2f", entryPrice));
                
                TradingWebSocketHandler.broadcastActivity(
                    String.format("[%s] 🛡️ BREAKEVEN STOP: %s protected at entry $%.2f",
                        profile.name(), symbol, entryPrice),
                    "INFO"
                );
            } catch (Exception e) {
                logger.error("{} Failed to set breakeven stop for {}: {}",
                    profilePrefix, symbol, e.getMessage());
            }
        }
    }
    
    /**
     * Check if current time is good for entering new positions.
     * Avoids first 15 minutes (9:30-9:45 AM) and optionally last 30 minutes.
     */
    private boolean isGoodEntryTime() {
        if (!config.isAvoidFirst15Minutes()) {
            return true; // Feature disabled, always allow
        }
        
        var now = java.time.ZonedDateTime.now(java.time.ZoneId.of("America/New_York"));
        var currentTime = now.toLocalTime();
        
        // Avoid first 15 minutes (9:30-9:45 AM)
        var marketOpen = java.time.LocalTime.of(9, 30);
        var safeEntryTime = java.time.LocalTime.of(9, 45);
        
        if (currentTime.isAfter(marketOpen) && currentTime.isBefore(safeEntryTime)) {
            return false; // Too early
        }
        
        // Optionally avoid last 30 minutes
        if (config.isAvoidLast30Minutes()) {
            var marketClose = java.time.LocalTime.of(16, 0);
            var stopEntryTime = java.time.LocalTime.of(15, 30);
            
            if (currentTime.isAfter(stopEntryTime) && currentTime.isBefore(marketClose)) {
                return false; // Too late
            }
        }
        
        return true; // Good time to enter
    }
    
    /**
     * Update daily P&L tracking and reset at start of new day.
     */
    private void updateDailyPnL(String profilePrefix, double pnl) {
        var today = java.time.LocalDate.now();
        
        if (!today.equals(lastResetDate)) {
            todayPnL = 0.0;
            lastResetDate = today;
            logger.info("{} Daily P&L reset for new trading day", profilePrefix);
        }
        
        todayPnL += pnl;
        logger.debug("{} Daily P&L updated: ${}", profilePrefix, String.format("%.2f", todayPnL));
    }
    
    /**
     * Check if we should reduce risk after hitting daily profit target.
     */
    private boolean shouldReduceRiskAfterTarget(String profilePrefix) {
        if (!config.isDailyProfitTargetEnabled()) {
            return false;
        }
        
        if (todayPnL >= config.getDailyProfitTarget()) {
            if (config.isReduceRiskAfterTarget()) {
                logger.info("{} Daily profit target hit (${}) - reducing risk",
                    profilePrefix, String.format("%.2f", todayPnL));
                return true;
            }
        }
        
        return false;
    }
    
    /**
     * Clean up excess positions when over limit.
     * Closes the weakest (most losing) positions first.
     */
    private void cleanupExcessPositions(String profilePrefix) throws Exception {
        int maxPositions = config.getMaxPositionsAtOnce();
        int currentPositions = portfolio.getActivePositionCount();
        
        if (currentPositions <= maxPositions) {
            return; // No cleanup needed
        }
        
        logger.warn("{} 🧹 Cleanup: {} positions, max is {}", 
            profilePrefix, currentPositions, maxPositions);
        
        // Get all positions sorted by P&L (worst first)
        var positions = client.getPositions();
        var sortedPositions = positions.stream()
            .sorted((a, b) -> Double.compare(
                a.unrealizedPL(), 
                b.unrealizedPL()
            ))
            .toList();
        
        // Close worst positions until we're at limit
        int toClose = currentPositions - maxPositions;
        for (int i = 0; i < toClose && i < sortedPositions.size(); i++) {
            var pos = sortedPositions.get(i);
            String symbol = pos.symbol();
            double qty = pos.quantity();
            double pnl = pos.unrealizedPL();
            
            logger.info("{} 🧹 Closing weakest position: {} (P&L: ${})", 
                profilePrefix, symbol, String.format("%.2f", pnl));
            
            try {
                client.placeOrder(symbol, qty, "sell", "market", "day", null);
                
                TradingWebSocketHandler.broadcastActivity(
                    String.format("[%s] 🧹 CLEANUP: Closed %s (P&L: $%.2f) - reducing to %d positions", 
                        profile.name(), symbol, pnl, maxPositions),
                    "INFO"
                );
                
                // Record trade close
                double currentPrice = Math.abs(pos.marketValue() / qty);
                database.closeTrade(symbol, Instant.now(), currentPrice, pnl);
                
            } catch (Exception e) {
                logger.error("{} Failed to close {} during cleanup", profilePrefix, symbol, e);
            }
        }
    }
    
    /**
     * Get position size with daily target adjustment.
     * Reduces size by 50% after hitting daily profit target.
     */
    private double getPositionSizeWithDailyTarget(String profilePrefix, double baseSize) {
        if (shouldReduceRiskAfterTarget(profilePrefix)) {
            return baseSize * 0.5; // Reduce by 50%
        }
        return baseSize;
    }
    
    /**
     * Check ALL Alpaca positions for take-profit and stop-loss triggers.
     * CRITICAL: This ensures positions not in current target symbols are still monitored.
     * Only the MAIN profile should execute exits to avoid duplicate orders.
     */
    private void checkAllPositionsForProfitTargets(String profilePrefix) {
        // Only MAIN profile should check and exit all positions
        if (!profile.name().equals("MAIN")) {
            return;
        }
        
        try {
            var alpacaPositions = client.getPositions();
            
            for (var alpacaPos : alpacaPositions) {
                String symbol = alpacaPos.symbol();
                double qty = alpacaPos.quantity();
                double marketValue = alpacaPos.marketValue();
                double entryPrice = alpacaPos.avgEntryPrice();
                
                // Calculate current price from market value
                double currentPrice = Math.abs(marketValue / qty);
                
                // Calculate P&L percentage
            double pnlPercent = ((currentPrice - entryPrice) / entryPrice) * 100.0;
            
            // Check breakeven stop (move stop to entry at +0.3% profit)
            checkBreakevenStop(profilePrefix, symbol, entryPrice, pnlPercent, qty);
            
            // ========== PHASE 2 EXIT STRATEGIES ==========
            // Create temporary position for Phase 2 exit evaluation
            // Use RiskManager to get configured stop/target values
            var riskManager = new RiskManager(100000); // Use current equity if available
            var tempPosition = new TradePosition(
                symbol,
                entryPrice,
                qty,
                riskManager.calculateStopLoss(entryPrice),
                riskManager.calculateTakeProfit(entryPrice),
                Instant.now().minus(Duration.ofHours(6)) // Assume held 6 hours
            );
            
            // Check EOD Profit Lock (Feature #23)
            var eodDecision = phase2ExitStrategies.evaluateEODProfitLock(tempPosition, currentPrice);
            if (eodDecision.type() != com.trading.exits.ExitStrategyManager.ExitType.NONE) {
                logger.info("{} 🔒 EOD PROFIT LOCK: {} - {}", profilePrefix, symbol, eodDecision.reason());
                
                try {
                    double exitQty = eodDecision.isPartial() ? eodDecision.quantity() : qty;
                    client.placeOrder(symbol, exitQty, "sell", "market", "day", null);
                    
                    double pnlDollars = (currentPrice - entryPrice) * exitQty;
                    database.closeTrade(symbol, Instant.now(), currentPrice, pnlDollars);
                    
                    TradingWebSocketHandler.broadcastActivity(
                        String.format("[%s] 🔒 EOD PROFIT LOCK: %s sold @ $%.2f (+%.2f%%, $%.2f)", 
                            profile.name(), symbol, currentPrice, pnlPercent, pnlDollars),
                        "SUCCESS"
                    );
                    
                    logger.info("{} ✅ EOD profit lock exit executed for {}", profilePrefix, symbol);
                    continue; // Skip other checks for this position
                } catch (Exception e) {
                    logger.error("{} ❌ Failed to execute EOD profit lock for {}", profilePrefix, symbol, e);
                }
            }
            
            // Get profile-specific targets
            double takeProfitPercent = profile.takeProfitPercent();
                double stopLossPercent = profile.stopLossPercent();
                
                // Check for take-profit trigger
                if (pnlPercent >= takeProfitPercent) {
                    logger.info("{} {} TAKE PROFIT HIT: Entry=${}, Current=${}, P&L=+{}% (target: +{}%)",
                        profilePrefix, symbol, entryPrice, currentPrice, 
                        String.format("%.2f", pnlPercent), String.format("%.1f", takeProfitPercent));
                    
                    logger.info("{} Attempting to place SELL order for {} qty={}", profilePrefix, symbol, qty);
                    
                    try {
                        // CRITICAL: Cancel any existing orders for this symbol first
                        // to free up held shares
                        logger.info("{} Canceling any existing orders for {} to free up shares", profilePrefix, symbol);
                        try {
                            var openOrders = client.getOpenOrders(symbol);
                            for (var order : openOrders) {
                                String orderId = order.get("id").asText();
                                client.cancelOrder(orderId);
                                logger.info("{} Canceled order {} for {}", profilePrefix, orderId, symbol);
                            }
                        } catch (Exception cancelEx) {
                            logger.warn("{} No existing orders to cancel for {}: {}", profilePrefix, symbol, cancelEx.getMessage());
                        }
                        
                        logger.info("{} Calling client.placeOrder({}, {}, sell, market, day, null)", profilePrefix, symbol, qty);
                        client.placeOrder(symbol, qty, "sell", "market", "day", null);
                        broadcastOrderData(symbol, qty, "sell", "market", "filled", currentPrice);   
                        logger.info("{} ✅ Order API call completed for {}", profilePrefix, symbol);
                        
                        // Calculate actual P&L in dollars
                        double pnlDollars = (currentPrice - entryPrice) * qty;
                        
                        // Record trade close
                        database.closeTrade(symbol, Instant.now(), currentPrice, pnlDollars);
                        
                        TradingWebSocketHandler.broadcastActivity(
                            String.format("[%s] ✅ TAKE PROFIT: %s sold @ $%.2f (+%.2f%%, $%.2f profit)", 
                                profile.name(), symbol, currentPrice, pnlPercent, pnlDollars),
                            "SUCCESS"
                        );
                        
                        logger.info("{} ✅ Take profit exit order placed for {}", profilePrefix, symbol);
                    } catch (Exception e) {
                        logger.error("{} ❌ FAILED to place take profit exit for {} - Exception: {}", 
                            profilePrefix, symbol, e.getClass().getName(), e);
                        logger.error("{} Error message: {}", profilePrefix, e.getMessage());
                        // Stack trace logged at debug level to avoid log noise
                        logger.debug("{} Full stack trace:", profilePrefix, e);
                    }
                }
                // Check for stop-loss trigger
                else if (pnlPercent <= -stopLossPercent) {
                    logger.warn("{} {} STOP LOSS HIT: Entry=${}, Current=${}, P&L={}% (stop: -{}%)",
                        profilePrefix, symbol, entryPrice, currentPrice, 
                        String.format("%.2f", pnlPercent), String.format("%.1f", stopLossPercent));
                    
                    try {
                        client.placeOrder(symbol, qty, "sell", "market", "day", null);
                        broadcastOrderData(symbol, qty, "sell", "market", "filled", currentPrice);
                        
                        // Calculate actual P&L in dollars
                        double pnlDollars = (currentPrice - entryPrice) * qty;
                        
                        // Record trade close
                        database.closeTrade(symbol, Instant.now(), currentPrice, pnlDollars);
                        
                        TradingWebSocketHandler.broadcastActivity(
                            String.format("[%s] ⚠️ STOP LOSS: %s sold @ $%.2f (%.2f%%, $%.2f loss)", 
                                profile.name(), symbol, currentPrice, pnlPercent, pnlDollars),
                            "WARN"
                        );
                        
                        // ========== SET RE-ENTRY COOLDOWN ==========
                        // Prevent immediate re-buy after stop loss (was causing repeated losses)
                        long cooldownMs = config.getStopLossCooldownMs();
                        stopLossCooldowns.put(symbol, System.currentTimeMillis() + cooldownMs);
                        logger.warn("{} {} placed on {}-minute COOLDOWN after stop loss - no re-entry until {}", 
                            profilePrefix, symbol, cooldownMs / 60000,
                            java.time.Instant.ofEpochMilli(System.currentTimeMillis() + cooldownMs));
                        
                        logger.info("{} ✅ Stop loss exit order placed for {}", profilePrefix, symbol);
                    } catch (Exception e) {
                        logger.error("{} Failed to place stop loss exit for {}", profilePrefix, symbol, e);
                    }
                }
            }
        } catch (Exception e) {
            logger.error("{} Error checking positions for profit targets: {}", profilePrefix, e.getMessage(), e);
        }
    }
    
    /**
     * Check if it's time for end-of-day exit and close all positions.
     * Only MAIN profile executes this to avoid duplicate orders.
     */
    private void checkAndExecuteEodExit(String profilePrefix) {
        // Only MAIN profile should execute EOD exits
        if (!profile.name().equals("MAIN")) {
            return;
        }
        
        try {
            // Get current time in ET timezone
            var now = java.time.ZonedDateTime.now(java.time.ZoneId.of("America/New_York"));
            var currentTime = now.toLocalTime();
            
            // Parse EOD exit time from config (e.g., "15:30")
            var eodTimeStr = config.getEodExitTime();
            var eodTime = java.time.LocalTime.parse(eodTimeStr);
            
            // Check if we're within 1 minute of EOD exit time
            var timeDiff = java.time.Duration.between(currentTime, eodTime).abs();
            
            if (timeDiff.toMinutes() <= 1) {
                logger.warn("{} ⏰ END OF DAY EXIT TIME ({}) - Closing all positions", profilePrefix, eodTimeStr);
                
                // Get all open positions
                var positions = client.getPositions();
                
                if (positions.isEmpty()) {
                    logger.info("{} No positions to close for EOD", profilePrefix);
                    return;
                }
                
                logger.warn("{} 🔴 Closing {} position(s) for end of day", profilePrefix, positions.size());
                
                // Close each position
                for (var position : positions) {
                    String symbol = position.symbol();
                    double qty = Math.abs(position.quantity());
                    double marketValue = position.marketValue();
                    double entryPrice = position.avgEntryPrice();
                    
                    // Calculate current price from market value
                    double currentPrice = Math.abs(marketValue / qty);
                    double pnl = (currentPrice - entryPrice) * qty;
                    double pnlPercent = ((currentPrice - entryPrice) / entryPrice) * 100;
                    
                    logger.warn("{} 📊 EOD EXIT: {} - Qty: {}, Entry: ${}, Current: ${}, P&L: ${} ({:.2f}%)",
                        profilePrefix, symbol, qty, entryPrice, currentPrice, pnl, pnlPercent);
                    
                    try {
                        // Cancel any existing orders for this symbol first
                        var openOrders = client.getOpenOrders(symbol);
                        for (var order : openOrders) {
                            String orderId = order.get("id").asText();
                            logger.info("{} Canceling existing order {} for {} before EOD exit", 
                                profilePrefix, orderId, symbol);
                            client.cancelOrder(orderId);
                        }
                        
                        // Place market sell order
                        logger.warn("{} 🔴 EOD SELL: {} - {} shares @ market", profilePrefix, symbol, qty);
                        client.placeOrder(symbol, qty, "sell", "market", "day", null);
                        broadcastOrderData(symbol, qty, "sell", "market", "filled", currentPrice);
                        
                        // Broadcast to UI
                        TradingWebSocketHandler.broadcastActivity(
                            String.format("[%s] EOD EXIT: %s - Closed %.3f shares | P&L: $%.2f (%.2f%%)",
                                profile.name(), symbol, qty, pnl, pnlPercent),
                            pnl >= 0 ? "SUCCESS" : "WARNING"
                        );
                        
                        logger.warn("{} ✅ EOD EXIT completed for {}", profilePrefix, symbol);
                        
                    } catch (Exception e) {
                        logger.error("{} ❌ Failed to execute EOD exit for {}: {}", 
                            profilePrefix, symbol, e.getMessage(), e);
                        
                        TradingWebSocketHandler.broadcastActivity(
                            String.format("[%s] EOD EXIT FAILED: %s - %s", 
                                profile.name(), symbol, e.getMessage()),
                            "ERROR"
                        );
                    }
                }
                
                logger.warn("{} ✅ END OF DAY EXIT COMPLETE - All positions closed", profilePrefix);
            }
            
        } catch (Exception e) {
            logger.error("{} Error during EOD exit check: {}", profilePrefix, e.getMessage(), e);
        }
    }
    
    /**
     * Broadcast bot status for Real-Time Focus Dashboard.
     */
    private void broadcastBotStatusData(boolean isMarketOpen, double currentVix) {
        try {
            // Get current time in ET timezone
            var now = java.time.ZonedDateTime.now(java.time.ZoneId.of("America/New_York"));
            var currentTime = now.toLocalTime();
            
            // Determine market status with extended hours
            String marketStatus;
            if (isMarketOpen) {
                // Check if we're in regular hours (9:30 AM - 4:00 PM)
                var marketOpen = java.time.LocalTime.of(9, 30);
                var marketClose = java.time.LocalTime.of(16, 0);
                
                if (currentTime.isAfter(marketOpen) && currentTime.isBefore(marketClose)) {
                    marketStatus = "OPEN";
                } else {
                    marketStatus = "EXTENDED HOURS";
                }
            } else {
                marketStatus = "CLOSED";
            }
            
            String regime = regimeDetector.getCurrentRegime().regime().toString();
            int posCount = portfolio.getActivePositionCount();
            String nextAction = String.format("Monitoring %d positions for profit targets", posCount);
            String waitingFor = posCount > 0 ? "Positions to reach profit targets" : "Market opportunities";
            
            TradingWebSocketHandler.broadcastBotStatus(
                marketStatus,
                regime,
                currentVix,
                nextAction,
                waitingFor
            );
        } catch (Exception e) {
            logger.debug("[{}] Failed to broadcast bot status", profile.name(), e);
        }
    }
    
    /**
     * Broadcast profit targets monitoring data.
     * Only broadcast from MAIN profile to avoid duplicates.
     */
    private void broadcastProfitTargetsData() {
        // Only broadcast from Main profile to avoid duplicate broadcasts
        if (!profile.name().equals("MAIN")) {
            return;
        }
        
        try {
            var positions = client.getPositions();
            var targets = new java.util.ArrayList<TradingWebSocketHandler.ProfitTargetStatus>();
            
            logger.info("[{}] broadcastProfitTargetsData: Found {} positions", 
                profile.name(), positions.size());
            
            for (var pos : positions) {
                double entryPrice = pos.avgEntryPrice();
                double currentPrice = Math.abs(pos.marketValue() / pos.quantity());
                double pnlPercent = ((currentPrice - entryPrice) / entryPrice) * 100.0;
                
                // Use actual profit target from config (1.25% for Main)
                double targetPercent = profile.takeProfitPercent();
                logger.debug("Position {} using target from config: {}%", pos.symbol(), targetPercent);
                double distancePercent = targetPercent - pnlPercent;
                
                String eta = distancePercent < 0.5 ? "Soon" : "-";
                
                logger.debug("Adding target: {} P&L={}% Target={}%", 
                    pos.symbol(), String.format("%.2f", pnlPercent), String.format("%.2f", targetPercent));
                
                targets.add(new TradingWebSocketHandler.ProfitTargetStatus(
                    pos.symbol(),
                    pnlPercent,
                    targetPercent,
                    distancePercent,
                    eta
                ));
            }
            
            // Always broadcast, even if empty - UI needs to know backend is working
            logger.info("[{}] Broadcasting {} profit targets to UI", profile.name(), targets.size());
            TradingWebSocketHandler.broadcastProfitTargets(targets);
        } catch (Exception e) {
            logger.error("[{}] Failed to broadcast profit targets", profile.name(), e);
        }
    }
    
    /**
     * PHASE 3: Get maximum correlation with existing positions
     * TODO: Implement proper correlation calculation when API available
     */
    private double getMaxCorrelation(String newSymbol) {
        // Simplified for now - return low correlation
        // Full implementation requires historical price data API
        return 0.1;
    }
}
