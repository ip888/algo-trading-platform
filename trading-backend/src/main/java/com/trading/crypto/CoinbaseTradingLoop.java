package com.trading.crypto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trading.broker.CoinbaseClient;
import com.trading.config.TradingConfig;
import com.trading.portfolio.PortfolioManager;
import com.trading.websocket.TradingWebSocketHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Coinbase Trading Loop 💰
 * 
 * Independent crypto trading loop using Coinbase Advanced Trade API.
 * 
 * ADVANTAGES OVER KRAKEN:
 * ✅ Works in US AND EU (Spain, Poland, all EU countries)
 * ✅ 30 requests/second rate limit (vs Kraken's ~4/minute)
 * ✅ Regulated exchange (public company)
 * ✅ NO RATE LIMIT ISSUES!
 * 
 * Runs 24/7 completely separate from Alpaca stock trading.
 */
public class CoinbaseTradingLoop implements Runnable {
    private static final Logger logger = LoggerFactory.getLogger(CoinbaseTradingLoop.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    
    private final CoinbaseClient coinbaseClient;
    private final PortfolioManager portfolio;
    
    // Trading parameters
    private final double takeProfitPercent;
    private final double stopLossPercent;
    private final double trailingStopPercent;
    private final int maxPositions;
    private final double positionSizeUsd;
    
    // Cycle timing
    private final long cycleIntervalMs;
    
    // Entry criteria thresholds
    private final double entryRangeMax;
    private final double entryDayChangeMin;
    private final double entryRsiMax;
    private final double rsiExitMinProfit;
    private final long reentryCooldownMs;
    
    // Re-entry cooldown tracking
    private final ConcurrentHashMap<String, Long> sellCooldowns = new ConcurrentHashMap<>();
    
    // Simple position tracking
    private final ConcurrentHashMap<String, CoinbasePosition> positions = new ConcurrentHashMap<>();
    
    // State
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicBoolean paused = new AtomicBoolean(false);
    
    // Crypto watchlist - Coinbase uses "BTC-USD" format (not "BTC/USD")
    private static final List<String> CRYPTO_SYMBOLS = List.of(
        "BTC-USD", "ETH-USD", "SOL-USD", "DOGE-USD", "XRP-USD", "AVAX-USD"
    );
    
    /**
     * Simple position record for tracking.
     */
    public record CoinbasePosition(
        String symbol,
        double entryPrice,
        double quantity,
        long entryTimeMs,
        double highWaterMark
    ) {
        public CoinbasePosition withHighWaterMark(double newHighWaterMark) {
            return new CoinbasePosition(symbol, entryPrice, quantity, entryTimeMs, 
                Math.max(highWaterMark, newHighWaterMark));
        }
    }
    
    public CoinbaseTradingLoop(CoinbaseClient coinbaseClient, PortfolioManager portfolio,
                                double takeProfitPercent, double stopLossPercent,
                                double trailingStopPercent, int maxPositions,
                                double positionSizeUsd, long cycleIntervalMs) {
        this.coinbaseClient = coinbaseClient;
        this.portfolio = portfolio;
        this.takeProfitPercent = takeProfitPercent;
        this.stopLossPercent = stopLossPercent;
        this.trailingStopPercent = trailingStopPercent;
        this.maxPositions = maxPositions;
        this.positionSizeUsd = positionSizeUsd;
        this.cycleIntervalMs = cycleIntervalMs;
        
        // Load entry criteria from config
        TradingConfig config = TradingConfig.getInstance();
        this.entryRangeMax = config.getCoinbaseEntryRangeMax();
        this.entryDayChangeMin = config.getCoinbaseEntryDayChangeMin();
        this.entryRsiMax = config.getCoinbaseEntryRsiMax();
        this.rsiExitMinProfit = config.getCoinbaseRsiExitMinProfit();
        this.reentryCooldownMs = config.getCoinbaseReentryCooldownMs();
        
        logger.info("💰 Coinbase Trading Loop initialized:");
        logger.info("   TP: {}%, SL: {}%, Trailing: {}%", 
            takeProfitPercent * 100, stopLossPercent * 100, trailingStopPercent * 100);
        logger.info("   Entry: Range<{}%, DayChange>{}%, RSI<{}", entryRangeMax, entryDayChangeMin, entryRsiMax);
        logger.info("   Max Positions: {}, Position Size: ${}", maxPositions, positionSizeUsd);
        logger.info("   Cycle Interval: {}ms", cycleIntervalMs);
        logger.info("   Symbols: {}", CRYPTO_SYMBOLS);
    }
    
    @Override
    public void run() {
        logger.info("💰 Coinbase Trading Loop STARTED - 24/7 crypto trading");
        
        // Test connection
        if (!coinbaseClient.testConnection()) {
            logger.error("❌ Coinbase connection failed! Check API credentials.");
            return;
        }
        logger.info("✅ Coinbase connection verified");
        
        while (running.get()) {
            try {
                if (!paused.get()) {
                    runTradingCycle();
                } else {
                    logger.debug("💤 Coinbase loop paused");
                }
                
                Thread.sleep(cycleIntervalMs);
            } catch (InterruptedException e) {
                logger.info("💰 Coinbase Trading Loop interrupted");
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                logger.error("❌ Coinbase cycle error: {}", e.getMessage(), e);
                try {
                    Thread.sleep(60_000);  // Wait 1 min on error
                } catch (InterruptedException ie) {
                    break;
                }
            }
        }
        
        logger.info("💰 Coinbase Trading Loop STOPPED");
    }
    
    private void runTradingCycle() {
        long cycleStart = System.currentTimeMillis();
        
        try {
            // 1. Get current balance
            double usdBalance = coinbaseClient.getBalance("USD");
            double usdcBalance = coinbaseClient.getBalance("USDC");
            double availableCash = usdBalance + usdcBalance;
            
            // 2. Sync positions
            Map<String, CoinbaseClient.Position> cbPositions = coinbaseClient.getPositions();
            int currentPositionCount = cbPositions.size();
            
            // 3. Check existing positions for exits
            for (CoinbaseClient.Position pos : cbPositions.values()) {
                checkExitConditions(pos);
            }
            
            // 4. Look for new entry opportunities
            if (currentPositionCount < maxPositions && availableCash >= positionSizeUsd) {
                scanForEntries(availableCash);
            }
            
            // 5. Log status
            long elapsed = System.currentTimeMillis() - cycleStart;
            logger.info("💰 Coinbase cycle: ${} cash, {} positions, {}ms", 
                String.format("%.2f", availableCash), currentPositionCount, elapsed);
            
            // 6. Broadcast to dashboard
            broadcastStatus(availableCash, cbPositions);
            
        } catch (Exception e) {
            logger.error("❌ Trading cycle failed: {}", e.getMessage());
        }
    }
    
    private void scanForEntries(double availableCash) {
        for (String symbol : CRYPTO_SYMBOLS) {
            // Check cooldown
            Long cooldownExpiry = sellCooldowns.get(symbol);
            if (cooldownExpiry != null && System.currentTimeMillis() < cooldownExpiry) {
                logger.debug("⏳ {} still in cooldown", symbol);
                continue;
            }
            
            // Check if already in position
            String baseCurrency = symbol.split("-")[0];
            if (coinbaseClient.getBalance(baseCurrency) > 0.00001) {
                continue;
            }
            
            // Get ticker data
            JsonNode ticker = coinbaseClient.getTicker(symbol);
            if (ticker == null) {
                continue;
            }
            
            // Extract price data
            double price = ticker.path("price").asDouble(0);
            double high24h = ticker.path("high_24_h").asDouble(0);
            double low24h = ticker.path("low_24_h").asDouble(0);
            double priceChange = ticker.path("price_percentage_change_24h").asDouble(0);
            
            if (price <= 0 || high24h <= low24h) {
                continue;
            }
            
            // Calculate range position (0% = at low, 100% = at high)
            double rangePosition = ((price - low24h) / (high24h - low24h)) * 100;
            
            // Entry criteria:
            // 1. Price in lower part of 24h range (not chasing highs)
            // 2. Not in a freefall (day change not too negative)
            
            boolean rangeOk = rangePosition < entryRangeMax;
            boolean dayChangeOk = priceChange > entryDayChangeMin;
            
            if (rangeOk && dayChangeOk) {
                logger.info("🎯 Entry signal for {}: price=${}, range={}%, change={}%",
                    symbol, String.format("%.2f", price), String.format("%.1f", rangePosition),
                    String.format("%.2f", priceChange));
                
                // Calculate position size (use quote size for market orders)
                double orderSize = Math.min(positionSizeUsd, availableCash * 0.95);
                
                // Place market buy order
                JsonNode result = coinbaseClient.placeMarketOrder(symbol, "BUY", orderSize);
                if (result != null && result.path("success").asBoolean()) {
                    String orderId = result.path("order_id").asText();
                    
                    // Track position
                    CoinbasePosition newPos = new CoinbasePosition(
                        symbol,
                        price,
                        orderSize / price,  // Approximate size in base currency
                        System.currentTimeMillis(),
                        price  // Initial high water mark = entry price
                    );
                    positions.put(symbol, newPos);
                    
                    logger.info("✅ BOUGHT {} ${} @ ${} (order: {})", 
                        symbol, String.format("%.2f", orderSize), 
                        String.format("%.2f", price), orderId);
                    
                    // Only open one position per cycle
                    return;
                }
            }
        }
    }
    
    private void checkExitConditions(CoinbaseClient.Position pos) {
        String symbol = pos.currency() + "-USD";
        double currentPrice = pos.currentPrice();
        
        CoinbasePosition tracked = positions.get(symbol);
        if (tracked == null) {
            // Position exists but we don't have entry data - use market value / size as entry estimate
            double estimatedEntry = currentPrice; // Assume current price for new tracking
            tracked = new CoinbasePosition(symbol, estimatedEntry, pos.size(), 
                System.currentTimeMillis(), currentPrice);
            positions.put(symbol, tracked);
        }
        
        double entryPrice = tracked.entryPrice();
        double pnlPercent = ((currentPrice - entryPrice) / entryPrice) * 100;
        
        // Update high water mark for trailing stop
        if (currentPrice > tracked.highWaterMark()) {
            positions.put(symbol, tracked.withHighWaterMark(currentPrice));
            tracked = positions.get(symbol);
        }
        
        // Check exit conditions
        boolean takeProfitHit = pnlPercent >= takeProfitPercent * 100;
        boolean stopLossHit = pnlPercent <= -stopLossPercent * 100;
        
        // Trailing stop check
        double trailingThreshold = tracked.highWaterMark() * (1 - trailingStopPercent);
        boolean trailingStopHit = currentPrice < trailingThreshold && pnlPercent > 0;
        
        String exitReason = null;
        if (takeProfitHit) {
            exitReason = "TAKE_PROFIT";
        } else if (stopLossHit) {
            exitReason = "STOP_LOSS";
        } else if (trailingStopHit) {
            exitReason = "TRAILING_STOP";
        }
        
        if (exitReason != null) {
            logger.info("📤 {} exit triggered for {}: P&L={}%", 
                exitReason, symbol, String.format("%.2f", pnlPercent));
            
            // Calculate sell size (sell entire position)
            double sellSize = pos.marketValue();
            
            // Place market sell order
            JsonNode result = coinbaseClient.placeMarketOrder(symbol, "SELL", sellSize);
            if (result != null && result.path("success").asBoolean()) {
                logger.info("✅ SOLD {} ${} @ ${} - P&L: {}%",
                    symbol, String.format("%.2f", sellSize),
                    String.format("%.2f", currentPrice),
                    String.format("%.2f", pnlPercent));
                
                // Set cooldown
                sellCooldowns.put(symbol, System.currentTimeMillis() + reentryCooldownMs);
                
                // Remove from tracking
                positions.remove(symbol);
            }
        }
    }
    
    private void broadcastStatus(double availableCash, Map<String, CoinbaseClient.Position> cbPositions) {
        try {
            Map<String, Object> status = Map.of(
                "exchange", "coinbase",
                "cash", availableCash,
                "positions", cbPositions.size(),
                "connected", coinbaseClient.isConnected(),
                "timestamp", Instant.now().toString()
            );
            
            // Use existing broadcast method
            TradingWebSocketHandler.broadcastActivity(
                "Coinbase: $" + String.format("%.2f", availableCash) + " cash, " + cbPositions.size() + " positions",
                "INFO"
            );
        } catch (Exception e) {
            logger.debug("Broadcast failed: {}", e.getMessage());
        }
    }
    
    // ==================== Control Methods ====================
    
    public void pause() {
        paused.set(true);
        logger.info("⏸️ Coinbase Trading Loop PAUSED");
    }
    
    public void resume() {
        paused.set(false);
        logger.info("▶️ Coinbase Trading Loop RESUMED");
    }
    
    public void stop() {
        running.set(false);
        logger.info("⏹️ Coinbase Trading Loop STOPPING...");
    }
    
    public boolean isPaused() {
        return paused.get();
    }
    
    public boolean isRunning() {
        return running.get();
    }
    
    public Map<String, CoinbasePosition> getPositions() {
        return new ConcurrentHashMap<>(positions);
    }
    
    public CoinbaseClient getClient() {
        return coinbaseClient;
    }
}
