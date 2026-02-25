package com.trading.protection;

import com.trading.persistence.TradeDatabase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;

/**
 * Pattern Day Trader (PDT) Protection.
 * Prevents account from being flagged as a PDT by tracking and limiting day trades.
 * 
 * PDT Rule: 4+ day trades in 5 business days = PDT flag (requires $25,000 equity)
 * 
 * This class helps accounts with < $25,000 avoid PDT restrictions.
 */
public final class PDTProtection {
    private static final Logger logger = LoggerFactory.getLogger(PDTProtection.class);
    private static final int MAX_DAY_TRADES = 3; // Stay under 4 to avoid PDT flag
    private static final int BUSINESS_DAYS_WINDOW = 5;
    
    private final TradeDatabase database;
    private final boolean enabled;
    private final Map<String, Integer> dayTradeCountCache = new HashMap<>();
    private volatile int alpacaDayTradeCount = -1; // -1 = not synced yet
    
    public PDTProtection(TradeDatabase database, boolean enabled) {
        this.database = database;
        this.enabled = enabled;
        logger.info("PDT Protection initialized - Enabled: {}", enabled);
    }
    
    /**
     * Check if a trade would violate PDT rules.
     * 
     * @param symbol Stock symbol
     * @param isSell True if this is a sell order
     * @param accountEquity Current account equity
     * @return true if trade is allowed, false if it would trigger PDT
     */
    public boolean canTrade(String symbol, boolean isSell, double accountEquity) {
        if (!enabled) {
            return true; // PDT protection disabled
        }
        
        // PDT rules don't apply to accounts with $25,000+
        if (accountEquity >= 25000) {
            logger.debug("Account equity ${} >= $25,000 - PDT rules don't apply", 
                        String.format("%.2f", accountEquity));
            return true;
        }
        
        // Only check on sell orders (closing a position same day = day trade)
        if (!isSell) {
            return true; // Buy orders are always allowed
        }
        
        // Check if this would be a day trade
        if (!wouldBeDayTrade(symbol)) {
            return true; // Not a day trade, allowed
        }
        
        // Count day trades in last 5 business days
        int dayTradeCount = getDayTradeCount();
        
        // Log warnings
        if (dayTradeCount == 2) {
            logger.warn("⚠️ PDT WARNING: This is your 3rd day trade in 5 days. One more will flag your account!");
        } else if (dayTradeCount >= MAX_DAY_TRADES) {
            logger.error("🚫 PDT PROTECTION: Blocking 4th day trade to prevent PDT flag");
            logger.error("   Current day trades: {} (max: {})", dayTradeCount, MAX_DAY_TRADES);
            logger.error("   Wait {} business days or increase equity to $25,000+", 
                        BUSINESS_DAYS_WINDOW - dayTradeCount);
            return false;
        }
        
        logger.info("Day trade allowed - Count: {}/{}", dayTradeCount + 1, MAX_DAY_TRADES);
        return true;
    }
    
    /**
     * Check if selling this symbol would be a day trade.
     * A day trade is buying and selling the same symbol on the same day.
     */
    private boolean wouldBeDayTrade(String symbol) {
        LocalDate today = LocalDate.now(ZoneId.of("America/New_York"));
        return database.hasBuyToday(symbol, today);
    }
    
    /**
     * Sync day trade count from Alpaca's /v2/account endpoint.
     * This ensures our local tracking doesn't diverge from the broker's count.
     */
    public void syncWithAlpaca(int alpacaCount) {
        this.alpacaDayTradeCount = alpacaCount;
        int localCount = database.getDayTradesInLastNBusinessDays(BUSINESS_DAYS_WINDOW);
        if (alpacaCount != localCount) {
            logger.warn("PDT count mismatch: Alpaca={}, Local DB={}. Using higher value.",
                alpacaCount, localCount);
        }
        dayTradeCountCache.clear(); // Invalidate cache after sync
    }

    /**
     * Get the count of day trades in the last 5 business days.
     * Uses the higher of Alpaca's server count and local database count.
     */
    public int getDayTradeCount() {
        // Check cache first
        String cacheKey = LocalDate.now(ZoneId.of("America/New_York")).toString();
        if (dayTradeCountCache.containsKey(cacheKey)) {
            return dayTradeCountCache.get(cacheKey);
        }

        // Calculate day trades from local DB
        int localCount = database.getDayTradesInLastNBusinessDays(BUSINESS_DAYS_WINDOW);

        // Use higher of Alpaca and local to be safe
        int count = alpacaDayTradeCount >= 0 ? Math.max(localCount, alpacaDayTradeCount) : localCount;

        // Cache the result
        dayTradeCountCache.clear(); // Clear old cache
        dayTradeCountCache.put(cacheKey, count);

        return count;
    }
    
    /**
     * Record a day trade for tracking purposes.
     */
    public void recordDayTrade(String symbol) {
        logger.info("📊 Day trade recorded for {}", symbol);
        dayTradeCountCache.clear(); // Invalidate cache
    }
    
    /**
     * Get PDT status summary for dashboard.
     */
    public String getStatusSummary(double accountEquity) {
        if (!enabled) {
            return "PDT Protection: Disabled";
        }
        
        if (accountEquity >= 25000) {
            return "PDT Protection: Not Applicable (Equity >= $25,000)";
        }
        
        int count = getDayTradeCount();
        int remaining = MAX_DAY_TRADES - count;
        
        if (remaining <= 0) {
            return String.format("PDT Protection: ACTIVE - No day trades available (used %d/%d)", 
                               count, MAX_DAY_TRADES);
        } else if (remaining == 1) {
            return String.format("⚠️ PDT Warning: %d day trade remaining (used %d/%d)", 
                               remaining, count, MAX_DAY_TRADES);
        } else {
            return String.format("PDT Protection: OK - %d day trades available (used %d/%d)", 
                               remaining, count, MAX_DAY_TRADES);
        }
    }
}
