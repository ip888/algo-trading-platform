package com.trading.autonomous;

import com.trading.config.Config;
import com.trading.persistence.TradeDatabase;
import com.trading.websocket.TradingWebSocketHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/**
 * Autonomous configuration-based self-healing system.
 * Detects errors, applies fixes, tests in sandbox, then promotes to production.
 */
public class ConfigSelfHealer {
    private static final Logger logger = LoggerFactory.getLogger(ConfigSelfHealer.class);

    private final Config config;
    private final ErrorDetector errorDetector;
    private final SandboxTester sandboxTester;
    private final TradeDatabase database;
    private final Path configPath;
    private final Path backupPath;

    // Safety limits
    private static final int MAX_HEALS_PER_HOUR = 3;
    private static final int MAX_TOTAL_HEALS = 10;
    private final Map<String, Instant> healHistory = new ConcurrentHashMap<>();
    private int totalHeals = 0;

    // Sandbox testing
    private final ExecutorService sandboxExecutor = Executors.newSingleThreadExecutor();

    public ConfigSelfHealer(Config config, ErrorDetector errorDetector, TradeDatabase database) {
        this.config = config;
        this.errorDetector = errorDetector;
        this.database = database;
        this.configPath = Paths.get("config.properties");
        this.backupPath = Paths.get("config.properties.backup.selfheal");

        // Initialize full sandbox tester
        Path botJar = Paths.get("target/alpaca-trading-bot-1.0.0.jar");
        this.sandboxTester = new SandboxTester(botJar);

        logger.info("🔧 ConfigSelfHealer initialized (Max heals: {}/hour, {}/total)",
            MAX_HEALS_PER_HOUR, MAX_TOTAL_HEALS);
        logger.info("🧪 Full sandbox testing enabled");
    }
    
    /**
     * Attempt to heal an error by adjusting configuration.
     */
    public CompletableFuture<HealResult> heal(ErrorDetector.ErrorAnalysis analysis) {
        // Check safety limits
        if (!canHeal()) {
            logger.warn("⚠️ Healing rate limit reached, skipping auto-heal");
            return CompletableFuture.completedFuture(
                new HealResult(false, "Rate limit reached", null));
        }
        
        // Get fix from error pattern
        if (analysis.pattern() == null) {
            logger.info("ℹ️ No known fix for error: {}", analysis.errorType());
            return CompletableFuture.completedFuture(
                new HealResult(false, "No known fix pattern", null));
        }
        
        logger.info("🔧 SELF-HEAL: Attempting to fix {} ({})", 
            analysis.pattern().name(), analysis.pattern().suggestedFix());
        
        // Broadcast to UI
        TradingWebSocketHandler.broadcastActivity(
            String.format("🔧 AUTO-HEAL: Detected %s (%d occurrences) - Applying fix...", 
                analysis.pattern().name(), analysis.occurrenceCount()),
            "WARN"
        );
        
        return CompletableFuture.supplyAsync(() -> {
            try {
                // Step 1: Backup current config
                backupConfig();
                
                // Step 2: Apply configuration adjustments
                Map<String, String> adjustments = analysis.pattern().configAdjustments();
                applyConfigAdjustments(adjustments);
                
                // Step 3: Test in sandbox
                boolean testPassed = testInSandbox(analysis);
                
                if (testPassed) {
                    // Step 4: Promote to production
                    logger.info("✅ Sandbox test passed, promoting fix to production");
                    recordHeal(analysis.pattern().name());

                    // Reset error counts since we fixed it
                    errorDetector.resetCounts(analysis.pattern().name());

                    // Broadcast success
                    TradingWebSocketHandler.broadcastActivity(
                        String.format("✅ AUTO-HEAL SUCCESS: Fixed %s - Config updated",
                            analysis.pattern().name()),
                        "SUCCESS"
                    );
                    recordHealAudit(analysis.pattern().name(), adjustments, true,
                        "Fix applied and tested successfully");

                    return new HealResult(true, "Fix applied and tested successfully", adjustments);
                } else {
                    // Step 5: Rollback if test failed
                    logger.error("❌ Sandbox test failed, rolling back changes");
                    rollbackConfig();

                    // Broadcast failure
                    TradingWebSocketHandler.broadcastActivity(
                        String.format("❌ AUTO-HEAL FAILED: %s - Sandbox test failed, rolled back",
                            analysis.pattern().name()),
                        "ERROR"
                    );
                    recordHealAudit(analysis.pattern().name(), adjustments, false,
                        "Sandbox test failed, rolled back");

                    return new HealResult(false, "Sandbox test failed, rolled back", null);
                }
            } catch (Exception e) {
                logger.error("❌ Self-healing failed", e);
                try {
                    rollbackConfig();
                } catch (IOException rollbackError) {
                    logger.error("❌ Rollback failed!", rollbackError);
                }
                recordHealAudit(analysis.pattern().name(), analysis.pattern().configAdjustments(), false,
                    "Healing failed: " + e.getMessage());

                TradingWebSocketHandler.broadcastActivity(
                    String.format("❌ AUTO-HEAL ERROR: %s - %s",
                        analysis.pattern().name(), e.getMessage()),
                    "ERROR"
                );
                
                return new HealResult(false, "Healing failed: " + e.getMessage(), null);
            }
        }, sandboxExecutor);
    }
    
