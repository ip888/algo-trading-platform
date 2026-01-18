package com.trading.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Client for Alpha Vantage News Sentiment API.
 * Provides real-time sentiment analysis for financial news.
 * 
 * API Documentation: https://www.alphavantage.co/documentation/#news-sentiment
 */
public class AlphaVantageClient {
    private static final Logger logger = LoggerFactory.getLogger(AlphaVantageClient.class);
    
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String apiKey;
    private final boolean enabled;
    private final ConcurrentHashMap<String, CachedSentiment> cache;
    private final long cacheTtlMs;
    private final int newsLimit;
    private final double minRelevance;
    
    public AlphaVantageClient(String apiKey, boolean enabled, int cacheTtlMinutes, 
                             int newsLimit, double minRelevance) {
        this.apiKey = apiKey;
        this.enabled = enabled;
        this.cacheTtlMs = TimeUnit.MINUTES.toMillis(cacheTtlMinutes);
        this.newsLimit = newsLimit;
        this.minRelevance = minRelevance;
        this.cache = new ConcurrentHashMap<>();
        this.objectMapper = new ObjectMapper();
        
        this.httpClient = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build();
        
        if (enabled) {
            logger.info("🔑 Alpha Vantage Client initialized - News limit: {}, Min relevance: {}", 
                newsLimit, minRelevance);
        } else {
            logger.info("🔑 Alpha Vantage Client initialized - DISABLED (feature flag off)");
        }
    }
    
    /**
     * Analyze sentiment for a specific ticker symbol.
     * Returns weighted average sentiment from relevant news articles.
     */
    public SentimentResult analyzeSentiment(String symbol) {
        if (!enabled) {
            logger.debug("Alpha Vantage disabled, returning neutral sentiment");
            return new SentimentResult(0.0, 0.0, 0.0, "Alpha Vantage disabled");
        }
        
        // Check cache
        CachedSentiment cached = cache.get(symbol);
        if (cached != null && !cached.isExpired(cacheTtlMs)) {
            logger.debug("Using cached Alpha Vantage sentiment for {}", symbol);
            return cached.result;
        }
        
        try {
            // Call Alpha Vantage API
            SentimentResult result = callAlphaVantageAPI(symbol);
            
            // Cache result
            cache.put(symbol, new CachedSentiment(result, Instant.now()));
            
            logger.info("📊 Alpha Vantage Sentiment for {}: score={}, confidence={}, weighted={}", 
                symbol, 
                String.format("%.2f", result.sentimentScore()),
                String.format("%.2f", result.confidence()),
                String.format("%.2f", result.weightedScore()));
            
            return result;
            
        } catch (Exception e) {
            logger.error("Alpha Vantage API call failed for {}: {}", symbol, e.getMessage());
            return new SentimentResult(0.0, 0.0, 0.0, "API error: " + e.getMessage());
        }
    }
    
    /**
     * Call Alpha Vantage News Sentiment API.
     */
    private SentimentResult callAlphaVantageAPI(String symbol) throws IOException {
        String url = String.format(
            "https://www.alphavantage.co/query?function=NEWS_SENTIMENT&tickers=%s&limit=%d&apikey=%s",
            symbol, newsLimit, apiKey
        );
        
        Request request = new Request.Builder()
            .url(url)
            .get()
            .build();
        
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Alpha Vantage API error: " + response.code() + " - " + response.message());
            }
            
