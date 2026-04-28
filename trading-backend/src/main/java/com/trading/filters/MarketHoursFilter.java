package com.trading.filters;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.trading.config.Config;

import java.time.*;
import java.util.Set;

/**
 * Filter to block trading outside market hours.
 * Standard: 9:30 AM - 4:00 PM ET
 * Extended: 4:00 AM - 8:00 PM ET (if enabled)
 *
 * Also blocks trading on NYSE holidays (2025-2027).
 */
public final class MarketHoursFilter {
    private static final Logger logger = LoggerFactory.getLogger(MarketHoursFilter.class);
    private static final ZoneId EST = ZoneId.of("America/New_York");

    // Standard Hours
    private static final LocalTime MARKET_OPEN  = LocalTime.of(9, 30);
    private static final LocalTime MARKET_CLOSE = LocalTime.of(16, 0);

    // Extended Hours
    private static final LocalTime EXTENDED_OPEN  = LocalTime.of(4, 0);
    private static final LocalTime EXTENDED_CLOSE = LocalTime.of(20, 0);

    /**
     * NYSE observed holidays 2025-2027.
     * Source: NYSE holiday schedule (https://www.nyse.com/markets/hours-calendars)
     */
    private static final Set<LocalDate> NYSE_HOLIDAYS = Set.of(
        // 2025
        LocalDate.of(2025,  1,  1),  // New Year's Day
        LocalDate.of(2025,  1, 20),  // MLK Jr. Day
        LocalDate.of(2025,  2, 17),  // Presidents' Day
        LocalDate.of(2025,  4, 18),  // Good Friday
        LocalDate.of(2025,  5, 26),  // Memorial Day
        LocalDate.of(2025,  6, 19),  // Juneteenth
        LocalDate.of(2025,  7,  4),  // Independence Day
        LocalDate.of(2025,  9,  1),  // Labor Day
        LocalDate.of(2025, 11, 27),  // Thanksgiving
        LocalDate.of(2025, 12, 25),  // Christmas

        // 2026
        LocalDate.of(2026,  1,  1),  // New Year's Day
        LocalDate.of(2026,  1, 19),  // MLK Jr. Day
        LocalDate.of(2026,  2, 16),  // Presidents' Day
        LocalDate.of(2026,  4,  3),  // Good Friday
        LocalDate.of(2026,  5, 25),  // Memorial Day
        LocalDate.of(2026,  6, 19),  // Juneteenth
        LocalDate.of(2026,  7,  3),  // Independence Day (observed, July 4 is Sat)
        LocalDate.of(2026,  9,  7),  // Labor Day
        LocalDate.of(2026, 11, 26),  // Thanksgiving
        LocalDate.of(2026, 12, 25),  // Christmas

        // 2027
        LocalDate.of(2027,  1,  1),  // New Year's Day
        LocalDate.of(2027,  1, 18),  // MLK Jr. Day
        LocalDate.of(2027,  2, 15),  // Presidents' Day
        LocalDate.of(2027,  3, 26),  // Good Friday
        LocalDate.of(2027,  5, 31),  // Memorial Day
        LocalDate.of(2027,  6, 18),  // Juneteenth (observed, June 19 is Sat)
        LocalDate.of(2027,  7,  5),  // Independence Day (observed, July 4 is Sun)
        LocalDate.of(2027,  9,  6),  // Labor Day
        LocalDate.of(2027, 11, 25),  // Thanksgiving
        LocalDate.of(2027, 12, 24)   // Christmas (observed, Dec 25 is Sat)
    );

    private final Config config;

    public MarketHoursFilter(Config config) {
        this.config = config;

        LocalTime openTime  = config.isExtendedHoursEnabled() ? EXTENDED_OPEN  : MARKET_OPEN;
        LocalTime closeTime = config.isExtendedHoursEnabled() ? EXTENDED_CLOSE : MARKET_CLOSE;
        String hoursType    = config.isExtendedHoursEnabled() ? "Extended" : "Standard";

        logger.info("═══════════════════════════════════════════════════════");
        logger.info("Market Hours Filter Initialized");
        logger.info("═══════════════════════════════════════════════════════");
        logger.info("Trading Hours: {} ({} - {} ET)", hoursType, openTime, closeTime);
        logger.info("Timezone: America/New_York (EST/EDT)");
        logger.info("Trading Days: Monday - Friday (excl. NYSE holidays)");
        logger.info("═══════════════════════════════════════════════════════");

        boolean isOpen = isMarketOpen();
        if (isOpen) {
            logger.info("✅ Market is currently OPEN");
            logger.info("   Session: {}", getCurrentSession());
            logger.info("   Closes at: {} ET", closeTime);
        } else {
            logger.info("❌ Market is currently CLOSED");
            logger.info("   Reason: {}", getClosedReason());
        }
        logger.info("═══════════════════════════════════════════════════════");
    }

