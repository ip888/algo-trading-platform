package com.trading.risk;

import com.trading.config.Config;
import com.trading.persistence.TradeDatabase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Advanced position sizing using Kelly Criterion, volatility adjustment,
 * and correlation-based limits for optimal capital allocation.
 * 
 * Key Features:
 * - Kelly Criterion for optimal bet sizing
 * - Volatility-based adjustments
 * - Win rate tracking per symbol
 * - Correlation-based position limits
 * - Maximum position size caps
 */
public class AdvancedPositionSizer {
    private static final Logger logger = LoggerFactory.getLogger(AdvancedPositionSizer.class);
    
    private final Config config;
    private final TradeDatabase database;
    private com.trading.autonomous.AdaptiveParameterManager adaptiveManager;
    
    // Track win rates per symbol for Kelly Criterion
    private final Map<String, SymbolStats> symbolStats = new HashMap<>();
    
    public AdvancedPositionSizer(Config config, TradeDatabase database) {
        this.config = config;
        this.database = database;
        
        logger.info("AdvancedPositionSizer initialized with Kelly Criterion");
    }
    
    /**
     * Set adaptive parameter manager for autonomous tuning.
     */
    public void setAdaptiveManager(com.trading.autonomous.AdaptiveParameterManager manager) {
        this.adaptiveManager = manager;
        logger.info("AdvancedPositionSizer now using adaptive parameter tuning");
    }
    
    /**
     * Calculate deployable capital (respecting reserve).
     * Feature #16: Smart Capital Reserve
     */
    private double calculateDeployableCapital(double totalEquity) {
        double reservePercent = config.getSmartCapitalReservePercent();
        double deployable = totalEquity * (1.0 - reservePercent);
        logger.debug("Capital: Total=${}, Reserve={}%, Deployable=${}", 
            String.format("%.2f", totalEquity), 
            String.format("%.0f", reservePercent * 100), 
            String.format("%.2f", deployable));
        return deployable;
    }
    
    /**
     * Calculate optimal position size using advanced methods.
     * Now respects capital reserve (Feature #16).
     */
    public double calculatePositionSize(String symbol, double equity, double currentPrice,
                                       double volatility, double stopLossPercent) {
        
        // Calculate deployable capital (respecting reserve)
        double deployableEquity = calculateDeployableCapital(equity);
        
        // Get or create symbol stats
        SymbolStats stats = symbolStats.computeIfAbsent(symbol, 
            k -> new SymbolStats(config.getPositionSizingDefaultWinRate()));
        
        // Update stats from database
        updateStatsFromDatabase(symbol, stats);
        
        // Calculate base size using selected method (with deployable capital)
        double baseSize = switch (config.getPositionSizingMethod()) {
            case "KELLY" -> calculateKellySize(deployableEquity, currentPrice, stats, stopLossPercent);
            case "VOLATILITY" -> calculateVolatilitySize(deployableEquity, currentPrice, volatility);
            case "FIXED" -> calculateFixedSize(deployableEquity, currentPrice);
            default -> calculateFixedSize(deployableEquity, currentPrice);
        };
        
        // Apply volatility adjustment
        double volatilityAdjusted = applyVolatilityAdjustment(baseSize, volatility);
        
        // Apply maximum position size cap (use adaptive if available, with deployable capital)
        double maxPercent = adaptiveManager != null ? 
            adaptiveManager.getAdaptiveMaxPositionPercent() : 
            config.getPositionSizingMaxPercent();
        double maxSize = (deployableEquity * maxPercent) / currentPrice;
        double cappedSize = Math.min(volatilityAdjusted, maxSize);
        
        // Apply minimum position size (with deployable capital)
        double minSize = (deployableEquity * config.getPositionSizingMinPercent()) / currentPrice;
        double finalSize = Math.max(cappedSize, minSize);
        
        logger.debug("{}: Position sizing - Base: {}, Vol-Adj: {}, Max%: {:.0f}%, Final: {} shares", 
            symbol, String.format("%.2f", baseSize), 
            String.format("%.2f", volatilityAdjusted),
            maxPercent * 100,
            String.format("%.2f", finalSize));
        
        return finalSize;
    }
    
    /**
     * Calculate position size using Kelly Criterion
     * Kelly % = (Win Rate * Avg Win - Loss Rate * Avg Loss) / Avg Win
     */
    private double calculateKellySize(double equity, double currentPrice, 
                                     SymbolStats stats, double stopLossPercent) {
        
        double winRate = stats.winRate;
        double lossRate = 1.0 - winRate;
        
        // Estimate average win/loss based on stop loss and typical R:R
        double avgLoss = stopLossPercent;
        double avgWin = stopLossPercent * config.getPositionSizingKellyRiskReward();
        
        // Kelly formula
        double kellyPercent = (winRate * avgWin - lossRate * avgLoss) / avgWin;
        
        // Apply Kelly fraction (use adaptive if available, otherwise config)
        double kellyFraction = adaptiveManager != null ? 
            adaptiveManager.getAdaptiveKellyFraction() : 
            config.getPositionSizingKellyFraction();
        
        kellyPercent *= kellyFraction;
        
        // Ensure Kelly is positive and reasonable
        kellyPercent = Math.max(0.01, Math.min(kellyPercent, 0.25));
        
        // Calculate position size
        double positionValue = equity * kellyPercent;
        double shares = positionValue / currentPrice;
        
        logger.debug("Kelly sizing: WinRate={:.1f}%, Kelly%={:.1f}%, Fraction={:.2f}, Shares={:.2f}", 
            winRate * 100, kellyPercent * 100, kellyFraction, shares);
        
        return shares;
    }
    
