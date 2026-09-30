package com.trading.backtest;

import com.trading.backtest.BacktestReportGenerator.BacktestReport;
import com.trading.analysis.MarketAnalyzer;
import com.trading.analysis.MarketRegimeDetector;
import com.trading.analysis.MultiTimeframeAnalyzer;
import com.trading.api.AlpacaClient;
import com.trading.api.model.Bar;
import com.trading.config.Config;
import com.trading.exits.ExitStrategyManager;
import com.trading.exits.TimeDecayExitManager;
import com.trading.analysis.MarketBreadthAnalyzer;
import com.trading.filters.MarketHoursFilter;
import com.trading.ops.EventDayDetector;
import com.trading.risk.CircuitBreakerState;
import com.trading.risk.PostLossCooldownTracker;
import com.trading.persistence.TradeDatabase;
import com.trading.risk.AdvancedPositionSizer;
import com.trading.risk.CapitalTierManager;
import com.trading.risk.TradePosition;
import com.trading.strategy.StrategyManager;
import com.trading.strategy.TradingSignal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;

/**
 * Replays historical bars through the REAL live decision pipeline — the same
 * {@link StrategyManager}, {@link MarketRegimeDetector}, {@link MultiTimeframeAnalyzer},
 * {@link AdvancedPositionSizer}, and consolidated exit logic ({@link ExitStrategyManager} +
 * {@link TimeDecayExitManager}) that runs live — so a proposed threshold or code change can be
 * validated against a historical window before it ever touches real capital, instead of the
 * previous validation method of "deploy, then watch a handful of live trades."
 *
 * <p><b>Entry gates modelled</b> (added 2026-09-30, faithfully mirroring {@code EntryEvaluator}'s
 * logic — see each check's comment in {@link #checkEntryGates}): entry stagger, the session
 * circuit breaker (consecutive losses / drawdown halt), daily loss/profit halts, the per-symbol
 * flat stop-loss cooldown, the escalating {@link PostLossCooldownTracker} cooldown, the 1%
 * price-improvement-after-loss rule, EOD entry cutoff, lunch blackout, the opening-window block
 * ({@link MarketHoursFilter}), the VIX-minimum gate, the economic-calendar blackout, the real
 * {@link MarketBreadthAnalyzer} (fed the same {@code regimeAnalysis.breadth()} live uses, not a
 * simulation), "existing position in significant loss", the DB-backed rolling-expectancy
 * win-rate gate (closed replay trades are now written to the backtest {@link TradeDatabase} so
 * this — and the position sizer's own win-rate lookups — see real data instead of an always-empty
 * table), and the price-based {@link EventDayDetector}. Toggle with {@link #setGatesEnabled} to
 * reproduce the pre-2026-09-30 ungated behaviour for comparison.
 *
 * <p><b>Still NOT replayed</b>: the sentiment gate (needs historical news, which the replay
 * client doesn't have — see {@code HistoricalReplayBrokerClient.getNews}, always empty),
 * RiskPredictor, AnomalyDetector, ML entry scoring, volume-profile analysis, the correlation
 * entry cap, PDT reservation (a no-op live anyway — {@code PDT_PROTECTION_ENABLED=false}), and
 * the inverse-ETF VIX/persistence gate. Those remain real gaps versus live.
 */
public final class WalkForwardBacktestHarness {
    private static final Logger logger = LoggerFactory.getLogger(WalkForwardBacktestHarness.class);
    private static final ZoneId ET = ZoneId.of("America/New_York");

    // Ancillary symbols MarketRegimeDetector needs regardless of what's actually traded.
    private static final List<String> SECTOR_ETFS = List.of("XLK", "XLF", "XLE", "XLV", "XLI", "XLC", "XLU", "XLB");
    private static final String MARKET_PROXY = "SPY";

    private final Config config;
    private final HistoricalBarCache cache;
    private final HistoricalReplayBrokerClient replayClient;
    private final TradeDatabase database;
    private final MarketRegimeDetector regimeDetector;
    private final MultiTimeframeAnalyzer mtfAnalyzer;
    private final StrategyManager strategyManager;
    private final ExitStrategyManager exitStrategyManager;
    private final TimeDecayExitManager timeDecayExitManager;
    private final AdvancedPositionSizer positionSizer;

