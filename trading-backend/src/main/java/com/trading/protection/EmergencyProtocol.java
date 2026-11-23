package com.trading.protection;

import com.trading.api.BrokerClient;
import com.trading.api.ResilientBrokerClient;
import com.trading.api.model.Position;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Handles emergency actions when the system detects a critical failure.
 * Primary action is "Flatten All" -> Cancel all orders and close all positions.
 * 
 * Alpaca (stocks) only.
 * 
 * CRITICAL: Uses direct AlpacaClient (bypasses circuit breaker) for emergency operations.
 * This ensures panic sells go through even when circuit breaker is open.
 * 
 * Modern design:
 * - Uses AtomicBoolean for lock-free triggered state
 * - compareAndSet for atomic check-and-set operation
 * - No synchronized keyword needed
 */
public class EmergencyProtocol {
    private static final Logger logger = LoggerFactory.getLogger(EmergencyProtocol.class);
    
    private final ResilientBrokerClient resilientClient;
    private final BrokerClient directClient; // For emergency bypass
    
    // Modern: AtomicBoolean for lock-free state management
    private final AtomicBoolean triggered = new AtomicBoolean(false);
    
    // Track last execution results
    private volatile Map<String, Object> lastExecutionResult;
    private volatile long lastTriggerTime;
    private volatile String lastTriggerReason;

    public EmergencyProtocol(ResilientBrokerClient client) {
        this.resilientClient = client;
        this.directClient = client.getDelegate(); // Bypass circuit breaker for emergencies
    }

    /**
     * Trigger the emergency protocol.
     * Uses compareAndSet for atomic check-and-trigger - no synchronized needed.
     * @param reason The cause of the emergency
     * @return Execution result with details of closed positions
     */
    public Map<String, Object> trigger(String reason) {
        // Atomic compare-and-set: only proceed if we're first to trigger
        if (!triggered.compareAndSet(false, true)) {
            logger.warn("Emergency protocol already triggered! Ignoring duplicate request: {}", reason);
            return Map.of(
                "status", "already_triggered",
                "message", "Emergency protocol was already triggered",
                "lastTriggerTime", lastTriggerTime,
                "lastReason", lastTriggerReason != null ? lastTriggerReason : "unknown"
            );
        }
        
        lastTriggerTime = System.currentTimeMillis();
        lastTriggerReason = reason;
        
        logger.error("🚨 EMERGENCY PROTOCOL ACTIVATED: {} 🚨", reason);
        
        Map<String, Object> result = new HashMap<>();
        result.put("status", "triggered");
        result.put("reason", reason);
        result.put("timestamp", lastTriggerTime);
        
        try {
            Map<String, Object> flattenResult = flattenAll();
            result.putAll(flattenResult);
            result.put("success", !flattenResult.containsKey("alpacaPositionsError")
                && Boolean.TRUE.equals(flattenResult.get("alpacaOrdersCancelled"))
                && ((java.util.List<?>) flattenResult.getOrDefault("alpacaPositions", java.util.List.of()))
                    .stream().noneMatch(p -> p instanceof java.util.Map<?, ?> m
                        && "failed".equals(m.get("status"))));
        } catch (Exception e) {
            logger.error("CRITICAL ERROR executing emergency protocol", e);
            result.put("success", false);
            result.put("error", e.getMessage());
        }
        
        lastExecutionResult = result;
        return result;
    }

