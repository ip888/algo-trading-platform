package com.trading.analysis;

import com.trading.api.AlpacaClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Analyzes market conditions and provides trading recommendations.
 */
public final class MarketAnalyzer {
    private static final Logger logger = LoggerFactory.getLogger(MarketAnalyzer.class);
    
    private final AlpacaClient client;
    private final com.trading.config.Config config;
    private volatile MarketAnalysis lastAnalysis;
    
    public MarketAnalyzer(AlpacaClient client) {
        this.client = client;
        this.config = new com.trading.config.Config();
        this.lastAnalysis = new MarketAnalysis(
            MarketTrend.NEUTRAL, 0.0, 0.0, Map.of(), "Initializing..."
        );
    }
    
    /**
     * Analyze current market conditions.
     */
    public MarketAnalysis analyze(List<String> symbols) {
        try {
            // Get VIX for volatility
            double vix = getVIX();
            
            // Analyze each symbol
            Map<String, AssetScore> scores = new HashMap<>();
            for (String symbol : symbols) {
                scores.put(symbol, analyzeAsset(symbol, vix));
            }
            
            // Determine overall market trend
            MarketTrend trend = determineTrend(scores, vix);
            
            // Calculate market strength (0-100)
            double marketStrength = calculateMarketStrength(scores);
            
            // Generate recommendation
            String recommendation = generateRecommendation(trend, scores, vix);
            
            lastAnalysis = new MarketAnalysis(trend, vix, marketStrength, scores, recommendation);
            logger.debug("Market analysis updated: {} VIX={}", trend, vix);
            
            return lastAnalysis;
            
        } catch (Exception e) {
            logger.error("Error analyzing market", e);
            return lastAnalysis;
        }
    }
    
    private double getVIX() {
        // 1. Try direct VIX index
        try {
            var bar = client.getLatestBar("VIX");
            return bar.get().close();
        } catch (Exception e) {
            logger.debug("VIX index not available, trying proxy...");
        }

        // 2. Try VIXY ETF as proxy
        try {
            var bar = client.getLatestBar("VIXY");
            double vixyPrice = bar.get().close();
            
            // CRITICAL FIX: VIXY price is NOT the same as VIX level!
            // VIXY is a VIX futures ETF with price in dollars, VIX is a volatility index
            // Approximate conversion: VIX ≈ (VIXY_price / 2) + 2
            // This is a rough estimate since VIXY tracks VIX futures, not spot VIX
            // When VIXY = $30, VIX is typically around 15-17
            double estimatedVIX = (vixyPrice / 2.0) + 2.0;
            
            logger.info("Using VIXY ETF as volatility proxy: VIXY=${} -> estimated VIX={}", 
                String.format("%.2f", vixyPrice), String.format("%.2f", estimatedVIX));
            return estimatedVIX;
        } catch (Exception e) {
            logger.debug("VIXY proxy not available, trying calculation...");
        }

        // 3. Fallback: Calculate realized volatility from SPY
        try {
            var bars = client.getMarketHistory("SPY", 20); // Last 20 days
            if (bars.size() >= 2) {
                double sum = 0;
                double sumSq = 0;
                int n = bars.size();
                
                // Calculate daily returns
                List<Double> returns = new ArrayList<>();
                for (int i = 1; i < n; i++) {
                    double close = bars.get(i).close();
                    double prevClose = bars.get(i-1).close();
                    returns.add(Math.log(close / prevClose));
                }
                
                // Calculate standard deviation of returns
                double mean = returns.stream().mapToDouble(val -> val).average().orElse(0.0);
                double variance = returns.stream().mapToDouble(val -> Math.pow(val - mean, 2)).sum() / (returns.size() - 1);
                double stdDev = Math.sqrt(variance);
                
                // Annualize (multiply by sqrt(252))
                double annualizedVol = stdDev * Math.sqrt(252) * 100;
                
                logger.info("Using calculated SPY volatility: {}", String.format("%.2f", annualizedVol));
                return annualizedVol;
            }
        } catch (Exception e) {
            logger.error("Failed to calculate volatility", e);
        }

        logger.warn("Could not determine volatility, using default");
        return 15.0; // Final fallback
    }
    
