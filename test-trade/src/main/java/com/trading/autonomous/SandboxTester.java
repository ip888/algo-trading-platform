package com.trading.autonomous;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/**
 * Full sandbox testing environment that spins up an isolated bot instance
 * to test configuration and code changes before promoting to production.
 */
public class SandboxTester {
    private static final Logger logger = LoggerFactory.getLogger(SandboxTester.class);
    
    private final Path sandboxRoot;
    private final Path botJarPath;
    private final ExecutorService executor;
    
    // Test configuration
    private static final Duration TEST_DURATION = Duration.ofSeconds(60);
    private static final int MAX_ERRORS_ALLOWED = 3;
    private static final int MIN_SUCCESSFUL_CYCLES = 5;
    
    public SandboxTester(Path botJarPath) {
        this.botJarPath = botJarPath;
        this.sandboxRoot = Paths.get(System.getProperty("java.io.tmpdir"), "trading-bot-sandbox");
        this.executor = Executors.newCachedThreadPool();
        
        logger.info("🧪 SandboxTester initialized: {}", sandboxRoot);
    }
    
    /**
     * Test configuration changes in isolated environment.
     */
    public CompletableFuture<TestResult> testConfigChange(
            Map<String, String> configChanges,
            String testReason) {
        
        return CompletableFuture.supplyAsync(() -> {
            SandboxEnvironment env = null;
            try {
                logger.info("🧪 Starting sandbox test: {}", testReason);
                
                // Step 1: Create isolated environment
                env = createSandboxEnvironment();
                
                // Step 2: Apply config changes
                applyConfigChanges(env, configChanges);
                
                // Step 3: Launch bot in test mode
                SandboxBot bot = launchSandboxBot(env);
                
                // Step 4: Monitor execution
                TestMetrics metrics = monitorBotExecution(bot, TEST_DURATION);
                
                // Step 5: Validate results
                boolean passed = validateTestResults(metrics);
                
                // Step 6: Cleanup
                bot.stop();
                
                if (passed) {
                    logger.info("✅ Sandbox test PASSED: {}", testReason);
                    return new TestResult(true, "All validations passed", metrics);
                } else {
                    logger.error("❌ Sandbox test FAILED: {}", testReason);
                    return new TestResult(false, "Validation failed: " + metrics.getFailureReason(), metrics);
                }
                
            } catch (Exception e) {
                logger.error("❌ Sandbox test error", e);
                return new TestResult(false, "Test error: " + e.getMessage(), null);
            } finally {
                if (env != null) {
                    cleanupEnvironment(env);
                }
            }
        }, executor);
    }
    
    /**
     * Create isolated sandbox environment.
     */
    private SandboxEnvironment createSandboxEnvironment() throws IOException {
        // Create unique sandbox directory
        String timestamp = String.valueOf(System.currentTimeMillis());
        Path sandboxDir = sandboxRoot.resolve("test-" + timestamp);
        Files.createDirectories(sandboxDir);
        
        logger.info("📁 Created sandbox: {}", sandboxDir);
        
        // Copy bot JAR
        Path sandboxJar = sandboxDir.resolve("bot.jar");
        Files.copy(botJarPath, sandboxJar, StandardCopyOption.REPLACE_EXISTING);
        
        // Copy current config as base
        Path currentConfig = Paths.get("config.properties");
        Path sandboxConfig = sandboxDir.resolve("config.properties");
        Files.copy(currentConfig, sandboxConfig, StandardCopyOption.REPLACE_EXISTING);
        
        // Create logs directory
        Path logsDir = sandboxDir.resolve("logs");
        Files.createDirectories(logsDir);
        
        return new SandboxEnvironment(sandboxDir, sandboxJar, sandboxConfig, logsDir);
    }
    
    /**
     * Apply configuration changes to sandbox config.
     */
    private void applyConfigChanges(SandboxEnvironment env, Map<String, String> changes) throws IOException {
        Properties props = new Properties();
        try (FileInputStream fis = new FileInputStream(env.configPath().toFile())) {
            props.load(fis);
        }
        
        // Apply changes
        for (Map.Entry<String, String> entry : changes.entrySet()) {
            String oldValue = props.getProperty(entry.getKey());
            props.setProperty(entry.getKey(), entry.getValue());
            logger.info("🔧 Config change: {} = {} → {}", 
                entry.getKey(), oldValue, entry.getValue());
        }
        
        // Save modified config
        try (FileOutputStream fos = new FileOutputStream(env.configPath().toFile())) {
            props.store(fos, "Sandbox test configuration");
        }
    }
    
    /**
     * Launch bot in sandbox environment.
     */
    private SandboxBot launchSandboxBot(SandboxEnvironment env) throws IOException {
        logger.info("🚀 Launching sandbox bot...");
        
        // Build command
        List<String> command = new ArrayList<>();
        command.add("java");
        command.add("--enable-preview");
        command.add("-Dtest.mode=true");  // Enable test mode
        command.add("-Dmarket.hours.bypass=true");  // Bypass market hours
        command.add("-jar");
        command.add(env.jarPath().toString());
        
        // Configure process
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(env.sandboxDir().toFile());
        pb.redirectErrorStream(true);
        
        // Set environment variables
        Map<String, String> envVars = pb.environment();
        envVars.put("TRADING_MODE", "PAPER");  // Force paper trading
        envVars.put("TEST_MODE_ENABLED", "true");
        
        // Start process
        Process process = pb.start();
        
        // Capture output
        BufferedReader reader = new BufferedReader(
            new InputStreamReader(process.getInputStream()));
        
        logger.info("✅ Sandbox bot started (PID: {})", process.pid());
        
        return new SandboxBot(process, reader, env);
    }
    
