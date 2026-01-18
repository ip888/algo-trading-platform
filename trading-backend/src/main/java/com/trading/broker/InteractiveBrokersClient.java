package com.trading.broker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.concurrent.CompletableFuture;

/**
 * Interactive Brokers Client (Stub)
 * TODO: Implement full IB Gateway integration
 */
public class InteractiveBrokersClient {
    private static final Logger logger = LoggerFactory.getLogger(InteractiveBrokersClient.class);
    
    public InteractiveBrokersClient() {
        logger.warn("⚠️ InteractiveBrokersClient is a stub - not configured");
    }
    
    public boolean isConfigured() {
        return false;
    }
    
    public CompletableFuture<String> getAccountSummaryAsync() {
        return CompletableFuture.completedFuture("{\"error\":\"IB not configured\"}");
    }
    
    public CompletableFuture<String> placeMarketOrderAsync(String symbol, String action, double quantity) {
        logger.warn("IB order not placed - stub implementation");
        return CompletableFuture.completedFuture("{\"error\":\"IB not configured\"}");
    }
}
