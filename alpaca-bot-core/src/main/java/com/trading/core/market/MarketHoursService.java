package com.trading.core.market;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.trading.core.api.AlpacaClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Market Hours Service - Controls bot behavior based on market status.
 * 
 * Behaviors:
 * - CLOSED: Bot pauses all trading activities
 * - PRE_MARKET: Bot performs readiness check and prepares for trading
 * - OPEN: Full trading mode
 * - POST_MARKET: Analysis only, no new trades
 */
public class MarketHoursService {
    private static final Logger logger = LoggerFactory.getLogger(MarketHoursService.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    
    public enum MarketPhase {
        PRE_MARKET,      // 4:00 AM - 9:30 AM ET
        OPEN,            // 9:30 AM - 4:00 PM ET
        POST_MARKET,     // 4:00 PM - 8:00 PM ET
        CLOSED           // 8:00 PM - 4:00 AM ET, weekends, holidays
    }
    
    public record MarketClock(
        boolean isOpen,
        String nextOpen,
        String nextClose,
        MarketPhase phase,
        long minutesToOpen,
        long minutesToClose
    ) {}
    
    public record ReadinessReport(
        boolean ready,
        boolean alpacaConnected,
        boolean configLoaded,
        boolean watchlistReady,
        String message,
        java.time.Instant timestamp
    ) {}
    
    private final AlpacaClient client;
    private final AtomicReference<MarketClock> cachedClock = new AtomicReference<>();
    private final AtomicReference<ReadinessReport> lastReadiness = new AtomicReference<>();
    private volatile long lastClockFetch = 0;
    private static final long CACHE_DURATION_MS = 60_000; // 1 minute
    
    // US Market holidays (simplified - would need annual updates)
    private static final Set<MonthDay> US_HOLIDAYS = Set.of(
        MonthDay.of(1, 1),   // New Year's Day
        MonthDay.of(7, 4),   // Independence Day
        MonthDay.of(12, 25)  // Christmas
    );
    
    public MarketHoursService(AlpacaClient client) {
        this.client = client;
    }
    
    /**
     * Get current market clock from Alpaca (cached for 1 minute).
     */
    public MarketClock getMarketClock() {
        long now = System.currentTimeMillis();
        if (cachedClock.get() != null && (now - lastClockFetch) < CACHE_DURATION_MS) {
            return cachedClock.get();
        }
        
        try {
            String clockJson = client.getClockAsync().join();
            var json = mapper.readTree(clockJson);
            
            boolean isOpen = json.get("is_open").asBoolean();
            String nextOpen = json.has("next_open") ? json.get("next_open").asText() : "";
            String nextClose = json.has("next_close") ? json.get("next_close").asText() : "";
            
            MarketPhase phase = determinePhase(isOpen, nextOpen, nextClose);
            long minutesToOpen = calculateMinutesTo(nextOpen);
            long minutesToClose = calculateMinutesTo(nextClose);
            
            MarketClock clock = new MarketClock(isOpen, nextOpen, nextClose, phase, minutesToOpen, minutesToClose);
            cachedClock.set(clock);
            lastClockFetch = now;
            
            logger.info("🕐 Market Clock: {} | Next Open: {} | Next Close: {}", 
                phase, nextOpen, nextClose);
            
            return clock;
        } catch (Exception e) {
            logger.error("Failed to fetch market clock", e);
            // Fallback to time-based detection
            return getFallbackClock();
        }
    }
    
    /**
     * Determine market phase based on current time.
     */
    private MarketPhase determinePhase(boolean isOpen, String nextOpen, String nextClose) {
        if (isOpen) return MarketPhase.OPEN;
        
        ZonedDateTime now = ZonedDateTime.now(ZoneId.of("America/New_York"));
        int hour = now.getHour();
        
        // Weekend check
        DayOfWeek day = now.getDayOfWeek();
        if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) {
            return MarketPhase.CLOSED;
        }
        
        // Holiday check
        if (US_HOLIDAYS.contains(MonthDay.from(now))) {
            return MarketPhase.CLOSED;
        }
        
        // Time-based phase
        if (hour >= 4 && hour < 9) return MarketPhase.PRE_MARKET;
        if (hour == 9 && now.getMinute() < 30) return MarketPhase.PRE_MARKET;
        if (hour >= 16 && hour < 20) return MarketPhase.POST_MARKET;
        
        return MarketPhase.CLOSED;
    }
    
