package com.trading.ops;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trading.api.AlpacaClient;
import com.trading.backtest.BacktestReportGenerator.BacktestReport;
import com.trading.backtest.WalkForwardBacktestHarness;
import com.trading.config.Config;
import com.trading.persistence.TradeDatabase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Weekly self-validation: re-runs the real, gated walk-forward harness (same one used for every
 * manual investigation this project has done) against the current live symbol universe and
 * config, on a schedule, instead of only when someone thinks to ask for it by hand.
 *
 * Why this exists: every real finding this month (the stop-inside-noise diagnosis, the MTF
 * confidence instability, the strategy-mix re-checks) came from manually running this same
 * harness after a human noticed something worth checking. This automates that habit so rolling
 * expectancy decay gets flagged on its own, on the only cadence the available data supports
 * (the backtest is capped at ~45-60 days of intraday history by the data feed, so re-running it
 * daily would just re-read the same window — weekly is frequent enough to catch a real shift
 * without pretending the data is fresher than it is).
 *
 * Never disables anything automatically — flags only. The last three "obvious-looking" fixes
 * this project tried were all rejected after multi-window testing found they didn't hold up; a
 * single automated run is even less trustworthy than that, so this is strictly an early-warning
 * signal for a human to look at, never an auto-pilot.
 */
public final class WeeklyValidationJob {

    private static final Logger logger = LoggerFactory.getLogger(WeeklyValidationJob.class);
    private static final ZoneId ET = ZoneId.of("America/New_York");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration RUN_INTERVAL = Duration.ofDays(7);
    private static final int BACKTEST_DAYS = 45; // the real ceiling — see loadHistory's own limit note
    // Flag (never disable) a strategy whose rolling window shows this many trades or more,
    // net P&L at or below zero. Below this trade count the window is too thin to mean anything
    // (same bar this project has applied by hand to every single-parameter test all month).
    private static final int MIN_TRADES_TO_FLAG = 10;

    private final TradeDatabase database;
    private final Config config;
    private final String brokerName;
    private static volatile WeeklyValidationJob instance;

    public WeeklyValidationJob(TradeDatabase database, Config config, String brokerName) {
        this.database = database;
        this.config = config;
        this.brokerName = brokerName;
    }

    public static void register(WeeklyValidationJob job) { instance = job; }
    public static WeeklyValidationJob instance() { return instance; }

    public void start() {
        Thread.ofVirtual().name("weekly-validation-job").start(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    tick(ZonedDateTime.now(ET));
                    Thread.sleep(Duration.ofHours(1));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    logger.warn("WeeklyValidationJob tick failed: {}", e.getMessage());
                }
            }
        });
        logger.info("WeeklyValidationJob started (re-validates every {} days, outside market hours)", RUN_INTERVAL.toDays());
    }

    /** Visible for testing. */
    void tick(ZonedDateTime nowEt) {
        // Only run outside the regular session — this is a read-only diagnostic, but the harness
        // fetches a non-trivial number of bars and there's no reason to compete with the live
        // loop's own API calls during market hours.
        var t = nowEt.toLocalTime();
        boolean marketHours = !t.isBefore(java.time.LocalTime.of(9, 30)) && t.isBefore(java.time.LocalTime.of(16, 0))
            && nowEt.getDayOfWeek().getValue() < 6;
        if (marketHours) return;

        String lastRun = database.loadBotState("weekly_validation_last_run");
        if (lastRun != null) {
            try {
                if (Duration.between(Instant.parse(lastRun), nowEt.toInstant()).compareTo(RUN_INTERVAL) < 0) return;
            } catch (Exception ignored) { /* malformed state: run now rather than get stuck forever */ }
        }
        runNow();
    }

    /** Runs immediately regardless of schedule. Exposed for the manual-trigger endpoint. */
    public synchronized Map<String, Object> runNow() {
        var result = new LinkedHashMap<String, Object>();
        result.put("ranAt", Instant.now().toString());
        result.put("windowDays", BACKTEST_DAYS);
        // Stamp the attempt (success or failure) unconditionally, in finally below, so a
        // reliably-failing run (e.g. a transient data-feed outage) still respects the 7-day
        // interval instead of retrying every hour on the next tick().
        try {
            var symbols = new LinkedHashSet<String>(config.getMainBullishSymbols());
            symbols.addAll(config.getMomentumAssets());
            var symbolList = new ArrayList<>(symbols);

            var liveClient = new AlpacaClient(config);
            Instant end = Instant.now();
            Instant start = end.minusSeconds((long) BACKTEST_DAYS * 86400L);
            Path cacheDir = Path.of(System.getProperty("java.io.tmpdir"), "weekly-validation-cache");
            var harness = new WalkForwardBacktestHarness(config, cacheDir, start);
            harness.loadHistory(liveClient, symbolList, BACKTEST_DAYS);
            BacktestReport report = harness.run(symbolList, start, end, 1_000.0, config.getMaxPositionsAtOnce());

            result.put("totalTrades", report.totalTrades());
            result.put("netPnl", round2(report.netPnl()));
            result.put("winRate", round2(report.winRate() * 100));

            var byStrategy = new LinkedHashMap<String, Object>();
            var flags = new ArrayList<String>();
            for (var s : report.byStrategy()) {
                var row = new LinkedHashMap<String, Object>();
                row.put("trades", s.count());
                row.put("winRate", round2(s.winRate() * 100));
                row.put("netPnl", round2(s.pnl()));
                byStrategy.put(s.strategy(), row);
                if (s.count() >= MIN_TRADES_TO_FLAG && s.pnl() <= 0) {
                    String msg = String.format("%s: %d trades over %dd, net $%.2f (%.0f%% win rate)",
                        s.strategy(), s.count(), BACKTEST_DAYS, s.pnl(), s.winRate() * 100);
                    flags.add(msg);
                }
            }
            result.put("byStrategy", byStrategy);
            result.put("flags", flags);

            String json = MAPPER.writeValueAsString(result);
            database.saveBotState("weekly_validation:latest", json);
            database.saveBotState("weekly_validation:" + Instant.now().toString().substring(0, 10), json);

            logger.info("[WEEKLY_VALIDATION] {}d window, {} trades, net ${}, {} strateg{} flagged",
                BACKTEST_DAYS, report.totalTrades(), String.format("%.2f", report.netPnl()),
                flags.size(), flags.size() == 1 ? "y" : "ies");
            for (String f : flags) {
                logger.warn("[WEEKLY_VALIDATION] flagged (not disabled, review manually): {}", f);
                com.trading.websocket.TradingWebSocketHandler.broadcastActivity(
                    "⚠️ Weekly validation flagged: " + f, "WARN");
            }
        } catch (Exception e) {
            logger.error("Weekly validation run failed: {}", e.getMessage(), e);
            result.put("error", e.getMessage());
        } finally {
            database.saveBotState("weekly_validation_last_run", Instant.now().toString());
        }
        return result;
    }

    public String loadLatest() {
        return database.loadBotState("weekly_validation:latest");
    }

    private static double round2(double v) { return Math.round(v * 100.0) / 100.0; }
}
