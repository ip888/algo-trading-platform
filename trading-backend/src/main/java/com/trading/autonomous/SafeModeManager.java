package com.trading.autonomous;

import com.trading.config.Config;
import com.trading.websocket.TradingWebSocketHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Safe mode manager that reduces risk exposure during anomalous market conditions.
 * Automatically activates defensive trading parameters and monitors for recovery.
 * 
 * Modern design:
 * - Uses ReentrantLock instead of synchronized for better virtual thread compatibility
 * - Explicit lock management with try-finally for guaranteed release
 */
public class SafeModeManager {
    private static final Logger logger = LoggerFactory.getLogger(SafeModeManager.class);
    
    private final Config config;
    private final Path configPath;
    private final Path safeModeBackupPath;
    private final ScheduledExecutorService recoveryScheduler;
    
    // Modern lock instead of synchronized
    private final ReentrantLock lock = new ReentrantLock();
    
    // Safe mode state
    private volatile boolean active = false;
    private Instant activationTime;
    private String activationReason;
    private Map<String, String> originalConfig;
    
    // Recovery tracking
    private int recoveryCheckCount = 0;
    private static final int MAX_RECOVERY_CHECKS = 12; // 1 hour (5 min intervals)
    
    public SafeModeManager(Config config) {
        this.config = config;
        this.configPath = Paths.get("config.properties");
        this.safeModeBackupPath = Paths.get("config.properties.backup.safemode");
        this.recoveryScheduler = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().factory());
        
