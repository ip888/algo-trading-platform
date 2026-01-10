package com.trading.protection;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Monitors the health of various system components by tracking "heartbeat" timestamps.
 * If any component fails to report in within its specified timeout, the system is flagged as unhealthy.
 */
public class HeartbeatMonitor {
    private static final Logger logger = LoggerFactory.getLogger(HeartbeatMonitor.class);
    
    private final Map<String, ComponentStatus> components = new ConcurrentHashMap<>();
    private final AtomicBoolean isSystemHealthy = new AtomicBoolean(true);
    private final EmergencyProtocol emergencyProtocol;

    public HeartbeatMonitor(EmergencyProtocol emergencyProtocol) {
        this.emergencyProtocol = emergencyProtocol;
    }

    /**
     * Register a component to be monitored.
     * @param componentName Unique name of the component
     * @param timeout Max allowed time between heartbeats
     */
    public void registerComponent(String componentName, Duration timeout) {
        components.put(componentName, new ComponentStatus(timeout));
        logger.info("Registered monitored component: {} (Timeout: {}s)", componentName, timeout.toSeconds());
    }

    /**
     * Record a heartbeat for a specific component.
     */
    public void beat(String componentName) {
        var status = components.get(componentName);
        if (status != null) {
            status.lastBeat = Instant.now();
        } else {
            logger.warn("Received heartbeat for unknown component: {}", componentName);
        }
    }

    /**
     * Check status of all components. Triggers emergency protocol if critical failures detected.
     */
    public void checkHealth() {
        Instant now = Instant.now();
        boolean allHealthy = true;

        for (var entry : components.entrySet()) {
            String name = entry.getKey();
            ComponentStatus status = entry.getValue();
            
            Duration timeSinceLastBeat = Duration.between(status.lastBeat, now);
            
            if (timeSinceLastBeat.compareTo(status.timeout) > 0) {
                logger.error("CRITICAL: Component '{}' requires attention! Last beat: {}s ago (Timeout: {}s)",
                    name, timeSinceLastBeat.toSeconds(), status.timeout.toSeconds());
                allHealthy = false;
            }
        }

        if (!allHealthy && isSystemHealthy.get()) {
            // Transition from Healthy -> Unhealthy
            logger.error("SYSTEM FAILURE DETECTED: initiating Emergency Protocol");
            isSystemHealthy.set(false);
            emergencyProtocol.trigger("Heartbeat Failure");
        } else if (allHealthy && !isSystemHealthy.get()) {
            // Transition from Unhealthy -> Healthy (Manual reset usually required, but logging recovery here)
            logger.info("System health recovered");
            isSystemHealthy.set(true);
        }
    }

    public boolean isHealthy() {
        return isSystemHealthy.get();
    }
    
    // For dashboard usage
    public Map<String, Long> getDetails() {
        Instant now = Instant.now();
        var details = new ConcurrentHashMap<String, Long>();
        components.forEach((k, v) -> details.put(k, Duration.between(v.lastBeat, now).toSeconds()));
        return details;
    }

    private static class ComponentStatus {
        Instant lastBeat;
        final Duration timeout;

        ComponentStatus(Duration timeout) {
            this.lastBeat = Instant.now();
            this.timeout = timeout;
        }
    }
}
