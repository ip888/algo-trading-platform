package com.trading.ai;

import com.trading.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.util.*;

/**
 * ML-based signal prediction using simple linear regression.
 * Predicts win probability for trading setups based on historical patterns.
 */
public class SignalPredictor {
    private static final Logger logger = LoggerFactory.getLogger(SignalPredictor.class);
    
    // Model weights (learned from training)
    private double vixWeight = 0.0;
    private double hourWeight = 0.0;
    private double dayWeight = 0.0;
    private double volumeWeight = 0.0;
    private double winRateWeight = 0.0;
    private double intercept = 0.5; // Start at 50% baseline
    
    private int trainingCount = 0;
    private double avgWinRate;
    
    public SignalPredictor(Config config) {
        // Initialize from config instead of hardcoded 0.5
        this.avgWinRate = config.getPositionSizingDefaultWinRate();
        logger.info("🤖 SignalPredictor initialized with baseline win rate: {}%", 
            String.format("%.1f", avgWinRate * 100));
    }
    
    /**
     * Train model on historical trade data.
     * Uses simple linear regression to learn patterns.
     */
    public void train(List<TradeRecord> history) {
        if (history.isEmpty()) {
            logger.warn("No training data available");
            return;
        }
        
        // Calculate average win rate
        long wins = history.stream().filter(TradeRecord::won).count();
        avgWinRate = (double) wins / history.size();
        
        // Simple weight updates based on correlations
        // In production, this would use proper gradient descent
        updateWeights(history);
        
        trainingCount = history.size();
        logger.info("📊 Model trained on {} trades, win rate: {}", 
            trainingCount, String.format("%.1f%%", avgWinRate * 100));
    }
    
    /**
     * Predict win probability for a trading setup.
     */
    public double predictWinProbability(TradingSetup setup) {
        if (trainingCount == 0) {
            // No training data - return baseline
            return avgWinRate;
        }
        
        // Extract features
        double vixFeature = normalizeVix(setup.vixLevel);
        double hourFeature = normalizeHour(setup.hour);
        double dayFeature = normalizeDay(setup.dayOfWeek);
        double volumeFeature = setup.volumeRatio;
        double winRateFeature = setup.recentWinRate;
        
        // Linear combination
        double score = intercept +
            (vixWeight * vixFeature) +
            (hourWeight * hourFeature) +
            (dayWeight * dayFeature) +
            (volumeWeight * volumeFeature) +
            (winRateWeight * winRateFeature);
        
        // Apply sigmoid to get probability [0, 1]
        double probability = 1.0 / (1.0 + Math.exp(-score));
        
        return Math.max(0.0, Math.min(1.0, probability));
    }
    
    /**
     * Get confidence score (0-100) for a setup.
     */
    public int getConfidence(TradingSetup setup) {
        double probability = predictWinProbability(setup);
        
        // Confidence based on how far from 50%
        double deviation = Math.abs(probability - 0.5);
        int confidence = (int) (deviation * 200); // 0-100 scale
        
        return Math.min(100, confidence);
    }
    
    /**
     * Update model weights based on training data.
     */
    private void updateWeights(List<TradeRecord> history) {
        // Calculate correlations (simplified)
        double vixCorr = calculateCorrelation(history, r -> normalizeVix(r.vixLevel));
        double hourCorr = calculateCorrelation(history, r -> normalizeHour(r.hour));
        double dayCorr = calculateCorrelation(history, r -> normalizeDay(r.dayOfWeek));
        double volumeCorr = calculateCorrelation(history, r -> r.volumeRatio);
        double winRateCorr = calculateCorrelation(history, r -> r.recentWinRate);
        
        // Set weights proportional to correlations
        vixWeight = vixCorr * 2.0;
        hourWeight = hourCorr * 2.0;
        dayWeight = dayCorr * 2.0;
        volumeWeight = volumeCorr * 2.0;
        winRateWeight = winRateCorr * 3.0; // Recent performance is important
        
        logger.debug("Model weights: VIX={}, Hour={}, Day={}, Volume={}, WinRate={}", 
            String.format("%.3f", vixWeight),
            String.format("%.3f", hourWeight),
            String.format("%.3f", dayWeight),
            String.format("%.3f", volumeWeight),
            String.format("%.3f", winRateWeight));
    }
    
    /**
     * Calculate correlation between feature and outcomes.
     */
    private double calculateCorrelation(List<TradeRecord> history, 
                                       java.util.function.Function<TradeRecord, Double> featureExtractor) {
        double sumX = 0, sumY = 0, sumXY = 0, sumX2 = 0, sumY2 = 0;
        int n = history.size();
        
        for (TradeRecord record : history) {
            double x = featureExtractor.apply(record);
            double y = record.won() ? 1.0 : 0.0;
            
            sumX += x;
            sumY += y;
            sumXY += x * y;
            sumX2 += x * x;
            sumY2 += y * y;
        }
        
        double numerator = (n * sumXY) - (sumX * sumY);
        double denominator = Math.sqrt((n * sumX2 - sumX * sumX) * (n * sumY2 - sumY * sumY));
        
        return denominator == 0 ? 0 : numerator / denominator;
    }
    
    /**
     * Normalize VIX to [-1, 1] range.
     */
    private double normalizeVix(double vix) {
        // VIX typically ranges 10-80
        return (vix - 30) / 25.0; // Center at 30, scale by 25
    }
    
    /**
     * Normalize hour to [-1, 1] range.
     */
    private double normalizeHour(int hour) {
        // Market hours 9:30 AM - 4:00 PM EST (9-16)
        // Best hours typically 10-11 AM and 2-3 PM
        if (hour >= 10 && hour <= 11) return 1.0;  // Morning strength
        if (hour >= 14 && hour <= 15) return 0.5;  // Afternoon activity
        if (hour == 9 || hour == 16) return -0.5;  // Open/close volatility
        return 0.0; // Neutral
    }
    
    /**
     * Normalize day of week to [-1, 1] range.
     */
    private double normalizeDay(DayOfWeek day) {
        // Tuesday-Thursday typically best
        return switch (day) {
            case TUESDAY, WEDNESDAY, THURSDAY -> 0.5;
            case MONDAY, FRIDAY -> -0.3; // More volatile
            default -> 0.0;
        };
    }
    
    /**
     * Get model statistics.
     */
    public String getStats() {
        return String.format("Trained on %d trades, Avg Win Rate: %.1f%%", 
            trainingCount, avgWinRate * 100);
    }
    
    // Inner classes
    public record TradeRecord(
        double vixLevel,
        int hour,
        DayOfWeek dayOfWeek,
        double volumeRatio,
        double recentWinRate,
        boolean won
    ) {}
    
    public record TradingSetup(
        double vixLevel,
        int hour,
        DayOfWeek dayOfWeek,
        double volumeRatio,
        double recentWinRate,
        int patternConfidence
    ) {}
}