    private volatile Instant simNow;
    private double equity;
    // Fidelity switches. Live stops are native broker orders that trigger on ANY tick, but the
    // replay only sampled price at 15-min step boundaries — so a 0.25% scalp stop was checked ~3
    // times in its 45-min life. Intrabar scanning of the 1-min bars (default on) fixes that; set
    // false to reproduce the pre-2026-09-24 optimistic behaviour for comparison.
    private boolean intrabarExits = true;
    private final Map<String, List<Bar>> oneMinCache = new java.util.HashMap<>();
    private Instant prevStep;
    // Realized P&L and original size of positions that have scaled out (final trade = remainder + these)
    private final Map<String, Double> partialPnl = new java.util.HashMap<>();
    private final Map<String, Double> originalQty = new java.util.HashMap<>();
    // Diagnostic only — lets callers see which regimes the replay window actually hit,
    // since a regime-specific fix (e.g. a WEAK_BULL-only threshold change) is untestable
    // if the requested window never actually visits that regime.
    private final Map<MarketRegimeDetector.MarketRegime, Integer> lastRunRegimeCounts = new LinkedHashMap<>();
    private final Map<String, TradePosition> openPositions = new LinkedHashMap<>();
    private final Map<String, String> openPositionStrategy = new LinkedHashMap<>();
    private final List<BacktestTrade> closedTrades = new ArrayList<>();
    private final List<double[]> equityCurve = new ArrayList<>(); // [epochSeconds, equity]

    // ── Entry gates (2026-09-30) ────────────────────────────────────────────────────────────
    // Toggle to reproduce the pre-2026-09-30 ungated behaviour for before/after comparison.
    private boolean gatesEnabled = true;
    private final MarketBreadthAnalyzer breadthAnalyzer;
    private final EventDayDetector eventDayDetector;
    private final CircuitBreakerState circuitBreaker;
    private final PostLossCooldownTracker postLossCooldown;
    // Per-symbol flat cooldown after ANY exit (mirrors riskGate.stopLossCooldowns()).
    private final Map<String, Long> stopLossCooldownExpiry = new HashMap<>();
    // Per-symbol price-improvement-after-loss gate (mirrors riskGate.lastExitPrices()).
    private final Map<String, Double> lastExitPriceBySymbol = new HashMap<>();
    // Global throttle between any two entries, regardless of symbol (mirrors
    // RiskGate.MIN_ENTRY_SPACING_MS — package-private in a different package, so the same
    // 90-second value is hardcoded here with this comment as the cross-reference).
    private long lastEntryEpochMs = 0L;
    private double todaySimPnL = 0.0;
    private java.time.LocalDate todaySimDate = null;
    // Diagnostic — how often each entry gate actually fired, so a "why did volume change"
    // question doesn't require re-deriving it from the trade list.
    private final Map<String, Integer> gateBlockCounts = new TreeMap<>();

    public WalkForwardBacktestHarness(Config config, Path cacheDir, Instant startingNow) {
        this.config = config;
        this.cache = new HistoricalBarCache(cacheDir);
        this.replayClient = new HistoricalReplayBrokerClient(startingNow);
        this.simNow = startingNow;
        this.database = new TradeDatabase(cacheDir.resolve("backtest.db").toString());

        var marketAnalyzer = new MarketAnalyzer(replayClient);
        this.regimeDetector = new MarketRegimeDetector(replayClient, config, marketAnalyzer);
        this.mtfAnalyzer = new MultiTimeframeAnalyzer(replayClient, config);
        this.strategyManager = new StrategyManager(replayClient, mtfAnalyzer, config);
        this.exitStrategyManager = new ExitStrategyManager(config);
        this.timeDecayExitManager = new TimeDecayExitManager(config);
        this.positionSizer = new AdvancedPositionSizer(config, database);
        this.breadthAnalyzer = new MarketBreadthAnalyzer(config);
        this.eventDayDetector = new EventDayDetector(
            sym -> replayClient.getMarketHistory(sym, 3), sym -> replayClient.getBars(sym, "15Min", 40), config);
        this.circuitBreaker = new CircuitBreakerState(
            config.getCircuitBreakerConsecutiveLosses(), config.getCircuitBreakerSessionDrawdownPercent() / 100.0);
        this.postLossCooldown = new PostLossCooldownTracker(
            config.getPostLossCooldownMs(), config.getPostLossCooldownExtendedMs(), 2);

        regimeDetector.setNowSupplier(this::getSimNow);
        mtfAnalyzer.setNowSupplier(this::getSimNow);
        strategyManager.setNowSupplier(() -> ZonedDateTime.ofInstant(simNow, ET));
        exitStrategyManager.setNowSupplier(this::getSimNow);
        timeDecayExitManager.setNowSupplier(this::getSimNow);
    }

    public void setIntrabarExits(boolean enabled) {
        this.intrabarExits = enabled;
    }

    public void setGatesEnabled(boolean enabled) {
        this.gatesEnabled = enabled;
    }

    /** Diagnostic — how many times each entry gate blocked a candidate in the most recent run(). */
    public Map<String, Integer> getLastRunGateBlockCounts() {
        return new TreeMap<>(gateBlockCounts);
    }

    private Instant getSimNow() {
        return simNow;
    }

    /** Visible for testing — lets tests load synthetic bars directly, bypassing loadHistory()'s real-API fetch. */
    HistoricalReplayBrokerClient getReplayClient() {
        return replayClient;
    }

