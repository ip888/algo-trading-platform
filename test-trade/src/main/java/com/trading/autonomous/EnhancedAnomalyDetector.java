package com.trading.autonomous;

import com.trading.config.Config;
import com.trading.websocket.TradingWebSocketHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/**
 * Enhanced anomaly detector with statistical analysis and market event detection.
 * Triggers safe mode when unusual conditions are detected.
 */
public class EnhancedAnomalyDetector {
    private static final Logger logger = LoggerFactory.getLogger(EnhancedAnomalyDetector.class);
    
    private final Config config;
    private final SafeModeManager safeModeManager;
    
    // Statistical tracking
    private final Map<String, MetricHistory> metricHistories = new ConcurrentHashMap<>();
    private final int HISTORY_SIZE = 100; // Track last 100 data points
    
    // Anomaly thresholds
    private static final double Z_SCORE_THRESHOLD = 3.0; // 3 standard deviations
    private static final double PRICE_SPIKE_THRESHOLD = 0.05; // 5% sudden movement
    private static final double VOLUME_SPIKE_THRESHOLD = 10.0; // 10x average volume
    private static final double ERROR_RATE_THRESHOLD = 0.10; // 10% error rate
    
    public EnhancedAnomalyDetector(Config config) {
        this.config = config;
        this.safeModeManager = new SafeModeManager(config);
        logger.info("🔍 EnhancedAnomalyDetector initialized with statistical analysis");
    }
    
    /**
     * Check if a metric value is anomalous using statistical analysis.
     */
    public AnomalyResult checkAnomaly(String metricName, double value) {
        MetricHistory history = metricHistories.computeIfAbsent(
            metricName, 
            k -> new MetricHistory(HISTORY_SIZE)
        );
        
        // Calculate statistics
        double mean = history.getMean();
        double stdDev = history.getStdDev();
        double zScore = stdDev > 0 ? Math.abs((value - mean) / stdDev) : 0;
        
        // Add to history
        history.add(value);
        
        // Check for anomaly
        if (zScore > Z_SCORE_THRESHOLD) {
            logger.warn("🚨 ANOMALY DETECTED: {} = {} (Z-score: {}, Mean: {}, StdDev: {})",
                metricName, value, String.format("%.2f", zScore),
                String.format("%.2f", mean), String.format("%.2f", stdDev));
            
            AnomalyType type = classifyAnomaly(metricName, value, mean);
            return new AnomalyResult(true, type, zScore, value, mean);
        }
        
        return new AnomalyResult(false, null, zScore, value, mean);
    }
    
    /**
     * Detect market events (flash crash, circuit breakers, etc.)
     */
    public MarketEventResult detectMarketEvent(String symbol, double currentPrice, 
                                               double previousPrice, long currentVolume, 
                                               long averageVolume) {
        
        // 1. Flash crash detection (>5% drop in short time)
        double priceChange = (currentPrice - previousPrice) / previousPrice;
        if (priceChange < -PRICE_SPIKE_THRESHOLD) {
            logger.error("🚨 FLASH CRASH DETECTED: {} dropped {}%", 
                symbol, String.format("%.2f", priceChange * 100));
            return new MarketEventResult(
                MarketEventType.FLASH_CRASH, 
                symbol, 
                priceChange,
                "Price dropped " + String.format("%.2f%%", priceChange * 100)
            );
        }
        
        // 2. Unusual spike detection (>5% jump)
        if (priceChange > PRICE_SPIKE_THRESHOLD) {
            logger.warn("⚠️ PRICE SPIKE DETECTED: {} jumped {}%", 
                symbol, String.format("%.2f", priceChange * 100));
            return new MarketEventResult(
                MarketEventType.PRICE_SPIKE, 
                symbol, 
                priceChange,
                "Price jumped " + String.format("%.2f%%", priceChange * 100)
            );
        }
        
        // 3. Volume spike detection (>10x average)
        if (averageVolume > 0 && currentVolume > averageVolume * VOLUME_SPIKE_THRESHOLD) {
            double volumeRatio = (double) currentVolume / averageVolume;
            logger.warn("⚠️ VOLUME SPIKE DETECTED: {} volume {}x average", 
                symbol, String.format("%.1f", volumeRatio));
            return new MarketEventResult(
                MarketEventType.VOLUME_SPIKE, 
                symbol, 
                volumeRatio,
                "Volume " + String.format("%.1fx", volumeRatio) + " average"
            );
        }
        
        return new MarketEventResult(MarketEventType.NORMAL, symbol, 0, "Normal");
    }
    
    /**
     * Monitor bot behavior for anomalies.
     */
    public BotAnomalyResult checkBotBehavior(int errorCount, int totalCycles, 
                                             double currentPnL, double expectedPnL) {
        
        // 1. Error rate anomaly
        double errorRate = totalCycles > 0 ? (double) errorCount / totalCycles : 0;
        if (errorRate > ERROR_RATE_THRESHOLD) {
            logger.error("🚨 HIGH ERROR RATE: {}% ({}/{})", 
                String.format("%.1f", errorRate * 100), errorCount, totalCycles);
            return new BotAnomalyResult(
                BotAnomalyType.HIGH_ERROR_RATE,
                errorRate,
                "Error rate: " + String.format("%.1f%%", errorRate * 100)
            );
        }
        
        // 2. Unexpected P&L swing
        if (expectedPnL != 0) {
            double pnlDeviation = Math.abs((currentPnL - expectedPnL) / expectedPnL);
            if (pnlDeviation > 0.50) { // 50% deviation
                logger.warn("⚠️ UNEXPECTED P&L SWING: Current={}, Expected={}, Deviation={}%",
                    String.format("%.2f", currentPnL),
                    String.format("%.2f", expectedPnL),
                    String.format("%.1f", pnlDeviation * 100));
                return new BotAnomalyResult(
                    BotAnomalyType.PNL_DEVIATION,
                    pnlDeviation,
                    "P&L deviation: " + String.format("%.1f%%", pnlDeviation * 100)
                );
            }
        }
        
        return new BotAnomalyResult(BotAnomalyType.NORMAL, 0, "Normal");
    }
    
