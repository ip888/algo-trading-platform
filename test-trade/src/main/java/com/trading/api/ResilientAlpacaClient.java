package com.trading.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.trading.api.model.Bar;
import com.trading.api.model.Position;
import com.trading.config.Config;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Production-grade wrapper for AlpacaClient with resilience patterns.
 * 
 * Features:
 * - Circuit Breaker: Prevents cascading failures
 * - Rate Limiter: Respects Alpaca's 200 req/min limit
 * - Retry: Automatic retry on transient failures
 * - Metrics: Tracks latency and success/failure rates
 * 
 * This is a decorator pattern - wraps the base AlpacaClient with resilience.
 */
public final class ResilientAlpacaClient {
    private static final Logger logger = LoggerFactory.getLogger(ResilientAlpacaClient.class);
    
    private final AlpacaClient delegate;
    private final CircuitBreaker circuitBreaker;
    private final RateLimiter rateLimiter;
    private final Retry retry;
    private final MeterRegistry meterRegistry;
    
    public ResilientAlpacaClient(AlpacaClient delegate, MeterRegistry meterRegistry) {
        this.delegate = delegate;
        this.meterRegistry = meterRegistry;
        
        // Circuit Breaker: Open after 50% failures in 10 requests
        var cbConfig = CircuitBreakerConfig.custom()
            .failureRateThreshold(50)
            .waitDurationInOpenState(Duration.ofSeconds(60))
            .slidingWindowSize(10)
            .permittedNumberOfCallsInHalfOpenState(3)
            .build();
        this.circuitBreaker = CircuitBreaker.of("alpaca-api", cbConfig);
        
        // Rate Limiter: Alpaca allows 200 requests/minute
        var rlConfig = RateLimiterConfig.custom()
            .limitForPeriod(200)
            .limitRefreshPeriod(Duration.ofMinutes(1))
            .timeoutDuration(Duration.ofSeconds(5))
            .build();
        this.rateLimiter = RateLimiter.of("alpaca-api", rlConfig);
        
        // Retry: 3 attempts with exponential backoff
        var retryConfig = RetryConfig.custom()
            .maxAttempts(3)
            .waitDuration(Duration.ofMillis(500))
            .retryExceptions(Exception.class)
            .build();
        this.retry = Retry.of("alpaca-api", retryConfig);
        
        // Register circuit breaker metrics
        circuitBreaker.getEventPublisher()
            .onStateTransition(event -> 
                logger.warn("Circuit breaker state changed: {}", event.getStateTransition()));
        
        logger.info("ResilientAlpacaClient initialized with circuit breaker, rate limiter, and retry");
    }
    
    /**
     * Execute a call with full resilience: rate limit -> retry -> circuit breaker -> metrics
     */
    private <T> T executeResilient(String operation, Supplier<T> supplier) {
        var timer = Timer.builder("alpaca.api.call")
            .tag("operation", operation)
            .register(meterRegistry);
        
        return timer.record(() -> {
            try {
                // Chain: RateLimiter -> Retry -> CircuitBreaker
                var decoratedSupplier = RateLimiter.decorateSupplier(rateLimiter,
                    Retry.decorateSupplier(retry,
                        CircuitBreaker.decorateSupplier(circuitBreaker, supplier)));
                
                T result = decoratedSupplier.get();
                
                // Record success
                meterRegistry.counter("alpaca.api.success",
                    "operation", operation).increment();
                
                return result;
                
            } catch (Exception e) {
                // Record failure
                meterRegistry.counter("alpaca.api.failure",
                    "operation", operation,
                    "error", e.getClass().getSimpleName()).increment();
                
                logger.error("API call failed after retries: {}", operation, e);
                throw new RuntimeException("Alpaca API call failed: " + operation, e);
            }
        });
    }
    
    // Delegate methods with resilience
    
    public JsonNode getAccount() {
        return executeResilient("getAccount", () -> {
            try {
                return delegate.getAccount();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }
    
    public List<Bar> getMarketHistory(String symbol, int limit) {
        return executeResilient("getMarketHistory", () -> {
            try {
                return delegate.getMarketHistory(symbol, limit);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }
    
    public List<Position> getPositions() {
        return executeResilient("getPositions", () -> {
            try {
                return delegate.getPositions();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }
    
    public void placeOrder(String symbol, double qty, String side, String type, 
                          String timeInForce, Double limitPrice) {
        executeResilient("placeOrder", () -> {
            try {
                delegate.placeOrder(symbol, qty, side, type, timeInForce, limitPrice);
                return null;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }
    
    public void placeBracketOrder(String symbol, double qty, String side,
                                 double takeProfitPrice, double stopLossPrice,
                                 Double stopLossLimitPrice, Double limitPrice) {
        executeResilient("placeBracketOrder", () -> {
            try {
                delegate.placeBracketOrder(symbol, qty, side, takeProfitPrice, 
                    stopLossPrice, stopLossLimitPrice, limitPrice);
                return null;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    public void cancelAllOrders() {
        executeResilient("cancelAllOrders", () -> {
            try {
                delegate.cancelAllOrders();
                return null;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }
    
    /**
     * Get circuit breaker state for health checks
     */
    public String getCircuitBreakerState() {
        return circuitBreaker.getState().name();
    }
    
    /**
     * Get metrics for monitoring
     */
    public CircuitBreakerMetrics getMetrics() {
        var metrics = circuitBreaker.getMetrics();
        return new CircuitBreakerMetrics(
            metrics.getNumberOfSuccessfulCalls(),
            metrics.getNumberOfFailedCalls(),
            metrics.getFailureRate(),
            circuitBreaker.getState().name()
        );
    }
    
    public record CircuitBreakerMetrics(
        long successfulCalls,
        long failedCalls,
        float failureRate,
        String state
    ) {}
}
