package com.trading.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Anomaly detection for unusual market behavior.
 * Tracks normal ranges and alerts when metrics exceed thresholds.
 */
public class AnomalyDetector {
    private static final Logger logger = LoggerFactory.getLogger(AnomalyDetector.class);
    
    // Thresholds
    private static final double VIX_SPIKE_THRESHOLD = 0.5; // 50% increase
    private static final double VOLUME_SURGE_THRESHOLD = 3.0; // 3x average
    private static final double PRICE_GAP_THRESHOLD = 0.05; // 5%
    private static final double RAPID_DRAWDOWN_THRESHOLD = 0.10; // 10%
    
    // Baseline values
    private final Map<String, Double> baselines;
    private int anomalyCount = 0;
    private double currentSeverity = 0.0;
    
    public AnomalyDetector() {
        this.baselines = new HashMap<>();
        logger.info("🔍 AnomalyDetector initialized");
    }
    
    /**
     * Check if a metric value is anomalous.
     */
    public boolean isAnomaly(String metric, double value) {
        double baseline = baselines.getOrDefault(metric, value);
        
        boolean anomalous = switch (metric.toLowerCase()) {
            case "vix" -> isVixAnomaly(value, baseline);
            case "volume" -> isVolumeAnomaly(value, baseline);
            case "price_gap" -> isPriceGapAnomaly(value);
            case "drawdown" -> isDrawdownAnomaly(value);
            default -> false;
        };
        
        if (anomalous) {
            anomalyCount++;
            updateSeverity(metric, value, baseline);
            logger.warn("⚠️ ANOMALY DETECTED: {} = {} (baseline: {})", 
                metric, String.format("%.2f", value), String.format("%.2f", baseline));
        }
        
        // Update baseline with exponential moving average
        baselines.put(metric, baseline * 0.9 + value * 0.1);
        
        return anomalous;
    }
    
    /**
     * Get current anomaly severity (0-100).
     */
    public int getAnomalySeverity() {
        return (int) Math.min(100, currentSeverity);
    }
    
    /**
     * Get recommended action based on anomaly severity.
     */
    public AnomalyAction getRecommendedAction() {
        if (currentSeverity > 75) {
            return AnomalyAction.HALT_TRADING;
        } else if (currentSeverity > 50) {
            return AnomalyAction.REDUCE_SIZE;
        } else if (currentSeverity > 25) {
            return AnomalyAction.TIGHTEN_STOPS;
        }
        return AnomalyAction.CONTINUE;
    }
    
    /**
     * Reset anomaly tracking.
     */
    public void reset() {
        anomalyCount = 0;
        currentSeverity = 0.0;
        logger.info("Anomaly detector reset");
    }
    
    /**
     * Check for VIX spike anomaly.
     */
    private boolean isVixAnomaly(double current, double baseline) {
        if (baseline == 0) return false;
        double change = (current - baseline) / baseline;
        return change > VIX_SPIKE_THRESHOLD;
    }
    
    /**
     * Check for volume surge anomaly.
     */
    private boolean isVolumeAnomaly(double current, double baseline) {
        if (baseline == 0) return false;
        return current > baseline * VOLUME_SURGE_THRESHOLD;
    }
    
    /**
     * Check for price gap anomaly.
     */
    private boolean isPriceGapAnomaly(double gapPercent) {
        return Math.abs(gapPercent) > PRICE_GAP_THRESHOLD;
    }
    
    /**
     * Check for rapid drawdown anomaly.
     */
    private boolean isDrawdownAnomaly(double drawdown) {
        return drawdown > RAPID_DRAWDOWN_THRESHOLD;
    }
    
    /**
     * Update severity score based on anomaly.
     */
    private void updateSeverity(String metric, double value, double baseline) {
        double severity = switch (metric.toLowerCase()) {
            case "vix" -> calculateVixSeverity(value, baseline);
            case "volume" -> calculateVolumeSeverity(value, baseline);
            case "price_gap" -> Math.abs(value) * 1000; // Convert to 0-100 scale
            case "drawdown" -> value * 100; // Already in percentage
            default -> 0.0;
        };
        
        // Exponential moving average of severity
        currentSeverity = currentSeverity * 0.7 + severity * 0.3;
    }
    
    private double calculateVixSeverity(double current, double baseline) {
        if (baseline == 0) return 0;
        double change = (current - baseline) / baseline;
        return Math.min(100, change * 100);
    }
    
    private double calculateVolumeSeverity(double current, double baseline) {
        if (baseline == 0) return 0;
        double ratio = current / baseline;
        return Math.min(100, (ratio - 1) * 50);
    }
    
    /**
     * Get anomaly statistics.
     */
    public String getStats() {
        return String.format("Anomalies: %d, Severity: %d, Action: %s", 
            anomalyCount, getAnomalySeverity(), getRecommendedAction());
    }
    
    /**
     * Recommended actions based on anomaly severity.
     */
    public enum AnomalyAction {
        CONTINUE,        // Normal operation
        TIGHTEN_STOPS,   // Reduce stop-loss distance
        REDUCE_SIZE,     // Cut position sizes 50%
        HALT_TRADING     // Stop new positions
    }
}
