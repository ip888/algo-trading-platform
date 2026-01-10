package com.trading.strategy;

import com.trading.config.Config;
import java.time.Duration;
import java.util.List;

/**
 * Represents a trading profile with specific risk parameters and symbol allocation.
 * Supports multi-profile trading where different strategies run in parallel.
 */
public record TradingProfile(
    String name,
    double capitalPercent,
    double takeProfitPercent,
    double stopLossPercent,
    double trailingStopPercent,
    List<String> bullishSymbols,
    List<String> bearishSymbols,
    double vixThreshold,
    double vixHysteresis,
    String strategyType,
    Duration minHoldTime,
    Duration maxHoldTime
) {
    
    /**
     * Main profile: Conservative swing trading with 60% capital allocation.
     * - 10 bullish symbols (broad market + sectors)
     * - 10 bearish inverse symbols
     * - 4% take profit, 2% stop loss
     * - MACD strategy
     * - 2-7 day hold time
     */
    public static TradingProfile main(Config config) {
        return new TradingProfile(
            "MAIN",
            config.getMainProfileCapitalPercent(),
            config.getMainTakeProfitPercent(),
            config.getMainStopLossPercent(),
            config.getMainTrailingStopPercent(),
            config.getMainBullishSymbols(),
            config.getMainBearishSymbols(),
            config.getVixThreshold(),
            config.getVixHysteresis(),
            "MACD",
            Duration.ofDays(2),
            Duration.ofDays(7)
        );
    }
    
    /**
     * Experimental profile: Aggressive day trading with 40% capital allocation.
     * - 12 bullish symbols (market + sectors + commodities + bonds)
     * - 12 bearish inverse symbols
     * - 2% take profit, 1% stop loss
     * - RSI strategy
     * - 4-24 hour hold time
     */
    public static TradingProfile experimental(Config config) {
        return new TradingProfile(
            "EXPERIMENTAL",
            config.getExperimentalProfileCapitalPercent(),
            config.getExperimentalTakeProfitPercent(),
            config.getExperimentalStopLossPercent(),
            config.getExperimentalTrailingStopPercent(),
            config.getExperimentalBullishSymbols(),
            config.getExperimentalBearishSymbols(),
            config.getVixThreshold(),
            config.getVixHysteresis(),
            "RSI",
            Duration.ofHours(4),
            Duration.ofHours(24)
        );
    }
    
    /**
     * Get capital amount for this profile based on total capital.
     */
    public double getCapitalAmount(double totalCapital) {
        return totalCapital * capitalPercent;
    }
    
    /**
     * Get all symbols (bullish + bearish) for this profile.
     */
    public List<String> getAllSymbols() {
        var all = new java.util.ArrayList<String>();
        all.addAll(bullishSymbols);
        all.addAll(bearishSymbols);
        return all;
    }
}
