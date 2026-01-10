package com.trading.filters;

import com.trading.api.AlpacaClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Filter to halt trading during extreme market volatility using VIX index.
 */
public final class VolatilityFilter {
    private static final Logger logger = LoggerFactory.getLogger(VolatilityFilter.class);
    private static final double VIX_THRESHOLD = 30.0;  // VIX > 30 = extreme fear
    private final AlpacaClient client;

    public VolatilityFilter(AlpacaClient client) {
        this.client = client;
    }

    /**
     * Check if volatility is acceptable for trading.
     * Returns false if VIX is too high (market panic).
     */
    public enum VolatilityState {
        NORMAL,
        HIGH,
        EXTREME
    }

    /**
     * Check current volatility state.
     */
    public VolatilityState getVolatilityState() {
        double vix = getCurrentVIX();
        
        if (vix > VIX_THRESHOLD) {
            logger.warn("VIX EXTREME: {} (threshold: {}). Switching to Volatility Mode.", 
                String.format("%.2f", vix), VIX_THRESHOLD);
            return VolatilityState.EXTREME;
        } else if (vix > 20.0) {
            return VolatilityState.HIGH;
        }
        
        return VolatilityState.NORMAL;
    }

    /**
     * Deprecated: Use getVolatilityState() instead.
     * Kept for backward compatibility until refactor is complete.
     */
    public boolean isVolatilityAcceptable() {
        // Always return true now, as we handle volatility in StrategyManager
        return true; 
    }

    /**
     * Get current VIX value.
     */
    /**
     * Get current VIX value with fallback logic.
     */
    public double getCurrentVIX() {
        // 1. Try direct VIX index
        try {
            var bar = client.getLatestBar("VIX");
            return bar.get().close();
        } catch (Exception e) {
            logger.debug("VIX index not available, trying proxy...");
        }

        // 2. Try VIXY ETF as proxy
    try {
        var bar = client.getLatestBar("VIXY");
        double vixyPrice = bar.get().close();
        
        // CRITICAL FIX: VIXY price is NOT the same as VIX level!
        // VIXY is a VIX futures ETF with price in dollars, VIX is a volatility index
        // Approximate conversion: VIX ≈ (VIXY_price / 2) + 2
        // When VIXY = $30, VIX is typically around 15-17
        double estimatedVIX = (vixyPrice / 2.0) + 2.0;
        
        logger.info("Using VIXY ETF as volatility proxy: VIXY=${} -> estimated VIX={}", 
            String.format("%.2f", vixyPrice), String.format("%.2f", estimatedVIX));
        return estimatedVIX;
    } catch (Exception e) {
        logger.debug("VIXY proxy not available, trying calculation...");
    }    

        // 3. Fallback: Calculate realized volatility from SPY
        try {
            var bars = client.getMarketHistory("SPY", 20); // Last 20 days
            if (bars.size() >= 2) {
                java.util.List<Double> returns = new java.util.ArrayList<>();
                for (int i = 1; i < bars.size(); i++) {
                    double close = bars.get(i).close();
                    double prevClose = bars.get(i-1).close();
                    returns.add(Math.log(close / prevClose));
                }
                
                double mean = returns.stream().mapToDouble(val -> val).average().orElse(0.0);
                double variance = returns.stream().mapToDouble(val -> Math.pow(val - mean, 2)).sum() / (returns.size() - 1);
                double stdDev = Math.sqrt(variance);
                
                return stdDev * Math.sqrt(252) * 100;
            }
        } catch (Exception e) {
            logger.error("Failed to calculate volatility", e);
        }

        return 15.0; // Final fallback
    }
}