    /**
     * Fallback clock when Alpaca API is unavailable.
     */
    private MarketClock getFallbackClock() {
        ZonedDateTime now = ZonedDateTime.now(ZoneId.of("America/New_York"));
        MarketPhase phase = determinePhase(false, "", "");
        boolean isOpen = phase == MarketPhase.OPEN;
        
        return new MarketClock(isOpen, "", "", phase, -1, -1);
    }
    
    private long calculateMinutesTo(String isoTime) {
        if (isoTime == null || isoTime.isEmpty()) return -1;
        try {
            Instant target = Instant.parse(isoTime);
            return Duration.between(Instant.now(), target).toMinutes();
        } catch (Exception e) {
            return -1;
        }
    }
    
    /**
     * Perform pre-market readiness check.
     * Called 15-30 minutes before market open.
     */
    public ReadinessReport performReadinessCheck() {
        logger.info("🔍 Performing pre-market readiness check...");
        
        boolean alpacaOk = false;
        boolean configOk = false;
        boolean watchlistOk = false;
        StringBuilder message = new StringBuilder();
        
        // 1. Check Alpaca connectivity
        try {
            String account = client.getAccountAsync().join();
            var json = mapper.readTree(account);
            if (json.has("status") && "ACTIVE".equals(json.get("status").asText())) {
                alpacaOk = true;
                message.append("✅ Alpaca: CONNECTED (Account ACTIVE)\n");
            } else {
                message.append("⚠️ Alpaca: Account not ACTIVE\n");
            }
        } catch (Exception e) {
            message.append("❌ Alpaca: CONNECTION FAILED - ").append(e.getMessage()).append("\n");
        }
        
        // 2. Check config loaded
        try {
            String mode = com.trading.core.config.Config.getTradingMode();
            double capital = com.trading.core.config.Config.getInitialCapital();
            if (mode != null && capital > 0) {
                configOk = true;
                message.append("✅ Config: Loaded (").append(mode).append(", $").append(capital).append(")\n");
            } else {
                message.append("⚠️ Config: Invalid settings\n");
            }
        } catch (Exception e) {
            message.append("❌ Config: LOAD FAILED - ").append(e.getMessage()).append("\n");
        }
        
        // 3. Check watchlist
        try {
            var bullish = com.trading.core.config.Config.getMainBullishSymbols();
            if (bullish != null && !bullish.isEmpty()) {
                watchlistOk = true;
                message.append("✅ Watchlist: ").append(bullish.size()).append(" bullish symbols ready\n");
            } else {
                message.append("⚠️ Watchlist: Empty\n");
            }
        } catch (Exception e) {
            message.append("❌ Watchlist: LOAD FAILED - ").append(e.getMessage()).append("\n");
        }
        
        boolean ready = alpacaOk && configOk && watchlistOk;
        ReadinessReport report = new ReadinessReport(
            ready, alpacaOk, configOk, watchlistOk,
            ready ? "🟢 READY FOR TRADING" : "🔴 NOT READY - Issues detected",
            Instant.now()
        );
        
        lastReadiness.set(report);
        
        logger.info("📋 Readiness Check Complete: {}", ready ? "READY" : "NOT READY");
        logger.info(message.toString());
        
        return report;
    }
    
    /**
     * Get last readiness report.
     */
    public ReadinessReport getLastReadinessReport() {
        return lastReadiness.get();
    }
    
    /**
     * Check if trading should be active based on market phase.
     */
    public boolean shouldTrade() {
        MarketClock clock = getMarketClock();
        return clock.phase() == MarketPhase.OPEN;
    }
    
    /**
     * Check if pre-market check should run (15-30 min before open).
     */
    public boolean shouldRunPreMarketCheck() {
        MarketClock clock = getMarketClock();
        return clock.phase() == MarketPhase.PRE_MARKET && 
               clock.minutesToOpen() >= 0 && clock.minutesToOpen() <= 30;
    }
}