    /**
     * Monitor bot execution and collect metrics.
     */
    private TestMetrics monitorBotExecution(SandboxBot bot, Duration duration) {
        logger.info("👀 Monitoring sandbox bot for {}s...", duration.getSeconds());
        
        TestMetrics metrics = new TestMetrics();
        Instant startTime = Instant.now();
        Instant endTime = startTime.plus(duration);
        
        try {
            String line;
            while (Instant.now().isBefore(endTime) && bot.process().isAlive()) {
                line = bot.reader().readLine();
                if (line == null) break;
                
                // Parse log line
                analyzeLine(line, metrics);
                
                // Check for critical errors
                if (metrics.errorCount() > MAX_ERRORS_ALLOWED) {
                    logger.error("❌ Too many errors in sandbox: {}", metrics.errorCount());
                    break;
                }
            }
            
            // Wait for graceful shutdown
            bot.process().waitFor(5, TimeUnit.SECONDS);
            
        } catch (Exception e) {
            logger.error("Error monitoring sandbox", e);
            metrics.recordError("Monitoring error: " + e.getMessage());
        }
        
        metrics.finalize(Duration.between(startTime, Instant.now()));
        
        logger.info("📊 Sandbox metrics: {} cycles, {} errors, {} trades",
            metrics.cycleCount(), metrics.errorCount(), metrics.tradeCount());
        
        return metrics;
    }
    
    /**
     * Analyze log line and update metrics.
     */
    private void analyzeLine(String line, TestMetrics metrics) {
        if (line.contains("ERROR")) {
            metrics.recordError(line);
        } else if (line.contains("Trading cycle completed")) {
            metrics.recordCycle();
        } else if (line.contains("BUY ORDER FILLED") || line.contains("SELL ORDER FILLED")) {
            metrics.recordTrade();
        } else if (line.contains("insufficient buying power")) {
            metrics.recordError("Insufficient buying power");
        } else if (line.contains("Exception")) {
            metrics.recordError(line);
        }
    }
    
    /**
     * Validate test results against success criteria.
     */
    private boolean validateTestResults(TestMetrics metrics) {
        logger.info("✅ Validating test results...");
        
        // Check 1: Minimum successful cycles
        if (metrics.cycleCount() < MIN_SUCCESSFUL_CYCLES) {
            metrics.setFailureReason("Insufficient cycles: " + metrics.cycleCount());
            return false;
        }
        
        // Check 2: Error threshold
        if (metrics.errorCount() > MAX_ERRORS_ALLOWED) {
            metrics.setFailureReason("Too many errors: " + metrics.errorCount());
            return false;
        }
        
        // Check 3: No critical errors
        if (metrics.hasCriticalErrors()) {
            metrics.setFailureReason("Critical errors detected");
            return false;
        }
        
        // Check 4: Bot stayed alive
        if (metrics.duration().getSeconds() < TEST_DURATION.getSeconds() * 0.8) {
            metrics.setFailureReason("Bot terminated early");
            return false;
        }
        
        logger.info("✅ All validations passed");
        return true;
    }
    
    /**
     * Cleanup sandbox environment.
     */
    private void cleanupEnvironment(SandboxEnvironment env) {
        try {
            // Delete sandbox directory
            Files.walk(env.sandboxDir())
                .sorted(Comparator.reverseOrder())
                .forEach(path -> {
                    try {
                        Files.delete(path);
                    } catch (IOException e) {
                        logger.warn("Failed to delete: {}", path);
                    }
                });
            
            logger.info("🧹 Cleaned up sandbox: {}", env.sandboxDir());
        } catch (Exception e) {
            logger.warn("Cleanup error", e);
        }
    }
    
    public void shutdown() {
        executor.shutdown();
    }
    
    // Inner classes
    record SandboxEnvironment(
        Path sandboxDir,
        Path jarPath,
        Path configPath,
        Path logsDir
    ) {}
    
    record SandboxBot(
        Process process,
        BufferedReader reader,
        SandboxEnvironment environment
    ) {
        void stop() {
            process.destroy();
            try {
                process.waitFor(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                process.destroyForcibly();
            }
        }
    }
    
    public static class TestMetrics {
        private int cycleCount = 0;
        private int errorCount = 0;
        private int tradeCount = 0;
        private final List<String> errors = new ArrayList<>();
        private Duration duration;
        private String failureReason;
        
        void recordCycle() { cycleCount++; }
        void recordError(String error) { 
            errorCount++; 
            errors.add(error);
        }
        void recordTrade() { tradeCount++; }
        void finalize(Duration d) { this.duration = d; }
        void setFailureReason(String reason) { this.failureReason = reason; }
        
        int cycleCount() { return cycleCount; }
        int errorCount() { return errorCount; }
        int tradeCount() { return tradeCount; }
        Duration duration() { return duration; }
        String getFailureReason() { return failureReason; }
        
        boolean hasCriticalErrors() {
            return errors.stream().anyMatch(e -> 
                e.contains("OutOfMemory") || 
                e.contains("StackOverflow") ||
                e.contains("NullPointer"));
        }
    }
    
    public record TestResult(
        boolean passed,
        String message,
        TestMetrics metrics
    ) {}
}