    /** Regime counts observed during the most recent run() — diagnostic only, see field comment. */
    public Map<String, Integer> getLastRunRegimeCounts() {
        var out = new LinkedHashMap<String, Integer>();
        lastRunRegimeCounts.forEach((k, v) -> out.put(k.name(), v));
        return out;
    }

    /**
     * Fetch (or reuse cached) historical bars for every symbol this run needs — the traded
     * symbols plus SPY and the 8 sector ETFs the regime detector always consults. Requires a
     * real, credentialed {@link AlpacaClient} the first time a given cache directory is used;
     * every subsequent run against the same cache directory is fully offline.
     */
    public void loadHistory(AlpacaClient liveClient, List<String> tradedSymbols) {
        loadHistory(liveClient, tradedSymbols, 30);
    }

    /**
     * Same as {@link #loadHistory(AlpacaClient, List)} but scales intraday fetch depth to cover
     * at least {@code minCalendarDays} back from today — the original fixed limits (780 15Min
     * bars etc.) only covered ~30 calendar days, silently truncating any longer replay window
     * to whatever that fixed limit actually reached, regardless of the caller's requested range.
     */
    public void loadHistory(AlpacaClient liveClient, List<String> tradedSymbols, int minCalendarDays) {
        var allSymbols = new LinkedHashSet<String>();
        allSymbols.addAll(tradedSymbols);
        allSymbols.add(MARKET_PROXY);
        allSymbols.addAll(SECTOR_ETFS);

        // Bars per calendar day at each timeframe (6.5h regular session), with a safety margin.
        // Alpaca's bars API hard-caps `limit` at 10000 regardless of timeframe — 1Min bars hit
        // that ceiling around 25 calendar days, so very long windows silently get whatever recent
        // history fits under 10000 for 1Min while the coarser timeframes scale further.
        int days = Math.max(minCalendarDays, 1);
        int dailyLimit = Math.min(10_000, Math.max(400, days + 60));
        int oneMinLimit = Math.min(10_000, Math.max(800, days * 390));
        int fiveMinLimit = Math.min(10_000, Math.max(1560, days * 78));
        int fifteenMinLimit = Math.min(10_000, Math.max(780, days * 26));
        int oneHourLimit = Math.min(10_000, Math.max(280, days * 7));

        for (String symbol : allSymbols) {
            replayClient.loadBars(symbol, "1Day", cache.getOrFetch(liveClient, symbol, "1Day", dailyLimit));
            replayClient.loadBars(symbol, "1Day-history", cache.getOrFetchMarketHistory(liveClient, symbol, dailyLimit));
        }
        for (String symbol : tradedSymbols) {
            replayClient.loadBars(symbol, "1Min", cache.getOrFetch(liveClient, symbol, "1Min", oneMinLimit));
            replayClient.loadBars(symbol, "5Min", cache.getOrFetch(liveClient, symbol, "5Min", fiveMinLimit));
            replayClient.loadBars(symbol, "15Min", cache.getOrFetch(liveClient, symbol, "15Min", fifteenMinLimit));
            replayClient.loadBars(symbol, "1Hour", cache.getOrFetch(liveClient, symbol, "1Hour", oneHourLimit));
        }
        // VIX often isn't fetchable directly via the equities bars endpoint; try VIXY as the
        // detector's own fallback does live. Best-effort — regime detection falls back to a
        // default VIX of 20.0 if neither is available, same as live.
        try {
            replayClient.loadBars("VIXY", "1Day", cache.getOrFetch(liveClient, "VIXY", "1Day", dailyLimit));
        } catch (Exception e) {
            logger.debug("VIXY history unavailable for replay: {}", e.getMessage());
        }
        logger.info("Loaded replay history for {} symbols ({} traded)", allSymbols.size(), tradedSymbols.size());
    }

