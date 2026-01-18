package com.trading.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Centralized trading configuration loaded from properties file.
 * All trading parameters should be accessed through this class.
 * 
 * Modern design:
 * - Uses AtomicReference for thread-safe singleton without synchronized
 * - Supports reset() for testing while maintaining thread safety
 */
public final class TradingConfig {
    private static final Logger logger = LoggerFactory.getLogger(TradingConfig.class);
    
    // Modern thread-safe singleton using AtomicReference
    private static final AtomicReference<TradingConfig> instanceRef = new AtomicReference<>();
    private final Properties properties;
    
    // Risk Management Parameters (with defaults matching config.properties)
    private final double stopLossPercent;
    private final double takeProfitPercent;
    private final double trailingStopPercent;
    private final double riskPerTrade;
    private final double maxDrawdown;
    
    // Portfolio-level limits
    private final double maxLossPercent;
    private final double portfolioStopLossPercent;
    
    // Crypto-specific (Kraken)
    private final double krakenStopLossPercent;
    private final double krakenTakeProfitPercent;
    private final double krakenTrailingStopPercent;
    
    private TradingConfig(Properties props) {
        this.properties = props;
        
        // Load risk parameters with sensible defaults
        // Main profile uses MAIN_ prefixed values, falling back to general values
        this.stopLossPercent = parsePercent("MAIN_STOP_LOSS_PERCENT", 
            parsePercent("STOP_LOSS_PERCENT", 0.5));
        this.takeProfitPercent = parsePercent("MAIN_TAKE_PROFIT_PERCENT", 
            parsePercent("TAKE_PROFIT_PERCENT", 0.75));
        this.trailingStopPercent = parsePercent("MAIN_TRAILING_STOP_PERCENT", 
            parsePercent("TRAILING_STOP_PERCENT", 0.5));
        this.riskPerTrade = parsePercent("RISK_PER_TRADE_PERCENT", 1.0);
        this.maxDrawdown = parsePercent("MAX_DRAWDOWN_PERCENT", 50.0);
        
        // Portfolio limits
        this.maxLossPercent = parsePercent("MAX_LOSS_PERCENT", 5.0);
        this.portfolioStopLossPercent = parsePercent("PORTFOLIO_STOP_LOSS_PERCENT", 3.0);
        
        // Kraken crypto settings
        this.krakenStopLossPercent = parsePercent("KRAKEN_STOP_LOSS_PERCENT", stopLossPercent);
        this.krakenTakeProfitPercent = parsePercent("KRAKEN_TAKE_PROFIT_PERCENT", takeProfitPercent);
        this.krakenTrailingStopPercent = parsePercent("KRAKEN_TRAILING_STOP_PERCENT", trailingStopPercent);
        
        logger.info("📊 Trading Configuration Loaded:");
        logger.info("   Stop-Loss: {}%", String.format("%.2f", stopLossPercent));
        logger.info("   Take-Profit: {}%", String.format("%.2f", takeProfitPercent));
        logger.info("   Trailing-Stop: {}%", String.format("%.2f", trailingStopPercent));
        logger.info("   Risk per Trade: {}%", String.format("%.2f", riskPerTrade));
        logger.info("   Max Drawdown: {}%", String.format("%.2f", maxDrawdown));
    }
    
