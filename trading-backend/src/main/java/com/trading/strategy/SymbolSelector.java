package com.trading.strategy;

import com.trading.analysis.MarketRegimeDetector;
import com.trading.analysis.MarketRegimeDetector.MarketRegime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Dynamically selects trading symbols based on market regime.
 * Uses comprehensive regime detection instead of simple VIX threshold.
 * 
 * Regime-to-Symbol Mapping:
 * - STRONG_BULL → Growth stocks (AAPL, NVDA, GOOGL, TSLA)
 * - WEAK_BULL → Defensive + Quality (XLP, XLV, JNJ, PG)
 * - STRONG_BEAR → Inverse ETFs (SQQQ, SPXS, TZA)
 * - WEAK_BEAR → Safe havens (GLD, TLT, utilities)
 * - RANGE_BOUND → Mean reversion candidates (SPY, QQQ, IWM)
 * - HIGH_VOLATILITY → Low beta, high quality
 */
public final class SymbolSelector {
    private static final Logger logger = LoggerFactory.getLogger(SymbolSelector.class);
    
    private final List<String> bullishSymbols;
    private final List<String> bearishSymbols;
    private final double vixThreshold;
    private final double hysteresis;
    
    // Track current regime for change detection
    private MarketRegime currentRegime;
    
    /**
     * Creates a symbol selector with regime-based selection.
     * 
     * @param bullishSymbols Symbols to trade in bullish regimes
     * @param bearishSymbols Symbols to trade in bearish regimes
     * @param vixThreshold VIX level for fallback mode (backward compatibility)
     * @param hysteresis Hysteresis band for fallback mode
     */
    public SymbolSelector(List<String> bullishSymbols, List<String> bearishSymbols, 
                         double vixThreshold, double hysteresis) {
        this.bullishSymbols = List.copyOf(bullishSymbols);
        this.bearishSymbols = List.copyOf(bearishSymbols);
        this.vixThreshold = vixThreshold;
        this.hysteresis = hysteresis;
        this.currentRegime = MarketRegime.RANGE_BOUND; // Start neutral
        
        logger.info("SymbolSelector initialized with regime-based selection:");
        logger.info("  Bullish symbols: {}", bullishSymbols);
        logger.info("  Bearish symbols: {}", bearishSymbols);
        logger.info("  Fallback VIX threshold: {} ± {}", vixThreshold, hysteresis);
    }
    
    /**
     * Selects appropriate symbols based on market regime.
     * 
     * @param regime Current market regime
     * @return List of symbols to trade
     */
    public List<String> selectSymbols(MarketRegime regime) {
        // Detect regime change
        if (regime != currentRegime) {
            logger.warn("🎯 REGIME CHANGE: {} → {}", currentRegime, regime);
            currentRegime = regime;
        }
        
        // Map regime to symbol set
        return switch (regime) {
            case STRONG_BULL -> {
                logger.debug("Strong bull regime → Bullish symbols");
                yield bullishSymbols;
            }
            case WEAK_BULL -> {
                logger.debug("Weak bull regime → Bullish symbols (defensive)");
                yield bullishSymbols;
            }
            case STRONG_BEAR -> {
                logger.debug("Strong bear regime → Bearish symbols");
                yield bearishSymbols;
            }
            case WEAK_BEAR -> {
                // Merge bullish + bearish so both defensive names (GLD, XLV) AND inverse
                // ETFs (SH, PSQ, RWM) are evaluated. StrategyManager's strict routing blocks
                // non-momentum longs; the inverse ETF bypass in StrategyManager opens the
                // path specifically for short ETFs.
                logger.debug("Weak bear regime → Combined bullish+bearish symbols (defensive + inverse ETF)");
                var combined = new java.util.ArrayList<>(bullishSymbols);
                combined.addAll(bearishSymbols);
                yield java.util.List.copyOf(combined);
            }
            case RANGE_BOUND -> {
                logger.debug("Range bound regime → Bullish symbols (mean reversion)");
                yield bullishSymbols;
            }
            case HIGH_VOLATILITY -> {
                logger.debug("High volatility regime → Defensive positioning");
                yield bullishSymbols; // Use defensive bullish in high vol
            }
        };
    }
    
    /**
     * Selects symbols based on VIX (fallback for backward compatibility).
     * 
     * @param currentVix Current VIX level
     * @return List of symbols to trade
     */
    public List<String> selectSymbols(double currentVix) {
        // Simple VIX-based selection for backward compatibility
        boolean shouldBeBearish = currentVix >= vixThreshold;
        
        // Convert to regime
        MarketRegime regime = shouldBeBearish ? 
            MarketRegime.STRONG_BEAR : MarketRegime.STRONG_BULL;
        
        return selectSymbols(regime);
    }
    
    /**
     * Returns true if currently in bearish regime.
     */
    public boolean isBearishMode() {
        return currentRegime == MarketRegime.STRONG_BEAR || 
               currentRegime == MarketRegime.WEAK_BEAR;
    }
    
    /**
     * Returns the current active symbols.
     */
    public List<String> getCurrentSymbols() {
        return selectSymbols(currentRegime);
    }
    
    /**
     * Get current regime.
     */
    public MarketRegime getCurrentRegime() {
        return currentRegime;
    }
    
    /**
     * Get bullish symbols list.
     */
    public List<String> getBullishSymbols() {
        return bullishSymbols;
    }
    
    /**
     * Get bearish symbols list.
     */
    public List<String> getBearishSymbols() {
        return bearishSymbols;
    }
}
