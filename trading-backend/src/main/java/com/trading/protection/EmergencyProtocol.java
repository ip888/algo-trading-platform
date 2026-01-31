package com.trading.protection;

import com.trading.api.AlpacaClient;
import com.trading.api.ResilientAlpacaClient;
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
    
    private final ResilientAlpacaClient resilientClient;
    private final AlpacaClient directClient; // For emergency bypass
    
    // Modern: AtomicBoolean for lock-free state management
    private final AtomicBoolean triggered = new AtomicBoolean(false);
    
    // Track last execution results
    private volatile Map<String, Object> lastExecutionResult;
    private volatile long lastTriggerTime;
    private volatile String lastTriggerReason;

    public EmergencyProtocol(ResilientAlpacaClient client) {
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
            result.put("success", true);
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
        List<Map<String, Object>> alpacaClosed = new ArrayList<>();
        
        // ===== STEP 1: Cancel ALL Alpaca Orders =====
        // Use directClient to bypass circuit breaker
        logger.warn("STEP 1: Cancelling all Alpaca open orders (direct API - bypassing circuit breaker)...");
        try {
            directClient.cancelAllOrders();
            result.put("alpacaOrdersCancelled", true);
            logger.info("✅ All Alpaca orders cancelled.");
        } catch (Exception e) {
            logger.error("❌ Failed to cancel Alpaca orders", e);
            result.put("alpacaOrdersCancelled", false);
            result.put("alpacaOrdersError", e.getMessage());
        }

        // ===== STEP 2: Close ALL Alpaca Positions =====
        // Use directClient to bypass circuit breaker
        logger.warn("STEP 2: Closing all Alpaca positions (direct API - bypassing circuit breaker)...");
        try {
            List<Position> positions = directClient.getPositions();
            if (positions.isEmpty()) {
                logger.info("No open Alpaca positions to close.");
            } else {
                for (Position pos : positions) {
                    try {
                        logger.warn("Closing Alpaca position: {} ({} shares)", pos.symbol(), pos.quantity());
                        directClient.placeOrder(
                            pos.symbol(), 
                            Math.abs(pos.quantity()), 
                            pos.quantity() > 0 ? "sell" : "buy",
                            "market", 
                            "day", 
                            null
                        );
                        alpacaClosed.add(Map.of(
                            "symbol", pos.symbol(),
                            "quantity", pos.quantity(),
                            "platform", "alpaca",
                            "status", "close_ordered"
                        ));
                    } catch (Exception e) {
                        logger.error("❌ Failed to close Alpaca position for {}", pos.symbol(), e);
                        alpacaClosed.add(Map.of(
                            "symbol", pos.symbol(),
                            "quantity", pos.quantity(),
                            "platform", "alpaca",
                            "status", "failed",
                            "error", e.getMessage()
                        ));
                    }
                }
            }
            logger.info("✅ Alpaca close commands sent for {} positions.", alpacaClosed.size());
        } catch (Exception e) {
            logger.error("❌ Failed to fetch Alpaca positions for closure", e);
            result.put("alpacaPositionsError", e.getMessage());
        }
        result.put("alpacaPositions", alpacaClosed);
        
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
}
