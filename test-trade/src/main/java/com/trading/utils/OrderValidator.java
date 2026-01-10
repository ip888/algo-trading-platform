package com.trading.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Order validation utility to prevent duplicate orders.
 * 
 * Features:
 * - Track recently placed orders
 * - Prevent duplicate order placement within time window
 * - Log order conflicts
 * 
 * Prevents: Double-ordering, order conflicts
 */
public class OrderValidator {
    private static final Logger logger = LoggerFactory.getLogger(OrderValidator.class);
    
    // Track recently placed orders (symbol -> timestamp)
    private static final Set<String> recentOrders = ConcurrentHashMap.newKeySet();
    private static final long ORDER_COOLDOWN_MS = 5000; // 5 seconds
    
    /**
     * Check if it's safe to place an order (no recent duplicate).
     */
    public static boolean canPlaceOrder(String symbol, String side) {
        String orderKey = symbol + "_" + side;
        
        if (recentOrders.contains(orderKey)) {
            logger.info("🚫 Skipping {} order for {} - order placed recently", side, symbol);
            return false;
        }
        
        return true;
    }
    
    /**
     * Mark an order as placed.
     */
    public static void markOrderPlaced(String symbol, String side) {
        String orderKey = symbol + "_" + side;
        recentOrders.add(orderKey);
        
        // Remove from tracking after cooldown period
        new Thread(() -> {
            try {
                Thread.sleep(ORDER_COOLDOWN_MS);
                recentOrders.remove(orderKey);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }).start();
    }
    
    /**
     * Clear all tracked orders (for testing).
     */
    public static void reset() {
        recentOrders.clear();
        logger.debug("OrderValidator reset");
    }
}