    /** Returns true if today is a NYSE holiday. */
    public static boolean isNyseHoliday(LocalDate date) {
        return NYSE_HOLIDAYS.contains(date);
    }

    /**
     * Returns true if {@code now} falls within the first {@code windowMinutes} after
     * the regular session open (9:30 ET). Used to block fresh entries during the noisy
     * opening auction. Weekends/holidays return false (already blocked elsewhere).
     */
    public static boolean isInOpeningWindow(ZonedDateTime now, int windowMinutes) {
        if (windowMinutes <= 0) return false;
        ZonedDateTime ny = now.withZoneSameInstant(EST);
        if (ny.getDayOfWeek() == DayOfWeek.SATURDAY || ny.getDayOfWeek() == DayOfWeek.SUNDAY) return false;
        if (isNyseHoliday(ny.toLocalDate())) return false;
        LocalTime t = ny.toLocalTime();
        LocalTime windowEnd = MARKET_OPEN.plusMinutes(windowMinutes);
        return !t.isBefore(MARKET_OPEN) && t.isBefore(windowEnd);
    }

    /** Convenience overload using the current clock. */
    public boolean isInOpeningWindow(int windowMinutes) {
        return isInOpeningWindow(ZonedDateTime.now(EST), windowMinutes);
    }

    /**
     * Check if current time is within market hours.
     */
    public boolean isMarketOpen() {
        ZonedDateTime now      = ZonedDateTime.now(EST);
        LocalDate today        = now.toLocalDate();
        LocalTime currentTime  = now.toLocalTime();
        DayOfWeek dayOfWeek    = now.getDayOfWeek();

        if (dayOfWeek == DayOfWeek.SATURDAY || dayOfWeek == DayOfWeek.SUNDAY) {
            return false;
        }

        if (isNyseHoliday(today)) {
            return false;
        }

        LocalTime openTime  = config.isExtendedHoursEnabled() ? EXTENDED_OPEN  : MARKET_OPEN;
        LocalTime closeTime = config.isExtendedHoursEnabled() ? EXTENDED_CLOSE : MARKET_CLOSE;

        return !currentTime.isBefore(openTime) && currentTime.isBefore(closeTime);
    }

    /**
     * Get current market session (PRE_MARKET, REGULAR, POST_MARKET, or CLOSED).
     */
    public String getCurrentSession() {
        if (!isMarketOpen()) {
            return "CLOSED";
        }
        LocalTime currentTime = ZonedDateTime.now(EST).toLocalTime();
        if (currentTime.isBefore(MARKET_OPEN))  return "PRE_MARKET";
        if (currentTime.isBefore(MARKET_CLOSE)) return "REGULAR";
        return "POST_MARKET";
    }

    /**
     * Get human-readable reason why market is closed.
     */
    public String getClosedReason() {
        ZonedDateTime now     = ZonedDateTime.now(EST);
        LocalDate today       = now.toLocalDate();
        LocalTime currentTime = now.toLocalTime();
        DayOfWeek dayOfWeek   = now.getDayOfWeek();

        LocalTime openTime  = config.isExtendedHoursEnabled() ? EXTENDED_OPEN  : MARKET_OPEN;
        LocalTime closeTime = config.isExtendedHoursEnabled() ? EXTENDED_CLOSE : MARKET_CLOSE;

        if (dayOfWeek == DayOfWeek.SATURDAY || dayOfWeek == DayOfWeek.SUNDAY) {
            return "Weekend";
        }
        if (isNyseHoliday(today)) {
            return "NYSE Holiday";
        }
        if (currentTime.isBefore(openTime)) {
            return "Pre-market (opens at " + openTime + " ET)";
        }
        return "After-hours (closed at " + closeTime + " ET)";
    }
}
