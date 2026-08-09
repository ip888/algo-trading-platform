package com.trading.strategy;

import com.trading.api.BrokerClient;
import com.trading.api.model.Bar;
import com.trading.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.function.Supplier;

/**
 * Intraday scalp strategy using 15-minute bars.
 *
 * Entry conditions (all must be true):
 *   1. Time window: 9:45–11:30 AM ET (morning momentum) or 14:00–15:00 ET (afternoon momentum)
 *   2. RSI in range [rsiBuyMin, rsiBuyMax] (default 40–62) AND RSI ≥ 50 (bullish bias)
 *   3. Current price ≥ VWAP (calculated from all intraday bars so far today)
 *   4. Last bar's volume ≥ volumeMultiplier × 20-bar average (institutional confirmation)
 *   5. No cooldown active for this symbol (45 min between entries on the same symbol)
 *
 * Returns {@link TradingSignal.ScalpBuy} with tight SL/TP embedded in the signal, so
 * ProfileManager bypasses the profile's swing-trade targets and uses scalp-specific levels.
 *
 * Daily trade cap prevents over-trading on choppy days.
 * No entry if positionQty > 0 (scalps are flat-in / flat-out only).
 */
public class ScalpStrategy {
    private static final Logger logger = LoggerFactory.getLogger(ScalpStrategy.class);

    private static final ZoneId ET = ZoneId.of("America/New_York");
    private static final int RSI_PERIOD = 14;
    private static final int VOLUME_LOOKBACK = 20;

    private final BrokerClient client;
    private final Config config;
    // Overridable clock — replaced in tests to simulate specific times of day
    private Supplier<ZonedDateTime> nowSupplier = () -> ZonedDateTime.now(ET);

    // Static: shared across MAIN and EXPERIMENTAL instances so both profiles
    // contribute to the same daily limit (prevents 2×SCALP_MAX_DAILY_TRADES total).
    // AtomicInteger prevents a race on the check-then-increment sequence when both
    // profiles evaluate the same symbol concurrently.
    private static final java.util.concurrent.atomic.AtomicInteger dailyScalpCount =
        new java.util.concurrent.atomic.AtomicInteger(0);
    private static volatile LocalDate lastCounterDate = null;

    // Per-symbol cooldown: after a scalp entry, block the same symbol for N minutes.
    // Prevents hammering the same symbol on every 20-second cycle when conditions hold.
    private static final java.util.concurrent.ConcurrentHashMap<String, Long> lastScalpEntryMs =
        new java.util.concurrent.ConcurrentHashMap<>();
    private static final long SYMBOL_COOLDOWN_MS = 45 * 60 * 1000L; // 45 minutes

    public ScalpStrategy(BrokerClient client, Config config) {
        this.client = client;
        this.config = config;
        logger.info("ScalpStrategy initialized: SL={}% TP={}% maxDaily={} window=[9:45–11:30, 14:00–15:00 ET]",
            config.getScalpStopLossPercent(), config.getScalpTakeProfitPercent(),
            config.getScalpMaxDailyTrades());
    }

