package com.trading.filters;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("MarketHoursFilter.isInOpeningWindow Tests (Tier 3.9)")
class MarketHoursFilterOpeningWindowTest {

    private static final ZoneId NY = ZoneId.of("America/New_York");

    @Test
    @DisplayName("9:30 ET is inside the 30-minute opening window")
    void atOpenIsInside() {
        ZonedDateTime t = ZonedDateTime.of(2026, 4, 28, 9, 30, 0, 0, NY);
        assertTrue(MarketHoursFilter.isInOpeningWindow(t, 30));
    }

    @Test
    @DisplayName("9:59 ET is inside the 30-minute opening window")
    void lateInWindowIsInside() {
        ZonedDateTime t = ZonedDateTime.of(2026, 4, 28, 9, 59, 0, 0, NY);
        assertTrue(MarketHoursFilter.isInOpeningWindow(t, 30));
    }

    @Test
    @DisplayName("10:00 ET is outside the 30-minute opening window (exclusive end)")
    void exactlyAtEndIsOutside() {
        ZonedDateTime t = ZonedDateTime.of(2026, 4, 28, 10, 0, 0, 0, NY);
        assertFalse(MarketHoursFilter.isInOpeningWindow(t, 30));
    }

    @Test
    @DisplayName("9:00 ET (pre-market) is outside the opening window")
    void preMarketIsOutside() {
        ZonedDateTime t = ZonedDateTime.of(2026, 4, 28, 9, 0, 0, 0, NY);
        assertFalse(MarketHoursFilter.isInOpeningWindow(t, 30));
    }

    @Test
    @DisplayName("Saturday is never in opening window")
    void saturdayExcluded() {
        ZonedDateTime t = ZonedDateTime.of(2026, 5, 2, 9, 35, 0, 0, NY);
        assertFalse(MarketHoursFilter.isInOpeningWindow(t, 30));
    }

    @Test
    @DisplayName("NYSE holiday is never in opening window")
    void holidayExcluded() {
        // 2026-01-01 is New Year's Day per MarketHoursFilter.NYSE_HOLIDAYS.
        ZonedDateTime t = ZonedDateTime.of(2026, 1, 1, 9, 35, 0, 0, NY);
        assertFalse(MarketHoursFilter.isInOpeningWindow(t, 30));
    }

    @Test
    @DisplayName("windowMinutes ≤ 0 disables the gate entirely")
    void zeroDisablesGate() {
        ZonedDateTime t = ZonedDateTime.of(2026, 4, 28, 9, 35, 0, 0, NY);
        assertFalse(MarketHoursFilter.isInOpeningWindow(t, 0));
        assertFalse(MarketHoursFilter.isInOpeningWindow(t, -5));
    }
}
