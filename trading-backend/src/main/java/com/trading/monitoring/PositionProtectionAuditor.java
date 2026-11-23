package com.trading.monitoring;

import com.trading.api.AlpacaClient;
import com.trading.notifications.TelegramNotifier;
import com.trading.websocket.TradingWebSocketHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Audits positions to ensure they have proper stop-loss/take-profit protection.
 *
 * This is critical for small accounts where fractional shares are common,
 * as Alpaca doesn't support bracket orders for fractional quantities.
 *
 * The auditor:
 * 1. Tracks positions that lack server-side protection
 * 2. Alerts operators when new unprotected positions are detected
 * 3. Provides a list of all unprotected positions for dashboard display
 * 4. Helps ensure client-side monitoring is aware of which positions need extra attention
 */
public final class PositionProtectionAuditor {
    private static final Logger logger = LoggerFactory.getLogger(PositionProtectionAuditor.class);

    // Track positions we've already alerted about to avoid spam
    private final Set<String> alertedPositions = ConcurrentHashMap.newKeySet();

    // Track all currently unprotected positions
    private final Set<String> unprotectedPositions = ConcurrentHashMap.newKeySet();

    // Cooldown between alerts for the same position (1 hour)
    private static final long ALERT_COOLDOWN_MS = 3_600_000;
    private volatile Instant lastAuditTime = Instant.MIN;

    private final TelegramNotifier telegramNotifier;
    private final AlpacaClient alpacaClient;

    public PositionProtectionAuditor(TelegramNotifier telegramNotifier, AlpacaClient alpacaClient) {
        this.telegramNotifier = telegramNotifier;
        this.alpacaClient = alpacaClient;
        logger.info("PositionProtectionAuditor initialized");
    }

    /**
     * Record that a position was opened WITHOUT bracket protection.
     * This is called immediately when a fractional order is placed.
     *
     * @param symbol The symbol that lacks protection
     * @param quantity The quantity (for logging)
     * @param reason Why bracket wasn't used (e.g., "fractional shares")
     */
    public void recordUnprotectedPosition(String symbol, double quantity, String reason) {
        unprotectedPositions.add(symbol);

        logger.warn("⚠️ UNPROTECTED POSITION: {} ({} shares) - {}", symbol, quantity, reason);

        // Broadcast to dashboard immediately
        TradingWebSocketHandler.broadcastActivity(
            String.format("⚠️ UNPROTECTED: %s (%.3f shares) - %s. Client-side monitoring active.",
                symbol, quantity, reason),
            "WARN"
        );

        // Send Telegram alert if not recently alerted
        if (!alertedPositions.contains(symbol)) {
            alertedPositions.add(symbol);

            if (telegramNotifier != null) {
                telegramNotifier.sendMessage(String.format(
                    "⚠️ Position Protection Alert\n\n" +
                    "Symbol: %s\n" +
                    "Quantity: %.3f shares\n" +
                    "Protection: ❌ NO SERVER-SIDE SL/TP\n" +
                    "Reason: %s\n\n" +
                    "⚡ Client-side monitoring is active but has 10-second gaps.\n" +
                    "💡 Consider using whole share quantities for bracket protection.",
                    symbol, quantity, reason
                ));
            }
        }
    }

    /**
     * Record that a position was opened WITH full bracket protection.
     */
    public void recordProtectedPosition(String symbol) {
        unprotectedPositions.remove(symbol);
        alertedPositions.remove(symbol);

        logger.info("✅ PROTECTED POSITION: {} has server-side bracket orders", symbol);
    }

    /**
     * Record that a position was closed (remove from tracking).
     */
    public void recordPositionClosed(String symbol) {
        unprotectedPositions.remove(symbol);
        alertedPositions.remove(symbol);
        logger.debug("Position {} closed, removed from protection tracking", symbol);
    }

