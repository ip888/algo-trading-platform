package com.trading.analysis;

import com.trading.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Set;

/**
 * Feature #22: Correlation-Based Exits
 * Exit correlated positions when one hits stop loss.
 */
public class CorrelationExitManager {
    private static final Logger logger = LoggerFactory.getLogger(CorrelationExitManager.class);
    
    private final Config config;
    
    // Define correlated symbol groups
    private static final Set<String> TECH_STOCKS = Set.of(
        "AAPL", "MSFT", "GOOGL", "AMZN", "META", "NVDA", "TSLA", "QQQ", "XLK"
    );
    
    private static final Set<String> FINANCIAL_STOCKS = Set.of(
        "JPM", "BAC", "WFC", "GS", "MS", "XLF"
    );
    
    private static final Set<String> ENERGY_STOCKS = Set.of(
        "XOM", "CVX", "COP", "SLB", "XLE"
    );
    
    private static final Set<String> UTILITY_STOCKS = Set.of(
        "NEE", "DUK", "SO", "D", "XLU"
    );
    
    public CorrelationExitManager(Config config) {
        this.config = config;
    }
    
    /**
     * Check if two symbols are correlated.
     */
    public boolean areCorrelated(String symbol1, String symbol2) {
        if (symbol1.equals(symbol2)) {
            return false;
        }
        
        // Check if both are in the same sector
        return (TECH_STOCKS.contains(symbol1) && TECH_STOCKS.contains(symbol2)) ||
               (FINANCIAL_STOCKS.contains(symbol1) && FINANCIAL_STOCKS.contains(symbol2)) ||
               (ENERGY_STOCKS.contains(symbol1) && ENERGY_STOCKS.contains(symbol2)) ||
               (UTILITY_STOCKS.contains(symbol1) && UTILITY_STOCKS.contains(symbol2));
    }
    
    /**
     * Get correlated symbols for a given symbol.
     */
    public Set<String> getCorrelatedSymbols(String symbol, Map<String, Double> activePositions) {
        if (!config.isCorrelationExitEnabled()) {
            return Set.of();
        }
        
        return activePositions.keySet().stream()
            .filter(s -> areCorrelated(symbol, s))
            .collect(java.util.stream.Collectors.toSet());
    }
    
    /**
     * Check if we should exit correlated positions.
     * 
     * @param stoppedSymbol Symbol that hit stop loss
     * @param activePositions Current active positions
     * @return Set of symbols to exit
     */
    public Set<String> getSymbolsToExit(String stoppedSymbol, Map<String, Double> activePositions) {
        if (!config.isCorrelationExitEnabled()) {
            return Set.of();
        }
        
        var correlated = getCorrelatedSymbols(stoppedSymbol, activePositions);
        
        if (!correlated.isEmpty()) {
            logger.warn("Correlation exit triggered: {} stopped, exiting {} correlated positions: {}",
                stoppedSymbol, correlated.size(), correlated);
        }
        
        return correlated;
    }
    
    /**
     * Get correlation threshold from config.
     */
    public double getCorrelationThreshold() {
        return config.getCorrelationExitThreshold();
    }
}
