package com.trading.autonomous;

import com.trading.config.Config;
import com.trading.strategy.StrategyManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Monitors and optimizes strategy performance.
 * Automatically disables underperforming strategies and enables winners.
 */
public class StrategyOptimizer {
    private static final Logger logger = LoggerFactory.getLogger(StrategyOptimizer.class);
    
    private final Config config;
    
    // Strategy performance tracking
    private final Map<String, AtomicInteger> strategyWins = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> strategyLosses = new ConcurrentHashMap<>();
    private final Map<String, Boolean> strategyEnabled = new ConcurrentHashMap<>();
    
    // Thresholds
    private static final int MIN_TRADES_FOR_EVALUATION = 20;
    private static final double MIN_WIN_RATE = 0.55; // 55%
    
    public StrategyOptimizer(Config config) {
        this.config = config;
        
        // Initialize strategies
        strategyEnabled.put("MACD", true);
        strategyEnabled.put("RSI", true);
        strategyEnabled.put("MEAN_REVERSION", true);
        
        logger.info("🧠 StrategyOptimizer initialized - Auto-optimization enabled");
    }
    
    /**
     * Record strategy trade outcome.
     */
    public void recordStrategyTrade(String strategy, boolean isWin) {
        strategyWins.computeIfAbsent(strategy, k -> new AtomicInteger(0));
        strategyLosses.computeIfAbsent(strategy, k -> new AtomicInteger(0));
        
        if (isWin) {
            strategyWins.get(strategy).incrementAndGet();
        } else {
            strategyLosses.get(strategy).incrementAndGet();
        }
        
        // Evaluate strategy performance
        evaluateStrategy(strategy);
    }
    
    /**
     * Evaluate strategy and disable if underperforming.
     */
    private void evaluateStrategy(String strategy) {
        int wins = strategyWins.getOrDefault(strategy, new AtomicInteger(0)).get();
        int losses = strategyLosses.getOrDefault(strategy, new AtomicInteger(0)).get();
        int total = wins + losses;
        
        if (total < MIN_TRADES_FOR_EVALUATION) {
            return; // Not enough data yet
        }
        
        double winRate = (double) wins / total;
        
        if (winRate < MIN_WIN_RATE && strategyEnabled.get(strategy)) {
            logger.warn("🧠 OPTIMIZATION: {} strategy underperforming ({}% win rate)",
                strategy, String.format("%.1f", winRate * 100));
            logger.warn("   Disabling {} strategy", strategy);
            strategyEnabled.put(strategy, false);
        } else if (winRate >= MIN_WIN_RATE + 0.05 && !strategyEnabled.get(strategy)) {
            logger.info("🧠 OPTIMIZATION: {} strategy recovered ({}% win rate)",
                strategy, String.format("%.1f", winRate * 100));
            logger.info("   Re-enabling {} strategy", strategy);
            strategyEnabled.put(strategy, true);
        }
    }
    
    /**
     * Check if strategy is enabled.
     */
    public boolean isStrategyEnabled(String strategy) {
        return strategyEnabled.getOrDefault(strategy, true);
    }
    
    /**
     * Get strategy statistics.
     */
    public String getStrategyStats() {
        StringBuilder stats = new StringBuilder("Strategy Performance:\n");
        
        for (String strategy : strategyEnabled.keySet()) {
            int wins = strategyWins.getOrDefault(strategy, new AtomicInteger(0)).get();
            int losses = strategyLosses.getOrDefault(strategy, new AtomicInteger(0)).get();
            int total = wins + losses;
            double winRate = total > 0 ? (double) wins / total : 0.0;
            boolean enabled = strategyEnabled.get(strategy);
            
            stats.append(String.format("  %s: %.1f%% (%d/%d) %s\n",
                strategy, winRate * 100, wins, total, enabled ? "✅" : "❌"));
        }
        
        return stats.toString();
    }
    
    /**
     * Reset all strategy counters.
     */
    public void resetCounters() {
        logger.info("🧠 OPTIMIZATION: Resetting strategy counters");
        strategyWins.clear();
        strategyLosses.clear();
        // Re-enable all strategies
        strategyEnabled.replaceAll((k, v) -> true);
    }
}