    /**
     * Calculate position size based on volatility
     * Higher volatility = smaller position
     */
    private double calculateVolatilitySize(double equity, double currentPrice, double volatility) {
        // Target risk per trade
        double targetRisk = equity * config.getPositionSizingVolatilityRiskPercent();
        
        // Adjust for volatility (higher vol = smaller position)
        double volatilityMultiplier = 1.0 / (1.0 + volatility * 10);
        
        double positionValue = targetRisk * volatilityMultiplier;
        return positionValue / currentPrice;
    }
    
    /**
     * Calculate fixed percentage position size
     */
    private double calculateFixedSize(double equity, double currentPrice) {
        double positionValue = equity * config.getPositionSizingFixedPercent();
        return positionValue / currentPrice;
    }
    
    /**
     * Apply volatility adjustment to position size
     */
    private double applyVolatilityAdjustment(double baseSize, double volatility) {
        if (!config.isPositionSizingVolatilityAdjustEnabled()) {
            return baseSize;
        }
        
        // Reduce size for high volatility
        // volatility of 0.02 (2%) = 0.8x multiplier
        // volatility of 0.05 (5%) = 0.5x multiplier
        double multiplier = 1.0 / (1.0 + volatility * config.getPositionSizingVolatilityMultiplier());
        
        return baseSize * multiplier;
    }
    
    /**
     * Update symbol statistics from database
     */
    private void updateStatsFromDatabase(String symbol, SymbolStats stats) {
        try {
            var dbStats = database.getSymbolStatistics(symbol);
            if (dbStats != null && dbStats.totalTrades() > 0) {
                stats.winRate = dbStats.winRate();
                stats.totalTrades = dbStats.totalTrades();
                stats.avgWin = dbStats.avgWin();
                stats.avgLoss = dbStats.avgLoss();
                
                logger.debug("{}: Updated stats from DB - WinRate: {:.1f}%, Trades: {}", 
                    symbol, stats.winRate * 100, stats.totalTrades);
            }
        } catch (Exception e) {
            logger.debug("Could not load stats for {}: {}", symbol, e.getMessage());
        }
    }
    
    /**
     * Record trade result to update win rate
     */
    public void recordTradeResult(String symbol, boolean isWin, double pnl) {
        SymbolStats stats = symbolStats.computeIfAbsent(symbol, 
            k -> new SymbolStats(config.getPositionSizingDefaultWinRate()));
        
        stats.totalTrades++;
        
        if (isWin) {
            stats.wins++;
            stats.totalWinAmount += pnl;
        } else {
            stats.totalLossAmount += Math.abs(pnl);
        }
        
        // Update win rate
        stats.winRate = (double) stats.wins / stats.totalTrades;
        
        // Update average win/loss
        if (stats.wins > 0) {
            stats.avgWin = stats.totalWinAmount / stats.wins;
        }
        int losses = stats.totalTrades - stats.wins;
        if (losses > 0) {
            stats.avgLoss = stats.totalLossAmount / losses;
        }
        
        logger.info("{}: Trade recorded - WinRate: {:.1f}% ({}/{}), Avg Win: ${:.2f}, Avg Loss: ${:.2f}", 
            symbol, stats.winRate * 100, stats.wins, stats.totalTrades,
            stats.avgWin, stats.avgLoss);
    }
    
    /**
     * Get current win rate for a symbol
     */
    public double getWinRate(String symbol) {
        SymbolStats stats = symbolStats.get(symbol);
        return stats != null ? stats.winRate : config.getPositionSizingDefaultWinRate();
    }
    
    /**
     * Check if position size should be reduced due to correlation
     */
    public boolean shouldReduceForCorrelation(String symbol, Map<String, Double> currentPositions) {
        if (!config.isPositionSizingCorrelationLimitEnabled()) {
            return false;
        }
        
        // Count positions in same sector/category
        // For now, simple implementation - could be enhanced with actual correlation data
        int correlatedPositions = 0;
        
        // Check if we have too many positions (simple correlation proxy)
        if (currentPositions.size() >= config.getPositionSizingMaxCorrelatedPositions()) {
            logger.warn("{}: Max correlated positions reached ({}), reducing size", 
                symbol, currentPositions.size());
            return true;
        }
        
        return false;
    }
    
    /**
     * Statistics for a symbol
     */
    private static class SymbolStats {
        double winRate;
        int totalTrades;
        int wins;
        double avgWin;
        double avgLoss;
        double totalWinAmount;
        double totalLossAmount;
        
        SymbolStats(double defaultWinRate) {
            this.winRate = defaultWinRate;
            this.totalTrades = 0;
            this.wins = 0;
            this.avgWin = 0.0;
            this.avgLoss = 0.0;
            this.totalWinAmount = 0.0;
            this.totalLossAmount = 0.0;
        }
    }
}