    private AssetScore analyzeAsset(String symbol, double vix) {
        try {
            var bar = client.getLatestBar(symbol);
            double price = bar.get().close();
            long volume = bar.get().volume();
            
            // Calculate change and changePercent
            double open = bar.get().open();
            double change = price - open;
            double changePercent = (change / open) * 100.0;
            
            // Determine trend based on price movement
            String trend;
            if (changePercent > 1.0) {
                trend = "UP";
            } else if (changePercent < -1.0) {
                trend = "DOWN";
            } else {
                trend = "FLAT";
            }
            
            // Calculate REAL momentum score using RSI and price action
            double momentum = calculateMomentumScore(symbol, price, changePercent);
            
            // Volatility score (inverse of VIX impact)
            // Low VIX = good for trading = high score
            double volatilityScore = Math.max(0, 100 - (vix * 2));
            
            // Volume score (normalized to 0-100)
            // Higher volume = more confidence = higher score
            double volumeScore = normalizeVolumeScore(volume);
            
            // Overall score (0-100) - weighted average
            // Momentum is most important (50%), then volatility (30%), then volume (20%)
            double overallScore = (momentum * 0.5) + (volatilityScore * 0.3) + (volumeScore * 0.2);
            
            // Trading recommendation based on overall score
            String recommendation;
            if (overallScore > 70) {
                recommendation = "STRONG BUY";
            } else if (overallScore > 55) {
                recommendation = "BUY";
            } else if (overallScore > 45) {
                recommendation = "HOLD";
            } else if (overallScore > 30) {
                recommendation = "SELL";
            } else {
                recommendation = "STRONG SELL";
            }
            
            return new AssetScore(
                symbol, price, change, changePercent, volume, trend,
                overallScore, momentum, volatilityScore, volumeScore,
                recommendation
            );
            
        } catch (Exception e) {
            logger.error("Error analyzing {}", symbol, e);
            return new AssetScore(symbol, 0.0, 0.0, 0.0, 0L, "FLAT", 50.0, 50.0, 50.0, 50.0, "ERROR");
        }
    }
    
    /**
     * Calculate momentum score (0-100) using RSI and price action.
     */
    private double calculateMomentumScore(String symbol, double currentPrice, double changePercent) {
        try {
            // Get historical data for RSI calculation
            var bars = client.getMarketHistory(symbol, 20);
            
            logger.debug("Calculating momentum for {}: {} bars available", symbol, bars.size());
            
            if (bars.size() < 15) {
                // Not enough data, use price change as proxy
                double fallbackScore = 50.0 + (changePercent * 10); // Scale price change to momentum
                logger.debug("Insufficient history for {} ({} bars), using price-based momentum: {}", 
                    symbol, bars.size(), String.format("%.1f", fallbackScore));
                return Math.max(0, Math.min(100, fallbackScore));
            }
            
            var closes = bars.stream().map(com.trading.api.model.Bar::close).toList();
            double rsi = calculateRSI(closes, 14);
            
            logger.debug("RSI for {}: {}", symbol, String.format("%.2f", rsi));
            
            // Convert RSI to momentum score (0-100)
            // RSI 0-30 (oversold) → momentum 0-40 (bearish but improving)
            // RSI 30-50 → momentum 40-60 (neutral to slightly bearish)
            // RSI 50-70 → momentum 60-80 (bullish)
            // RSI 70-100 (overbought) → momentum 80-100 (very bullish but may reverse)
            
            double momentumScore;
            if (rsi < 30) {
                // Oversold - bearish but potential reversal
                momentumScore = 20 + (rsi / 30.0 * 20); // 20-40 range
            } else if (rsi < 50) {
                // Below neutral - slightly bearish
                momentumScore = 40 + ((rsi - 30) / 20.0 * 20); // 40-60 range
            } else if (rsi < 70) {
                // Above neutral - bullish
                momentumScore = 60 + ((rsi - 50) / 20.0 * 20); // 60-80 range
            } else {
                // Overbought - very bullish
                momentumScore = 80 + ((rsi - 70) / 30.0 * 20); // 80-100 range
            }
            
            // Adjust for recent price action
            momentumScore += (changePercent * 2); // Add/subtract based on today's movement
            
            double finalScore = Math.max(0, Math.min(100, momentumScore));
            logger.debug("Momentum for {}: RSI={} → score={}", 
                symbol, String.format("%.2f", rsi), String.format("%.1f", finalScore));
            
            return finalScore;
            
        } catch (Exception e) {
            logger.debug("Could not calculate momentum for {}: {}, using price change", 
                symbol, e.getMessage());
            // Fallback: use price change as momentum indicator
            double fallbackScore = 50.0 + (changePercent * 10); // Scale to 0-100 range
            return Math.max(0, Math.min(100, fallbackScore));
        }
    }
    
