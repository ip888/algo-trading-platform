package com.trading.options;

import com.trading.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;

/**
 * Options Strategy Manager
 * Decides when and which options strategies to execute based on market conditions
 * 
 * Strategies:
 * - Long Call: Bullish outlook (VIX < 20, strong uptrend)
 * - Long Put: Bearish outlook (VIX < 20, strong downtrend)
 * - Long Straddle: High volatility expected (VIX > 25, earnings, etc.)
 */
public class OptionsStrategyManager {
    private static final Logger logger = LoggerFactory.getLogger(OptionsStrategyManager.class);
    
    private final Config config;
    private final OptionsClient optionsClient;
    
    // Default max cost per options trade (protective for small accounts)
    private static final double DEFAULT_MAX_OPTION_COST = 200.0;
    
    public OptionsStrategyManager(Config config) {
        this.config = config;
        this.optionsClient = new OptionsClient(config);
        
        if (config.isOptionsEnabled()) {
            logger.info("🎯 Options strategies ENABLED");
            logger.info("   Long Straddle: {}", config.isLongStraddleEnabled() ? "ON" : "OFF");
            logger.info("   Iron Butterfly: {}", config.isIronButterflyEnabled() ? "ON" : "OFF");
            logger.info("   Calendar Spread: {}", config.isCalendarSpreadEnabled() ? "ON" : "OFF");
        } else {
            logger.info("Options strategies disabled");
        }
    }
    
    /**
     * Check if we should execute an options strategy
     * @param symbol Underlying symbol
     * @param vix Current VIX level
     * @param trend Trend direction (-1 bearish, 0 neutral, +1 bullish)
     * @return Strategy recommendation or null
     */
    public OptionsRecommendation evaluateStrategy(String symbol, double vix, int trend) {
        if (!config.isOptionsEnabled()) {
            return null;
        }
        
        // High VIX (>25) -> Long Straddle (profit from big moves either direction)
        if (config.isLongStraddleEnabled() && vix > 25) {
            return new OptionsRecommendation(
                OptionsStrategy.LONG_STRADDLE,
                symbol,
                "High VIX (" + vix + ") indicates expected large price move"
            );
        }
        
        // Low VIX (<20) with strong trend -> Directional play
        if (vix < 20) {
            if (trend > 0 && config.isLongStraddleEnabled()) {
                return new OptionsRecommendation(
                    OptionsStrategy.LONG_CALL,
                    symbol,
                    "Bullish trend with low VIX - directional call"
                );
            }
            if (trend < 0 && config.isLongStraddleEnabled()) {
                return new OptionsRecommendation(
                    OptionsStrategy.LONG_PUT,
                    symbol,
                    "Bearish trend with low VIX - directional put"
                );
            }
        }
        
        return null; // No options opportunity
    }
    
    /**
     * Execute the recommended options strategy
     */
    public boolean executeStrategy(OptionsRecommendation recommendation, 
                                    double currentPrice, 
                                    double maxCost) {
        if (recommendation == null) return false;
        
        try {
            // Calculate expiration (typically 2-4 weeks out for momentum plays)
            var expiration = LocalDate.now().plusWeeks(3);
            var effectiveMaxCost = maxCost > 0 ? maxCost : DEFAULT_MAX_OPTION_COST;
            
            logger.info("Executing {} on {} (expiration: {})", 
                recommendation.strategy(), recommendation.symbol(), expiration);
            
            switch (recommendation.strategy()) {
                case LONG_CALL -> {
                    var result = optionsClient.executeLongCall(
                        recommendation.symbol(), currentPrice, expiration, effectiveMaxCost);
                    if (result != null) {
                        logger.info("✅ Long Call executed: {}", result.get("id"));
                        return true;
                    }
                }
                case LONG_PUT -> {
                    var result = optionsClient.executeLongPut(
                        recommendation.symbol(), currentPrice, expiration, effectiveMaxCost);
                    if (result != null) {
                        logger.info("✅ Long Put executed: {}", result.get("id"));
                        return true;
                    }
                }
                case LONG_STRADDLE -> {
                    optionsClient.executeLongStraddle(
                        recommendation.symbol(), currentPrice, expiration, effectiveMaxCost * 2);
                    logger.info("✅ Long Straddle executed");
                    return true;
                }
                default -> logger.warn("Strategy {} not implemented", recommendation.strategy());
            }
        } catch (Exception e) {
            logger.error("Failed to execute options strategy: {}", e.getMessage());
        }
        
        return false;
    }
    
    /**
     * Options strategy types
     */
    public enum OptionsStrategy {
        LONG_CALL,      // Bullish directional
        LONG_PUT,       // Bearish directional
        LONG_STRADDLE,  // Volatility play
        IRON_BUTTERFLY, // Range-bound, low vol (not yet implemented)
        CALENDAR_SPREAD // Time decay play (not yet implemented)
    }
    
    /**
     * Strategy recommendation record
     */
    public record OptionsRecommendation(
        OptionsStrategy strategy,
        String symbol,
        String rationale
    ) {}
    
    // Legacy methods for backwards compatibility
    public boolean shouldExecuteStrategy(String symbol, double vix) {
        return evaluateStrategy(symbol, vix, 0) != null;
    }
    
    public void executeLongStraddle(String symbol) {
        try {
            optionsClient.executeLongStraddle(symbol, 0, LocalDate.now().plusWeeks(3), 400);
        } catch (Exception e) {
            logger.error("Long straddle failed: {}", e.getMessage());
        }
    }
    
    public void executeIronButterfly(String symbol) {
        logger.warn("Iron Butterfly not yet implemented");
    }
    
    public void executeCalendarSpread(String symbol) {
        logger.warn("Calendar Spread not yet implemented");
    }
    
    public void manageOptionsRisk() {
        logger.debug("Options risk management - monitoring Greek exposure");
    }
}
