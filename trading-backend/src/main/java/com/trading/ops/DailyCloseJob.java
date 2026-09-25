package com.trading.ops;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trading.api.BrokerClient;
import com.trading.config.Config;
import com.trading.persistence.TradeDatabase;
import com.trading.persistence.TradeDatabase.TradeRow;
import com.trading.websocket.TradingWebSocketHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * End-of-day job: re-price the day's trades from real Alpaca fills, then build, persist and (if needed)
 * alert on a daily digest.
 *
 * Why it exists: Fly's log buffer only holds ~40 minutes, so by the time anyone looks, the trading day's
 * evidence is gone. The digest is stored in {@code bot_state} ("digest:YYYY-MM-DD") and served by
 * /api/digest/*, so every day's outcome, attribution and broker-vs-DB gap survives restarts.
 *
 * Alerting: a comparable (flat) day whose |DB P&L − Alpaca equity change| exceeds DIGEST_GAP_ALERT_USD, or
 * any position still open after the close, raises an ERROR log line ([DIGEST_ALERT]) and a dashboard
 * broadcast. It never trades and never throws into the trading loop.
 */
public final class DailyCloseJob {

    private static final Logger logger = LoggerFactory.getLogger(DailyCloseJob.class);
    private static final ZoneId ET = ZoneId.of("America/New_York");
    private static final LocalTime RUN_AFTER = LocalTime.of(16, 10);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static volatile DailyCloseJob instance;

    private final TradeDatabase database;
    private final BrokerClient client;
    private final Config config;
    private final String brokerName;

    public DailyCloseJob(TradeDatabase database, BrokerClient client, Config config, String brokerName) {
        this.database = database;
        this.client = client;
        this.config = config;
        this.brokerName = brokerName;
    }

    public static void register(DailyCloseJob job) { instance = job; }
    public static DailyCloseJob instance() { return instance; }

