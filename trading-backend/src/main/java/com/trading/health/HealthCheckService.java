package com.trading.health;

import com.trading.api.ResilientAlpacaClient;
import com.trading.persistence.TradeDatabase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Health check service for monitoring system components.
 */
public final class HealthCheckService {
    private static final Logger logger = LoggerFactory.getLogger(HealthCheckService.class);
    
    private final ResilientAlpacaClient alpacaClient;
    private final TradeDatabase database;
    
    public HealthCheckService(ResilientAlpacaClient alpacaClient, TradeDatabase database) {
        this.alpacaClient = alpacaClient;
        this.database = database;
    }
    
    /**
     * Perform comprehensive health check of all system components.
     */
    public HealthStatus getHealth() {
        Map<String, ComponentHealth> components = new HashMap<>();
        
        // Check Alpaca API
        components.put("alpaca_api", checkAlpacaHealth());
        
        // Check Database
        components.put("database", checkDatabaseHealth());
        
        // Check Circuit Breaker
        components.put("circuit_breaker", checkCircuitBreakerHealth());
        
        // Overall status
        boolean allHealthy = components.values().stream()
            .allMatch(c -> c.status() == Status.UP);
        
        boolean anyDegraded = components.values().stream()
            .anyMatch(c -> c.status() == Status.DEGRADED);
        
        Status overallStatus = allHealthy ? Status.UP : 
                              anyDegraded ? Status.DEGRADED : Status.DOWN;
        
        return new HealthStatus(
            overallStatus,
            Instant.now(),
            components
        );
    }
    
    private ComponentHealth checkAlpacaHealth() {
        try {
            alpacaClient.getAccount();
            return new ComponentHealth(Status.UP, "API responding normally", null);
        } catch (Exception e) {
            logger.error("Alpaca API health check failed", e);
            return new ComponentHealth(Status.DOWN, "API error", e.getMessage());
        }
    }
    
    private ComponentHealth checkDatabaseHealth() {
        try {
            database.getTotalTrades();
            return new ComponentHealth(Status.UP, "Database accessible", null);
        } catch (Exception e) {
            logger.error("Database health check failed", e);
            return new ComponentHealth(Status.DOWN, "Database error", e.getMessage());
        }
    }
    
    private ComponentHealth checkCircuitBreakerHealth() {
        String state = alpacaClient.getCircuitBreakerState();
        Status status = switch (state) {
            case "CLOSED" -> Status.UP;
            case "HALF_OPEN" -> Status.DEGRADED;
            case "OPEN" -> Status.DOWN;
            default -> Status.DEGRADED;
        };
        return new ComponentHealth(status, "Circuit breaker: " + state, null);
    }
    
    /**
     * Health status enumeration.
     */
    public enum Status {
        UP,       // All systems operational
        DEGRADED, // Some issues but functional
        DOWN      // Critical failure
    }
    
    /**
     * Overall health status record.
     */
    public record HealthStatus(
        Status status,
        Instant timestamp,
        Map<String, ComponentHealth> components
    ) {
        public String toJson() {
            StringBuilder json = new StringBuilder();
            json.append("{");
            json.append("\"status\":\"").append(status).append("\",");
            json.append("\"timestamp\":\"").append(timestamp).append("\",");
            json.append("\"components\":{");
            
            boolean first = true;
            for (Map.Entry<String, ComponentHealth> entry : components.entrySet()) {
                if (!first) json.append(",");
                json.append("\"").append(entry.getKey()).append("\":");
                json.append(entry.getValue().toJson());
                first = false;
            }
            
            json.append("}}");
            return json.toString();
        }
    }
    
    /**
     * Individual component health record.
     */
    public record ComponentHealth(
        Status status,
        String message,
        String error
    ) {
        public String toJson() {
            StringBuilder json = new StringBuilder();
            json.append("{");
            json.append("\"status\":\"").append(status).append("\",");
            json.append("\"message\":\"").append(message).append("\"");
            if (error != null) {
                json.append(",\"error\":\"").append(error.replace("\"", "\\\"")).append("\"");
            }
            json.append("}");
            return json.toString();
        }
    }
}
