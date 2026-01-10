package com.trading.core.broker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Manages failover between multiple brokers.
 * Implements circuit breaker pattern for automatic switching.
 */
public class BrokerFailoverManager {
    private static final Logger logger = LoggerFactory.getLogger(BrokerFailoverManager.class);
    
    private final List<BrokerGateway> brokers;
    private final AtomicReference<BrokerGateway> activeBroker;
    private final AtomicInteger failureCount;
    private static final int MAX_FAILURES = 3;
    
    public BrokerFailoverManager(List<BrokerGateway> brokers) {
        if (brokers == null || brokers.isEmpty()) {
            throw new IllegalArgumentException("At least one broker must be provided");
        }
        this.brokers = brokers;
        this.activeBroker = new AtomicReference<>(brokers.get(0));
        this.failureCount = new AtomicInteger(0);
        logger.info("🔄 BrokerFailoverManager initialized with {} broker(s). Primary: {}",
            brokers.size(), activeBroker.get().getBrokerId());
    }
    
    /**
     * Get the currently active broker.
     */
    public BrokerGateway getActiveBroker() {
        return activeBroker.get();
    }
    
    /**
     * Report a failure on the current broker.
     * If failures exceed threshold, switch to next available broker.
     */
    public void reportFailure(String reason) {
        int failures = failureCount.incrementAndGet();
        logger.warn("⚠️ Broker {} failure #{}: {}", 
            activeBroker.get().getBrokerId(), failures, reason);
        
        if (failures >= MAX_FAILURES) {
            switchToNextBroker();
        }
    }
    
    /**
     * Report a successful operation, resetting the failure counter.
     */
    public void reportSuccess() {
        failureCount.set(0);
    }
    
    /**
     * Switch to the next available broker in the list.
     */
    private synchronized void switchToNextBroker() {
        BrokerGateway current = activeBroker.get();
        int currentIndex = brokers.indexOf(current);
        
        for (int i = 1; i < brokers.size(); i++) {
            int nextIndex = (currentIndex + i) % brokers.size();
            BrokerGateway next = brokers.get(nextIndex);
            
            if (next.isHealthy()) {
                activeBroker.set(next);
                failureCount.set(0);
                logger.info("🔄 Switched broker from {} to {}", 
                    current.getBrokerId(), next.getBrokerId());
                return;
            }
        }
        
        logger.error("🚨 No healthy brokers available for failover!");
    }
    
    /**
     * Get status of all brokers.
     */
    public String getStatus() {
        StringBuilder sb = new StringBuilder();
        sb.append("Active: ").append(activeBroker.get().getBrokerId());
        sb.append(", Failures: ").append(failureCount.get());
        sb.append(", Available: [");
        for (int i = 0; i < brokers.size(); i++) {
            if (i > 0) sb.append(", ");
            BrokerGateway b = brokers.get(i);
            sb.append(b.getBrokerId()).append(b.isHealthy() ? "✓" : "✗");
        }
        sb.append("]");
        return sb.toString();
    }
}