    /** Poll once a minute; run at most once per trading date, after the close. */
    public void start() {
        Thread.ofVirtual().name("daily-close-job").start(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    tick(ZonedDateTime.now(ET));
                    Thread.sleep(Duration.ofSeconds(60));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    logger.warn("DailyCloseJob tick failed: {}", e.getMessage());
                }
            }
        });
        logger.info("DailyCloseJob started (runs after {} ET on trading weekdays)", RUN_AFTER);
    }

    /** Visible for testing. */
    void tick(ZonedDateTime nowEt) {
        DayOfWeek d = nowEt.getDayOfWeek();
        if (d == DayOfWeek.SATURDAY || d == DayOfWeek.SUNDAY) return;
        if (nowEt.toLocalTime().isBefore(RUN_AFTER)) return;
        LocalDate date = nowEt.toLocalDate();
        if (database.loadBotState("digest_done:" + date) != null) return;
        runFor(date);
    }

    /** Reconcile fills, build + persist the digest for {@code date} (ET). Safe to re-run (idempotent). */
    public synchronized Map<String, Object> runFor(LocalDate date) {
        var digest = new LinkedHashMap<String, Object>();
        var alerts = new ArrayList<String>();
        digest.put("date", date.toString());
        try {
            Instant from = date.atStartOfDay(ET).toInstant();
            Instant to = date.plusDays(1).atStartOfDay(ET).toInstant();

            // 1) Re-price from real fills (best effort — never blocks the digest)
            var applied = new ArrayList<Map<String, Object>>();
            var skipped = new ArrayList<String>();
            try {
                var trades = database.getClosedTradesBetween(brokerName, from, to);
                var orders = client.getOrderHistory(null, 500);
                var result = FillReconciler.reconcile(trades, orders, 0.03);
                for (var c : result.corrections()) {
                    database.updateTradeFills(c.tradeId(), c.newEntry(), c.newExit(), c.newPnl());
                    var row = new LinkedHashMap<String, Object>();
                    row.put("symbol", c.symbol());
                    row.put("pnlBefore", round2(c.oldPnl()));
                    row.put("pnlAfter", round2(c.newPnl()));
                    applied.add(row);
                    logger.info("[FILL_RECON] {} #{} pnl {} -> {} (entry {} -> {}, exit {} -> {})", c.symbol(), c.tradeId(),
                        String.format("%.2f", c.oldPnl()), String.format("%.2f", c.newPnl()),
                        String.format("%.3f", c.oldEntry()), String.format("%.3f", c.newEntry()),
                        String.format("%.3f", c.oldExit()), String.format("%.3f", c.newExit()));
                }
                skipped.addAll(result.skipped());
            } catch (Exception e) {
                logger.warn("Fill reconciliation failed: {}", e.getMessage());
                skipped.add("fill reconciliation failed: " + e.getMessage());
            }
            digest.put("fillCorrections", applied);
            digest.put("fillCorrectionsSkipped", skipped);

            // 2) Day summary from the (now corrected) DB rows
            List<TradeRow> trades = database.getClosedTradesBetween(brokerName, from, to);
            int wins = 0;
            double net = 0;
            var bySource = new TreeMap<String, double[]>();   // n, wins, net
            var byExit = new TreeMap<String, double[]>();
            var bySymbol = new TreeMap<String, double[]>();
            int scalpTrades = 0;
            for (TradeRow t : trades) {
                net += t.pnl();
                if (t.pnl() > 0) wins++;
                String src = sourceOf(t);
                if ("SCALP".equals(src)) scalpTrades++;
                add(bySource, src, t.pnl());
                add(byExit, t.exitReason() == null ? "unknown" : t.exitReason(), t.pnl());
                add(bySymbol, t.symbol(), t.pnl());
            }
            digest.put("trades", trades.size());
            digest.put("wins", wins);
            digest.put("winRate", trades.isEmpty() ? 0.0 : round2(100.0 * wins / trades.size()));
            digest.put("dbNetPnL", round2(net));
            digest.put("bySource", table(bySource));
            digest.put("byExitReason", table(byExit));
            digest.put("bySymbol", table(bySymbol));
            digest.put("scalpTrades", scalpTrades);

            // 3) Broker truth + gap. The account endpoint only knows "today vs last close", so a manual
            // re-run for a PAST date (e.g. a fill-repricing backfill) has no broker figure to compare with.
            boolean historical = date.isBefore(LocalDate.now(ET));
            digest.put("historical", historical);
            int openPositions = 0;
            double gap = 0;
            if (!historical) {
                var account = client.getAccount();
                double equity = account.path("equity").asDouble();
                double lastEquity = account.has("last_equity") ? account.path("last_equity").asDouble() : equity;
                openPositions = client.getPositions().size();
                double brokerDelta = equity - lastEquity;
                gap = net - brokerDelta;
                digest.put("equity", round2(equity));
                digest.put("lastEquity", round2(lastEquity));
                digest.put("brokerEquityDelta", round2(brokerDelta));
                digest.put("gap", round2(gap));
                digest.put("openPositions", openPositions);
                digest.put("comparable", openPositions == 0);
            }

            // 4) What the bot declined to do (ET-day window)
            var blocked = new TreeMap<String, Integer>();
            for (var row : database.getBlockedEntries(2, 5000)) {
                try {
                    Instant ts = Instant.parse(String.valueOf(row.get("ts")).replace(' ', 'T') + "Z");
                    if (ts.isBefore(from) || !ts.isBefore(to)) continue;
                } catch (Exception e) { continue; }
                blocked.merge(String.valueOf(row.get("reason")).replaceAll("\\d+(\\.\\d+)?", "#"), 1, Integer::sum);
            }
            digest.put("blockedEntries", blocked);

            // 5) Alerts
            double limit = config.getDigestGapAlertUsd();
            if (!historical && openPositions == 0 && Math.abs(gap) > limit) {
                alerts.add(String.format("DB P&L differs from Alpaca equity change by $%.2f (limit $%.2f)", gap, limit));
            }
            if (openPositions > 0) {
                alerts.add(openPositions + " position(s) still open after the close (EOD flatten should have closed them)");
            }
            digest.put("alerts", alerts);

            String json = MAPPER.writeValueAsString(digest);
            database.saveBotState("digest:" + date, json);
            if (!historical) {
                database.saveBotState("digest:latest", json);
                database.saveBotState("digest_done:" + date, Instant.now().toString());
            }
            logger.info("[DAILY_DIGEST] {} trades={} wins={} dbNet={} gap={} open={} alerts={} historical={}",
                date, trades.size(), wins, String.format("%.2f", net),
                String.format("%.2f", gap), openPositions, alerts.size(), historical);
            for (String a : alerts) {
                logger.error("[DIGEST_ALERT] {} — {}", date, a);
                TradingWebSocketHandler.broadcastActivity("🚨 DAILY DIGEST ALERT " + date + ": " + a, "ERROR");
            }
        } catch (Exception e) {
            logger.error("Daily digest for {} failed: {}", date, e.getMessage(), e);
            digest.put("error", e.getMessage());
        }
        return digest;
    }

    /** Persisted digest JSON for a date, or null. */
    public String loadDigest(String dateOrLatest) {
        return database.loadBotState("digest:" + dateOrLatest);
    }

    /** Persisted digests, newest first. */
    public List<String> loadHistory(int days) {
        var all = new TreeMap<>(database.loadBotStateWithPrefix("digest:2"));
        var out = new ArrayList<String>();
        for (var e : all.descendingMap().entrySet()) {
            out.add(e.getValue());
            if (out.size() >= Math.max(1, days)) break;
        }
        return out;
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** Real signal source from entry_reason ("MACD | MTF Direct Buy | regime=..."), SCALP for scalps. */
    static String sourceOf(TradeRow t) {
        if ("SCALP".equals(t.strategy())) return "SCALP";
        String r = t.entryReason();
        if (r != null) {
            String[] parts = r.split("\\|");
            if (parts.length > 1 && !parts[1].trim().startsWith("regime=")) return parts[1].trim();
        }
        return "unattributed";
    }

    private static void add(Map<String, double[]> m, String key, double pnl) {
        double[] v = m.computeIfAbsent(key, k -> new double[3]);
        v[0] += 1; if (pnl > 0) v[1] += 1; v[2] += pnl;
    }

    private static Map<String, Map<String, Object>> table(Map<String, double[]> m) {
        var out = new LinkedHashMap<String, Map<String, Object>>();
        m.forEach((k, v) -> {
            var row = new LinkedHashMap<String, Object>();
            row.put("n", (int) v[0]);
            row.put("wins", (int) v[1]);
            row.put("net", round2(v[2]));
            out.put(k, row);
        });
        return out;
    }

    private static double round2(double v) { return Math.round(v * 100.0) / 100.0; }
}
