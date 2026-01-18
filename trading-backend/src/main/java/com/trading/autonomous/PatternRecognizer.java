package com.trading.autonomous;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Recognizes profitable trading patterns and assigns confidence scores.
 * Learns which setups have highest probability of success.
 */
public class PatternRecognizer {
    private static final Logger logger = LoggerFactory.getLogger(PatternRecognizer.class);
    
    // Pattern performance tracking
    private final Map<String, PatternStats> patternPerformance = new ConcurrentHashMap<>();
    
    public PatternRecognizer() {
        logger.info("📊 PatternRecognizer initialized - Pattern learning enabled");
    }
    
    /**
     * Detect and score trading setup patterns.
     */
    public int detectPattern(String symbol, double vix, long volume, long avgVolume,
                            int hour, boolean isPreMarket, boolean isPostMarket) {
        
        List<String> detectedPatterns = new ArrayList<>();
        
        // Pattern 1: Morning gap with high volume
        if (hour >= 9 && hour <= 10 && volume > avgVolume * 1.5) {
            detectedPatterns.add("MORNING_GAP_HIGH_VOLUME");
        }
        
        // Pattern 2: VIX spike + inverse ETF
        if (vix > 25 && isInverseETF(symbol)) {
            detectedPatterns.add("VIX_SPIKE_INVERSE");
        }
        
        // Pattern 3: Extended hours low volume (avoid)
        if ((isPreMarket || isPostMarket) && volume < avgVolume * 0.3) {
            detectedPatterns.add("EXTENDED_HOURS_LOW_VOLUME");
        }
        
        // Pattern 4: High volatility + high volume
        if (vix > 30 && volume > avgVolume * 2.0) {
            detectedPatterns.add("HIGH_VOL_HIGH_VOLUME");
        }
        
        // Pattern 5: Friday afternoon (often choppy)
        if (hour >= 14 && LocalDate.now().getDayOfWeek() == DayOfWeek.FRIDAY) {
            detectedPatterns.add("FRIDAY_AFTERNOON");
        }
        
        // Calculate confidence score
        int confidence = calculateConfidence(detectedPatterns);
        
        if (!detectedPatterns.isEmpty()) {
            logger.debug("📊 PATTERNS: Detected {} - Confidence: {}%",
                detectedPatterns, confidence);
        }
        
        return confidence;
    }
    
    /**
     * Record pattern outcome to learn from it.
     */
    public void recordPatternOutcome(String pattern, boolean isWin) {
        patternPerformance.computeIfAbsent(pattern, k -> new PatternStats()).record(isWin);
        
        PatternStats stats = patternPerformance.get(pattern);
        if (stats.getTotalOccurrences() % 10 == 0) {
            logger.info("📊 PATTERN LEARNING: {} - {}% win rate ({} occurrences)",
                pattern, String.format("%.1f", stats.getWinRate() * 100), stats.getTotalOccurrences());
        }
    }
    
    /**
     * Calculate confidence score based on detected patterns.
     */
    private int calculateConfidence(List<String> patterns) {
        if (patterns.isEmpty()) {
            return 50; // Neutral
        }
        
        int totalConfidence = 0;
        int count = 0;
        
        for (String pattern : patterns) {
            PatternStats stats = patternPerformance.get(pattern);
            if (stats != null && stats.getTotalOccurrences() >= 5) {
                // Use learned win rate
                totalConfidence += (int) (stats.getWinRate() * 100);
                count++;
            } else {
                // Use default confidence for new patterns
                totalConfidence += getDefaultConfidence(pattern);
                count++;
            }
        }
        
        return count > 0 ? totalConfidence / count : 50;
    }
    
    /**
     * Get default confidence for patterns before learning.
     */
    private int getDefaultConfidence(String pattern) {
        return switch (pattern) {
            case "MORNING_GAP_HIGH_VOLUME" -> 70; // High confidence
            case "VIX_SPIKE_INVERSE" -> 75; // Very high confidence
            case "EXTENDED_HOURS_LOW_VOLUME" -> 30; // Low confidence (avoid)
            case "HIGH_VOL_HIGH_VOLUME" -> 65; // Good confidence
            case "FRIDAY_AFTERNOON" -> 40; // Low confidence (avoid)
            default -> 50; // Neutral
        };
    }
    
    /**
     * Check if symbol is an inverse ETF.
     */
    private boolean isInverseETF(String symbol) {
        Set<String> inverseETFs = Set.of(
            "SH", "PSQ", "DOG", "RWM", "ERY", "FAZ", "GLL", "ZSL",
            "TBT", "EUM", "SRS", "SDP", "SCC", "SSG", "BIS", "SZK",
            "MYY", "YANG", "TZA", "UVXY", "VIXY"
        );
        return inverseETFs.contains(symbol);
    }
    
    /**
     * Get pattern statistics report.
     */
    public String getPatternReport() {
        StringBuilder report = new StringBuilder();
        report.append("Pattern Performance:\n");
        
        patternPerformance.entrySet().stream()
            .filter(e -> e.getValue().getTotalOccurrences() >= 3)
            .sorted((a, b) -> Double.compare(
                b.getValue().getWinRate(), a.getValue().getWinRate()))
            .forEach(e -> report.append(String.format("  %s: %.1f%% (%d times)\n",
                e.getKey(), e.getValue().getWinRate() * 100, 
                e.getValue().getTotalOccurrences())));
        
        return report.toString();
    }
    
    // Inner class
    private static class PatternStats {
        private int wins = 0;
        private int losses = 0;
        
        void record(boolean isWin) {
            if (isWin) wins++;
            else losses++;
        }
        
        double getWinRate() {
            int total = wins + losses;
            return total > 0 ? (double) wins / total : 0.0;
        }
        
        int getTotalOccurrences() {
            return wins + losses;
        }
    }
}
