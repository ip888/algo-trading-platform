package com.trading.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Intelligent rate limiter for API requests.
 * Combines fixed delays with adaptive backoff to prevent rate limit errors.
 */
public class RateLimiter {
    private static final Logger logger = LoggerFactory.getLogger(RateLimiter.class);
    
    private final long minDelayMs;
    private final boolean adaptiveEnabled;
    
    private long lastRequestTime = 0;
    private long currentDelayMs;
    private int consecutiveErrors = 0;
    private int consecutiveSuccesses = 0;
    
    public RateLimiter(long minDelayMs, boolean adaptiveEnabled) {
        this.minDelayMs = minDelayMs;
        this.adaptiveEnabled = adaptiveEnabled;
        this.currentDelayMs = minDelayMs;
        logger.info("RateLimiter initialized: minDelay={}ms, adaptive={}", minDelayMs, adaptiveEnabled);
    }
    
    /**
     * Wait if needed to respect rate limits.
     * Thread-safe for concurrent access.
     */
    public synchronized void waitIfNeeded() {
        long now = System.currentTimeMillis();
        long elapsed = now - lastRequestTime;
        
        if (elapsed < currentDelayMs) {
            long sleepTime = currentDelayMs - elapsed;
            try {
                Thread.sleep(sleepTime);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.warn("Rate limiter sleep interrupted");
            }
        }
        
        lastRequestTime = System.currentTimeMillis();
    }
    
    /**
     * Record successful API call.
     * Gradually reduces delay if adaptive mode is enabled.
     */
    public synchronized void recordSuccess() {
        consecutiveErrors = 0;
        consecutiveSuccesses++;
        
        if (adaptiveEnabled && consecutiveSuccesses >= 10 && currentDelayMs > minDelayMs) {
            // Gradually reduce delay after sustained success
            currentDelayMs = Math.max(minDelayMs, (long)(currentDelayMs * 0.9));
            consecutiveSuccesses = 0;
            logger.debug("Rate limit delay reduced to {}ms", currentDelayMs);
        }
    }
    
    /**
     * Record rate limit error (429).
     * Increases delay using exponential backoff.
     */
    public synchronized void recordRateLimit() {
        consecutiveSuccesses = 0;
        consecutiveErrors++;
        
        if (adaptiveEnabled) {
            backoff();
        }
    }
    
    /**
     * Exponential backoff on rate limit errors.
     */
    private void backoff() {
        long oldDelay = currentDelayMs;
        
        // Exponential backoff: double the delay, max 5 seconds
        currentDelayMs = Math.min(5000, currentDelayMs * 2);
        
        logger.warn("Rate limit detected! Backing off: {}ms -> {}ms (errors: {})", 
            oldDelay, currentDelayMs, consecutiveErrors);
        
        // Extra sleep to let rate limit window reset
        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
    
    /**
     * Get current delay for monitoring.
     */
    public synchronized long getCurrentDelay() {
        return currentDelayMs;
    }
    
    /**
     * Reset to minimum delay (for testing or account upgrades).
     */
    public synchronized void reset() {
        currentDelayMs = minDelayMs;
        consecutiveErrors = 0;
        consecutiveSuccesses = 0;
        logger.info("Rate limiter reset to {}ms", minDelayMs);
    }
}