    /**
     * Run the walk-forward replay over [start, end) at 15-minute steps, using one of the traded
     * symbols' own 15-min bar timestamps as the step sequence (so steps only land on real,
     * already-elapsed market data points rather than a fabricated calendar).
     */
    public BacktestReport run(List<String> tradedSymbols, Instant start, Instant end,
                              double initialCapital, int maxPositions) {
        this.equity = initialCapital;
        openPositions.clear();
        openPositionStrategy.clear();
        closedTrades.clear();
        equityCurve.clear();
        lastRunRegimeCounts.clear();
        prevStep = null;
        partialPnl.clear();
        originalQty.clear();
        stopLossCooldownExpiry.clear();
        lastExitPriceBySymbol.clear();
        gateBlockCounts.clear();
        lastEntryEpochMs = 0L;
        todaySimPnL = 0.0;
        todaySimDate = null;
        circuitBreaker.resetForNewSession(initialCapital);
        postLossCooldown.clear();

        List<Instant> steps = stepTimestamps(tradedSymbols, start, end);
        logger.info("Replaying {} steps from {} to {}", steps.size(), start, end);

        for (Instant t : steps) {
            simNow = t;
            replayClient.advanceTo(t);
            // No explicit day-rollover handling needed: ORB's own date check
            // (level.date().equals(today), driven by the injected clock set once in the
            // constructor) already recomputes its range automatically whenever the simulated
            // date changes — see OpeningRangeBreakoutStrategy.evaluate().

            var regimeAnalysis = regimeDetector.getCurrentRegime();
            lastRunRegimeCounts.merge(regimeAnalysis.regime(), 1, Integer::sum);

            // Day rollover: reset the daily P&L halt and the session circuit breaker, same as
            // live's midnight-ET rollover. Total mark-to-market equity (cash + open positions),
            // matching what ProfileManager passes into these checks live.
            double totalEquity = equity + openPositionsValue(t);
            var etDate = ZonedDateTime.ofInstant(t, ET).toLocalDate();
            if (!etDate.equals(todaySimDate)) {
                todaySimDate = etDate;
                todaySimPnL = 0.0;
                circuitBreaker.resetForNewSession(totalEquity);
            }
            circuitBreaker.updateEquity(totalEquity);
            breadthAnalyzer.updateBreadth(regimeAnalysis.breadth().strength());

            // Manage open positions first — exits before new entries, matching live priority.
            for (String symbol : new ArrayList<>(openPositions.keySet())) {
                // Native-stop/TP fidelity: did the 1-min bars since the last step touch either level?
                if (intrabarExits && prevStep != null && scanIntrabarExit(symbol, prevStep, t)) continue;
                double price = latestClose(symbol, t);
                if (Double.isNaN(price)) continue;
                checkExit(symbol, price, t);
            }
            prevStep = t;

            // Live flattens everything at EOD_EXIT_TIME and takes no new entries after it.
            boolean pastEod = false;
            if (config.isEodExitEnabled()) {
                var et = ZonedDateTime.ofInstant(t, ET).toLocalTime();
                pastEod = !et.isBefore(java.time.LocalTime.parse(config.getEodExitTime()));
                if (pastEod) {
                    for (String symbol : new ArrayList<>(openPositions.keySet())) {
                        double price = latestClose(symbol, t);
                        if (!Double.isNaN(price)) closePosition(symbol, price, "EOD_EXIT", t);
                    }
                }
            }

            // Cycle-level halts (apply to every symbol, matching live's pre-loop circuit-breaker
            // and daily-loss/profit checks in ProfileManager.handleBuy/EntryEvaluator).
            boolean haltAllEntries = false;
            if (gatesEnabled) {
                if (circuitBreaker.shouldHaltEntries()) {
                    haltAllEntries = true;
                    gateBlockCounts.merge("circuit breaker " + circuitBreaker.tripReason(), 1, Integer::sum);
                } else if (config.isDailyMaxLossEnabled()
                        && todaySimPnL < -Math.abs(totalEquity * config.getDailyMaxLossPercent() / 100.0)) {
                    haltAllEntries = true;
                    gateBlockCounts.merge("daily loss limit hit", 1, Integer::sum);
                } else if (config.isDailyProfitTargetEnabled() && todaySimPnL >= config.getDailyProfitTarget()) {
                    haltAllEntries = true;
                    gateBlockCounts.merge("daily profit target reached", 1, Integer::sum);
                }
            }

            // Consider new entries if under the position cap.
            if (!pastEod && !haltAllEntries && openPositions.size() < maxPositions) {
                for (String symbol : tradedSymbols) {
                    if (openPositions.containsKey(symbol)) continue;
                    if (openPositions.size() >= maxPositions) break;
                    double price = latestClose(symbol, t);
                    if (Double.isNaN(price)) continue;
                    tryEnter(symbol, price, regimeAnalysis, t);
                }
            }

            equityCurve.add(new double[]{t.getEpochSecond(), equity + openPositionsValue(t)});
        }

        // Force-close anything still open at the end of the window so the report is complete.
        for (String symbol : new ArrayList<>(openPositions.keySet())) {
            double price = latestClose(symbol, end);
            if (!Double.isNaN(price)) {
                closePosition(symbol, price, "END_OF_REPLAY_WINDOW", end);
            }
        }

        return BacktestReportGenerator.generate(closedTrades, initialCapital, equity, equityCurve);
    }

