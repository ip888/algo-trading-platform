package com.trading.core.analysis;

import com.trading.core.api.AlpacaClient;
import com.trading.core.config.Config;
import com.trading.core.model.Bar;
import com.trading.core.model.MarketRegime;
import com.trading.core.strategy.StrategyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Adaptive Strategy Engine (Walk-Forward Optimizer).
 * Runs "Tournament" simulations to auto-tune parameters.
 */
public class ParameterOptimizer {
    private static final Logger logger = LoggerFactory.getLogger(ParameterOptimizer.class);
    private final AlpacaClient client;
    private final BacktestService backtester;

    public ParameterOptimizer(AlpacaClient client, BacktestService backtester) {
        this.client = client;
        this.backtester = backtester;
    }

    public record OptimizationResult(double score, double bestSl, double bestTp) {}

    /**
     * Run optimization tournament for the symbol.
     */
    public CompletableFuture<OptimizationResult> optimize(String symbol) {
        logger.info("🧬 Starting Optimization Tournament for {}", symbol);

        // 1. Fetch last 5 business days of 15-min data (approx 130 bars) for quick optimization
        // In a real system, we'd fetch minutes, but we'll use daily history helper for now 
        // and just inject it into the backtester if it supported varying granularity.
        // For this demo, we assume the backtester handles daily data well enough for basic parameter tuning.
        
        return client.getMarketHistoryAsync(symbol, 20).thenApply(history -> {
            if (history == null || history.size() < 20) {
                logger.warn("Optimization skipped for {}: Insufficient data", symbol);
                return new OptimizationResult(0, Config.getMainStopLossPercent(), Config.getMainTakeProfitPercent());
            }

            double bestScore = -9999;
            double bestSl = Config.getMainStopLossPercent();
            double bestTp = Config.getMainTakeProfitPercent();

            // 2. Define Param Space
            double[] stopLosses = {0.25, 0.5, 1.0, 1.5, 2.0};
            double[] takeProfits = {0.5, 0.75, 1.0, 2.0, 3.0};

            // 3. Run Tournament
            for (double sl : stopLosses) {
                for (double tp : takeProfits) {
                    // Safety check: Don't optimize into negative expectancy (TP < SL can be valid for high win rate, but risky)
                    if (tp < sl * 0.5) continue; 

                    BacktestService.BacktestRequest req = new BacktestService.BacktestRequest(
                        symbol, 20, 10000, tp, sl
                    );
                    
                    try {
                        Map<String, Object> result = backtester.runSimulation(req, history);
                        
                        double totalReturn = (double) result.get("totalReturn");
                        double maxDrawdown = (double) result.get("maxDrawdown");
                        double winRate = (double) result.get("winRate");
                        
                        // Scoring Function: Return / Drawdown (Calmar-like)
                        // Penalize low win rates or high drawdowns severely
                        double score = totalReturn;
                        if (maxDrawdown > 5) score /= (maxDrawdown / 2); // Penalty for DD > 5%
                        if (winRate < 40) score *= 0.5; // Penalty for low win rate
                        
                        // logger.debug("Testing SL={} TP={} -> Return={}% DD={}% Score={}", sl, tp, String.format("%.2f", totalReturn), String.format("%.2f", maxDrawdown), String.format("%.2f", score));
                        
                        if (score > bestScore) {
                            bestScore = score;
                            bestSl = sl;
                            bestTp = tp;
                        }
                    } catch (Exception e) {
                        logger.warn("Optimization simulation failed", e);
                    }
                }
            }
            
            logger.info("🏆 Optimization Winner for {}: SL={}%, TP={}%, Score={}", symbol, bestSl, bestTp, String.format("%.2f", bestScore));
            
            // Apply Best Parameters to Config Overrides
            // Note: In a real multi-symbol system, we might want per-symbol overrides.
            // For now, we apply to global experimental settings if this is our "Main" symbol (like SPY)
            if (symbol.equals("SPY")) {
                 Config.setOverride("EXPERIMENTAL_STOP_LOSS_PERCENT", String.valueOf(bestSl));
                 Config.setOverride("EXPERIMENTAL_TAKE_PROFIT_PERCENT", String.valueOf(bestTp));
                 Config.setOverride("MAIN_STOP_LOSS_PERCENT", String.valueOf(bestSl)); // Aggressive update
                 Config.setOverride("MAIN_TAKE_PROFIT_PERCENT", String.valueOf(bestTp));
            }
            
            return new OptimizationResult(bestScore, bestSl, bestTp);
        });
    }
}
