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
    private final String brokerName;
    private volatile int dayTradeCount = 0;
    private volatile boolean synced = false;

    public PDTProtection(TradeDatabase database, boolean enabled, String brokerName) {
        this.database = database;
        this.enabled = enabled;
        this.brokerName = brokerName;
        logger.info("PDT Protection initialized for {} - Enabled: {}", brokerName, enabled);
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

        // If not yet synced, block day trades to be safe
        if (!synced) {
            logger.warn("PDT [{}] count not yet synced — blocking sell to be safe", brokerName);
            return false;
        }

        int count = getDayTradeCount();

        if (count >= MAX_DAY_TRADES) {
            logger.warn("PDT [{}] PROTECTION: Blocking trade — {}/{} day trades used",
                brokerName, count, MAX_DAY_TRADES);
            return false;
        }

        if (count == MAX_DAY_TRADES - 1) {
            logger.warn("PDT [{}] WARNING: This would be day trade {}/{} — last one available!",
                brokerName, count + 1, MAX_DAY_TRADES);
        }

        logger.info("PDT [{}] day trade allowed — {}/{}", brokerName, count + 1, MAX_DAY_TRADES);
        return true;
    }

    /**
     * Sync day trade count from broker's account API (Alpaca: daytrade_count field).
     * For non-Alpaca brokers that don't report this, use initializeLocal(0) instead.
     */
    public void syncWithAlpaca(int count) {
        if (!synced) {
            logger.info("PDT [{}] synced from broker API: daytrade_count={}", brokerName, count);
        } else if (count != this.dayTradeCount) {
            logger.info("PDT [{}] count updated from broker: {} → {}", brokerName, this.dayTradeCount, count);
        }
        this.dayTradeCount = count;
        this.synced = true;
    }

    /**
     * Initialize PDT counter for brokers that don't report daytrade_count via API.
     * Marks counter as synced so sell orders aren't blocked on startup.
     */
    public void initializeLocal(int startCount) {
        this.dayTradeCount = startCount;
        this.synced = true;
        logger.info("PDT [{}] initialized with local tracking: count={}", brokerName, startCount);
    }

    /**
     * Get the count of day trades used today.
     */
    public int getDayTradeCount() {
        return dayTradeCount;
    }

    /**
     * Record a locally-tracked day trade (for brokers without broker-side daytrade_count).
     * Alpaca's counter is authoritative and refreshed each cycle via syncWithAlpaca().
     */
    public void recordDayTrade(String symbol) {
        dayTradeCount++;
        logger.info("PDT [{}] day trade recorded for {} — now {}/{}", brokerName, symbol, dayTradeCount, MAX_DAY_TRADES);
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
            return "PDT Protection [" + brokerName + "]: Waiting for sync...";
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
