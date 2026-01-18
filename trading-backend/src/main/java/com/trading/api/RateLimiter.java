package com.trading.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Modern rate limiter for API requests using lock-free and non-blocking patterns.
 * 
 * Features:
 * - ReentrantLock instead of synchronized for better virtual thread compatibility
 * - LockSupport.parkNanos() instead of Thread.sleep() for non-blocking waits
 * - AtomicLong for lock-free time tracking
 * - Combines fixed delays with adaptive backoff to prevent rate limit errors
 * 
 * Design: Virtual thread friendly - no carrier thread blocking during waits.
 */
public class RateLimiter {
    private static final Logger logger = LoggerFactory.getLogger(RateLimiter.class);
    
    private final long minDelayMs;
    private final boolean adaptiveEnabled;
    
    // Lock for coordinating rate limit updates (not held during waits)
    private final ReentrantLock lock = new ReentrantLock();
    
    // Atomic fields for lock-free reads
    private final AtomicLong lastRequestTime = new AtomicLong(0);
    private final AtomicLong currentDelayMs;
    private final AtomicInteger consecutiveErrors = new AtomicInteger(0);
    private final AtomicInteger consecutiveSuccesses = new AtomicInteger(0);
    
    public RateLimiter(long minDelayMs, boolean adaptiveEnabled) {
        this.minDelayMs = minDelayMs;
        this.adaptiveEnabled = adaptiveEnabled;
        this.currentDelayMs = new AtomicLong(minDelayMs);
        logger.info("RateLimiter initialized: minDelay={}ms, adaptive={} (modern lock-free design)", 
            minDelayMs, adaptiveEnabled);
    }
    
    /**
     * Wait if needed to respect rate limits.
     * Thread-safe and virtual thread friendly - uses non-blocking parking.
     */
    public void waitIfNeeded() {
        long now = System.currentTimeMillis();
        long lastTime = lastRequestTime.get();
        long delay = currentDelayMs.get();
        long elapsed = now - lastTime;
        
        if (elapsed < delay) {
            long sleepNanos = TimeUnit.MILLISECONDS.toNanos(delay - elapsed);
            // LockSupport.parkNanos is virtual thread friendly - doesn't block carrier
            LockSupport.parkNanos(sleepNanos);
        }
        
        // Update last request time atomically
        lastRequestTime.set(System.currentTimeMillis());
    }
    
    /**
     * Record successful API call.
     * Gradually reduces delay if adaptive mode is enabled.
     */
    public void recordSuccess() {
        consecutiveErrors.set(0);
        int successes = consecutiveSuccesses.incrementAndGet();
        
        if (adaptiveEnabled && successes >= 10) {
            lock.lock();
            try {
                long current = currentDelayMs.get();
                if (current > minDelayMs) {
                    // Gradually reduce delay after sustained success
                    long newDelay = Math.max(minDelayMs, (long)(current * 0.9));
                    currentDelayMs.set(newDelay);
                    consecutiveSuccesses.set(0);
                    logger.debug("Rate limit delay reduced to {}ms", newDelay);
                }
            } finally {
                lock.unlock();
            }
        }
    }
    
    /**
     * Record rate limit error (429).
     * Increases delay using exponential backoff.
     */
    public void recordRateLimit() {
        consecutiveSuccesses.set(0);
        int errors = consecutiveErrors.incrementAndGet();
        
        if (adaptiveEnabled) {
            backoff(errors);
        }
    }
    
    /**
     * Exponential backoff on rate limit errors.
     * Uses non-blocking park instead of Thread.sleep.
     */
    private void backoff(int errorCount) {
        lock.lock();
        try {
            long oldDelay = currentDelayMs.get();
            
            // Exponential backoff: double the delay, max 5 seconds
            long newDelay = Math.min(5000, oldDelay * 2);
            currentDelayMs.set(newDelay);
            
            logger.warn("Rate limit detected! Backing off: {}ms -> {}ms (errors: {})", 
                oldDelay, newDelay, errorCount);
        } finally {
            lock.unlock();
        }
        
        // Extra wait to let rate limit window reset (non-blocking)
        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(2000));
    }
    
    /**
     * Get current delay for monitoring (lock-free read).
     */
    public long getCurrentDelay() {
        return currentDelayMs.get();
    }
    
    /**
     * Reset to minimum delay (for testing or account upgrades).
     */
    public void reset() {
        lock.lock();
        try {
            currentDelayMs.set(minDelayMs);
            consecutiveErrors.set(0);
            consecutiveSuccesses.set(0);
            logger.info("Rate limiter reset to {}ms", minDelayMs);
        } finally {
            lock.unlock();
        }
    }
}
