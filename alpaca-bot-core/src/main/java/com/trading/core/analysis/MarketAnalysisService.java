package com.trading.core.analysis;

import com.trading.core.api.AlpacaClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * Stateless Analysis Service using Virtual Threads.
 */
public class MarketAnalysisService {
    private static final Logger logger = LoggerFactory.getLogger(MarketAnalysisService.class);
    private final AlpacaClient client;
    private final com.trading.core.analysis.MarketRegimeService regimeService;
    private final com.trading.core.strategy.StrategyService strategyService;

    public MarketAnalysisService(AlpacaClient client) {
        this.client = client;
        this.regimeService = new com.trading.core.analysis.MarketRegimeService(client);
        this.strategyService = new com.trading.core.strategy.StrategyService(client);
    }

    public record AnalysisResult(String symbol, String recommendation, double score, String regime, String strategy, String reason, double price) {}

    public List<AnalysisResult> analyzeMarket(List<String> symbols) {
        logger.info("🧠 Analyzing market for {} symbols using Virtual Threads...", symbols.size());

        // 1. Detect Global Market Regime (Once)
        var globalRegime = regimeService.detectRegime();
        logger.info("🌍 Global Market Regime: {}", globalRegime);

        // Java 25 / Project Loom: Use Virtual Threads for high-concurrency tasks
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            
            var futures = symbols.stream()
                .map(symbol -> CompletableFuture.supplyAsync(() -> analyzeSymbol(symbol, globalRegime), executor))
                .toList();

            return futures.stream()
                .map(CompletableFuture::join)
                .collect(Collectors.toList());
        }
    }

    private AnalysisResult analyzeSymbol(String symbol, com.trading.core.model.MarketRegime regime) {
        // This runs on a virtual thread
        try {
            // Execute Strategy based on Regime
            var signal = strategyService.generateSignal(symbol, regime);
            
            logger.debug("Analyzed {}: {} ({})", symbol, signal.action(), signal.reason());
            
            // Map Strategy Signal to Analysis Result
            double score = signal.action().equals("BUY") ? 80.0 : (signal.action().equals("SELL") ? 20.0 : 50.0);
            
            return new AnalysisResult(
                symbol, 
                signal.action(), 
                score, 
                regime.toString(), 
                signal.strategyName(), 
                signal.reason(),
                signal.price()
            );

        } catch (Exception e) {
            logger.error("Failed to analyze " + symbol, e);
            return new AnalysisResult(symbol, "ERROR", 0.0, "UNKNOWN", "None", e.getMessage(), 0.0);
        }
    }
}
