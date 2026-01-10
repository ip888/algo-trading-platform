package com.trading.analysis;

import com.trading.api.AlpacaClient;
import com.trading.api.model.Bar;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Correlation Calculator - Analyzes correlation between positions.
 * 
 * Features:
 * - Calculate pairwise correlation between symbols
 * - Build correlation matrix
 * - Identify highly correlated positions
 * - Suggest diversification improvements
 * - Track correlation over time
 * 
 * Goal: Reduce portfolio concentration risk and improve diversification.
 */
public class CorrelationCalculator {
    private static final Logger logger = LoggerFactory.getLogger(CorrelationCalculator.class);
    
    private final AlpacaClient client;
    private static final int CORRELATION_PERIOD = 20; // 20 days for correlation
    private static final double HIGH_CORRELATION_THRESHOLD = 0.7; // 70% correlation
    
    public CorrelationCalculator(AlpacaClient client) {
        this.client = client;
        logger.info("CorrelationCalculator initialized");
    }
    
    /**
     * Correlation result record
     */
    public record CorrelationResult(
        String symbol1,
        String symbol2,
        double correlation,
        boolean isHighlyCorrelated
    ) {}
    
    /**
     * Portfolio correlation analysis
     */
    public record PortfolioCorrelation(
        Map<String, Map<String, Double>> correlationMatrix,
        List<CorrelationResult> highCorrelations,
        double averageCorrelation,
        double diversificationScore  // 0.0 (highly correlated) to 1.0 (well diversified)
    ) {}
    
    /**
     * Calculate correlation matrix for a list of symbols.
     */
    public PortfolioCorrelation analyzePortfolio(List<String> symbols) {
        if (symbols.size() < 2) {
            return new PortfolioCorrelation(
                Map.of(), List.of(), 0.0, 1.0
            );
        }
        
        // Fetch historical data for all symbols
        Map<String, List<Double>> returns = new HashMap<>();
        for (String symbol : symbols) {
            try {
                List<Double> symbolReturns = calculateReturns(symbol);
                if (!symbolReturns.isEmpty()) {
                    returns.put(symbol, symbolReturns);
                }
            } catch (Exception e) {
                logger.warn("Could not fetch data for {}: {}", symbol, e.getMessage());
            }
        }
        
        // Build correlation matrix
        Map<String, Map<String, Double>> correlationMatrix = new HashMap<>();
        List<CorrelationResult> highCorrelations = new ArrayList<>();
        double totalCorrelation = 0.0;
        int correlationCount = 0;
        
        List<String> validSymbols = new ArrayList<>(returns.keySet());
        
        for (int i = 0; i < validSymbols.size(); i++) {
            String symbol1 = validSymbols.get(i);
            correlationMatrix.putIfAbsent(symbol1, new HashMap<>());
            
            for (int j = i + 1; j < validSymbols.size(); j++) {
                String symbol2 = validSymbols.get(j);
                
                double corr = calculateCorrelation(
                    returns.get(symbol1), 
                    returns.get(symbol2)
                );
                
                // Store in matrix (both directions)
                correlationMatrix.get(symbol1).put(symbol2, corr);
                correlationMatrix.putIfAbsent(symbol2, new HashMap<>());
                correlationMatrix.get(symbol2).put(symbol1, corr);
                
                // Track high correlations
                if (Math.abs(corr) >= HIGH_CORRELATION_THRESHOLD) {
                    highCorrelations.add(new CorrelationResult(
                        symbol1, symbol2, corr, true
                    ));
                }
                
                totalCorrelation += Math.abs(corr);
                correlationCount++;
            }
            
            // Self-correlation is 1.0
            correlationMatrix.get(symbol1).put(symbol1, 1.0);
        }
        
        double avgCorrelation = correlationCount > 0 ? totalCorrelation / correlationCount : 0.0;
        
        // Diversification score: 1.0 - average correlation
        // Higher score = better diversification
        double diversificationScore = 1.0 - avgCorrelation;
        
        logger.info("Portfolio correlation analysis: {} symbols, avg correlation: {:.2f}, diversification: {:.2f}",
            validSymbols.size(), avgCorrelation, diversificationScore);
        
        if (!highCorrelations.isEmpty()) {
            logger.warn("Found {} highly correlated pairs (>{:.0f}%):", 
                highCorrelations.size(), HIGH_CORRELATION_THRESHOLD * 100);
            for (var pair : highCorrelations) {
                logger.warn("  {} ↔ {}: {:.2f}", pair.symbol1(), pair.symbol2(), pair.correlation());
            }
        }
        
        return new PortfolioCorrelation(
            correlationMatrix,
            highCorrelations,
            avgCorrelation,
            diversificationScore
        );
    }
    
