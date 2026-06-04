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
 *   2. RSI crossed above 50 on the current bar (previous bar RSI < 50, current ≥ 50)
 *   3. RSI ≤ rsiBuyMax (default 58) — not entering an already-extended intraday move
 *   4. Current price ≥ VWAP (calculated from all intraday bars so far today)
 *   5. Last bar's volume ≥ volumeMultiplier × 20-bar average (institutional confirmation)
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

    // Daily trade counter — reset at the start of each new trading day
    private int dailyScalpCount = 0;
    private LocalDate lastCounterDate = null;

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
        if (dailyScalpCount >= config.getScalpMaxDailyTrades()) {
            return new TradingSignal.Hold(
                String.format("Scalp: daily limit reached (%d/%d)",
                    dailyScalpCount, config.getScalpMaxDailyTrades()));
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
        boolean rsiInWindow = rsi >= rsiBuyMin && rsi <= rsiBuyMax;
        boolean rsiCrossedAbove50 = rsiPrev < 50.0 && rsi >= 50.0;
        boolean priceAboveVwap = vwap > 0 && currentPrice >= vwap;
        boolean volumeConfirmed = volumeRatio >= volMultiplier;

        logger.debug("Scalp {}: RSI={:.1f}↑{:.1f} VWAP=${:.2f} price=${:.2f} vol={:.1f}x [window={} cross={} aboveVwap={} vol={}]",
            symbol,
            String.format("%.1f", rsi), String.format("%.1f", rsiPrev),
            String.format("%.2f", vwap), String.format("%.2f", currentPrice),
            String.format("%.1f", volumeRatio),
            isInScalpWindow(), rsiCrossedAbove50, priceAboveVwap, volumeConfirmed);

        if (rsiInWindow && rsiCrossedAbove50 && priceAboveVwap && volumeConfirmed) {
            dailyScalpCount++;
            String reason = String.format(
                "Scalp: RSI %.1f crossed 50 (prev %.1f), above VWAP $%.2f, vol %.1f× avg [%d/%d today]",
                rsi, rsiPrev, vwap, volumeRatio, dailyScalpCount, config.getScalpMaxDailyTrades());
            logger.info("{}: SCALP BUY — {}", symbol, reason);
            return new TradingSignal.ScalpBuy(reason,
                config.getScalpStopLossPercent(), config.getScalpTakeProfitPercent());
        }

        return new TradingSignal.Hold(
            String.format("Scalp: waiting (RSI=%.1f/prev=%.1f cross=%b vwap=%b vol=%b)",
                rsi, rsiPrev, rsiCrossedAbove50, priceAboveVwap, volumeConfirmed));
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

    /** Ratio of last bar's volume to the 20-bar lookback average (excluding last bar). */
    double volumeRatio(List<Bar> bars) {
        int last = bars.size() - 1;
        int from = Math.max(0, last - VOLUME_LOOKBACK);
        double avg = bars.subList(from, last).stream()
            .mapToLong(Bar::volume)
            .average()
            .orElse(0.0);
        if (avg <= 0) return 0.0;
        return bars.get(last).volume() / avg;
    }

    /** Morning window 9:45–11:30 AM ET or afternoon momentum window 14:00–15:00 ET. */
    boolean isInScalpWindow() {
        LocalTime t = nowSupplier.get().toLocalTime();
        boolean morning = !t.isBefore(LocalTime.of(9, 45)) && t.isBefore(LocalTime.of(11, 30));
        boolean afternoon = !t.isBefore(LocalTime.of(14, 0)) && t.isBefore(LocalTime.of(15, 0));
        return morning || afternoon;
    }

    private void resetDailyCounterIfNeeded() {
        LocalDate today = nowSupplier.get().toLocalDate();
        if (!today.equals(lastCounterDate)) {
            dailyScalpCount = 0;
            lastCounterDate = today;
        }
    }

    /** Visible for testing — injects a fixed clock so time-window checks are deterministic. */
    void setNowSupplier(Supplier<ZonedDateTime> supplier) { this.nowSupplier = supplier; }

    /** Visible for testing. */
    int getDailyScalpCount() { return dailyScalpCount; }

    /** Visible for testing — allows injecting a known count. */
    void setDailyScalpCount(int count, LocalDate date) {
        dailyScalpCount = count;
        lastCounterDate = date;
    }
}
