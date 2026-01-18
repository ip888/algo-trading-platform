package com.trading.broker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.concurrent.CompletableFuture;

/**
 * Saxo Bank Client (Stub)
 * TODO: Implement full Saxo OpenAPI integration
 */
public class SaxoClient {
    private static final Logger logger = LoggerFactory.getLogger(SaxoClient.class);
    
    public SaxoClient() {
        logger.warn("⚠️ SaxoClient is a stub - not configured");
    }
    
    public boolean isConfigured() {
        return false;
    }
    
    public CompletableFuture<String> getBalanceAsync() {
        return CompletableFuture.completedFuture("{\"error\":\"Saxo not configured\"}");
    }
    
    public CompletableFuture<String> placeMarketOrderAsync(int uic, String assetType, String buySell, double amount) {
        logger.warn("Saxo order not placed - stub implementation");
        return CompletableFuture.completedFuture("{\"error\":\"Saxo not configured\"}");
    }
}
