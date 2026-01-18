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
 * Client for HuggingFace Inference API to access FinGPT models.
 * Provides confidence-weighted sentiment analysis for financial news.
 */
public class FinGPTClient {
    private static final Logger logger = LoggerFactory.getLogger(FinGPTClient.class);
    
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String apiToken;
    private final String sentimentModel;
    private final boolean enabled;
    private final ConcurrentHashMap<String, CachedSentiment> cache;
    private final long cacheTtlMs;
    
    // Confidence weighting: 60% model confidence + 40% sentiment strength
    private static final double CONFIDENCE_WEIGHT = 0.6;
    private static final double SENTIMENT_WEIGHT = 0.4;
    
    public FinGPTClient(String apiToken, String sentimentModel, boolean enabled, int cacheTtlMinutes) {
        this.apiToken = apiToken;
        this.sentimentModel = sentimentModel;
        this.enabled = enabled;
        this.cacheTtlMs = TimeUnit.MINUTES.toMillis(cacheTtlMinutes);
        this.cache = new ConcurrentHashMap<>();
        this.objectMapper = new ObjectMapper();
        
        this.httpClient = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build();
        
        if (enabled) {
            logger.info("🤖 FinGPT Client initialized - Model: {}", sentimentModel);
        } else {
            logger.info("🤖 FinGPT Client initialized - DISABLED (feature flag off)");
        }
    }
    
    /**
     * Analyze sentiment of financial news text.
     * Returns confidence-weighted sentiment score.
     */
    public SentimentResult analyzeSentiment(String text, String symbol) {
        if (!enabled) {
            logger.debug("FinGPT disabled, returning neutral sentiment");
            return new SentimentResult(0.0, 0.0, 0.0, "FinGPT disabled");
        }
        
        // Check cache
        String cacheKey = symbol + ":" + text.hashCode();
        CachedSentiment cached = cache.get(cacheKey);
        if (cached != null && !cached.isExpired(cacheTtlMs)) {
            logger.debug("Using cached FinGPT sentiment for {}", symbol);
            return cached.result;
        }
        
        try {
            // Call HuggingFace Inference API
            SentimentResult result = callHuggingFaceAPI(text);
            
            // Cache result
            cache.put(cacheKey, new CachedSentiment(result, Instant.now()));
            
            logger.info("📊 FinGPT Sentiment for {}: score={:.2f}, confidence={:.2f}, weighted={:.2f}", 
                symbol, result.sentimentScore(), result.confidence(), result.weightedScore());
            
            return result;
            
        } catch (Exception e) {
            logger.error("FinGPT API call failed for {}: {}", symbol, e.getMessage());
            return new SentimentResult(0.0, 0.0, 0.0, "API error: " + e.getMessage());
        }
    }
    
    /**
     * Call HuggingFace Inference API for sentiment analysis.
     */
    private SentimentResult callHuggingFaceAPI(String text) throws IOException {
        String url = "https://api-inference.huggingface.co/models/" + sentimentModel;
        
        // Prepare request body
        String jsonBody = objectMapper.writeValueAsString(
            new HuggingFaceRequest(text)
        );
        
        RequestBody body = RequestBody.create(
            jsonBody,
            MediaType.parse("application/json")
        );
        
        Request request = new Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer " + apiToken)
            .addHeader("Content-Type", "application/json")
            .post(body)
            .build();
        
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("HuggingFace API error: " + response.code() + " - " + response.message());
            }
            
            String responseBody = response.body().string();
            return parseSentimentResponse(responseBody);
        }
    }
    
    /**
     * Parse HuggingFace API response and calculate confidence-weighted score.
     */
    private SentimentResult parseSentimentResponse(String jsonResponse) throws IOException {
        JsonNode root = objectMapper.readTree(jsonResponse);
        
        // HuggingFace returns array of label/score pairs
        // Example: [{"label": "positive", "score": 0.95}, {"label": "negative", "score": 0.05}]
        if (root.isArray() && root.size() > 0) {
            JsonNode firstResult = root.get(0);
            
            // Handle both array of results and single result
            JsonNode results = firstResult.isArray() ? firstResult : root;
            
            double positiveScore = 0.0;
            double negativeScore = 0.0;
            double neutralScore = 0.0;
            double maxConfidence = 0.0;
            
            for (JsonNode item : results) {
                String label = item.get("label").asText().toLowerCase();
                double score = item.get("score").asDouble();
                
                maxConfidence = Math.max(maxConfidence, score);
                
                if (label.contains("positive") || label.contains("bullish")) {
                    positiveScore = score;
                } else if (label.contains("negative") || label.contains("bearish")) {
                    negativeScore = score;
                } else if (label.contains("neutral")) {
                    neutralScore = score;
                }
            }
            
            // Calculate sentiment score: -1 (bearish) to +1 (bullish)
            double sentimentScore = positiveScore - negativeScore;
            
            // Confidence is the max score (how sure the model is)
            double confidence = maxConfidence;
            
            // Weighted score: 60% confidence + 40% sentiment strength
            double weightedScore = (CONFIDENCE_WEIGHT * confidence) + 
                                  (SENTIMENT_WEIGHT * Math.abs(sentimentScore));
            
            // Apply sentiment direction to weighted score
            weightedScore = sentimentScore >= 0 ? weightedScore : -weightedScore;
            
            String interpretation = interpretSentiment(sentimentScore, confidence);
            
            return new SentimentResult(sentimentScore, confidence, weightedScore, interpretation);
        }
        
        throw new IOException("Unexpected API response format");
    }
    
    /**
     * Interpret sentiment for logging.
     */
    private String interpretSentiment(double sentiment, double confidence) {
        String direction;
        if (sentiment > 0.3) direction = "BULLISH";
        else if (sentiment < -0.3) direction = "BEARISH";
        else direction = "NEUTRAL";
        
        String confidenceLevel;
        if (confidence > 0.8) confidenceLevel = "HIGH";
        else if (confidence > 0.5) confidenceLevel = "MEDIUM";
        else confidenceLevel = "LOW";
        
        return String.format("%s (%s confidence)", direction, confidenceLevel);
    }
    
    /**
     * Check if FinGPT is enabled.
     */
    public boolean isEnabled() {
        return enabled;
    }
    
    /**
     * Get cache statistics.
     */
    public String getStats() {
        return String.format("Cache: %d entries", cache.size());
    }
    
    // Records
    
    /**
     * Sentiment analysis result with confidence weighting.
     */
    public record SentimentResult(
        double sentimentScore,    // -1.0 (bearish) to +1.0 (bullish)
        double confidence,        // 0.0 to 1.0 (model confidence)
        double weightedScore,     // Combined score for trading decisions
        String interpretation     // Human-readable interpretation
    ) {}
    
    /**
     * HuggingFace API request format.
     */
    private record HuggingFaceRequest(String inputs) {}
    
    /**
     * Cached sentiment result with timestamp.
     */
    private record CachedSentiment(SentimentResult result, Instant timestamp) {
        boolean isExpired(long ttlMs) {
            return Instant.now().toEpochMilli() - timestamp.toEpochMilli() > ttlMs;
        }
    }
}
