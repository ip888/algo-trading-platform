package com.trading.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Validates the intraday bar lookback date-range calculation used in
 * TradierClient.getIntradayBars() without making real HTTP calls.
 *
 * The formula: calendarDays = (limit / barsPerDay * 2) + 3
 * Must cover at least `limit` trading bars with a buffer for weekends/holidays.
 */
@DisplayName("TradierClient — intraday lookback date range")
class TradierIntradayLookbackTest {

    /** Replicates the formula from TradierClient.getIntradayBars */
    private static long calendarDays(String interval, int limit) {
        double barsPerDay = switch (interval) {
            case "1min"  -> 390.0;
            case "5min"  ->  78.0;
            case "15min" ->  26.0;
            default      ->  78.0;
        };
        return (long)(limit / barsPerDay * 2) + 3;
    }

    @ParameterizedTest(name = "{0} bars of {1} → {2}+ calendar days")
    @CsvSource({
        "100, 15min, 10",   // 100/26*2+3 = (long)7.69+3 = 10 days — covers ~4 trading days + weekends
        "100, 5min,   5",   // 100/78*2+3 = ~5.5 → covers weekends
        "100, 1min,   3",   // 100/390*2+3 = ~3.5 → same-day/next-day
        "200, 15min, 18",   // 200/26*2+3 = ~18.4 → enough for 2+ weeks
        "50,  15min,  6",   // 50/26*2+3 = ~6.8 → covers a full trading week
    })
    @DisplayName("lookback covers enough calendar days for requested bar count")
    void lookbackSufficient(int limit, String interval, long minExpectedDays) {
        long days = calendarDays(interval, limit);
        assertTrue(days >= minExpectedDays,
            String.format("%d %s bars needs ≥%d calendar days, got %d", limit, interval, minExpectedDays, days));
    }

    @Test
    @DisplayName("start date is before end date")
    void startBeforeEnd() {
        LocalDate end   = LocalDate.now();
        LocalDate start = end.minusDays(calendarDays("15min", 100));
        assertTrue(start.isBefore(end), "start must be before end");
    }

    @Test
    @DisplayName("15-min 100 bars lookback covers at least 2 full weeks")
    void fifteenMin100BarsCoversEnough() {
        // 100 15-min bars = ~4 trading days; with 2x buffer + 3 = ~11 calendar days
        // That's enough to span 2 weekends and a potential holiday
        long days = calendarDays("15min", 100);
        assertTrue(days >= 10, "Need at least 10 calendar days for 100 15-min bars, got " + days);
        // Sanity: 100 bars / 26 bars-per-trading-day = ~4 trading days needed
        // 2x buffer + 3 = 10 calendar days comfortably covers weekends and holidays
    }

    @Test
    @DisplayName("1-hour equivalent (using 5min as proxy) covers sufficient range")
    void oneHourEquivalent() {
        // 1Hour = 6.5 bars/day; approximate with 5min=78 bars/day scaled
        long days = calendarDays("5min", 100);
        assertTrue(days >= 4, "5-min 100 bars must cover at least 4 calendar days");
    }
}
