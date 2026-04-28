package com.trading.earnings;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("EarningsCalendarService Tests (Tier 2.5)")
class EarningsCalendarServiceTest {

    private static final ZoneId NY = ZoneId.of("America/New_York");

    @Test
    @DisplayName("isInBlackout returns true within the ±window of an earnings date")
    void blackoutWithinWindow() {
        LocalDate earningsDay = LocalDate.of(2026, 5, 1);
        var svc = new EarningsCalendarService(
            Map.of("AAPL", Set.of(earningsDay)),
            60_000L);

        Instant midDay = ZonedDateTime.of(2026, 5, 1, 12, 0, 0, 0, NY).toInstant();
        assertTrue(svc.isInBlackout("AAPL", midDay, 24, 24));

        Instant dayBefore = ZonedDateTime.of(2026, 4, 30, 18, 0, 0, 0, NY).toInstant();
        assertTrue(svc.isInBlackout("AAPL", dayBefore, 24, 24));
    }

    @Test
    @DisplayName("isInBlackout returns false outside the window")
    void noBlackoutOutsideWindow() {
        LocalDate earningsDay = LocalDate.of(2026, 5, 1);
        var svc = new EarningsCalendarService(
            Map.of("AAPL", Set.of(earningsDay)),
            60_000L);

        Instant fiveDaysBefore = ZonedDateTime.of(2026, 4, 26, 12, 0, 0, 0, NY).toInstant();
        assertFalse(svc.isInBlackout("AAPL", fiveDaysBefore, 24, 24));
    }

    @Test
    @DisplayName("isInBlackout returns false for unknown symbol")
    void unknownSymbol() {
        var svc = new EarningsCalendarService(Map.of(), 60_000L);
        assertFalse(svc.isInBlackout("ZZZ", Instant.now(), 24, 24));
    }

    @Test
    @DisplayName("symbol matching is case-insensitive")
    void caseInsensitive() {
        LocalDate earningsDay = LocalDate.of(2026, 5, 1);
        var svc = new EarningsCalendarService(
            Map.of("aapl", Set.of(earningsDay)),
            60_000L);
        Instant midDay = ZonedDateTime.of(2026, 5, 1, 12, 0, 0, 0, NY).toInstant();
        assertTrue(svc.isInBlackout("AAPL", midDay, 24, 24));
        assertTrue(svc.isInBlackout("aapl", midDay, 24, 24));
    }

    @Test
    @DisplayName("parseCsv extracts reportDate column")
    void parseCsvHappyPath() {
        String csv = """
            symbol,name,reportDate,fiscalDateEnding,estimate,currency
            AAPL,Apple Inc,2026-05-01,2026-03-31,1.50,USD
            MSFT,Microsoft,2026-04-25,2026-03-31,2.50,USD
            """;
        var dates = EarningsCalendarService.parseCsv(csv);
        assertTrue(dates.contains(LocalDate.of(2026, 5, 1)));
        assertTrue(dates.contains(LocalDate.of(2026, 4, 25)));
    }

    @Test
    @DisplayName("parseCsv tolerates blank/empty input")
    void parseCsvTolerant() {
        assertTrue(EarningsCalendarService.parseCsv("").isEmpty());
        assertTrue(EarningsCalendarService.parseCsv(null).isEmpty());
        assertTrue(EarningsCalendarService.parseCsv("only,one,header,row").isEmpty());
    }

    @Test
    @DisplayName("parseCsv skips rows with malformed dates without throwing")
    void parseCsvSkipsBadRows() {
        String csv = """
            symbol,name,reportDate
            AAPL,Apple,not-a-date
            MSFT,Microsoft,2026-04-25
            """;
        var dates = EarningsCalendarService.parseCsv(csv);
        assertEquals(Set.of(LocalDate.of(2026, 4, 25)), dates);
    }

    @Test
    @DisplayName("blank/null symbol is a no-op")
    void blankSymbol() {
        var svc = new EarningsCalendarService(Map.of(), 60_000L);
        assertFalse(svc.isInBlackout("", Instant.now(), 24, 24));
        assertFalse(svc.isInBlackout(null, Instant.now(), 24, 24));
    }
}