            String responseBody = response.body().string();
            return parseSentimentResponse(responseBody, symbol);
        }
    }
    
    /**
     * Parse Alpha Vantage API response and calculate weighted sentiment.
     * 
     * Response format:
     * {
     *   "feed": [
     *     {
     *       "title": "...",
     *       "overall_sentiment_score": 0.123,
     *       "overall_sentiment_label": "Bullish",
     *       "ticker_sentiment": [
     *         {
     *           "ticker": "SPY",
     *           "relevance_score": "0.8",
     *           "ticker_sentiment_score": "0.456",
     *           "ticker_sentiment_label": "Bullish"
     *         }
     *       ]
     *     }
     *   ]
     * }
     */
    private SentimentResult parseSentimentResponse(String jsonResponse, String symbol) throws IOException {
        JsonNode root = objectMapper.readTree(jsonResponse);
        
        // Check for API errors
        if (root.has("Information") || root.has("Error Message")) {
            String error = root.has("Information") ? 
                root.get("Information").asText() : root.get("Error Message").asText();
            throw new IOException("Alpha Vantage API error: " + error);
        }
        
        JsonNode feed = root.get("feed");
        if (feed == null || !feed.isArray() || feed.size() == 0) {
            logger.warn("No news articles found for {}", symbol);
            return new SentimentResult(0.0, 0.0, 0.0, "No news available");
        }
        
        double totalWeightedSentiment = 0.0;
        double totalRelevance = 0.0;
        int articlesProcessed = 0;
        
        // Process each news article
        for (JsonNode article : feed) {
            JsonNode tickerSentiments = article.get("ticker_sentiment");
            if (tickerSentiments == null || !tickerSentiments.isArray()) {
                continue;
            }
            
            // Find sentiment for our specific ticker
            for (JsonNode tickerSent : tickerSentiments) {
                String ticker = tickerSent.get("ticker").asText();
                if (!ticker.equalsIgnoreCase(symbol)) {
                    continue;
                }
                
                double relevance = Double.parseDouble(tickerSent.get("relevance_score").asText());
                
                // Filter out low-relevance articles
                if (relevance < minRelevance) {
                    continue;
                }
                
                double sentiment = Double.parseDouble(tickerSent.get("ticker_sentiment_score").asText());
                
                // Weight sentiment by relevance
                totalWeightedSentiment += sentiment * relevance;
                totalRelevance += relevance;
                articlesProcessed++;
                
                logger.debug("Article for {}: sentiment={}, relevance={}", 
                    symbol, String.format("%.3f", sentiment), String.format("%.3f", relevance));
            }
        }
        
        if (articlesProcessed == 0 || totalRelevance == 0) {
            logger.warn("No relevant articles found for {} (min relevance: {})", symbol, minRelevance);
            return new SentimentResult(0.0, 0.0, 0.0, "No relevant news");
        }
        
        // Calculate weighted average sentiment
        double avgSentiment = totalWeightedSentiment / totalRelevance;
        
        // Confidence is based on number of articles and total relevance
        // More articles + higher relevance = higher confidence
        double confidence = Math.min(1.0, (articlesProcessed / 10.0) * (totalRelevance / articlesProcessed));
        
        // Weighted score combines sentiment strength and confidence
        double weightedScore = avgSentiment * confidence;
        
        String interpretation = interpretSentiment(avgSentiment, confidence, articlesProcessed);
        
        return new SentimentResult(avgSentiment, confidence, weightedScore, interpretation);
    }
    
    /**
     * Interpret sentiment for logging.
     */
    private String interpretSentiment(double sentiment, double confidence, int articles) {
        String direction;
        if (sentiment > 0.3) direction = "BULLISH";
        else if (sentiment < -0.3) direction = "BEARISH";
        else direction = "NEUTRAL";
        
        String confidenceLevel;
        if (confidence > 0.7) confidenceLevel = "HIGH";
        else if (confidence > 0.4) confidenceLevel = "MEDIUM";
        else confidenceLevel = "LOW";
        
        return String.format("%s (%s confidence, %d articles)", direction, confidenceLevel, articles);
    }
    
    /**
     * Check if Alpha Vantage is enabled.
     */
    public boolean isEnabled() {
        return enabled;
    }
    
    /**
     * Get cache statistics.
     */
    public String getStats() {
        return String.format("Cache: %d symbols", cache.size());
    }
    
    // Records
    
    /**
     * Sentiment analysis result.
     */
    public record SentimentResult(
        double sentimentScore,    // -1.0 (bearish) to +1.0 (bullish)
        double confidence,        // 0.0 to 1.0 (based on articles and relevance)
        double weightedScore,     // Combined score for trading decisions
        String interpretation     // Human-readable interpretation
    ) {}
    
    /**
     * Cached sentiment result with timestamp.
     */
    private record CachedSentiment(SentimentResult result, Instant timestamp) {
        boolean isExpired(long ttlMs) {
            return Instant.now().toEpochMilli() - timestamp.toEpochMilli() > ttlMs;
        }
    }
}