    /**
     * Calculate RSI (Relative Strength Index).
     */
    private double calculateRSI(List<Double> prices, int period) {
        if (prices.size() <= period) {
            return 50.0; // Neutral if not enough data
        }
        
        double avgGain = 0.0;
        double avgLoss = 0.0;
        
        // Calculate initial average gain/loss
        for (int i = 1; i <= period; i++) {
            double change = prices.get(i) - prices.get(i - 1);
            if (change > 0) {
                avgGain += change;
            } else {
                avgLoss += Math.abs(change);
            }
        }
        
        avgGain /= period;
        avgLoss /= period;
        
        // Calculate smoothed averages for remaining data
        for (int i = period + 1; i < prices.size(); i++) {
            double change = prices.get(i) - prices.get(i - 1);
            double gain = change > 0 ? change : 0;
            double loss = change < 0 ? Math.abs(change) : 0;
            
            avgGain = (avgGain * (period - 1) + gain) / period;
            avgLoss = (avgLoss * (period - 1) + loss) / period;
        }
        
        if (avgLoss == 0) {
            return 100.0;
        }
        
        double rs = avgGain / avgLoss;
        return 100.0 - (100.0 / (1.0 + rs));
    }
    
    /**
     * Normalize volume to 0-100 score.
     */
    private double normalizeVolumeScore(long volume) {
        // Typical volume ranges:
        // SPY: 50-100M shares/day
        // Individual stocks: 1-50M shares/day
        // Low volume: < 1M
        
        if (volume > 50_000_000) {
            return 100.0; // Very high volume
        } else if (volume > 10_000_000) {
            return 70.0 + ((volume - 10_000_000) / 40_000_000.0 * 30); // 70-100
        } else if (volume > 1_000_000) {
            return 40.0 + ((volume - 1_000_000) / 9_000_000.0 * 30); // 40-70
        } else {
            return Math.min(40, volume / 1_000_000.0 * 40); // 0-40
        }
    }
    
    private MarketTrend determineTrend(Map<String, AssetScore> scores, double vix) {
        // Check for forced trend (for testing)
        String forcedTrend = config.getForcedMarketTrend();
        if (forcedTrend != null && !forcedTrend.isEmpty()) {
            try {
                MarketTrend trend = MarketTrend.valueOf(forcedTrend.toUpperCase());
                logger.warn("⚠️ USING FORCED MARKET TREND: {}", trend);
                return trend;
            } catch (IllegalArgumentException e) {
                logger.warn("Invalid forced trend: {}", forcedTrend);
            }
        }

        if (vix > 30) {
            return MarketTrend.VOLATILE;
        }
        
        double avgScore = scores.values().stream()
            .mapToDouble(AssetScore::overallScore)
            .average()
            .orElse(50.0);
        
        if (avgScore > 60) {
            return MarketTrend.BULLISH;
        } else if (avgScore < 40) {
            return MarketTrend.BEARISH;
        } else {
            return MarketTrend.NEUTRAL;
        }
    }
    
    private double calculateMarketStrength(Map<String, AssetScore> scores) {
        return scores.values().stream()
            .mapToDouble(AssetScore::overallScore)
            .average()
            .orElse(50.0);
    }
    
    private String generateRecommendation(MarketTrend trend, Map<String, AssetScore> scores, double vix) {
        // Get top ranked asset
        var topAsset = scores.values().stream()
            .max(Comparator.comparingDouble(AssetScore::overallScore))
            .orElse(null);
        
        return switch (trend) {
            case BULLISH -> "Market is bullish. Consider buying " + 
                (topAsset != null ? topAsset.symbol() : "top assets") + ". VIX is low (" + String.format("%.1f", vix) + ").";
            case BEARISH -> "Market is bearish. Be cautious, consider defensive positions or cash.";
            case VOLATILE -> "High volatility (VIX=" + String.format("%.1f", vix) + "). Avoid new positions, use tight stops.";
            case NEUTRAL -> "Market is neutral. Look for breakout opportunities in " + 
                (topAsset != null ? topAsset.symbol() : "individual assets") + ".";
        };
    }
    
    public MarketAnalysis getLastAnalysis() {
        return lastAnalysis;
    }
    
    /**
     * Market analysis result.
     */
    public record MarketAnalysis(
        MarketTrend trend,
        double vix,
        double marketStrength,
        Map<String, AssetScore> assetScores,
        String recommendation
    ) {
        public List<AssetScore> getTopAssets(int limit) {
            return assetScores.values().stream()
                .sorted(Comparator.comparingDouble(AssetScore::overallScore).reversed())
                .limit(limit)
                .toList();
        }
        
        // Helper methods for WebSocket broadcasting
        public String marketTrend() {
            return trend.toString();
        }
        
        public double vixLevel() {
            return vix;
        }
        
        public String topAsset() {
            return assetScores.values().stream()
                .max(Comparator.comparingDouble(AssetScore::overallScore))
                .map(AssetScore::symbol)
                .orElse("N/A");
        }
    }
    
    /**
     * Asset scoring details.
     */
    public record AssetScore(
        String symbol,
        double price,
        double change,
        double changePercent,
        long volume,
        String trend,
        double overallScore,
        double momentumScore,
        double volatilityScore,
        double volumeScore,
        String recommendation
    ) {}
}
