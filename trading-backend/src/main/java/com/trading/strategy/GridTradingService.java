package com.trading.strategy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trading.api.AlpacaClient;
import com.trading.broker.KrakenClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Phase 3: Grid Miner ⛏️
 * Passive Strategy with Multi-Broker Support:
 * 1. "Fishing": Place Buy Limit orders below current price for assets we don't own.
 * 2. "Harvesting": If we own an asset, place Sell Limit orders above cost basis.
 * 
 * PROFITABILITY OPTIMIZED v2.0:
 * - Multi-Level Grid: Place orders at -0.3%, -0.5%, -1.0% levels
 * - RSI Momentum Filter: Skip overbought (RSI>70), double down oversold (RSI<30)
 * - Trailing Take-Profit: Let winners run with dynamic exit
 * - Cross-Asset Arbitrage: Trade BTC/ETH correlation breakdowns
 * - Dynamic grid spacing based on volatility
 * - Routes crypto orders to Kraken, stocks to Alpaca.
 */
public class GridTradingService {
    private static final Logger logger = LoggerFactory.getLogger(GridTradingService.class);
    private final AlpacaClient alpacaClient;
    private final KrakenClient krakenClient;
    private final ObjectMapper mapper = new ObjectMapper();
    private final boolean krakenEnabled;

    // ===== CONFIGURATION v2.0 - PROFITABILITY OPTIMIZED =====
    
    // Multi-Level Grid offsets (buy at multiple levels)
    private static final double[] GRID_BUY_LEVELS = {0.997, 0.995, 0.990};  // -0.3%, -0.5%, -1.0%
    private static final double[] GRID_LEVEL_WEIGHTS = {0.3, 0.4, 0.3};     // Distribution per level
    
    // Trailing Take-Profit (dynamic exit)
    private static final double TRAILING_TP_ACTIVATION = 0.005;  // Activate at +0.5%
    private static final double TRAILING_TP_DISTANCE = 0.003;    // Trail by 0.3%
    private static final double MAX_TAKE_PROFIT = 0.02;          // Cap at +2%
    
    // Dynamic grid sizing - scales with available balance
    private static final double MIN_GRID_SIZE = 11.0;   // BTC minimum (~$10)
    private static final double MAX_GRID_SIZE = 200.0;  // Cap for large balances
    private static final double GRID_BALANCE_RATIO = 0.80;  // Use 80% of available balance
    private double configGridSize = 35.0;  // Base config value (used as fallback)
    
    // Order management - INCREASED for more opportunities
    private static final int MAX_CONCURRENT_GRID_ORDERS = 3;  // 3x more orders
    private static final long STALE_ORDER_AGE_MS = 15 * 60 * 1000;  // 15 minutes = stale (was 5)
    private final ConcurrentHashMap<String, Long> pendingOrders = new ConcurrentHashMap<>();
    private long lastOrderCleanup = 0;
    
    // ===== RSI MOMENTUM FILTER =====
    private static final int RSI_PERIOD = 14;
    private static final double RSI_OVERBOUGHT = 70.0;
    private static final double RSI_OVERSOLD = 30.0;
    private final ConcurrentHashMap<String, RsiTracker> rsiTrackers = new ConcurrentHashMap<>();
    
    // ===== EMA TREND CONFIRMATION =====
    private static final int EMA_FAST_PERIOD = 9;
    private static final int EMA_SLOW_PERIOD = 21;
    private final ConcurrentHashMap<String, EmaTracker> emaTrackers = new ConcurrentHashMap<>();
    
    // ===== VOLUME SPIKE FILTER =====
    private static final int VOLUME_PERIOD = 20;
    private static final double VOLUME_SPIKE_THRESHOLD = 1.5;  // 1.5x average volume
    private final ConcurrentHashMap<String, VolumeTracker> volumeTrackers = new ConcurrentHashMap<>();
    
    // ===== MACD FILTER FOR TREND CONFIRMATION =====
    private static final int MACD_FAST = 12;
    private static final int MACD_SLOW = 26;
    private static final int MACD_SIGNAL = 9;
    private final ConcurrentHashMap<String, MacdTracker> macdTrackers = new ConcurrentHashMap<>();
    
    // ===== ATR FOR VOLATILITY-BASED TP/SL =====
    private static final int ATR_PERIOD = 14;
    private final ConcurrentHashMap<String, AtrTracker> atrTrackers = new ConcurrentHashMap<>();
    
    // ===== MOMENTUM TRACKER =====
    private final ConcurrentHashMap<String, MomentumTracker> momentumTrackers = new ConcurrentHashMap<>();
    private static final int MOMENTUM_PERIOD = 5;  // 5 price readings for short-term momentum
    
    // ===== DAILY P&L TRACKING =====
    private volatile double dailyPnL = 0.0;
    private volatile long dailyPnLResetTime = 0;
    private static final double MAX_DAILY_LOSS_PERCENT = 2.0;  // Stop trading if down 2%
    private volatile double accountEquity = 200.0;  // Will be updated from actual balance
    
    // ===== CROSS-ASSET ARBITRAGE =====
    private static final double CORRELATION_THRESHOLD = 0.85;  // BTC/ETH normally >0.85
    private static final double DIVERGENCE_THRESHOLD = 0.02;   // 2% divergence = opportunity
    private final ConcurrentHashMap<String, Double> lastPriceChanges = new ConcurrentHashMap<>();
    
    // ===== TRAILING TP STATE =====
    private final ConcurrentHashMap<String, TrailingTpState> trailingTpStates = new ConcurrentHashMap<>();
    
    /**
     * RSI Tracker - calculates RSI from price history
     */
    private static class RsiTracker {
        private final java.util.Deque<Double> prices = new java.util.ArrayDeque<>();
        private double avgGain = 0;
        private double avgLoss = 0;
        private double lastRsi = 50;
        private long lastUpdate = 0;
        
        synchronized void addPrice(double price) {
            if (prices.isEmpty()) {
                prices.addLast(price);
                return;
            }
            
            double lastPrice = prices.peekLast();
            double change = price - lastPrice;
            
            prices.addLast(price);
            if (prices.size() > RSI_PERIOD + 1) {
                prices.removeFirst();
            }
            
            // Calculate RSI using Wilder's smoothing
            if (prices.size() >= 2) {
                double gain = Math.max(0, change);
                double loss = Math.abs(Math.min(0, change));
                
                if (prices.size() < RSI_PERIOD + 1) {
                    avgGain = (avgGain * (prices.size() - 2) + gain) / (prices.size() - 1);
                    avgLoss = (avgLoss * (prices.size() - 2) + loss) / (prices.size() - 1);
                } else {
                    avgGain = (avgGain * (RSI_PERIOD - 1) + gain) / RSI_PERIOD;
                    avgLoss = (avgLoss * (RSI_PERIOD - 1) + loss) / RSI_PERIOD;
                }
                
                if (avgLoss == 0) {
                    lastRsi = 100;
                } else {
                    double rs = avgGain / avgLoss;
                    lastRsi = 100 - (100 / (1 + rs));
                }
            }
            lastUpdate = System.currentTimeMillis();
        }
        
        double getRsi() { return lastRsi; }
        boolean isOverbought() { return lastRsi > RSI_OVERBOUGHT; }
        boolean isOversold() { return lastRsi < RSI_OVERSOLD; }
        boolean hasEnoughData() { return prices.size() >= RSI_PERIOD; }
    }
    
    /**
     * EMA Tracker - calculates EMA9 and EMA21 for trend confirmation
     */
    private static class EmaTracker {
        private double ema9 = 0;
        private double ema21 = 0;
        private int priceCount = 0;
        private double sumForEma9 = 0;
        private double sumForEma21 = 0;
        private static final double EMA9_MULTIPLIER = 2.0 / (EMA_FAST_PERIOD + 1);
        private static final double EMA21_MULTIPLIER = 2.0 / (EMA_SLOW_PERIOD + 1);
        