    private void tryEnter(String symbol, double price, MarketRegimeDetector.MarketRegimeAnalysis regime, Instant t) {
        TradingSignal signal;
        try {
            signal = strategyManager.evaluate(symbol, price, 0, regime.regime());
        } catch (Exception e) {
            logger.debug("{}: signal evaluation failed at {}: {}", symbol, t, e.getMessage());
            return;
        }
        if (!(signal instanceof TradingSignal.Buy) && !(signal instanceof TradingSignal.ScalpBuy)) {
            return;
        }
        boolean isScalpSignal = signal instanceof TradingSignal.ScalpBuy;
        if (gatesEnabled) {
            String blockReason = checkEntryGates(symbol, price, regime, isScalpSignal, t);
            if (blockReason != null) {
                gateBlockCounts.merge(blockReason, 1, Integer::sum);
                return;
            }
        }

        double stopLossPct;
        double takeProfitPct;
        String strategyLabel;
        if (signal instanceof TradingSignal.ScalpBuy scalpBuy) {
            stopLossPct = scalpBuy.stopLossPercent();
            takeProfitPct = scalpBuy.takeProfitPercent();
            strategyLabel = "SCALP";
        } else {
            stopLossPct = config.getVixScaledStopLoss(regime.vix());
            takeProfitPct = config.getVixScaledTakeProfit(regime.vix());
            // Real signal source (activeStrategy is set by StrategyManager.evaluate() just above,
            // single-threaded here) — was hard-coded "MACD" for every non-scalp entry, which made
            // "Momentum never fires" impossible to test.
            strategyLabel = strategyManager.getActiveStrategy();
        }

        double stopLoss = price * (1.0 - stopLossPct / 100.0);
        double takeProfit = price * (1.0 + takeProfitPct / 100.0);

        double volatility = regime.vix() / 100.0;
        double shares = positionSizer.calculatePositionSize(
            symbol, equity, price, volatility, stopLossPct / 100.0, regime.regime().name());

        // Same belt-and-suspenders hard notional cap ProfileManager applies live.
        var tierParams = CapitalTierManager.getParameters(equity);
        double tierMaxNotional = equity * tierParams.maxPositionPercent();
        if (shares * price > tierMaxNotional && tierMaxNotional > 0) {
            shares = tierMaxNotional / price;
        }
        if (shares <= 0 || shares * price < 1.0) {
            return;
        }

        var position = new TradePosition(symbol, price, shares, stopLoss, takeProfit,
            t, price, 0);
        openPositions.put(symbol, position);
        openPositionStrategy.put(symbol, strategyLabel);
        originalQty.put(symbol, shares);
        equity -= shares * price;
        lastEntryEpochMs = t.toEpochMilli();
        // Persist to the backtest DB so DB-backed gates/sizing (rolling-expectancy gate,
        // AdvancedPositionSizer's Kelly win-rate lookup) see real data instead of an always-empty
        // table — a latent fidelity gap that predates the 2026-09-30 gate work.
        database.recordTradeWithContext(symbol, strategyLabel, "BACKTEST", "backtest", t, price, shares,
            stopLoss, takeProfit, regime.regime().name(), regime.vix(), regime.breadth().strength());
        // Same commit-on-execution contract as live: only a really-opened scalp consumes a daily slot.
        if (signal instanceof TradingSignal.ScalpBuy) {
            strategyManager.commitScalpEntry(symbol);
        }
    }

