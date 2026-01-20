package com.trading.crypto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trading.broker.KrakenClient;
import com.trading.config.TradingConfig;
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
    
    // Entry criteria thresholds (from config)
    private final double entryRangeMax;
    private final double entryDayChangeMin;
    private final double entryRsiMax;
    private final double rsiExitMinProfit;
    private final long reentryCooldownMs;
    
    // Re-entry cooldown to prevent churn (symbol -> timestamp when cooldown expires)
    private final ConcurrentHashMap<String, Long> sellCooldowns = new ConcurrentHashMap<>();
    
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
        
        // Load entry criteria from config (with sensible defaults)
        TradingConfig config = TradingConfig.getInstance();
        this.entryRangeMax = config.getKrakenEntryRangeMax();
        this.entryDayChangeMin = config.getKrakenEntryDayChangeMin();
        this.entryRsiMax = config.getKrakenEntryRsiMax();
        this.rsiExitMinProfit = config.getKrakenRsiExitMinProfit();
        this.reentryCooldownMs = config.getKrakenReentryCooldownMs();
        
        logger.info("🦑 Kraken Trading Loop initialized:");
        logger.info("   TP: {}%, SL: {}%, Trailing: {}%", 
            takeProfitPercent * 100, stopLossPercent * 100, trailingStopPercent * 100);
        logger.info("   Entry: Range<{}%, DayChange>{}%, RSI<{}", entryRangeMax, entryDayChangeMin, entryRsiMax);
        logger.info("   RSI Exit Min Profit: {}%, Cooldown: {}min", rsiExitMinProfit, reentryCooldownMs / 60000);
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
     * FIXED: Fetches actual entry price from Kraken trade history instead of using current price
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
                    // New position discovered - fetch ACTUAL entry price from trade history
                    try {
                        double entryPrice = fetchActualEntryPrice(symbol, balance);
                        double currentPrice = fetchCurrentPrice(symbol);
                        
                        if (entryPrice > 0 && currentPrice > 0) {
                            double calculatedSL = entryPrice * (1 - stopLossPercent);
                            double calculatedTP = entryPrice * (1 + takeProfitPercent);
                            logger.info("🔧 DEBUG SYNC: {} | Entry: ${} | SL%: {} | TP%: {} | SL: ${} | TP: ${}",
                                symbol, String.format("%.2f", entryPrice), 
                                String.format("%.6f", stopLossPercent), String.format("%.6f", takeProfitPercent),
                                String.format("%.2f", calculatedSL), String.format("%.2f", calculatedTP));
                            
                            var pos = new TradePosition(
                                symbol, entryPrice, balance,
                                calculatedSL,
                                calculatedTP,
                                Instant.now()
                            );
                            krakenPositions.put(symbol, pos);
                            portfolio.setPosition(symbol, Optional.of(pos));
                            
                            // Initialize trailing TP
                            gridTradingService.initTrailingTp(symbol, entryPrice);
                            
                            double pnl = (currentPrice - entryPrice) / entryPrice * 100;
                            logger.info("🦑 Synced position: {} x{} | Entry: ${} | Current: ${} | PnL: {}%", 
                                symbol, String.format("%.6f", balance), String.format("%.2f", entryPrice),
                                String.format("%.2f", currentPrice), String.format("%+.2f", pnl));
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
     * Fetch actual entry price from Kraken trade history.
     * Uses weighted average of recent buy trades for this asset.
     * Falls back to 24h open price if no trade history found.
     */
    private double fetchActualEntryPrice(String symbol, double currentBalance) {
        try {
            String krakenSymbol = KrakenClient.toKrakenSymbol(symbol);
            
            // Try to get trade history from Kraken
            String tradesJson = krakenClient.getTradesHistoryAsync().join();
            JsonNode tradesNode = mapper.readTree(tradesJson);
            
            if (tradesNode.has("error") && tradesNode.get("error").size() > 0) {
                logger.debug("Trade history error: {}", tradesNode.get("error"));
                return fetchFallbackEntryPrice(symbol);
            }
            
            JsonNode tradesResult = tradesNode.path("result").path("trades");
            if (!tradesResult.isObject()) {
                return fetchFallbackEntryPrice(symbol);
            }
            
            // Calculate weighted average entry price from buy trades
            double totalCost = 0;
            double totalVolume = 0;
            
            var tradeIds = tradesResult.fieldNames();
            while (tradeIds.hasNext()) {
                String tradeId = tradeIds.next();
                JsonNode trade = tradesResult.get(tradeId);
                
                String pair = trade.path("pair").asText();
                String type = trade.path("type").asText();
                
                // Match our symbol (handle Kraken pair naming variations)
                if (!pair.contains(krakenSymbol.replace("/", "")) && 
                    !pair.contains(symbol.replace("/USD", "").toUpperCase())) {
                    continue;
                }
                
                // Only count BUY trades
                if (!"buy".equals(type)) continue;
                
                double price = trade.path("price").asDouble();
                double volume = trade.path("vol").asDouble();
                
                if (price > 0 && volume > 0) {
                    totalCost += price * volume;
                    totalVolume += volume;
                }
            }
            
            if (totalVolume > 0) {
                double avgPrice = totalCost / totalVolume;
                logger.info("📊 {} actual entry price from trades: ${} (from {} units)", 
                    symbol, String.format("%.4f", avgPrice), String.format("%.6f", totalVolume));
                return avgPrice;
            }
            
            // No matching trades found - use fallback
            return fetchFallbackEntryPrice(symbol);
            
        } catch (Exception e) {
            logger.debug("Failed to fetch trade history for {}: {}", symbol, e.getMessage());
            return fetchFallbackEntryPrice(symbol);
        }
    }
    
    /**
     * Fallback: Use 24h open price as estimated entry.
     * Better than current price because it's more likely to be closer to actual entry.
     */
    private double fetchFallbackEntryPrice(String symbol) {
        try {
            String krakenSymbol = KrakenClient.toKrakenSymbol(symbol);
            String tickerJson = krakenClient.getTickerAsync(krakenSymbol).join();
            JsonNode ticker = mapper.readTree(tickerJson);
            
            JsonNode result = ticker.path("result");
            if (result.fields().hasNext()) {
                JsonNode pairData = result.fields().next().getValue();
                // "o" = today's opening price - use as fallback entry estimate
                double openPrice = pairData.path("o").asDouble();
                if (openPrice > 0) {
                    logger.warn("⚠️ {} using 24h open as entry estimate: ${} (no trade history)", 
                        symbol, String.format("%.4f", openPrice));
                    return openPrice;
                }
            }
        } catch (Exception e) {
            logger.debug("Fallback price fetch failed for {}: {}", symbol, e.getMessage());
        }
        
        // Last resort: use current price (old behavior)
        logger.warn("⚠️ {} falling back to current price as entry (unreliable SL!)", symbol);
        return fetchCurrentPrice(symbol);
    }
    
    /**
     * Check TP/SL exits for all positions
     * Uses TRAILING TAKE-PROFIT from GridTradingService for better exits
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
                
                // Get RSI from grid service
                double rsi = gridTradingService.getRsi(symbol);
                boolean isOverbought = gridTradingService.isOverbought(symbol);
                
                // ALWAYS log position status with RSI
                logger.info("🔍 {} | ${} | Entry: ${} | PnL: {}% | RSI: {} | SL: ${}",
                    symbol, String.format("%.2f", currentPrice), String.format("%.2f", pos.entryPrice()), 
                    String.format("%+.2f", pnlPercent), String.format("%.1f", rsi), String.format("%.2f", pos.stopLoss()));
                
                // SL threshold (stopLossPercent=0.005 -> 0.5%)
                double slThreshold = stopLossPercent * 100;
                
                // ===== AGGRESSIVE STOP LOSS CHECK (0.5%) =====
                if (pnlPercent <= -slThreshold) {
                    logger.warn("🛑 STOP LOSS TRIGGERED: {} @ ${} | Loss: {}% | Entry: ${}", 
                        symbol, String.format("%.4f", currentPrice), String.format("%.2f", pnlPercent),
                        String.format("%.4f", pos.entryPrice()));
                    executeSell(symbol, pos, "STOP_LOSS", currentPrice);
                    gridTradingService.clearTrailingTp(symbol);
                    continue;
                }
                
                // ===== TRAILING TAKE-PROFIT (replaces fixed TP) =====
                // Activates at +0.5%, trails by 0.3%, caps at +2%
                boolean shouldExitTrailing = gridTradingService.updateTrailingTp(symbol, currentPrice, pos.entryPrice());
                if (shouldExitTrailing) {
                    logger.info("🎯 TRAILING TP EXIT: {} @ ${} | Profit: {}%", 
                        symbol, String.format("%.4f", currentPrice), String.format("%.2f", pnlPercent));
                    executeSell(symbol, pos, "TRAILING_TP", currentPrice);
                    continue;
                }
                
                // ===== RSI OVERBOUGHT EXIT (when in SIGNIFICANT profit) =====
                // FIXED: Only exit if profit covers fees + minimum gain
                // Fees are ~0.4% round trip, so need sufficient profit to be worthwhile
                if (isOverbought && pnlPercent > rsiExitMinProfit) {
                    logger.info("📊 RSI OVERBOUGHT EXIT: {} @ ${} | RSI: {} | Profit: {}%", 
                        symbol, String.format("%.4f", currentPrice), String.format("%.1f", rsi), String.format("%.2f", pnlPercent));
                    executeSell(symbol, pos, "RSI_OVERBOUGHT", currentPrice);
                    gridTradingService.clearTrailingTp(symbol);
                    continue;
                }
                
                // Update trailing stop if in profit (legacy behavior, kept for safety)
                if (pnlPercent > 0 && trailingStopPercent > 0) {
                    double newStop = currentPrice * (1 - trailingStopPercent);
                    if (newStop > pos.stopLoss()) {
                        var updated = new TradePosition(
                            symbol, pos.entryPrice(), pos.quantity(),
                            newStop, pos.takeProfit(), pos.entryTime()
                        );
                        krakenPositions.put(symbol, updated);
                        portfolio.setPosition(symbol, Optional.of(updated));
                        logger.debug("🦑 Trailing stop updated: {} SL ${} -> ${}", symbol, 
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
            
            // Check re-entry cooldown (prevent churn)
            Long cooldownExpiry = sellCooldowns.get(symbol);
            if (cooldownExpiry != null && System.currentTimeMillis() < cooldownExpiry) {
                long remainingSec = (cooldownExpiry - System.currentTimeMillis()) / 1000;
                logger.debug("🦑 {} on cooldown for {} more seconds", symbol, remainingSec);
                continue;
            }
            
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
                
                // Get RSI for momentum confirmation
                double rsi = gridTradingService.getRsi(symbol);
                boolean isOversold = gridTradingService.isOversold(symbol);
                
                // === IMPROVED ENTRY CRITERIA (v2.0 - configurable) ===
                // 1. Must be in lower part of range
                boolean inBuyZone = rangePosition < entryRangeMax;
                
                // 2. Must be at or below VWAP (value area)
                boolean belowVWAP = distanceFromVWAP <= 0.2;
                
                // 3. Must NOT be in a downtrend - KEY FIX!
                boolean trendOK = dayChange > entryDayChangeMin;
                
                // 4. Must have volume
                boolean hasVolume = pairData.get("v").get(1).asDouble() > 0;
                
                // 5. RSI momentum confirmation
                boolean rsiConfirm = rsi < entryRsiMax;
                
                // Log current market conditions
                logger.debug("🦑 {} Market: Range={}% VWAP={}% Day={}% RSI={}",
                    symbol, String.format("%.1f", rangePosition), String.format("%.2f", distanceFromVWAP),
                    String.format("%.2f", dayChange), String.format("%.1f", rsi));
                
                // Entry requires ALL conditions (stricter)
                if (inBuyZone && belowVWAP && trendOK && hasVolume && rsiConfirm) {
                    logger.info("🦑 BUY SIGNAL: {} | Range: {}% | VWAP: {}% | Day: {}% | RSI: {}",
                        symbol, String.format("%.1f", rangePosition), String.format("%.2f", distanceFromVWAP), 
                        String.format("%.2f", dayChange), String.format("%.1f", rsi));
                    
                    executeBuy(symbol, currentPrice);
                } else if (inBuyZone && belowVWAP && !trendOK) {
                    // Log why we skipped (trend filter)
                    logger.info("🦑 SKIP: {} | Day change {}% too negative (need > {}%)",
                        symbol, String.format("%.2f", dayChange), entryDayChangeMin);
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
            
            // Set re-entry cooldown to prevent immediate churn
            sellCooldowns.put(symbol, System.currentTimeMillis() + reentryCooldownMs);
            logger.debug("🦑 {} cooldown set for {} minutes", symbol, reentryCooldownMs / 60000);
            
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