        synchronized void addPrice(double price) {
            priceCount++;
            
            if (priceCount <= EMA_FAST_PERIOD) {
                sumForEma9 += price;
                if (priceCount == EMA_FAST_PERIOD) {
                    ema9 = sumForEma9 / EMA_FAST_PERIOD;
                }
            } else {
                ema9 = (price - ema9) * EMA9_MULTIPLIER + ema9;
            }
            
            if (priceCount <= EMA_SLOW_PERIOD) {
                sumForEma21 += price;
                if (priceCount == EMA_SLOW_PERIOD) {
                    ema21 = sumForEma21 / EMA_SLOW_PERIOD;
                }
            } else {
                ema21 = (price - ema21) * EMA21_MULTIPLIER + ema21;
            }
        }
        
        double getEma9() { return ema9; }
        double getEma21() { return ema21; }
        boolean hasEnoughData() { return priceCount >= EMA_SLOW_PERIOD; }
        boolean isBullish() { return hasEnoughData() && ema9 > ema21; }
        boolean isBearish() { return hasEnoughData() && ema9 < ema21; }
    }
    
    /**
     * Volume Tracker - tracks volume for spike detection
     */
    private static class VolumeTracker {
        private final java.util.Deque<Double> volumes = new java.util.ArrayDeque<>();
        private double avgVolume = 0;
        
        synchronized void addVolume(double volume) {
            volumes.addLast(volume);
            if (volumes.size() > VOLUME_PERIOD) {
                volumes.removeFirst();
            }
            
            // Calculate average volume
            if (!volumes.isEmpty()) {
                avgVolume = volumes.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            }
        }
        
        double getAvgVolume() { return avgVolume; }
        boolean hasEnoughData() { return volumes.size() >= VOLUME_PERIOD / 2; }
        boolean isVolumeSpike(double currentVolume) {
            return hasEnoughData() && avgVolume > 0 && currentVolume > avgVolume * VOLUME_SPIKE_THRESHOLD;
        }
    }
    
    /**
     * MACD Tracker - calculates MACD for trend confirmation
     * MACD = EMA12 - EMA26, Signal = EMA9 of MACD
     */
    private static class MacdTracker {
        private double ema12 = 0;
        private double ema26 = 0;
        private double signalLine = 0;
        private int priceCount = 0;
        private double sum12 = 0;
        private double sum26 = 0;
        private final java.util.Deque<Double> macdHistory = new java.util.ArrayDeque<>();
        private static final double EMA12_MULT = 2.0 / (MACD_FAST + 1);
        private static final double EMA26_MULT = 2.0 / (MACD_SLOW + 1);
        private static final double SIGNAL_MULT = 2.0 / (MACD_SIGNAL + 1);
        
        synchronized void addPrice(double price) {
            priceCount++;
            
            // Build EMA12
            if (priceCount <= MACD_FAST) {
                sum12 += price;
                if (priceCount == MACD_FAST) ema12 = sum12 / MACD_FAST;
            } else {
                ema12 = (price - ema12) * EMA12_MULT + ema12;
            }
            
            // Build EMA26
            if (priceCount <= MACD_SLOW) {
                sum26 += price;
                if (priceCount == MACD_SLOW) ema26 = sum26 / MACD_SLOW;
            } else {
                ema26 = (price - ema26) * EMA26_MULT + ema26;
            }
            
            // Calculate MACD and signal line
            if (priceCount >= MACD_SLOW) {
                double macd = ema12 - ema26;
                macdHistory.addLast(macd);
                if (macdHistory.size() > MACD_SIGNAL) macdHistory.removeFirst();
                
                if (macdHistory.size() >= MACD_SIGNAL) {
                    // Calculate signal as EMA of MACD
                    if (signalLine == 0) {
                        signalLine = macdHistory.stream().mapToDouble(Double::doubleValue).average().orElse(0);
                    } else {
                        signalLine = (macd - signalLine) * SIGNAL_MULT + signalLine;
                    }
                }
            }
        }
        
        double getMacd() { return ema12 - ema26; }
        double getSignalLine() { return signalLine; }
        double getHistogram() { return getMacd() - signalLine; }
        boolean hasEnoughData() { return priceCount >= MACD_SLOW + MACD_SIGNAL; }
        boolean isBullish() { return hasEnoughData() && getMacd() > signalLine; }
        boolean isBearish() { return hasEnoughData() && getMacd() < signalLine; }
        boolean isBullishCrossover() { 
            return hasEnoughData() && getHistogram() > 0 && getHistogram() < 0.001 * Math.abs(getMacd());
        }
    }
    
    /**
     * ATR Tracker - Average True Range for volatility-based targets
     */
    private static class AtrTracker {
        private final java.util.Deque<Double> trueRanges = new java.util.ArrayDeque<>();
        private double atr = 0;
        private double prevClose = 0;
        
        synchronized void addCandle(double high, double low, double close) {
            double tr;
            if (prevClose == 0) {
                tr = high - low;
            } else {
                // True Range = max(H-L, |H-prevC|, |L-prevC|)
                tr = Math.max(high - low, Math.max(Math.abs(high - prevClose), Math.abs(low - prevClose)));
            }
            prevClose = close;
            
            trueRanges.addLast(tr);
            if (trueRanges.size() > ATR_PERIOD) trueRanges.removeFirst();
            
            // Calculate ATR as SMA of true ranges
            if (!trueRanges.isEmpty()) {
                atr = trueRanges.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            }
        }
        
        double getAtr() { return atr; }
        boolean hasEnoughData() { return trueRanges.size() >= ATR_PERIOD / 2; }
        
        /** Get ATR as percentage of price */
        double getAtrPercent(double currentPrice) {
            return currentPrice > 0 ? (atr / currentPrice) * 100 : 2.0;
        }
        
        /** Calculate recommended TP based on ATR (1.5x ATR) */
        double getRecommendedTpPercent(double currentPrice) {
            double atrPct = getAtrPercent(currentPrice);
            // TP = 1.5x ATR, clamped between 0.8% and 3%
            return Math.max(0.8, Math.min(atrPct * 1.5, 3.0));
        }
        
        /** Calculate recommended SL based on ATR (1x ATR) */
        double getRecommendedSlPercent(double currentPrice) {
            double atrPct = getAtrPercent(currentPrice);
            // SL = 1x ATR, clamped between 0.5% and 2%
            return Math.max(0.5, Math.min(atrPct, 2.0));
        }
    }
    
    /**
     * Momentum Tracker - short-term price momentum
     */
    private static class MomentumTracker {
        private final java.util.Deque<Double> prices = new java.util.ArrayDeque<>();
        
        synchronized void addPrice(double price) {
            prices.addLast(price);
            if (prices.size() > MOMENTUM_PERIOD) prices.removeFirst();
        }
        
        boolean hasEnoughData() { return prices.size() >= MOMENTUM_PERIOD; }
        
        /** Get short-term momentum (% change over momentum period) */
        double getMomentum() {
            if (prices.size() < 2) return 0;
            double first = prices.peekFirst();
            double last = prices.peekLast();
            return first > 0 ? ((last - first) / first) * 100 : 0;
        }
        
        /** Check if momentum is positive (price rising) */
        boolean isPositive() { return getMomentum() > 0; }
        
        /** Check if momentum is strong positive (>0.1% in period) */
        boolean isStrongPositive() { return getMomentum() > 0.1; }
        
        /** Check if momentum is negative (price falling) */
        boolean isNegative() { return getMomentum() < -0.1; }
    }
    
    /**
     * Trailing Take-Profit State
     */
    private static class TrailingTpState {
        double highWaterMark;
        double activationPrice;
        boolean isActive;
        long activatedAt;
        
        TrailingTpState(double entryPrice) {
            this.highWaterMark = entryPrice;
            this.activationPrice = entryPrice * (1 + TRAILING_TP_ACTIVATION);
            this.isActive = false;
            this.activatedAt = 0;
        }
        
