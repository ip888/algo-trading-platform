package com.trading.api.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trading.broker.KrakenClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Centralized Kraken data cache to prevent API rate limit issues.
 * 
 * PROBLEM: Dashboard makes 6+ API calls per refresh (every 30s), each endpoint
 * creates new KrakenClient and makes multiple Kraken API calls = 20+ API calls/refresh.
 * Kraken allows ~15 calls/minute, so we exceed rate limit immediately.
 * 
 * SOLUTION: Single shared cache that refreshes ALL data in one batch,
 * then serves cached data to all dashboard endpoints.
 */
public class KrakenDataCache {
    private static final Logger logger = LoggerFactory.getLogger(KrakenDataCache.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();
    
    // Cache TTL: 60 seconds (Kraken rate limit is ~15/min, we want room for trading loop too)
    private static final long CACHE_TTL_MS = 60_000;
    
    // Rate limit backoff: when Kraken returns rate limit error, wait this long before retrying
    private static final long RATE_LIMIT_BACKOFF_MS = 120_000;  // 2 minutes
    private volatile long rateLimitBackoffUntil = 0;
    
    // Singleton instance
    private static final KrakenDataCache INSTANCE = new KrakenDataCache();
    
    // Cache timestamps
    private final AtomicLong lastRefresh = new AtomicLong(0);
    private volatile boolean refreshInProgress = false;
    
    // Cached data
    private volatile JsonNode cachedBalance = null;
    private volatile JsonNode cachedTradeBalance = null;
    private volatile JsonNode cachedTrades = null;
    private volatile Map<String, JsonNode> cachedTickers = new ConcurrentHashMap<>();
    
    // Computed cached responses (ready to serve)
    private volatile Map<String, Object> cachedHoldingsResponse = null;
    private volatile Map<String, Object> cachedCapitalResponse = null;
    private volatile Map<String, Object> cachedBalanceResponse = null;
    private volatile Map<String, Object> cachedTradesResponse = null;
    
    private KrakenDataCache() {}
    
    public static KrakenDataCache getInstance() {
        return INSTANCE;
    }
    
    /**
     * Check if cache is fresh enough to serve, or if we're in rate limit backoff
     */
    public boolean isCacheValid() {
        // If in rate limit backoff, consider cache "valid" (don't retry)
        if (System.currentTimeMillis() < rateLimitBackoffUntil) {
            logger.debug("In rate limit backoff, serving stale cache");
            return true;
        }
        return System.currentTimeMillis() - lastRefresh.get() < CACHE_TTL_MS && cachedBalance != null;
    }
    
    /**
     * Get cached balance response (for /api/kraken/balance)
     */
    public Map<String, Object> getBalanceResponse() {
        refreshIfNeeded();
        if (cachedBalanceResponse == null) {
            // Map.of() doesn't allow null values, use HashMap
            var emptyResponse = new HashMap<String, Object>();
            emptyResponse.put("assets", null);
            emptyResponse.put("tradeBalance", null);
            emptyResponse.put("error", "No cached data available yet");
            return emptyResponse;
        }
        
        // Add cache metadata
        var response = new HashMap<>(cachedBalanceResponse);
        long cacheAge = System.currentTimeMillis() - lastRefresh.get();
        response.put("cacheAgeSeconds", cacheAge / 1000);
        
        // Indicate if in rate limit backoff
        if (System.currentTimeMillis() < rateLimitBackoffUntil) {
            long backoffRemaining = (rateLimitBackoffUntil - System.currentTimeMillis()) / 1000;
            response.put("rateLimitBackoffSeconds", backoffRemaining);
        }
        
        return response;
    }
    
    /**
     * Get cached holdings response (for /api/kraken/holdings)
     */
    public List<Map<String, Object>> getHoldingsResponse() {
        refreshIfNeeded();
        if (cachedHoldingsResponse == null) return Collections.emptyList();
        @SuppressWarnings("unchecked")
        var holdings = (List<Map<String, Object>>) cachedHoldingsResponse.get("holdings");
        return holdings != null ? holdings : Collections.emptyList();
    }
    
    /**
     * Get cached capital response (for /api/kraken/capital)
     */
    public Map<String, Object> getCapitalResponse() {
        refreshIfNeeded();
        return cachedCapitalResponse != null ? cachedCapitalResponse : Map.of(
            "totalEquity", 0.0,
            "freeMargin", 0.0,
            "deployedCapital", 0.0,
            "deploymentPercent", 0.0,
            "positions", Collections.emptyList(),
            "positionCount", 0
        );
    }
    
    /**
     * Get cached trades response (for /api/kraken/trades)
     */
    public Map<String, Object> getTradesResponse() {
        refreshIfNeeded();
        return cachedTradesResponse != null ? cachedTradesResponse : Map.of("trades", Collections.emptyList(), "count", 0);
    }
    
    /**
     * Refresh cache if needed (rate-limited to once per CACHE_TTL_MS)
     */
    private synchronized void refreshIfNeeded() {
        if (isCacheValid() || refreshInProgress) {
            return;
        }
        
        refreshInProgress = true;
        try {
            logger.info("🔄 Refreshing Kraken data cache (single batch fetch)...");
            
            var krakenClient = new KrakenClient();
            if (!krakenClient.isConfigured()) {
                logger.warn("Kraken not configured, skipping cache refresh");
                return;
            }
            
            // Fetch balance FIRST and check for rate limit errors before continuing
            String balanceStr = krakenClient.getBalanceAsync().join();
            cachedBalance = objectMapper.readTree(balanceStr);
            
            // Check for rate limit error - if so, back off immediately
            if (cachedBalance.has("error") && cachedBalance.get("error").size() > 0) {
                String error = cachedBalance.get("error").get(0).asText();
                if (error.contains("Rate limit")) {
                    logger.warn("🛑 Kraken rate limit hit! Backing off for 2 minutes...");
                    rateLimitBackoffUntil = System.currentTimeMillis() + RATE_LIMIT_BACKOFF_MS;
                    computeBalanceResponse();  // Still compute what we have
                    return;  // Don't fetch more data
                }
            }
            
            // Wait 2 seconds between calls to avoid rate limiting
            Thread.sleep(2000);
            
            // Fetch trade balance
            String tradeBalanceStr = krakenClient.getTradeBalanceAsync().join();
            cachedTradeBalance = objectMapper.readTree(tradeBalanceStr);
            
            // Check again for rate limit
            if (cachedTradeBalance.has("error") && cachedTradeBalance.get("error").size() > 0) {
                String error = cachedTradeBalance.get("error").get(0).asText();
                if (error.contains("Rate limit")) {
                    logger.warn("🛑 Kraken rate limit hit on TradeBalance! Backing off for 2 minutes...");
                    rateLimitBackoffUntil = System.currentTimeMillis() + RATE_LIMIT_BACKOFF_MS;
                    computeBalanceResponse();
                    return;
                }
            }
            
            Thread.sleep(2000);
            
            // Fetch trades history
            String tradesStr = krakenClient.getTradesHistoryAsync().join();
            cachedTrades = objectMapper.readTree(tradesStr);
            
            // Skip ticker fetches entirely to conserve rate limit
            // We'll show holdings without live prices
            
            // Compute all responses from cached data
            computeBalanceResponse();
            computeHoldingsResponse();
            computeCapitalResponse();
            computeTradesResponse();
            
            lastRefresh.set(System.currentTimeMillis());
            logger.info("✅ Kraken data cache refreshed successfully");
            
        } catch (Exception e) {
            logger.error("Failed to refresh Kraken cache: {}", e.getMessage());
        } finally {
            refreshInProgress = false;
        }
    }
    
    /**
     * Fetch tickers for assets we hold (limited to most important ones)
     */
    private void fetchTickersForHeldAssets(KrakenClient krakenClient) {
        try {
            var assets = cachedBalance.get("result");
            if (assets == null) return;
            
            var symbolMap = Map.of(
                "XXRP", "XXRPZUSD",
                "XXDG", "XDGUSD",
                "SOL", "SOLUSD",
                "XXBT", "XXBTZUSD",
                "XETH", "XETHZUSD",
                "XBT", "XXBTZUSD"
            );
            
            // Find which assets we hold
            var pairsToFetch = new HashSet<String>();
            var fields = assets.fieldNames();
            while (fields.hasNext()) {
                var symbol = fields.next();
                if (symbol.contains("USD") || symbol.equals("ZUSD")) continue;
                double amount = assets.get(symbol).asDouble();
                if (amount >= 0.0001 && symbolMap.containsKey(symbol)) {
                    pairsToFetch.add(symbolMap.get(symbol));
                }
            }
            
            // Limit to max 3 ticker calls to stay within rate limits
            int count = 0;
            for (String pair : pairsToFetch) {
                if (count++ >= 3) break;
                try {
                    String tickerStr = krakenClient.getTickerAsync(pair).join();
                    cachedTickers.put(pair, objectMapper.readTree(tickerStr));
                } catch (Exception e) {
                    logger.warn("Failed to fetch ticker for {}: {}", pair, e.getMessage());
                }
            }
            
        } catch (Exception e) {
            logger.warn("Error fetching tickers: {}", e.getMessage());
        }
    }
    
    private void computeBalanceResponse() {
        var response = new HashMap<String, Object>();
        
        if (cachedBalance == null) {
            response.put("assets", null);
            response.put("error", "Balance data not available");
            cachedBalanceResponse = response;
            return;
        }
        
        if (cachedBalance.has("error") && cachedBalance.get("error").size() > 0) {
            String error = cachedBalance.get("error").get(0).asText();
            logger.warn("Kraken Balance API error: {}", error);
            response.put("assets", null);
            response.put("error", error);
        } else {
            response.put("assets", cachedBalance.get("result"));
        }
        
        // cachedTradeBalance may be null if we hit rate limit early
        if (cachedTradeBalance == null) {
            response.put("tradeBalance", null);
            response.put("tradeBalanceError", "TradeBalance data not available");
        } else if (cachedTradeBalance.has("error") && cachedTradeBalance.get("error").size() > 0) {
            String error = cachedTradeBalance.get("error").get(0).asText();
            logger.warn("Kraken TradeBalance API error: {}", error);
            response.put("tradeBalance", null);
            response.put("tradeBalanceError", error);
        } else {
            response.put("tradeBalance", cachedTradeBalance.get("result"));
        }
        
        cachedBalanceResponse = response;
    }
    
    private void computeHoldingsResponse() {
        try {
            var assets = cachedBalance.get("result");
            if (assets == null || assets.isEmpty()) {
                cachedHoldingsResponse = Map.of("holdings", Collections.emptyList());
                return;
            }
            
            var displayNames = Map.of(
                "XXRP", "XRP", "XXDG", "DOGE", "SOL", "SOL",
                "XXBT", "BTC", "XETH", "ETH", "XBT", "BTC"
            );
            var symbolMap = Map.of(
                "XXRP", "XXRPZUSD", "XXDG", "XDGUSD", "SOL", "SOLUSD",
                "XXBT", "XXBTZUSD", "XETH", "XETHZUSD", "XBT", "XXBTZUSD"
            );
            
            var holdings = new ArrayList<Map<String, Object>>();
            var fields = assets.fieldNames();
            
            while (fields.hasNext()) {
                var symbol = fields.next();
                if (symbol.contains("USD") || symbol.equals("ZUSD")) continue;
                
                double amount = assets.get(symbol).asDouble();
                if (amount < 0.0001) continue;
                
                var holding = new HashMap<String, Object>();
                holding.put("symbol", symbol);
                holding.put("displayName", displayNames.getOrDefault(symbol, symbol));
                holding.put("amount", amount);
                
                // Use cached ticker
                String tradingPair = symbolMap.get(symbol);
                if (tradingPair != null && cachedTickers.containsKey(tradingPair)) {
                    var tickerJson = cachedTickers.get(tradingPair);
                    var tickerResult = tickerJson.get("result");
                    if (tickerResult != null && tickerResult.has(tradingPair)) {
                        var ticker = tickerResult.get(tradingPair);
                        double currentPrice = ticker.get("c").get(0).asDouble();
                        double openPrice = ticker.get("o").asDouble();
                        double high24h = ticker.get("h").get(1).asDouble();
                        double low24h = ticker.get("l").get(1).asDouble();
                        
                        double change24h = currentPrice - openPrice;
                        double changePercent = openPrice > 0 ? (change24h / openPrice) * 100 : 0;
                        
                        holding.put("price", currentPrice);
                        holding.put("value", currentPrice * amount);
                        holding.put("change24h", change24h);
                        holding.put("changePercent", changePercent);
                        holding.put("high24h", high24h);
                        holding.put("low24h", low24h);
                        holding.put("direction", change24h >= 0 ? "up" : "down");
                    }
                }
                
                holdings.add(holding);
            }
            
            cachedHoldingsResponse = Map.of("holdings", holdings);
            
        } catch (Exception e) {
            logger.error("Error computing holdings response: {}", e.getMessage());
            cachedHoldingsResponse = Map.of("holdings", Collections.emptyList());
        }
    }
    
    private void computeCapitalResponse() {
        try {
            var assets = cachedBalance.get("result");
            var tb = cachedTradeBalance.get("result");
            
            double totalEquity = tb != null && tb.has("eb") ? tb.get("eb").asDouble() : 0;
            double freeMargin = tb != null && tb.has("mf") ? tb.get("mf").asDouble() : 0;
            double deployedCapital = totalEquity - freeMargin;
            double deploymentPercent = totalEquity > 0 ? (deployedCapital / totalEquity) * 100 : 0;
            
            var positions = new ArrayList<Map<String, Object>>();
            
            if (assets != null) {
                var displayNames = Map.of(
                    "XXRP", "XRP", "XXDG", "DOGE", "SOL", "SOL",
                    "XXBT", "BTC", "XETH", "ETH", "XBT", "BTC"
                );
                var symbolMap = Map.of(
                    "XXRP", "XXRPZUSD", "XXDG", "XDGUSD", "SOL", "SOLUSD",
                    "XXBT", "XXBTZUSD", "XETH", "XETHZUSD", "XBT", "XXBTZUSD"
                );
                
                var fields = assets.fieldNames();
                while (fields.hasNext()) {
                    var symbol = fields.next();
                    if (symbol.contains("USD") || symbol.equals("ZUSD")) continue;
                    
                    double amount = assets.get(symbol).asDouble();
                    if (amount < 0.0001) continue;
                    
                    var pos = new HashMap<String, Object>();
                    pos.put("asset", displayNames.getOrDefault(symbol, symbol));
                    pos.put("amount", amount);
                    
                    // Use cached ticker
                    String tradingPair = symbolMap.get(symbol);
                    if (tradingPair != null && cachedTickers.containsKey(tradingPair)) {
                        var tickerJson = cachedTickers.get(tradingPair);
                        var tickerResult = tickerJson.get("result");
                        if (tickerResult != null && tickerResult.has(tradingPair)) {
                            double price = tickerResult.get(tradingPair).get("c").get(0).asDouble();
                            double value = price * amount;
                            pos.put("price", price);
                            pos.put("value", value);
                        }
                    }
                    
                    positions.add(pos);
                }
            }
            
            cachedCapitalResponse = Map.of(
                "totalEquity", totalEquity,
                "freeMargin", freeMargin,
                "deployedCapital", deployedCapital,
                "deploymentPercent", deploymentPercent,
                "positions", positions,
                "positionCount", positions.size()
            );
            
        } catch (Exception e) {
            logger.error("Error computing capital response: {}", e.getMessage());
            cachedCapitalResponse = Map.of(
                "totalEquity", 0.0, "freeMargin", 0.0, "deployedCapital", 0.0,
                "deploymentPercent", 0.0, "positions", Collections.emptyList(), "positionCount", 0
            );
        }
    }
    
    private void computeTradesResponse() {
        try {
            var tradesResult = cachedTrades.get("result");
            if (tradesResult == null || !tradesResult.has("trades")) {
                cachedTradesResponse = Map.of("trades", Collections.emptyList(), "count", 0);
                return;
            }
            
            var trades = new ArrayList<Map<String, Object>>();
            var tradesNode = tradesResult.get("trades");
            
            var tradeList = new ArrayList<Map.Entry<String, JsonNode>>();
            tradesNode.fields().forEachRemaining(tradeList::add);
            
            tradeList.sort((a, b) -> Double.compare(
                b.getValue().get("time").asDouble(),
                a.getValue().get("time").asDouble()
            ));
            
            int count = 0;
            for (var entry : tradeList) {
                if (count++ >= 50) break;
                
                var trade = entry.getValue();
                var tradeMap = new HashMap<String, Object>();
                
                tradeMap.put("id", entry.getKey());
                tradeMap.put("pair", trade.get("pair").asText());
                tradeMap.put("type", trade.get("type").asText());
                tradeMap.put("price", trade.get("price").asDouble());
                tradeMap.put("volume", trade.get("vol").asDouble());
                tradeMap.put("cost", trade.get("cost").asDouble());
                tradeMap.put("fee", trade.get("fee").asDouble());
                tradeMap.put("time", trade.get("time").asDouble());
                tradeMap.put("ordertype", trade.get("ordertype").asText());
                
                long epochSeconds = (long) trade.get("time").asDouble();
                var instant = java.time.Instant.ofEpochSecond(epochSeconds);
                var formatted = java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm")
                    .withZone(java.time.ZoneId.systemDefault())
                    .format(instant);
                tradeMap.put("formattedTime", formatted);
                
                String pair = trade.get("pair").asText();
                String symbol = switch (pair) {
                    case "XXBTZUSD" -> "BTC/USD";
                    case "XETHZUSD" -> "ETH/USD";
                    case "SOLUSD" -> "SOL/USD";
                    case "XDGUSD" -> "DOGE/USD";
                    case "XXRPZUSD" -> "XRP/USD";
                    default -> pair;
                };
                tradeMap.put("symbol", symbol);
                
                trades.add(tradeMap);
            }
            
            cachedTradesResponse = Map.of("trades", trades, "count", trades.size());
            
        } catch (Exception e) {
            logger.error("Error computing trades response: {}", e.getMessage());
            cachedTradesResponse = Map.of("trades", Collections.emptyList(), "count", 0);
        }
    }
    
    /**
     * Force a cache refresh (called after trading actions)
     */
    public void invalidate() {
        lastRefresh.set(0);
    }
    
    /**
     * Get cache age in seconds
     */
    public long getCacheAgeSeconds() {
        return (System.currentTimeMillis() - lastRefresh.get()) / 1000;
    }
}
