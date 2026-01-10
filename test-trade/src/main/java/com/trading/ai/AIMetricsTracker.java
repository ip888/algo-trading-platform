package com.trading.ai;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Tracks AI component metrics for dashboard display.
 * Thread-safe singleton for collecting AI activity across the bot.
 */
public class AIMetricsTracker {
    private static final AIMetricsTracker INSTANCE = new AIMetricsTracker();
    
    private final AtomicReference<String> lastSentimentSymbol = new AtomicReference<>("-");
    private final AtomicReference<Double> lastSentimentScore = new AtomicReference<>(0.0);
    private final AtomicReference<Double> lastMLProbability = new AtomicReference<>(0.0);
    private final AtomicReference<Integer> lastAnomalySeverity = new AtomicReference<>(0);
    private final AtomicReference<String> lastAnomalyAction = new AtomicReference<>("CONTINUE");
    private final AtomicReference<Integer> lastRiskScore = new AtomicReference<>(0);
    private final AtomicInteger tradesFilteredToday = new AtomicInteger(0);
    
    private AIMetricsTracker() {}
    
    public static AIMetricsTracker getInstance() {
        return INSTANCE;
    }
    
    public void recordSentiment(String symbol, double score) {
        lastSentimentSymbol.set(symbol);
        lastSentimentScore.set(score);
    }
    
    public void recordMLPrediction(double probability) {
        lastMLProbability.set(probability);
    }
    
    public void recordAnomaly(int severity, String action) {
        lastAnomalySeverity.set(severity);
        lastAnomalyAction.set(action);
    }
    
    public void recordRisk(int riskScore) {
        lastRiskScore.set(riskScore);
    }
    
    public void incrementTradesFiltered() {
        tradesFilteredToday.incrementAndGet();
    }
    
    public void resetDailyCounters() {
        tradesFilteredToday.set(0);
    }
    
    // Getters
    public String getLastSentimentSymbol() {
        return lastSentimentSymbol.get();
    }
    
    public double getLastSentimentScore() {
        return lastSentimentScore.get();
    }
    
    public double getLastMLProbability() {
        return lastMLProbability.get();
    }
    
    public int getLastAnomalySeverity() {
        return lastAnomalySeverity.get();
    }
    
    public String getLastAnomalyAction() {
        return lastAnomalyAction.get();
    }
    
    public int getLastRiskScore() {
        return lastRiskScore.get();
    }
    
    public int getTradesFilteredToday() {
        return tradesFilteredToday.get();
    }
}
