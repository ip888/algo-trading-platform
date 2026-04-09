package com.trading.api;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * CME Globex futures market hours utility.
 *
 * Schedule (all times CT = America/Chicago):
 *   Open:  Sunday 6:00 PM CT
 *   Close: Friday 5:00 PM CT
 *   Daily maintenance window: 5:00 PM – 6:00 PM CT (closed every day)
 *
 * The market is effectively open 23 hours/day, 5 days/week (Sun evening – Fri evening),
 * with a 1-hour maintenance break each day at 5 PM CT.
 */
public final class FuturesMarketHours {

    public static final ZoneId CT = ZoneId.of("America/Chicago");

    private static final LocalTime MAINTENANCE_START = LocalTime.of(17, 0); // 5:00 PM CT
    private static final LocalTime MAINTENANCE_END   = LocalTime.of(18, 0); // 6:00 PM CT
    private static final LocalTime SUNDAY_OPEN       = LocalTime.of(18, 0); // 6:00 PM CT Sunday

    private FuturesMarketHours() {}

    /**
     * Returns true if the futures market is open at the given instant.
     *
     * Closed when:
     *  - It is Saturday (any time)
     *  - It is Sunday before 6:00 PM CT
     *  - It is Friday after 5:00 PM CT
     *  - Daily maintenance window: 5:00 PM – 6:00 PM CT (any weekday including Sunday after open)
     */
    public static boolean isOpen(ZonedDateTime now) {
        ZonedDateTime ct = now.withZoneSameInstant(CT);
        DayOfWeek dow = ct.getDayOfWeek();
        LocalTime time = ct.toLocalTime();

        // Saturday: always closed
        if (dow == DayOfWeek.SATURDAY) return false;

        // Sunday: only open from 6 PM onward
        if (dow == DayOfWeek.SUNDAY) {
            return !time.isBefore(SUNDAY_OPEN);
        }

        // Friday: closed at or after 5 PM
        if (dow == DayOfWeek.FRIDAY && !time.isBefore(MAINTENANCE_START)) return false;

        // Daily maintenance window 5 PM – 6 PM (applies Mon–Thu fully, Sun only after 6 PM open,
        // but Sunday maintenance at 5 PM would be before the 6 PM open so it's already handled above)
        if (!time.isBefore(MAINTENANCE_START) && time.isBefore(MAINTENANCE_END)) return false;

        return true;
    }

    /**
     * Returns the next time the market opens after (or at) the given instant.
     * If the market is currently open, returns the end of the current maintenance window
     * or the next open after the current session ends — but callers typically only call
     * this when the market is closed, so we return the next open moment.
     */
    public static ZonedDateTime nextOpen(ZonedDateTime now) {
        ZonedDateTime ct = now.withZoneSameInstant(CT);

        // During maintenance window → opens at 6 PM same day (unless Friday)
        DayOfWeek dow = ct.getDayOfWeek();
        LocalTime time = ct.toLocalTime();

        if (!time.isBefore(MAINTENANCE_START) && time.isBefore(MAINTENANCE_END)
                && dow != DayOfWeek.FRIDAY && dow != DayOfWeek.SATURDAY) {
            return ct.toLocalDate().atTime(MAINTENANCE_END).atZone(CT);
        }

        // If market is open, next "open" concept doesn't apply well; return now
        if (isOpen(ct)) return ct;

        // Friday after 5 PM, Saturday, or Sunday before 6 PM → next open is Sunday 6 PM
        ZonedDateTime candidate = ct;
        // Advance to upcoming Sunday 6 PM
        int daysUntilSunday = (DayOfWeek.SUNDAY.getValue() - candidate.getDayOfWeek().getValue() + 7) % 7;
        if (daysUntilSunday == 0) {
            // It's Sunday — if before 6 PM we just need today's 6 PM
            ZonedDateTime sundayOpen = candidate.toLocalDate().atTime(SUNDAY_OPEN).atZone(CT);
            if (!candidate.isBefore(sundayOpen)) {
                // Already past Sunday 6 PM — shouldn't reach here if isOpen was false, but handle edge
                daysUntilSunday = 7;
            } else {
                return sundayOpen;
            }
        }
        return candidate.toLocalDate().plusDays(daysUntilSunday).atTime(SUNDAY_OPEN).atZone(CT);
    }

    /**
     * Returns the next time the market closes after the given instant.
     * If the market is currently closed, returns the close time of the next session.
     */
    public static ZonedDateTime nextClose(ZonedDateTime now) {
        ZonedDateTime ct = now.withZoneSameInstant(CT);
        DayOfWeek dow = ct.getDayOfWeek();

        // If open, next close is either today's maintenance start (5 PM) or Friday's close
        if (isOpen(ct)) {
            if (dow == DayOfWeek.FRIDAY) {
                return ct.toLocalDate().atTime(MAINTENANCE_START).atZone(CT);
            }
            // Next close is daily maintenance at 5 PM today
            ZonedDateTime maintenanceToday = ct.toLocalDate().atTime(MAINTENANCE_START).atZone(CT);
            if (ct.isBefore(maintenanceToday)) {
                return maintenanceToday;
            }
            // We're past today's maintenance already (open means we're in 6PM–5PM window)
            // Find next Friday 5 PM or next day's maintenance
            ZonedDateTime nextMaintenance = ct.toLocalDate().plusDays(1).atTime(MAINTENANCE_START).atZone(CT);
            // But if next day is Saturday, the close is Friday 5 PM which is already past
            if (ct.plusDays(1).getDayOfWeek() == DayOfWeek.SATURDAY) {
                // This case shouldn't happen (Friday after 5 PM is closed), but be safe
                return nextMaintenance;
            }
            return nextMaintenance;
        }

        // Market is closed — return the maintenance start of next open session
        ZonedDateTime openTime = nextOpen(ct);
        // Close is the maintenance window start the same day as openTime,
        // unless it's a Sunday open (close is that day's 5 PM, but market stays open through the week)
        // Actually the "next close" after next open is the next day's 5 PM maintenance
        return openTime.toLocalDate().plusDays(1).atTime(MAINTENANCE_START).atZone(CT);
    }
}
