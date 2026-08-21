package com.trading.strategy;

import com.trading.api.BrokerClient;
import com.trading.api.model.Bar;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.*;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Opening Range Breakout (ORB) strategy.
 *
 * Defines the "opening range" as the high and low of the first ORB_MINUTES (30) minutes
 * after the 9:30am ET market open. A breakout above that high is a BUY signal; a breakdown
 * below the low while holding a position is a SELL signal.
 *
 * Why this has edge over MACD/RSI:
 *   MACD and RSI are lagging — they confirm moves that have already started. ORB identifies
 *   a structural price level (the range defined by the first half-hour auction) and enters
 *   the first time price decisively clears it. Institutional desks use ORB because the opening
 *   range represents real supply/demand equilibrium, not a mathematical derivative of past prices.
 *
 * Active window: 10:00am – 11:30am ET (after range is established, before lunch slows momentum).
 *   Outside this window the strategy returns HOLD, deferring to MACD/MTF signals.
 *
 * ORB levels are computed once per trading day per symbol and cached. The cache resets
 * automatically when a new trading day begins (date check on each evaluate() call).
 */
public class OpeningRangeBreakoutStrategy {
    private static final Logger logger = LoggerFactory.getLogger(OpeningRangeBreakoutStrategy.class);

    // 9:30–10:00am (30 min) defines the range; window end is hardcoded as LocalTime.of(10, 0)
    private static final int ORB_FETCH_BARS = 40;        // fetch slightly more than needed
    private static final int MIN_BARS_REQUIRED = 5;      // reject if fewer than 5 bars in window
    private static final ZoneId ET = ZoneId.of("America/New_York");

    // Breakout confirmation: price must exceed range boundary by this % to avoid false breakouts.
    // 0.05% (~3¢ on a $57 stock) was too thin — barely-above breakouts reverse immediately.
    // 0.20% requires a real move beyond the level before committing capital.
    private static final double BREAKOUT_BUFFER_PCT = 0.20;

    record OrbLevel(double high, double low, LocalDate date) {
        double breakoutAbove() { return high * (1.0 + BREAKOUT_BUFFER_PCT / 100.0); }
        double breakdownBelow() { return low * (1.0 - BREAKOUT_BUFFER_PCT / 100.0); }
        double rangeWidth() { return (high - low) / low * 100.0; }
    }

    private final ConcurrentHashMap<String, OrbLevel> levels = new ConcurrentHashMap<>();

    /**
     * Evaluate ORB signal for a symbol.
     *
     * Returns BUY on first confirmed breakout above ORB high (morning power hour only).
     * Returns SELL when holding and price breaks below ORB low.
     * Returns HOLD in all other cases (before range is set, inside range, outside active window).
     */
    public TradingSignal evaluate(String symbol, double currentPrice, double positionQty,
                                  BrokerClient client) {
        var etNow = ZonedDateTime.now(ET);
        var today = etNow.toLocalDate();
        var currentTime = etNow.toLocalTime();

        var orbEnd = LocalTime.of(10, 0);      // range complete at 10:00am
        var activeEnd = LocalTime.of(11, 30);  // ORB BUY signals valid until 11:30am

        // Exit signal is valid all day (not just morning) — if structure fails, sell
        var level = levels.get(symbol);

        // SELL: holding position and ORB structure fails (price breaks range low)
        if (positionQty > 0 && level != null && level.date().equals(today)) {
            if (currentPrice < level.breakdownBelow()) {
                logger.info("ORB {} SELL — structure failed, price ${} below range low ${} (range was {:.1f}%)",
                    symbol, String.format("%.2f", currentPrice),
                    String.format("%.2f", level.low()), level.rangeWidth());
                return new TradingSignal.Sell(
                    String.format("ORB structure failed — below range low $%.2f", level.low()));
            }
        }

        // Outside active BUY window → HOLD (defer to MACD/MTF)
        if (currentTime.isBefore(orbEnd) || !currentTime.isBefore(activeEnd)) {
            return new TradingSignal.Hold("outside ORB active window");
        }

        // Compute ORB if not yet done for today
        if (level == null || !level.date().equals(today)) {
            level = computeOrb(symbol, today, client);
            if (level == null) {
                return new TradingSignal.Hold("ORB not yet computed for " + symbol);
            }
            levels.put(symbol, level);
            logger.info("ORB {} range: low=${} high=${} width={:.2f}%",
                symbol,
                String.format("%.2f", level.low()),
                String.format("%.2f", level.high()),
                level.rangeWidth());
        }

        // BUY: price clears ORB high (with buffer), no existing position
        if (positionQty == 0 && currentPrice > level.breakoutAbove()) {
            logger.info("ORB {} BUY — breakout above ${} (range ${}-${})",
                symbol,
                String.format("%.2f", level.high()),
                String.format("%.2f", level.low()),
                String.format("%.2f", level.high()));
            return new TradingSignal.Buy(
                String.format("ORB breakout above $%.2f (range $%.2f–$%.2f, width %.2f%%)",
                    level.high(), level.low(), level.high(), level.rangeWidth()));
        }

        return new TradingSignal.Hold(
            String.format("within ORB range [$%.2f–$%.2f]", level.low(), level.high()));
    }

    /** True if ORB BUY signals are currently active (10:00–11:30am ET). */
    public boolean isActiveWindow() {
        var t = ZonedDateTime.now(ET).toLocalTime();
        return !t.isBefore(LocalTime.of(10, 0)) && t.isBefore(LocalTime.of(11, 30));
    }

    /** True if an ORB level has been computed for this symbol today. */
    public boolean hasLevel(String symbol) {
        var level = levels.get(symbol);
        return level != null && level.date().equals(LocalDate.now(ET));
    }

    private OrbLevel computeOrb(String symbol, LocalDate date, BrokerClient client) {
        try {
            List<Bar> bars = client.getBars(symbol, "1Min", ORB_FETCH_BARS);
            if (bars == null || bars.isEmpty()) return null;

            Instant openInstant = date.atTime(9, 30).atZone(ET).toInstant();
            Instant orbEndInstant = date.atTime(10, 0).atZone(ET).toInstant();

            double high = Double.MIN_VALUE;
            double low = Double.MAX_VALUE;
            int count = 0;
            for (Bar bar : bars) {
                Instant t = bar.timestamp();
                if (!t.isBefore(openInstant) && t.isBefore(orbEndInstant)) {
                    high = Math.max(high, bar.high());
                    low = Math.min(low, bar.low());
                    count++;
                }
            }

            if (count < MIN_BARS_REQUIRED || high == Double.MIN_VALUE) {
                logger.debug("ORB {} — insufficient bars in opening window ({} bars)", symbol, count);
                return null;
            }

            return new OrbLevel(high, low, date);
        } catch (Exception e) {
            logger.debug("ORB {} — bar fetch failed: {}", symbol, e.getMessage());
            return null;
        }
    }
}
