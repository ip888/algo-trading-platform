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
                String action = result.recommendation();
                if ("BUY".equalsIgnoreCase(action)) {
                    // Simple logic: Buy 1 share
                    return client.postOrderAsync(result.symbol(), 1, "buy", "market", "day")
                               .thenApply(resp -> "BUY " + result.symbol() + ": " + resp);
                } else if ("SELL".equalsIgnoreCase(action)) {
                    // Simple logic: Sell 1 share (or close position)
                    return client.postOrderAsync(result.symbol(), 1, "sell", "market", "day")
                               .thenApply(resp -> "SELL " + result.symbol() + ": " + resp);
                }
                return CompletableFuture.completedFuture("HOLD " + result.symbol());
            })
            .map(CompletableFuture::join) // Wait for completion (in VThread this is cheap)
            .toList();
    }
}
