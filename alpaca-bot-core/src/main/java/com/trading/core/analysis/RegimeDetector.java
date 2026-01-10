package com.trading.core.analysis;

import com.trading.core.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Advanced market regime detection using multiple indicators.
 * Replaces simple VIX threshold with comprehensive market analysis.
 */
public class RegimeDetector {
    private static final Logger logger = LoggerFactory.getLogger(RegimeDetector.class);
    
    public enum MarketRegime {
        BULLISH,      // Strong uptrend
        BEARISH,      // Strong downtrend
        RANGE_BOUND,  // Sideways/consolidation
        HIGH_VOLATILITY  // VIX spike, uncertain
    }
    
    private static final AtomicReference<MarketRegime> currentRegime = 
        new AtomicReference<>(MarketRegime.RANGE_BOUND);
    private static long lastUpdateTime = 0;
    
    /**
     * Get current market regime (cached for performance).
     */
    public static MarketRegime getCurrentRegime() {
        if (!Config.isRegimeDetectionEnabled()) {
            return MarketRegime.RANGE_BOUND;
        }
        
        // Check if cache is stale
        int cacheMinutes = Config.getInt("REGIME_UPDATE_INTERVAL_MINUTES", 15);
        long now = System.currentTimeMillis();
        if (now - lastUpdateTime > cacheMinutes * 60 * 1000) {
            updateRegime();
        }
        
        return currentRegime.get();
    }
    
    /**
     * Update regime based on market indicators.
     * Uses MA crossover, breadth, and VIX.
     */
    private static synchronized void updateRegime() {
        try {
            // In production, fetch real data from Alpaca
            double spyPrice = 500.0; // placeholder
            double ma50 = 490.0;     // placeholder
            double ma200 = 480.0;    // placeholder
            double vix = 18.5;       // placeholder
            double breadth = 0.65;   // placeholder (% of stocks above 50 MA)
            
            MarketRegime newRegime = detectRegime(spyPrice, ma50, ma200, vix, breadth);
            
            MarketRegime oldRegime = currentRegime.getAndSet(newRegime);
            if (oldRegime != newRegime) {
                logger.info("🌍 Market Regime Changed: {} → {}", oldRegime, newRegime);
            }
            
            lastUpdateTime = System.currentTimeMillis();
        } catch (Exception e) {
            logger.error("Regime detection error", e);
        }
    }
    
    /**
     * Detect regime based on multiple factors.
     */
    private static MarketRegime detectRegime(
            double price, double ma50, double ma200, double vix, double breadth) {
        
        double vixThreshold = Config.getVixThreshold();
        double breadthThreshold = Config.getDouble("REGIME_BREADTH_THRESHOLD", 0.6);
        
        // High volatility overrides other signals
        if (vix > vixThreshold + 5) {
            return MarketRegime.HIGH_VOLATILITY;
        }
        
        // MA crossover signals
        boolean ma50AboveMa200 = ma50 > ma200;
        boolean priceAboveMa50 = price > ma50;
        boolean priceAboveMa200 = price > ma200;
        
        // Bullish: All aligned up, good breadth
        if (ma50AboveMa200 && priceAboveMa50 && breadth > breadthThreshold) {
            return MarketRegime.BULLISH;
        }
        
        // Bearish: All aligned down, poor breadth
        if (!ma50AboveMa200 && !priceAboveMa200 && breadth < (1 - breadthThreshold)) {
            return MarketRegime.BEARISH;
        }
        
        // Default: Range bound
        return MarketRegime.RANGE_BOUND;
    }
    
    /**
     * Get symbols appropriate for current regime.
     */
    public static java.util.List<String> getRegimeSymbols() {
        MarketRegime regime = getCurrentRegime();
        
        return switch (regime) {
            case BULLISH -> Config.getMainBullishSymbols();
            case BEARISH -> Config.getMainBearishSymbols();
            case HIGH_VOLATILITY -> Config.getExperimentalBearishSymbols(); // Defensive
            default -> Config.getMainBullishSymbols(); // Default to main
        };
    }
    
    /**
     * Force regime update (for testing or manual override).
     */
    public static void forceUpdate() {
        lastUpdateTime = 0;
        updateRegime();
    }
}
