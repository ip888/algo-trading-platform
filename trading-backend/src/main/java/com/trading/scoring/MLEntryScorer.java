package com.trading.scoring;

import com.trading.analysis.MarketAnalyzer;
import com.trading.ai.SentimentAnalyzer;
import com.trading.config.Config;
import com.trading.api.model.Bar;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * ML-Based Entry Scoring System
 * Combines multiple factors to score entry opportunities (0-100)
 * Higher scores = better entry quality
 */
public class MLEntryScorer {
    private static final Logger logger = LoggerFactory.getLogger(MLEntryScorer.class);
    
    private final Config config;
    private final MarketAnalyzer marketAnalyzer;
    private final SentimentAnalyzer sentimentAnalyzer;
    
    // Weights for different factors
    private final double technicalWeight;
    private final double sentimentWeight;
    private final double volumeWeight;
    private final double momentumWeight;
    
    public MLEntryScorer(Config config, MarketAnalyzer marketAnalyzer, SentimentAnalyzer sentimentAnalyzer) {
        this.config = config;
        this.marketAnalyzer = marketAnalyzer;
        this.sentimentAnalyzer = sentimentAnalyzer;
        
        // Get weights from config
        this.technicalWeight = config.getMLScoreTechnicalWeight();
        this.sentimentWeight = config.getMLScoreSentimentWeight();
        this.volumeWeight = config.getMLScoreVolumeWeight();
        this.momentumWeight = config.getMLScoreMomentumWeight();
    }
    
    /**
     * Score an entry opportunity (0-100)
     * @param symbol Stock symbol
     * @param currentPrice Current price
     * @param bars Recent price bars
     * @return Score from 0-100 (higher is better)
     */
    public double scoreEntry(String symbol, double currentPrice, List<Bar> bars) {
        try {
            double technicalScore = calculateTechnicalScore(bars, currentPrice);
            double sentimentScore = calculateSentimentScore(symbol);
            double volumeScore = calculateVolumeScore(bars);
            double momentumScore = calculateMomentumScore(bars);
            
            // Weighted combination
            double mlScore = (technicalScore * technicalWeight) +
                           (sentimentScore * sentimentWeight) +
                           (volumeScore * volumeWeight) +
                           (momentumScore * momentumWeight);
            
            logger.debug("ML Score for {}: {} (Tech:{} Sent:{} Vol:{} Mom:{})",
                symbol, String.format("%.1f", mlScore), 
                String.format("%.1f", technicalScore), 
                String.format("%.1f", sentimentScore), 
                String.format("%.1f", volumeScore), 
                String.format("%.1f", momentumScore));
            
            return Math.max(0, Math.min(100, mlScore));
            
        } catch (Exception e) {
            logger.error("Error calculating ML score for {}", symbol, e);
            return 50.0; // Neutral score on error
        }
    }
    
    /**
     * Calculate technical score based on indicators
     */
    private double calculateTechnicalScore(List<Bar> bars, double currentPrice) {
        if (bars.size() < 20) return 50.0;
        
        double score = 50.0; // Start neutral
        
        // Moving average trend
        double sma20 = calculateSMA(bars, 20);
        double sma50 = bars.size() >= 50 ? calculateSMA(bars, 50) : sma20;
        
        if (currentPrice > sma20 && sma20 > sma50) {
            score += 30; // Strong uptrend
        } else if (currentPrice > sma20) {
            score += 20; // Mild uptrend
        }
        
        // Price vs MA distance (not too extended)
        double distanceFromMA = Math.abs(currentPrice - sma20) / sma20;
        if (distanceFromMA < 0.02) {
            score += 20; // Close to MA = good entry
        }
        
        return Math.max(0, Math.min(100, score));
    }
    
    /**
     * Calculate sentiment score
     */
    private double calculateSentimentScore(String symbol) {
        try {
            if (sentimentAnalyzer == null) return 50.0;
            
            double sentiment = sentimentAnalyzer.getSentimentScore(symbol);
            
            // Convert sentiment (-1 to +1) to score (0-100)
            return 50 + (sentiment * 50);
            
        } catch (Exception e) {
            return 50.0; // Neutral on error
        }
    }
    
    /**
     * Calculate volume score
     */
    private double calculateVolumeScore(List<Bar> bars) {
        if (bars.size() < 20) return 50.0;
        
        double score = 50.0;
        
        // Get recent volume vs average
        long recentVolume = bars.get(bars.size() - 1).volume();
        long avgVolume = bars.stream()
            .limit(20)
            .mapToLong(Bar::volume)
            .sum() / 20;
        
        double volumeRatio = (double) recentVolume / avgVolume;
        
        if (volumeRatio > 1.5) {
            score += 30; // High volume = strong signal
        } else if (volumeRatio > 1.2) {
            score += 20;
        } else if (volumeRatio < 0.5) {
            score -= 20; // Low volume = weak signal
        }
        
        return Math.max(0, Math.min(100, score));
    }
    
    /**
     * Calculate momentum score
     */
    private double calculateMomentumScore(List<Bar> bars) {
        if (bars.size() < 10) return 50.0;
        
        double score = 50.0;
        
        // Calculate price momentum (% change over last 5 bars)
        double oldPrice = bars.get(bars.size() - 6).close();
        double newPrice = bars.get(bars.size() - 1).close();
        double momentum = ((newPrice - oldPrice) / oldPrice) * 100;
        
        if (momentum > 2.0) {
            score += 30; // Strong positive momentum
        } else if (momentum > 1.0) {
            score += 20;
        } else if (momentum > 0) {
            score += 10;
        } else if (momentum < -2.0) {
            score -= 30; // Strong negative momentum
        }
        
        return Math.max(0, Math.min(100, score));
    }
    
    /**
     * Calculate Simple Moving Average
     */
    private double calculateSMA(List<Bar> bars, int period) {
        if (bars.size() < period) return 0;
        
        return bars.stream()
            .skip(Math.max(0, bars.size() - period))
            .mapToDouble(Bar::close)
            .average()
            .orElse(0);
    }
    
    /**
     * Check if score meets minimum threshold
     */
    public boolean meetsThreshold(double score) {
        double minScore = config.getMLMinScore();
        return score >= minScore;
    }
}
