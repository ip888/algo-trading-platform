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
 * <p><b>Explicit scope — what this does NOT replay</b> (documented so results are never mistaken
 * for full live-parity): sentiment gate, RiskPredictor, AnomalyDetector, the correlation entry
 * cap, circuit breakers/daily-loss halts, cooldowns, and Kelly sizing's database-backed win-rate
 * stats (the position sizer runs with a fresh, empty {@link TradeDatabase} each backtest, so its
 * win-rate defaults to {@code POSITION_SIZING_DEFAULT_WIN_RATE} rather than real history — this
 * only matters if {@code POSITION_SIZING_METHOD=KELLY}; it's a no-op under the currently-live
 * {@code FIXED} method). Those are all real, separate live gates layered on top of the signal
 * pipeline in {@code ProfileManager}, not part of the core "what does the strategy say, how big
 * a position, when does it exit" question this harness answers.
 *
 * <p>Also does not replay real news (sentiment score stays neutral/0.0 for the duration), so any
 * future change that wires sentiment into the harness needs real historical news data first.
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
    // Diagnostic only — lets callers see which regimes the replay window actually hit,
    // since a regime-specific fix (e.g. a WEAK_BULL-only threshold change) is untestable
    // if the requested window never actually visits that regime.
    private final Map<MarketRegimeDetector.MarketRegime, Integer> lastRunRegimeCounts = new LinkedHashMap<>();
    private final Map<String, TradePosition> openPositions = new LinkedHashMap<>();
    private final Map<String, String> openPositionStrategy = new LinkedHashMap<>();
    private final List<BacktestTrade> closedTrades = new ArrayList<>();
    private final List<double[]> equityCurve = new ArrayList<>(); // [epochSeconds, equity]

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

        regimeDetector.setNowSupplier(this::getSimNow);
        mtfAnalyzer.setNowSupplier(this::getSimNow);
        strategyManager.setNowSupplier(() -> ZonedDateTime.ofInstant(simNow, ET));
        exitStrategyManager.setNowSupplier(this::getSimNow);
        timeDecayExitManager.setNowSupplier(this::getSimNow);
    }

    public void setIntrabarExits(boolean enabled) {
        this.intrabarExits = enabled;
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

            // Consider new entries if under the position cap.
            if (!pastEod && openPositions.size() < maxPositions) {
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
        equity -= shares * price;
        // Same commit-on-execution contract as live: only a really-opened scalp consumes a daily slot.
        if (signal instanceof TradingSignal.ScalpBuy) {
            strategyManager.commitScalpEntry(symbol);
        }
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
        if (decision.type() != ExitStrategyManager.ExitType.NONE && !decision.isPartial()) {
            closePosition(symbol, decision.expectedPrice(), decision.type().name(), t);
        }
        // Partial exits are not simulated — the harness tracks whole-position round trips only,
        // consistent with its documented scope (validating entry/exit/sizing thresholds, not
        // reproducing every live partial-fill mechanic).
    }

    /**
     * Scan the 1-min bars in [from, to) for a touch of the position's stop or take-profit and close
     * at that level. Same-bar stop+TP resolves to the stop (conservative); a bar that opens through
     * the stop fills at the open (gap), like a real stop-market order.
     * @return true if the position was closed
     */
    boolean scanIntrabarExit(String symbol, Instant from, Instant to) {
        var pos = openPositions.get(symbol);
        if (pos == null) return false;
        List<Bar> bars = oneMinCache.computeIfAbsent(symbol, s -> allBarsUnfiltered(s, "1Min"));
        Instant lo = pos.entryTime() != null && pos.entryTime().isAfter(from) ? pos.entryTime() : from;
        int idx = java.util.Collections.binarySearch(bars, new Bar(lo, 1, 1, 1, 1, 0L),
            java.util.Comparator.comparing(Bar::timestamp));
        if (idx < 0) idx = -idx - 1;
        for (int i = idx; i < bars.size(); i++) {
            Bar b = bars.get(i);
            if (!b.timestamp().isBefore(to)) break;
            if (b.open() <= pos.stopLoss()) {
                closePosition(symbol, b.open(), "STOP_LOSS", b.timestamp());
                return true;
            }
            if (b.low() <= pos.stopLoss()) {
                closePosition(symbol, pos.stopLoss(), "STOP_LOSS", b.timestamp());
                return true;
            }
            if (b.high() >= pos.takeProfit()) {
                closePosition(symbol, pos.takeProfit(), "TAKE_PROFIT", b.timestamp());
                return true;
            }
        }
        return false;
    }

    /** Visible for testing. */
    void openPositionForTest(String symbol, TradePosition p, String strategy) {
        openPositions.put(symbol, p);
        openPositionStrategy.put(symbol, strategy);
    }

    private void closePosition(String symbol, double exitPrice, String exitReason, Instant exitTime) {
        var position = openPositions.remove(symbol);
        String strategy = openPositionStrategy.remove(symbol);
        if (position == null) return;
        double pnl = (exitPrice - position.entryPrice()) * position.quantity();
        equity += position.quantity() * exitPrice;
        closedTrades.add(new BacktestTrade(symbol, strategy, position.entryTime(), exitTime,
            position.entryPrice(), exitPrice, position.quantity(), pnl, exitReason));
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