        void update(double currentPrice) {
            if (currentPrice > highWaterMark) {
                highWaterMark = currentPrice;
            }
            if (!isActive && currentPrice >= activationPrice) {
                isActive = true;
                activatedAt = System.currentTimeMillis();
            }
        }
        
        boolean shouldExit(double currentPrice, double entryPrice) {
            if (!isActive) return false;
            
            // Exit if price drops 0.3% from high water mark
            double trailingStop = highWaterMark * (1 - TRAILING_TP_DISTANCE);
            if (currentPrice <= trailingStop) {
                return true;
            }
            
            // Also exit if we hit max TP (2%)
            double maxTpPrice = entryPrice * (1 + MAX_TAKE_PROFIT);
            return currentPrice >= maxTpPrice;
        }
        
        double getTrailingStopPrice() {
            return highWaterMark * (1 - TRAILING_TP_DISTANCE);
        }
    }
    
    // ===== PHASE 2: Performance Tracking =====
    // Track wins, losses, and P&L per symbol to prefer profitable ones
    private final ConcurrentHashMap<String, SymbolPerformance> symbolPerformance = new ConcurrentHashMap<>();
    private static final int MIN_TRADES_FOR_WEIGHTING = 3;  // Need 3+ trades before weighting
    private static final double PERFORMANCE_WEIGHT_FACTOR = 0.3;  // 30% of score from performance
    
    // ===== PHASE 3: Dynamic Portfolio Management =====
    // Volatility tracking for position sizing
    private final ConcurrentHashMap<String, VolatilityData> symbolVolatility = new ConcurrentHashMap<>();
    private static final double BASE_VOLATILITY = 0.02;  // 2% daily = baseline
    private static final double HIGH_VOLATILITY_THRESHOLD = 0.05;  // 5% = high volatility
    private static final double VOLATILITY_SIZE_FACTOR = 0.5;  // Reduce size by 50% for high vol
    
    // Portfolio concentration limits
    private static final double MAX_SINGLE_POSITION_RATIO = 0.40;  // Max 40% in one asset
    private static final double CORRELATED_GROUP_LIMIT = 0.60;  // Max 60% in correlated group
    
    // Asset correlation groups (assets that move together)
    private static final Map<String, String> CORRELATION_GROUPS = Map.of(
        "BTC/USD", "CRYPTO_MAJOR",
        "ETH/USD", "CRYPTO_MAJOR",
        "SOL/USD", "CRYPTO_ALT",
        "DOGE/USD", "CRYPTO_MEME",
        "XRP/USD", "CRYPTO_ALT",
        "ADA/USD", "CRYPTO_ALT",
        "DOT/USD", "CRYPTO_ALT",
        "AVAX/USD", "CRYPTO_ALT"
    );
    
    /**
     * Volatility tracking for a symbol
     */
    private static class VolatilityData {
        double dailyVolatility = BASE_VOLATILITY;
        double high24h = 0;
        double low24h = 0;
        long lastUpdate = 0;
        
        void update(double high, double low, double current) {
            if (high > 0 && low > 0 && current > 0) {
                this.high24h = high;
                this.low24h = low;
                // Daily volatility = (high - low) / current
                this.dailyVolatility = (high - low) / current;
                this.lastUpdate = System.currentTimeMillis();
            }
        }
        
        boolean isHighVolatility() {
            return dailyVolatility > HIGH_VOLATILITY_THRESHOLD;
        }
        
        double getSizeMultiplier() {
            // High volatility = smaller position
            // Low volatility = normal position
            if (dailyVolatility > HIGH_VOLATILITY_THRESHOLD) {
                return VOLATILITY_SIZE_FACTOR;  // 50% size
            } else if (dailyVolatility > BASE_VOLATILITY * 1.5) {
                return 0.75;  // 75% size
            }
            return 1.0;  // Full size
        }
    }
    
    /**
     * Performance stats for a single symbol
     */
    private static class SymbolPerformance {
        int wins = 0;
        int losses = 0;
        double totalPnlPercent = 0.0;
        long lastTradeTime = 0;
        
        double getWinRate() {
            int total = wins + losses;
            return total > 0 ? (double) wins / total : 0.5;  // Default 50% if no data
        }
        
        double getAvgPnl() {
            int total = wins + losses;
            return total > 0 ? totalPnlPercent / total : 0.0;
        }
        
        int getTotalTrades() {
            return wins + losses;
        }
        
        void recordTrade(boolean isWin, double pnlPercent) {
            if (isWin) wins++; else losses++;
            totalPnlPercent += pnlPercent;
            lastTradeTime = System.currentTimeMillis();
        }
    }

    // Crypto symbols we support (Alpaca format)
    private static final List<String> CRYPTO_SYMBOLS = List.of(
        "BTC/USD", "ETH/USD", "SOL/USD", 
        "DOGE/USD", "XRP/USD", "ADA/USD", 
        "DOT/USD", "AVAX/USD"
    );

    public GridTradingService(AlpacaClient client, KrakenClient krakenClient) {
        this.alpacaClient = client;
        this.krakenClient = krakenClient;
        
        // Kraken enabled check
        this.krakenEnabled = krakenClient.isConfigured();
        
        if (krakenEnabled) {
            logger.info("🦑 Kraken trading ENABLED for crypto");
        } else {
            logger.warn("⚠️ Kraken NOT configured - crypto trading disabled");
        }
    }
    
    /**
     * Constructor with configurable grid size
     */
    public GridTradingService(AlpacaClient client, KrakenClient krakenClient, double gridSizeUsd) {
        this(client, krakenClient);
        this.configGridSize = gridSizeUsd;
        logger.info("⛏️ Grid size configured: ${} (will scale dynamically with balance)", gridSizeUsd);
    }
    
    /**
     * Calculate dynamic grid size based on available balance.
     * Uses 75% of available balance, clamped between MIN and MAX.
     * This allows the bot to automatically scale with profits.
     */
    private double calculateDynamicGridSize(double availableBalance) {
        // Use 75% of available balance
        double dynamicSize = availableBalance * GRID_BALANCE_RATIO;
        
        // Clamp between min and max
        dynamicSize = Math.max(MIN_GRID_SIZE, Math.min(dynamicSize, MAX_GRID_SIZE));
        
        return dynamicSize;
    }

    /**
     * Check if a symbol is crypto (should be routed to Kraken)
     */
    private boolean isCrypto(String symbol) {
        return CRYPTO_SYMBOLS.contains(symbol);
    }

    public CompletableFuture<Void> runGridCycle(List<String> watchlist) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // Skip crypto if Kraken not configured
                if (!krakenEnabled) {
                    logger.debug("⛏️ Skipping crypto - Kraken not configured");
                    return null;
                }
                
                // ===== STEP 1: Cleanup stale orders periodically (every 2 minutes) =====
                long now = System.currentTimeMillis();
                if (now - lastOrderCleanup > 2 * 60 * 1000) {
                    cleanupStaleOrders();
                    lastOrderCleanup = now;
                }
                
                // ===== STEP 2: Check current available balance & calculate dynamic grid size =====
                double availableBalance = krakenClient.getAvailableBalanceAsync().join();
                double dynamicGridSize = calculateDynamicGridSize(availableBalance);
                
                // Must have at least minimum grid size available
                if (availableBalance < MIN_GRID_SIZE) {
                    logger.info("⛏️ Insufficient funds: ${} < ${} minimum", 
                        String.format("%.2f", availableBalance), String.format("%.2f", MIN_GRID_SIZE));
                    return null;
                }
                
                // ===== STEP 3: Count current open orders =====
                int openOrderCount = getKrakenOpenOrderCount();
                if (openOrderCount >= MAX_CONCURRENT_GRID_ORDERS) {
                    logger.info("⛏️ Max grid orders reached ({}/{}), waiting for fills...", 
                        openOrderCount, MAX_CONCURRENT_GRID_ORDERS);
                    return null;
                }
                
                logger.info("⛏️ Grid Cycle: ${} available, grid=${}, {}/{} orders", 
                    String.format("%.2f", availableBalance), String.format("%.2f", dynamicGridSize),
                    openOrderCount, MAX_CONCURRENT_GRID_ORDERS);

