package com.trading.portfolio;

import com.trading.ai.AnomalyDetector;
import com.trading.ai.RiskPredictor;
import com.trading.ai.SentimentAnalyzer;
import com.trading.ai.SignalPredictor;
import com.trading.api.ResilientBrokerClient;
import com.trading.api.PDTRejectedException;
import com.trading.analysis.AtrCalculator;
import com.trading.analysis.MarketAnalyzer;
import com.trading.config.Config;
import com.trading.earnings.EarningsCalendarService;
import com.trading.filters.MarketHoursFilter;
import com.trading.filters.VolatilityFilter;
import com.trading.persistence.TradeDatabase;
import com.trading.protection.PDTProtection;
import com.trading.risk.AdvancedPositionSizer;
import com.trading.risk.CircuitBreakerState;
import com.trading.risk.PostLossCooldownTracker;
import com.trading.risk.RiskManager;
import com.trading.risk.PortfolioRiskManager;
import com.trading.risk.TradePosition;
import com.trading.analysis.MarketRegimeDetector;
import com.trading.analysis.MarketRegimeDetector.MarketRegime;
import com.trading.strategy.StrategyManager;
import com.trading.strategy.SymbolSelector;
import com.trading.strategy.TradingProfile;
import com.trading.strategy.TradingSignal;
import com.trading.execution.SmartOrderTypeSelector;
import com.trading.execution.SmartOrderTypeSelector.OrderContext;
import com.trading.execution.SmartOrderTypeSelector.OrderTypeDecision;
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
    /** Cycle interval: read from profile-specific env var, default 30s for MAIN, 45s for others. */
    private final Duration sleepDuration;
    private static final Duration SLEEP_DURATION = Duration.ofSeconds(30); // fallback
    
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
    private final SmartOrderTypeSelector orderTypeSelector;
    private final com.trading.lending.StockLendingTracker lendingTracker;
    private final com.trading.options.OptionsStrategyManager optionsManager;
    
    // Shared resources (thread-safe)
    private final ResilientBrokerClient client;
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
    
    // STATIC: Shared across ALL ProfileManager instances to prevent cross-profile buy-back.
    // When MAIN sells SLV, EXPERIMENTAL must also see the cooldown and not re-buy.
    // Re-entry cooldown after ANY sell (stop loss, take profit, risk exit, etc.)
    // Key = symbol, Value = timestamp when cooldown expires
    private static java.util.concurrent.ConcurrentHashMap<String, Long> stopLossCooldowns = new java.util.concurrent.ConcurrentHashMap<>();

    // STATIC: Shared across profiles. Track symbols with pending exit orders to prevent duplicate sells.
    // When a sell order is placed, the symbol is added here.
    // Entries are removed when the position disappears from Alpaca (order filled).
    private static java.util.concurrent.ConcurrentHashMap<String, Long> pendingExitOrders = new java.util.concurrent.ConcurrentHashMap<>();

    // STATIC: Shared across profiles. Track consecutive stop-loss hits per symbol.
    // Key = symbol, Value = count of consecutive SL hits (reset on successful trade or manual clear)
    private static java.util.concurrent.ConcurrentHashMap<String, Integer> consecutiveStopLosses = new java.util.concurrent.ConcurrentHashMap<>();
    private static final int MAX_CONSECUTIVE_SL_BEFORE_EXTENDED_COOLDOWN = 2;

    // STATIC: Shared across profiles. Track last exit price per symbol.
    // After a loss exit, only allow re-entry if current price is at least 1% lower (better entry).
    // Prevents buying back at the same price you just sold at a loss.
    // Key = symbol, Value = exit price
    private static java.util.concurrent.ConcurrentHashMap<String, Double> lastExitPrices = new java.util.concurrent.ConcurrentHashMap<>();
    private static final double MIN_PRICE_IMPROVEMENT_PERCENT = 1.0;

    // STATIC: Urgent exit queue — symbols whose protective sell failed due to API error.
    // Retried every cycle (every 10s) until the sell succeeds or the position disappears.
    // Key = "broker:symbol" so multi-broker mode doesn't cross-clear entries
    // (e.g. Alpaca's failed SPY exit shouldn't be wiped by Tradier's reconcile).
    private static final java.util.concurrent.ConcurrentHashMap<String, UrgentExit> urgentExitQueue
        = new java.util.concurrent.ConcurrentHashMap<>();

    private record UrgentExit(String broker, String symbol, double quantity, String reason, long firstFailedAt) {}

    private static String urgentKey(String broker, String symbol) { return broker + ":" + symbol; }

    // STATIC: Track why buys were most recently blocked per symbol (gap-down, price gate, etc.)
    // Key = symbol, Value = reason string. Cleared when the buy is eventually allowed.
    private static final java.util.concurrent.ConcurrentHashMap<String, String> blockedBuys
        = new java.util.concurrent.ConcurrentHashMap<>();

    // STATIC: Per-symbol post-loss cooldown shared across profiles & brokers — Tier 1.1.
    // When MAIN closes TLT at a loss, EXPERIMENTAL must also see the cooldown so the bot
    // can't churn the same losing name on the second profile.
    private static volatile PostLossCooldownTracker postLossCooldown;

    // STATIC: Earnings calendar — Tier 2.5. Single instance for cache reuse across profiles.
    private static volatile EarningsCalendarService earningsCalendar;

    // STATIC: Per-broker session circuit breakers — Tier 3.10. Map keyed by broker name so
    // Alpaca and Tradier maintain independent loss streaks / drawdowns.
    private static final java.util.concurrent.ConcurrentHashMap<String, CircuitBreakerState> circuitBreakers
        = new java.util.concurrent.ConcurrentHashMap<>();

    public static java.util.Map<String, String> getUrgentExitQueue() {
        var result = new java.util.LinkedHashMap<String, String>();
        long now = System.currentTimeMillis();
        urgentExitQueue.forEach((key, exit) ->
            result.put(key, String.format("%s (queued %dm ago)", exit.reason(), (now - exit.firstFailedAt()) / 60000)));
        return java.util.Collections.unmodifiableMap(result);
    }


    // Current market state (updated each cycle, used by profit target checks)
    private volatile double latestVix = 15.0;
    private volatile MarketRegime latestRegime = MarketRegime.RANGE_BOUND;
    private volatile double latestEquity = 0.0;

    // Per-broker: track when we first detected a pending ENTRY order per symbol.
    // Used to cancel stale orders (e.g. sandbox orders that never fill).
    private final java.util.concurrent.ConcurrentHashMap<String, Long> pendingEntryTimestamps
        = new java.util.concurrent.ConcurrentHashMap<>();
    private static final long STALE_ENTRY_ORDER_MS = 30 * 60 * 1000L; // 30 minutes
    // Hard cap: at most 2 open DB entries per symbol per broker (original + 1 add-to-position).
    // Prevents runaway pyramid buying across restarts or repeated signals.
    private static final int MAX_OPEN_ENTRIES_PER_SYMBOL = 2;

    // PDT circuit breaker: skip sell attempts for the rest of the cycle after a 403 rejection
    private volatile long pdtBlockedUntil = 0;

    // Static PDT state — exposed to dashboard (account-level, shared across profiles)
    private static volatile long staticPdtBlockedUntil = 0;
    private static volatile int staticDayTradeCount = 0;

    // STATIC: Halt state snapshots — updated each cycle, exposed to dashboard
    private static volatile boolean portfolioStopLossHaltActive = false;
    private static volatile boolean maxDrawdownHaltActive = false;
    private static volatile double latestVixSnapshot = 0.0;
    private static volatile String latestRegimeSnapshot = "UNKNOWN";
    private static volatile String latestTargetSymbolsSnapshot = "";

    private volatile boolean running = true;
    private final String brokerName;

    public ProfileManager(
            TradingProfile profile,
            double capital,
            ResilientBrokerClient client,
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
            com.trading.autonomous.ConfigSelfHealer configSelfHealer,
            String brokerName) {
        
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
        this.brokerName = brokerName;

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

        // Override sync-time profile defaults with persisted DB stops/TPs/entry-times where available.
        // Without this, every restart silently relaxes stops on fractional positions to the profile
        // default (the META incident: real ~1.5% stop replaced with 2% default after deploy).
        try {
            for (var rec : database.getOpenTradeRecords(brokerName)) {
                var existing = portfolio.getPosition(rec.symbol());
                if (existing.isEmpty()) continue; // sync didn't pick it up — likely closed at broker
                var prev = existing.get();
                var restored = new TradePosition(
                    rec.symbol(), prev.entryPrice(), prev.quantity(),
                    rec.stopLoss(), rec.takeProfit(), rec.entryTime(),
                    prev.entryPrice(), rec.partialExitsExecuted()
                );
                portfolio.setPosition(rec.symbol(), Optional.of(restored));
                logger.info("[{}] Restored persisted stops for {}: SL=${} TP=${} partialMask={}",
                    profile.name(), rec.symbol(),
                    String.format("%.2f", rec.stopLoss()), String.format("%.2f", rec.takeProfit()),
                    rec.partialExitsExecuted());
            }
        } catch (Exception e) {
            logger.warn("[{}] Failed to restore persisted stops from DB: {}", profile.name(), e.getMessage());
        }
        
        // Create adaptive parameter manager for autonomous tuning
        this.adaptiveManager = new com.trading.autonomous.AdaptiveParameterManager(config, database);
        
        // Create advanced position sizer
        var positionSizer = new com.trading.risk.AdvancedPositionSizer(config, database);
        positionSizer.setAdaptiveManager(adaptiveManager);
        
        this.riskManager = new RiskManager(capital, positionSizer);
        // Portfolio stop loss baseline = full account equity at startup (not just this profile's share).
        // Using profile-split capital caused the check to compare full equity vs ~60% of it,
        // making the portfolio stop loss never fire correctly.
        double fullStartupCapital = com.trading.bot.TradingBot.getSessionStartCapital();
        // In multi-broker mode, getSessionStartCapital() returns 0 — use capital directly to avoid
        // inflated baseline (e.g. $1179/0.6 = $1966 ghost baseline that triggers false stop loss).
        double portfolioBaseline = fullStartupCapital > 0 ? fullStartupCapital : capital;
        this.portfolioRiskManager = new PortfolioRiskManager(config, portfolioBaseline);
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
        this.orderTypeSelector = new SmartOrderTypeSelector();
        this.lendingTracker = new com.trading.lending.StockLendingTracker(config);
        this.optionsManager = new com.trading.options.OptionsStrategyManager(config);

        // Initialize cross-profile singletons lazily (first profile to start wins; others reuse).
        if (postLossCooldown == null) {
            synchronized (ProfileManager.class) {
                if (postLossCooldown == null) {
                    postLossCooldown = new PostLossCooldownTracker(
                        config.getPostLossCooldownMs(),
                        config.getPostLossCooldownExtendedMs(),
                        2);
                }
            }
        }
        if (earningsCalendar == null) {
            synchronized (ProfileManager.class) {
                if (earningsCalendar == null) {
                    String key = System.getenv("ALPHA_VANTAGE_API_KEY");
                    earningsCalendar = new EarningsCalendarService(
                        key == null ? "" : key,
                        config.getEarningsCacheTtlMs());
                }
            }
        }
        circuitBreakers.computeIfAbsent(brokerName, b -> new CircuitBreakerState(
            config.getCircuitBreakerConsecutiveLosses(),
            config.getCircuitBreakerSessionDrawdownPercent() / 100.0));

        // Cycle interval: MAIN reads MAIN_CYCLE_INTERVAL_MS, others read EXP_CYCLE_INTERVAL_MS
        // Defaults: MAIN=20s, EXPERIMENTAL=40s — reduces API calls vs old 10s for both
        String intervalEnvKey = profile.isMainProfile() ? "MAIN_CYCLE_INTERVAL_MS" : "EXP_CYCLE_INTERVAL_MS";
        long defaultMs = profile.isMainProfile() ? 20_000L : 40_000L;
        String envVal = System.getenv(intervalEnvKey);
        long intervalMs = defaultMs;
        if (envVal != null && !envVal.isBlank()) {
            try { intervalMs = Long.parseLong(envVal); } catch (NumberFormatException ignored) {}
        }
        this.sleepDuration = Duration.ofMillis(intervalMs);
        logger.info("[{}] Cycle interval: {}ms (env: {})", profile.name(), intervalMs, intervalEnvKey);
        
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
                Thread.sleep(sleepDuration);
            } catch (InterruptedException e) {
                logger.info("[{}] Profile thread interrupted", profile.name());
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                logger.error("[{}] Error in trading cycle", profile.name(), e);
                try {
                    Thread.sleep(sleepDuration);
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

        // Clean up expired stop-loss cooldowns to prevent memory leak
        cleanupExpiredCooldowns();

        // Reconcile internal portfolio state with Alpaca positions
        reconcilePortfolioWithBroker(profilePrefix);

        // Retry any protective exits that failed in a previous cycle (e.g., API down).
        // Only drain during market hours — pre-market market orders get rejected by Alpaca
        // (extended_hours=false), which throws an exception and keeps the symbol in the queue,
        // causing a cancel-and-replace loop every 20s from market close until open.
        if (!urgentExitQueue.isEmpty()) {
            if (marketHoursFilter.isMarketOpen()) {
                drainUrgentExitQueue(profilePrefix);
            } else {
                logger.debug("{} Urgent exit queue has {} symbol(s) — holding until market open",
                    profilePrefix, urgentExitQueue.size());
            }
        }

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

        // Sync PDT day trade count from Alpaca server to prevent local DB divergence.
        // Only do this for Alpaca — other brokers don't report daytrade_count and must
        // not overwrite Alpaca's authoritative count with their own (possibly 0) value.
        if ("alpaca".equalsIgnoreCase(brokerName) && account.has("daytrade_count")) {
            pdtProtection.syncWithAlpaca(account.get("daytrade_count").asInt(0));
        }
        
        // Use actual account equity for ALL decisions — adapts dynamically as capital grows.
        // The old Math.min(accountEquity, capital) cap prevented the bot from using extra
        // capital deposited after startup. Now the full real balance is used every cycle.
        var equity = accountEquity;

        // Store latest market state for use in profit target checks
        this.latestVix = currentVix;
        this.latestRegime = regime;
        this.latestEquity = equity;

        // Update static snapshots for dashboard visibility
        latestVixSnapshot = currentVix;
        latestRegimeSnapshot = regime != null ? regime.name() : "UNKNOWN";
        latestTargetSymbolsSnapshot = String.join(",", targetSymbols);
        
        logger.debug("{} Account equity: ${}, Buying power: ${}, Using: ${}", 
            profilePrefix, 
            String.format("%.2f", accountEquity),
            String.format("%.2f", buyingPower), 
            String.format("%.2f", equity));
        
        // ========== CHECK ALL ALPACA POSITIONS FOR RISK EXITS ==========
        // CRITICAL: Run BEFORE portfolio halt so individual stop-losses still fire
        // even when the portfolio-level stop loss has been triggered
        // Only run during market hours — orders with extended_hours=false can't fill pre-market
        if (marketHoursFilter.isMarketOpen()) {
            checkAllPositionsForRiskExits(profilePrefix);

            // ========== CHECK ALL POSITIONS FOR PROFIT TARGETS ==========
            // CRITICAL: Check ALL positions for take-profit/stop-loss, not just current targets
            // This ensures positions from previous regimes are still monitored for exits
            // Runs before portfolio halt to guarantee protective exits always execute
            checkAllPositionsForProfitTargets(profilePrefix);
        } else {
            logger.debug("{} Skipping risk/profit checks — market closed (orders can't fill)", profilePrefix);
        }

        // ========== EMERGENCY / PAUSE GUARD ==========
        // Risk exits above already ran. Skip all new entries if emergency or paused.
        if (com.trading.bot.TradingBot.isEmergencyTriggered()) {
            logger.warn("{} EMERGENCY ACTIVE — skipping new entries this cycle", profilePrefix);
            return;
        }
        if (com.trading.bot.TradingBot.isTradingPaused()) {
            logger.info("{} TRADING PAUSED — skipping new entries this cycle", profilePrefix);
            return;
        }

        // ========== EQUITY-CURVE CIRCUIT BREAKER (Tier 3.10) ==========
        // Per-broker session breaker: trips on N consecutive losses or session drawdown.
        // Auto-resets at NY day rollover. Skips new entries until the next session.
        if (config.isCircuitBreakerEnabled()) {
            CircuitBreakerState cb = circuitBreakers.get(brokerName);
            if (cb != null) {
                cb.rolloverIfNewDay(accountEquity);
                cb.updateEquity(accountEquity);
                if (cb.shouldHaltEntries()) {
                    logger.warn("{} 🚨 CIRCUIT BREAKER {} — halting new entries (consec losses={}, dd={}%)",
                        profilePrefix, cb.tripReason(), cb.getConsecutiveLosses(),
                        String.format("%.2f", cb.getSessionDrawdownPct() * 100.0));
                    TradingWebSocketHandler.broadcastActivity(
                        String.format("[%s] 🚨 CIRCUIT BREAKER %s — entries halted (resets next session)",
                            profile.name(), cb.tripReason()),
                        "ERROR");
                    return;
                }
            }
        }

        // ========== PORTFOLIO-LEVEL STOP LOSS CHECK ==========
        // Halts NEW entries only — protective exits above have already run
        if (portfolioRiskManager.shouldHaltTrading(accountEquity)) {
            portfolioStopLossHaltActive = true;
            logger.error("{} 🛑 PORTFOLIO STOP LOSS - Halting new entries (protective exits already checked)", profilePrefix);

            // Assess and log portfolio risk
            var risk = portfolioRiskManager.assessRisk(client.getDelegate(), accountEquity);
            logger.error("{} {}", profilePrefix, risk.getSummary());

            TradingWebSocketHandler.broadcastActivity(
                String.format("[%s] PORTFOLIO STOP LOSS HIT - New entries halted", profile.name()),
                "ERROR"
            );

            return; // Skip new entries only
        } else {
            portfolioStopLossHaltActive = false;
        }

        // ========== CLEANUP EXCESS POSITIONS ==========
        // Auto-close worst positions if over limit
        // Only during market hours — sell orders can't fill pre-market
        if (marketHoursFilter.isMarketOpen()) {
            cleanupExcessPositions(profilePrefix);
        }

        // ========== END OF DAY EXIT (3:30 PM) ==========
        // Close all positions before market close to avoid overnight risk
        // Only during market hours — sell orders can't fill pre-market
        if (config.isEodExitEnabled() && marketHoursFilter.isMarketOpen()) {
            checkAndExecuteEodExit(profilePrefix);
        }

        // Check market hours
        boolean isMarketOpen = marketHoursFilter.isMarketOpen();
        boolean bypassMarketHours = config.isMarketHoursBypassEnabled();

        if (!isMarketOpen && !bypassMarketHours) {
            logger.debug("{} Market is closed", profilePrefix);
            return;
        }

        // Check entry timing (avoid first 15 minutes)
        if (!isGoodEntryTime()) {
            logger.debug("{} Not in entry window - skipping new entries", profilePrefix);
            return;
        }

        // Check for max drawdown
        if (riskManager.shouldHaltTrading(equity)) {
            maxDrawdownHaltActive = true;
            logger.error("{} HALTING TRADING: Max drawdown exceeded!", profilePrefix);
            return;
        } else {
            maxDrawdownHaltActive = false;
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

        logger.debug("{} Processing {} symbols", profilePrefix, symbolsToProcess.size());
        
        // Trade each symbol
        for (String symbol : symbolsToProcess) {
            try {
                tradeSymbol(symbol, targetSymbols, equity, buyingPower, regime, currentVix, profilePrefix);
            } catch (PDTRejectedException e) {
                pdtBlockedUntil = System.currentTimeMillis() + millisUntilMarketClose();
                staticPdtBlockedUntil = pdtBlockedUntil;
                logger.warn("{} PDT rejected for {} — blocking sell attempts until market close", profilePrefix, symbol);
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
                    logger.warn("{} ⚠️ High correlation detected in portfolio (diversification: {})",
                        profilePrefix, String.format("%.2f", correlation.diversificationScore()));
                }
                
                logger.debug("{} Portfolio diversification score: {}",
                    profilePrefix, String.format("%.2f", correlation.diversificationScore()));
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
                    score.momentumScore(),
                    score.recommendation()
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
                logger.warn("{} ⚠️ MAX LOSS EXIT: {} down {}% (limit: -{}%)",
                    profilePrefix, symbol, String.format("%.2f", lossPercent), String.format("%.1f", config.getMaxLossPercent()));

                // Cancel existing orders to free up held shares
                cancelExistingOrders(profilePrefix, symbol);

                // Retry up to 3 times if order fails
                int maxAttempts = 3;
                int attempt = 0;
                boolean success = false;
                Exception lastError = null;
                while (attempt < maxAttempts && !success) {
                    try {
                        client.placeOrder(symbol, qty, "sell", "market", "day", null);
                        logger.info("{} ✅ Max loss exit order placed for {} (attempt {}/{})", profilePrefix, symbol, attempt+1, maxAttempts);
                        // Record trade close
                        double exitPnl = pos.calculatePnL(currentPrice);
                        database.closeTrade(symbol, Instant.now(), currentPrice, exitPnl, brokerName);
                        TradingWebSocketHandler.broadcastActivity(
                            String.format("[%s] MAX LOSS EXIT: %s (%.2f%% loss) [attempt %d]",
                                profile.name(), symbol, lossPercent, attempt+1),
                            "WARN"
                        );
                        portfolio.setPosition(symbol, Optional.empty());
                        applyPostExitCooldown(symbol, currentPrice, exitPnl, profilePrefix, "max loss");
                        success = true;
                        return;
                    } catch (Exception e) {
                        lastError = e;
                        logger.error("{} Failed to place max loss exit order for {} (attempt {}/{}): {}", profilePrefix, symbol, attempt+1, maxAttempts, e.getMessage());
                        try { Thread.sleep(5000); } catch (InterruptedException ignored) {}
                        attempt++;
                    }
                }
                if (!success) {
                    TradingWebSocketHandler.broadcastActivity(
                        String.format("[%s] CRITICAL: Max loss exit FAILED for %s after %d attempts. Manual intervention required!", 
                            profile.name(), symbol, maxAttempts),
                        "ERROR"
                    );
                    logger.error("{} CRITICAL: Max loss exit FAILED for {} after {} attempts. Last error: {}", profilePrefix, symbol, maxAttempts, lastError != null ? lastError.getMessage() : "none");
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
                        cancelExistingOrders(profilePrefix, symbol);
                        client.placeOrder(symbol, qty, "sell", "market", "day", null);
                        logger.info("{} ✅ Time-based exit order placed for {}", profilePrefix, symbol);

                        // Record trade close
                        database.closeTrade(symbol, Instant.now(), currentPrice, pnl, brokerName);
                        portfolio.setPosition(symbol, Optional.empty());
                        applyPostExitCooldown(symbol, currentPrice, pnl, profilePrefix, "time-based");

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
            // 1. We don't have a position (qty == 0) AND no open DB record, OR
            // 2. We have a position but BUY signal is strong AND position is under tier max
            if (qty == 0) {
                // DB gate: block entry if an open trade already exists in the DB for this symbol.
                // This prevents duplicate entries after a restart when the in-memory portfolio
                // is empty but broker positions aren't yet filled (e.g., Tradier sandbox pending orders).
                if (database.hasOpenTrade(symbol, brokerName)) {
                    logger.debug("{} {} skipping BUY — open DB record exists (pending/filled position)",
                        profilePrefix, symbol);
                    return;
                }
                handleBuy(symbol, currentPrice, equity, buyingPower, currentVix, regime, profilePrefix);
            } else if (isTarget && buyingPower > 1.0) {
                // Guard: don't add to position if it already exceeds tier max
                double currentPositionValue = qty * currentPrice;
                double tierMaxValue = equity * com.trading.risk.CapitalTierManager.getParameters(equity).maxPositionPercent();
                int openEntries = database.countOpenTrades(symbol, brokerName);
                if (openEntries >= MAX_OPEN_ENTRIES_PER_SYMBOL) {
                    logger.info("{} Skipping add-to-position for {} — at hard cap ({}/{} open entries)",
                        profilePrefix, symbol, openEntries, MAX_OPEN_ENTRIES_PER_SYMBOL);
                } else if (currentPositionValue >= tierMaxValue) {
                    logger.info("{} Skipping add-to-position for {} — already at tier max (${} / ${})",
                        profilePrefix, symbol,
                        String.format("%.2f", currentPositionValue),
                        String.format("%.2f", tierMaxValue));
                    TradingWebSocketHandler.broadcastActivity(
                        String.format("[%s] ⛔ ADD BLOCKED: %s position $%.0f exceeds tier max $%.0f (%.0f%%)",
                            profile.name(), symbol, currentPositionValue, tierMaxValue,
                            com.trading.risk.CapitalTierManager.getParameters(equity).maxPositionPercent() * 100),
                        "INFO"
                    );
                } else {
                    handleBuy(symbol, currentPrice, equity, buyingPower, currentVix, regime, profilePrefix);
                }
            } else {
                logger.debug("{} Skipping BUY for {} (already have position, low buying power: ${})",
                    profilePrefix, symbol, String.format("%.2f", buyingPower));
            }
        } else if (signal instanceof TradingSignal.Sell sell) {
            if (qty > 0) {
                TradePosition position = currentPosition.get();
                double lossPercent = position.getLossPercent(currentPrice);
                double emergencyThreshold = config.getEmergencyStopLossPercent();

                // EMERGENCY STOP-LOSS: bypass hold-time and PDT if loss exceeds emergency threshold
                if (lossPercent <= -emergencyThreshold) {
                    logger.warn("{} {} EMERGENCY STOP-LOSS: loss {}% exceeds -{}% threshold — bypassing hold-time restriction",
                        profilePrefix, symbol, String.format("%.2f", lossPercent), String.format("%.1f", emergencyThreshold));
                    TradingWebSocketHandler.broadcastActivity(
                        String.format("[%s] 🚨 EMERGENCY SELL: %s loss %.2f%% exceeds -%.1f%% threshold",
                            profile.name(), symbol, lossPercent, emergencyThreshold),
                        "ERROR"
                    );
                    handleSell(symbol, currentPrice, position, profilePrefix);
                    return;
                }

                // Check if position has been held long enough (PDT compliance)
                int minHoldHours = config.getMinHoldTimeHours();
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
                          double buyingPower, double currentVix, MarketRegime regime, String profilePrefix) throws Exception {
        
        // ========== PDT RESERVATION CHECK ==========
        // Reserve the last PDT day-trade slot for protective exits (stop-loss/take-profit).
        // A new buy requires a same-day sell capability — if we're at 2/3 trades, a new
        // buy could use the last slot, then a stop-loss exit gets PDT-blocked.
        // Solution: block all new buys when daytrade_count >= 2.
        int currentDayTrades = pdtProtection.getDayTradeCount();
        staticDayTradeCount = currentDayTrades; // sync for dashboard
        // Reserve 1 PDT slot for exits (worst case: 1 position needs same-day stop-loss exit).
        // Block new buys only when 2 of 3 day trades are already used.
        // pdtReserveThreshold=2: allows buys at 0/3 and 1/3, blocks at 2/3 to keep 1 exit slot.
        // Old threshold=1 was too aggressive: it blocked ALL trading after the first day trade.
        int pdtReserveThreshold = config.getPdtReserveThreshold(); // default 2
        if (currentDayTrades >= pdtReserveThreshold && equity < 25000) {
            logger.warn("{} {} BUY BLOCKED — PDT reservation: {}/{} day trades used, keeping slots for exits",
                profilePrefix, symbol, currentDayTrades, 3);
            TradingWebSocketHandler.broadcastActivity(
                String.format("[%s] ⛔ BUY BLOCKED: %s — PDT slots reserved for exits (%d/3 used)",
                    profile.name(), symbol, currentDayTrades),
                "WARN"
            );
            return;
        }

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

        // ========== PER-SYMBOL POST-LOSS COOLDOWN (Tier 1.1) ==========
        // Distinct from the legacy minute-scale cooldown above: this is a 24h–72h block
        // applied after losses on the *same* symbol, escalating after consecutive losses.
        // Aimed at the TLT-loses-4x pattern. Other symbols keep trading.
        if (config.isPerSymbolCooldownEnabled() && postLossCooldown != null) {
            long now = System.currentTimeMillis();
            if (postLossCooldown.isInCooldown(symbol, now)) {
                long remHours = postLossCooldown.remainingMs(symbol, now) / (60L * 60 * 1000);
                int losses = postLossCooldown.getConsecutiveLosses(symbol);
                String reason = String.format("post-loss cooldown: %dh remaining (%d consec losses)", remHours, losses);
                blockedBuys.put(symbol, reason);
                logger.info("{} {} BUY BLOCKED — {}", profilePrefix, symbol, reason);
                TradingWebSocketHandler.broadcastActivity(
                    String.format("[%s] ⛔ %s post-loss cooldown: %dh left (%d consec losses)",
                        profile.name(), symbol, remHours, losses),
                    "WARN");
                return;
            }
        }

        // ========== NO-TRADE OPEN WINDOW (Tier 3.9) ==========
        // Block fresh entries during the noisy first N minutes of the regular session
        // (default 30m). Opening auction prints have a poor signal-to-noise ratio.
        if (config.isNoTradeOpenWindowEnabled()
                && marketHoursFilter.isInOpeningWindow(config.getNoTradeOpenWindowMinutes())) {
            String reason = "opening-window block: first " + config.getNoTradeOpenWindowMinutes() + "min";
            blockedBuys.put(symbol, reason);
            logger.info("{} {} BUY BLOCKED — {}", profilePrefix, symbol, reason);
            TradingWebSocketHandler.broadcastActivity(
                String.format("[%s] ⛔ %s blocked: first %d min after open",
                    profile.name(), symbol, config.getNoTradeOpenWindowMinutes()),
                "INFO");
            return;
        }

        // ========== EARNINGS BLACKOUT (Tier 2.5) ==========
        // Avoid entering positions within ±N hours of an earnings announcement.
        // Earnings days are gap-risk events and our backtests show negative EV around them.
        if (config.isEarningsBlackoutEnabled() && earningsCalendar != null) {
            try {
                boolean inBlackout = earningsCalendar.isInBlackout(
                    symbol,
                    java.time.Instant.now(),
                    config.getEarningsBlackoutHoursBefore(),
                    config.getEarningsBlackoutHoursAfter());
                if (inBlackout) {
                    String reason = String.format("earnings blackout: ±%d/±%dh window",
                        config.getEarningsBlackoutHoursBefore(),
                        config.getEarningsBlackoutHoursAfter());
                    blockedBuys.put(symbol, reason);
                    logger.info("{} {} BUY BLOCKED — {}", profilePrefix, symbol, reason);
                    TradingWebSocketHandler.broadcastActivity(
                        String.format("[%s] ⛔ %s earnings blackout active", profile.name(), symbol),
                        "WARN");
                    return;
                }
            } catch (Exception e) {
                logger.debug("{} Earnings check failed for {}: {}", profilePrefix, symbol, e.getMessage());
            }
        }

        // ========== PRICE IMPROVEMENT CHECK ==========
        // After a loss exit, only re-enter if price has dropped at least 1% from exit price.
        // Prevents buying back at the same price you just sold at a loss.
        Double lastExit = lastExitPrices.get(symbol);
        if (lastExit != null) {
            double improvementPercent = ((lastExit - currentPrice) / lastExit) * 100.0;
            if (improvementPercent < MIN_PRICE_IMPROVEMENT_PERCENT) {
                blockedBuys.put(symbol, String.format("waiting for price: need %.1f%% below $%.2f exit", MIN_PRICE_IMPROVEMENT_PERCENT, lastExit));
                logger.info("{} {} PRICE IMPROVEMENT CHECK FAILED - last exit=${}, now=${}, need {}% drop but only {}%",
                    profilePrefix, symbol, String.format("%.2f", lastExit), String.format("%.2f", currentPrice),
                    String.format("%.1f", MIN_PRICE_IMPROVEMENT_PERCENT), String.format("%.2f", improvementPercent));
                TradingWebSocketHandler.broadcastActivity(
                    String.format("[%s] ⏳ %s waiting for better price: need %.1f%% below $%.2f exit",
                        profile.name(), symbol, MIN_PRICE_IMPROVEMENT_PERCENT, lastExit),
                    "INFO"
                );
                return;
            }
            // Price has improved enough — clear the gate and allow entry
            logger.info("{} {} PRICE IMPROVED {}% below last exit ${} — allowing re-entry",
                profilePrefix, symbol, String.format("%.2f", improvementPercent), String.format("%.2f", lastExit));
            blockedBuys.remove(symbol);
            lastExitPrices.remove(symbol);
        }
        
        // ========== GAP-DOWN PROTECTION ==========
        // Prevent buying into a stock that is already down significantly from yesterday's close.
        // Strategy signals are based on daily bars (yesterday's data). If the stock gaps down
        // today, the signal is stale and we'd be buying into weakness.
        // Threshold = stop_loss * 0.67: block if already 2/3 of the way to stop loss before entry.
        // MAIN (1.5% SL) → block at ~1.0% | EXPERIMENTAL (1.0% SL) → block at ~0.67%
        try {
            var recentBars = client.getMarketHistory(symbol, 2);
            if (recentBars.size() >= 2) {
                // Use the MOST RECENT completed bar (last in list = yesterday's close).
                // Bug was: get(0) = older bar (e.g. Thursday), not yesterday (Friday).
                // If stock rallied Friday then gaps down Monday, old code missed the gap.
                double prevClose = recentBars.get(recentBars.size() - 1).close();
                double gapDownPct = (prevClose - currentPrice) / prevClose * 100.0;
                double gapDownThreshold = profile.stopLossPercent() * 0.67;
                if (gapDownPct >= gapDownThreshold) {
                    String reason = String.format("gap-down %.1f%% from $%.2f", gapDownPct, prevClose);
                    blockedBuys.put(symbol, reason);
                    logger.info("{} {} BUY BLOCKED — gap-down {}% from yesterday close ${} (threshold {}%)",
                        profilePrefix, symbol, String.format("%.1f", gapDownPct),
                        String.format("%.2f", prevClose), String.format("%.1f", gapDownThreshold));
                    TradingWebSocketHandler.broadcastActivity(
                        String.format("[%s] ⛔ BUY BLOCKED: %s gap-down %.1f%% from $%.2f",
                            profile.name(), symbol, gapDownPct, prevClose),
                        "WARN"
                    );
                    return;
                } else {
                    blockedBuys.remove(symbol); // gap resolved — clear block
                }
            }
        } catch (Exception e) {
            logger.debug("{} Could not check gap-down for {}: {}", profilePrefix, symbol, e.getMessage());
        }

        // ========== INTRADAY TREND CHECK ==========
        // Strategy BUY signals are based on yesterday's daily closes. If the stock is
        // actively falling in the current session, the signal is stale and entry is risky.
        // Check: last completed hourly bar must not show a decline of ≥0.3%, AND
        //        current price must not be more than 0.2% below the previous hour close.
        try {
            var NY = java.time.ZoneId.of("America/New_York");
            var sessionStart = java.time.LocalDate.now(NY).atTime(9, 30).atZone(NY).toInstant();
            var intradayBars = client.getBars(symbol, "1Hour", 8).stream()
                .filter(b -> !b.timestamp().isBefore(sessionStart))
                .toList();
            if (intradayBars.size() >= 2) {
                double lastHourClose = intradayBars.get(intradayBars.size() - 1).close();
                double prevHourClose = intradayBars.get(intradayBars.size() - 2).close();
                double hourlyDeclinePct = (prevHourClose - lastHourClose) / prevHourClose * 100.0;
                double currentVsPrevHour = (prevHourClose - currentPrice) / prevHourClose * 100.0;
                // Block only if BOTH last hour was red AND current price is still below it
                if (hourlyDeclinePct >= 0.3 && currentVsPrevHour >= 0.2) {
                    String reason = String.format("intraday downtrend: last hour -%.1f%%, now -%.1f%% from prev hour",
                        hourlyDeclinePct, currentVsPrevHour);
                    blockedBuys.put(symbol, reason);
                    logger.info("{} {} BUY BLOCKED — {}", profilePrefix, symbol, reason);
                    TradingWebSocketHandler.broadcastActivity(
                        String.format("[%s] ⛔ BUY BLOCKED: %s %s", profile.name(), symbol, reason),
                        "WARN"
                    );
                    return;
                }
            }
        } catch (Exception e) {
            logger.debug("{} Could not check intraday trend for {}: {}", profilePrefix, symbol, e.getMessage());
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

        // ========== CORRELATION / CONCENTRATION CAP (Tier 2.4) ==========
        // Block entry if we'd exceed N concurrent positions whose pairwise correlation
        // is above {threshold}. A loss event in correlated names compounds (SPY+QQQ+VTI
        // all dump together), so cap concentration rather than relying on diversification.
        if (config.isCorrelationCapEnabled() && portfolio.getActivePositionCount() > 0) {
            try {
                java.util.List<String> openSymbols = new java.util.ArrayList<>(portfolio.getActiveStoredSymbols());
                if (!openSymbols.contains(symbol)) {
                    java.util.List<String> probe = new java.util.ArrayList<>(openSymbols);
                    probe.add(symbol);
                    var analysis = correlationCalculator.analyzePortfolio(probe);
                    double thr = config.getCorrelationCapThreshold();
                    int maxConc = config.getCorrelationCapMaxConcurrent();
                    int relatedHits = 0;
                    for (var pair : analysis.highCorrelations()) {
                        if ((pair.symbol1().equalsIgnoreCase(symbol) || pair.symbol2().equalsIgnoreCase(symbol))
                                && Math.abs(pair.correlation()) >= thr) {
                            relatedHits++;
                        }
                    }
                    if (relatedHits >= maxConc) {
                        String reason = String.format("correlation cap: %d existing positions ≥%.2f corr (max %d)",
                            relatedHits, thr, maxConc);
                        blockedBuys.put(symbol, reason);
                        logger.info("{} {} BUY BLOCKED — {}", profilePrefix, symbol, reason);
                        TradingWebSocketHandler.broadcastActivity(
                            String.format("[%s] ⛔ %s correlation cap: %d ≥%.2f (max %d)",
                                profile.name(), symbol, relatedHits, thr, maxConc),
                            "WARN");
                        return;
                    }
                }
            } catch (Exception e) {
                logger.debug("{} Correlation cap check failed for {}: {}", profilePrefix, symbol, e.getMessage());
            }
        }

        // ========== PENDING ORDER CHECK ==========
        // Prevent duplicate orders for the same symbol (fixes order spam issue).
        // If a pending entry order has been sitting >30 min (e.g. sandbox never fills),
        // cancel it so the bot can re-evaluate and place a fresh order.
        try {
            var pendingOrders = client.getOpenOrders(symbol);
            if (pendingOrders.isArray() && pendingOrders.size() > 0) {
                long now = System.currentTimeMillis();
                long firstSeen = pendingEntryTimestamps.computeIfAbsent(symbol, k -> now);
                long ageMs = now - firstSeen;

                if (ageMs >= STALE_ENTRY_ORDER_MS) {
                    logger.warn("{} {} has {} stale pending entry order(s) ({}min old) — cancelling and re-evaluating",
                        profilePrefix, symbol, pendingOrders.size(), ageMs / 60000);
                    for (var order : pendingOrders) {
                        String orderId = order.path("id").asText();
                        if (!orderId.isBlank()) {
                            try { client.cancelOrder(orderId); } catch (Exception ce) {
                                logger.warn("{} Failed to cancel stale entry order {} for {}: {}",
                                    profilePrefix, orderId, symbol, ce.getMessage());
                            }
                        }
                    }
                    pendingEntryTimestamps.remove(symbol);
                    // Fall through to re-evaluate entry this cycle
                } else {
                    logger.info("{} {} already has {} pending order(s) ({}min old), skipping new entry",
                        profilePrefix, symbol, pendingOrders.size(), ageMs / 60000);
                    return;
                }
            } else {
                pendingEntryTimestamps.remove(symbol); // order filled/expired — clear tracking
            }
        } catch (Exception e) {
            logger.debug("{} Could not check pending orders for {}: {}", profilePrefix, symbol, e.getMessage());
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
        // Pass actual VIX for volatility-based size adjustment (NOT stop-loss percent)
        double positionSize = riskManager.calculatePositionSize(
            availableCapital,
            currentPrice,
            currentVix
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
                
                logger.info("{} {}: 📊 PHASE 3 - Adaptive sizing: ${} (ML:{} VIX:{} Corr:{})",
                    profilePrefix, symbol, String.format("%.2f", positionSize), String.format("%.1f", mlScore), String.format("%.1f", currentVix), String.format("%.2f", maxCorrelation));
                
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
        
        // VIX adjustment is already applied inside calculateVolatilityAdjustedSize (20/VIX factor).
        // A second 30% cut here was double-penalizing volatile markets and starving a $365 account
        // of capital. Removed to avoid triple-stacking: MICRO tier risk% + VIX formula + 0.7 here.

        logger.info("{} {}: 🎯 Final position sizing: {} shares",
            profilePrefix, symbol, positionSize);

        // ========== ATR-SCALED STOP / TAKE-PROFIT (Tier 1.2) ==========
        // Replaces flat % stops with ATR-derived stops so volatile names get wider stops
        // (don't get noise-stopped) and quiet names get tighter ones (don't bleed slowly).
        // Falls back to profile flat % if ATR can't be computed (insufficient bars).
        double stopLoss;
        double takeProfit;
        double atr = 0.0;
        if (config.isAtrStopsEnabled()) {
            try {
                int period = config.getAtrPeriodBars();
                var atrBars = client.getBars(symbol, "1Day", period + 5);
                atr = AtrCalculator.atr(atrBars, period);
            } catch (Exception e) {
                logger.debug("{} ATR fetch failed for {}: {}", profilePrefix, symbol, e.getMessage());
            }
        }
        if (atr > 0.0) {
            stopLoss = RiskManager.calculateAtrStopLoss(
                currentPrice, atr,
                config.getAtrStopMultiplier(),
                config.getAtrStopFloorPercent(),
                config.getAtrStopCeilingPercent());
            takeProfit = RiskManager.calculateAtrTakeProfit(
                currentPrice, atr,
                config.getAtrTakeProfitMultiplier(),
                config.getAtrStopFloorPercent());
            logger.info("{} {}: ATR={} (n={}) → stop=${} ({}%) tp=${} ({}%)",
                profilePrefix, symbol,
                String.format("%.4f", atr),
                config.getAtrPeriodBars(),
                String.format("%.2f", stopLoss),
                String.format("%.2f", (currentPrice - stopLoss) / currentPrice * 100.0),
                String.format("%.2f", takeProfit),
                String.format("%.2f", (takeProfit - currentPrice) / currentPrice * 100.0));
        } else {
            stopLoss = currentPrice * (1.0 - profile.stopLossPercent() / 100.0);
            takeProfit = currentPrice * (1.0 + profile.takeProfitPercent() / 100.0);
        }

        // ========== ATR-BASED VOL-TARGETED SIZING (Tier 1.3) ==========
        // Override the earlier sizing if ATR-based stop is available: ensures total $-risk is
        // capped at risk_per_trade × equity regardless of per-name volatility.
        if (config.isAtrSizingEnabled() && atr > 0.0 && stopLoss > 0.0 && stopLoss < currentPrice) {
            try {
                AdvancedPositionSizer atrSizer = new AdvancedPositionSizer(config, database);
                atrSizer.setAdaptiveManager(adaptiveManager);
                double volTargetedSize = atrSizer.calculateAtrPositionSize(
                    symbol, availableCapital, currentPrice, stopLoss);
                if (volTargetedSize > 0) {
                    double prev = positionSize;
                    positionSize = Math.min(positionSize, volTargetedSize);
                    logger.info("{} {}: ATR vol-targeted sizing: {} → {} shares (risk={}%)",
                        profilePrefix, symbol,
                        String.format("%.3f", prev),
                        String.format("%.3f", positionSize),
                        String.format("%.2f", config.getAtrSizingRiskPercent() * 100.0));
                }
            } catch (Exception e) {
                logger.debug("{} ATR sizing failed for {}: {}", profilePrefix, symbol, e.getMessage());
            }
        }
        
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
            
            // Check minimum order amount ($10.00).
            // Below $10 the position is too small to meaningfully capture P&L and
            // creates noise (e.g. $6 GLD fractional shares on a $1K Alpaca account).
            if (orderValue < 10.00) {
                logger.warn("{} {}: ⚠️ Order value ${} is below minimum $10.00 - SKIPPING",
                    profilePrefix, symbol, String.format("%.2f", orderValue));

                // Broadcast to UI with capital increase recommendation
                TradingWebSocketHandler.broadcastActivity(
                    String.format("[%s] ⚠️ SKIPPED: %s order ($%.2f < $10.00 minimum) - " +
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

            // Determine optimal order type (limit vs market) based on conditions
            var orderCtx = new OrderContext(
                symbol, "buy", currentPrice, equity, currentVix, regime,
                profile.strategyType(), false, false, false
            );
            var orderDecision = orderTypeSelector.selectOrderType(orderCtx);
            Double entryLimitPrice = orderDecision.limitPrice();

            // Place bracket order and check if server-side protection was applied
            var bracketResult = client.placeBracketOrder(symbol, positionSize, "buy",
                takeProfit, stopLoss, null, entryLimitPrice);

            if (!bracketResult.success()) {
                // Order failed completely - try simple market order as fallback
                logger.warn("{} {}: Bracket order failed ({}), trying market order",
                    profilePrefix, symbol, bracketResult.message());

                try {
                    client.placeOrder(symbol, positionSize, "buy", "market", "day", null);
                    broadcastOrderData(symbol, positionSize, "buy", "market", "filled", currentPrice);

                    // Market order succeeded but NO bracket protection
                    TradingWebSocketHandler.broadcastActivity(
                        String.format("[%s] ⚠️ BUY ORDER FILLED (No Protection): %s %.3f shares @ $%.2f",
                            profile.name(), symbol, positionSize, currentPrice),
                        "WARN"
                    );

                    // Record as unprotected position
                    logger.warn("{} {}: ⚠️ Position has NO server-side SL/TP protection!",
                        profilePrefix, symbol);
                } catch (Exception marketOrderError) {
                    if (marketOrderError.getMessage() != null &&
                        marketOrderError.getMessage().contains("insufficient buying power")) {
                        logger.error("{} {}: ❌ Order FAILED - Insufficient buying power",
                            profilePrefix, symbol);

                        TradingWebSocketHandler.broadcastActivity(
                            String.format("[%s] ❌ BUY ORDER FAILED: %s - Insufficient buying power (Tried: $%.2f, Available: $%.2f)",
                                profile.name(), symbol, positionSize * currentPrice, buyingPower),
                            "ERROR"
                        );
                        return;
                    } else {
                        TradingWebSocketHandler.broadcastActivity(
                            String.format("[%s] ❌ BUY ORDER FAILED: %s - %s",
                                profile.name(), symbol, marketOrderError.getMessage()),
                            "ERROR"
                        );
                        throw marketOrderError;
                    }
                }
            } else if (bracketResult.needsClientSideMonitoring()) {
                // Order succeeded but fractional — Alpaca doesn't support bracket orders for fractions.
                // Place a separate native GTC stop-market order to provide crash-safe protection.
                // This order persists on Alpaca even if the bot restarts.
                logger.warn("{} {}: ⚠️ Fractional position — placing native GTC stop-loss at ${}",
                    profilePrefix, symbol, String.format("%.2f", stopLoss));
                try {
                    client.placeNativeStopOrder(symbol, positionSize, stopLoss);
                    logger.info("{} {}: ✅ Native GTC stop-loss placed at ${} (crash-safe)",
                        profilePrefix, symbol, String.format("%.2f", stopLoss));
                } catch (Exception stopEx) {
                    // Loud signal — fractional position with no broker-side stop is the META-incident
                    // failure mode (only client-side polling stands between this position and unbounded loss).
                    logger.error("{} {}: 🚨 NATIVE STOP FAILED on fractional position ({}). Position is protected ONLY by client-side polling — restart or outage = unbounded loss exposure.",
                        profilePrefix, symbol, stopEx.getMessage());
                    TradingWebSocketHandler.broadcastActivity(
                        String.format("[%s] 🚨 BROKER STOP FAILED: %s (fractional) — only client-side stop active. Investigate immediately.",
                            profile.name(), symbol),
                        "ERROR");
                }

                TradingWebSocketHandler.broadcastActivity(
                    String.format("[%s] ⚠️ BUY FILLED (Fractional): %s %.3f shares @ $%.2f — GTC stop @ $%.2f",
                        profile.name(), symbol, positionSize, currentPrice, stopLoss),
                    "WARN"
                );
            } else {
                // Full bracket protection applied
                logger.info("{} {}: ✅ Bracket order with full SL/TP protection", profilePrefix, symbol);

                TradingWebSocketHandler.broadcastActivity(
                    String.format("[%s] ✅ BUY ORDER FILLED: %s %.3f shares @ $%.2f (Protected)",
                        profile.name(), symbol, positionSize, currentPrice),
                    "SUCCESS"
                );
            }
        }
        
        // Update portfolio
        portfolio.setPosition(symbol, Optional.of(newPosition));
        
        // Record trade
        database.recordTrade(
            symbol,
            profile.strategyType(),
            profile.name(),  // Add profile name for tracking
            brokerName,
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
            // Cancel existing orders to free up held shares
            cancelExistingOrders("[" + profile.name() + "]", symbol);

            // Determine optimal order type for signal-based exit
            var orderCtx = new OrderContext(
                symbol, "sell", currentPrice, latestEquity, latestVix, latestRegime,
                profile.strategyType(), true, false, false
            );
            var orderDecision = orderTypeSelector.selectOrderType(orderCtx);
            client.placeOrder(symbol, position.quantity(), "sell",
                orderDecision.orderType(), orderDecision.timeInForce(), orderDecision.limitPrice());
        }
        
        // Update portfolio
        portfolio.setPosition(symbol, Optional.empty());

        // Set re-entry cooldown to prevent immediate re-buy
        long cooldownMs = config.getStopLossCooldownMs();
        stopLossCooldowns.put(symbol, System.currentTimeMillis() + cooldownMs);
        logger.info("{} {} placed on {}-minute re-entry cooldown after sell", profilePrefix, symbol, cooldownMs / 60000);

        // Record exit price for loss exits — require price improvement before re-entry
        if (pnl < 0) {
            lastExitPrices.put(symbol, currentPrice);
            logger.info("{} {} recorded loss exit at ${} — re-entry requires {}% price improvement",
                profilePrefix, symbol, String.format("%.2f", currentPrice), MIN_PRICE_IMPROVEMENT_PERCENT);
            // Tier 1.1: feed per-symbol post-loss cooldown (escalates after consecutive losses).
            if (postLossCooldown != null) {
                long applied = postLossCooldown.recordLoss(symbol, System.currentTimeMillis());
                logger.info("{} {} post-loss cooldown applied: {}h ({} consec losses)",
                    profilePrefix, symbol, applied / (60L * 60 * 1000),
                    postLossCooldown.getConsecutiveLosses(symbol));
            }
        } else {
            // Profitable exit — allow free re-entry at any price, reset consecutive SL counter.
            lastExitPrices.remove(symbol);
            consecutiveStopLosses.remove(symbol);
            if (postLossCooldown != null) postLossCooldown.recordWin(symbol);
            logger.debug("{} {} consecutive SL counter reset after profitable exit", profilePrefix, symbol);
        }

        // Tier 3.10: feed circuit breaker (per-broker session breaker on consecutive $-losses).
        CircuitBreakerState cb = circuitBreakers.get(brokerName);
        if (cb != null) cb.recordTrade(pnl);

        // Track day trades locally for non-Alpaca brokers (Alpaca syncs from broker API each cycle).
        // A day trade = position opened and closed on the same calendar day (ET timezone).
        if (!"alpaca".equalsIgnoreCase(brokerName) && position.entryTime() != null) {
            var NY = java.time.ZoneId.of("America/New_York");
            boolean isToday = position.entryTime().atZone(NY).toLocalDate()
                .equals(java.time.LocalDate.now(NY));
            if (isToday) {
                pdtProtection.recordDayTrade(symbol);
            }
        }

        // Close trade in database
        database.closeTrade(symbol, java.time.Instant.now(), currentPrice, pnl, brokerName);
        
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
            database.updateStop(symbol, brokerName, updatedPosition.stopLoss());

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
        if (!profile.isMainProfile()) {
            return;
        }

        if (!config.isMaxLossExitEnabled()) {
            return; // Feature disabled
        }

        // PDT circuit breaker: skip sell attempts if Alpaca recently rejected with 403 PDT
        if (System.currentTimeMillis() < pdtBlockedUntil) {
            logger.debug("{} Skipping risk exits — PDT blocked for {} more seconds",
                profilePrefix, (pdtBlockedUntil - System.currentTimeMillis()) / 1000);
            return;
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
                double qty = alpacaPos.quantity();

                // Skip if a sell order was already placed this cycle (prevents duplicate sells)
                if (pendingExitOrders.containsKey(symbol)) {
                    logger.debug("{} {} has pending exit order, skipping risk exit check", profilePrefix, symbol);
                    continue;
                }

                // Guard against division by zero for empty positions
                if (qty == 0) {
                    logger.debug("{} Skipping zero-quantity position: {}", profilePrefix, symbol);
                    continue;
                }

                double currentPrice = alpacaPos.marketValue() / qty;
                double entryPrice = alpacaPos.avgEntryPrice();

                if (entryPrice == 0) {
                    logger.warn("{} Skipping position with zero entry price: {}", profilePrefix, symbol);
                    continue;
                }
                
                // Get tracked position if exists
                var trackedPos = portfolio.getPosition(symbol);
                
                // Use enhanced exit strategy if position is tracked
                if (trackedPos.isPresent()) {
                    TradePosition position = trackedPos.get();

                    // Calculate current volatility (simplified - using price movement)
                    double volatility = Math.abs(currentPrice - entryPrice) / entryPrice;

                    // Pre-earnings force-exit. Tier 2.5 only blocks new ENTRIES; an open position
                    // would otherwise ride straight into the announcement (the META scenario:
                    // bought 2026-04-27, held through 2026-04-30 earnings, gapped down ~10%).
                    if (config.isPreEarningsExitEnabled() && earningsCalendar != null
                            && config.getPreEarningsExitHoursBefore() > 0) {
                        try {
                            boolean approachingEarnings = earningsCalendar.isInBlackout(
                                symbol, java.time.Instant.now(),
                                config.getPreEarningsExitHoursBefore(), 0);
                            if (approachingEarnings) {
                                var exitDecision = com.trading.exits.ExitStrategyManager.ExitDecision.fullExit(
                                    com.trading.exits.ExitStrategyManager.ExitType.EARNINGS_PROTECTION,
                                    String.format("earnings within %dh — pre-emptive exit",
                                        config.getPreEarningsExitHoursBefore()),
                                    currentPrice);
                                logger.warn("{} {} 🗓️ PRE-EARNINGS EXIT — {}",
                                    profilePrefix, symbol, exitDecision.reason());
                                try {
                                    cancelExistingOrders(profilePrefix, symbol);
                                    client.placeOrderDirect(symbol, qty, "sell", "market", "day", null);
                                    portfolio.setPosition(symbol, Optional.empty());
                                    applyPostExitCooldown(symbol, currentPrice,
                                        (currentPrice - entryPrice) * qty,
                                        profilePrefix, "PRE_EARNINGS");
                                    TradingWebSocketHandler.broadcastActivity(
                                        String.format("[%s] 🗓️ PRE-EARNINGS EXIT: %s — %s",
                                            profile.name(), symbol, exitDecision.reason()),
                                        "WARN");
                                    pendingExitOrders.put(symbol, System.currentTimeMillis());
                                    continue;
                                } catch (PDTRejectedException e) {
                                    pdtBlockedUntil = System.currentTimeMillis() + millisUntilMarketClose();
                                    staticPdtBlockedUntil = pdtBlockedUntil;
                                    logger.warn("{} PDT rejected pre-earnings exit for {}",
                                        profilePrefix, symbol);
                                    continue;
                                } catch (Exception e) {
                                    logger.error("{} Failed pre-earnings exit for {}",
                                        profilePrefix, symbol, e);
                                    urgentExitQueue.put(urgentKey(brokerName, symbol),
                                        new UrgentExit(brokerName, symbol, qty,
                                            "pre-earnings", System.currentTimeMillis()));
                                }
                            }
                        } catch (Exception e) {
                            logger.debug("{} Earnings check failed for {}: {}",
                                profilePrefix, symbol, e.getMessage());
                        }
                    }

                    // Evaluate exit decision using enhanced strategy
                    var exitDecision = exitStrategyManager.evaluateExit(
                        position, currentPrice, volatility, portfolioPositions
                    );
                    
                    if (exitDecision.type() != com.trading.exits.ExitStrategyManager.ExitType.NONE) {
                        // Use live broker qty for full exits to prevent "insufficient qty" errors
                        // when internal tracker has drifted from actual broker position.
                        double qtyToExit = exitDecision.isPartial()
                            ? position.quantity() * exitDecision.quantity()  // partial: fraction of internal
                            : qty;  // full exit: always use live broker qty
                        
                        logger.info("{} 🎯 ENHANCED EXIT: {} - {}", 
                            profilePrefix, symbol, exitDecision.reason());
                        
                        try {
                            cancelExistingOrders(profilePrefix, symbol);
                            // Use direct order for risk exits (bypass circuit breaker - critical protective exit)
                            client.placeOrderDirect(symbol, qtyToExit, "sell", "market", "day", null);
                            
                            if (exitDecision.isPartial()) {
                                logger.info("{} ✅ Partial exit executed: {} ({}% of position)",
                                    profilePrefix, symbol, String.format("%.1f", exitDecision.quantity() * 100));
                                // Mark the partial exit level so it won't re-trigger next cycle
                                if (exitDecision.partialLevel() > 0) {
                                    var marked = position.markPartialExit(exitDecision.partialLevel());
                                    portfolio.setPosition(symbol, Optional.of(marked));
                                    database.updatePartialExits(symbol, brokerName, marked.partialExitsExecuted());
                                }
                            } else {
                                logger.info("{} ✅ Full exit executed: {}", profilePrefix, symbol);
                                portfolio.setPosition(symbol, Optional.empty());
                                // Set re-entry cooldown after full exit
                                stopLossCooldowns.put(symbol, System.currentTimeMillis() + config.getStopLossCooldownMs());
                                // Record exit price if loss — require price improvement before re-entry
                                double tradePnl = (currentPrice - entryPrice) * qty;
                                if (currentPrice < entryPrice) {
                                    lastExitPrices.put(symbol, currentPrice);
                                    if (postLossCooldown != null) {
                                        postLossCooldown.recordLoss(symbol, System.currentTimeMillis());
                                    }
                                } else if (postLossCooldown != null) {
                                    postLossCooldown.recordWin(symbol);
                                }
                                CircuitBreakerState cbExit = circuitBreakers.get(brokerName);
                                if (cbExit != null) cbExit.recordTrade(tradePnl);
                            }
                            
                            TradingWebSocketHandler.broadcastActivity(
                                String.format("[%s] %s EXIT: %s - %s",
                                    profile.name(),
                                    exitDecision.isPartial() ? "PARTIAL" : "FULL",
                                    symbol,
                                    exitDecision.reason()),
                                exitDecision.isPartial() ? "INFO" : "WARN"
                            );

                            // Mark as pending to prevent duplicate sells in this and future cycles
                            pendingExitOrders.put(symbol, System.currentTimeMillis());
                            continue;
                        } catch (PDTRejectedException e) {
                            pdtBlockedUntil = System.currentTimeMillis() + millisUntilMarketClose();
                staticPdtBlockedUntil = pdtBlockedUntil;
                            logger.warn("{} PDT rejected protective exit for {} — blocking until market close ({})",
                                profilePrefix, symbol, java.time.Instant.ofEpochMilli(pdtBlockedUntil));
                            TradingWebSocketHandler.broadcastActivity(
                                String.format("[%s] ⛔ PDT LIMIT HIT: %s exit blocked — all sells paused until 4PM ET. Positions protected by native stops.",
                                    profile.name(), symbol), "ERROR");
                            continue; // try remaining positions — non-day-trade sells may still succeed
                        } catch (Exception e) {
                            logger.error("{} Failed to place enhanced exit order for {}",
                                profilePrefix, symbol, e);
                            urgentExitQueue.put(urgentKey(brokerName, symbol), new UrgentExit(brokerName, symbol, qtyToExit, exitDecision.reason(), System.currentTimeMillis()));
                            TradingWebSocketHandler.broadcastActivity(
                                String.format("[%s] ⚠️ EXIT FAILED, QUEUED FOR RETRY: %s (%s)",
                                    profile.name(), symbol, exitDecision.reason()),
                                "ERROR"
                            );
                        }
                    }
                    continue; // Position handled by enhanced strategy
                }
                
                // First sight of an untracked position: register it (with DB-persisted stops if available,
                // or freshly reconstructed tight stops otherwise) AND attempt a native broker stop. This
                // closes the META-incident hole where fractional fills + post-restart drift left positions
                // completely unprotected. Falls through to the loss-threshold safety net below.
                var existingDbRecord = database.getOpenTradeRecords(brokerName).stream()
                    .filter(r -> r.symbol().equals(symbol)).findFirst();

                double recoveredStop;
                double recoveredTp;
                java.time.Instant recoveredEntryTime;
                if (existingDbRecord.isPresent()) {
                    var rec = existingDbRecord.get();
                    recoveredStop = rec.stopLoss();
                    recoveredTp = rec.takeProfit();
                    recoveredEntryTime = rec.entryTime();
                    logger.info("{} {}: untracked position — restored stops from DB (SL=${} TP=${})",
                        profilePrefix, symbol,
                        String.format("%.2f", recoveredStop), String.format("%.2f", recoveredTp));
                } else {
                    double profileSlFraction = Math.max(profile.stopLossPercent(), 1.0) / 100.0;
                    double profileTpFraction = Math.max(profile.takeProfitPercent(), 2.0) / 100.0;
                    double idealStop = Math.max(entryPrice * (1.0 - profileSlFraction), currentPrice * 0.985);
                    recoveredStop = Math.min(idealStop, currentPrice * 0.999);
                    recoveredTp = entryPrice * (1.0 + profileTpFraction);
                    recoveredEntryTime = java.time.Instant.now().minus(java.time.Duration.ofHours(24));
                    database.recordTrade(symbol, profile.strategyType(), profile.name(), brokerName,
                        recoveredEntryTime, entryPrice, qty, recoveredStop, recoveredTp);
                    logger.warn("{} {}: untracked position with no DB record — reconstructed SL=${} TP=${}",
                        profilePrefix, symbol,
                        String.format("%.2f", recoveredStop), String.format("%.2f", recoveredTp));
                }

                boolean hasOpenStop = false;
                try {
                    var openOrders = client.getOpenOrders(symbol);
                    if (openOrders != null && openOrders.isArray()) {
                        for (var ord : openOrders) {
                            String otype = ord.has("type") ? ord.get("type").asText("").toLowerCase() : "";
                            if (otype.contains("stop")) { hasOpenStop = true; break; }
                        }
                    }
                } catch (Exception ignored) { /* best-effort detection */ }

                if (!hasOpenStop) {
                    try {
                        client.placeNativeStopOrder(symbol, qty, recoveredStop);
                        logger.warn("{} {}: ⚠️ orphan position recovered — native GTC stop placed @ ${}",
                            profilePrefix, symbol, String.format("%.2f", recoveredStop));
                        TradingWebSocketHandler.broadcastActivity(
                            String.format("[%s] ⚠️ Recovered orphan %s — native stop @ $%.2f",
                                profile.name(), symbol, recoveredStop),
                            "WARN");
                    } catch (Exception stopEx) {
                        logger.error("{} {}: 🚨 ORPHAN POSITION WITHOUT BROKER STOP ({}). Client-side max-loss is the only safety net.",
                            profilePrefix, symbol, stopEx.getMessage());
                        TradingWebSocketHandler.broadcastActivity(
                            String.format("[%s] 🚨 UNPROTECTED orphan %s — broker stop FAILED (%s)",
                                profile.name(), symbol, stopEx.getMessage()),
                            "ERROR");
                    }
                }

                int recoveredPartialMask = existingDbRecord.map(TradeDatabase.OpenTradeRecord::partialExitsExecuted).orElse(0);
                portfolio.setPosition(symbol,
                    Optional.of(new TradePosition(symbol, entryPrice, qty, recoveredStop, recoveredTp,
                        recoveredEntryTime, entryPrice, recoveredPartialMask)));

                // Loss-threshold safety net (also runs after registration as belt-and-suspenders).
                double lossPercent = ((currentPrice - entryPrice) / entryPrice) * 100;

                if (lossPercent <= -config.getMaxLossPercent()) {
                    logger.warn("{} ⚠️ MAX LOSS EXIT (untracked): {} down {}% (limit: -{}%)",
                        profilePrefix, symbol, String.format("%.2f", Math.abs(lossPercent)), String.format("%.1f", config.getMaxLossPercent()));

                    try {
                        cancelExistingOrders(profilePrefix, symbol);
                        // Use direct order for max-loss exit (bypass circuit breaker - critical protective exit)
                        client.placeOrderDirect(symbol, qty, "sell", "market", "day", null);
                        portfolio.setPosition(symbol, Optional.empty());
                        // Set re-entry cooldown + per-symbol cooldown + circuit-breaker tracking.
                        applyPostExitCooldown(symbol, currentPrice, (currentPrice - entryPrice) * qty,
                            profilePrefix, "MAX_LOSS_UNTRACKED");
                        logger.info("{} ✅ Max loss exit order placed for untracked position {}",
                            profilePrefix, symbol);
                        
                        TradingWebSocketHandler.broadcastActivity(
                            String.format("[%s] MAX LOSS EXIT (untracked): %s (%.2f%% loss)", 
                                profile.name(), symbol, Math.abs(lossPercent)),
                            "WARN"
                        );
                    } catch (PDTRejectedException e) {
                        pdtBlockedUntil = System.currentTimeMillis() + millisUntilMarketClose();
                staticPdtBlockedUntil = pdtBlockedUntil;
                        logger.warn("{} PDT rejected max-loss exit for {} — blocking until market close",
                            profilePrefix, symbol);
                        return;
                    } catch (Exception e) {
                        logger.error("{} Failed to place max loss exit order for {}",
                            profilePrefix, symbol, e);
                        urgentExitQueue.put(urgentKey(brokerName, symbol), new UrgentExit(brokerName, symbol, qty,
                            String.format("max loss (%.1f%%)", Math.abs(lossPercent)), System.currentTimeMillis()));
                        TradingWebSocketHandler.broadcastActivity(
                            String.format("[%s] ⚠️ MAX LOSS EXIT FAILED, QUEUED FOR RETRY: %s",
                                profile.name(), symbol),
                            "ERROR"
                        );
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
     * Cancel all existing orders for a symbol to free up held shares before placing a new sell order.
     */
    private void cancelExistingOrders(String profilePrefix, String symbol) {
        try {
            var openOrders = client.getOpenOrders(symbol);
            if (openOrders.isArray()) {
                for (var order : openOrders) {
                    String orderId = order.get("id").asText();
                    client.cancelOrder(orderId);
                    logger.info("{} Canceled existing order {} for {} before exit", profilePrefix, orderId, symbol);
                }
            }
        } catch (Exception e) {
            logger.warn("{} Could not cancel existing orders for {}: {}", profilePrefix, symbol, e.getMessage());
        }
    }

    /**
     * Remove expired entries from stopLossCooldowns map to prevent memory leak.
     * Called at the start of each trading cycle.
     */
    private void cleanupExpiredCooldowns() {
        long now = System.currentTimeMillis();
        stopLossCooldowns.entrySet().removeIf(entry -> entry.getValue() < now);
    }

    /**
     * Arm the re-entry cooldown + last-exit-price gate after a forced exit
     * (time-based, max-loss, stop-loss). Without this, forced exits skip the
     * same-day re-entry protection applied by handleSell, producing churn
     * (e.g. time-exit TLT → immediately re-buy TLT on next cycle's RSI signal).
     */
    private void applyPostExitCooldown(String symbol, double exitPrice, double pnl,
                                       String profilePrefix, String exitKind) {
        long cooldownMs = config.getStopLossCooldownMs();
        stopLossCooldowns.put(symbol, System.currentTimeMillis() + cooldownMs);
        if (pnl < 0) {
            lastExitPrices.put(symbol, exitPrice);
            if (postLossCooldown != null) {
                postLossCooldown.recordLoss(symbol, System.currentTimeMillis());
            }
        } else if (pnl > 0 && postLossCooldown != null) {
            postLossCooldown.recordWin(symbol);
        }
        CircuitBreakerState cb = circuitBreakers.get(brokerName);
        if (cb != null) cb.recordTrade(pnl);
        logger.info("{} {} placed on {}-minute re-entry cooldown after {} exit (pnl=${})",
            profilePrefix, symbol, cooldownMs / 60000, exitKind, String.format("%.2f", pnl));
    }

    /**
     * Reconcile internal portfolio state with actual Alpaca positions.
     * Removes any internal positions that no longer exist on Alpaca.
     * This prevents stale state from blocking new entries after external sells,
     * stop-loss fills, or other exit paths that may miss portfolio cleanup.
     */
    /**
     * Retry protective exits that failed in a previous cycle (e.g., Alpaca API was down).
     * Called every cycle so failed exits are retried every ~10 seconds until they succeed.
     * Only MAIN profile drains the queue to avoid duplicate orders.
     */
    private void drainUrgentExitQueue(String profilePrefix) {
        if (System.currentTimeMillis() < pdtBlockedUntil) return;

        for (String key : new java.util.HashSet<>(urgentExitQueue.keySet())) {
            UrgentExit exit = urgentExitQueue.get(key);
            if (exit == null) continue;
            // Only drain entries owned by THIS broker so multi-broker setups don't cross-fire.
            if (!brokerName.equals(exit.broker())) continue;
            String symbol = exit.symbol();

            long minsWaiting = (System.currentTimeMillis() - exit.firstFailedAt()) / 60000;
            logger.warn("{} 🔄 URGENT EXIT RETRY: {} qty={} reason='{}' ({}m since first fail)",
                profilePrefix, symbol, String.format("%.4f", exit.quantity()), exit.reason(), minsWaiting);

            try {
                // Check if position still exists — native stop may have already filled it.
                // Always use the LIVE qty from broker, not the stale internal qty, to avoid
                // "insufficient qty available" errors when position was partially filled externally.
                var positions = client.getPositions();
                var livePos = positions.stream().filter(p -> p.symbol().equals(symbol)).findFirst();
                if (livePos.isEmpty()) {
                    urgentExitQueue.remove(key);
                    logger.info("{} Urgent exit cleared: {} position no longer on broker", profilePrefix, symbol);
                    continue;
                }
                double liveQty = livePos.get().quantity();
                if (Math.abs(liveQty - exit.quantity()) > 0.001) {
                    logger.warn("{} Urgent exit qty mismatch for {}: internal={} broker={} — using broker qty",
                        profilePrefix, symbol, String.format("%.4f", exit.quantity()), String.format("%.4f", liveQty));
                }

                cancelExistingOrders(profilePrefix, symbol);
                client.placeOrderDirect(symbol, liveQty, "sell", "market", "day", null);

                urgentExitQueue.remove(key);
                pendingExitOrders.put(symbol, System.currentTimeMillis());

                TradingWebSocketHandler.broadcastActivity(
                    String.format("[%s] ✅ URGENT EXIT SUCCEEDED: %s after %dm delay (%s)",
                        profile.name(), symbol, minsWaiting, exit.reason()),
                    "WARN"
                );
                logger.info("{} ✅ Urgent exit succeeded for {} after {}m", profilePrefix, symbol, minsWaiting);

            } catch (PDTRejectedException e) {
                pdtBlockedUntil = System.currentTimeMillis() + millisUntilMarketClose();
                staticPdtBlockedUntil = pdtBlockedUntil;
                logger.warn("{} PDT rejected urgent exit for {} — blocking until market close. Positions protected by native GTC stops.",
                    profilePrefix, symbol);
                TradingWebSocketHandler.broadcastActivity(
                    String.format("[%s] ⛔ PDT LIMIT: Urgent exit for %s blocked — native stops protecting positions until tomorrow.",
                        profile.name(), symbol), "ERROR");
                break;
            } catch (Exception e) {
                logger.warn("{} Urgent exit retry still failing for {}: {}", profilePrefix, symbol, e.getMessage());
                TradingWebSocketHandler.broadcastActivity(
                    String.format("[%s] ⚠️ URGENT EXIT RETRY FAILED: %s (%dm waiting, will retry) — %s",
                        profile.name(), symbol, minsWaiting, e.getMessage()),
                    "ERROR"
                );
            }
        }
    }

    private void reconcilePortfolioWithBroker(String profilePrefix) {
        try {
            var brokerPositions = client.getPositions();
            var brokerSymbols = new java.util.HashSet<String>();
            for (var pos : brokerPositions) {
                brokerSymbols.add(pos.symbol());
            }

            var internalSymbols = portfolio.getActiveStoredSymbols();
            int removed = 0;
            for (String symbol : internalSymbols) {
                if (!brokerSymbols.contains(symbol)) {
                    portfolio.setPosition(symbol, Optional.empty());
                    removed++;
                    logger.info("{} Reconciliation: removed stale position {} (not found at broker)",
                        profilePrefix, symbol);
                }
            }
            if (removed > 0) {
                logger.warn("{} Reconciliation: removed {} stale position(s), active count now {}",
                    profilePrefix, removed, portfolio.getActivePositionCount());
            }

            // Clean up pending exit orders for positions that have been filled (no longer at broker).
            // Use a snapshot of keys to avoid concurrent-modification issues with ConcurrentHashMap.
            // Also clear STALE entries: order placed >20 min ago but position still exists at broker
            // (this happens when orders expire after market close or are rejected by the broker).
            long staleThresholdMs = 20 * 60 * 1000L; // 20 minutes
            long now = System.currentTimeMillis();
            int clearedExits = 0;
            for (String symbol : new java.util.HashSet<>(pendingExitOrders.keySet())) {
                if (!brokerSymbols.contains(symbol)) {
                    pendingExitOrders.remove(symbol);
                    clearedExits++;
                    logger.info("{} Pending exit cleared: {} (position filled/gone from broker)",
                        profilePrefix, symbol);
                } else {
                    // Position still exists — check if our "pending" order is stale
                    long placedAt = pendingExitOrders.getOrDefault(symbol, now);
                    if (now - placedAt > staleThresholdMs) {
                        pendingExitOrders.remove(symbol);
                        clearedExits++;
                        logger.warn("{} Stale pending exit cleared for {} — order is {}min old but position still exists (likely expired/rejected by broker); will re-evaluate next cycle",
                            profilePrefix, symbol, (now - placedAt) / 60000);
                    }
                }
            }

            // Also clear urgent exit queue for THIS broker's entries that are no longer at broker.
            // Filter by exit.broker so multi-broker mode doesn't clear another broker's queue.
            for (String key : new java.util.HashSet<>(urgentExitQueue.keySet())) {
                UrgentExit exit = urgentExitQueue.get(key);
                if (exit == null || !brokerName.equals(exit.broker())) continue;
                if (!brokerSymbols.contains(exit.symbol())) {
                    urgentExitQueue.remove(key);
                    logger.info("{} Urgent exit cleared: {} (position filled/gone from broker)",
                        profilePrefix, exit.symbol());
                }
            }

            // Clean up ghost OPEN DB records for symbols no longer held at this broker.
            // Each broker owns its own rows (filtered by brokerName), so this is safe to
            // run per-broker in multi-broker mode. Min age = 2 minutes to avoid closing
            // records for positions currently being opened this cycle.
            database.closeOrphanedOpenTrades(brokerName, brokerSymbols, 2 * 60 * 1000L);

            // Ensure every live broker position has a matching OPEN record in the trade DB.
            // This fixes the "0 trades in DB" problem after a redeploy wipes the ephemeral DB:
            // positions that were bought in a previous session are re-inserted as OPEN so that
            // when they are eventually sold, closeTrade() can find them and record P&L.
            for (var pos : brokerPositions) {
                String symbol = pos.symbol();
                if (!database.hasOpenTrade(symbol, brokerName)) {
                    try {
                        database.recordTrade(
                            symbol,
                            "recovered",          // strategy = recovered (synced from Alpaca)
                            profile.name(),
                            brokerName,
                            java.time.Instant.now(),
                            pos.avgEntryPrice(),
                            pos.quantity(),
                            pos.avgEntryPrice() * (1.0 - profile.stopLossPercent() / 100.0),
                            pos.avgEntryPrice() * (1.0 + profile.takeProfitPercent() / 100.0)
                        );
                        logger.info("{} DB recovery: inserted OPEN trade for {} (qty={}, entry=${})",
                            profilePrefix, symbol,
                            String.format("%.4f", pos.quantity()),
                            String.format("%.2f", pos.avgEntryPrice()));
                    } catch (Exception e) {
                        logger.warn("{} DB recovery failed for {}: {}", profilePrefix, symbol, e.getMessage());
                    }
                }
            }
            if (clearedExits > 0) {
                logger.info("{} Cleared {} pending exit order(s) after fill confirmation",
                    profilePrefix, clearedExits);
            }

            // Restore in-memory portfolio from open DB records.
            // Critical for brokers where getPositions() returns only FILLED positions:
            // Tradier sandbox never fills limit orders, so getPositions() is empty even
            // though we hold pending orders. Without this, the bot sees qty=0 and re-buys.
            var openDbTrades = database.getOpenTradeRecords(brokerName);
            int restored = 0;
            for (var trade : openDbTrades) {
                if (portfolio.getPosition(trade.symbol()).isEmpty()) {
                    var position = new com.trading.risk.TradePosition(
                        trade.symbol(), trade.entryPrice(), trade.quantity(),
                        trade.stopLoss(), trade.takeProfit(), trade.entryTime(),
                        trade.entryPrice(), 0
                    );
                    portfolio.setPosition(trade.symbol(), java.util.Optional.of(position));
                    restored++;
                    logger.info("{} Memory restore: {} loaded from DB (qty={}, entry=${})",
                        profilePrefix, trade.symbol(),
                        String.format("%.4f", trade.quantity()),
                        String.format("%.2f", trade.entryPrice()));
                }
            }
            if (restored > 0) {
                logger.info("{} Restored {} position(s) from DB into in-memory portfolio", profilePrefix, restored);
            }
        } catch (Exception e) {
            logger.debug("{} Reconciliation check failed: {}", profilePrefix, e.getMessage());
        }
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

            // Guard against zero quantity
            if (qty == 0) {
                logger.debug("{} Skipping zero-quantity position during cleanup: {}", profilePrefix, symbol);
                continue;
            }
            
            logger.info("{} 🧹 Closing weakest position: {} (P&L: ${})", 
                profilePrefix, symbol, String.format("%.2f", pnl));
            
            try {
                cancelExistingOrders(profilePrefix, symbol);
                client.placeOrder(symbol, qty, "sell", "market", "day", null);
                portfolio.setPosition(symbol, Optional.empty());

                TradingWebSocketHandler.broadcastActivity(
                    String.format("[%s] 🧹 CLEANUP: Closed %s (P&L: $%.2f) - reducing to %d positions",
                        profile.name(), symbol, pnl, maxPositions),
                    "INFO"
                );
                
                // Record trade close
                double currentPrice = Math.abs(pos.marketValue() / qty);
                database.closeTrade(symbol, Instant.now(), currentPrice, pnl, brokerName);
                
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
        if (!profile.isMainProfile()) {
            return;
        }

        // PDT circuit breaker: skip sell attempts if Alpaca recently rejected with 403 PDT
        if (System.currentTimeMillis() < pdtBlockedUntil) {
            logger.debug("{} Skipping profit target checks — PDT blocked for {} more seconds",
                profilePrefix, (pdtBlockedUntil - System.currentTimeMillis()) / 1000);
            return;
        }
        
        try {
            var alpacaPositions = client.getPositions();
            logger.info("{} 🔍 Checking {} positions for take-profit/stop-loss", profilePrefix, alpacaPositions.size());
            
            for (var alpacaPos : alpacaPositions) {
                String symbol = alpacaPos.symbol();
                double qty = alpacaPos.quantity();
                double marketValue = alpacaPos.marketValue();
                double entryPrice = alpacaPos.avgEntryPrice();

                // Guard against division by zero
                if (qty == 0) {
                    logger.debug("{} Skipping zero-quantity position: {}", profilePrefix, symbol);
                    continue;
                }

                if (entryPrice == 0) {
                    logger.warn("{} Skipping position with zero entry price: {}", profilePrefix, symbol);
                    continue;
                }

                // Skip dust positions (< $1 market value) to prevent order spam
                if (Math.abs(marketValue) < 1.0) {
                    logger.debug("{} Skipping dust position: {} (value=${})", profilePrefix, symbol, String.format("%.2f", marketValue));
                    continue;
                }

                // Skip symbols with pending exit orders to prevent duplicate sell/closeTrade calls
                // This fixes the bug where 49+ duplicate trade records were created for one position
                if (pendingExitOrders.containsKey(symbol)) {
                    logger.info("{} {} has pending exit order (placed at {}), skipping duplicate check",
                        profilePrefix, symbol,
                        java.time.Instant.ofEpochMilli(pendingExitOrders.get(symbol)));
                    continue;
                }
                
                // Calculate current price from market value
                double currentPrice = Math.abs(marketValue / qty);
                
                // Calculate P&L percentage
            double pnlPercent = ((currentPrice - entryPrice) / entryPrice) * 100.0;
            
            // Check breakeven stop (move stop to entry at +0.3% profit)
            checkBreakevenStop(profilePrefix, symbol, entryPrice, pnlPercent, qty);
            
            // ========== PHASE 2 EXIT STRATEGIES ==========
            // Create temporary position for Phase 2 exit evaluation
            // Use RiskManager to get configured stop/target values
            var riskManager = new RiskManager(latestEquity > 0 ? latestEquity : capital);
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
                    cancelExistingOrders(profilePrefix, symbol);
                    client.placeOrder(symbol, exitQty, "sell", "market", "day", null);

                    double pnlDollars = (currentPrice - entryPrice) * exitQty;
                    database.closeTrade(symbol, Instant.now(), currentPrice, pnlDollars, brokerName);
                    if (!eodDecision.isPartial()) {
                        portfolio.setPosition(symbol, Optional.empty());
                        pendingExitOrders.put(symbol, System.currentTimeMillis());
                    }
                    
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
                
                // Log P&L status for each position
                logger.info("{} {} P&L check: current=+{}% vs target=+{}% (entry=${}, now=${})",
                    profilePrefix, symbol, String.format("%.2f", pnlPercent), 
                    String.format("%.1f", takeProfitPercent), 
                    String.format("%.2f", entryPrice), String.format("%.2f", currentPrice));
                
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

                        // Use smart order type for take-profit exits (limit is fine here)
                        var tpCtx = new OrderContext(
                            symbol, "sell", currentPrice, latestEquity, latestVix, latestRegime,
                            profile.strategyType(), true, false, false
                        );
                        var tpDecision = orderTypeSelector.selectOrderType(tpCtx);

                        logger.info("{} Calling client.placeOrder({}, {}, sell, {}, {}, {})", profilePrefix, symbol, qty,
                            tpDecision.orderType(), tpDecision.timeInForce(), tpDecision.limitPrice());
                        client.placeOrder(symbol, qty, "sell", tpDecision.orderType(), tpDecision.timeInForce(), tpDecision.limitPrice());
                        broadcastOrderData(symbol, qty, "sell", tpDecision.orderType(), "filled", currentPrice);   
                        logger.info("{} ✅ Order API call completed for {}", profilePrefix, symbol);
                        
                        // Calculate actual P&L in dollars
                        double pnlDollars = (currentPrice - entryPrice) * qty;
                        
                        // Record trade close
                        // Set cooldown BEFORE clearing position to close race window between
                        // MAIN and EXPERIMENTAL profiles (position cleared → cooldown set gap = re-buy risk)
                        stopLossCooldowns.put(symbol, System.currentTimeMillis() + config.getStopLossCooldownMs());
                        consecutiveStopLosses.remove(symbol);
                        lastExitPrices.remove(symbol);
                        if (postLossCooldown != null) postLossCooldown.recordWin(symbol);
                        CircuitBreakerState cbTp = circuitBreakers.get(brokerName);
                        if (cbTp != null) cbTp.recordTrade(pnlDollars);

                        database.closeTrade(symbol, Instant.now(), currentPrice, pnlDollars, brokerName);
                        portfolio.setPosition(symbol, Optional.empty());

                        // Mark as pending exit to prevent duplicate sell on next cycle
                        pendingExitOrders.put(symbol, System.currentTimeMillis());

                        TradingWebSocketHandler.broadcastActivity(
                            String.format("[%s] ✅ TAKE PROFIT: %s sold @ $%.2f (+%.2f%%, $%.2f profit)",
                                profile.name(), symbol, currentPrice, pnlPercent, pnlDollars),
                            "SUCCESS"
                        );

                        logger.info("{} ✅ Take profit exit order placed for {}", profilePrefix, symbol);
                    } catch (PDTRejectedException e) {
                        pdtBlockedUntil = System.currentTimeMillis() + millisUntilMarketClose();
                staticPdtBlockedUntil = pdtBlockedUntil;
                        logger.warn("{} PDT rejected by Alpaca for {} — blocking sell attempts until market close",
                            profilePrefix, symbol);
                        continue; // try remaining positions — non-day-trade sells may still succeed
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
                        // Cancel any existing orders first to free up held shares
                        cancelExistingOrders(profilePrefix, symbol);

                        // Use direct order for stop-loss (bypass circuit breaker - critical protective exit)
                        client.placeOrderDirect(symbol, qty, "sell", "market", "day", null);
                        broadcastOrderData(symbol, qty, "sell", "market", "filled", currentPrice);

                        // Calculate actual P&L in dollars
                        double pnlDollars = (currentPrice - entryPrice) * qty;
                        
                        // Record trade close
                        database.closeTrade(symbol, Instant.now(), currentPrice, pnlDollars, brokerName);
                        portfolio.setPosition(symbol, Optional.empty());

                        // Mark as pending exit to prevent duplicate sell on next cycle
                        pendingExitOrders.put(symbol, System.currentTimeMillis());

                        TradingWebSocketHandler.broadcastActivity(
                            String.format("[%s] ⚠️ STOP LOSS: %s sold @ $%.2f (%.2f%%, $%.2f loss)",
                                profile.name(), symbol, currentPrice, pnlPercent, pnlDollars),
                            "WARN"
                        );

                        // ========== SET RE-ENTRY COOLDOWN ==========
                        // Prevent immediate re-buy after stop loss (was causing repeated losses)
                        // Track consecutive stop-losses per symbol for extended cooldown
                        int slCount = consecutiveStopLosses.merge(symbol, 1, Integer::sum);
                        long cooldownMs = config.getStopLossCooldownMs();

                        // Extended cooldown after repeated stop-losses on same symbol
                        if (slCount >= MAX_CONSECUTIVE_SL_BEFORE_EXTENDED_COOLDOWN) {
                            // 4-hour cooldown after 2+ consecutive SLs (prevents MACD churn)
                            cooldownMs = Math.max(cooldownMs, 4 * 60 * 60 * 1000L);
                            logger.warn("{} {} has {} consecutive stop-losses! Extended cooldown: {} hours",
                                profilePrefix, symbol, slCount, cooldownMs / 3600000);

                            TradingWebSocketHandler.broadcastActivity(
                                String.format("[%s] ⚠️ %s: %d consecutive stop-losses - extended %d-hour cooldown",
                                    profile.name(), symbol, slCount, cooldownMs / 3600000),
                                "WARN"
                            );
                        }

                        stopLossCooldowns.put(symbol, System.currentTimeMillis() + cooldownMs);
                        // Record exit price — require price improvement before re-entry
                        lastExitPrices.put(symbol, currentPrice);
                        // Tier 1.1 + 3.10: feed per-symbol post-loss cooldown and session circuit breaker.
                        if (postLossCooldown != null) {
                            postLossCooldown.recordLoss(symbol, System.currentTimeMillis());
                        }
                        CircuitBreakerState cbSl = circuitBreakers.get(brokerName);
                        if (cbSl != null) cbSl.recordTrade(pnlDollars);
                        logger.warn("{} {} placed on {}-minute COOLDOWN after stop loss - no re-entry until {}",
                            profilePrefix, symbol, cooldownMs / 60000,
                            java.time.Instant.ofEpochMilli(System.currentTimeMillis() + cooldownMs));
                        
                        logger.info("{} ✅ Stop loss exit order placed for {}", profilePrefix, symbol);
                    } catch (PDTRejectedException e) {
                        pdtBlockedUntil = System.currentTimeMillis() + millisUntilMarketClose();
                staticPdtBlockedUntil = pdtBlockedUntil;
                        logger.warn("{} PDT rejected by Alpaca for {} — blocking sell attempts until market close",
                            profilePrefix, symbol);
                        continue; // try remaining positions — non-day-trade sells may still succeed
                    } catch (Exception e) {
                        logger.error("{} Failed to place stop loss exit for {}", profilePrefix, symbol, e);
                        urgentExitQueue.put(urgentKey(brokerName, symbol), new UrgentExit(brokerName, symbol, qty,
                            String.format("stop loss (%.1f%%)", pnlPercent), System.currentTimeMillis()));
                        TradingWebSocketHandler.broadcastActivity(
                            String.format("[%s] ⚠️ STOP LOSS EXIT FAILED, QUEUED FOR RETRY: %s",
                                profile.name(), symbol),
                            "ERROR"
                        );
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
        if (!profile.isMainProfile()) {
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

                    // Guard against division by zero
                    if (qty == 0 || entryPrice == 0) {
                        logger.debug("{} Skipping invalid position during EOD exit: {} (qty={}, entry={})",
                            profilePrefix, symbol, qty, entryPrice);
                        continue;
                    }

                    // Calculate current price from market value
                    double currentPrice = Math.abs(marketValue / qty);
                    double pnl = (currentPrice - entryPrice) * qty;
                    double pnlPercent = ((currentPrice - entryPrice) / entryPrice) * 100;
                    
                    logger.warn("{} 📊 EOD EXIT: {} - Qty: {}, Entry: ${}, Current: ${}, P&L: ${} ({}%)",
                        profilePrefix, symbol, qty, entryPrice, currentPrice, pnl, String.format("%.2f", pnlPercent));
                    
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
                        portfolio.setPosition(symbol, Optional.empty());

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
        if (!profile.isMainProfile()) {
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

    /**
     * Returns active cooldowns for the dashboard behavior monitor.
     * Key = symbol, Value = expiry timestamp (epoch ms).
     */
    public static java.util.Map<String, Long> getActiveCooldowns() {
        long now = System.currentTimeMillis();
        var result = new java.util.LinkedHashMap<String, Long>();
        stopLossCooldowns.forEach((symbol, expiresAt) -> {
            if (expiresAt > now) result.put(symbol, expiresAt);
        });
        return result;
    }

    /**
     * Returns consecutive stop-loss counts per symbol for the dashboard.
     */
    public static java.util.Map<String, Integer> getConsecutiveStopLosses() {
        return java.util.Collections.unmodifiableMap(consecutiveStopLosses);
    }

    /** PDT state for dashboard — account-level, updated by the MAIN profile each cycle. */
    public static long getPdtBlockedUntil() { return staticPdtBlockedUntil; }
    public static int getPdtDayTradeCount() { return staticDayTradeCount; }

    /** Blocked buy reasons — symbols that failed entry gates (gap-down, price improvement). */
    public static java.util.Map<String, String> getBlockedBuys() {
        return java.util.Collections.unmodifiableMap(blockedBuys);
    }

    /** Trading halt/gate state snapshots — updated each cycle, for dashboard diagnostics. */
    public static boolean isPortfolioStopLossHaltActive() { return portfolioStopLossHaltActive; }
    public static boolean isMaxDrawdownHaltActive() { return maxDrawdownHaltActive; }
    public static double getLatestVixSnapshot() { return latestVixSnapshot; }
    public static String getLatestRegimeSnapshot() { return latestRegimeSnapshot; }
    public static String getLatestTargetSymbolsSnapshot() { return latestTargetSymbolsSnapshot; }

    /** Per-symbol post-loss cooldown registry (Tier 1.1). Empty map if disabled / no cooldowns. */
    public static java.util.Map<String, Long> getPostLossCooldowns() {
        if (postLossCooldown == null) return java.util.Map.of();
        long now = System.currentTimeMillis();
        var snap = postLossCooldown.snapshot();
        var live = new java.util.LinkedHashMap<String, Long>();
        snap.forEach((sym, exp) -> { if (exp > now) live.put(sym, exp); });
        return live;
    }

    /** Per-broker circuit breaker snapshot for dashboard (Tier 3.10). */
    public static java.util.Map<String, java.util.Map<String, Object>> getCircuitBreakerSnapshot() {
        var out = new java.util.LinkedHashMap<String, java.util.Map<String, Object>>();
        circuitBreakers.forEach((broker, cb) -> {
            var info = new java.util.HashMap<String, Object>();
            info.put("tripped", cb.shouldHaltEntries());
            var reason = cb.tripReason();
            info.put("tripReason", reason == null ? null : reason.name());
            info.put("consecutiveLosses", cb.getConsecutiveLosses());
            info.put("sessionDrawdownPct", cb.getSessionDrawdownPct());
            out.put(broker, info);
        });
        return out;
    }

    /** True iff any broker's circuit breaker is currently tripped. */
    public static boolean isAnyCircuitBreakerTripped() {
        return circuitBreakers.values().stream().anyMatch(CircuitBreakerState::shouldHaltEntries);
    }

    /**
     * Returns milliseconds until the PDT day-trade count resets.
     * - During market hours (before 4PM ET): block until today's close.
     * - After market close: block only until next market OPEN (9:30 AM ET next weekday).
     *   This prevents the block from lasting an entire extra trading day when a PDT
     *   rejection occurs after hours (e.g. during an urgent-exit retry loop post-deploy).
     */
    private long millisUntilMarketClose() {
        var NY = java.time.ZoneId.of("America/New_York");
        var now = java.time.ZonedDateTime.now(NY);
        var closeToday = now.toLocalDate().atTime(16, 0).atZone(NY);
        if (now.isBefore(closeToday)) {
            // Still in trading day — block until today's close
            return java.time.Duration.between(now, closeToday).toMillis();
        }
        // After close — block only until next market open (9:30 AM next weekday)
        var nextOpen = closeToday.toLocalDate().plusDays(1).atTime(9, 30).atZone(NY);
        while (nextOpen.getDayOfWeek() == java.time.DayOfWeek.SATURDAY ||
               nextOpen.getDayOfWeek() == java.time.DayOfWeek.SUNDAY) {
            nextOpen = nextOpen.plusDays(1);
        }
        return java.time.Duration.between(now, nextOpen).toMillis();
    }
}