    /**
     * Mirrors {@code EntryEvaluator.evaluate()}'s gate chain (see its class Javadoc for the full
     * live list). Order follows the live gate order where it matters for interaction; gates not
     * modelled here are listed in this class's own Javadoc.
     * @return a block reason, or null if every modelled gate passes.
     */
    /** Package-visible (not private) so tests can exercise the gate chain directly. */
    String checkEntryGates(String symbol, double price, MarketRegimeDetector.MarketRegimeAnalysis regime,
                                   boolean isScalp, Instant t) {
        long nowMs = t.toEpochMilli();
        double vix = regime.vix();
        var zdt = ZonedDateTime.ofInstant(t, ET);

        // Entry stagger — global, not per-symbol (see the field comment for the hardcoded value).
        if (nowMs - lastEntryEpochMs < 90_000L) {
            return "entry stagger";
        }

        // Escalating post-loss cooldown (per symbol).
        if (postLossCooldown.isInCooldown(symbol, nowMs)) {
            return "post-loss cooldown";
        }

        // Flat stop-loss cooldown after ANY exit (per symbol) — not scalp-exempt live, so not here either.
        Long cooldownExpiry = stopLossCooldownExpiry.get(symbol);
        if (cooldownExpiry != null && nowMs < cooldownExpiry) {
            return "stop loss cooldown";
        }

        // Price-improvement-after-loss (1%), including the same state-clearing side effects as
        // EntryEvaluator: a recovery above the exit price, or enough of a discount, clears the gate.
        Double lastExit = lastExitPriceBySymbol.get(symbol);
        if (lastExit != null) {
            double improvementPercent = (lastExit - price) / lastExit * 100.0;
            if (price > lastExit) {
                lastExitPriceBySymbol.remove(symbol);
            } else if (improvementPercent < 1.0) {
                return "waiting for price improvement below last exit";
            } else {
                lastExitPriceBySymbol.remove(symbol);
            }
        }

        // Time-of-day gates — scalp-exempt live (ScalpStrategy has its own window), so exempt here too.
        if (!isScalp) {
            if (config.isEodExitEnabled()) {
                try {
                    var eodTime = java.time.LocalTime.parse(config.getEodExitTime());
                    var mainCutoff = eodTime.minusMinutes(config.getMainEodEntryCutoffMinutes());
                    if (!zdt.toLocalTime().isBefore(mainCutoff)) {
                        return "within " + config.getMainEodEntryCutoffMinutes() + "min of EOD";
                    }
                } catch (Exception ignored) { /* malformed EOD_EXIT_TIME: fail open, matches live's catch */ }
            }
            if (config.isLunchBlackoutEnabled()) {
                try {
                    var lunchStart = java.time.LocalTime.parse(config.getLunchBlackoutStart());
                    var lunchEnd = java.time.LocalTime.parse(config.getLunchBlackoutEnd());
                    var nowET = zdt.toLocalTime();
                    if (!nowET.isBefore(lunchStart) && nowET.isBefore(lunchEnd)) {
                        return "lunch blackout " + lunchStart + "-" + lunchEnd + " ET";
                    }
                } catch (Exception ignored) { }
            }
            if (config.isNoTradeOpenWindowEnabled()
                    && MarketHoursFilter.isInOpeningWindow(zdt, config.getNoTradeOpenWindowMinutes())) {
                return "opening-window block: first " + config.getNoTradeOpenWindowMinutes() + "min";
            }
            if (config.isVixEntryGateEnabled() && vix > 0 && vix < config.getVixEntryMinimum()) {
                return "VIX " + vix + " below minimum " + config.getVixEntryMinimum();
            }
        }

        if (config.isEconomicCalendarBlackoutEnabled()
                && config.getEconomicBlackoutDates().contains(zdt.toLocalDate())) {
            return "economic calendar blackout";
        }

        // Real market breadth (already computed every step from the same sector-ETF bars live uses),
        // not a simulation — see run()'s per-step breadthAnalyzer.updateBreadth() call.
        boolean skipBreadthFilter = regime.regime() == MarketRegimeDetector.MarketRegime.RANGE_BOUND && vix < 15.0;
        if (!skipBreadthFilter && !breadthAnalyzer.isMarketHealthy()) {
            return "market breadth too low";
        }

        // An existing held position already down >0.20% blocks adding fresh exposure.
        for (var e : openPositions.entrySet()) {
            double heldPrice = latestClose(e.getKey(), t);
            if (Double.isNaN(heldPrice)) continue;
            double lossPct = (heldPrice - e.getValue().entryPrice()) / e.getValue().entryPrice() * 100.0;
            if (lossPct <= -0.20) {
                return "existing position is in significant loss";
            }
        }

        // Rolling-expectancy win-rate gate (2026-09-25 live addition) — reads the backtest DB
        // rows tryEnter() now writes, not the live production database.
        String regimeName = regime.regime().name();
        var w = database.getRecentSymbolStats(symbol, regimeName,
            config.getWinRateGateWindowDays(), config.getWinRateGateWindowTrades());
        if (w != null && w.trades() >= config.getWinRateGateMinTrades()
                && w.winRate() < config.getWinRateGateMaxWinRate() && w.avgReturnPct() < 0) {
            return "low win rate in " + regimeName;
        }

        // Price-based event-day gate (2026-09-25 live addition) — single stocks only.
        if (eventDayDetector.eventReason(symbol).isPresent()) {
            return "event day";
        }

        return null;
    }

    private void checkExit(String symbol, double price, Instant t) {
        var position = openPositions.get(symbol);
        if (position == null) return;
        boolean isScalp = "SCALP".equals(openPositionStrategy.get(symbol));

        // Live SCALP_MAX_HOLD_MINUTES timeout (ExitEvaluator) — scalp is exempt from time-decay there.
        if (isScalp && position.entryTime() != null
                && java.time.Duration.between(position.entryTime(), simNow).toMinutes() >= config.getScalpMaxHoldMinutes()) {
            closePosition(symbol, price, "SCALP_TIMEOUT", t);
            return;
        }

        if (timeDecayExitManager.shouldExit(position, price)) {
            closePosition(symbol, price, "TIME_DECAY", t);
            return;
        }

        var decision = exitStrategyManager.evaluateExit(position, price, 0.0, Map.of(), isScalp);
        if (decision.type() == ExitStrategyManager.ExitType.NONE) return;
        if (decision.isPartial()) {
            if (!intrabarExits) return; // legacy mode: whole-position round trips only
            applyPartial(symbol, position, decision, price);
        } else {
            closePosition(symbol, decision.expectedPrice(), decision.type().name(), t);
        }
    }

