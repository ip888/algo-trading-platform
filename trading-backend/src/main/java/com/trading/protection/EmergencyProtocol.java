package com.trading.protection;

import com.trading.api.ResilientAlpacaClient;
import com.trading.api.model.Position;
import com.trading.broker.KrakenClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * Supports both Alpaca (stocks) and Kraken (crypto) platforms.
 * 
 * Modern design:
 * - Uses AtomicBoolean for lock-free triggered state
 * - compareAndSet for atomic check-and-set operation
 * - No synchronized keyword needed
 */
public class EmergencyProtocol {
    private static final Logger logger = LoggerFactory.getLogger(EmergencyProtocol.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();
    
    private final ResilientAlpacaClient alpacaClient;
    private KrakenClient krakenClient;
    
    // Modern: AtomicBoolean for lock-free state management
    private final AtomicBoolean triggered = new AtomicBoolean(false);
    
    // Track last execution results
    private volatile Map<String, Object> lastExecutionResult;
    private volatile long lastTriggerTime;
    private volatile String lastTriggerReason;

    public EmergencyProtocol(ResilientAlpacaClient client) {
        this.alpacaClient = client;
    }
    
    /**
     * Set Kraken client for crypto liquidation support.
     */
    public void setKrakenClient(KrakenClient krakenClient) {
        this.krakenClient = krakenClient;
        logger.info("EmergencyProtocol: Kraken client configured for crypto liquidation");
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
     * @return Details of what was closed
     */
    private Map<String, Object> flattenAll() {
        Map<String, Object> result = new HashMap<>();
        List<Map<String, Object>> alpacaClosed = new ArrayList<>();
        List<Map<String, Object>> krakenClosed = new ArrayList<>();
        
        // ===== STEP 1: Cancel ALL Alpaca Orders =====
        logger.warn("STEP 1: Cancelling all Alpaca open orders...");
        try {
            alpacaClient.cancelAllOrders();
            result.put("alpacaOrdersCancelled", true);
            logger.info("✅ All Alpaca orders cancelled.");
        } catch (Exception e) {
            logger.error("❌ Failed to cancel Alpaca orders", e);
            result.put("alpacaOrdersCancelled", false);
            result.put("alpacaOrdersError", e.getMessage());
        }

        // ===== STEP 2: Close ALL Alpaca Positions =====
        logger.warn("STEP 2: Closing all Alpaca positions...");
        try {
            List<Position> positions = alpacaClient.getPositions();
            if (positions.isEmpty()) {
                logger.info("No open Alpaca positions to close.");
            } else {
                for (Position pos : positions) {
                    try {
                        logger.warn("Closing Alpaca position: {} ({} shares)", pos.symbol(), pos.quantity());
                        alpacaClient.placeOrder(
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
        
        // ===== STEP 3: Cancel ALL Kraken Orders =====
        if (krakenClient != null && krakenClient.isConfigured()) {
            logger.warn("STEP 3: Cancelling all Kraken open orders...");
            try {
                String cancelResult = krakenClient.cancelAllOpenOrdersAsync().join();
                result.put("krakenOrdersCancelled", true);
                logger.info("✅ All Kraken orders cancelled: {}", cancelResult);
            } catch (Exception e) {
                logger.error("❌ Failed to cancel Kraken orders", e);
                result.put("krakenOrdersCancelled", false);
                result.put("krakenOrdersError", e.getMessage());
            }
            
            // ===== STEP 4: Close ALL Kraken Positions =====
            logger.warn("STEP 4: Closing all Kraken positions...");
            try {
                String balanceJson = krakenClient.getBalanceAsync().join();
                JsonNode balanceNode = objectMapper.readTree(balanceJson);
                JsonNode balanceResult = balanceNode.path("result");
                
                if (balanceResult.isObject()) {
                    var fields = balanceResult.fields();
                    while (fields.hasNext()) {
                        var entry = fields.next();
                        String asset = entry.getKey();
                        double balance = entry.getValue().asDouble();
                        
                        // Skip stablecoins and fiat (USD, EUR, etc.)
                        if (asset.equals("ZUSD") || asset.equals("USD") || asset.equals("EUR") || 
                            asset.equals("ZEUR") || asset.equals("USDT") || asset.equals("USDC") ||
                            balance < 0.0001) {
                            continue;
                        }
                        
                        // Convert Kraken asset to trading pair using proper mapping
                        // Kraken balance returns assets like: SOL, XBT, ETH (some with X prefix already)
                        String pair = assetToKrakenPair(asset);
                        
                        try {
                            logger.warn("Closing Kraken position: {} ({})", asset, balance);
                            String orderResult = krakenClient.placeMarketOrderAsync(pair, "sell", balance).join();
                            
                            if (orderResult.contains("ERROR")) {
                                krakenClosed.add(Map.of(
                                    "symbol", asset,
                                    "quantity", balance,
                                    "platform", "kraken",
                                    "status", "failed",
                                    "error", orderResult
                                ));
                            } else {
                                krakenClosed.add(Map.of(
                                    "symbol", asset,
                                    "quantity", balance,
                                    "platform", "kraken",
                                    "status", "close_ordered"
                                ));
                            }
                        } catch (Exception e) {
                            logger.error("❌ Failed to close Kraken position for {}", asset, e);
                            krakenClosed.add(Map.of(
                                "symbol", asset,
                                "quantity", balance,
                                "platform", "kraken",
                                "status", "failed",
                                "error", e.getMessage()
                            ));
                        }
                    }
                }
                logger.info("✅ Kraken close commands sent for {} positions.", krakenClosed.size());
            } catch (Exception e) {
                logger.error("❌ Failed to process Kraken positions for closure", e);
                result.put("krakenPositionsError", e.getMessage());
            }
        } else {
            logger.info("Kraken client not configured, skipping crypto liquidation.");
            result.put("krakenSkipped", true);
        }
        result.put("krakenPositions", krakenClosed);
        
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
    
    /**
     * Convert Kraken asset name to proper trading pair.
     * Kraken balance returns asset names like: SOL, XBT, XXBT, ETH, XETH, DOT, ADA, etc.
     * We need to convert to proper pair format: SOLUSD, XXBTZUSD, XETHZUSD, etc.
     */
    private String assetToKrakenPair(String asset) {
        // Remove leading X if present for normalization
        String normalized = asset.startsWith("X") && asset.length() > 3 ? asset.substring(1) : asset;
        
        return switch (normalized.toUpperCase()) {
            case "XBT", "BTC" -> "XXBTZUSD";   // Bitcoin uses X prefix
            case "ETH" -> "XETHZUSD";          // Ethereum uses X prefix
            case "XRP" -> "XXRPZUSD";          // XRP uses X prefix
            case "SOL" -> "SOLUSD";            // SOL - no prefix
            case "DOGE", "XDG" -> "XDGUSD";    // Doge is XDG on Kraken
            case "ADA" -> "ADAUSD";            // ADA - no prefix
            case "DOT" -> "DOTUSD";            // DOT - no prefix
            case "AVAX" -> "AVAXUSD";          // AVAX - no prefix
            case "LINK" -> "LINKUSD";          // LINK - no prefix
            case "MATIC", "POL" -> "MATICUSD"; // MATIC/POL
            case "ATOM" -> "ATOMUSD";          // ATOM - no prefix
            case "UNI" -> "UNIUSD";            // UNI - no prefix
            case "LTC" -> "XLTCZUSD";          // Litecoin uses X prefix
            default -> {
                // Default: try adding USD suffix
                logger.warn("Unknown Kraken asset {}, trying {}USD", asset, asset);
                yield asset + "USD";
            }
        };
    }
}
