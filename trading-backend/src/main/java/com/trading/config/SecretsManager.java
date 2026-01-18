package com.trading.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Manages secure loading of secrets from environment variables and .env files.
 * Priority: Environment variables > .env file
 */
public final class SecretsManager {
    private static final Logger logger = LoggerFactory.getLogger(SecretsManager.class);
    private static final Map<String, String> secrets = new HashMap<>();
    
    static {
        loadSecrets();
    }
    
    private SecretsManager() {
        // Utility class
    }
    
    private static void loadSecrets() {
        // 1. Try environment variables first (highest priority)
        loadFromEnvironment();
        
        // 2. Try .env file (second priority)
        loadFromDotEnv();
        
        // 3. Validate required secrets
        validateRequiredSecrets();
        
        logger.info("Secrets loaded successfully from {} sources", 
            secrets.isEmpty() ? "no" : "environment/file");
    }
    
    private static void loadFromEnvironment() {
        System.getenv().forEach((key, value) -> {
            if (isRelevantSecret(key)) {
                secrets.put(key, value);
                logger.debug("Loaded secret from environment: {}", key);
            }
        });
    }
    
    private static boolean isRelevantSecret(String key) {
        return key.startsWith("APCA_") || 
               key.startsWith("TRADING_") || 
               key.startsWith("TELEGRAM_") || 
               key.startsWith("EMAIL_") ||
               key.startsWith("INITIAL_") ||
               key.startsWith("PDT_") ||
               key.startsWith("EXTENDED_") ||
               key.startsWith("TEST_");
    }
    
    private static void loadFromDotEnv() {
        Path envFile = Path.of(".env");
        if (!Files.exists(envFile)) {
            logger.info(".env file not found, using environment variables only");
            return;
        }
        
        try {
            Files.lines(envFile)
                .filter(line -> !line.trim().isEmpty() && !line.startsWith("#"))
                .forEach(line -> {
                    String[] parts = line.split("=", 2);
                    if (parts.length == 2) {
                        String key = parts[0].trim();
                        String value = parts[1].trim();
                        // Remove quotes if present
                        if (value.startsWith("\"") && value.endsWith("\"")) {
                            value = value.substring(1, value.length() - 1);
                        }
                        secrets.putIfAbsent(key, value); // Don't override env vars
                        logger.debug("Loaded secret from .env: {}", key);
                    }
                });
            logger.info("Loaded secrets from .env file");
        } catch (IOException e) {
            logger.error("Failed to read .env file", e);
        }
    }
    
    private static void validateRequiredSecrets() {
        String[] required = {
            "APCA_API_KEY_ID",
            "APCA_API_SECRET_KEY"
        };
        
        for (String key : required) {
            if (!secrets.containsKey(key) || secrets.get(key).isBlank()) {
                throw new IllegalStateException(
                    "Required secret missing: " + key + 
                    ". Please set in environment or .env file. " +
                    "See .env.template for reference."
                );
            }
        }
    }
    
    /**
     * Get a secret value by key.
     * @param key the secret key
     * @return the secret value, or null if not found
     */
    public static String getSecret(String key) {
        return secrets.get(key);
    }
    
    /**
     * Get a secret value with a default fallback.
     * @param key the secret key
     * @param defaultValue the default value if secret not found
     * @return the secret value or default
     */
    public static String getSecretOrDefault(String key, String defaultValue) {
        return secrets.getOrDefault(key, defaultValue);
    }
    
    /**
     * Check if a secret exists and is not blank.
     * @param key the secret key
     * @return true if secret exists and is not blank
     */
    public static boolean hasSecret(String key) {
        return secrets.containsKey(key) && !secrets.get(key).isBlank();
    }
    
    /**
     * Get a secret as an integer.
     * @param key the secret key
     * @param defaultValue the default value if not found or invalid
     * @return the integer value
     */
    public static int getSecretAsInt(String key, int defaultValue) {
        String value = secrets.get(key);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            logger.warn("Invalid integer value for {}: {}", key, value);
            return defaultValue;
        }
    }
    
    /**
     * Get a secret as a double.
     * @param key the secret key
     * @param defaultValue the default value if not found or invalid
     * @return the double value
     */
    public static double getSecretAsDouble(String key, double defaultValue) {
        String value = secrets.get(key);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            logger.warn("Invalid double value for {}: {}", key, value);
            return defaultValue;
        }
    }
    
    /**
     * Get a secret as a boolean.
     * @param key the secret key
     * @param defaultValue the default value if not found
     * @return the boolean value
     */
    public static boolean getSecretAsBoolean(String key, boolean defaultValue) {
        String value = secrets.get(key);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return Boolean.parseBoolean(value);
    }
}
