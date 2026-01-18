package com.trading.autonomous;

import com.trading.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Autonomous error detection system that monitors, classifies, and tracks errors.
 * Provides root cause analysis and suggests fixes.
 */
public class ErrorDetector {
    private static final Logger logger = LoggerFactory.getLogger(ErrorDetector.class);
    
    // Error tracking
    private final Map<String, AtomicInteger> errorCounts = new ConcurrentHashMap<>();
    private final Map<String, Instant> lastOccurrence = new ConcurrentHashMap<>();
    private final Map<String, ErrorPattern> knownPatterns = new ConcurrentHashMap<>();
    
    // Thresholds
    private static final int HIGH_FREQUENCY_THRESHOLD = 5; // 5 errors in window
    private static final int CRITICAL_FREQUENCY_THRESHOLD = 10;
    private static final long TIME_WINDOW_MS = 3600_000; // 1 hour
    
    public ErrorDetector() {
        initializeKnownPatterns();
        logger.info("🔍 ErrorDetector initialized with {} known patterns", knownPatterns.size());
    }
    
    /**
     * Analyze an error and determine if self-healing is needed.
     */
    public ErrorAnalysis analyze(Exception error, String context) {
        String errorMessage = error.getMessage();
        String errorType = error.getClass().getSimpleName();
        
        // Match against known patterns
        ErrorPattern pattern = matchPattern(errorMessage);
        
        // Track frequency
        String key = pattern != null ? pattern.name() : errorType;
        errorCounts.computeIfAbsent(key, k -> new AtomicInteger(0)).incrementAndGet();
        lastOccurrence.put(key, Instant.now());
        
        // Classify severity
        Severity severity = classifySeverity(key, pattern);
        
        // Determine if auto-healing should trigger
        boolean shouldHeal = shouldTriggerHealing(key, severity);
        
        logger.info("🔍 Error detected: {} | Pattern: {} | Severity: {} | Count: {} | Auto-heal: {}", 
            errorType, 
            pattern != null ? pattern.name() : "UNKNOWN",
            severity,
            errorCounts.get(key).get(),
            shouldHeal);
        
        return new ErrorAnalysis(
            errorType,
            errorMessage,
            pattern,
            severity,
            errorCounts.get(key).get(),
            shouldHeal,
            context
        );
    }
    
    /**
     * Initialize known error patterns with fixes.
     */
    private void initializeKnownPatterns() {
        // Insufficient buying power
        knownPatterns.put("INSUFFICIENT_BUYING_POWER", new ErrorPattern(
            "INSUFFICIENT_BUYING_POWER",
            "insufficient buying power",
            Severity.HIGH,
            "Reduce POSITION_SIZING_KELLY_FRACTION by 20%",
            Map.of("POSITION_SIZING_KELLY_FRACTION", "multiply:0.8")
        ));
        
        // API rate limit
        knownPatterns.put("RATE_LIMIT", new ErrorPattern(
            "RATE_LIMIT",
            "rate limit|429|too many requests",
            Severity.MEDIUM,
            "Increase API_REQUEST_DELAY_MS by 50%",
            Map.of("API_REQUEST_DELAY_MS", "multiply:1.5")
        ));
        
        // Order rejection
        knownPatterns.put("ORDER_REJECTED", new ErrorPattern(
            "ORDER_REJECTED",
            "order.*rejected|invalid.*order",
            Severity.MEDIUM,
            "Reduce position sizes and use market orders",
            Map.of(
                "POSITION_SIZING_KELLY_FRACTION", "multiply:0.9",
                "USE_LIMIT_ORDERS", "false"
            )
        ));
        
        // PDT violation risk
        knownPatterns.put("PDT_RISK", new ErrorPattern(
            "PDT_RISK",
            "day trade|pdt|pattern day trader",
            Severity.HIGH,
            "Increase minimum hold time",
            Map.of("MIN_HOLD_TIME_HOURS", "add:4")
        ));
        
        // High volatility
        knownPatterns.put("HIGH_VOLATILITY", new ErrorPattern(
            "HIGH_VOLATILITY",
            "volatility.*high|vix.*above",
            Severity.MEDIUM,
            "Reduce position sizing in volatile conditions",
            Map.of("POSITION_SIZING_KELLY_FRACTION", "multiply:0.7")
        ));
        
        // Connection errors
        knownPatterns.put("CONNECTION_ERROR", new ErrorPattern(
            "CONNECTION_ERROR",
            "connection.*failed|timeout|network",
            Severity.LOW,
            "Increase retry delays",
            Map.of("API_REQUEST_DELAY_MS", "add:200")
        ));
        
        // Minimum order amount (NEW)
        knownPatterns.put("MINIMUM_ORDER_AMOUNT", new ErrorPattern(
            "MINIMUM_ORDER_AMOUNT",
            "minimal amount of order|cost basis must be|minimum.*\\$1",
            Severity.LOW,
            "Skip orders under $1.00 - Recommend: Increase capital for better position sizing",
            Map.of() // No config changes needed - handled in code
        ));
    }
    