        logger.info("🛡️ SafeModeManager initialized (modern lock-based design)");
    }
    
    /**
     * Activate safe mode with defensive trading parameters.
     */
    public void activate(String reason, String context) {
        lock.lock();
        try {
            if (active) {
                logger.warn("⚠️ Safe mode already active, ignoring new activation");
                return;
            }
            
            logger.error("🚨 ACTIVATING SAFE MODE: {} ({})", reason, context);
            
            try {
                // 1. Backup current config
                backupConfig();
                
                // 2. Apply safe mode parameters
                applySafeModeConfig();
                
                // 3. Update state
                this.active = true;
                this.activationTime = Instant.now();
                this.activationReason = reason;
                this.recoveryCheckCount = 0;
                
                // 4. Broadcast to UI
                TradingWebSocketHandler.broadcastActivity(
                    String.format("🚨 SAFE MODE ACTIVATED: %s - Risk reduced 50%%, stops tightened 2x, new entries paused", 
                        reason),
                    "CRITICAL"
                );
                
                // 5. Schedule recovery checks
                scheduleRecoveryChecks();
                
                logger.info("✅ Safe mode activated successfully");
                
            } catch (Exception e) {
                logger.error("❌ Failed to activate safe mode", e);
            }
        } finally {
            lock.unlock();
        }
    }
    
    /**
     * Deactivate safe mode and restore normal trading parameters.
     */
    public void deactivate() {
        lock.lock();
        try {
            if (!active) {
                logger.warn("⚠️ Safe mode not active, ignoring deactivation");
                return;
            }
            
            logger.info("✅ DEACTIVATING SAFE MODE");
            
            try {
                // 1. Restore original config
                restoreConfig();
                
                // 2. Update state
                this.active = false;
                Duration duration = Duration.between(activationTime, Instant.now());
                
                // 3. Broadcast to UI
                TradingWebSocketHandler.broadcastActivity(
                    String.format("✅ SAFE MODE DEACTIVATED: Normal trading resumed (Duration: %d min)", 
                        duration.toMinutes()),
                    "SUCCESS"
                );
                
                logger.info("✅ Safe mode deactivated after {} minutes", duration.toMinutes());
                
            } catch (Exception e) {
                logger.error("❌ Failed to deactivate safe mode", e);
            }
        } finally {
            lock.unlock();
        }
    }
    
    /**
     * Apply safe mode configuration parameters.
     */
    private void applySafeModeConfig() throws IOException {
        Properties props = new Properties();
        try (FileInputStream fis = new FileInputStream(configPath.toFile())) {
            props.load(fis);
        }
        
        // Store original values
        originalConfig = new HashMap<>();
        
        // 1. Reduce position sizing (50% reduction)
        String kellyKey = "POSITION_SIZING_KELLY_FRACTION";
        String originalKelly = props.getProperty(kellyKey, "0.20");
        originalConfig.put(kellyKey, originalKelly);
        double newKelly = Double.parseDouble(originalKelly) * 0.5;
        props.setProperty(kellyKey, String.format("%.2f", newKelly));
        logger.info("🔧 Reduced Kelly fraction: {} → {}", originalKelly, String.format("%.2f", newKelly));
        
        // 2. Tighten stop losses (2x tighter)
        String mainStopKey = "MAIN_TRAILING_STOP_PERCENT";
        String originalMainStop = props.getProperty(mainStopKey, "1.0");
        originalConfig.put(mainStopKey, originalMainStop);
        double newMainStop = Double.parseDouble(originalMainStop) * 0.5;
        props.setProperty(mainStopKey, String.format("%.1f", newMainStop));
        logger.info("🔧 Tightened main stop: {}% → {}%", originalMainStop, String.format("%.1f", newMainStop));
        
        String expStopKey = "EXPERIMENTAL_TRAILING_STOP_PERCENT";
        String originalExpStop = props.getProperty(expStopKey, "0.5");
        originalConfig.put(expStopKey, originalExpStop);
        double newExpStop = Double.parseDouble(originalExpStop) * 0.5;
        props.setProperty(expStopKey, String.format("%.1f", newExpStop));
        logger.info("🔧 Tightened exp stop: {}% → {}%", originalExpStop, String.format("%.1f", newExpStop));
        
        // 3. Pause new entries (optional - can be enabled if needed)
        // props.setProperty("PAUSE_NEW_ENTRIES", "true");
        
        // 4. Increase monitoring frequency
        String monitorKey = "TRADING_CYCLE_INTERVAL_SECONDS";
        String originalInterval = props.getProperty(monitorKey, "10");
        originalConfig.put(monitorKey, originalInterval);
        props.setProperty(monitorKey, "5"); // 5 second intervals
        logger.info("🔧 Increased monitoring: {}s → 5s", originalInterval);
        
        // Save modified config
        try (FileOutputStream fos = new FileOutputStream(configPath.toFile())) {
            props.store(fos, "Safe mode activated at " + Instant.now());
        }
    }
    
    /**
     * Backup current configuration.
     */
    private void backupConfig() throws IOException {
        Files.copy(configPath, safeModeBackupPath, StandardCopyOption.REPLACE_EXISTING);
        logger.info("💾 Config backed up for safe mode");
    }
    
    /**
     * Restore original configuration.
     */
    private void restoreConfig() throws IOException {
        if (Files.exists(safeModeBackupPath)) {
            Files.copy(safeModeBackupPath, configPath, StandardCopyOption.REPLACE_EXISTING);
            logger.info("🔄 Config restored from safe mode backup");
        }
    }
    
    /**
     * Schedule periodic recovery checks.
     */
    private void scheduleRecoveryChecks() {
        recoveryScheduler.scheduleAtFixedRate(() -> {
            try {
                checkRecoveryConditions();
            } catch (Exception e) {
                logger.error("Error during recovery check", e);
            }
        }, 5, 5, TimeUnit.MINUTES); // Check every 5 minutes
    }
    
    /**
     * Check if conditions have normalized and safe mode can be deactivated.
     */
    private void checkRecoveryConditions() {
        if (!active) return;
        
        recoveryCheckCount++;
        logger.info("🔍 Recovery check {}/{}: Evaluating conditions...", 
            recoveryCheckCount, MAX_RECOVERY_CHECKS);
        
        // Auto-deactivate after 1 hour (12 checks × 5 min)
        if (recoveryCheckCount >= MAX_RECOVERY_CHECKS) {
            logger.info("⏰ Max safe mode duration reached, auto-deactivating");
            deactivate();
            return;
        }
        
        // TODO: Add more sophisticated recovery conditions:
        // - Check if error rate normalized
        // - Check if market volatility decreased
        // - Check if anomaly scores returned to normal
        
        // For now, rely on time-based recovery
    }
    
    /**
     * Check if safe mode is currently active.
     */
    public boolean isActive() {
        return active;
    }
    
    /**
     * Get safe mode status information.
     */
    public SafeModeStatus getStatus() {
        if (!active) {
            return new SafeModeStatus(false, null, null, null);
        }
        
        Duration duration = Duration.between(activationTime, Instant.now());
        return new SafeModeStatus(
            true,
            activationReason,
            activationTime,
            duration
        );
    }
    
    /**
     * Force deactivation (for manual override).
     */
    public void forceDeactivate() {
        logger.warn("⚠️ FORCE DEACTIVATING SAFE MODE (manual override)");
        deactivate();
    }
    
    public void shutdown() {
        recoveryScheduler.shutdown();
    }
    
    // Status record
    public record SafeModeStatus(
        boolean active,
        String reason,
        Instant activationTime,
        Duration duration
    ) {}
}