                // ===== STEP 4: Find best entry opportunity =====
                String bestSymbol = null;
                double bestScore = 0;
                double bestPrice = 0;
                
                for (String symbol : watchlist) {
                    if (!isCrypto(symbol)) continue;
                    
                    try {
                        // Skip if already have pending order for this symbol
                        if (pendingOrders.containsKey(symbol)) {
                            logger.debug("⛏️ Skipping {} - pending order exists", symbol);
                            continue;
                        }
                        
                        // Skip assets with high minimums that don't fit grid size
                        // Kraken minimum order sizes at current approximate prices:
                        // - BTC: 0.0001 BTC (~$10)
                        // - ETH: 0.01 ETH (~$35)
                        // - XRP: 10 XRP (~$21)
                        // - DOGE: 500 DOGE (~$68)
                        // - SOL: 0.5 SOL (~$71)
                        if (symbol.contains("SOL") && dynamicGridSize < 75) {
                            logger.debug("⛏️ Skipping {} - grid size ${} below $75 minimum", symbol, dynamicGridSize);
                            continue;
                        }
                        if (symbol.contains("DOGE") && dynamicGridSize < 70) {
                            logger.debug("⛏️ Skipping {} - grid size ${} below $70 minimum", symbol, dynamicGridSize);
                            continue;
                        }
                        if (symbol.contains("ETH") && dynamicGridSize < 38) {
                            logger.debug("⛏️ Skipping {} - grid size ${} below $38 minimum", symbol, dynamicGridSize);
                            continue;
                        }
                        if (symbol.contains("XRP") && dynamicGridSize < 22) {
                            logger.debug("⛏️ Skipping {} - grid size ${} below $22 minimum", symbol, dynamicGridSize);
                            continue;
                        }
                        // BTC has lowest minimum (~$10), always try if grid size >= $11
                        if (symbol.contains("BTC") && dynamicGridSize < 11) {
                            logger.debug("⛏️ Skipping {} - grid size ${} below $11 minimum", symbol, dynamicGridSize);
                            continue;
                        }
                        
                        String krakenSymbol = KrakenClient.toKrakenSymbol(symbol);
                        String tickerJson = krakenClient.getTickerAsync(krakenSymbol).join();
                        JsonNode tickerData = mapper.readTree(tickerJson);
                        
                        if (tickerData.has("error") && tickerData.get("error").size() > 0) {
                            continue;
                        }
                        
                        JsonNode result = tickerData.get("result");
                        if (result == null || !result.fields().hasNext()) continue;
                        
                        JsonNode pairData = result.fields().next().getValue();
                        if (pairData == null || !pairData.has("c")) continue;
                        
                        double currentPrice = Double.parseDouble(pairData.get("c").get(0).asText());
                        double openPrice = pairData.get("o").asDouble();
                        double lowPrice24h = pairData.get("l").get(1).asDouble();
                        double highPrice24h = pairData.get("h").get(1).asDouble();
                        
                        // ===== RSI MOMENTUM FILTER =====
                        RsiTracker rsi = rsiTrackers.computeIfAbsent(symbol, k -> new RsiTracker());
                        rsi.addPrice(currentPrice);
                        
                        // Track price changes for cross-asset arbitrage
                        double priceChange = (currentPrice - openPrice) / openPrice;
                        lastPriceChanges.put(symbol, priceChange);
                        
                        // Skip if overbought (RSI > 70)
                        if (rsi.hasEnoughData() && rsi.isOverbought()) {
                            logger.debug("⛏️ Skipping {} - RSI {:.1f} OVERBOUGHT", symbol, rsi.getRsi());
                            continue;
                        }
                        
                        // ===== PHASE 3: Update volatility tracking =====
                        updateVolatility(symbol, highPrice24h, lowPrice24h, currentPrice);
                        
                        // Score: Lower in range = better buy opportunity
                        double rangePosition = (highPrice24h > lowPrice24h)
                            ? (currentPrice - lowPrice24h) / (highPrice24h - lowPrice24h)
                            : 0.5;
                        
                        double dayChange = (currentPrice - openPrice) / openPrice;
                        
                        // Score calculation:
                        // - Lower in range = higher score (up to 50 points)
                        // - Small dip today = bonus (up to 20 points)
                        // - RSI oversold = extra bonus
                        double score = (1 - rangePosition) * 50;
                        if (dayChange < 0 && dayChange > -0.03) {
                            score += Math.abs(dayChange) * 500; // Small dips are buying opportunities
                        }
                        
                        // ===== RSI OVERSOLD BONUS =====
                        if (rsi.hasEnoughData() && rsi.isOversold()) {
                            score *= 1.5;  // 50% bonus for oversold conditions
                            logger.info("📊 {} RSI {:.1f} OVERSOLD - boosting score!", symbol, rsi.getRsi());
                        }
                        
                        // ===== PHASE 2: Performance Weighting =====
                        // Boost score for historically profitable symbols
                        score = applyPerformanceWeighting(symbol, score);
                        
                        if (score > bestScore) {
                            bestScore = score;
                            bestSymbol = symbol;
                            bestPrice = currentPrice;
                        }
                        
                    } catch (Exception e) {
                        logger.debug("⛏️ Price check failed for {}: {}", symbol, e.getMessage());
                    }
                }
                
