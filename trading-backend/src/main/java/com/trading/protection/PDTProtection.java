package com.trading.protection;

import com.trading.persistence.TradeDatabase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Pattern Day Trader (PDT) Protection.
 * Prevents account from being flagged as a PDT by tracking and limiting day trades.
 *
 * PDT Rule: 4+ day trades in 5 business days = PDT flag (requires $25,000 equity)
 *
 * Uses Alpaca's daytrade_count from /v2/account as the sole source of truth.
 * Local SQLite DB is unreliable on ephemeral containers (Fly.io wipes it on deploy).
 */
public final class PDTProtection {
    private static final Logger logger = LoggerFactory.getLogger(PDTProtection.class);
    private static final int MAX_DAY_TRADES = 3; // Stay under 4 to avoid PDT flag

    private final TradeDatabase database;
    private final boolean enabled;
    private volatile int alpacaDayTradeCount = 0;
    private volatile boolean synced = false;

    public PDTProtection(TradeDatabase database, boolean enabled) {
        this.database = database;
        this.enabled = enabled;
        logger.info("PDT Protection initialized - Enabled: {} (using Alpaca as source of truth)", enabled);
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

        // If not yet synced with Alpaca, block day trades to be safe
        if (!synced) {
            logger.warn("PDT count not yet synced with Alpaca — blocking sell to be safe");
            return false;
        }

        int dayTradeCount = getDayTradeCount();

        if (dayTradeCount >= MAX_DAY_TRADES) {
            logger.warn("PDT PROTECTION: Blocking trade — {}/{} day trades used (Alpaca count)",
                dayTradeCount, MAX_DAY_TRADES);
            return false;
        }

        if (dayTradeCount == MAX_DAY_TRADES - 1) {
            logger.warn("PDT WARNING: This would be day trade {}/{} — last one available!",
                dayTradeCount + 1, MAX_DAY_TRADES);
        }

        logger.info("Day trade allowed - Count: {}/{} (Alpaca source)", dayTradeCount + 1, MAX_DAY_TRADES);
        return true;
    }

    /**
     * Sync day trade count from Alpaca's /v2/account endpoint.
     * This is the sole source of truth for PDT decisions.
     */
    public void syncWithAlpaca(int alpacaCount) {
        if (!synced) {
            logger.info("PDT synced with Alpaca: daytrade_count={}", alpacaCount);
        } else if (alpacaCount != this.alpacaDayTradeCount) {
            logger.info("PDT count updated from Alpaca: {} → {}", this.alpacaDayTradeCount, alpacaCount);
        }
        this.alpacaDayTradeCount = alpacaCount;
        this.synced = true;
    }

    /**
     * Get the count of day trades in the last 5 business days.
     * Uses Alpaca's server count as the sole source of truth.
     */
    public int getDayTradeCount() {
        return alpacaDayTradeCount;
    }

    /**
     * Record a day trade for tracking purposes (local logging only).
     */
    public void recordDayTrade(String symbol) {
        logger.info("Day trade recorded for {} (Alpaca count: {})", symbol, alpacaDayTradeCount);
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

        if (!synced) {
            return "PDT Protection: Waiting for Alpaca sync...";
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
