package com.trading.learning;

import com.trading.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Auto-parameter optimization for take-profit and stop-loss percentages.
 * Learns from performance and gradually adjusts parameters for better results.
 */
public class ParameterOptimizer {
    private static final Logger logger = LoggerFactory.getLogger(ParameterOptimizer.class);
    
    // Parameter bounds
    private static final double MAX_TP = 0.05;  // 5%
    private static final double MIN_TP = 0.005; // 0.5%
    private static final double MAX_SL = 0.03;  // 3%
    private static final double MIN_SL = 0.005; // 0.5%
    private static final double ADJUSTMENT_STEP = 0.001; // 0.1% per adjustment
    
    // Performance tracking
    private final Map<String, StrategyPerformance> strategyStats;
    private final Config config;
    
    public ParameterOptimizer(Config config) {
        this.config = config;
        this.strategyStats = new HashMap<>();
        logger.info("📊 ParameterOptimizer initialized");
    }
    
    /**
     * Record trade outcome for a strategy.
     */
    public void recordTrade(String strategy, boolean won, double tpPercent, double slPercent) {
        StrategyPerformance perf = strategyStats.computeIfAbsent(
            strategy, 
            k -> new StrategyPerformance()
        );
        
        perf.totalTrades++;
        if (won) {
            perf.wins++;
        } else {
            perf.losses++;
        }
        
        // Track current parameters
        perf.currentTP = tpPercent;
        perf.currentSL = slPercent;
    }
    
    /**
     * Optimize take-profit percentage for a strategy.
     */
    public double optimizeTakeProfit(String strategy) {
        StrategyPerformance perf = strategyStats.get(strategy);
        if (perf == null || perf.totalTrades < 20) {
            // Not enough data, use default 2%
            return 0.02;
        }
        
        double winRate = (double) perf.wins / perf.totalTrades;
        double currentTP = perf.currentTP;
        
        // If hitting TP frequently (high win rate), increase target
        if (winRate > 0.70 && currentTP < MAX_TP) {
            double newTP = Math.min(MAX_TP, currentTP + ADJUSTMENT_STEP);
            logger.info("📈 Increasing TP for {}: {}% → {}% (win rate: {}%)", 
                strategy, 
                String.format("%.2f", currentTP * 100),
                String.format("%.2f", newTP * 100),
                String.format("%.1f", winRate * 100));
            return newTP;
        }
        
        // If rarely hitting TP (low win rate), decrease target
        if (winRate < 0.50 && currentTP > MIN_TP) {
            double newTP = Math.max(MIN_TP, currentTP - ADJUSTMENT_STEP);
            logger.info("📉 Decreasing TP for {}: {}% → {}% (win rate: {}%)", 
                strategy,
                String.format("%.2f", currentTP * 100),
                String.format("%.2f", newTP * 100),
                String.format("%.1f", winRate * 100));
            return newTP;
        }
        
        // Current TP is optimal
        return currentTP;
    }
    
    /**
     * Optimize stop-loss percentage for a strategy.
     */
    public double optimizeStopLoss(String strategy) {
        StrategyPerformance perf = strategyStats.get(strategy);
        if (perf == null || perf.totalTrades < 20) {
            // Not enough data, use default 1%
            return 0.01;
        }
        
        double winRate = (double) perf.wins / perf.totalTrades;
        double currentSL = perf.currentSL;
        
        // If getting stopped out often (low win rate), widen SL
        if (winRate < 0.50 && currentSL < MAX_SL) {
            double newSL = Math.min(MAX_SL, currentSL + ADJUSTMENT_STEP);
            logger.info("📈 Widening SL for {}: {}% → {}% (win rate: {}%)", 
                strategy,
                String.format("%.2f", currentSL * 100),
                String.format("%.2f", newSL * 100),
                String.format("%.1f", winRate * 100));
            return newSL;
        }
        
        // If rarely hitting SL (high win rate), tighten SL
        if (winRate > 0.70 && currentSL > MIN_SL) {
            double newSL = Math.max(MIN_SL, currentSL - ADJUSTMENT_STEP);
            logger.info("📉 Tightening SL for {}: {}% → {}% (win rate: {}%)", 
                strategy,
                String.format("%.2f", currentSL * 100),
                String.format("%.2f", newSL * 100),
                String.format("%.1f", winRate * 100));
            return newSL;
        }
        
        // Current SL is optimal
        return currentSL;
    }
    
    /**
     * Get optimization report for all strategies.
     */
    public String getOptimizationReport() {
        if (strategyStats.isEmpty()) {
            return "No optimization data available";
        }
        
        StringBuilder report = new StringBuilder();
        report.append("Parameter Optimization Report:\n");
        
        for (Map.Entry<String, StrategyPerformance> entry : strategyStats.entrySet()) {
            String strategy = entry.getKey();
            StrategyPerformance perf = entry.getValue();
            double winRate = perf.totalTrades > 0 ? 
                (double) perf.wins / perf.totalTrades : 0.0;
            
            report.append(String.format("  %s: %d trades, %.1f%% win rate, TP=%.2f%%, SL=%.2f%%\n",
                strategy, perf.totalTrades, winRate * 100, 
                perf.currentTP * 100, perf.currentSL * 100));
        }
        
        return report.toString();
    }
    
    /**
     * Get statistics.
     */
    public String getStats() {
        int totalStrategies = strategyStats.size();
        int totalTrades = strategyStats.values().stream()
            .mapToInt(p -> p.totalTrades)
            .sum();
        
        return String.format("Strategies: %d, Total Trades: %d", 
            totalStrategies, totalTrades);
    }
    
    // Inner class for tracking performance
    private static class StrategyPerformance {
        int wins = 0;
        int losses = 0;
        int totalTrades = 0;
        double currentTP = 0.02; // Default 2%
        double currentSL = 0.01; // Default 1%
    }
}
