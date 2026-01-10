package com.trading.autonomous;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;

/**
 * Build pipeline for compiling and packaging code changes.
 * Handles Maven compilation, JAR creation, and build artifact management.
 */
public class BuildPipeline {
    private static final Logger logger = LoggerFactory.getLogger(BuildPipeline.class);
    
    private final Path projectRoot;
    private final Path targetDir;
    
    public BuildPipeline(Path projectRoot) {
        this.projectRoot = projectRoot;
        this.targetDir = projectRoot.resolve("target");
        logger.info("🔨 BuildPipeline initialized: {}", projectRoot);
    }
    
    /**
     * Apply code fix to source file.
     */
    public void applyCodeFix(ClaudeCodeGenerator.CodeFix fix) throws IOException {
        Path filePath = projectRoot.resolve(fix.filePath());
        
        // Backup original file
        Path backupPath = Paths.get(filePath.toString() + ".backup");
        Files.copy(filePath, backupPath, StandardCopyOption.REPLACE_EXISTING);
        
        // Write new code
        Files.writeString(filePath, fix.getFullCode());
        
        logger.info("✅ Applied code fix to: {}", fix.filePath());
    }
    
    /**
     * Compile project with Maven.
     */
    public CompilationResult compile() {
        logger.info("🔨 Starting Maven compilation...");
        
        try {
            // Build Maven command
            ProcessBuilder pb = new ProcessBuilder(
                "mvn", "clean", "package", "-DskipTests", "-q"
            );
            pb.directory(projectRoot.toFile());
            pb.redirectErrorStream(true);
            
            // Start compilation
            Process process = pb.start();
            
            // Capture output
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }
            
            // Wait for completion (max 5 minutes)
            boolean completed = process.waitFor(5, TimeUnit.MINUTES);
            
            if (!completed) {
                process.destroyForcibly();
                return CompilationResult.failed("Compilation timeout (>5 min)");
            }
            
            int exitCode = process.exitValue();
            
            if (exitCode == 0) {
                Path jarPath = targetDir.resolve("alpaca-trading-bot-1.0.0.jar");
                logger.info("✅ Compilation successful: {}", jarPath);
                return CompilationResult.success(jarPath, output.toString());
            } else {
                logger.error("❌ Compilation failed (exit code: {})", exitCode);
                return CompilationResult.failed("Exit code: " + exitCode + "\n" + output);
            }
            
        } catch (Exception e) {
            logger.error("❌ Compilation error", e);
            return CompilationResult.failed("Exception: " + e.getMessage());
        }
    }
    
    /**
     * Rollback code changes.
     */
    public void rollback(String filePath) throws IOException {
        Path file = projectRoot.resolve(filePath);
        Path backup = Paths.get(file.toString() + ".backup");
        
        if (Files.exists(backup)) {
            Files.copy(backup, file, StandardCopyOption.REPLACE_EXISTING);
            Files.delete(backup);
            logger.info("🔄 Rolled back: {}", filePath);
        }
    }
    
    /**
     * Compilation result.
     */
    public record CompilationResult(
        boolean success,
        Path jarPath,
        String buildLog,
        String errorMessage
    ) {
        public static CompilationResult success(Path jarPath, String buildLog) {
            return new CompilationResult(true, jarPath, buildLog, null);
        }
        
        public static CompilationResult failed(String error) {
            return new CompilationResult(false, null, null, error);
        }
    }
}
