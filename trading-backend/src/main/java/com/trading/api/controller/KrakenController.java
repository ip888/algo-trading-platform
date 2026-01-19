package com.trading.api.controller;

import com.trading.broker.KrakenClient;
import com.trading.strategy.GridTradingService;
import io.javalin.Javalin;
import io.javalin.http.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Kraken API Controller
 * Handles crypto trading endpoints for Kraken exchange.
 * 
 * RATE LIMIT STRATEGY: All read-only endpoints use KrakenDataCache which batches
 * Kraken API calls into a single refresh every 60 seconds. This prevents the
 * dashboard from exceeding Kraken's ~15 calls/minute limit.
 */
public final class KrakenController {
    private static final Logger logger = LoggerFactory.getLogger(KrakenController.class);
    private GridTradingService gridTradingService;
    private final KrakenDataCache dataCache = KrakenDataCache.getInstance();
    
    public void registerRoutes(Javalin app) {
        app.get("/api/kraken/balance", this::getKrakenBalance);
        app.get("/api/kraken/status", this::getKrakenStatus);
        app.get("/api/kraken/grid", this::getGridStatus);
        app.get("/api/kraken/holdings", this::getKrakenHoldings);
        app.get("/api/kraken/trades", this::getTradesHistory);
        app.get("/api/kraken/capital", this::getCapitalDeployment);
        app.post("/api/kraken/liquidate-losers", this::liquidateLosers);
    }
    
    /**
     * Set the GridTradingService reference for status API
     */
    public void setGridTradingService(GridTradingService service) {
        this.gridTradingService = service;
    }
    
    /**
     * Get Kraken balance (crypto trading).
     * Uses centralized cache to prevent rate limit issues.
     */
    private void getKrakenBalance(Context ctx) {
        try {
            var krakenClient = new KrakenClient();
            
            if (!krakenClient.isConfigured()) {
                ctx.status(503).json(Map.of(
                    "error", "Kraken not configured",
                    "message", "Set KRAKEN_API_KEY and KRAKEN_API_SECRET environment variables"
                ));
                return;
            }
            
            // Use cached response
            var response = dataCache.getBalanceResponse();
            response.put("cacheAgeSeconds", dataCache.getCacheAgeSeconds());
            ctx.json(response);
            
        } catch (Exception e) {
            logger.error("Failed to fetch Kraken balance", e);
            ctx.status(500).json(Map.of(
                "error", "Failed to fetch Kraken balance",
                "message", e.getMessage()
            ));
        }
    }
    
    /**
     * Get Kraken connection status.
     */
    private void getKrakenStatus(Context ctx) {
        var krakenClient = new KrakenClient();
        ctx.json(Map.of(
            "configured", krakenClient.isConfigured(),
            "status", krakenClient.isConfigured() ? "ready" : "not_configured"
        ));
    }
    
    /**
     * Get Grid Trading status including dynamic sizing, performance, and volatility.
     */
    private void getGridStatus(Context ctx) {
        try {
            if (gridTradingService == null) {
                // Create a temporary instance if not injected
                var krakenClient = new KrakenClient();
                gridTradingService = new GridTradingService(null, krakenClient);
            }
            
            Map<String, Object> status = gridTradingService.getGridStatus();
            ctx.json(status);
            
        } catch (Exception e) {
            logger.error("Failed to fetch grid status", e);
            ctx.status(500).json(Map.of(
                "error", "Failed to fetch grid status",
                "message", e.getMessage()
            ));
        }
    }
    
    /**
     * Get Kraken holdings with live prices and 24h change.
     * Uses centralized cache to prevent rate limit issues.
     */
    private void getKrakenHoldings(Context ctx) {
        try {
            var krakenClient = new KrakenClient();
            
            if (!krakenClient.isConfigured()) {
                ctx.status(503).json(Map.of("error", "Kraken not configured"));
                return;
            }
            
            // Use cached response
            var holdings = dataCache.getHoldingsResponse();
            ctx.json(holdings);
            
        } catch (Exception e) {
            logger.error("Failed to fetch Kraken holdings", e);
            ctx.status(500).json(Map.of("error", e.getMessage()));
        }
    }
    
