package com.trading.monitoring;

import com.trading.metrics.MetricsService;
import com.trading.notifications.TelegramNotifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Alerting service for monitoring critical system events and thresholds.
 */
public final class AlertingService {
    private static final Logger logger = LoggerFactory.getLogger(AlertingService.class);
    
    // Alert thresholds
    private static final double HIGH_FAILURE_RATE_THRESHOLD = 0.10; // 10%
    private static final double CRITICAL_DRAWDOWN_THRESHOLD = 0.08; // 8%
    private static final int MAX_CONSECUTIVE_FAILURES = 5;
    private static final long ALERT_COOLDOWN_MS = 300_000; // 5 minutes
    
    private final TelegramNotifier notifier;
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private volatile Instant lastAlertTime = Instant.MIN;
    
    public AlertingService(TelegramNotifier notifier) {
        this.notifier = notifier;
    }
    
    /**
     * Check all alert conditions and send notifications if thresholds exceeded.
     */
    public void checkAlerts(double failureRate, double drawdown, int activePositions) {
        checkFailureRate(failureRate);
        checkDrawdown(drawdown);
        checkPositionCount(activePositions);
    }
    
    /**
     * Check if order failure rate exceeds threshold.
     */
    private void checkFailureRate(double failureRate) {
        if (failureRate > HIGH_FAILURE_RATE_THRESHOLD) {
            int failures = consecutiveFailures.incrementAndGet();
            
            logger.error("🚨 ALERT: High failure rate detected: {}%", 
                String.format("%.2f", failureRate * 100));
            
            if (failures >= MAX_CONSECUTIVE_FAILURES) {
                sendAlert(
                    "CRITICAL: Order Failure Rate",
                    String.format("Failure rate: %.2f%% (%d consecutive failures). Consider halting trading.",
                        failureRate * 100, failures),
                    AlertLevel.CRITICAL
                );
            } else if (failures == 3) {
                sendAlert(
                    "WARNING: Elevated Failure Rate",
                    String.format("Failure rate: %.2f%% (%d consecutive failures)",
                        failureRate * 100, failures),
                    AlertLevel.WARNING
                );
            }
        } else {
            consecutiveFailures.set(0);
        }
    }
    
    /**
     * Check if drawdown exceeds critical threshold.
     */
    private void checkDrawdown(double drawdown) {
        if (drawdown > CRITICAL_DRAWDOWN_THRESHOLD) {
            logger.error("🚨 ALERT: Critical drawdown: {}%", 
                String.format("%.2f", drawdown * 100));
            
            sendAlert(
                "CRITICAL: Drawdown Alert",
                String.format("Current drawdown: %.2f%% (threshold: %.2f%%)",
                    drawdown * 100, CRITICAL_DRAWDOWN_THRESHOLD * 100),
                AlertLevel.CRITICAL
            );
        }
    }
    
    /**
     * Check if position count is unusual.
     */
    private void checkPositionCount(int activePositions) {
        if (activePositions == 0) {
            logger.warn("⚠️ No active positions - bot may be idle");
        } else if (activePositions > 10) {
            logger.warn("⚠️ High position count: {} active positions", activePositions);
            
            sendAlert(
                "INFO: High Position Count",
                String.format("Currently holding %d positions", activePositions),
                AlertLevel.INFO
            );
        }
    }
    
    /**
     * Send alert with cooldown to prevent spam.
     */
    private void sendAlert(String title, String message, AlertLevel level) {
        Instant now = Instant.now();
        
        // Check cooldown
        if (now.toEpochMilli() - lastAlertTime.toEpochMilli() < ALERT_COOLDOWN_MS) {
            logger.debug("Alert suppressed due to cooldown: {}", title);
            return;
        }
        
        lastAlertTime = now;
        
        // Format message with emoji based on level
        String emoji = switch (level) {
            case CRITICAL -> "🚨";
            case WARNING -> "⚠️";
            case INFO -> "ℹ️";
        };
        
        String formattedMessage = String.format("%s %s\n\n%s\n\nTime: %s",
            emoji, title, message, now);
        
        // Send notification
        if (notifier != null) {
            try {
                notifier.sendMessage(formattedMessage);
                logger.info("Alert sent: {}", title);
            } catch (Exception e) {
                logger.error("Failed to send alert: {}", title, e);
            }
        }
        
        // Record metric
        MetricsService.getInstance().recordAlert(level.name());
    }
    
    /**
     * Reset alert state (useful for testing).
     */
    public void reset() {
        consecutiveFailures.set(0);
        lastAlertTime = Instant.MIN;
    }
    
    /**
     * Alert severity levels.
     */
    public enum AlertLevel {
        INFO,
        WARNING,
        CRITICAL
    }
}