    public TradingSignal evaluate(String symbol, double currentPrice, double positionQty) {
        if (!config.isScalpStrategyEnabled()) {
            return new TradingSignal.Hold("Scalp disabled");
        }
        if (positionQty > 0) {
            return new TradingSignal.Hold("Scalp: already in position");
        }

        resetDailyCounterIfNeeded();
        if (dailyScalpCount.get() >= config.getScalpMaxDailyTrades()) {
            return new TradingSignal.Hold(
                String.format("Scalp: daily limit reached (%d/%d)",
                    dailyScalpCount.get(), config.getScalpMaxDailyTrades()));
        }

        if (!isInScalpWindow()) {
            return new TradingSignal.Hold("Scalp: outside time window");
        }

        List<Bar> bars;
        try {
            bars = client.getBars(symbol, "15Min", 100);
        } catch (Exception e) {
            logger.debug("Scalp: failed to fetch 15-min bars for {}: {}", symbol, e.getMessage());
            return new TradingSignal.Hold("Scalp: bar fetch failed");
        }

        if (bars.size() < RSI_PERIOD + 2) {
            return new TradingSignal.Hold("Scalp: insufficient 15-min history");
        }

        LocalDate today = nowSupplier.get().toLocalDate();
        List<Bar> todayBars = bars.stream()
            .filter(b -> b.timestamp().atZone(ET).toLocalDate().equals(today))
            .toList();
        if (todayBars.isEmpty()) {
            return new TradingSignal.Hold("Scalp: no intraday bars for VWAP");
        }

        // --- Indicators (extracted for testability) ---
        double[] ind = computeIndicators(currentPrice, bars, todayBars);
        double rsi = ind[0];
        double rsiPrev = ind[1];
        double vwap = ind[2];
        double volumeRatio = ind[3];

        double rsiBuyMin = config.getScalpRsiBuyMin();
        double rsiBuyMax = config.getScalpRsiBuyMax();
        double volMultiplier = config.getScalpVolumeMultiplier();

        // --- Entry conditions ---
        // RSI-in-range: fires whenever RSI is between rsiBuyMin and rsiBuyMax (default 40–62).
        // Previously required RSI to cross from <50 to ≥50 on the current bar — that crossover
        // never fired in WEAK_BULL because stocks open with RSI already >50 and stay there.
        boolean rsiInWindow = rsi >= rsiBuyMin && rsi <= rsiBuyMax;
        boolean rsiAbove50 = rsi >= 50.0;        // bullish bias — must be above midpoint
        boolean priceAboveVwap = vwap > 0 && currentPrice >= vwap;
        boolean volumeConfirmed = volumeRatio >= volMultiplier;
        boolean onCooldown = isOnCooldown(symbol);

        logger.info("Scalp {}: RSI={} (prev={}) VWAP=${} price=${} vol={}x [inWindow={} rsiOk={} above50={} vwap={} vol={} cooldown={}]",
            symbol,
            String.format("%.1f", rsi), String.format("%.1f", rsiPrev),
            String.format("%.2f", vwap), String.format("%.2f", currentPrice),
            String.format("%.1f", volumeRatio),
            isInScalpWindow(), rsiInWindow, rsiAbove50, priceAboveVwap, volumeConfirmed, onCooldown);

        if (rsiInWindow && rsiAbove50 && priceAboveVwap && volumeConfirmed && !onCooldown) {
            int count = dailyScalpCount.incrementAndGet();
            lastScalpEntryMs.put(symbol, System.currentTimeMillis());
            String reason = String.format(
                "Scalp: RSI %.1f in window [%.0f–%.0f], above VWAP $%.2f, vol %.1f× avg [%d/%d today]",
                rsi, rsiBuyMin, rsiBuyMax, vwap, volumeRatio, count, config.getScalpMaxDailyTrades());
            logger.info("{}: SCALP BUY — {}", symbol, reason);
            return new TradingSignal.ScalpBuy(reason,
                config.getScalpStopLossPercent(), config.getScalpTakeProfitPercent());
        }

        // --- VWAP reclaim entry ---
        // When price crosses above VWAP from below with momentum, institutional buyers
        // are re-establishing positions at the day's average cost — reliable intraday signal.
        // Uses a slightly wider RSI window (45–65) since the reclaim itself confirms bullish bias.
        if (!onCooldown && vwap > 0 && bars.size() >= 2 && volumeConfirmed) {
            double prevClose = bars.get(bars.size() - 2).close();
            boolean vwapReclaim = prevClose < vwap && currentPrice >= vwap;
            boolean rsiBuilding = rsi >= 45.0 && rsi <= 65.0;
            if (vwapReclaim && rsiBuilding) {
                int count = dailyScalpCount.incrementAndGet();
                lastScalpEntryMs.put(symbol, System.currentTimeMillis());
                String reason = String.format(
                    "Scalp VWAP reclaim: $%.2f crossed above VWAP $%.2f, RSI=%.1f, vol=%.1f× [%d/%d today]",
                    currentPrice, vwap, rsi, volumeRatio, count, config.getScalpMaxDailyTrades());
                logger.info("{}: SCALP BUY (VWAP reclaim) — {}", symbol, reason);
                return new TradingSignal.ScalpBuy(reason,
                    config.getScalpStopLossPercent(), config.getScalpTakeProfitPercent());
            }
        }

        String blockReason = !rsiInWindow ? String.format("RSI=%.1f outside [%.0f–%.0f]", rsi, rsiBuyMin, rsiBuyMax)
            : !rsiAbove50 ? String.format("RSI=%.1f below 50", rsi)
            : !priceAboveVwap ? String.format("price $%.2f below VWAP $%.2f", currentPrice, vwap)
            : !volumeConfirmed ? String.format("vol %.1f× < %.1f×", volumeRatio, volMultiplier)
            : String.format("cooldown (%.0f min remaining)", cooldownMinutesLeft(symbol));
        return new TradingSignal.Hold("Scalp: " + blockReason);
    }

