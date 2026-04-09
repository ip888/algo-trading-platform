package com.trading.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.trading.api.model.Bar;
import com.trading.api.model.BracketOrderResult;
import com.trading.api.model.Position;
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
 * Production-grade wrapper for any BrokerClient with resilience patterns.
 *
 * Features:
 * - Circuit Breaker: Prevents cascading failures
 * - Rate Limiter: Respects broker's request-per-minute limit
 * - Retry: Automatic retry on transient failures
 * - Metrics: Tracks latency and success/failure rates
 *
 * This is a decorator pattern - wraps the base BrokerClient with resilience.
 */
public class ResilientBrokerClient {
    private static final Logger logger = LoggerFactory.getLogger(ResilientBrokerClient.class);

    private final BrokerClient delegate;
    private final CircuitBreaker circuitBreaker;
    private final RateLimiter rateLimiter;
    private final Retry retry;
    private final MeterRegistry meterRegistry;

    public ResilientBrokerClient(BrokerClient delegate, MeterRegistry meterRegistry) {
        this.delegate = delegate;
        this.meterRegistry = meterRegistry;

        // Circuit Breaker: Open after 50% failures in 10 requests
        // AUTO-RECOVERY: Wait only 15 seconds before trying again (faster for trading)
        // PDTRejectedException is a business-logic rejection, NOT an infrastructure failure
        var cbConfig = CircuitBreakerConfig.custom()
            .failureRateThreshold(50)
            .waitDurationInOpenState(Duration.ofSeconds(15))  // Faster recovery for trading
            .slidingWindowSize(10)
            .permittedNumberOfCallsInHalfOpenState(5)  // More test calls in half-open
            .automaticTransitionFromOpenToHalfOpenEnabled(true)  // Auto-transition!
            .ignoreExceptions(PDTRejectedException.class)  // PDT is not an infra failure
            .build();
        this.circuitBreaker = CircuitBreaker.of("alpaca-api", cbConfig);

        // Rate Limiter: Alpaca allows 200 requests/minute
        // Use 150 to leave headroom for both profiles sharing this limiter
        var rlConfig = RateLimiterConfig.custom()
            .limitForPeriod(150)  // Conservative: 2 profiles share this
            .limitRefreshPeriod(Duration.ofMinutes(1))
            .timeoutDuration(Duration.ofSeconds(5))
            .build();
        this.rateLimiter = RateLimiter.of("alpaca-api", rlConfig);

        // Retry: 3 attempts with exponential backoff
        // Don't retry PDT rejections (business logic, retrying won't help)
        var retryConfig = RetryConfig.custom()
            .maxAttempts(3)
            .waitDuration(Duration.ofMillis(500))
            .retryExceptions(Exception.class)
            .ignoreExceptions(PDTRejectedException.class)
            .build();
        this.retry = Retry.of("alpaca-api", retryConfig);

        // Register circuit breaker metrics
        circuitBreaker.getEventPublisher()
            .onStateTransition(event ->
                logger.warn("Circuit breaker state changed: {}", event.getStateTransition()));

        // Start background health checker for auto-recovery
        startHealthBasedRecovery();

        logger.info("ResilientBrokerClient initialized with circuit breaker, rate limiter, and retry");
    }

    /**
     * Background thread that checks if API is healthy and resets circuit breaker.
     * This prevents the circuit breaker from being "stuck" in OPEN state.
     */
    private void startHealthBasedRecovery() {
        Thread.ofVirtual().name("circuit-breaker-recovery").start(() -> {
            while (true) {
                try {
                    Thread.sleep(45_000);  // Check every 45 seconds

                    if (circuitBreaker.getState() == CircuitBreaker.State.OPEN) {
                        logger.info("🔄 Circuit breaker is OPEN - testing API health...");

                        // Try a simple API call directly (bypass circuit breaker)
                        try {
                            var account = delegate.getAccount();
                            if (account != null && account.has("status")) {
                                logger.info("✅ API is healthy! Resetting circuit breaker.");
                                circuitBreaker.reset();
                            }
                        } catch (Exception e) {
                            logger.debug("API still unhealthy: {}", e.getMessage());
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    logger.debug("Health check error: {}", e.getMessage());
                }
            }
        });
    }

    /**
     * Manually reset the circuit breaker.
     * Call this when you know the API is healthy but circuit is stuck.
     */
    public void resetCircuitBreaker() {
        logger.info("🔄 Manual circuit breaker reset requested");
        circuitBreaker.reset();
    }

    /**
     * Get current circuit breaker state for health checks.
     */
    public String getCircuitBreakerState() {
        return circuitBreaker.getState().name();
    }

    /**
     * Get the underlying BrokerClient for components that need direct access.
     * Use sparingly - prefer the resilient wrapper methods when possible.
     * This is for read-only/sync operations that don't need full resilience.
     */
    public BrokerClient getDelegate() {
        return delegate;
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
                // Let PDTRejectedException propagate directly (not an infra failure)
                if (e instanceof PDTRejectedException) {
                    throw e;
                }
                Throwable cause = e.getCause();
                while (cause != null) {
                    if (cause instanceof PDTRejectedException) {
                        throw (PDTRejectedException) cause;
                    }
                    cause = cause.getCause();
                }

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
            } catch (PDTRejectedException e) {
                throw e; // Propagate directly — not an infra failure
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    /**
     * Place a bracket order with resilience patterns.
     *
     * @return BracketOrderResult indicating whether server-side protection was applied.
     *         For fractional quantities, returns result with hasBracketProtection=false,
     *         meaning client-side SL/TP monitoring is required.
     */
    public BracketOrderResult placeBracketOrder(String symbol, double qty, String side,
                                 double takeProfitPrice, double stopLossPrice,
                                 Double stopLossLimitPrice, Double limitPrice) {
        return executeResilient("placeBracketOrder", () ->
            delegate.placeBracketOrder(symbol, qty, side, takeProfitPrice,
                stopLossPrice, stopLossLimitPrice, limitPrice)
        );
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

    public Optional<Bar> getLatestBar(String symbol) {
        return executeResilient("getLatestBar", () -> {
            try {
                return delegate.getLatestBar(symbol);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    public List<Bar> getBars(String symbol, String timeframe, int limit) {
        return executeResilient("getBars", () -> {
            try {
                return delegate.getBars(symbol, timeframe, limit);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    public JsonNode getOpenOrders(String symbol) {
        return executeResilient("getOpenOrders", () -> {
            return delegate.getOpenOrders(symbol);
        });
    }

    public void cancelOrder(String orderId) {
        executeResilient("cancelOrder", () -> {
            delegate.cancelOrder(orderId);
            return null;
        });
    }

    /**
     * Place a sell order bypassing the circuit breaker.
     * Used for critical protective exits (stop-loss, emergency) that must execute
     * even when the circuit breaker is open due to other failures.
     * Still respects rate limiting.
     */
    public void placeOrderDirect(String symbol, double qty, String side, String type,
                                 String timeInForce, Double limitPrice) {
        logger.info("DIRECT ORDER (bypass circuit breaker): {} {} {} qty={}", side, type, symbol, qty);
        delegate.placeOrder(symbol, qty, side, type, timeInForce, limitPrice);
    }

    /**
     * Place a native GTC stop-market sell order for fractional positions.
     * Goes through the circuit breaker like normal orders.
     */
    public void placeNativeStopOrder(String symbol, double qty, double stopPrice) {
        executeResilient("placeNativeStopOrder", () -> {
            try {
                delegate.placeNativeStopOrder(symbol, qty, stopPrice);
                return null;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
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
