package com.trading.crypto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trading.broker.KrakenClient;
import com.trading.risk.TradePosition;
import com.trading.portfolio.PortfolioManager;
import com.trading.strategy.GridTradingService;
import com.trading.websocket.TradingWebSocketHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Independent Kraken Trading Loop 🦑
 * 
 * Runs 24/7 completely separate from Alpaca.
 * - Has its own cycle timing
 * - Own error handling
 * - Own position management
 * - Does NOT depend on market hours
 * - DYNAMIC position sizing based on available balance
 */
public class KrakenTradingLoop implements Runnable {
    private static final Logger logger = LoggerFactory.getLogger(KrakenTradingLoop.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    
    private final KrakenClient krakenClient;
    private final PortfolioManager portfolio;
    private final GridTradingService gridTradingService;
    
    // Kraken-specific settings
    private final double takeProfitPercent;
    private final double stopLossPercent;
    private final double trailingStopPercent;
    private final int configMaxPositions;  // From config (minimum)
    private final double positionSizeUsd;
    
    // Dynamic max positions (recalculated each cycle based on balance)
    private volatile int dynamicMaxPositions;
    
    // Cycle timing (independent from Alpaca)
    private final long cycleIntervalMs;
    
    // State
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicBoolean paused = new AtomicBoolean(false);
    private final ConcurrentHashMap<String, TradePosition> krakenPositions = new ConcurrentHashMap<>();
    
    // Crypto watchlist
    private static final List<String> CRYPTO_SYMBOLS = List.of(
        "BTC/USD", "ETH/USD", "SOL/USD", "DOGE/USD", "XRP/USD"
    );
    
    public KrakenTradingLoop(KrakenClient krakenClient, PortfolioManager portfolio,
                              GridTradingService gridTradingService,
                              double takeProfitPercent, double stopLossPercent,
                              double trailingStopPercent, int maxPositions,
                              double positionSizeUsd, long cycleIntervalMs) {
        this.krakenClient = krakenClient;
        this.portfolio = portfolio;
        this.gridTradingService = gridTradingService;
        this.takeProfitPercent = takeProfitPercent;
        this.stopLossPercent = stopLossPercent;
        this.trailingStopPercent = trailingStopPercent;
        this.configMaxPositions = maxPositions;
        this.dynamicMaxPositions = maxPositions;  // Start with config value
        this.positionSizeUsd = positionSizeUsd;
        this.cycleIntervalMs = cycleIntervalMs;
        
        logger.info("🦑 Kraken Trading Loop initialized:");
        logger.info("   TP: {}%, SL: {}%, Trailing: {}%", 
            takeProfitPercent * 100, stopLossPercent * 100, trailingStopPercent * 100);
        logger.info("   Config Max Positions: {}, Position Size: ${}", maxPositions, positionSizeUsd);
        logger.info("   Dynamic sizing ENABLED (auto-adjusts based on balance)");
        logger.info("   Cycle Interval: {}ms", cycleIntervalMs);
    }
    
    @Override
    public void run() {
        logger.info("🦑 Kraken Trading Loop STARTED - Running 24/7 independently");
        
        // Initial sync
        syncKrakenPositions();
        
        while (running.get()) {
            try {
                if (paused.get()) {
                    Thread.sleep(1000);
                    continue;
                }
                
                long cycleStart = System.currentTimeMillis();
                
                // ===== STEP 0: Update dynamic max positions based on balance =====
                updateDynamicMaxPositions();
                
                // ===== STEP 1: Sync positions from Kraken balance =====
                syncKrakenPositions();
                
                // ===== STEP 2: Check TP/SL for existing positions =====
                checkPositionExits();
                
                // ===== STEP 3: Evaluate new entries =====
                if (krakenPositions.size() < dynamicMaxPositions) {
                    evaluateNewEntries();
                }
                
                // ===== STEP 4: Run Grid Trading (passive orders) =====
                try {
                    gridTradingService.runGridCycle(CRYPTO_SYMBOLS);
                } catch (Exception e) {
                    logger.warn("⛏️ Grid cycle error: {}", e.getMessage());
                }
                
                // ===== Broadcast status =====
                broadcastKrakenStatus();
                
                // Sleep remaining time
                long elapsed = System.currentTimeMillis() - cycleStart;
                long sleepTime = Math.max(100, cycleIntervalMs - elapsed);
                Thread.sleep(sleepTime);
                
            } catch (InterruptedException e) {
                logger.info("🦑 Kraken loop interrupted");
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                logger.error("🦑 Kraken cycle error: {}", e.getMessage(), e);
                try {
                    Thread.sleep(5000); // Back off on error
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        
        logger.info("🦑 Kraken Trading Loop STOPPED");
    }
    
    /**
     * Dynamically calculate max positions based on available balance.
     * This allows the bot to scale up automatically when you add funds.
     * Formula: maxPositions = floor(totalEquity / positionSizeUsd)
     * Capped at 10 positions to avoid over-diversification.
     */
    private void updateDynamicMaxPositions() {
        try {
            String tradeBalanceJson = krakenClient.getTradeBalanceAsync().join();
            JsonNode tradeBalance = mapper.readTree(tradeBalanceJson);
            JsonNode result = tradeBalance.path("result");
            
            if (!result.has("eb")) return;
            
            // "eb" = equivalent balance (total equity in USD)
            double totalEquity = result.get("eb").asDouble();
            
            // Calculate how many positions we can afford
            // Reserve 20% for grid fishing orders
            double availableForPositions = totalEquity * 0.80;
            int calculatedMax = (int) Math.floor(availableForPositions / positionSizeUsd);
            
            // Clamp between configMaxPositions (minimum) and 10 (maximum)
            int newMax = Math.max(configMaxPositions, Math.min(calculatedMax, 10));
            
            // Only log if changed
            if (newMax != dynamicMaxPositions) {
                logger.info("📊 Dynamic max positions updated: {} → {} (equity: ${}, per-position: ${})",
                    dynamicMaxPositions, newMax, String.format("%.2f", totalEquity), positionSizeUsd);
                dynamicMaxPositions = newMax;
            }
            
        } catch (Exception e) {
            logger.debug("Failed to update dynamic max positions: {}", e.getMessage());
            // Keep using existing value on error
        }
    }
    
    /**
     * Sync positions from Kraken balance (spot holdings)
     */
    private void syncKrakenPositions() {
        try {
            String balanceJson = krakenClient.getBalanceAsync().join();
            JsonNode balanceNode = mapper.readTree(balanceJson);
            JsonNode result = balanceNode.path("result");
            
            if (!result.isObject()) return;
            
            var fields = result.fields();
            while (fields.hasNext()) {
                var entry = fields.next();
                String asset = entry.getKey();
                double balance = entry.getValue().asDouble();
                
                // Skip fiat and tiny balances
                if (asset.equals("ZUSD") || asset.equals("USD") || asset.equals("EUR") || 
                    asset.equals("ZEUR") || asset.equals("USDT") || asset.equals("USDC") ||
                    balance < 0.0001) {
                    continue;
                }
                
                // Convert asset to symbol (e.g., SOL -> SOL/USD)
                String symbol = assetToSymbol(asset);
                
                // Check if we already track this position
                if (!krakenPositions.containsKey(symbol)) {
                    // New position discovered - fetch current price for entry estimation
                    try {
                        double price = fetchCurrentPrice(symbol);
                        if (price > 0) {
                            var pos = new TradePosition(
                                symbol, price, balance,
                                price * (1 - stopLossPercent),
                                price * (1 + takeProfitPercent),
                                Instant.now()
                            );
                            krakenPositions.put(symbol, pos);
                            portfolio.setPosition(symbol, Optional.of(pos));
                            logger.info("🦑 Synced position: {} x{} @ ${}", symbol, balance, price);
                        }
                    } catch (Exception e) {
                        logger.warn("🦑 Failed to sync {}: {}", symbol, e.getMessage());
                    }
                } else {
                    // Update quantity if changed
                    var existing = krakenPositions.get(symbol);
                    if (Math.abs(existing.quantity() - balance) > 0.0001) {
                        var updated = new TradePosition(
                            symbol, existing.entryPrice(), balance,
                            existing.stopLoss(), existing.takeProfit(),
                            existing.entryTime()
                        );
                        krakenPositions.put(symbol, updated);
                        portfolio.setPosition(symbol, Optional.of(updated));
                    }
                }
            }
        } catch (Exception e) {
            logger.error("🦑 Balance sync failed: {}", e.getMessage());
        }
    }
    
    /**
     * Check TP/SL exits for all positions
     */
    private void checkPositionExits() {
        for (var entry : krakenPositions.entrySet()) {
            String symbol = entry.getKey();
            TradePosition pos = entry.getValue();
            
            try {
                double currentPrice = fetchCurrentPrice(symbol);
                if (currentPrice <= 0) continue;
                
                // Calculate distances from entry
                double pnlPercent = (currentPrice - pos.entryPrice()) / pos.entryPrice() * 100;
                double tpDistance = ((pos.takeProfit() - currentPrice) / currentPrice) * 100;
                double slDistance = ((currentPrice - pos.stopLoss()) / currentPrice) * 100;
                
                // ALWAYS log position status (visible monitoring)
                logger.info("🔍 {} | Price: ${} | Entry: ${} | PnL: {}% | SL: ${} | TP: ${}",
                    symbol, String.format("%.4f", currentPrice), String.format("%.4f", pos.entryPrice()), 
                    String.format("%+.2f", pnlPercent), String.format("%.4f", pos.stopLoss()), 
                    String.format("%.4f", pos.takeProfit()));
                
                // SL/TP thresholds (stopLossPercent=0.005 -> 0.5%, takeProfitPercent=0.0075 -> 0.75%)
                double slThreshold = stopLossPercent * 100;   // 0.5
                double tpThreshold = takeProfitPercent * 100; // 0.75
                
                // ===== AGGRESSIVE STOP LOSS CHECK (0.5%) =====
                if (pnlPercent <= -slThreshold) {
                    logger.warn("🛑 STOP LOSS TRIGGERED: {} @ ${} | Loss: {}% (threshold: -{}%) | Entry: ${}", 
                        symbol, String.format("%.4f", currentPrice), String.format("%.2f", pnlPercent),
                        String.format("%.2f", slThreshold), String.format("%.4f", pos.entryPrice()));
                    executeSell(symbol, pos, "STOP_LOSS", currentPrice);
                    continue;
                }
                
                // ===== TAKE PROFIT CHECK (0.75%) =====
                if (pnlPercent >= tpThreshold) {
                    logger.info("🎯 TAKE PROFIT TRIGGERED: {} @ ${} | Profit: {}% (threshold: +{}%) | Entry: ${}", 
                        symbol, String.format("%.4f", currentPrice), String.format("%.2f", pnlPercent),
                        String.format("%.2f", tpThreshold), String.format("%.4f", pos.entryPrice()));
                    executeSell(symbol, pos, "TAKE_PROFIT", currentPrice);
                    continue;
                }
                
                // Update trailing stop if in profit
                if (pnlPercent > 0 && trailingStopPercent > 0) {
                    double newStop = currentPrice * (1 - trailingStopPercent);
                    if (newStop > pos.stopLoss()) {
                        var updated = new TradePosition(
                            symbol, pos.entryPrice(), pos.quantity(),
                            newStop, pos.takeProfit(), pos.entryTime()
                        );
                        krakenPositions.put(symbol, updated);
                        portfolio.setPosition(symbol, Optional.of(updated));
                        logger.info("🦑 Trailing stop updated: {} SL ${} -> ${}", symbol, 
                            String.format("%.4f", pos.stopLoss()), String.format("%.4f", newStop));
                    }
                }
                
            } catch (Exception e) {
                logger.warn("🦑 Price check failed for {}: {}", symbol, e.getMessage());
            }
        }
    }
    
    /**
     * Evaluate new entry opportunities
     */
    private void evaluateNewEntries() {
        for (String symbol : CRYPTO_SYMBOLS) {
            // Skip if already holding
            if (krakenPositions.containsKey(symbol)) continue;
            
            // Check max positions
            if (krakenPositions.size() >= dynamicMaxPositions) break;
            
            try {
                String krakenSymbol = KrakenClient.toKrakenSymbol(symbol);
                String tickerJson = krakenClient.getTickerAsync(krakenSymbol).join();
                JsonNode root = mapper.readTree(tickerJson);
                
                if (root.has("error") && root.get("error").size() > 0) {
                    continue;
                }
                
                JsonNode result = root.get("result");
                if (result == null || !result.fields().hasNext()) continue;
                
                JsonNode pairData = result.fields().next().getValue();
                
                double currentPrice = pairData.get("c").get(0).asDouble();
                double openPrice = pairData.get("o").asDouble();
                double lowPrice24h = pairData.get("l").get(1).asDouble();
                double highPrice24h = pairData.get("h").get(1).asDouble();
                double vwap24h = pairData.get("p").get(1).asDouble();
                
                // Calculate indicators
                double dayChange = ((currentPrice - openPrice) / openPrice) * 100;
                double rangePosition = (highPrice24h > lowPrice24h) 
                    ? (currentPrice - lowPrice24h) / (highPrice24h - lowPrice24h) * 100 
                    : 50;
                double distanceFromVWAP = ((currentPrice - vwap24h) / vwap24h) * 100;
                
                // === MICRO-PROFIT ENTRY CRITERIA ===
                boolean inBuyZone = rangePosition < 35;          // Lower 35% of range
                boolean belowVWAP = distanceFromVWAP <= 0.3;     // At or below VWAP
                boolean notFreefalling = dayChange > -4.0;       // Not crashing
                boolean hasVolume = pairData.get("v").get(1).asDouble() > 0;
                
                if (inBuyZone && belowVWAP && notFreefalling && hasVolume) {
                    logger.info("🦑 BUY SIGNAL: {} | Range: {}% | VWAP dist: {}% | Day: {}%",
                        symbol, String.format("%.1f", rangePosition), String.format("%.2f", distanceFromVWAP), 
                        String.format("%.2f", dayChange));
                    
                    executeBuy(symbol, currentPrice);
                }
                
            } catch (Exception e) {
                logger.debug("🦑 Evaluation failed for {}: {}", symbol, e.getMessage());
            }
        }
    }
    
    /**
     * Execute BUY order
     */
    private void executeBuy(String symbol, double price) {
        try {
            String krakenSymbol = KrakenClient.toKrakenSymbol(symbol);
            double volume = positionSizeUsd / price;
            volume = Math.round(volume * 100000000.0) / 100000000.0; // 8 decimals
            
            // Validate order
            boolean canPlace = krakenClient.canPlaceOrder(krakenSymbol, volume, price).join();
            if (!canPlace) {
                logger.warn("🦑 Order validation failed for {}", symbol);
                return;
            }
            
            // Place market order
            String result = krakenClient.placeMarketOrderAsync(krakenSymbol, "buy", volume).join();
            
            if (result.contains("ERROR")) {
                logger.error("🦑 BUY FAILED: {} - {}", symbol, result);
                TradingWebSocketHandler.broadcastActivity("🦑 BUY FAILED: " + symbol + " - " + result, "ERROR");
                return;
            }
            
            // Create position tracking
            var pos = new TradePosition(
                symbol, price, volume,
                price * (1 - stopLossPercent),
                price * (1 + takeProfitPercent),
                Instant.now()
            );
            krakenPositions.put(symbol, pos);
            portfolio.setPosition(symbol, Optional.of(pos));
            
            logger.info("🦑 BUY EXECUTED: {} x{} @ ${}", symbol, volume, price);
            TradingWebSocketHandler.broadcastActivity(
                String.format("🦑 BUY: %s x%.6f @ $%.4f", symbol, volume, price), "SUCCESS");
            TradingWebSocketHandler.broadcastTradeEvent(symbol, "BUY", price, volume, "Micro-profit entry");
            
        } catch (Exception e) {
            logger.error("🦑 Buy execution failed for {}: {}", symbol, e.getMessage());
        }
    }
    
    /**
     * Execute SELL order
     */
    private void executeSell(String symbol, TradePosition pos, String reason, double price) {
        try {
            String krakenSymbol = KrakenClient.toKrakenSymbol(symbol);
            
            String result = krakenClient.placeMarketOrderAsync(krakenSymbol, "sell", pos.quantity()).join();
            
            if (result.contains("ERROR")) {
                logger.error("🦑 SELL FAILED: {} - {}", symbol, result);
                TradingWebSocketHandler.broadcastActivity("🦑 SELL FAILED: " + symbol + " - " + result, "ERROR");
                return;
            }
            
            // Calculate PnL
            double pnl = (price - pos.entryPrice()) * pos.quantity();
            double pnlPercent = (price - pos.entryPrice()) / pos.entryPrice() * 100;
            
            // Remove from tracking
            krakenPositions.remove(symbol);
            portfolio.setPosition(symbol, Optional.empty());
            
            logger.info("🦑 SELL EXECUTED: {} x{} @ ${} | PnL: ${} ({}%) | Reason: {}",
                symbol, pos.quantity(), price, String.format("%.2f", pnl), 
                String.format("%.2f", pnlPercent), reason);
            
            String emoji = pnl >= 0 ? "✅" : "❌";
            TradingWebSocketHandler.broadcastActivity(
                String.format("%s SELL: %s @ $%.4f | PnL: $%.2f (%s)", emoji, symbol, price, pnl, reason), 
                pnl >= 0 ? "SUCCESS" : "WARN");
            TradingWebSocketHandler.broadcastTradeEvent(symbol, "SELL", price, pos.quantity(), 
                reason + " | PnL: $" + String.format("%.2f", pnl));
            
        } catch (Exception e) {
            logger.error("🦑 Sell execution failed for {}: {}", symbol, e.getMessage());
        }
    }
    
    /**
     * Fetch current price for a symbol
     */
    private double fetchCurrentPrice(String symbol) {
        try {
            String krakenSymbol = KrakenClient.toKrakenSymbol(symbol);
            String tickerJson = krakenClient.getTickerAsync(krakenSymbol).join();
            JsonNode root = mapper.readTree(tickerJson);
            
            if (root.has("error") && root.get("error").size() > 0) {
                return 0;
            }
            
            JsonNode result = root.get("result");
            if (result != null && result.fields().hasNext()) {
                JsonNode pairData = result.fields().next().getValue();
                return pairData.get("c").get(0).asDouble();
            }
        } catch (Exception e) {
            logger.debug("Price fetch failed for {}: {}", symbol, e.getMessage());
        }
        return 0;
    }
    
    /**
     * Convert Kraken asset to trading symbol
     */
    private String assetToSymbol(String asset) {
        String normalized = asset.startsWith("X") && asset.length() > 3 ? asset.substring(1) : asset;
        return switch (normalized.toUpperCase()) {
            case "XBT" -> "BTC/USD";
            case "ETH" -> "ETH/USD";
            case "SOL" -> "SOL/USD";
            case "XDG", "DOGE" -> "DOGE/USD";
            case "XRP" -> "XRP/USD";
            case "ADA" -> "ADA/USD";
            case "DOT" -> "DOT/USD";
            case "AVAX" -> "AVAX/USD";
            default -> normalized + "/USD";
        };
    }
    
    /**
     * Broadcast Kraken status to dashboard
     */
    private void broadcastKrakenStatus() {
        try {
            StringBuilder status = new StringBuilder();
            status.append("🦑 Kraken: ").append(krakenPositions.size()).append("/").append(dynamicMaxPositions).append(" positions");
            
            if (!krakenPositions.isEmpty()) {
                for (var entry : krakenPositions.entrySet()) {
                    var pos = entry.getValue();
                    double price = fetchCurrentPrice(entry.getKey());
                    if (price > 0) {
                        double pnl = (price - pos.entryPrice()) / pos.entryPrice() * 100;
                        status.append(" | ").append(entry.getKey()).append(": ")
                              .append(String.format("%+.2f%%", pnl));
                    }
                }
            }
            
            TradingWebSocketHandler.broadcastActivity(status.toString(), "INFO");
        } catch (Exception e) {
            // Ignore broadcast errors
        }
    }
    
    // Control methods
    public void stop() { running.set(false); }
    public void pause() { paused.set(true); logger.info("🦑 Kraken loop PAUSED"); }
    public void resume() { paused.set(false); logger.info("🦑 Kraken loop RESUMED"); }
    public boolean isRunning() { return running.get() && !paused.get(); }
    public Map<String, TradePosition> getPositions() { return Map.copyOf(krakenPositions); }
}