    /**
     * Apply configuration adjustments.
     */
    private void applyConfigAdjustments(Map<String, String> adjustments) throws IOException {
        Properties props = new Properties();
        try (FileInputStream fis = new FileInputStream(configPath.toFile())) {
            props.load(fis);
        }
        
        for (Map.Entry<String, String> entry : adjustments.entrySet()) {
            String key = entry.getKey();
            String operation = entry.getValue();
            
            String currentValue = props.getProperty(key);
            if (currentValue == null) {
                logger.warn("⚠️ Config key not found: {}", key);
                continue;
            }
            
            String newValue = applyOperation(currentValue, operation);
            props.setProperty(key, newValue);
            
            logger.info("🔧 Adjusted {}: {} → {}", key, currentValue, newValue);
        }
        
        // Save updated config
        try (FileOutputStream fos = new FileOutputStream(configPath.toFile())) {
            props.store(fos, "Auto-healed by ConfigSelfHealer at " + Instant.now());
        }
    }
    
    /**
     * Apply mathematical operation to config value.
     */
    private String applyOperation(String currentValue, String operation) {
        if (operation.startsWith("multiply:")) {
            double multiplier = Double.parseDouble(operation.substring(9));
            double current = Double.parseDouble(currentValue);
            return String.format("%.2f", current * multiplier);
        } else if (operation.startsWith("add:")) {
            double addend = Double.parseDouble(operation.substring(4));
            double current = Double.parseDouble(currentValue);
            return String.format("%.0f", current + addend);
        } else if (operation.equals("true") || operation.equals("false")) {
            return operation;
        }
        return operation;
    }
    
    /**
     * Test configuration changes in isolated sandbox with full bot instance.
     * Returns true if test passes, false otherwise.
     */
    private boolean testInSandbox(ErrorDetector.ErrorAnalysis analysis) {
        logger.info("🧪 Testing fix in FULL SANDBOX (isolated bot instance for 60s)...");
        
        try {
            // Use full sandbox tester
            CompletableFuture<SandboxTester.TestResult> testFuture = 
                sandboxTester.testConfigChange(
                    analysis.pattern().configAdjustments(),
                    analysis.pattern().name()
                );
            
            // Wait for test to complete (with timeout)
            SandboxTester.TestResult result = testFuture.get(90, TimeUnit.SECONDS);
            
            if (result.passed()) {
                logger.info("✅ Full sandbox test PASSED: {} cycles, {} errors, {} trades",
                    result.metrics().cycleCount(),
                    result.metrics().errorCount(),
                    result.metrics().tradeCount());
                return true;
            } else {
                logger.error("❌ Full sandbox test FAILED: {}",  result.message());
                return false;
            }
            
        } catch (TimeoutException e) {
            logger.error("❌ Sandbox test timeout (>90s)");
            return false;
        } catch (Exception e) {
            logger.error("❌ Sandbox test error", e);
            return false;
        }
    }
    
