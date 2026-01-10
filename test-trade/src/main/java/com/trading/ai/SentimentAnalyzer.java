package com.trading.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.trading.api.AlpacaClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AI-powered sentiment analysis using Alpaca News API.
 * Analyzes news articles to determine market sentiment for symbols.
 */
public class SentimentAnalyzer {
    private static final Logger logger = LoggerFactory.getLogger(SentimentAnalyzer.class);
    
    private final AlpacaClient client;
    private final AlphaVantageClient alphaVantageClient;
    private final FinGPTClient finGPTClient;
    private final Map<String, CachedSentiment> sentimentCache;
    private static final long CACHE_TTL_MS = 5 * 60 * 1000; // 5 minutes
    
    // Sentiment keywords (fallback when FinGPT is disabled or fails)
    private static final Set<String> POSITIVE_KEYWORDS = Set.of(
        "surge", "rally", "bullish", "gains", "upgrade", "beat", "strong",
        "growth", "profit", "outperform", "buy", "positive", "rise", "soar"
    );
    
    private static final Set<String> NEGATIVE_KEYWORDS = Set.of(
        "plunge", "crash", "bearish", "losses", "downgrade", "miss", "weak",
        "decline", "loss", "underperform", "sell", "negative", "fall", "drop"
    );
    
    public SentimentAnalyzer(AlpacaClient client, AlphaVantageClient alphaVantageClient, 
                            FinGPTClient finGPTClient) {
        this.client = client;
        this.alphaVantageClient = alphaVantageClient;
        this.finGPTClient = finGPTClient;
        this.sentimentCache = new ConcurrentHashMap<>();
        
        String provider = alphaVantageClient.isEnabled() ? "Alpha Vantage" : 
                         finGPTClient.isEnabled() ? "FinGPT" : "Keywords Only";
        logger.info("🧠 SentimentAnalyzer initialized (Provider: {})", provider);
    }
    
    /**
     * Get sentiment score for a symbol (-1.0 to +1.0).
     * Positive = bullish, Negative = bearish, 0 = neutral
     * Uses FinGPT if enabled, falls back to keyword analysis.
     */
    public double getSentimentScore(String symbol) {
        // Check cache first
        CachedSentiment cached = sentimentCache.get(symbol);
        if (cached != null && !cached.isExpired()) {
            logger.debug("Using cached sentiment for {}: {}", symbol, cached.score);
            return cached.score;
        }
        
        try {
            // Fetch recent news
            List<NewsArticle> news = getRecentNews(symbol, 10);
            
            if (news.isEmpty()) {
                logger.debug("No news found for {}, returning neutral sentiment", symbol);
                return 0.0;
            }
            
            // Calculate sentiment (FinGPT or keyword-based)
            double score = calculateSentiment(news, symbol);
            
            // Cache result
            sentimentCache.put(symbol, new CachedSentiment(score, Instant.now()));
            
            logger.info("📰 Sentiment for {}: {} (from {} articles)", 
                symbol, formatScore(score), news.size());
            
            return score;
            
        } catch (Exception e) {
            logger.error("Failed to get sentiment for {}", symbol, e);
            return 0.0; // Neutral on error
        }
    }
    
    /**
     * Get enhanced sentiment with confidence score.
     * Only available when FinGPT is enabled.
     */
    public FinGPTClient.SentimentResult getEnhancedSentiment(String symbol) {
        if (!finGPTClient.isEnabled()) {
            // Return basic sentiment wrapped in result
            double score = getSentimentScore(symbol);
            return new FinGPTClient.SentimentResult(
                score, 0.5, score * 0.5, "Keyword-based (FinGPT disabled)"
            );
        }
        
        try {
            // Fetch recent news
            List<NewsArticle> news = getRecentNews(symbol, 10);
            
            if (news.isEmpty()) {
                return new FinGPTClient.SentimentResult(
                    0.0, 0.0, 0.0, "No news available"
                );
            }
            
            // Combine all news into single text for FinGPT
            StringBuilder newsText = new StringBuilder();
            for (NewsArticle article : news) {
                newsText.append(article.headline).append(". ");
                if (!article.summary.isEmpty()) {
                    newsText.append(article.summary).append(" ");
                }
            }
            
            // Analyze with FinGPT
            return finGPTClient.analyzeSentiment(newsText.toString(), symbol);
            
        } catch (Exception e) {
            logger.error("Failed to get enhanced sentiment for {}", symbol, e);
            return new FinGPTClient.SentimentResult(
                0.0, 0.0, 0.0, "Error: " + e.getMessage()
            );
        }
    }
    
    /**
     * Check if sentiment supports the trade direction.
     * Returns true if sentiment is neutral or aligned with trade direction.
     * Only blocks if sentiment is STRONGLY opposite (to avoid false negatives).
     */
    public boolean isSentimentPositive(String symbol, boolean isBullish) {
        double score = getSentimentScore(symbol);
        
        // For bullish trades, only block if sentiment is STRONGLY bearish
        if (isBullish) {
            return score > -0.4; // Allow neutral and mildly bearish, block only strong bearish
        } else {
            // For bearish trades, only block if sentiment is STRONGLY bullish
            return score < 0.4; // Allow neutral and mildly bullish, block only strong bullish
        }
    }
    