    /**
     * Calculate daily returns for a symbol.
     */
    private List<Double> calculateReturns(String symbol) throws Exception {
        List<Bar> bars = client.getMarketHistory(symbol, CORRELATION_PERIOD + 1);
        
        if (bars.size() < 2) {
            return List.of();
        }
        
        List<Double> returns = new ArrayList<>();
        for (int i = 1; i < bars.size(); i++) {
            double prevClose = bars.get(i - 1).close();
            double currClose = bars.get(i).close();
            double dailyReturn = (currClose - prevClose) / prevClose;
            returns.add(dailyReturn);
        }
        
        return returns;
    }
    
    /**
     * Calculate Pearson correlation coefficient between two return series.
     */
    private double calculateCorrelation(List<Double> returns1, List<Double> returns2) {
        if (returns1.size() != returns2.size() || returns1.isEmpty()) {
            return 0.0;
        }
        
        int n = returns1.size();
        
        // Calculate means
        double mean1 = returns1.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        double mean2 = returns2.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        
        // Calculate covariance and standard deviations
        double covariance = 0.0;
        double variance1 = 0.0;
        double variance2 = 0.0;
        
        for (int i = 0; i < n; i++) {
            double diff1 = returns1.get(i) - mean1;
            double diff2 = returns2.get(i) - mean2;
            
            covariance += diff1 * diff2;
            variance1 += diff1 * diff1;
            variance2 += diff2 * diff2;
        }
        
        covariance /= n;
        variance1 /= n;
        variance2 /= n;
        
        double stdDev1 = Math.sqrt(variance1);
        double stdDev2 = Math.sqrt(variance2);
        
        if (stdDev1 == 0.0 || stdDev2 == 0.0) {
            return 0.0;
        }
        
        return covariance / (stdDev1 * stdDev2);
    }
    
    /**
     * Check if two symbols are highly correlated.
     */
    public boolean areHighlyCorrelated(String symbol1, String symbol2) {
        try {
            List<Double> returns1 = calculateReturns(symbol1);
            List<Double> returns2 = calculateReturns(symbol2);
            
            if (returns1.isEmpty() || returns2.isEmpty()) {
                return false;
            }
            
            double corr = calculateCorrelation(returns1, returns2);
            return Math.abs(corr) >= HIGH_CORRELATION_THRESHOLD;
            
        } catch (Exception e) {
            logger.debug("Could not calculate correlation for {} and {}", symbol1, symbol2);
            return false;
        }
    }
    
    /**
     * Get diversification suggestions based on current portfolio.
     */
    public List<String> getDiversificationSuggestions(List<String> currentSymbols) {
        PortfolioCorrelation analysis = analyzePortfolio(currentSymbols);
        
        List<String> suggestions = new ArrayList<>();
        
        if (analysis.diversificationScore() < 0.5) {
            suggestions.add("Portfolio is highly correlated (score: " + 
                String.format("%.2f", analysis.diversificationScore()) + ")");
            suggestions.add("Consider reducing positions in correlated symbols");
        }
        
        if (!analysis.highCorrelations().isEmpty()) {
            suggestions.add("High correlations detected:");
            for (var pair : analysis.highCorrelations()) {
                suggestions.add(String.format("  %s ↔ %s (%.2f)", 
                    pair.symbol1(), pair.symbol2(), pair.correlation()));
            }
        }
        
        if (analysis.diversificationScore() > 0.7) {
            suggestions.add("Portfolio is well diversified (score: " + 
                String.format("%.2f", analysis.diversificationScore()) + ")");
        }
        
        return suggestions;
    }
}