    /**
     * Backup current configuration.
     */
    private void backupConfig() throws IOException {
        Files.copy(configPath, backupPath, StandardCopyOption.REPLACE_EXISTING);
        logger.info("💾 Config backed up to: {}", backupPath);
    }
    
    /**
     * Rollback to previous configuration.
     */
    private void rollbackConfig() throws IOException {
        if (Files.exists(backupPath)) {
            Files.copy(backupPath, configPath, StandardCopyOption.REPLACE_EXISTING);
            logger.info("🔄 Config rolled back from backup");
        }
    }
    
    /**
     * Check if healing is allowed (rate limiting).
     */
    private boolean canHeal() {
        // Check total heals limit
        if (totalHeals >= MAX_TOTAL_HEALS) {
            return false;
        }
        
        // Check hourly rate limit
        Instant oneHourAgo = Instant.now().minusSeconds(3600);
        long recentHeals = healHistory.values().stream()
            .filter(time -> time.isAfter(oneHourAgo))
            .count();
        
        return recentHeals < MAX_HEALS_PER_HOUR;
    }
    
    /**
     * Record successful heal.
     */
    private void recordHeal(String errorPattern) {
        healHistory.put(errorPattern, Instant.now());
        totalHeals++;
        logger.info("📊 Heal recorded: {} (Total: {}/{})", errorPattern, totalHeals, MAX_TOTAL_HEALS);
    }

    /**
     * Persist every heal attempt (success or failure) to bot_state and push a
     * notification — previously the only record of an autonomous config edit was an
     * app log line and a WebSocket broadcast, neither of which the operator sees
     * outside the dashboard. Reuses the same ntfy.sh pattern as the EOD summary push
     * in ProfileManager (set NTFY_TOPIC to enable).
     */
    private void recordHealAudit(String errorPattern, Map<String, String> adjustments,
                                  boolean success, String message) {
        try {
            String key = "config_heal:" + Instant.now().toEpochMilli();
            String adjustmentsJson = adjustments == null ? "{}" : adjustments.entrySet().stream()
                .map(e -> String.format("\"%s\":\"%s\"", e.getKey(), e.getValue()))
                .reduce((a, b) -> a + "," + b)
                .map(s -> "{" + s + "}")
                .orElse("{}");
            String record = String.format(
                "{\"pattern\":\"%s\",\"success\":%b,\"message\":\"%s\",\"adjustments\":%s,\"timestamp\":\"%s\"}",
                errorPattern, success, message.replace("\"", "'"), adjustmentsJson, Instant.now());
            if (database != null) {
                database.saveBotState(key, record);
            }
        } catch (Exception e) {
            logger.warn("Failed to write self-heal audit record: {}", e.getMessage());
        }

        String ntfyTopic = System.getenv("NTFY_TOPIC");
        if (ntfyTopic == null || ntfyTopic.isBlank()) {
            return;
        }
        try {
            String msg = String.format("%s %s: %s",
                success ? "✅" : "❌", errorPattern, message);
            var req = HttpRequest.newBuilder()
                .uri(URI.create("https://ntfy.sh/" + ntfyTopic))
                .POST(HttpRequest.BodyPublishers.ofString(msg))
                .header("Title", "Trading Bot Self-Heal")
                .header("Priority", success ? "default" : "high")
                .build();
            HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            logger.warn("Self-heal ntfy push failed: {}", e.getMessage());
        }
    }


    /**
     * Get healing statistics for dashboard.
     */
    public Map<String, Object> getStatistics() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("totalHeals", totalHeals);
        stats.put("maxHeals", MAX_TOTAL_HEALS);
        stats.put("recentHeals", healHistory.size());
        stats.put("canHeal", canHeal());
        return stats;
    }
    
    public void shutdown() {
        sandboxExecutor.shutdown();
        sandboxTester.shutdown();
    }
    
    // Result record
    public record HealResult(
        boolean success,
        String message,
        Map<String, String> appliedAdjustments
    ) {}
}
