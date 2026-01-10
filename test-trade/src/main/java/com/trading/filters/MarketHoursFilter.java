package com.trading.filters;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.trading.config.Config;

import java.time.*;

/**
 * Filter to block trading outside market hours.
 * Standard: 9:30 AM - 4:00 PM EST
 * Extended: 4:00 AM - 8:00 PM EST (if enabled)
 */
public final class MarketHoursFilter {
    private static final Logger logger = LoggerFactory.getLogger(MarketHoursFilter.class);
    private static final ZoneId EST = ZoneId.of("America/New_York");
    
    // Standard Hours
    private static final LocalTime MARKET_OPEN = LocalTime.of(9, 30);
    private static final LocalTime MARKET_CLOSE = LocalTime.of(16, 0);
    
    // Extended Hours
    private static final LocalTime EXTENDED_OPEN = LocalTime.of(4, 0);
    private static final LocalTime EXTENDED_CLOSE = LocalTime.of(20, 0);
    
    private final Config config;
    
    public MarketHoursFilter(Config config) {
        this.config = config;
        
        // Log market hours on initialization
        LocalTime openTime = config.isExtendedHoursEnabled() ? EXTENDED_OPEN : MARKET_OPEN;
        LocalTime closeTime = config.isExtendedHoursEnabled() ? EXTENDED_CLOSE : MARKET_CLOSE;
        String hoursType = config.isExtendedHoursEnabled() ? "Extended" : "Standard";
        
        logger.info("═══════════════════════════════════════════════════════");
        logger.info("Market Hours Filter Initialized");
        logger.info("═══════════════════════════════════════════════════════");
        logger.info("Trading Hours: {} ({} - {} EST)", hoursType, openTime, closeTime);
        logger.info("Timezone: America/New_York (EST/EDT)");
        logger.info("Trading Days: Monday - Friday");
        logger.info("═══════════════════════════════════════════════════════");
        
        // Show current status
        ZonedDateTime now = ZonedDateTime.now(EST);
        boolean isOpen = isMarketOpen();
        if (isOpen) {
            logger.info("✅ Market is currently OPEN");
            logger.info("   Session: {}", getCurrentSession());
            logger.info("   Closes at: {} EST", closeTime);
        } else {
            logger.info("❌ Market is currently CLOSED");
            logger.info("   Reason: {}", getClosedReason());
            if (now.getDayOfWeek() != DayOfWeek.SATURDAY && now.getDayOfWeek() != DayOfWeek.SUNDAY) {
                logger.info("   Next open: {} EST", openTime);
            } else {
                logger.info("   Next open: Monday at {} EST", openTime);
            }
        }
        logger.info("═══════════════════════════════════════════════════════");
    }

    /**
     * Check if current time is within market hours.
     */
    public boolean isMarketOpen() {
        ZonedDateTime now = ZonedDateTime.now(EST);
        LocalTime currentTime = now.toLocalTime();
        DayOfWeek dayOfWeek = now.getDayOfWeek();

        // Check if it's a weekday
        boolean isWeekday = dayOfWeek != DayOfWeek.SATURDAY && dayOfWeek != DayOfWeek.SUNDAY;
        
        if (!isWeekday) {
            return false;
        }
        
        // Determine effective hours
        LocalTime openTime = config.isExtendedHoursEnabled() ? EXTENDED_OPEN : MARKET_OPEN;
        LocalTime closeTime = config.isExtendedHoursEnabled() ? EXTENDED_CLOSE : MARKET_CLOSE;
        
        // Check if within trading hours
        boolean withinHours = !currentTime.isBefore(openTime) && currentTime.isBefore(closeTime);

        return withinHours;
    }
    
    /**
     * Get current market session (PRE_MARKET, REGULAR, POST_MARKET, or CLOSED).
     */
    public String getCurrentSession() {
        if (!isMarketOpen()) {
            return "CLOSED";
        }
        
        ZonedDateTime now = ZonedDateTime.now(EST);
        LocalTime currentTime = now.toLocalTime();
        
        if (currentTime.isBefore(MARKET_OPEN)) {
            return "PRE_MARKET";
        } else if (currentTime.isBefore(MARKET_CLOSE)) {
            return "REGULAR";
        } else {
            return "POST_MARKET";
        }
    }

    /**
     * Get reason why market is closed.
     */
    public String getClosedReason() {
        ZonedDateTime now = ZonedDateTime.now(EST);
        LocalTime currentTime = now.toLocalTime();
        DayOfWeek dayOfWeek = now.getDayOfWeek();
        
        LocalTime openTime = config.isExtendedHoursEnabled() ? EXTENDED_OPEN : MARKET_OPEN;
        LocalTime closeTime = config.isExtendedHoursEnabled() ? EXTENDED_CLOSE : MARKET_CLOSE;

        if (dayOfWeek == DayOfWeek.SATURDAY || dayOfWeek == DayOfWeek.SUNDAY) {
            return "Weekend";
        } else if (currentTime.isBefore(openTime)) {
            return "Pre-market (opens at " + openTime + " EST)";
        } else {
            return "After-hours (closed at " + closeTime + " EST)";
        }
    }
}
