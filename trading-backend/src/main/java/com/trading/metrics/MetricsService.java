package com.trading.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.prometheus.PrometheusConfig;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Centralized metrics service for the trading bot.
 * 
 * Provides:
 * - Prometheus-compatible metrics
 * - Custom counters, timers, and gauges
 * - Metrics scraping endpoint
 * 
 * Modern design:
 * - Uses Initialization-on-Demand Holder pattern for thread-safe lazy singleton
 * - No synchronized keyword - JVM guarantees thread safety
 * 
 * Usage:
 *   var metrics = MetricsService.getInstance();
 *   metrics.incrementOrdersPlaced("MAIN", "buy");
 *   metrics.recordOrderLatency("MAIN", duration);
 */
public final class MetricsService {
    private static final Logger logger = LoggerFactory.getLogger(MetricsService.class);
    
    private final PrometheusMeterRegistry registry;
    
    private MetricsService() {
        this.registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        logger.info("MetricsService initialized with Prometheus registry");
    }
    
    /**
     * Initialization-on-Demand Holder pattern.
     * Thread-safe without synchronized - JVM guarantees class initialization is thread-safe.
     */
    private static class Holder {
        private static final MetricsService INSTANCE = new MetricsService();
    }
    
    public static MetricsService getInstance() {
        return Holder.INSTANCE;
    }
    
    public MeterRegistry getRegistry() {
        return registry;
    }
    
    /**
     * Get Prometheus-formatted metrics for scraping
     */
    public String scrape() {
        return registry.scrape();
    }
    
    // Convenience methods for common metrics
    
    public void incrementOrdersPlaced(String profile, String side) {
        registry.counter("trading.orders.placed",
            "profile", profile,
            "side", side).increment();
    }
    
    public void incrementOrdersFailed(String profile, String side, String reason) {
        registry.counter("trading.orders.failed",
            "profile", profile,
            "side", side,
            "reason", reason).increment();
    }
    
    public void recordOrderLatency(String profile, long milliseconds) {
        registry.timer("trading.order.latency",
            "profile", profile).record(java.time.Duration.ofMillis(milliseconds));
    }
    
    public void recordPositionPnL(String profile, double pnl) {
        registry.summary("trading.position.pnl",
            "profile", profile).record(pnl);
    }
    
    public void setActivePositions(String profile, int count) {
        registry.gauge("trading.positions.active",
            java.util.List.of(
                io.micrometer.core.instrument.Tag.of("profile", profile)
            ), count);
    }
    
    public void recordTradeExecution(String profile, String symbol, double price, double quantity) {
        registry.counter("trading.trades.executed",
            "profile", profile,
            "symbol", symbol).increment();
        
        registry.summary("trading.trade.value",
            "profile", profile).record(price * quantity);
    }
    
    /**
     * Record alert event for monitoring.
     */
    public void recordAlert(String level) {
        registry.counter("trading.alerts.triggered",
            "level", level).increment();
    }
}
