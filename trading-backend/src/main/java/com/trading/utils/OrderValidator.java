package com.trading.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Order validation utility to prevent duplicate orders.
 * 
 * Features:
 * - Track recently placed orders
 * - Prevent duplicate order placement within time window
 * - Log order conflicts
 * 
 * Modern design:
 * - Uses ScheduledExecutorService instead of raw Thread creation
 * - Virtual thread executor for efficient scheduling
 * - No Thread.sleep() - uses scheduled task removal
 * 
 * Prevents: Double-ordering, order conflicts
 */
public class OrderValidator {
    private static final Logger logger = LoggerFactory.getLogger(OrderValidator.class);
    
    // Track recently placed orders (symbol -> timestamp)
    private static final Set<String> recentOrders = ConcurrentHashMap.newKeySet();
    private static final long ORDER_COOLDOWN_MS = 5000; // 5 seconds
    
    // Modern: Use virtual thread scheduled executor instead of raw threads
    private static final ScheduledExecutorService scheduler = 
        Executors.newScheduledThreadPool(1, Thread.ofVirtual().factory());
    
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
     * Uses scheduled task for cleanup instead of Thread.sleep.
     */
    public static void markOrderPlaced(String symbol, String side) {
        String orderKey = symbol + "_" + side;
        recentOrders.add(orderKey);
        
        // Schedule removal after cooldown period (no blocking)
        scheduler.schedule(
            () -> recentOrders.remove(orderKey),
            ORDER_COOLDOWN_MS,
            TimeUnit.MILLISECONDS
        );
    }
    
    /**
     * Clear all tracked orders (for testing).
     */
    public static void reset() {
        recentOrders.clear();
        logger.debug("OrderValidator reset");
    }
}