    /**
     * Get recent news articles for symbol.
     */
    private List<NewsArticle> getRecentNews(String symbol, int limit) {
        try {
            JsonNode newsData = client.getNews(symbol, limit);
            List<NewsArticle> articles = new ArrayList<>();
            
            if (newsData != null && newsData.has("news")) {
                for (JsonNode article : newsData.get("news")) {
                    String headline = article.has("headline") ? article.get("headline").asText() : "";
                    String summary = article.has("summary") ? article.get("summary").asText() : "";
                    
                    articles.add(new NewsArticle(headline, summary));
                }
            }
            
            return articles;
            
        } catch (Exception e) {
            logger.warn("Failed to fetch news for {}: {}", symbol, e.getMessage());
            return Collections.emptyList();
        }
    }
    
    /**
     * Calculate sentiment score from news articles.
     * Fallback chain: Alpha Vantage → FinGPT → Keywords
     */
    private double calculateSentiment(List<NewsArticle> articles, String symbol) {
        // Try Alpha Vantage first if enabled
        if (alphaVantageClient.isEnabled()) {
            try {
                AlphaVantageClient.SentimentResult result = 
                    alphaVantageClient.analyzeSentiment(symbol);
                
                logger.debug("Alpha Vantage sentiment for {}: {}", symbol, result.interpretation());
                return result.sentimentScore();
                
            } catch (Exception e) {
                logger.warn("Alpha Vantage failed for {}, trying FinGPT: {}", 
                    symbol, e.getMessage());
                // Fall through to FinGPT
            }
        }
        
        // Try FinGPT if enabled
        if (finGPTClient.isEnabled()) {
            try {
                // Combine articles into text
                StringBuilder newsText = new StringBuilder();
                for (NewsArticle article : articles) {
                    newsText.append(article.headline).append(". ");
                    if (!article.summary.isEmpty()) {
                        newsText.append(article.summary).append(" ");
                    }
                }
                
                // Get FinGPT sentiment
                FinGPTClient.SentimentResult result = 
                    finGPTClient.analyzeSentiment(newsText.toString(), symbol);
                
                logger.debug("FinGPT sentiment for {}: {}", symbol, result.interpretation());
                return result.sentimentScore();
                
            } catch (Exception e) {
                logger.warn("FinGPT failed for {}, falling back to keywords: {}", 
                    symbol, e.getMessage());
                // Fall through to keyword analysis
            }
        }
        
        // Fallback: Keyword-based sentiment analysis
        return calculateKeywordSentiment(articles);
    }
    
    /**
     * Calculate sentiment using keyword matching (fallback method).
     */
    private double calculateKeywordSentiment(List<NewsArticle> articles) {
        int positiveCount = 0;
        int negativeCount = 0;
        
        for (NewsArticle article : articles) {
            String text = (article.headline + " " + article.summary).toLowerCase();
            
            // Count positive keywords
            for (String keyword : POSITIVE_KEYWORDS) {
                if (text.contains(keyword)) {
                    positiveCount++;
                }
            }
            
            // Count negative keywords
            for (String keyword : NEGATIVE_KEYWORDS) {
                if (text.contains(keyword)) {
                    negativeCount++;
                }
            }
        }
        
        // Calculate normalized score
        int total = positiveCount + negativeCount;
        if (total == 0) {
            return 0.0; // Neutral
        }
        
        double score = (double) (positiveCount - negativeCount) / total;
        return Math.max(-1.0, Math.min(1.0, score)); // Clamp to [-1, 1]
    }
    
    /**
     * Format score for logging.
     */
    private String formatScore(double score) {
        if (score > 0.5) return String.format("VERY BULLISH (%.2f)", score);
        if (score > 0.2) return String.format("BULLISH (%.2f)", score);
        if (score > -0.2) return String.format("NEUTRAL (%.2f)", score);
        if (score > -0.5) return String.format("BEARISH (%.2f)", score);
        return String.format("VERY BEARISH (%.2f)", score);
    }
    
    /**
     * Get sentiment statistics.
     */
    public String getStats() {
        return String.format("Cached: %d symbols", sentimentCache.size());
    }
    
    // Inner classes
    private static class NewsArticle {
        final String headline;
        final String summary;
        
        NewsArticle(String headline, String summary) {
            this.headline = headline;
            this.summary = summary;
        }
    }
    
    private static class CachedSentiment {
        final double score;
        final Instant timestamp;
        
        CachedSentiment(double score, Instant timestamp) {
            this.score = score;
            this.timestamp = timestamp;
        }
        
        boolean isExpired() {
            return Instant.now().toEpochMilli() - timestamp.toEpochMilli() > CACHE_TTL_MS;
        }
    }
}
