package com.trading.core.execution;

import com.trading.core.api.AlpacaClient;
import com.trading.core.analysis.MarketAnalysisService.AnalysisResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Service to execute trades based on analysis results.
 * Stateless and ready for Cloud Run.
 */
public class OrderExecutionService {
    private static final Logger logger = LoggerFactory.getLogger(OrderExecutionService.class);
    private final AlpacaClient client;

    public OrderExecutionService(AlpacaClient client) {
        this.client = client;
    }

    public List<String> executeSignals(List<AnalysisResult> analysisResults) {
        return analysisResults.stream()
            .map(result -> {
                String action = result.recommendation().toUpperCase();
                return switch (action) {
                    case "BUY" -> client.postOrderAsync(result.symbol(), 1, "buy", "market", "day")
                            .thenApply(resp -> "BUY " + result.symbol() + ": " + resp);
                    case "SELL" -> client.postOrderAsync(result.symbol(), 1, "sell", "market", "day")
                            .thenApply(resp -> "SELL " + result.symbol() + ": " + resp);
                    default -> CompletableFuture.completedFuture("HOLD " + result.symbol());
                };
            })
            .map(CompletableFuture::join) // Wait for completion (cheap on Virtual Threads)
            .toList();
    }
}