                // ===== STEP 5: Place order on best opportunity =====
                // Lowered threshold from 25 to 5 for limited balance situations
                // When only BTC is tradeable, be more flexible with entry timing
                if (bestSymbol != null && bestScore > 5) {
                    // ===== PHASE 3: Apply volatility-adjusted sizing =====
                    double adjustedGridSize = applyVolatilitySizing(bestSymbol, dynamicGridSize);
                    
                    // ===== PHASE 3: Check portfolio concentration =====
                    if (!checkPortfolioConcentration(bestSymbol, adjustedGridSize, availableBalance)) {
                        logger.info("⛏️ Skipping {} - would exceed portfolio concentration limits", bestSymbol);
                    } else {
                        placeGridOrder(bestSymbol, bestPrice, adjustedGridSize);
                    }
                } else {
                    logger.debug("⛏️ No good entry opportunities (best score: {})", String.format("%.1f", bestScore));
                }
                
            } catch (Exception e) {
                logger.error("Grid Cycle Error: {}", e.getMessage());
            }
            return null;
        });
    }
    
    /**
     * Place MULTI-LEVEL grid fishing orders at -0.3%, -0.5%, -1.0% levels
     */
    private void placeGridOrder(String symbol, double currentPrice, double gridSize) {
        try {
            String krakenSymbol = KrakenClient.toKrakenSymbol(symbol);
            
            // Check RSI for oversold bonus (double down on oversold)
            RsiTracker rsi = rsiTrackers.get(symbol);
            boolean isOversold = rsi != null && rsi.hasEnoughData() && rsi.isOversold();
            
            int ordersPlaced = 0;
            int currentOpenOrders = getKrakenOpenOrderCount();
            int availableSlots = MAX_CONCURRENT_GRID_ORDERS - currentOpenOrders;
            
            for (int level = 0; level < GRID_BUY_LEVELS.length && ordersPlaced < availableSlots; level++) {
                double levelOffset = GRID_BUY_LEVELS[level];
                double levelWeight = GRID_LEVEL_WEIGHTS[level];
                
                // If oversold, put more weight on deeper levels (better entries)
                if (isOversold && level == 2) {
                    levelWeight *= 1.5;  // 50% more on -1% level when oversold
                }
                
                double levelSize = gridSize * levelWeight;
                if (levelSize < MIN_GRID_SIZE) continue;  // Skip if too small
                
                double buyPrice = currentPrice * levelOffset;
                double qty = levelSize / buyPrice;
                
                // Round price based on asset (Kraken requirements)
                String formattedPrice;
                if (symbol.contains("BTC")) {
                    formattedPrice = String.format("%.1f", buyPrice);
                } else {
                    formattedPrice = String.format("%.2f", buyPrice);
                }
                
                // Format volume to 8 decimals to avoid scientific notation
                String formattedVolume = String.format("%.8f", qty);
                
                double offsetPercent = (1 - levelOffset) * 100;
                logger.info("🦑 Fishing L{} {}: ${} @ -{:.1f}% = ${} (qty: {})", 
                    level + 1, symbol, String.format("%.2f", currentPrice), 
                    offsetPercent, formattedPrice, formattedVolume);
                
                // PRE-FLIGHT VALIDATION
                boolean canPlace = krakenClient.canPlaceOrder(krakenSymbol, qty, buyPrice).join();
                if (!canPlace) {
                    logger.warn("⚠️ Skipping {} L{} order - validation failed", symbol, level + 1);
                    continue;
                }
                
                // Place order
                String result = krakenClient.placeLimitOrderAsync(krakenSymbol, "buy", formattedVolume, formattedPrice).join();
                
                if (result.contains("ERROR")) {
                    logger.warn("🦑 Order failed for {} L{}: {}", symbol, level + 1, result);
                } else {
                    logger.info("🦑 Order placed for {} L{}: {}", symbol, level + 1, result);
                    pendingOrders.put(symbol + "_L" + level, System.currentTimeMillis());
                    ordersPlaced++;
                }
            }
            
            if (ordersPlaced > 0) {
                logger.info("🦑 Placed {} multi-level orders for {}", ordersPlaced, symbol);
            }
            
        } catch (Exception e) {
            logger.error("🦑 Failed to place orders for {}: {}", symbol, e.getMessage());
        }
    }
    
    /**
     * Get count of open Kraken orders
     */
    private int getKrakenOpenOrderCount() {
        try {
            String ordersJson = krakenClient.getOpenOrdersAsync().join();
            JsonNode orders = mapper.readTree(ordersJson);
            if (orders.has("result") && orders.get("result").has("open")) {
                return orders.get("result").get("open").size();
            }
        } catch (Exception e) {
            logger.debug("Failed to get open orders count: {}", e.getMessage());
        }
        return 0;
    }
    
    /**
     * Cleanup stale orders (older than threshold)
     */
    private void cleanupStaleOrders() {
        try {
            String ordersJson = krakenClient.getOpenOrdersAsync().join();
            JsonNode orders = mapper.readTree(ordersJson);
            
            if (!orders.has("result") || !orders.get("result").has("open")) {
                pendingOrders.clear();
                return;
            }
            
            JsonNode openOrders = orders.get("result").get("open");
            int staleCount = 0;
            
            var orderIds = openOrders.fieldNames();
            while (orderIds.hasNext()) {
                String txid = orderIds.next();
                JsonNode order = openOrders.get(txid);
                
                // Check order age
                double openTm = order.get("opentm").asDouble();
                long ageMs = (long) ((System.currentTimeMillis() / 1000.0 - openTm) * 1000);
                
                if (ageMs > STALE_ORDER_AGE_MS) {
                    logger.info("🗑️ Canceling stale order {} (age: {}ms)", txid, ageMs);
                    krakenClient.cancelOrderAsync(txid).join();
                    staleCount++;
                }
            }
            
            if (staleCount > 0) {
                logger.info("🗑️ Canceled {} stale orders", staleCount);
            }
            
            // Clear pending orders tracking
            pendingOrders.clear();
            
        } catch (Exception e) {
            logger.error("Failed to cleanup stale orders: {}", e.getMessage());
        }
    }
    
    // ===== PHASE 2: Performance Tracking Methods =====
    
    /**
     * Apply performance weighting to a symbol's score.
     * Symbols with better historical performance get a score boost.
     */
    private double applyPerformanceWeighting(String symbol, double baseScore) {
        SymbolPerformance perf = symbolPerformance.get(symbol);
        
        // No history = no adjustment
        if (perf == null || perf.getTotalTrades() < MIN_TRADES_FOR_WEIGHTING) {
            return baseScore;
        }
        
        // Calculate performance modifier:
        // Win rate 50% = 0% adjustment
        // Win rate 70% = +6% boost (0.7 - 0.5) * 0.3 = 0.06
        // Win rate 30% = -6% penalty
        double winRateModifier = (perf.getWinRate() - 0.5) * PERFORMANCE_WEIGHT_FACTOR;
        
        // Also consider average P&L (capped at ±10%)
        double pnlModifier = Math.max(-0.1, Math.min(0.1, perf.getAvgPnl() / 100));
        
        double totalModifier = 1.0 + winRateModifier + pnlModifier;
        double adjustedScore = baseScore * totalModifier;
        
        logger.debug("📊 {} Performance: {}W/{}L ({}%), AvgPnL {:.1f}%, Score {:.1f} → {:.1f}",
            symbol, perf.wins, perf.losses, 
            String.format("%.0f", perf.getWinRate() * 100),
            perf.getAvgPnl(),
            baseScore, adjustedScore);
        
        return adjustedScore;
    }
    
    /**
     * Record a completed trade for performance tracking.
     * Call this when a position is closed (either at profit or loss).
     * 
     * @param symbol The trading symbol (e.g., "BTC/USD")
     * @param pnlPercent The P&L percentage (-5% to +10% typical)
     */
    public void recordTradeResult(String symbol, double pnlPercent) {
        boolean isWin = pnlPercent > 0;
        
        symbolPerformance.computeIfAbsent(symbol, k -> new SymbolPerformance())
            .recordTrade(isWin, pnlPercent);
        
        SymbolPerformance perf = symbolPerformance.get(symbol);
        logger.info("📈 Trade Recorded: {} {} {:.2f}% | Total: {}W/{}L ({:.0f}% win rate)",
            symbol, isWin ? "WIN" : "LOSS", pnlPercent,
            perf.wins, perf.losses, perf.getWinRate() * 100);
    }
    
    /**
     * Log performance summary for all tracked symbols
     */
    public void logPerformanceSummary() {
        if (symbolPerformance.isEmpty()) {
            logger.info("📊 No performance data yet");
            return;
        }
        
        logger.info("📊 === Performance Summary ===");
        symbolPerformance.forEach((symbol, perf) -> {
            if (perf.getTotalTrades() > 0) {
                logger.info("📊 {}: {}W/{}L ({:.0f}%), AvgPnL: {:.2f}%",
                    symbol, perf.wins, perf.losses,
                    perf.getWinRate() * 100, perf.getAvgPnl());
            }
        });
    }
    
    /**
     * Get performance stats for a symbol (for external callers)
     */
    public String getSymbolStats(String symbol) {
        SymbolPerformance perf = symbolPerformance.get(symbol);
        if (perf == null || perf.getTotalTrades() == 0) {
            return symbol + ": No data";
        }
        return String.format("%s: %dW/%dL (%.0f%%), AvgPnL: %.2f%%",
            symbol, perf.wins, perf.losses, perf.getWinRate() * 100, perf.getAvgPnl());
    }
    
    // ===== PHASE 3: Portfolio Management Methods =====
    
    /**
     * Update volatility data for a symbol
     */
    private void updateVolatility(String symbol, double high24h, double low24h, double currentPrice) {
        symbolVolatility.computeIfAbsent(symbol, k -> new VolatilityData())
            .update(high24h, low24h, currentPrice);
    }
    
    /**
     * Apply volatility-based position sizing.
     * High volatility = smaller position to manage risk.
     */
    private double applyVolatilitySizing(String symbol, double baseGridSize) {
        VolatilityData vol = symbolVolatility.get(symbol);
        if (vol == null) {
            return baseGridSize;  // No data, use base size
        }
        
        double multiplier = vol.getSizeMultiplier();
        double adjustedSize = baseGridSize * multiplier;
        
        // Ensure we still meet minimum
        adjustedSize = Math.max(MIN_GRID_SIZE, adjustedSize);
        
        if (multiplier < 1.0) {
            logger.info("📉 {} high volatility ({:.1f}%), reducing size ${:.2f} → ${:.2f}",
                symbol, vol.dailyVolatility * 100, baseGridSize, adjustedSize);
        }
        
        return adjustedSize;
    }
    
    /**
     * Check if adding a position would exceed portfolio concentration limits.
     * Prevents over-concentration in single assets or correlated groups.
     */
    private boolean checkPortfolioConcentration(String symbol, double orderSize, double availableBalance) {
        // For small portfolios (<$500), be more lenient to allow diversification
        // Use trade balance equity for total portfolio value
        try {
            String tradeBalanceJson = krakenClient.getTradeBalanceAsync().join();
            JsonNode tradeBalance = mapper.readTree(tradeBalanceJson);
            
            if (!tradeBalance.has("result")) {
                return true;  // Can't check, allow trade
            }
            
            JsonNode result = tradeBalance.get("result");
            double totalEquity = result.has("eb") ? result.get("eb").asDouble() : 0.0;
            
            // For portfolios under $500, skip concentration checks
            // Need to build positions before we can diversify
            if (totalEquity < 500) {
                logger.debug("📊 Portfolio ${:.2f} < $500 - skipping concentration check", 
                    String.format("%.2f", totalEquity));
                return true;
            }
            
            // Get current positions to calculate actual USD values
            String balanceJson = krakenClient.getBalanceAsync().join();
            JsonNode balances = mapper.readTree(balanceJson);
            
            if (!balances.has("result")) {
                return true;
            }
            
            // Estimate position value as proportion of total equity
            // (totalEquity - cash) / number of positions = avg position size
            double cashBalance = availableBalance;
            double investedValue = totalEquity - cashBalance;
            
            // Count non-zero crypto positions
            JsonNode balResult = balances.get("result");
            int positionCount = 0;
            boolean hasTargetSymbol = false;
            String targetGroup = CORRELATION_GROUPS.getOrDefault(symbol, "UNKNOWN");
            int sameGroupCount = 0;
            
            var fields = balResult.fields();
            while (fields.hasNext()) {
                var entry = fields.next();
                String asset = entry.getKey();
                double balance = entry.getValue().asDouble();
                
                if (balance <= 0 || asset.equals("ZUSD") || asset.equals("USD")) continue;
                
                String normalizedSymbol = normalizeAssetToSymbol(asset);
                if (normalizedSymbol != null) {
                    positionCount++;
                    if (normalizedSymbol.equals(symbol)) {
                        hasTargetSymbol = true;
                    }
                    String assetGroup = CORRELATION_GROUPS.getOrDefault(normalizedSymbol, "UNKNOWN");
                    if (assetGroup.equals(targetGroup)) {
                        sameGroupCount++;
                    }
                }
            }
            
            // If we already have this symbol, estimate its current value
            double avgPositionValue = positionCount > 0 ? investedValue / positionCount : 0;
            double estimatedSymbolValue = hasTargetSymbol ? avgPositionValue : 0;
            double estimatedGroupValue = sameGroupCount * avgPositionValue;
            
            // Calculate new ratios
            double newSymbolValue = estimatedSymbolValue + orderSize;
            double newGroupValue = estimatedGroupValue + orderSize;
            double newTotalValue = totalEquity + orderSize;
            
            double symbolRatio = newSymbolValue / newTotalValue;
            double groupRatio = newGroupValue / newTotalValue;
            
            // Check single position limit (40%)
            if (symbolRatio > MAX_SINGLE_POSITION_RATIO) {
                logger.warn("⚠️ {} would be {}% of portfolio (max {}%)",
                    symbol, String.format("%.0f", symbolRatio * 100), 
                    String.format("%.0f", MAX_SINGLE_POSITION_RATIO * 100));
                return false;
            }
            
            // Check correlated group limit (60%)
            if (groupRatio > CORRELATED_GROUP_LIMIT) {
                logger.warn("⚠️ {} group would be {}% of portfolio (max {}%)",
                    targetGroup, String.format("%.0f", groupRatio * 100), 
                    String.format("%.0f", CORRELATED_GROUP_LIMIT * 100));
                return false;
            }
            
            logger.debug("📊 Concentration OK: {} at {}%, group at {}%",
                symbol, String.format("%.0f", symbolRatio * 100), String.format("%.0f", groupRatio * 100));
            return true;
            
        } catch (Exception e) {
            logger.debug("Portfolio concentration check failed: {}", e.getMessage());
            return true;  // Allow trade if check fails
        }
    }
    
    /**
     * Convert Kraken asset code to our symbol format
     */
    private String normalizeAssetToSymbol(String krakenAsset) {
        return switch (krakenAsset) {
            case "XXBT", "XBT" -> "BTC/USD";
            case "XETH", "ETH" -> "ETH/USD";
            case "SOL" -> "SOL/USD";
            case "DOGE", "XDG" -> "DOGE/USD";
            case "XXRP", "XRP" -> "XRP/USD";
            case "ADA" -> "ADA/USD";
            case "DOT" -> "DOT/USD";
            case "AVAX" -> "AVAX/USD";
            default -> null;
        };
    }
    
    /**
     * Get volatility summary for all tracked symbols
     */
    public void logVolatilitySummary() {
        if (symbolVolatility.isEmpty()) {
            logger.info("📊 No volatility data yet");
            return;
        }
        
        logger.info("📊 === Volatility Summary ===");
        symbolVolatility.forEach((symbol, vol) -> {
            String status = vol.isHighVolatility() ? "🔴 HIGH" : "🟢 NORMAL";
            logger.info("📊 {}: {:.1f}% daily vol {} (size mult: {:.0f}%)",
                symbol, vol.dailyVolatility * 100, status, vol.getSizeMultiplier() * 100);
        });
    }
    
    // ===== API Status Methods =====
    
    /**
     * Get current grid trading status for API/Dashboard
     */
    public Map<String, Object> getGridStatus() {
        Map<String, Object> status = new java.util.HashMap<>();
        
        // Basic config
        status.put("minGridSize", MIN_GRID_SIZE);
        status.put("maxGridSize", MAX_GRID_SIZE);
        status.put("balanceRatio", GRID_BALANCE_RATIO);
        status.put("maxConcurrentOrders", MAX_CONCURRENT_GRID_ORDERS);
        status.put("krakenEnabled", krakenEnabled);
        
        // Dynamic state - fetch live balance
        try {
            if (krakenEnabled) {
                double balance = krakenClient.getAvailableBalanceAsync().join();
                double dynamicGridSize = calculateDynamicGridSize(balance);
                int openOrders = getKrakenOpenOrderCount();
                
                status.put("availableBalance", balance);
                status.put("currentGridSize", dynamicGridSize);
                status.put("openOrders", openOrders);
                status.put("canPlaceOrder", openOrders < MAX_CONCURRENT_GRID_ORDERS && balance >= MIN_GRID_SIZE);
                
                // Tradeable symbols based on current grid size
                Map<String, Boolean> tradeableSymbols = new java.util.HashMap<>();
                tradeableSymbols.put("BTC/USD", dynamicGridSize >= 11);
                tradeableSymbols.put("XRP/USD", dynamicGridSize >= 22);
                tradeableSymbols.put("ETH/USD", dynamicGridSize >= 38);
                tradeableSymbols.put("DOGE/USD", dynamicGridSize >= 70);
                tradeableSymbols.put("SOL/USD", dynamicGridSize >= 75);
                status.put("tradeableSymbols", tradeableSymbols);
            }
        } catch (Exception e) {
            status.put("error", e.getMessage());
        }
        
        // Performance stats
        Map<String, Map<String, Object>> perfStats = new java.util.HashMap<>();
        symbolPerformance.forEach((symbol, perf) -> {
            Map<String, Object> symbolStats = new java.util.HashMap<>();
            symbolStats.put("wins", perf.wins);
            symbolStats.put("losses", perf.losses);
            symbolStats.put("winRate", perf.getWinRate());
            symbolStats.put("avgPnl", perf.getAvgPnl());
            symbolStats.put("totalTrades", perf.getTotalTrades());
            perfStats.put(symbol, symbolStats);
        });
        status.put("performanceStats", perfStats);
        
        // Volatility stats
        Map<String, Map<String, Object>> volStats = new java.util.HashMap<>();
        symbolVolatility.forEach((symbol, vol) -> {
            Map<String, Object> symbolVol = new java.util.HashMap<>();
            symbolVol.put("dailyVolatility", vol.dailyVolatility);
            symbolVol.put("isHighVolatility", vol.isHighVolatility());
            symbolVol.put("sizeMultiplier", vol.getSizeMultiplier());
            volStats.put(symbol, symbolVol);
        });
        status.put("volatilityStats", volStats);
        
        // ===== NEW: RSI Stats =====
        Map<String, Map<String, Object>> rsiStats = new java.util.HashMap<>();
        rsiTrackers.forEach((symbol, rsi) -> {
            Map<String, Object> symbolRsi = new java.util.HashMap<>();
            symbolRsi.put("rsi", rsi.getRsi());
            symbolRsi.put("isOverbought", rsi.isOverbought());
            symbolRsi.put("isOversold", rsi.isOversold());
            symbolRsi.put("hasEnoughData", rsi.hasEnoughData());
            rsiStats.put(symbol, symbolRsi);
        });
        status.put("rsiStats", rsiStats);
        
        // ===== NEW: Multi-Level Grid Config =====
        Map<String, Object> gridConfig = new java.util.HashMap<>();
        gridConfig.put("levels", new double[]{-0.3, -0.5, -1.0});
        gridConfig.put("weights", GRID_LEVEL_WEIGHTS);
        gridConfig.put("trailingTpActivation", TRAILING_TP_ACTIVATION * 100);
        gridConfig.put("trailingTpDistance", TRAILING_TP_DISTANCE * 100);
        gridConfig.put("maxTakeProfit", MAX_TAKE_PROFIT * 100);
        gridConfig.put("rsiOverbought", RSI_OVERBOUGHT);
        gridConfig.put("rsiOversold", RSI_OVERSOLD);
        status.put("gridConfig", gridConfig);
        
        // ===== NEW: Cross-Asset Arbitrage Status =====
        Map<String, Object> arbitrageStatus = new java.util.HashMap<>();
        Double btcChange = lastPriceChanges.get("BTC/USD");
        Double ethChange = lastPriceChanges.get("ETH/USD");
        if (btcChange != null && ethChange != null) {
            double divergence = Math.abs(btcChange - ethChange);
            arbitrageStatus.put("btcChange24h", btcChange * 100);
            arbitrageStatus.put("ethChange24h", ethChange * 100);
            arbitrageStatus.put("divergence", divergence * 100);
            arbitrageStatus.put("hasOpportunity", divergence > DIVERGENCE_THRESHOLD);
            arbitrageStatus.put("divergenceThreshold", DIVERGENCE_THRESHOLD * 100);
        }
        status.put("arbitrageStatus", arbitrageStatus);
        
        // ===== NEW: Trailing TP States =====
        Map<String, Map<String, Object>> trailingStates = new java.util.HashMap<>();
        trailingTpStates.forEach((symbol, state) -> {
            Map<String, Object> tpState = new java.util.HashMap<>();
            tpState.put("isActive", state.isActive);
            tpState.put("highWaterMark", state.highWaterMark);
            tpState.put("trailingStop", state.getTrailingStopPrice());
            tpState.put("activationPrice", state.activationPrice);
            trailingStates.put(symbol, tpState);
        });
        status.put("trailingTpStates", trailingStates);
        
        return status;
    }
    
    // ===== CROSS-ASSET ARBITRAGE METHODS =====
    
    /**
     * Check for BTC/ETH arbitrage opportunity.
     * When normally correlated assets diverge, trade the reversion.
     * @return Symbol to buy if opportunity exists, null otherwise
     */
    public String checkArbitrageOpportunity() {
        Double btcChange = lastPriceChanges.get("BTC/USD");
        Double ethChange = lastPriceChanges.get("ETH/USD");
        
        if (btcChange == null || ethChange == null) {
            return null;
        }
        
        double divergence = btcChange - ethChange;
        
        // If BTC up more than ETH, ETH is undervalued -> buy ETH
        // If ETH up more than BTC, BTC is undervalued -> buy BTC
        if (Math.abs(divergence) > DIVERGENCE_THRESHOLD) {
            if (divergence > 0) {
                logger.info("📈 Arbitrage: BTC +{:.2f}% vs ETH +{:.2f}% | ETH undervalued", 
                    btcChange * 100, ethChange * 100);
                return "ETH/USD";
            } else {
                logger.info("📈 Arbitrage: BTC +{:.2f}% vs ETH +{:.2f}% | BTC undervalued", 
                    btcChange * 100, ethChange * 100);
                return "BTC/USD";
            }
        }
        
        return null;
    }
    
    // ===== TRAILING TAKE-PROFIT METHODS =====
    
    /**
     * Initialize trailing TP state for a new position
     */
    public void initTrailingTp(String symbol, double entryPrice) {
        trailingTpStates.put(symbol, new TrailingTpState(entryPrice));
        logger.info("📊 Trailing TP initialized for {} @ ${}", symbol, entryPrice);
    }
    
    /**
     * Update trailing TP state with current price
     * @return true if should exit position
     */
    public boolean updateTrailingTp(String symbol, double currentPrice, double entryPrice) {
        TrailingTpState state = trailingTpStates.get(symbol);
        if (state == null) {
            initTrailingTp(symbol, entryPrice);
            state = trailingTpStates.get(symbol);
        }
        
        state.update(currentPrice);
        
        if (state.shouldExit(currentPrice, entryPrice)) {
            double pnl = (currentPrice - entryPrice) / entryPrice * 100;
            logger.info("📊 Trailing TP EXIT: {} @ ${} | PnL: +{:.2f}% | HWM: ${}", 
                symbol, currentPrice, pnl, state.highWaterMark);
            trailingTpStates.remove(symbol);
            return true;
        }
        
        if (state.isActive) {
            logger.debug("📊 Trailing TP active: {} | HWM: ${} | Trail Stop: ${}", 
                symbol, state.highWaterMark, state.getTrailingStopPrice());
        }
        
        return false;
    }
    
    /**
     * Remove trailing TP state when position closed
     */
    public void clearTrailingTp(String symbol) {
        trailingTpStates.remove(symbol);
    }
    
    /**
     * Get RSI for a symbol
     */
    public double getRsi(String symbol) {
        RsiTracker rsi = rsiTrackers.get(symbol);
        return rsi != null ? rsi.getRsi() : 50.0;
    }
    
    /**
     * Check if symbol is overbought
     */
    public boolean isOverbought(String symbol) {
        RsiTracker rsi = rsiTrackers.get(symbol);
        return rsi != null && rsi.hasEnoughData() && rsi.isOverbought();
    }
    
    /**
     * Check if symbol is oversold
     */
    public boolean isOversold(String symbol) {
        RsiTracker rsi = rsiTrackers.get(symbol);
        return rsi != null && rsi.hasEnoughData() && rsi.isOversold();
    }
    
    // ===== NEW PROFITABILITY IMPROVEMENTS =====
    
    /**
     * Update EMA for a symbol
     */
    public void updateEma(String symbol, double price) {
        emaTrackers.computeIfAbsent(symbol, k -> new EmaTracker()).addPrice(price);
    }
    
    /**
     * Check if EMA trend is bullish (EMA9 > EMA21)
     */
    public boolean isEmaBullish(String symbol) {
        EmaTracker ema = emaTrackers.get(symbol);
        return ema != null && ema.isBullish();
    }
    
    /**
     * Check if EMA trend is bearish (EMA9 < EMA21)
     */
    public boolean isEmaBearish(String symbol) {
        EmaTracker ema = emaTrackers.get(symbol);
        return ema != null && ema.isBearish();
    }
    
    /**
     * Check if EMA has enough data
     */
    public boolean hasEmaData(String symbol) {
        EmaTracker ema = emaTrackers.get(symbol);
        return ema != null && ema.hasEnoughData();
    }
    
    /**
     * Update volume for a symbol
     */
    public void updateVolume(String symbol, double volume) {
        volumeTrackers.computeIfAbsent(symbol, k -> new VolumeTracker()).addVolume(volume);
    }
    
    /**
     * Check if current volume is a spike (> 1.5x average)
     */
    public boolean isVolumeSpike(String symbol, double currentVolume) {
        VolumeTracker vol = volumeTrackers.get(symbol);
        return vol != null && vol.isVolumeSpike(currentVolume);
    }
    
    /**
     * Record P&L from a trade and update daily total
     */
    public void recordTradePnL(double pnl) {
        // Reset daily P&L at midnight UTC
        long now = System.currentTimeMillis();
        long dayStart = now - (now % (24 * 60 * 60 * 1000));
        if (dayStart > dailyPnLResetTime) {
            dailyPnL = 0;
            dailyPnLResetTime = dayStart;
            logger.info("📊 Daily P&L reset at midnight");
        }
        dailyPnL += pnl;
        logger.info("📊 Trade P&L: ${} | Daily P&L: ${}", 
            String.format("%.2f", pnl), String.format("%.2f", dailyPnL));
    }
    
    /**
     * Update account equity for daily loss calculation
     */
    public void updateAccountEquity(double equity) {
        this.accountEquity = equity;
    }
    
    /**
     * Check if daily loss limit exceeded (stop trading if down > 2%)
     */
    public boolean isDailyLossLimitExceeded() {
        if (accountEquity <= 0) return false;
        double lossPercent = (dailyPnL / accountEquity) * 100;
        return dailyPnL < 0 && Math.abs(lossPercent) >= MAX_DAILY_LOSS_PERCENT;
    }
    
    /**
     * Get daily P&L
     */
    public double getDailyPnL() {
        return dailyPnL;
    }
    
    /**
     * Check if two symbols are correlated (BTC/ETH)
     * Returns true if they should not be held together
     */
    public boolean isCorrelated(String symbol1, String symbol2) {
        // BTC and ETH are highly correlated (~0.9)
        boolean isBtc1 = symbol1.contains("BTC");
        boolean isEth1 = symbol1.contains("ETH");
        boolean isBtc2 = symbol2.contains("BTC");
        boolean isEth2 = symbol2.contains("ETH");
        
        return (isBtc1 && isEth2) || (isEth1 && isBtc2);
    }
    
    /**
     * Check if holding a new symbol would violate correlation rules
     */
    public boolean wouldViolateCorrelation(String newSymbol, java.util.Set<String> currentHoldings) {
        for (String held : currentHoldings) {
            if (isCorrelated(newSymbol, held)) {
                logger.info("📊 Correlation filter: {} blocked (already holding {})", newSymbol, held);
                return true;
            }
        }
        return false;
    }
    
    // ===== MACD INDICATOR METHODS =====
    
    /**
     * Update MACD for a symbol
     */
    public void updateMacd(String symbol, double price) {
        macdTrackers.computeIfAbsent(symbol, k -> new MacdTracker()).addPrice(price);
    }
    
    /**
     * Check if MACD is bullish (MACD > Signal)
     */
    public boolean isMacdBullish(String symbol) {
        MacdTracker macd = macdTrackers.get(symbol);
        return macd != null && macd.isBullish();
    }
    
    /**
     * Check if MACD is bearish (MACD < Signal)
     */
    public boolean isMacdBearish(String symbol) {
        MacdTracker macd = macdTrackers.get(symbol);
        return macd != null && macd.isBearish();
    }
    
    /**
     * Check if MACD has enough data
     */
    public boolean hasMacdData(String symbol) {
        MacdTracker macd = macdTrackers.get(symbol);
        return macd != null && macd.hasEnoughData();
    }
    
    /**
     * Get MACD histogram value (positive = bullish momentum)
     */
    public double getMacdHistogram(String symbol) {
        MacdTracker macd = macdTrackers.get(symbol);
        return macd != null ? macd.getHistogram() : 0;
    }
    
    // ===== ATR (VOLATILITY) METHODS =====
    
    /**
     * Update ATR for a symbol with OHLC data
     */
    public void updateAtr(String symbol, double high, double low, double close) {
        atrTrackers.computeIfAbsent(symbol, k -> new AtrTracker()).addCandle(high, low, close);
    }
    
    /**
     * Get ATR as percentage of current price
     */
    public double getAtrPercent(String symbol, double currentPrice) {
        AtrTracker atr = atrTrackers.get(symbol);
        return atr != null && atr.hasEnoughData() ? atr.getAtrPercent(currentPrice) : 2.0;
    }
    
    /**
     * Get recommended take-profit based on ATR (1.5x ATR)
     */
    public double getAtrBasedTpPercent(String symbol, double currentPrice) {
        AtrTracker atr = atrTrackers.get(symbol);
        return atr != null && atr.hasEnoughData() ? atr.getRecommendedTpPercent(currentPrice) : 1.2;
    }
    
    /**
     * Get recommended stop-loss based on ATR (1x ATR)
     */
    public double getAtrBasedSlPercent(String symbol, double currentPrice) {
        AtrTracker atr = atrTrackers.get(symbol);
        return atr != null && atr.hasEnoughData() ? atr.getRecommendedSlPercent(currentPrice) : 0.8;
    }
    
    /**
     * Check if ATR data is available
     */
    public boolean hasAtrData(String symbol) {
        AtrTracker atr = atrTrackers.get(symbol);
        return atr != null && atr.hasEnoughData();
    }
    
    // ===== MOMENTUM METHODS =====
    
    /**
     * Update momentum tracker for a symbol
     */
    public void updateMomentum(String symbol, double price) {
        momentumTrackers.computeIfAbsent(symbol, k -> new MomentumTracker()).addPrice(price);
    }
    
    /**
     * Check if short-term momentum is positive (price rising)
     */
    public boolean isPositiveMomentum(String symbol) {
        MomentumTracker mom = momentumTrackers.get(symbol);
        return mom != null && mom.hasEnoughData() && mom.isPositive();
    }
    
    /**
     * Check if momentum is strongly positive (>0.1% gain in period)
     */
    public boolean isStrongPositiveMomentum(String symbol) {
        MomentumTracker mom = momentumTrackers.get(symbol);
        return mom != null && mom.hasEnoughData() && mom.isStrongPositive();
    }
    
    /**
     * Check if momentum is negative (price falling)
     */
    public boolean isNegativeMomentum(String symbol) {
        MomentumTracker mom = momentumTrackers.get(symbol);
        return mom != null && mom.hasEnoughData() && mom.isNegative();
    }
    
    /**
     * Get current momentum percentage
     */
    public double getMomentum(String symbol) {
        MomentumTracker mom = momentumTrackers.get(symbol);
        return mom != null ? mom.getMomentum() : 0;
    }
    
    /**
     * Check if momentum data is available
     */
    public boolean hasMomentumData(String symbol) {
        MomentumTracker mom = momentumTrackers.get(symbol);
        return mom != null && mom.hasEnoughData();
    }
    
    // ===== TIME-OF-DAY FILTER =====
    
    /**
     * Check if current time is in low-liquidity hours (2-6 AM UTC)
     * Returns true if we should SKIP trading
     */
    public boolean isLowLiquidityHour() {
        int utcHour = java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC).getHour();
        // Low liquidity: 2-6 AM UTC (weekend-like quiet hours)
        return utcHour >= 2 && utcHour < 6;
    }
    
    /**
     * Check if current time is in peak trading hours (13-21 UTC = US market hours)
     * Returns true if we're in peak trading time
     */
    public boolean isPeakTradingHour() {
        int utcHour = java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC).getHour();
        // Peak: 13-21 UTC (9am-5pm Eastern US)
        return utcHour >= 13 && utcHour <= 21;
    }
}