    /**
     * Perform a full audit of all positions against open orders.
     * This should be called periodically (e.g., every minute) to catch any positions
     * that might have lost their bracket orders (e.g., partial fill, order expired).
     *
     * @return List of symbols that lack proper SL/TP protection
     */
    public List<String> auditAllPositions() {
        List<String> unprotected = new ArrayList<>();

        try {
            var positions = alpacaClient.getPositions();

            for (var position : positions) {
                String symbol = position.symbol();
                var orders = alpacaClient.getOpenOrders(symbol);

                // A stop must be an active SELL with enough remaining quantity.
                // A BUY stop, stale/canceled order, or a stop for one share cannot
                // protect an unrelated or larger position.
                boolean hasStopLoss = hasSufficientBrokerStop(orders, symbol, position.quantity());
                boolean hasTakeProfit = hasSufficientTakeProfit(orders, symbol, position.quantity());

                boolean isFractional = Math.abs(position.quantity() - Math.floor(position.quantity())) > 0.0001;

                if (!hasStopLoss || !hasTakeProfit) {
                    unprotected.add(symbol);
                    recordUnprotectedPosition(symbol, position.quantity(),
                        "broker-side active protective orders missing or insufficient");

                    if (isFractional) {
                        logger.debug("{} is unprotected (fractional: {} shares) - expected behavior",
                            symbol, position.quantity());
                    } else {
                        // Whole shares without protection is more concerning
                        logger.warn("⚠️ {} is unprotected with {} WHOLE shares - unexpected!",
                            symbol, position.quantity());
                    }
                } else {
                    unprotectedPositions.remove(symbol);
                }
            }

            lastAuditTime = Instant.now();

            if (!unprotected.isEmpty()) {
                logger.info("Position audit complete: {}/{} positions lack server-side protection",
                    unprotected.size(), positions.size());
            }

        } catch (Exception e) {
            logger.error("Failed to audit positions", e);
        }

        return unprotected;
    }

    static boolean hasSufficientBrokerStop(com.fasterxml.jackson.databind.JsonNode orders,
                                           String symbol, double positionQty) {
        return hasCoveringOrder(orders, symbol, positionQty, true);
    }

    static boolean hasSufficientTakeProfit(com.fasterxml.jackson.databind.JsonNode orders,
                                          String symbol, double positionQty) {
        return hasCoveringOrder(orders, symbol, positionQty, false);
    }

    private static boolean hasCoveringOrder(com.fasterxml.jackson.databind.JsonNode orders,
                                            String symbol, double positionQty, boolean stop) {
        if (orders == null || !orders.isArray() || !Double.isFinite(positionQty) || positionQty <= 0) return false;
        for (var order : orders) {
            if (!symbol.equalsIgnoreCase(order.path("symbol").asText("")) ||
                !"sell".equalsIgnoreCase(order.path("side").asText(""))) continue;
            String status = order.path("status").asText("");
            if (!("new".equals(status) || "accepted".equals(status) ||
                  "partially_filled".equals(status) || "held".equals(status))) continue;
            String type = order.path("type").asText("");
            boolean correctType = stop
                ? ("stop".equals(type) || "stop_limit".equals(type) || "trailing_stop".equals(type))
                : "limit".equals(type);
            if (!correctType) continue;
            try {
                double qty = Double.parseDouble(order.path("qty").asText("NaN"));
                double filledQty = Double.parseDouble(order.path("filled_qty").asText("0"));
                if (Double.isFinite(qty) && Double.isFinite(filledQty)
                    && qty - filledQty + 0.0000001 >= positionQty) return true;
            } catch (NumberFormatException e) {
                // Unknown quantity cannot prove protection.
            }
        }
        return false;
    }

    /**
     * Get all currently tracked unprotected positions.
     */
    public Set<String> getUnprotectedPositions() {
        return Set.copyOf(unprotectedPositions);
    }

    /**
     * Get count of unprotected positions.
     */
    public int getUnprotectedCount() {
        return unprotectedPositions.size();
    }

    /**
     * Check if a specific position is tracked as unprotected.
     */
    public boolean isUnprotected(String symbol) {
        return unprotectedPositions.contains(symbol);
    }

    /**
     * Reset alert cooldown for a symbol (useful for testing).
     */
    public void resetAlertCooldown(String symbol) {
        alertedPositions.remove(symbol);
    }

    /**
     * Reset all tracking state.
     */
    public void reset() {
        unprotectedPositions.clear();
        alertedPositions.clear();
        logger.info("PositionProtectionAuditor reset");
    }
}