    /**
     * Liquidate only losing Kraken positions.
     * Sells crypto holdings that have negative P&L (based on tracked entry prices).
     * Preserves profitable positions.
     */
    private void liquidateLosers(Context ctx) {
        logger.warn("🦑 KRAKEN LIQUIDATE LOSERS - Requested from Dashboard");
        
        try {
            var krakenClient = new KrakenClient();
            
            if (!krakenClient.isConfigured()) {
                ctx.status(503).json(Map.of(
                    "error", "Kraken not configured",
                    "message", "Set KRAKEN_API_KEY and KRAKEN_API_SECRET"
                ));
                return;
            }
            
            var objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();
            
            // Get tracked positions from the trading loop
            var krakenLoop = com.trading.bot.TradingBot.getKrakenTradingLoop();
            var trackedPositions = krakenLoop != null ? krakenLoop.getPositions() : new java.util.concurrent.ConcurrentHashMap<String, com.trading.risk.TradePosition>();
            
            // Get current balance
            var balanceJson = objectMapper.readTree(krakenClient.getBalanceAsync().join());
            var assets = balanceJson.get("result");
            
            if (assets == null || assets.isEmpty()) {
                ctx.json(Map.of(
                    "status", "no_positions",
                    "message", "No crypto holdings to liquidate",
                    "sold", java.util.Collections.emptyList()
                ));
                return;
            }
            
            // Map Kraken symbols to trading pairs
            var symbolMap = Map.of(
                "XXRP", "XXRPZUSD",
                "XXDG", "XDGUSD",
                "SOL", "SOLUSD",
                "XXBT", "XXBTZUSD",
                "XETH", "XETHZUSD",
                "XBT", "XXBTZUSD"
            );
            
            var sold = new java.util.ArrayList<Map<String, Object>>();
            var preserved = new java.util.ArrayList<Map<String, Object>>();
            
            var fields = assets.fieldNames();
            while (fields.hasNext()) {
                var symbol = fields.next();
                
                // Skip USD balances
                if (symbol.contains("USD") || symbol.equals("ZUSD")) continue;
                
                double amount = assets.get(symbol).asDouble();
                if (amount < 0.0001) continue; // Skip dust
                
                String tradingPair = symbolMap.get(symbol);
                if (tradingPair == null) {
                    logger.warn("Unknown symbol {}, skipping", symbol);
                    continue;
                }
                
                try {
                    // Get current price
                    var tickerJson = objectMapper.readTree(krakenClient.getTickerAsync(tradingPair).join());
                    var tickerResult = tickerJson.get("result");
                    
                    if (tickerResult == null || !tickerResult.has(tradingPair)) {
                        logger.warn("No ticker data for {}", tradingPair);
                        continue;
                    }
                    
                    var ticker = tickerResult.get(tradingPair);
                    double currentPrice = ticker.get("c").get(0).asDouble();
                    
                    // Determine P&L
                    double entryPrice = currentPrice; // Default to current if not tracked
                    double pnlPercent = 0.0;
                    
                    // Check tracked positions for entry price
                    String normalizedSymbol = symbol.replaceAll("^X+", ""); // Remove leading X's
                    for (var entry : trackedPositions.entrySet()) {
                        if (entry.getKey().contains(normalizedSymbol) || entry.getKey().contains(symbol)) {
                            entryPrice = entry.getValue().entryPrice();
                            pnlPercent = ((currentPrice - entryPrice) / entryPrice) * 100;
                            break;
                        }
                    }
                    
                    // If not found in tracked positions, use 24h open as proxy
                    if (pnlPercent == 0.0) {
                        double openPrice = ticker.get("o").asDouble();
                        pnlPercent = ((currentPrice - openPrice) / openPrice) * 100;
                        entryPrice = openPrice;
                    }
                    
                    double value = currentPrice * amount;
                    
                    if (pnlPercent < 0) {
                        // SELL - Position is losing
                        logger.warn("🔴 SELLING {} @ ${} (P&L: {:.2f}%)", symbol, currentPrice, pnlPercent);
                        
                        String orderResult = krakenClient.placeMarketOrderAsync(tradingPair, "sell", amount).join();
                        
                        sold.add(Map.of(
                            "symbol", symbol,
                            "amount", amount,
                            "price", currentPrice,
                            "value", value,
                            "pnlPercent", pnlPercent,
                            "entryPrice", entryPrice,
                            "orderResult", orderResult.contains("ERROR") ? "FAILED" : "SOLD"
                        ));
                        
                    } else {
                        // PRESERVE - Position is profitable or breakeven
                        logger.info("🟢 PRESERVING {} @ ${} (P&L: +{:.2f}%)", symbol, currentPrice, pnlPercent);
                        
                        preserved.add(Map.of(
                            "symbol", symbol,
                            "amount", amount,
                            "price", currentPrice,
                            "value", value,
                            "pnlPercent", pnlPercent,
                            "entryPrice", entryPrice
                        ));
                    }
                    
                } catch (Exception e) {
                    logger.error("Failed to process {}: {}", symbol, e.getMessage());
                }
            }
            
            logger.warn("🦑 Liquidate Losers Complete: {} sold, {} preserved", sold.size(), preserved.size());
            
            // Invalidate cache after trading action
            dataCache.invalidate();
            
            ctx.json(Map.of(
                "status", "completed",
                "sold", sold,
                "preserved", preserved,
                "summary", String.format("Sold %d losing positions, preserved %d profitable positions", sold.size(), preserved.size())
            ));
            
        } catch (Exception e) {
            logger.error("Failed to liquidate losers", e);
            ctx.status(500).json(Map.of(
                "error", "Failed to liquidate losers",
                "message", e.getMessage()
            ));
        }
    }
    
    /**
     * Get recent trades history from Kraken.
     * Uses centralized cache to prevent rate limit issues.
     */
    private void getTradesHistory(Context ctx) {
        try {
            var krakenClient = new KrakenClient();
            
            if (!krakenClient.isConfigured()) {
                ctx.status(503).json(Map.of("error", "Kraken not configured"));
                return;
            }
            
            // Use cached response
            ctx.json(dataCache.getTradesResponse());
            
        } catch (Exception e) {
            logger.error("Failed to fetch trades history", e);
            ctx.status(500).json(Map.of("error", e.getMessage()));
        }
    }
    
    /**
     * Get Kraken capital deployment summary.
     * Uses centralized cache to prevent rate limit issues.
     */
    private void getCapitalDeployment(Context ctx) {
        try {
            var krakenClient = new KrakenClient();
            
            if (!krakenClient.isConfigured()) {
                ctx.status(503).json(Map.of("error", "Kraken not configured"));
                return;
            }
            
            // Use cached response
            ctx.json(dataCache.getCapitalResponse());
            
        } catch (Exception e) {
            logger.error("Failed to fetch capital deployment", e);
            ctx.status(500).json(Map.of("error", e.getMessage()));
        }
    }
}