    /**
     * Computes [rsi, rsiPrev, vwap, volumeRatio] from raw bars.
     * Package-private so tests can subclass and inject controlled indicator values,
     * keeping bar-generation complexity out of the test.
     */
    double[] computeIndicators(double currentPrice, List<Bar> bars, List<Bar> todayBars) {
        List<Double> closes = bars.stream().map(Bar::close).toList();
        double rsi = RSIStrategy.calculateRSI(closes, RSI_PERIOD);
        double rsiPrev = RSIStrategy.calculateRSI(closes.subList(0, closes.size() - 1), RSI_PERIOD);
        double vwap = calculateVWAP(todayBars);
        double volRatio = volumeRatio(bars);
        return new double[]{rsi, rsiPrev, vwap, volRatio};
    }

    /** VWAP = Σ(typical_price × volume) / Σ(volume) for today's bars. */
    double calculateVWAP(List<Bar> bars) {
        double sumTPV = 0.0;
        double sumV = 0.0;
        for (Bar bar : bars) {
            double tp = (bar.high() + bar.low() + bar.close()) / 3.0;
            sumTPV += tp * bar.volume();
            sumV += bar.volume();
        }
        return sumV > 0 ? sumTPV / sumV : 0.0;
    }

    /** Ratio of last complete bar's volume to the 20-bar lookback average (excluding the last bar). */
    double volumeRatio(List<Bar> bars) {
        int last = bars.size() - 1;
        // Skip the forming bar: its 15-min window hasn't closed, so volume is still accumulating.
        // Comparing incomplete volume to settled bars gives a misleadingly high ratio.
        Bar lastBar = bars.get(last);
        java.time.Instant barEnd = lastBar.timestamp().plusSeconds(15 * 60);
        if (barEnd.isAfter(java.time.Instant.now())) {
            last--;
        }
        if (last < 1) return 0.0;
        int from = Math.max(0, last - VOLUME_LOOKBACK);
        double avg = bars.subList(from, last).stream()
            .mapToLong(Bar::volume)
            .average()
            .orElse(0.0);
        if (avg <= 0) return 0.0;
        return bars.get(last).volume() / avg;
    }

    /**
     * Scalp entry windows:
     *   Morning   09:45–11:30 — primary momentum window
     *   Midday    11:30–13:00 — trend continuation in active markets
     *   Afternoon 14:00–15:00 — second momentum burst before close
     */
    boolean isInScalpWindow() {
        LocalTime t = nowSupplier.get().toLocalTime();
        boolean morning   = !t.isBefore(LocalTime.of(9,  45)) && t.isBefore(LocalTime.of(11, 30));
        boolean midday    = !t.isBefore(LocalTime.of(11, 30)) && t.isBefore(LocalTime.of(13,  0));
        boolean afternoon = !t.isBefore(LocalTime.of(14,  0)) && t.isBefore(LocalTime.of(15,  0));
        return morning || midday || afternoon;
    }

    private void resetDailyCounterIfNeeded() {
        LocalDate today = nowSupplier.get().toLocalDate();
        if (!today.equals(lastCounterDate)) {
            dailyScalpCount.set(0);
            lastCounterDate = today;
        }
    }

    /** Visible for testing — injects a fixed clock so time-window checks are deterministic. */
    void setNowSupplier(Supplier<ZonedDateTime> supplier) { this.nowSupplier = supplier; }

    private boolean isOnCooldown(String symbol) {
        Long last = lastScalpEntryMs.get(symbol);
        return last != null && (System.currentTimeMillis() - last) < SYMBOL_COOLDOWN_MS;
    }

    private double cooldownMinutesLeft(String symbol) {
        Long last = lastScalpEntryMs.get(symbol);
        if (last == null) return 0.0;
        long elapsedMs = System.currentTimeMillis() - last;
        return Math.max(0.0, (SYMBOL_COOLDOWN_MS - elapsedMs) / 60_000.0);
    }

    /** Visible for testing. */
    int getDailyScalpCount() { return dailyScalpCount.get(); }

    /** Visible for testing — allows injecting a known count. */
    void setDailyScalpCount(int count, LocalDate date) {
        dailyScalpCount.set(count);
        lastCounterDate = date;
    }

    /** Visible for testing — clear per-symbol cooldown so tests can fire multiple entries. */
    static void clearCooldown(String symbol) { lastScalpEntryMs.remove(symbol); }

    /** Visible for testing — inject a last-entry timestamp to simulate an active cooldown. */
    static void setCooldown(String symbol, long epochMs) { lastScalpEntryMs.put(symbol, epochMs); }
}
