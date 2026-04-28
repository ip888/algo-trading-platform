package com.trading.analysis;

import com.trading.api.model.Bar;

import java.util.List;

/**
 * Average True Range — Wilder's smoothing.
 * TR = max(high − low, |high − prevClose|, |low − prevClose|)
 * ATR_t = ((period − 1) × ATR_{t-1} + TR_t) / period   (Wilder's RMA)
 *
 * Returns the ATR in absolute price units. Use atrPercent() for vol-as-fraction-of-price.
 */
public final class AtrCalculator {

    private AtrCalculator() {}

    /**
     * Compute ATR over the most recent {@code period} bars.
     *
     * @param bars   chronologically ordered bars (oldest → newest)
     * @param period lookback (e.g. 14)
     * @return ATR in price units, or 0.0 if {@code bars.size() < period + 1}.
     */
    public static double atr(List<Bar> bars, int period) {
        if (bars == null || period <= 0 || bars.size() < period + 1) {
            return 0.0;
        }

        // Seed with simple average of first {period} TRs.
        double sumTr = 0.0;
        for (int i = 1; i <= period; i++) {
            sumTr += trueRange(bars.get(i), bars.get(i - 1));
        }
        double atr = sumTr / period;

        // Wilder smoothing for the rest.
        for (int i = period + 1; i < bars.size(); i++) {
            double tr = trueRange(bars.get(i), bars.get(i - 1));
            atr = ((period - 1) * atr + tr) / period;
        }
        return atr;
    }

    /** ATR expressed as a fraction of the latest close (e.g. 0.012 = 1.2%). */
    public static double atrPercent(List<Bar> bars, int period) {
        double atr = atr(bars, period);
        if (atr <= 0.0 || bars == null || bars.isEmpty()) {
            return 0.0;
        }
        double lastClose = bars.get(bars.size() - 1).close();
        return lastClose > 0.0 ? atr / lastClose : 0.0;
    }

    private static double trueRange(Bar current, Bar previous) {
        double range = current.high() - current.low();
        double upGap = Math.abs(current.high() - previous.close());
        double downGap = Math.abs(current.low() - previous.close());
        return Math.max(range, Math.max(upGap, downGap));
    }
}
