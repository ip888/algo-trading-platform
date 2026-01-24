package com.trading.broker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.trading.config.Config;

import java.util.concurrent.CompletableFuture;

/**
 * UNIFIED BROKER ROUTER
 * 
 * Routes orders to the appropriate broker based on:
 * - Asset type (stocks)
 * - Market region (US vs EU)
 * - Symbol format
 * 
 * Current Configuration:
 * - US Stocks: Alpaca
 * - EU Stocks: Interactive Brokers OR Saxo Bank
 * 
 * Symbol Detection:
 * - IBEX/European suffix -> EU (IB/Saxo)
 * - Default -> US (Alpaca)
 */
public class BrokerRouter {
    private static final Logger logger = LoggerFactory.getLogger(BrokerRouter.class);
    
    private final com.trading.api.AlpacaClient alpacaClient;
    private final InteractiveBrokersClient ibClient;
    private final SaxoClient saxoClient;
    
    // Configuration
    private final String region;  // "US" or "EU"
    private final String euBrokerPreference;  // "IB" or "SAXO"
    
    public BrokerRouter(com.trading.api.AlpacaClient alpacaClient) {
        this.alpacaClient = alpacaClient;
        this.ibClient = new InteractiveBrokersClient();
        this.saxoClient = new SaxoClient();
        
        // Load from environment
        this.region = System.getenv().getOrDefault("TRADING_REGION", "US");
        this.euBrokerPreference = System.getenv().getOrDefault("EU_BROKER", "IB");
        
        logger.info("🔀 Broker Router initialized: Region={}, EU Broker={}", region, euBrokerPreference);
        logBrokerStatus();
    }
    
    /**
     * Route a buy order to the appropriate broker
     */
    public CompletableFuture<String> routeBuyOrderAsync(String symbol, double quantity, double price) {
        BrokerType broker = detectBroker(symbol);
        
        logger.info("🔀 Routing BUY {} x{} @ ${} to {}", symbol, quantity, price, broker);
        
        return switch (broker) {
            case ALPACA -> {
                yield CompletableFuture.supplyAsync(() -> {
                    try {
                        if (price > 0) {
                            alpacaClient.placeOrder(symbol, quantity, "buy", "limit", "day", price);
                        } else {
                            alpacaClient.placeOrder(symbol, quantity, "buy", "market", "day", null);
                        }
                        return "{\"status\":\"success\"}";
                    } catch (Exception e) {
                        return "{\"error\":\"" + e.getMessage() + "\"}";
                    }
                });
            }
            case INTERACTIVE_BROKERS -> {
                // Would need contract search first in real implementation
                yield ibClient.placeMarketOrderAsync(symbol, "BUY", quantity);
            }
            case SAXO -> {
                // Would need instrument search first in real implementation
                yield saxoClient.placeMarketOrderAsync(0, "Stock", "Buy", quantity);
            }
        };
    }
    
    /**
     * Route a sell order to the appropriate broker
     */
    public CompletableFuture<String> routeSellOrderAsync(String symbol, double quantity, double price) {
        BrokerType broker = detectBroker(symbol);
        
        logger.info("🔀 Routing SELL {} x{} @ ${} to {}", symbol, quantity, price, broker);
        
        return switch (broker) {
            case ALPACA -> {
                yield CompletableFuture.supplyAsync(() -> {
                    try {
                        if (price > 0) {
                            alpacaClient.placeOrder(symbol, quantity, "sell", "limit", "day", price);
                        } else {
                            alpacaClient.placeOrder(symbol, quantity, "sell", "market", "day", null);
                        }
                        return "{\"status\":\"success\"}";
                    } catch (Exception e) {
                        return "{\"error\":\"" + e.getMessage() + "\"}";
                    }
                });
            }
            case INTERACTIVE_BROKERS -> {
                yield ibClient.placeMarketOrderAsync(symbol, "SELL", quantity);
            }
            case SAXO -> {
                yield saxoClient.placeMarketOrderAsync(0, "Stock", "Sell", quantity);
            }
        };
    }
    
    /**
     * Get account balance from all configured brokers
     */
    public CompletableFuture<String> getConsolidatedBalanceAsync() {
        var results = new java.util.concurrent.ConcurrentHashMap<String, String>();
        
        var futures = java.util.List.of(
            CompletableFuture.supplyAsync(() -> { try { return alpacaClient.getAccount().toString(); } catch (Exception e) { return "{\"error\":\"" + e.getMessage() + "\"}"; } })
                .thenAccept(r -> results.put("alpaca", r)),
            ibClient.isConfigured() ? 
                ibClient.getAccountSummaryAsync().thenAccept(r -> results.put("ib", r)) :
                CompletableFuture.completedFuture(null),
            saxoClient.isConfigured() ? 
                saxoClient.getBalanceAsync().thenAccept(r -> results.put("saxo", r)) :
                CompletableFuture.completedFuture(null)
        );
        
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
            .thenApply(v -> {
                try {
                    return new com.fasterxml.jackson.databind.ObjectMapper()
                        .writeValueAsString(results);
                } catch (Exception e) {
                    return results.toString();
                }
            });
    }
    
    /**
     * Detect which broker should handle this symbol
     */
    public BrokerType detectBroker(String symbol) {
        if (symbol == null || symbol.isEmpty()) {
            return "EU".equals(region) ? getEuBroker() : BrokerType.ALPACA;
        }
        
        // EU detection (when in EU region)
        if ("EU".equals(region)) {
            return getEuBroker();
        }
        
        // Default to Alpaca (US stocks)
        return BrokerType.ALPACA;
    }
    
    private BrokerType getEuBroker() {
        return "SAXO".equalsIgnoreCase(euBrokerPreference) ? 
            BrokerType.SAXO : BrokerType.INTERACTIVE_BROKERS;
    }
    
    private void logBrokerStatus() {
        logger.info("📊 Broker Status:");
        logger.info("   Alpaca (US Stocks): ✅ Active");
        logger.info("   IB (EU Stocks):     {}", ibClient.isConfigured() ? "✅ Configured" : "⚠️ Not Configured");
        logger.info("   Saxo (EU Stocks):   {}", saxoClient.isConfigured() ? "✅ Configured" : "⚠️ Not Configured");
    }
    
    /**
     * Get status of all brokers
     */
    public java.util.Map<String, Boolean> getBrokerStatus() {
        return java.util.Map.of(
            "alpaca", true,
            "ib", ibClient.isConfigured(),
            "saxo", saxoClient.isConfigured()
        );
    }
    
    public enum BrokerType {
        ALPACA,
        INTERACTIVE_BROKERS,
        SAXO
    }
}