    /**
     * Cancel all orders and close all positions immediately.
     * CRITICAL: Uses directClient to bypass circuit breaker for emergency operations.
     * @return Details of what was closed
     */
    private Map<String, Object> flattenAll() {
        Map<String, Object> result = new HashMap<>();
        List<Map<String, Object>> closed = new ArrayList<>();

        // Snapshot every currently held symbol's protective orders BEFORE bulk
        // cancel. A successful DELETE response alone is not terminal cancellation.
        final List<Position> originalPositions;
        final Map<String, List<String>> priorOrders = new HashMap<>();
        try {
            originalPositions = directClient.getPositions();
            if (originalPositions == null) throw new IllegalStateException("Broker positions unavailable");
            for (Position position : originalPositions) {
                var open = directClient.getOpenOrders(position.symbol());
                if (open == null || !open.isArray()) {
                    throw new IllegalStateException("Open orders unknown for " + position.symbol());
                }
                var ids = new ArrayList<String>();
                for (var order : open) {
                    String id = order.path("id").asText("");
                    if (id.isBlank()) throw new IllegalStateException("Open order missing ID");
                    ids.add(id);
                }
                priorOrders.put(position.symbol(), ids);
            }
        } catch (Exception e) {
            logger.error("PANIC: cannot snapshot broker orders; automatic liquidation aborted", e);
            result.put("alpacaOrdersCancelled", false);
            result.put("alpacaPositionsError", "Cannot establish pre-cancel broker state; manual intervention required");
            result.put("alpacaPositions", closed);
            return result;
        }

        try {
            directClient.cancelAllOrders();
            for (var entry : priorOrders.entrySet()) {
                for (String id : entry.getValue()) {
                    var terminal = directClient.getOrderById(id);
                    if (!ResilientBrokerClient.isConfirmedNoFillCancellation(terminal)) {
                        throw new IllegalStateException("Order " + id
                            + " may have filled or cancellation is still pending");
                    }
                }
                var open = directClient.getOpenOrders(entry.getKey());
                if (open == null || !open.isArray() || !open.isEmpty()) {
                    throw new IllegalStateException("Open orders remain for " + entry.getKey());
                }
            }
            result.put("alpacaOrdersCancelled", true);
        } catch (Exception e) {
            logger.error("PANIC: broker cancellations not terminal; no new SELL sent", e);
            result.put("alpacaOrdersCancelled", false);
            result.put("alpacaOrdersError", "Cancellation/fill race unresolved; manual intervention required");
            result.put("alpacaPositions", closed);
            return result;
        }

        try {
            // Fetch a new snapshot to avoid selling shares already removed by a
            // protective stop while cancellation was being processed.
            var currentPositions = directClient.getPositions();
            if (currentPositions == null) throw new IllegalStateException("Broker positions unavailable");
            for (Position pos : currentPositions) {
                try {
                    if (pos.quantity() <= 0.0) {
                        throw new IllegalStateException("Short/flat position needs manual handling");
                    }
                    var open = directClient.getOpenOrders(pos.symbol());
                    if (open == null || !open.isArray() || !open.isEmpty()) {
                        throw new IllegalStateException("Pending orders remain for " + pos.symbol());
                    }
                    // Retains single-attempt idempotency while bypassing the
                    // circuit breaker for emergency liquidation.
                    resilientClient.placeOrderDirect(pos.symbol(), pos.quantity(),
                        "sell", "market", "day", null);
                    closed.add(Map.of(
                        "symbol", pos.symbol(), "quantity", pos.quantity(),
                        "platform", "alpaca", "status", "filled"));
                } catch (Exception e) {
                    logger.error("PANIC: SELL fill unconfirmed for {}", pos.symbol(), e);
                    closed.add(Map.of(
                        "symbol", pos.symbol(), "quantity", pos.quantity(),
                        "platform", "alpaca", "status", "failed",
                        "error", "Broker status unresolved; manual review required"));
                }
            }
        } catch (Exception e) {
            logger.error("PANIC: unable to obtain final broker positions", e);
            result.put("alpacaPositionsError", "Broker positions unavailable; manual intervention required");
        }
        result.put("alpacaPositions", closed);
        return result;
    }

    /**
     * Reset the emergency protocol so it can be triggered again.
     * Use with caution - only after system has stabilized.
     */
    public Map<String, Object> reset() {
        boolean wasTriggered = triggered.getAndSet(false);
        logger.warn("🔄 Emergency Protocol RESET (was triggered: {})", wasTriggered);
        
        return Map.of(
            "status", "reset",
            "wasTriggered", wasTriggered,
            "previousTriggerTime", lastTriggerTime,
            "previousReason", lastTriggerReason != null ? lastTriggerReason : "none"
        );
    }
    
    public boolean isTriggered() {
        return triggered.get();
    }

    public Map<String, Object> getLastExecutionResult() {
        return lastExecutionResult;
    }

    public long getLastTriggerTime() {
        return lastTriggerTime;
    }

    /** Reason passed to the most recent {@link #trigger}, or null if never triggered this process. */
    public String getLastTriggerReason() {
        return lastTriggerReason;
    }
}
