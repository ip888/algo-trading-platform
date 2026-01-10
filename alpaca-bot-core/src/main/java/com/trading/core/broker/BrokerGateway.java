package com.trading.core.broker;

import java.util.concurrent.CompletableFuture;

/**
 * Interface for broker operations.
 * Allows dynamic switching between brokers (Alpaca, IBKR, etc.)
 */
public interface BrokerGateway {
    
    /**
     * Get account information (equity, buying power, cash).
     */
    CompletableFuture<String> getAccountAsync();
    
    /**
     * Get all open positions.
     */
    CompletableFuture<String> getPositionsAsync();
    
    /**
     * Place an order.
     * @param symbol Stock symbol
     * @param qty Quantity (can be fractional)
     * @param side "buy" or "sell"
     * @param type "market", "limit", etc.
     * @param timeInForce "day", "gtc", etc.
     */
    CompletableFuture<String> placeOrderAsync(String symbol, double qty, String side, String type, String timeInForce);
    
    /**
     * Cancel an order by ID.
     */
    CompletableFuture<Void> cancelOrderAsync(String orderId);
    
    /**
     * Get historical market data.
     */
    CompletableFuture<String> getBarsAsync(String symbol, String timeframe, int limit);
    
    /**
     * Check if broker connection is healthy.
     */
    boolean isHealthy();
    
    /**
     * Get broker identifier.
     */
    String getBrokerId();
}