    /**
     * Replays the 1-min bars in [from, to) for one open position, mirroring how live splits
     * protection between the BROKER and the BOT:
     * <ul>
     *   <li>Native stop (broker-side, triggers on any tick) → checked against each bar's open/low;
     *       gap-through fills at the open, same-bar ambiguity resolves against us (stop wins).</li>
     *   <li>Everything the bot polls every ~20s — breakeven-stop move, winner-runner launch,
     *       partial exits, take-profit — is evaluated at each bar's CLOSE (a 1-min bar is a fair
     *       stand-in for a 20s poll; using the high would be optimistic).</li>
     * </ul>
     * Not simulated: multi-level trailing stop (TrailingTargetManager), orphan/max-loss paths.
     * @return true if the position was fully closed
     */
    boolean scanIntrabarExit(String symbol, Instant from, Instant to) {
        var first = openPositions.get(symbol);
        if (first == null) return false;
        List<Bar> bars = oneMinCache.computeIfAbsent(symbol, s -> allBarsUnfiltered(s, "1Min"));
        Instant lo = first.entryTime() != null && first.entryTime().isAfter(from) ? first.entryTime() : from;
        int idx = java.util.Collections.binarySearch(bars, new Bar(lo, 1, 1, 1, 1, 0L),
            java.util.Comparator.comparing(Bar::timestamp));
        if (idx < 0) idx = -idx - 1;
        Instant savedNow = simNow;
        try {
            for (int i = idx; i < bars.size(); i++) {
                Bar b = bars.get(i);
                if (!b.timestamp().isBefore(to)) break;
                var pos = openPositions.get(symbol);
                if (pos == null) return true;
                Instant barEnd = b.timestamp().plusSeconds(60);
                simNow = barEnd; // time-aware exit logic (hold-time rules) sees the bar's own time
                boolean isScalp = "SCALP".equals(openPositionStrategy.get(symbol));

                // --- native broker stop: wick-triggered ---
                if (b.open() <= pos.stopLoss()) {
                    closePosition(symbol, b.open(), "STOP_LOSS", barEnd);
                    return true;
                }
                if (b.low() <= pos.stopLoss()) {
                    closePosition(symbol, pos.stopLoss(), "STOP_LOSS", barEnd);
                    return true;
                }

                // --- bot-polled logic at the bar close ---
                double c = b.close();
                if (config.isBreakevenStopEnabled() && pos.stopLoss() < pos.entryPrice()
                        && (c - pos.entryPrice()) / pos.entryPrice() * 100.0 >= config.getBreakevenTriggerPercent()) {
                    pos = new TradePosition(symbol, pos.entryPrice(), pos.quantity(), pos.entryPrice(),
                        pos.takeProfit(), pos.entryTime(), Math.max(pos.highestPrice(), c), pos.partialExitsExecuted());
                    openPositions.put(symbol, pos);
                }
                if (!isScalp && config.isWinnerRunnerEnabled() && !pos.hasPartialExit(4) && pos.isTakeProfitHit(c)) {
                    launchRunner(symbol, pos, c);
                    continue;
                }
                var decision = exitStrategyManager.evaluateExit(pos, c, 0.0, Map.of(), isScalp);
                if (decision.type() == ExitStrategyManager.ExitType.NONE) continue;
                if (decision.isPartial()) {
                    applyPartial(symbol, pos, decision, c);
                } else {
                    closePosition(symbol, decision.expectedPrice() > 0 ? decision.expectedPrice() : c,
                        decision.type().name(), barEnd);
                    return true;
                }
            }
        } finally {
            simNow = savedNow;
        }
        return false;
    }

    /** Sell `decision.quantity()` of the REMAINING shares at `price`; bank the P&L; keep the stop. */
    private void applyPartial(String symbol, TradePosition pos, ExitStrategyManager.ExitDecision d, double price) {
        double sold = pos.quantity() * d.quantity();
        if (sold <= 0 || sold >= pos.quantity()) return;
        bankPartial(symbol, pos, sold, price);
        var reduced = new TradePosition(symbol, pos.entryPrice(), pos.quantity() - sold, pos.stopLoss(),
            pos.takeProfit(), pos.entryTime(), Math.max(pos.highestPrice(), price), pos.partialExitsExecuted());
        if (d.partialLevel() > 0) reduced = reduced.markPartialExit(d.partialLevel());
        openPositions.put(symbol, reduced);
    }

    /** ExitEvaluator's winner runner: sell half at TP, lock a profitable stop, extend TP 1.5x. */
    private void launchRunner(String symbol, TradePosition pos, double price) {
        double half = pos.quantity() * 0.5;
        bankPartial(symbol, pos, half, price);
        double lockedStop = pos.entryPrice() + (pos.takeProfit() - pos.entryPrice()) * config.getRunnerLockPct();
        double runnerTp = pos.takeProfit() + (pos.takeProfit() - pos.entryPrice()) * 0.5;
        var marked = pos.markPartialExit(1).markPartialExit(2).markPartialExit(3).markPartialExit(4);
        openPositions.put(symbol, new TradePosition(symbol, pos.entryPrice(), pos.quantity() - half, lockedStop,
            runnerTp, pos.entryTime(), Math.max(pos.highestPrice(), price), marked.partialExitsExecuted()));
    }

