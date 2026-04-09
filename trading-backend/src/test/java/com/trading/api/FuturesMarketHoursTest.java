package com.trading.api;

import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.*;

class FuturesMarketHoursTest {

    private static final java.time.ZoneId CT = FuturesMarketHours.CT;

    private ZonedDateTime ct(int year, int month, int day, int hour, int minute) {
        return ZonedDateTime.of(year, month, day, hour, minute, 0, 0, CT);
    }

    // ── isOpen ────────────────────────────────────────────────────────────────

    @Test
    void isOpen_duringTradingHours_returnsTrue() {
        // Monday 2:00 PM CT — well within trading hours
        ZonedDateTime monday2pm = ct(2026, 4, 6, 14, 0);
        assertTrue(FuturesMarketHours.isOpen(monday2pm));
    }

    @Test
    void isOpen_duringMaintenanceWindow_returnsFalse() {
        // Any weekday at 5:30 PM CT is in the maintenance window
        ZonedDateTime maintenanceTime = ct(2026, 4, 7, 17, 30); // Tuesday 5:30 PM CT
        assertFalse(FuturesMarketHours.isOpen(maintenanceTime));
    }

    @Test
    void isOpen_atMaintenanceStart_returnsFalse() {
        // Exactly 5:00 PM CT is the start of maintenance — closed
        ZonedDateTime maintenanceStart = ct(2026, 4, 7, 17, 0); // Tuesday 5:00 PM CT
        assertFalse(FuturesMarketHours.isOpen(maintenanceStart));
    }

    @Test
    void isOpen_atMaintenanceEnd_returnsTrue() {
        // Exactly 6:00 PM CT — maintenance ends, market reopens
        ZonedDateTime maintenanceEnd = ct(2026, 4, 7, 18, 0); // Tuesday 6:00 PM CT
        assertTrue(FuturesMarketHours.isOpen(maintenanceEnd));
    }

    @Test
    void isOpen_saturdayMorning_returnsFalse() {
        // Saturday 10:00 AM CT — always closed
        ZonedDateTime saturday = ct(2026, 4, 11, 10, 0);
        assertFalse(FuturesMarketHours.isOpen(saturday));
    }

    @Test
    void isOpen_saturdayEvening_returnsFalse() {
        // Saturday 8:00 PM CT — still closed
        ZonedDateTime saturdayEvening = ct(2026, 4, 11, 20, 0);
        assertFalse(FuturesMarketHours.isOpen(saturdayEvening));
    }

    @Test
    void isOpen_sundayBeforeOpen_returnsFalse() {
        // Sunday 5:00 PM CT — before 6 PM open
        ZonedDateTime sunday5pm = ct(2026, 4, 12, 17, 0);
        assertFalse(FuturesMarketHours.isOpen(sunday5pm));
    }

    @Test
    void isOpen_sundayAfterOpen_returnsTrue() {
        // Sunday 7:00 PM CT — after 6 PM open
        ZonedDateTime sunday7pm = ct(2026, 4, 12, 19, 0);
        assertTrue(FuturesMarketHours.isOpen(sunday7pm));
    }

    @Test
    void isOpen_sundayAtOpen_returnsTrue() {
        // Exactly Sunday 6:00 PM CT — market just opened
        ZonedDateTime sundayOpen = ct(2026, 4, 12, 18, 0);
        assertTrue(FuturesMarketHours.isOpen(sundayOpen));
    }

    @Test
    void isOpen_fridayBeforeClose_returnsTrue() {
        // Friday 4:00 PM CT — still open
        ZonedDateTime friday4pm = ct(2026, 4, 10, 16, 0);
        assertTrue(FuturesMarketHours.isOpen(friday4pm));
    }

    @Test
    void isOpen_fridayAtClose_returnsFalse() {
        // Friday exactly 5:00 PM CT — closed for the week
        ZonedDateTime fridayClose = ct(2026, 4, 10, 17, 0);
        assertFalse(FuturesMarketHours.isOpen(fridayClose));
    }

    @Test
    void isOpen_fridayAfterClose_returnsFalse() {
        // Friday 6:00 PM CT — still closed (weekly break until Sunday 6 PM)
        ZonedDateTime fridayEvening = ct(2026, 4, 10, 18, 0);
        assertFalse(FuturesMarketHours.isOpen(fridayEvening));
    }

    // ── nextOpen ──────────────────────────────────────────────────────────────

    @Test
    void nextOpen_duringSaturday_returnsSundayEvening() {
        ZonedDateTime saturday = ct(2026, 4, 11, 10, 0); // Saturday 10 AM CT
        ZonedDateTime nextOpen = FuturesMarketHours.nextOpen(saturday);
        // Should be Sunday April 12 at 6:00 PM CT
        assertEquals(ct(2026, 4, 12, 18, 0), nextOpen.withZoneSameInstant(CT));
    }

    @Test
    void nextOpen_duringMaintenanceWindow_returnsSameDayAt6pm() {
        // Tuesday 5:30 PM CT — maintenance window, reopens at 6 PM same day
        ZonedDateTime maintenance = ct(2026, 4, 7, 17, 30);
        ZonedDateTime nextOpen = FuturesMarketHours.nextOpen(maintenance);
        assertEquals(ct(2026, 4, 7, 18, 0), nextOpen.withZoneSameInstant(CT));
    }

    @Test
    void nextOpen_sundayBeforeOpen_returnsSundayAt6pm() {
        // Sunday 5:00 PM CT — waiting for today's open
        ZonedDateTime sunday5pm = ct(2026, 4, 12, 17, 0);
        ZonedDateTime nextOpen = FuturesMarketHours.nextOpen(sunday5pm);
        assertEquals(ct(2026, 4, 12, 18, 0), nextOpen.withZoneSameInstant(CT));
    }

    @Test
    void nextOpen_duringOpenMarket_returnsNow() {
        // Monday 2 PM CT — market is open, nextOpen returns the current time
        ZonedDateTime monday2pm = ct(2026, 4, 6, 14, 0);
        ZonedDateTime nextOpen = FuturesMarketHours.nextOpen(monday2pm);
        // Returns now (or close to it) since market is already open
        assertFalse(nextOpen.isAfter(monday2pm.plusMinutes(1)));
    }

    // ── nextClose ─────────────────────────────────────────────────────────────

    @Test
    void nextClose_duringTradingHours_returnsTodayMaintenanceStart() {
        // Monday 2 PM CT — next close is today at 5 PM
        ZonedDateTime monday2pm = ct(2026, 4, 6, 14, 0);
        ZonedDateTime nextClose = FuturesMarketHours.nextClose(monday2pm);
        assertEquals(ct(2026, 4, 6, 17, 0), nextClose.withZoneSameInstant(CT));
    }

    @Test
    void nextClose_onFriday_returnsFriday5pm() {
        // Friday 2 PM CT — next close is today at 5 PM (weekly close)
        ZonedDateTime friday2pm = ct(2026, 4, 10, 14, 0);
        ZonedDateTime nextClose = FuturesMarketHours.nextClose(friday2pm);
        assertEquals(ct(2026, 4, 10, 17, 0), nextClose.withZoneSameInstant(CT));
    }
}