    /**
     * Match error message against known patterns.
     */
    private ErrorPattern matchPattern(String errorMessage) {
        if (errorMessage == null) return null;
        
        String lowerMessage = errorMessage.toLowerCase();
        for (ErrorPattern pattern : knownPatterns.values()) {
            if (lowerMessage.matches(".*(" + pattern.regex() + ").*")) {
                return pattern;
            }
        }
        return null;
    }
    
    /**
     * Classify error severity based on frequency and pattern.
     */
    private Severity classifySeverity(String key, ErrorPattern pattern) {
        int count = errorCounts.get(key).get();
        
        // Critical if very frequent
        if (count >= CRITICAL_FREQUENCY_THRESHOLD) {
            return Severity.CRITICAL;
        }
        
        // Use pattern severity if available
        if (pattern != null) {
            // Upgrade severity if frequent
            if (count >= HIGH_FREQUENCY_THRESHOLD && pattern.severity() == Severity.MEDIUM) {
                return Severity.HIGH;
            }
            return pattern.severity();
        }
        
        // Default based on frequency
        if (count >= HIGH_FREQUENCY_THRESHOLD) {
            return Severity.HIGH;
        }
        return Severity.LOW;
    }
    
    /**
     * Determine if auto-healing should trigger.
     */
    private boolean shouldTriggerHealing(String key, Severity severity) {
        int count = errorCounts.get(key).get();
        
        // Critical errors: heal after 3 occurrences
        if (severity == Severity.CRITICAL && count >= 3) {
            return true;
        }
        
        // High severity: heal after 5 occurrences
        if (severity == Severity.HIGH && count >= 5) {
            return true;
        }
        
        // Medium severity: heal after 10 occurrences
        if (severity == Severity.MEDIUM && count >= 10) {
            return true;
        }
        
        // Low severity: don't auto-heal
        return false;
    }
    
    /**
     * Get error statistics for dashboard.
     */
    public Map<String, Object> getStatistics() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("totalErrors", errorCounts.values().stream()
            .mapToInt(AtomicInteger::get).sum());
        stats.put("uniqueErrors", errorCounts.size());
        stats.put("knownPatterns", knownPatterns.size());
        
        // Top errors
        List<Map.Entry<String, AtomicInteger>> topErrors = errorCounts.entrySet().stream()
            .sorted((a, b) -> b.getValue().get() - a.getValue().get())
            .limit(5)
            .toList();
        stats.put("topErrors", topErrors);
        
        return stats;
    }
    
    /**
     * Reset error counts (for testing or after successful healing).
     */
    public void resetCounts(String errorKey) {
        errorCounts.remove(errorKey);
        lastOccurrence.remove(errorKey);
        logger.info("🔄 Reset error counts for: {}", errorKey);
    }
    
    // Inner classes
    public record ErrorPattern(
        String name,
        String regex,
        Severity severity,
        String suggestedFix,
        Map<String, String> configAdjustments
    ) {}
    
    public record ErrorAnalysis(
        String errorType,
        String errorMessage,
        ErrorPattern pattern,
        Severity severity,
        int occurrenceCount,
        boolean shouldHeal,
        String context
    ) {
        public String getSummary() {
            return String.format("[%s] %s (Count: %d, Severity: %s)", 
                errorType, 
                pattern != null ? pattern.name() : "UNKNOWN",
                occurrenceCount,
                severity);
        }
    }
    
    public enum Severity {
        LOW, MEDIUM, HIGH, CRITICAL
    }
}