    /**
     * Classify type of anomaly based on metric and value.
     */
    private AnomalyType classifyAnomaly(String metricName, double value, double mean) {
        if (metricName.contains("error") || metricName.contains("failure")) {
            return AnomalyType.ERROR_SPIKE;
        } else if (metricName.contains("price")) {
            return value > mean ? AnomalyType.PRICE_SPIKE : AnomalyType.PRICE_DROP;
        } else if (metricName.contains("volume")) {
            return AnomalyType.VOLUME_SPIKE;
        } else if (metricName.contains("latency") || metricName.contains("delay")) {
            return AnomalyType.PERFORMANCE_DEGRADATION;
        }
        return AnomalyType.UNKNOWN;
    }
    
    /**
     * Handle detected anomaly - may trigger safe mode.
     */
    public void handleAnomaly(AnomalyResult anomaly, String context) {
        if (!anomaly.isAnomaly()) return;
        
        // Broadcast to UI
        TradingWebSocketHandler.broadcastActivity(
            String.format("🚨 ANOMALY: %s - Z-score: %.2f (Context: %s)", 
                anomaly.type(), anomaly.zScore(), context),
            "WARN"
        );
        
        // Trigger safe mode for critical anomalies
        if (anomaly.zScore() > 4.0 || anomaly.type() == AnomalyType.ERROR_SPIKE) {
            safeModeManager.activate(anomaly.type().toString(), context);
        }
    }
    
    /**
     * Handle market event - may trigger safe mode.
     */
    public void handleMarketEvent(MarketEventResult event) {
        if (event.type() == MarketEventType.NORMAL) return;
        
        // Broadcast to UI
        TradingWebSocketHandler.broadcastActivity(
            String.format("🚨 MARKET EVENT: %s - %s (%s)", 
                event.type(), event.symbol(), event.description()),
            event.type() == MarketEventType.FLASH_CRASH ? "CRITICAL" : "WARN"
        );
        
        // Trigger safe mode for critical events
        if (event.type() == MarketEventType.FLASH_CRASH) {
            safeModeManager.activate(event.type().toString(), 
                event.symbol() + ": " + event.description());
        }
    }
    
    /**
     * Handle bot behavior anomaly.
     */
    public void handleBotAnomaly(BotAnomalyResult anomaly) {
        if (anomaly.type() == BotAnomalyType.NORMAL) return;
        
        // Broadcast to UI
        TradingWebSocketHandler.broadcastActivity(
            String.format("🚨 BOT ANOMALY: %s - %s", 
                anomaly.type(), anomaly.description()),
            "WARN"
        );
        
        // Trigger safe mode for high error rates
        if (anomaly.type() == BotAnomalyType.HIGH_ERROR_RATE) {
            safeModeManager.activate(anomaly.type().toString(), anomaly.description());
        }
    }
    
    /**
     * Check if safe mode is active.
     */
    public boolean isSafeModeActive() {
        return safeModeManager.isActive();
    }
    
    /**
     * Get safe mode manager for external control.
     */
    public SafeModeManager getSafeModeManager() {
        return safeModeManager;
    }
    
    // Inner classes for metric tracking
    private static class MetricHistory {
        private final Queue<Double> values;
        private final int maxSize;
        private double sum = 0;
        private double sumSquares = 0;
        
        MetricHistory(int maxSize) {
            this.maxSize = maxSize;
            this.values = new LinkedList<>();
        }
        
        void add(double value) {
            if (values.size() >= maxSize) {
                double removed = values.poll();
                sum -= removed;
                sumSquares -= removed * removed;
            }
            values.offer(value);
            sum += value;
            sumSquares += value * value;
        }
        
        double getMean() {
            return values.isEmpty() ? 0 : sum / values.size();
        }
        
        double getStdDev() {
            if (values.size() < 2) return 0;
            double mean = getMean();
            double variance = (sumSquares / values.size()) - (mean * mean);
            return Math.sqrt(Math.max(0, variance));
        }
    }
    
    // Result records
    public record AnomalyResult(
        boolean isAnomaly,
        AnomalyType type,
        double zScore,
        double value,
        double mean
    ) {}
    
    public record MarketEventResult(
        MarketEventType type,
        String symbol,
        double magnitude,
        String description
    ) {}
    
    public record BotAnomalyResult(
        BotAnomalyType type,
        double magnitude,
        String description
    ) {}
    
    // Enums
    public enum AnomalyType {
        ERROR_SPIKE, PRICE_SPIKE, PRICE_DROP, VOLUME_SPIKE, 
        PERFORMANCE_DEGRADATION, UNKNOWN
    }
    
    public enum MarketEventType {
        NORMAL, FLASH_CRASH, PRICE_SPIKE, VOLUME_SPIKE, CIRCUIT_BREAKER
    }
    
    public enum BotAnomalyType {
        NORMAL, HIGH_ERROR_RATE, PNL_DEVIATION, API_FAILURE
    }
}