    /**
     * Parse a percent value from properties.
     * Handles both decimal (0.5) and percentage (0.5%) formats.
     */
    private double parsePercent(String key, double defaultValue) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            double parsed = Double.parseDouble(value.trim());
            // If value > 1, assume it's already a percentage (e.g., 0.5 means 0.5%)
            // Config uses values like 0.5 for 0.5%, so return as-is
            return parsed;
        } catch (NumberFormatException e) {
            logger.warn("Invalid {} value '{}', using default {}", key, value, defaultValue);
            return defaultValue;
        }
    }
    
    /**
     * Get the singleton instance, loading from default config.properties.
     * Uses AtomicReference.updateAndGet for thread-safe lazy initialization.
     */
    public static TradingConfig getInstance() {
        return instanceRef.updateAndGet(existing -> 
            existing != null ? existing : load()
        );
    }
    
    /**
     * Load configuration from config.properties file.
     */
    public static TradingConfig load() {
        Properties props = new Properties();
        
        // Try loading from file system first (for production)
        Path configPath = Path.of("config.properties");
        if (Files.exists(configPath)) {
            try (InputStream is = Files.newInputStream(configPath)) {
                props.load(is);
                logger.info("Loaded config from: {}", configPath.toAbsolutePath());
                return new TradingConfig(props);
            } catch (IOException e) {
                logger.warn("Failed to load config.properties from filesystem: {}", e.getMessage());
            }
        }
        
        // Try loading from classpath (for tests)
        try (InputStream is = TradingConfig.class.getClassLoader().getResourceAsStream("config.properties")) {
            if (is != null) {
                props.load(is);
                logger.info("Loaded config from classpath");
                return new TradingConfig(props);
            }
        } catch (IOException e) {
            logger.warn("Failed to load config.properties from classpath: {}", e.getMessage());
        }
        
        logger.warn("No config.properties found, using defaults");
        return new TradingConfig(props);
    }
    
    /**
     * Create a test instance with custom properties.
     */
    public static TradingConfig forTest(Properties testProps) {
        return new TradingConfig(testProps);
    }
    
    /**
     * Reset singleton for testing.
     * Thread-safe using AtomicReference.
     */
    public static void reset() {
        instanceRef.set(null);
    }
    
    // ========== Getters ==========
    
    /** Stop-loss percentage (e.g., 0.5 = 0.5%) - as decimal for calculations use getStopLossDecimal() */
    public double getStopLossPercent() {
        return stopLossPercent;
    }
    
    /** Stop-loss as decimal (e.g., 0.005 for 0.5%) */
    public double getStopLossDecimal() {
        return stopLossPercent / 100.0;
    }
    
    /** Take-profit percentage (e.g., 0.75 = 0.75%) */
    public double getTakeProfitPercent() {
        return takeProfitPercent;
    }
    
    /** Take-profit as decimal (e.g., 0.0075 for 0.75%) */
    public double getTakeProfitDecimal() {
        return takeProfitPercent / 100.0;
    }
    
    /** Trailing stop percentage */
    public double getTrailingStopPercent() {
        return trailingStopPercent;
    }
    
    /** Trailing stop as decimal */
    public double getTrailingStopDecimal() {
        return trailingStopPercent / 100.0;
    }
    
    /** Risk per trade percentage (e.g., 1.0 = 1%) */
    public double getRiskPerTradePercent() {
        return riskPerTrade;
    }
    
    /** Risk per trade as decimal */
    public double getRiskPerTradeDecimal() {
        return riskPerTrade / 100.0;
    }
    
    /** Max drawdown percentage (e.g., 50 = 50%) */
    public double getMaxDrawdownPercent() {
        return maxDrawdown;
    }
    
    /** Max drawdown as decimal */
    public double getMaxDrawdownDecimal() {
        return maxDrawdown / 100.0;
    }
    
    /** Max portfolio loss percentage */
    public double getMaxLossPercent() {
        return maxLossPercent;
    }
    
    /** Portfolio stop-loss percentage */
    public double getPortfolioStopLossPercent() {
        return portfolioStopLossPercent;
    }
    
    /** Kraken stop-loss percentage */
    public double getKrakenStopLossPercent() {
        return krakenStopLossPercent;
    }
    
    /** Kraken take-profit percentage */
    public double getKrakenTakeProfitPercent() {
        return krakenTakeProfitPercent;
    }
    
    /** Kraken trailing stop percentage */
    public double getKrakenTrailingStopPercent() {
        return krakenTrailingStopPercent;
    }
    
    /** Get raw property value */
    public String getProperty(String key) {
        return properties.getProperty(key);
    }
    
    /** Get property with default */
    public String getProperty(String key, String defaultValue) {
        return properties.getProperty(key, defaultValue);
    }
}
