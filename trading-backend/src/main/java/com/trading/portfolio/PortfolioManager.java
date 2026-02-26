package com.trading.portfolio;

import com.trading.risk.TradePosition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages a portfolio of multiple symbols with capital allocation.
 * Modernized with Java 23 ConcurrentHashMap for thread-safe operations without locks.
 * 
 * Thread-Safety: Uses ConcurrentHashMap which provides lock-free reads and fine-grained locking for writes.
 * This is significantly faster than synchronized HashMap or Collections.synchronizedMap().
 */
public final class PortfolioManager {
    private static final Logger logger = LoggerFactory.getLogger(PortfolioManager.class);
    
    private final List<String> symbols;
    private final ConcurrentHashMap<String, TradePosition> positions;  // null = no position
    private final double capitalPerSymbol;
    
    public PortfolioManager(List<String> symbols, double totalCapital) {
        this.symbols = List.copyOf(symbols);
        this.positions = new ConcurrentHashMap<>();
        this.capitalPerSymbol = totalCapital / symbols.size();
        
        logger.info("Portfolio initialized with {} symbols, ${} per symbol", 
            symbols.size(), String.format("%.2f", capitalPerSymbol));
    }
    
    public List<String> getSymbols() {
        return symbols;
    }
    
    public double getCapitalPerSymbol() {
        return capitalPerSymbol;
    }
    
    /**
     * Get position for a symbol.
     * Returns Optional for null-safety, but internally uses null for "no position".
     */
    public Optional<TradePosition> getPosition(String symbol) {
        return Optional.ofNullable(positions.get(symbol));
    }
    
    /**
     * Set position for a symbol.
     * Pass Optional.empty() to remove position.
     */
    public void setPosition(String symbol, Optional<TradePosition> position) {
        if (position.isPresent()) {
            positions.put(symbol, position.get());
        } else {
            positions.remove(symbol);
        }
    }
    
    /**
     * Get count of active positions.
     * ConcurrentHashMap.size() is thread-safe and lock-free.
     */
    public int getActivePositionCount() {
        return positions.size();
    }
    
    /**
     * Get total P&L across all active positions.
     * Thread-safe calculation using stream operations.
     */
    public double getTotalPnL() {
        // Note: This returns 0 for now since we don't have current prices
        // The actual P&L is tracked in the database
        return 0.0;
    }
    
    /**
     * Get all positions as a map.
     * Returns a snapshot - modifications won't affect the original.
     */
    public Map<String, Optional<TradePosition>> getAllPositions() {
        Map<String, Optional<TradePosition>> result = new HashMap<>();
        for (String symbol : symbols) {
            result.put(symbol, Optional.ofNullable(positions.get(symbol)));
        }
        return result;
    }
    
    /**
     * Get symbols that currently have active positions.
     */
    public Set<String> getActiveStoredSymbols() {
        return new HashSet<>(positions.keySet());
    }
    
    /**
     * Calculate win rate from closed positions.
     * Note: This is a simplified calculation - production systems would track this in database.
     */
    public double getWinRate() {
        // Placeholder - would need historical data
        return 0.0;
    }
    
    
    /**
     * Sync portfolio with ALL Alpaca positions on startup.
     * Tracks every existing position regardless of current target symbol list,
     * preventing "untracked" positions from being sold on deploy.
     *
     * @param client AlpacaClient instance
     * @param defaultTpPercent Default Take Profit percent (e.g., 4.0) if not known
     * @param defaultSlPercent Default Stop Loss percent (e.g., 2.0) if not known
     */
    public void syncWithAlpaca(com.trading.api.AlpacaClient client, double defaultTpPercent, double defaultSlPercent) {
        try {
            logger.info("Syncing portfolio with ALL Alpaca positions (Default TP: {}%, SL: {}%)", defaultTpPercent, defaultSlPercent);
            var accountPositions = client.getPositions();

            int syncedCount = 0;
            for (var pos : accountPositions) {
                String symbol = pos.symbol();
                double entryPrice = pos.avgEntryPrice();
                double qty = pos.quantity();

                if (entryPrice <= 0 || qty <= 0) {
                    logger.debug("Skipping invalid position: {} (entry={}, qty={})", symbol, entryPrice, qty);
                    continue;
                }

                // Use configured percentages for initial targets
                double estimatedTP = entryPrice * (1.0 + (defaultTpPercent / 100.0));
                double estimatedSL = entryPrice * (1.0 - (defaultSlPercent / 100.0));

                // Use a past entry time so hold-time restrictions don't block sells
                // Existing positions have already been held; use 24h ago as safe default
                var estimatedEntryTime = java.time.Instant.now().minus(java.time.Duration.ofHours(24));

                var tradePos = new TradePosition(
                    symbol,
                    entryPrice,
                    qty,
                    estimatedSL,
                    estimatedTP,
                    estimatedEntryTime
                );

                positions.put(symbol, tradePos);
                syncedCount++;

                boolean inTargetList = symbols.contains(symbol);
                logger.info("Synced position: {} - {} shares @ ${} (TP: ${}, SL: ${}){}",
                    symbol, qty, String.format("%.2f", entryPrice),
                    String.format("%.2f", estimatedTP), String.format("%.2f", estimatedSL),
                    inTargetList ? "" : " [NOT in current target symbols]");
            }

            logger.info("Portfolio sync complete: {} positions loaded from Alpaca", syncedCount);

        } catch (Exception e) {
            logger.error("Failed to sync portfolio with Alpaca", e);
        }
    }

}
