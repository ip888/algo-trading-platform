package com.trading.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Supplier;

/**
 * Retry utility for handling transient failures in API calls.
 * 
 * Features:
 * - Configurable retry attempts
 * - Exponential backoff
 * - Custom exception handling
 * - Logging of retry attempts
 * 
 * Use for: API calls, order placement, data fetching
 */
public class RetryUtil {
    private static final Logger logger = LoggerFactory.getLogger(RetryUtil.class);
    
    private static final int DEFAULT_MAX_ATTEMPTS = 3;
    private static final long DEFAULT_INITIAL_DELAY_MS = 1000; // 1 second
    private static final double BACKOFF_MULTIPLIER = 2.0;
    
    /**
     * Execute an operation with retry logic.
     * 
     * @param operation The operation to execute
     * @param maxAttempts Maximum number of attempts
     * @param operationName Name for logging
     * @return Result of the operation
     * @throws Exception if all retries fail
     */
    public static <T> T executeWithRetry(
            Supplier<T> operation,
            int maxAttempts,
            String operationName) throws Exception {
        
        Exception lastException = null;
        long delayMs = DEFAULT_INITIAL_DELAY_MS;
        
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                T result = operation.get();
                
                if (attempt > 1) {
                    logger.info("✅ {} succeeded on attempt {}/{}", 
                        operationName, attempt, maxAttempts);
                }
                
                return result;
                
            } catch (Exception e) {
                lastException = e;
                
                if (attempt < maxAttempts) {
                    logger.warn("⚠️ {} failed (attempt {}/{}): {} - Retrying in {}ms", 
                        operationName, attempt, maxAttempts, e.getMessage(), delayMs);
                    
                    try {
                        Thread.sleep(delayMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new Exception("Retry interrupted", ie);
                    }
                    
                    // Exponential backoff
                    delayMs = (long) (delayMs * BACKOFF_MULTIPLIER);
                } else {
                    logger.error("❌ {} failed after {} attempts", operationName, maxAttempts);
                }
            }
        }
        
        throw new Exception(
            String.format("%s failed after %d attempts", operationName, maxAttempts),
            lastException
        );
    }
    
    /**
     * Execute with default retry settings (3 attempts).
     */
    public static <T> T executeWithRetry(Supplier<T> operation, String operationName) 
            throws Exception {
        return executeWithRetry(operation, DEFAULT_MAX_ATTEMPTS, operationName);
    }
    
    /**
     * Execute a void operation with retry logic.
     */
    public static void executeWithRetry(
            Runnable operation,
            int maxAttempts,
            String operationName) throws Exception {
        
        executeWithRetry(() -> {
            operation.run();
            return null;
        }, maxAttempts, operationName);
    }
    
    /**
     * Execute a void operation with default retry settings.
     */
    public static void executeWithRetry(Runnable operation, String operationName) 
            throws Exception {
        executeWithRetry(operation, DEFAULT_MAX_ATTEMPTS, operationName);
    }
    
    /**
     * Check if an exception is retryable.
     */
    public static boolean isRetryable(Exception e) {
        String message = e.getMessage();
        if (message == null) {
            return false;
        }
        
        // Common retryable error patterns
        return message.contains("timeout") ||
               message.contains("connection") ||
               message.contains("network") ||
               message.contains("temporarily unavailable") ||
               message.contains("rate limit") ||
               message.contains("503") ||
               message.contains("429");
    }
}
