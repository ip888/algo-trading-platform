package com.trading.portfolio;

import com.trading.ai.AnomalyDetector;
import com.trading.ai.RiskPredictor;
import com.trading.ai.SentimentAnalyzer;
import com.trading.ai.SignalPredictor;
import com.trading.api.ResilientBrokerClient;
import com.trading.api.PDTRejectedException;
import com.trading.analysis.MarketAnalyzer;
import com.trading.config.Config;
import com.trading.earnings.EarningsCalendarService;
import com.trading.filters.MarketHoursFilter;
import com.trading.filters.VolatilityFilter;
import com.trading.persistence.TradeDatabase;
import com.trading.protection.PDTProtection;
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
import com.trading.testing.TestModeSimulator;
import com.trading.websocket.TradingWebSocketHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
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
    // Full BUY sizing pipeline — extracted from handleBuy() 2026-08-30 (see its class Javadoc).
    private final PositionSizer positionSizer;
    // Every position-exit path (risk exits, profit targets, trailing stop, EOD flatten, urgent
    // retry queue, excess-position cleanup) — extracted from handleSell()/checkAllPositionsFor*()
    // 2026-08-30 (see its class Javadoc for what stayed here and why).
    private final ExitEvaluator exitEvaluator;
    // Every pre-sizing BUY gate (cooldowns, PDT, VIX/regime guards, AI filters, etc.) —
    // extracted from handleBuy() 2026-08-30 (see its class Javadoc for what stayed here and why).
    private final EntryEvaluator entryEvaluator;

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

    // ════════════════════════════════════════════════════════════════════════════════════
    // RISK/COORDINATION STATE — formerly ~20 `static` fields directly on this class,
    // converted to instance state 2026-08-30, then extracted into RiskGate the same day as
    // the first step of splitting this file up. See RiskGate's class Javadoc for the full
    // "why static, why not anymore, why a separate class" history — repeating it at every
    // call site isn't useful, but the short version: that state used to coordinate two
    // concurrent ProfileManager instances (MAIN + EXPERIMENTAL), this deployment runs
    // exactly one, and keeping the coordination state directly on this god-object made it
    // easy to lose track of (see the correlation-cap stub found during the same session's
    // audit — this class had already grown too large to read in one pass).
    // ════════════════════════════════════════════════════════════════════════════════════
    private final RiskGate riskGate = new RiskGate();

    // Scalp override: non-null while handleBuy is executing for a ScalpBuy signal.
    // Carries [stopLossPercent, takeProfitPercent] to bypass the profile's swing-trade targets.
    // Cleared in a finally block — never bleeds into subsequent normal entries.
    private Double[] scalpOverrides = null;

    public java.util.Map<String, String> getUrgentExitQueue() {
        return riskGate.urgentExitQueueForDashboard();
    }

    // Guard against silently reintroducing multi-instance state sharing (see RiskGate's class
    // Javadoc). Not a hard limit — the bot can still technically run several instances — but a
    // loud, impossible-to-miss log line beats a silent behavior change if BROKERS ever grows a
    // second entry again.
    private static final java.util.concurrent.atomic.AtomicInteger activeInstanceCount =
        new java.util.concurrent.atomic.AtomicInteger(0);

    // Current market state (updated each cycle, used by profit target checks)
    private volatile double latestVix = 15.0;
    private volatile MarketRegime latestRegime = MarketRegime.RANGE_BOUND;
    private volatile double latestEquity = 0.0;

    // Hard cap: at most 1 open DB entry per symbol per broker.
    // Prevents adding to a losing position when the same MACD signal fires again
    // (IWM×2 and SMH×2 on Jul 13 2026 each doubled the loss on declining positions).
    private static final int MAX_OPEN_ENTRIES_PER_SYMBOL = 1;

    // PDT circuit breaker: skip sell attempts for the rest of the cycle after a 403 rejection.
    // Lives on RiskGate (staticPdtBlockedUntil) since 2026-08-30 — see ExitEvaluator's Javadoc —
    // so this ProfileManager's one remaining read/write site and ExitEvaluator's several stay in sync.

    private volatile boolean running = true;
    private final String brokerName;

    // Bearish regime persistence: tracks when STRONG_BEAR/WEAK_BEAR was first confirmed
    // during market hours. Inverse ETF entries are blocked until the regime has been bearish
    // for at least BEAR_ENTRY_PERSISTENCE_MINUTES continuous market-hours minutes.
    // Resets to null whenever regime turns non-bearish.
    private java.time.Instant bearishRegimeMarketStart = null;

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

        // See the "INSTANCE STATE" comment block above the field declarations: the cooldown/
        // held-symbol/circuit-breaker state above used to be static specifically to coordinate
        // multiple concurrent ProfileManager instances, and was converted to instance state on
        // 2026-08-30 because this deployment only ever runs one. If that assumption changes
        // (e.g. BROKERS gains a second entry), each instance now tracks its own state
        // independently — no cross-instance double-buy protection. Fail loud, not silent.
        int nowActive = activeInstanceCount.incrementAndGet();
        if (nowActive > 1) {
            logger.error("⚠️⚠️⚠️ {} ProfileManager instances now active — cross-profile state " +
                "sharing (cooldowns, held symbols, consecutive-loss tracking) was REMOVED in the " +
                "2026-08-30 refactor. Two instances can now independently buy the same symbol with " +
                "no coordination. Do not run multi-broker/multi-profile until this is redesigned " +
                "with an explicit shared coordinator — see ProfileManager.java's INSTANCE STATE " +
                "comment block.", nowActive);
        }

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
        
        // Create advanced position sizer (feeds RiskManager's base Kelly sizing — distinct from
        // the com.trading.portfolio.PositionSizer field below, which is the full handleBuy()
        // sizing pipeline that itself calls a fresh AdvancedPositionSizer for ATR-vol-targeting).
        var advancedPositionSizerForRiskManager = new com.trading.risk.AdvancedPositionSizer(config, database);
        advancedPositionSizerForRiskManager.setAdaptiveManager(adaptiveManager);

        this.riskManager = new RiskManager(capital, advancedPositionSizerForRiskManager);
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
        // Restore trailing-stop state for any positions still open at broker.
        // Without this, a redeploy mid-session resets the multi-level trail to level 0,
        // reverting the stop to entry-time distance and giving back protected gains.
        try {
            var trailingStates = database.loadBotStateWithPrefix("trailing:");
            for (var entry : trailingStates.entrySet()) {
                // key format: "trailing:SYMBOL:broker"
                String[] parts = entry.getKey().split(":", 3);
                if (parts.length == 3 && brokerName.equalsIgnoreCase(parts[2])) {
                    trailingTargetManager.restoreFromEncoded(parts[1], entry.getValue());
                }
            }
        } catch (Exception e) {
            logger.warn("[{}] Failed to restore trailing-target states: {}", profile.name(), e.getMessage());
        }
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

        // Full BUY sizing pipeline, extracted from handleBuy() 2026-08-30 — see PositionSizer's
        // class Javadoc for why its result is a sealed type rather than a plain double.
        // getMaxCorrelation is passed by reference rather than duplicated so both this and the
        // separate Tier 2.4 entry-cap gate keep using the same CorrelationCalculator instance.
        this.positionSizer = new PositionSizer(config, database, client, riskManager, mlEntryScorer,
            adaptivePositionSizer, adaptiveManager, anomalyDetector, riskPredictor, sentimentAnalyzer,
            portfolio, riskGate, profile, this::getMaxCorrelation);

        // Every position-exit path, extracted from handleSell()/checkAllPositionsFor*() 2026-08-30
        // — see ExitEvaluator's class Javadoc for what's a same-shape relocation here vs. what
        // deliberately stayed on ProfileManager. latestVix/latestRegime/latestEquity are passed as
        // suppliers (not captured values) so ExitEvaluator always reads this profile's current
        // per-cycle snapshot, exactly like the original code reading the volatile fields directly.
        this.exitEvaluator = new ExitEvaluator(profile, brokerName, config, database, client, portfolio,
            riskGate, exitStrategyManager, phase2ExitStrategies, timeDecayExitManager, trailingTargetManager,
            orderTypeSelector, testSimulator, configSelfHealer, capital,
            () -> latestVix, () -> latestRegime, () -> latestEquity, this::updateDailyPnL);

        // Every pre-sizing BUY gate, extracted from handleBuy() 2026-08-30 — see EntryEvaluator's
        // class Javadoc. todayPnL/bearishRegimeMarketStart are passed as suppliers (not captured
        // values) since both are mutated elsewhere (updateDailyPnL, the regime-tracking block in
        // runTradingCycle) after this constructor runs — a snapshot here would go stale.
        this.entryEvaluator = new EntryEvaluator(profile, config, database, client, portfolio, riskGate,
            pdtProtection, marketHoursFilter, correlationCalculator, sentimentAnalyzer, signalPredictor,
            marketBreadthAnalyzer, mlEntryScorer, volumeProfileAnalyzer,
            () -> todayPnL, () -> bearishRegimeMarketStart);

        // Lazy-init, guarded by a null-check + synchronized block — a leftover of the old
        // cross-profile-singleton pattern from when multiple ProfileManager instances could
        // race to create these. Harmless and still correct with a single instance (the
        // double-checked-locking degrades to "just initialize it once"), so left as-is rather
        // than risk changing behavior for a cosmetic simplification.
        if (riskGate.postLossCooldown() == null) {
            synchronized (ProfileManager.class) {
                if (riskGate.postLossCooldown() == null) {
                    riskGate.setPostLossCooldown(new PostLossCooldownTracker(
                        config.getPostLossCooldownMs(),
                        config.getPostLossCooldownExtendedMs(),
                        2));
                    // Restore cooldown state from DB so restarts don't silently clear active cooldowns
                    restorePostLossCooldownsFromDb(riskGate.postLossCooldown());
                }
            }
        }
        if (riskGate.earningsCalendar() == null) {
            synchronized (ProfileManager.class) {
                if (riskGate.earningsCalendar() == null) {
                    String key = System.getenv("ALPHA_VANTAGE_API_KEY");
                    riskGate.setEarningsCalendar(new EarningsCalendarService(
                        key == null ? "" : key,
                        config.getEarningsCacheTtlMs()));
                }
            }
        }
        riskGate.circuitBreakers().computeIfAbsent(brokerName, b -> new CircuitBreakerState(
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
        
        // Log last Claude end-of-session review suggestion so operator sees it on startup
        String claudeKey = System.getenv("CLAUDE_API_KEY");
        if (claudeKey != null && !claudeKey.isBlank()) {
            new com.trading.ai.ClaudeSessionReviewer(database, claudeKey).logLastReviewIfPresent();
        }

        // Restore today's realized P&L from DB so the daily loss circuit breaker
        // survives restarts. Without this, redeploying mid-day resets todayPnL to 0
        // and the bot can re-enter after hitting the daily loss limit.
        try {
            this.todayPnL = database.getTodayPnL();
            if (this.todayPnL != 0.0) {
                logger.info("[{}] Restored today's P&L from DB: ${}", profile.name(), String.format("%.2f", this.todayPnL));
            }
        } catch (Exception e) {
            logger.warn("[{}] Failed to restore todayPnL from DB: {}", profile.name(), e.getMessage());
        }

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
        if (!riskGate.urgentExitQueue().isEmpty()) {
            if (marketHoursFilter.isMarketOpen()) {
                exitEvaluator.drainUrgentExitQueue(profilePrefix);
            } else {
                logger.debug("{} Urgent exit queue has {} symbol(s) — holding until market open",
                    profilePrefix, riskGate.urgentExitQueue().size());
            }
        }

        // Get current market regime (uses advanced detection if enabled)
        MarketRegime regime;
        List<String> targetSymbols;
        double currentVix;
        
        com.trading.analysis.MarketRegimeDetector.MarketRegimeAnalysis lastRegimeAnalysis = null;
        if (config.isRegimeDetectionEnabled()) {
            var regimeAnalysis = regimeDetector.getCurrentRegime();
            lastRegimeAnalysis = regimeAnalysis;
            regime = regimeAnalysis.regime();
            currentVix = regimeAnalysis.vix();

            // ── Regime confidence gate ─────────────────────────────────────────
            // If the detector isn't confident, fall back to the safer RANGE_BOUND.
            // This protects against low-quality signals (stale overnight data, few
            // bars, all indicators in conflict).
            double minConfidence = config.getMinRegimeConfidence();
            if (regimeAnalysis.confidence() < minConfidence && regime != MarketRegime.RANGE_BOUND) {
                logger.info("{} Regime confidence {}% < minimum {}% — using RANGE_BOUND fallback (was {})",
                    profilePrefix,
                    String.format("%.0f", regimeAnalysis.confidence() * 100),
                    String.format("%.0f", minConfidence * 100),
                    regime);
                regime = MarketRegime.RANGE_BOUND;
            }

            // ── Bearish regime persistence clock ──────────────────────────────
            // Track how long we've been in a bearish regime during market hours.
            // Inverse ETF entry guard in handleBuy() reads bearishRegimeMarketStart.
            boolean isBearishRegime = (regime == MarketRegime.STRONG_BEAR || regime == MarketRegime.WEAK_BEAR);
            if (isBearishRegime && marketHoursFilter.isMarketOpen()) {
                if (bearishRegimeMarketStart == null) {
                    bearishRegimeMarketStart = java.time.Instant.now();
                    logger.info("{} 🐻 Bearish regime ({}) confirmed during market hours — " +
                        "inverse ETF entries blocked for {}min persistence window",
                        profilePrefix, regime, config.getBearEntryPersistenceMinutes());
                }
            } else if (!isBearishRegime) {
                if (bearishRegimeMarketStart != null) {
                    logger.info("{} 🔄 Regime flipped {} → {} — resetting bearish persistence clock",
                        profilePrefix,
                        isBearishRegime ? regime : "bearish",
                        regime);
                }
                bearishRegimeMarketStart = null;
            }

            // VIX spike override: bypass the cached regime when VIX > 25.
            // The cache can be up to REGIME_UPDATE_INTERVAL minutes stale — a spike
            // from 18 → 28 mid-session would be invisible until the next cache refresh.
            if (currentVix > 25.0 && regime != MarketRegime.HIGH_VOLATILITY) {
                logger.warn("{} ⚠️ VIX spike override: {} → HIGH_VOLATILITY (VIX={})",
                    profilePrefix, regime, String.format("%.1f", currentVix));
                regime = MarketRegime.HIGH_VOLATILITY;
            }

            targetSymbols = symbolSelector.selectSymbols(regime);
            logger.debug("{} {}", profilePrefix, regimeAnalysis.getSummary());

            // Feed real advance/decline breadth from the regime detector into the breadth analyzer.
            // Previously MarketBreadthAnalyzer used random simulation (50-80%) — now it reflects
            // actual 5-day sector breadth (SPY/QQQ/IWM/DIA/XLK/XLF/XLE/XLV) so the Phase 3
            // breadth filter is meaningful rather than always passing.
            marketBreadthAnalyzer.updateBreadth(regimeAnalysis.breadth().strength());
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
        if (regime != this.latestRegime) {
            logger.info("[REGIME_CHANGE] {} {}→{} vix={}", profile.name(),
                this.latestRegime != null ? this.latestRegime.name() : "INIT", regime.name(),
                String.format("%.1f", currentVix));
            var ra = lastRegimeAnalysis;
            database.saveRegimeLog(
                regime.name(),
                ra != null ? ra.confidence() : 1.0,
                ra != null ? ra.breadth().strength() : 0.0,
                ra != null ? ra.trend().direction().name() : "UNKNOWN",
                ra != null ? ra.volume().trend().name() : "UNKNOWN");
        }
        this.latestRegime = regime;
        this.latestEquity = equity;

        // Persist regime streak — used by position sizing to reduce exposure after
        // 3+ consecutive bearish days (WEAK_BEAR or STRONG_BEAR).
        if (regime != null) {
            database.updateRegimePersistence(regime.name());
        }

        // Update static snapshots for dashboard visibility
        riskGate.setLatestVixSnapshot(currentVix);
        riskGate.setLatestRegimeSnapshot(regime != null ? regime.name() : "UNKNOWN");
        riskGate.setLatestTargetSymbolsSnapshot(String.join(",", targetSymbols));
        
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
            exitEvaluator.checkAllPositionsForRiskExits(profilePrefix);

            // ========== CHECK ALL POSITIONS FOR PROFIT TARGETS ==========
            // CRITICAL: Check ALL positions for take-profit/stop-loss, not just current targets
            // This ensures positions from previous regimes are still monitored for exits
            // Runs before portfolio halt to guarantee protective exits always execute
            exitEvaluator.checkAllPositionsForProfitTargets(profilePrefix);
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
            CircuitBreakerState cb = riskGate.circuitBreakers().get(brokerName);
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
            riskGate.setPortfolioStopLossHaltActive(true);
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
            riskGate.setPortfolioStopLossHaltActive(false);
        }

        // ========== CLEANUP EXCESS POSITIONS ==========
        // Auto-close worst positions if over limit
        // Only during market hours — sell orders can't fill pre-market
        if (marketHoursFilter.isMarketOpen()) {
            exitEvaluator.cleanupExcessPositions(profilePrefix);
        }

        // ========== END OF DAY EXIT (3:30 PM) ==========
        // Close all positions before market close to avoid overnight risk
        // Only during market hours — sell orders can't fill pre-market
        if (config.isEodExitEnabled() && marketHoursFilter.isMarketOpen()) {
            exitEvaluator.checkAndExecuteEodExit(profilePrefix);
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
            riskGate.setMaxDrawdownHaltActive(true);
            logger.error("{} HALTING TRADING: Max drawdown exceeded!", profilePrefix);
            return;
        } else {
            riskGate.setMaxDrawdownHaltActive(false);
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
                riskGate.setStaticPdtBlockedUntil(System.currentTimeMillis() + exitEvaluator.millisUntilMarketClose());
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
        
        // Scalp-priority scan: always evaluate high-liquidity symbols for scalp every cycle,
        // even when not in the current main batch rotation.
        if (config.isScalpStrategyEnabled()) {
            runScalpPriorityScan(symbolsToProcess, equity, buyingPower, regime, currentVix, profilePrefix);
        }

        // Log portfolio status
        logger.info("{} Portfolio: {} active positions",
            profilePrefix, portfolio.getActivePositionCount());

        // Evaluate and adjust parameters autonomously
        adaptiveManager.evaluateAndAdjust();
        
        // Analyze correlation of OPEN positions only (not full watchlist)
        if (portfolio.getActivePositionCount() >= 2) {
            try {
                var openSymbols = new java.util.ArrayList<>(portfolio.getActiveStoredSymbols());
                var correlation = correlationCalculator.analyzePortfolio(openSymbols);
                if (!correlation.highCorrelations().isEmpty()) {
                    logger.debug("{} Open position correlation: diversification={}", profilePrefix,
                        String.format("%.2f", correlation.diversificationScore()));
                }
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

        // ========== SYMBOL BLACKLIST ==========
        // Hard block for IPOs, secondary offerings, or symbols with broker-imposed restrictions
        // (e.g. fractional trading disabled, no margin, no price history). Set SYMBOL_BLACKOUT=X,Y in config.
        var blacklist = config.getSymbolBlacklist();
        if (!blacklist.isEmpty() && blacklist.contains(symbol.toUpperCase())) {
            logger.info("{} {} BLACKLISTED — skipping (reason: SYMBOL_BLACKOUT config)", profilePrefix, symbol);
            riskGate.blockedBuys().put(symbol, "blacklisted (SYMBOL_BLACKOUT)");
            return;
        }

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
                exitEvaluator.cancelExistingOrders(profilePrefix, symbol);

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
                        database.closeTrade(symbol, Instant.now(), currentPrice, exitPnl, brokerName, "max_loss");
                        TradingWebSocketHandler.broadcastActivity(
                            String.format("[%s] MAX LOSS EXIT: %s (%.2f%% loss) [attempt %d]",
                                profile.name(), symbol, lossPercent, attempt+1),
                            "WARN"
                        );
                        portfolio.setPosition(symbol, Optional.empty());
                        exitEvaluator.clearPositionTracking(symbol);
                        exitEvaluator.applyPostExitCooldown(symbol, currentPrice, exitPnl, profilePrefix, "max loss");
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
                        exitEvaluator.cancelExistingOrders(profilePrefix, symbol);
                        client.placeOrder(symbol, qty, "sell", "market", "day", null);
                        logger.info("{} ✅ Time-based exit order placed for {}", profilePrefix, symbol);

                        // Record trade close
                        database.closeTrade(symbol, Instant.now(), currentPrice, pnl, brokerName, "max_hold_time");
                        portfolio.setPosition(symbol, Optional.empty());
                        exitEvaluator.clearPositionTracking(symbol);
                        exitEvaluator.applyPostExitCooldown(symbol, currentPrice, pnl, profilePrefix, "time-based");

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
        
        // Give StrategyManager current VIX so it can adjust thresholds (e.g. inverse ETF
        // MACD threshold scales with volatility — lower in calm WEAK_BEAR markets).
        strategyManager.setLatestVix(currentVix);

        // Evaluate strategy using regime
        var signal = strategyManager.evaluate(symbol, currentPrice, qty, regime);
        
        // Handle signal
        if (signal instanceof TradingSignal.ScalpBuy scalpBuy && qty == 0) {
            // Scalp entry: tight SL/TP carried in the signal itself.
            // Bypasses add-to-position logic — scalps are flat-in / flat-out only.
            if (database.hasOpenTrade(symbol, brokerName)) {
                logger.debug("{} {} skipping SCALP BUY — open DB record exists", profilePrefix, symbol);
            } else {
                // Reset static counter if day has rolled over
                java.time.LocalDate today = java.time.LocalDate.now();
                if (!today.equals(riskGate.scalpCountDate())) {
                    riskGate.staticScalpDailyCount().set(0);
                    riskGate.setScalpCountDate(today);
                }
                riskGate.staticScalpDailyCount().incrementAndGet();
                scalpOverrides = new Double[]{scalpBuy.stopLossPercent(), scalpBuy.takeProfitPercent()};
                try {
                    handleBuy(symbol, currentPrice, equity, buyingPower, currentVix, regime, profilePrefix);
                } finally {
                    scalpOverrides = null;
                }
            }
        } else if (signal instanceof TradingSignal.Buy buy) {
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
                if (currentPosition.isEmpty()) {
                    // Broker reports position but portfolio has no record: stale state after restart.
                    // checkAllPositionsForRiskExits handles untracked positions for loss protection.
                    logger.warn("{} {} SELL skipped — position not tracked (post-restart state)", profilePrefix, symbol);
                    return;
                }
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
                    exitEvaluator.handleSell(symbol, currentPrice, position, profilePrefix);
                    return;
                }

                // ========== FIX 1: PROFIT GATE ON MOMENTUM SELL ==========
                // When position is profitable but below the breakeven trigger, the trailing stop
                // is already managing the exit. Momentum SELL at this point exits winners too early
                // (avg +0.22% vs +0.58% when trailing stop manages). Suppress SELL and let the
                // trailing stop handle the exit once the position matures past the breakeven level.
                double breakevenTriggerPct = config.getBreakevenTriggerPercent();
                if (lossPercent > 0 && lossPercent < breakevenTriggerPct) {
                    logger.info("{} {}: Momentum SELL suppressed — +{}% (below breakeven gate {}%), trailing stop managing",
                        profilePrefix, symbol,
                        String.format("%.2f", lossPercent),
                        String.format("%.1f", breakevenTriggerPct));
                    exitEvaluator.updateTrailingStop(symbol, currentPrice, position, profilePrefix);
                    return;
                }

                // ========== FIX 2: MIN-HOLD BYPASS FOR LOSING POSITIONS ==========
                // The 1-hour min-hold is for PDT compliance on profitable/flat positions.
                // When a position is clearly losing (-0.25%), holding for 1 hour compounds the
                // damage — let momentum SELL cut losses immediately without waiting for min-hold.
                if (lossPercent <= -0.25) {
                    logger.info("{} {}: Early loss cut — {}%, bypassing min-hold restriction",
                        profilePrefix, symbol, String.format("%.2f", lossPercent));
                    exitEvaluator.handleSell(symbol, currentPrice, position, profilePrefix);
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
                exitEvaluator.handleSell(symbol, currentPrice, currentPosition.get(), profilePrefix);
            }
        } else {
            // HOLD - update trailing stop if position exists
            if (currentPosition.isPresent()) {
                exitEvaluator.updateTrailingStop(symbol, currentPrice, currentPosition.get(), profilePrefix);
            }
        }
    }
    
    /**
     * Lightweight scalp-only pass over high-liquidity symbols not in the current main batch.
     * Calls ScalpStrategy directly — skips the full MTF/regime analysis — so it's fast enough
     * to run on 5-9 additional symbols every 20-second cycle without rate-limit pressure.
     */
    private void runScalpPriorityScan(Set<String> alreadyProcessed, double equity, double buyingPower,
                                      MarketRegime regime, double currentVix, String profilePrefix) {
        if (regime == com.trading.analysis.MarketRegimeDetector.MarketRegime.STRONG_BEAR
                || regime == com.trading.analysis.MarketRegimeDetector.MarketRegime.HIGH_VOLATILITY) {
            return;
        }
        int maxPositions = config.getMaxPositionsAtOnce();
        for (String symbol : config.getScalpSymbols()) {
            if (alreadyProcessed.contains(symbol)) continue; // already scanned this cycle
            if (portfolio.getActivePositionCount() >= maxPositions) break; // at capacity
            try {
                var bar = client.getLatestBar(symbol);
                if (bar.isEmpty()) continue;
                double price = bar.get().close();
                double qty = portfolio.getPosition(symbol).map(com.trading.risk.TradePosition::quantity).orElse(0.0);
                if (qty > 0) continue; // already have position in this symbol

                var signal = strategyManager.evaluateScalpOnly(symbol, price, qty);
                if (!(signal instanceof TradingSignal.ScalpBuy scalpBuy)) continue;
                if (database.hasOpenTrade(symbol, brokerName)) continue;

                java.time.LocalDate today = java.time.LocalDate.now();
                if (!today.equals(riskGate.scalpCountDate())) {
                    riskGate.staticScalpDailyCount().set(0);
                    riskGate.setScalpCountDate(today);
                }
                scalpOverrides = new Double[]{scalpBuy.stopLossPercent(), scalpBuy.takeProfitPercent()};
                try {
                    handleBuy(symbol, price, equity, buyingPower, currentVix, regime, profilePrefix);
                } finally {
                    scalpOverrides = null;
                }
            } catch (Exception e) {
                logger.debug("{} Scalp priority check failed for {}: {}", profilePrefix, symbol, e.getMessage());
            }
        }
    }

    private void handleBuy(String symbol, double currentPrice, double equity,
                          double buyingPower, double currentVix, MarketRegime regime, String profilePrefix) throws Exception {

        // ========== DOUBLE-ENTRY RACE GUARD (cross-profile safe) ==========
        // putIfAbsent is atomic: whichever profile wins the CAS owns the entry, the other returns.
        // This closes the race where MAIN and EXPERIMENTAL both pass a containsKey check at the
        // same millisecond before either reaches the later put() — causing duplicate entries on
        // the same symbol and doubling loss exposure on bad trades.
        String pendingBuyKey = brokerName + ":" + symbol;
        Long existingClaim = riskGate.pendingBuySymbols().putIfAbsent(pendingBuyKey, System.currentTimeMillis());
        if (existingClaim != null) {
            logger.debug("{} {} BUY skipped — buy already in flight or claimed by sibling profile ({}s ago)",
                profilePrefix, symbol,
                (System.currentTimeMillis() - existingClaim) / 1000);
            return;
        }

        // All code after this point owns the pendingBuyKey claim — ensure it is always released.
        // Without try/finally, early returns (buying power) and throws (Alpaca errors) leave the
        // key stuck for RiskGate.PENDING_BUY_TTL_MS (5 min), silently blocking all buys on this symbol.
        try {

        // Every pre-sizing gate (cooldowns, PDT, VIX/regime guards, AI filters, etc.) lives in
        // EntryEvaluator now — see its class Javadoc. Each gate's `return;` became a Blocked
        // result; handle that here at exactly the point the old inline code would have returned.
        var entryResult = entryEvaluator.evaluate(symbol, currentPrice, equity, currentVix, regime,
            scalpOverrides, profilePrefix);
        if (entryResult instanceof EntryEvaluator.Blocked blocked) {
            logger.debug("{} {}: entry blocked by EntryEvaluator: {}", profilePrefix, symbol, blocked.reason());
            return;
        }

        // Full sizing pipeline (Kelly base → bear-streak penalty → Phase 3 adaptive → buying-power
        // cap → AI anomaly/risk gates → ATR stop/TP → ATR vol-targeted sizing → hard notional cap
        // → afternoon haircut) lives in PositionSizer now — see its class Javadoc. The two AI gates
        // that used to `return;` straight out of this method now surface as PositionSizer.Halt;
        // handle that here at exactly the point the old inline code would have returned.
        var sizing = positionSizer.evaluate(symbol, currentPrice, equity, buyingPower, currentVix,
            regime, scalpOverrides, profilePrefix);
        if (sizing instanceof PositionSizer.Halt halt) {
            logger.debug("{} {}: entry halted by PositionSizer: {}", profilePrefix, symbol, halt.reason());
            return;
        }
        var sized = (PositionSizer.Sized) sizing;
        double positionSize = sized.quantity();
        double stopLoss = sized.stopLoss();
        double takeProfit = sized.takeProfit();

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
                profile.strategyType(), false, false, false, positionSize
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
        
        // Update portfolio and cross-profile tracker atomically
        portfolio.setPosition(symbol, Optional.of(newPosition));
        riskGate.globalHeldSymbols().put(symbol, profilePrefix);
        if (scalpOverrides != null) {
            riskGate.scalpHeldSymbols().add(symbol);
        }
        riskGate.setLastEntryEpochMs(System.currentTimeMillis()); // arm entry stagger for next 90s

        // Record trade with full market context for adaptive learning (regime, VIX, breadth).
        // Re-read breadth here rather than threading it out of EntryEvaluator's market-breadth
        // gate: marketBreadthAnalyzer caches internally, so this is a cheap re-read, not a
        // second computation, and keeps EntryEvaluator's Result free of this method's own concerns.
        String entryStrategyTag = scalpOverrides != null ? "SCALP" : profile.strategyType();
        database.recordTradeWithContext(
            symbol,
            entryStrategyTag,
            profile.name(),
            brokerName,
            newPosition.entryTime(),
            currentPrice,
            positionSize,
            stopLoss,
            takeProfit,
            regime != null ? regime.name() : "UNKNOWN",
            currentVix,
            marketBreadthAnalyzer.getCurrentBreadth()
        );
        // Stamp entry_reason: strategy tag + VIX + regime so post-analysis can filter by conditions.
        String entryReason = String.format("%s | regime=%s vix=%.1f sl=%.2f tp=%.2f",
            entryStrategyTag, regime != null ? regime.name() : "UNKNOWN", currentVix, stopLoss, takeProfit);
        database.setEntryReason(symbol, brokerName, entryReason);
        logger.info("[TRADE_OPEN] {} {} qty={} entry=${} sl=${} tp=${} reason={}",
            profile.name(), symbol, String.format("%.3f", positionSize),
            String.format("%.2f", currentPrice), String.format("%.2f", stopLoss),
            String.format("%.2f", takeProfit), entryStrategyTag);
        // Broadcast trade event
        TradingWebSocketHandler.broadcastTradeEvent(
            symbol, "BUY", currentPrice, positionSize,
            profile.name() + " Profile"
        );

        } finally {
            // Always release the in-flight lock — covers success, early returns, and thrown exceptions.
            riskGate.pendingBuySymbols().remove(pendingBuyKey);
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
     * Remove expired entries from riskGate.stopLossCooldowns() map to prevent memory leak.
     * Called at the start of each trading cycle.
     */
    private void cleanupExpiredCooldowns() {
        long now = System.currentTimeMillis();
        riskGate.stopLossCooldowns().entrySet().removeIf(entry -> entry.getValue() < now);
        riskGate.pendingBuySymbols().entrySet().removeIf(entry -> now - entry.getValue() > RiskGate.PENDING_BUY_TTL_MS);
    }

    /**
     * Reconcile internal portfolio state with actual Alpaca positions.
     * Removes any internal positions that no longer exist on Alpaca.
     * This prevents stale state from blocking new entries after external sells,
     * stop-loss fills, or other exit paths that may miss portfolio cleanup.
     */
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
                    // Arm the re-entry cooldown BEFORE clearing the position.
                    // Broker-side native stop/TP fills bypass handleSell() and handleTakeProfit(),
                    // so the cooldown would never be set — allowing immediate re-entry on the next
                    // cycle. This is the root cause of rapid same-symbol re-entries (e.g. NVDA
                    // entered twice within 9 minutes on July 6, 2026).
                    riskGate.stopLossCooldowns().put(symbol, System.currentTimeMillis() + config.getStopLossCooldownMs());
                    // Cancel any native stop/trailing-stop orders that survived the broker-side fill.
                    // Without this, a native stop placed by updateTrailingStop() can trigger on a flat
                    // account and create an accidental short position.
                    exitEvaluator.cancelExistingOrders(profilePrefix, symbol);
                    portfolio.setPosition(symbol, Optional.empty());
                    exitEvaluator.clearPositionTracking(symbol);
                    removed++;
                    logger.info("{} Reconciliation: removed stale position {} (not found at broker) — {}-min re-entry cooldown armed",
                        profilePrefix, symbol, config.getStopLossCooldownMs() / 60000);
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
            for (String pendingKey : new java.util.HashSet<>(riskGate.pendingExitOrders().keySet())) {
                // Key format is "brokerName:symbol" — extract symbol for broker-position lookup
                String[] parts = pendingKey.split(":", 2);
                if (parts.length < 2 || !brokerName.equals(parts[0])) continue;
                String symbol = parts[1];
                if (!brokerSymbols.contains(symbol)) {
                    riskGate.pendingExitOrders().remove(pendingKey);
                    clearedExits++;
                    logger.info("{} Pending exit cleared: {} (position filled/gone from broker)",
                        profilePrefix, symbol);
                } else {
                    // Position still exists — check if our "pending" order is stale
                    long placedAt = riskGate.pendingExitOrders().getOrDefault(pendingKey, now);
                    if (now - placedAt > staleThresholdMs) {
                        riskGate.pendingExitOrders().remove(pendingKey);
                        clearedExits++;
                        logger.warn("{} Stale pending exit cleared for {} — order is {}min old but position still exists (likely expired/rejected by broker); will re-evaluate next cycle",
                            profilePrefix, symbol, (now - placedAt) / 60000);
                    }
                }
            }

            // Also clear urgent exit queue for THIS broker's entries that are no longer at broker.
            // Filter by exit.broker so multi-broker mode doesn't clear another broker's queue.
            for (String key : new java.util.HashSet<>(riskGate.urgentExitQueue().keySet())) {
                RiskGate.UrgentExit exit = riskGate.urgentExitQueue().get(key);
                if (exit == null || !brokerName.equals(exit.broker())) continue;
                if (!brokerSymbols.contains(exit.symbol())) {
                    riskGate.urgentExitQueue().remove(key);
                    logger.info("{} Urgent exit cleared: {} (position filled/gone from broker)",
                        profilePrefix, exit.symbol());
                }
            }

            // Clean up ghost OPEN DB records for symbols no longer held at this broker.
            // First attempt to recover real fill prices from Alpaca order history so that
            // orphaned rows are closed with actual P&L (not CANCELLED with $0).
            // This fixes the "CANCELLED with pnl=0" problem caused by restarts or broker-side stops.
            try {
                var orderHistory = client.getDelegate().getOrderHistory(null, 50);
                if (orderHistory != null && orderHistory.isArray()) {
                    for (var order : orderHistory) {
                        String side = order.path("side").asText("");
                        String status = order.path("status").asText("");
                        String sym = order.path("symbol").asText("");
                        if (!"sell".equals(side) || !"filled".equals(status)) continue;
                        if (brokerSymbols.contains(sym)) continue; // still held
                        if (!database.hasOpenTrade(sym, brokerName)) continue; // no orphan to close
                        double fillPrice = order.path("filled_avg_price").asDouble(0);
                        String filledAtStr = order.path("filled_at").asText("");
                        if (fillPrice <= 0) continue;
                        java.time.Instant fillTime = filledAtStr.isEmpty()
                            ? java.time.Instant.now()
                            : java.time.Instant.parse(filledAtStr);
                        database.closeTrade(sym, fillTime, fillPrice, 0, brokerName, "reconciliation");
                        logger.info("{} Orphan recovery: closed {} with real fill price ${} from order history",
                            profilePrefix, sym, String.format("%.2f", fillPrice));
                    }
                }
            } catch (Exception e) {
                logger.debug("{} Order history fetch for orphan recovery failed: {}", profilePrefix, e.getMessage());
            }
            // Mark any remaining orphans (no fill price found) as CANCELLED.
            // Min age = 2 minutes to avoid closing records for positions currently being opened.
            database.closeOrphanedOpenTrades(brokerName, brokerSymbols, 2 * 60 * 1000L);

            // Ensure every live broker position has a matching OPEN record in the trade DB.
            // This fixes the "0 trades in DB" problem after a redeploy wipes the ephemeral DB:
            // positions that were bought in a previous session are re-inserted as OPEN so that
            // when they are eventually sold, closeTrade() can find them and record P&L.
            // Gate on isMainProfile() — both profiles run reconciliation but only MAIN writes DB
            // recovery records. Without this gate, MAIN and EXPERIMENTAL both call hasOpenTrade()
            // at the same instant (both see false), then both call recordTrade(), creating duplicate
            // OPEN rows whose averaged entry price mis-places stops on restart recovery.
            if (profile.isMainProfile()) for (var pos : brokerPositions) {
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
                        trade.entryPrice(), trade.partialExitsExecuted()
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

            // Sync riskGate.globalHeldSymbols() with every position now tracked in the portfolio.
            // After a restart the static map is empty — without this, the cross-profile
            // ownership guard at handleBuy allows both profiles to buy the same symbol
            // because riskGate.globalHeldSymbols().get(symbol) returns null for all held symbols.
            int synced = 0;
            for (var trade : openDbTrades) {
                if (portfolio.getPosition(trade.symbol()).isPresent()) {
                    riskGate.globalHeldSymbols().putIfAbsent(trade.symbol(), profilePrefix);
                    synced++;
                }
            }
            if (synced > 0) {
                logger.info("{} riskGate.globalHeldSymbols() synced: {} symbol(s) registered as held", profilePrefix, synced);
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
        activeInstanceCount.decrementAndGet();
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
     * Check if current time is good for entering new positions.
     * Avoids first 15 minutes (9:30-9:45 AM) and optionally last 30 minutes.
     */
    private boolean isGoodEntryTime() {
        var now = java.time.ZonedDateTime.now(java.time.ZoneId.of("America/New_York"));
        var currentTime = now.toLocalTime();

        // EOD entry block is independent of all other timing flags.
        // Must be checked first: new entries opened at/after EOD exit time would be
        // immediately closed by the EOD exit, creating churn and potential overnight holds.
        // isAvoidFirst15Minutes defaults false, so this was dead code before — fixed.
        if (config.isEodExitEnabled()) {
            try {
                var eodTime = java.time.LocalTime.parse(config.getEodExitTime());
                var entryDeadline = eodTime.minusMinutes(config.getMainEodEntryCutoffMinutes());
                if (!currentTime.isBefore(entryDeadline)) {
                    logger.debug("Entry blocked — within {}min of EOD exit ({} → cutoff {})",
                        config.getMainEodEntryCutoffMinutes(), config.getEodExitTime(), entryDeadline);
                    return false;
                }
            } catch (Exception e) {
                logger.warn("Could not parse EOD exit time — skipping EOD entry block");
            }
        }

        if (!config.isAvoidFirst15Minutes()) {
            return true; // Open-window feature disabled; EOD block above still applies
        }

        // Avoid first 15 minutes (9:30-9:45 AM)
        var marketOpen = java.time.LocalTime.of(9, 30);
        var safeEntryTime = java.time.LocalTime.of(9, 45);

        if (currentTime.isAfter(marketOpen) && currentTime.isBefore(safeEntryTime)) {
            return false; // Too early
        }

        // Optionally avoid last 30 minutes (legacy, kept for compatibility)
        if (config.isAvoidLast30Minutes()) {
            var marketClose = java.time.LocalTime.of(16, 0);
            var stopEntryTime = java.time.LocalTime.of(15, 30);

            if (currentTime.isAfter(stopEntryTime) && currentTime.isBefore(marketClose)) {
                return false; // Too late
            }
        }

        return true;
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
        // Real correlation via CorrelationCalculator (same instance the Tier 2.4 entry-cap
        // gate above uses) — reuses its 20-day Pearson-correlation matrix rather than the
        // old hardcoded 0.1 stub, which silently defeated adjustForCorrelation() whenever
        // Phase 3 adaptive sizing was enabled.
        try {
            java.util.List<String> openSymbols = new java.util.ArrayList<>(portfolio.getActiveStoredSymbols());
            if (openSymbols.isEmpty()) {
                return 0.0;
            }
            if (!openSymbols.contains(newSymbol)) {
                openSymbols.add(newSymbol);
            }
            var analysis = correlationCalculator.analyzePortfolio(openSymbols);
            var row = analysis.correlationMatrix().get(newSymbol);
            if (row == null) {
                return 0.0;
            }
            double max = 0.0;
            for (var entry : row.entrySet()) {
                if (entry.getKey().equalsIgnoreCase(newSymbol)) continue; // skip self-correlation (1.0)
                max = Math.max(max, Math.abs(entry.getValue()));
            }
            return max;
        } catch (Exception e) {
            logger.debug("getMaxCorrelation failed for {}: {}", newSymbol, e.getMessage());
            return 0.0;
        }
    }

    /**
     * Returns active cooldowns for the dashboard behavior monitor.
     * Key = symbol, Value = expiry timestamp (epoch ms).
     */
    public java.util.Map<String, Long> getActiveCooldowns() {
        long now = System.currentTimeMillis();
        var result = new java.util.LinkedHashMap<String, Long>();
        riskGate.stopLossCooldowns().forEach((symbol, expiresAt) -> {
            if (expiresAt > now) result.put(symbol, expiresAt);
        });
        return result;
    }

    /**
     * Returns consecutive stop-loss counts per symbol for the dashboard.
     */
    public java.util.Map<String, Integer> getConsecutiveStopLosses() {
        return java.util.Collections.unmodifiableMap(riskGate.consecutiveStopLosses());
    }

    /** PDT state — kept for backward-compat; always 0 since PDT abolished June 4 2026. */
    public long getPdtBlockedUntil() { return riskGate.staticPdtBlockedUntil(); }
    public int getPdtDayTradeCount() { return riskGate.staticDayTradeCount(); }

    /**
     * Restore PostLossCooldownTracker state from bot_state table after a restart.
     * Loads all "cooldown:{symbol}" entries, skips expired ones, seeds the tracker.
     * Also restores riskGate.consecutiveStopLosses() from "consec_sl:{symbol}" entries.
     */
    private void restorePostLossCooldownsFromDb(PostLossCooldownTracker tracker) {
        try {
            var cooldowns = database.loadBotStateWithPrefix("cooldown:");
            long now = System.currentTimeMillis();
            int restored = 0;
            int expired = 0;
            for (var entry : cooldowns.entrySet()) {
                String symbol = entry.getKey().substring("cooldown:".length());
                String[] parts = entry.getValue().split(",");
                if (parts.length < 2) continue;
                long expiryMs = Long.parseLong(parts[0].trim());
                int consecLosses = Integer.parseInt(parts[1].trim());
                if (expiryMs <= now) {
                    database.deleteBotState(entry.getKey());
                    // Keep consec_sl — count resets only on a win, not on cooldown expiry.
                    // Bug: previously deleted consec_sl here, so NVDA re-entered every morning
                    // with count=0, always getting the base 6h cooldown instead of 12h extended.
                    if (consecLosses > 0) {
                        riskGate.consecutiveStopLosses().put(symbol, consecLosses);
                        tracker.restoreLossCount(symbol, consecLosses);
                        logger.info("[{}] Cooldown expired for {} — {} consec losses carried forward (resets on win only)",
                            profile.name(), symbol, consecLosses);
                    } else {
                        database.deleteBotState("consec_sl:" + symbol);
                    }
                    expired++;
                    continue;
                }
                // Restore exact persisted state — no recalculation
                tracker.restoreState(symbol, expiryMs, consecLosses);
                riskGate.consecutiveStopLosses().put(symbol, consecLosses);
                logger.info("[{}] Restored post-loss cooldown for {} — expires in {}h ({} consec losses)",
                    profile.name(), symbol,
                    (expiryMs - now) / (60L * 60 * 1000), consecLosses);
                restored++;
            }
            if (restored > 0 || expired > 0) {
                logger.info("[{}] Post-loss cooldown restore: {} active, {} expired/cleaned",
                    profile.name(), restored, expired);
            }
        } catch (Exception e) {
            logger.warn("[{}] Failed to restore post-loss cooldowns from DB: {}", profile.name(), e.getMessage());
        }
    }

    /** Scalp trades executed today across all profiles — resets at midnight. */
    public int getScalpDailyCount() {
        java.time.LocalDate today = java.time.LocalDate.now();
        if (!today.equals(riskGate.scalpCountDate())) {
            riskGate.staticScalpDailyCount().set(0);
            riskGate.setScalpCountDate(today);
        }
        return riskGate.staticScalpDailyCount().get();
    }

    /** Blocked buy reasons — symbols that failed entry gates (gap-down, price improvement). */
    public java.util.Map<String, String> getBlockedBuys() {
        return java.util.Collections.unmodifiableMap(riskGate.blockedBuys());
    }

    /** Trading halt/gate state snapshots — updated each cycle, for dashboard diagnostics. */
    public boolean isPortfolioStopLossHaltActive() { return riskGate.portfolioStopLossHaltActive(); }
    public boolean isMaxDrawdownHaltActive() { return riskGate.maxDrawdownHaltActive(); }
    public double getLatestVixSnapshot() { return riskGate.latestVixSnapshot(); }
    public String getLatestRegimeSnapshot() { return riskGate.latestRegimeSnapshot(); }
    public String getLatestTargetSymbolsSnapshot() { return riskGate.latestTargetSymbolsSnapshot(); }

    /** Per-symbol post-loss cooldown registry (Tier 1.1). Empty map if disabled / no cooldowns. */
    public java.util.Map<String, Long> getPostLossCooldowns() {
        if (riskGate.postLossCooldown() == null) return java.util.Map.of();
        long now = System.currentTimeMillis();
        var snap = riskGate.postLossCooldown().snapshot();
        var live = new java.util.LinkedHashMap<String, Long>();
        snap.forEach((sym, exp) -> { if (exp > now) live.put(sym, exp); });
        return live;
    }

    /** Per-broker circuit breaker snapshot for dashboard (Tier 3.10). */
    public java.util.Map<String, java.util.Map<String, Object>> getCircuitBreakerSnapshot() {
        var out = new java.util.LinkedHashMap<String, java.util.Map<String, Object>>();
        riskGate.circuitBreakers().forEach((broker, cb) -> {
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
    public boolean isAnyCircuitBreakerTripped() {
        return riskGate.circuitBreakers().values().stream().anyMatch(CircuitBreakerState::shouldHaltEntries);
    }

}