    private void bankPartial(String symbol, TradePosition pos, double qtySold, double price) {
        equity += qtySold * price;
        double legPnl = (price - pos.entryPrice()) * qtySold;
        partialPnl.merge(symbol, legPnl, Double::sum);
        // Live's partial/runner paths feed todayPnL (updateDailyPnLFn) but NOT the cooldown/circuit
        // breaker — only a full exit does that (see ExitEvaluator, and the 2026-09-21 fix that
        // closed this exact live gap for partials specifically).
        todaySimPnL += legPnl;
    }

    /** Visible for testing. */
    List<BacktestTrade> closedTradesForTest() {
        return closedTrades;
    }

    // ── Test-only seeding hooks for the entry-gate chain ────────────────────────────────────
    void seedStopLossCooldownForTest(String symbol, long expiryMs) { stopLossCooldownExpiry.put(symbol, expiryMs); }
    void seedLastExitPriceForTest(String symbol, double price) { lastExitPriceBySymbol.put(symbol, price); }
    void seedLastEntryEpochMsForTest(long ms) { lastEntryEpochMs = ms; }
    MarketBreadthAnalyzer breadthAnalyzerForTest() { return breadthAnalyzer; }
    CircuitBreakerState circuitBreakerForTest() { return circuitBreaker; }
    PostLossCooldownTracker postLossCooldownForTest() { return postLossCooldown; }

    /** Visible for testing. */
    void openPositionForTest(String symbol, TradePosition p, String strategy) {
        openPositions.put(symbol, p);
        originalQty.put(symbol, p.quantity());
        openPositionStrategy.put(symbol, strategy);
    }

    private void closePosition(String symbol, double exitPrice, String exitReason, Instant exitTime) {
        var position = openPositions.remove(symbol);
        String strategy = openPositionStrategy.remove(symbol);
        if (position == null) return;
        double pnl = (exitPrice - position.entryPrice()) * position.quantity()
            + partialPnl.getOrDefault(symbol, 0.0);
        double qtyReported = originalQty.getOrDefault(symbol, position.quantity());
        partialPnl.remove(symbol);
        originalQty.remove(symbol);
        equity += position.quantity() * exitPrice;
        closedTrades.add(new BacktestTrade(symbol, strategy, position.entryTime(), exitTime,
            position.entryPrice(), exitPrice, qtyReported, pnl, exitReason));

        // Post-exit gate state (mirrors ExitEvaluator.applyPostExitCooldown, run for every full
        // exit including EOD/end-of-window — a simplification: live's EOD flatten doesn't arm
        // these, but the overnight gap outlasts every cooldown here except the escalated
        // post-loss one, so this errs toward slightly MORE caution than live, never less).
        long nowMs = exitTime.toEpochMilli();
        stopLossCooldownExpiry.put(symbol, nowMs + config.getStopLossCooldownMs());
        if (pnl < 0) {
            lastExitPriceBySymbol.put(symbol, exitPrice);
            postLossCooldown.recordLoss(symbol, nowMs);
        } else if (pnl > 0) {
            lastExitPriceBySymbol.remove(symbol);
            postLossCooldown.recordWin(symbol);
        }
        circuitBreaker.recordTrade(pnl);
        todaySimPnL += pnl;
        database.closeTrade(symbol, exitTime, exitPrice, pnl, "backtest", exitReason);
    }

    private double openPositionsValue(Instant t) {
        double v = 0;
        for (var e : openPositions.entrySet()) {
            double price = latestClose(e.getKey(), t);
            v += Double.isNaN(price) ? 0 : e.getValue().quantity() * price;
        }
        return v;
    }

    private double latestClose(String symbol, Instant t) {
        var bar = replayClient.getLatestBar(symbol);
        return bar.map(Bar::close).orElse(Double.NaN);
    }

    private List<Instant> stepTimestamps(List<String> tradedSymbols, Instant start, Instant end) {
        // Use the union of all traded symbols' 15-min bar timestamps within [start, end) as the
        // step sequence, sorted — real data points only, no fabricated calendar.
        var steps = new TreeSet<Instant>();
        for (String symbol : tradedSymbols) {
            for (Bar b : allBarsUnfiltered(symbol, "15Min")) {
                if (!b.timestamp().isBefore(start) && b.timestamp().isBefore(end)) {
                    steps.add(b.timestamp());
                }
            }
        }
        return new ArrayList<>(steps);
    }

    private List<Bar> allBarsUnfiltered(String symbol, String timeframe) {
        // Temporarily advance the replay clock to the far future to read the full cached series
        // for step-sequence construction, then restore — getBars() is otherwise clock-scoped.
        Instant saved = replayClient.getSimulatedNow();
        replayClient.advanceTo(Instant.MAX);
        List<Bar> all = replayClient.getBars(symbol, timeframe, Integer.MAX_VALUE / 2);
        replayClient.advanceTo(saved);
        return all;
    }

    public record BacktestTrade(
        String symbol, String strategy, Instant entryTime, Instant exitTime,
        double entryPrice, double exitPrice, double quantity, double pnl, String exitReason
    ) {}
}
