package com.trading.core.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

/**
 * Central configuration manager for the trading bot.
 * Loads settings from config.properties (pre-migration settings).
 */
public class Config {
    private static final Logger logger = LoggerFactory.getLogger(Config.class);
    private static final Properties props = new Properties();
    private static boolean loaded = false;
    
    // Default config paths to search
    private static final String[] CONFIG_PATHS = {
        "config.properties",
        "../test-trade/config.properties",
        "/Users/igor/projects/java-edu/test-trade/config.properties"
    };
    
    static {
        loadConfig();
    }
    
    private static void loadConfig() {
        for (String path : CONFIG_PATHS) {
            try {
                Path configPath = Path.of(path);
                if (Files.exists(configPath)) {
                    try (FileInputStream fis = new FileInputStream(configPath.toFile())) {
                        props.load(fis);
                        loaded = true;
                        logger.info("📋 Loaded config from: {}", configPath.toAbsolutePath());
                        break;
                    }
                }
            } catch (IOException e) {
                logger.warn("Could not load config from {}: {}", path, e.getMessage());
            }
        }
        
        if (!loaded) {
            logger.warn("⚠️ No config.properties found, using defaults");
        }
    }
    
    // ==================== Trading Mode ====================
    public static String getTradingMode() {
        return props.getProperty("TRADING_MODE", "LIVE");
    }
    
    public static double getInitialCapital() {
        return getDouble("INITIAL_CAPITAL", 980.0);
    }
    
    // ==================== Main Profile Settings ====================
    public static double getMainTakeProfitPercent() {
        return getDouble("MAIN_TAKE_PROFIT_PERCENT", 0.75);
    }
    
    public static double getMainStopLossPercent() {
        return getDouble("MAIN_STOP_LOSS_PERCENT", 0.50);
    }
    
    public static double getMainTrailingStopPercent() {
        return getDouble("MAIN_TRAILING_STOP_PERCENT", 1.5);
    }
    
    public static List<String> getMainBullishSymbols() {
        return getList("MAIN_BULLISH_SYMBOLS", "SPY,QQQ,IWM,DIA,AAPL,MSFT,NVDA,GOOGL,AMZN,META,TSLA");
    }
    
    public static List<String> getMainBearishSymbols() {
        return getList("MAIN_BEARISH_SYMBOLS", "SH,PSQ,RWM,DOG");
    }
    
    // ==================== Experimental Profile Settings ====================
    public static double getExperimentalTakeProfitPercent() {
        return getDouble("EXPERIMENTAL_TAKE_PROFIT_PERCENT", 0.75);
    }
    
    public static double getExperimentalStopLossPercent() {
        return getDouble("EXPERIMENTAL_STOP_LOSS_PERCENT", 0.50);
    }
    
    public static double getExperimentalTrailingStopPercent() {
        return getDouble("EXPERIMENTAL_TRAILING_STOP_PERCENT", 0.75);
    }
    
    public static List<String> getExperimentalBullishSymbols() {
        return getList("EXPERIMENTAL_BULLISH_SYMBOLS", "SPY,QQQ,IWM,DIA,GLD,SLV,TLT,XLU");
    }
    
    public static List<String> getExperimentalBearishSymbols() {
        return getList("EXPERIMENTAL_BEARISH_SYMBOLS", "VXX,UVXY,VIXY");
    }
    
    // ==================== Multi-Profile Settings ====================
    public static boolean isMultiProfileEnabled() {
        return getBoolean("MULTI_PROFILE_ENABLED", true);
    }
    
    public static int getMainProfileCapitalPercent() {
        return getInt("MAIN_PROFILE_CAPITAL_PERCENT", 60);
    }
    
    public static int getExperimentalProfileCapitalPercent() {
        return getInt("EXPERIMENTAL_PROFILE_CAPITAL_PERCENT", 40);
    }
    
    // ==================== Risk Management ====================
    public static double getMaxLossPercent() {
        return getDouble("MAX_LOSS_PERCENT", 10.0);
    }
    
    public static boolean isMaxLossExitEnabled() {
        return getBoolean("MAX_LOSS_EXIT_ENABLED", true);
    }
    
    public static double getPortfolioStopLossPercent() {
        return getDouble("PORTFOLIO_STOP_LOSS_PERCENT", 5.0);
    }
    
    public static boolean isPortfolioStopLossEnabled() {
        return getBoolean("PORTFOLIO_STOP_LOSS_ENABLED", true);
    }
    
    // ==================== PDT Protection ====================
    public static boolean isPdtProtectionEnabled() {
        return getBoolean("PDT_PROTECTION_ENABLED", true);
    }
    
    public static int getMaxDayTradesPer5Days() {
        return getInt("MAX_DAY_TRADES_PER_5_DAYS", 3);
    }
    
    // ==================== Advanced Features ====================
    public static boolean isRegimeDetectionEnabled() {
        return getBoolean("REGIME_DETECTION_ENABLED", true);
    }
    
    public static boolean isMultiTimeframeEnabled() {
        return getBoolean("MULTI_TIMEFRAME_ENABLED", true);
    }
    
    public static String getPositionSizingMethod() {
        return props.getProperty("POSITION_SIZING_METHOD", "KELLY");
    }
    
    public static double getKellyFraction() {
        return getDouble("POSITION_SIZING_KELLY_FRACTION", 0.25);
    }
    
    public static double getKellyRiskReward() {
        return getDouble("POSITION_SIZING_KELLY_RISK_REWARD", 2.0);
    }
    
    public static double getDefaultWinRate() {
        return getDouble("POSITION_SIZING_DEFAULT_WIN_RATE", 0.55);
    }
    
    // ==================== VIX Settings ====================
    public static double getVixThreshold() {
        return getDouble("VIX_THRESHOLD", 20.0);
    }
    
    public static double getVixHysteresis() {
        return getDouble("VIX_HYSTERESIS", 2.0);
    }
    
    // ==================== Rate Limiting ====================
    public static int getApiRequestDelayMs() {
        return getInt("API_REQUEST_DELAY_MS", 300);
    }
    
    public static int getSymbolBatchSize() {
        return getInt("SYMBOL_BATCH_SIZE", 6);
    }
    
    // ==================== Utilities ====================
    public static double getDouble(String key, double defaultValue) {
        try {
            String value = props.getProperty(key);
            return value != null ? Double.parseDouble(value) : defaultValue;
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
    
    public static int getInt(String key, int defaultValue) {
        try {
            String value = props.getProperty(key);
            return value != null ? Integer.parseInt(value) : defaultValue;
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
    
    private static boolean getBoolean(String key, boolean defaultValue) {
        String value = props.getProperty(key);
        return value != null ? Boolean.parseBoolean(value) : defaultValue;
    }
    
    private static List<String> getList(String key, String defaultValue) {
        String value = props.getProperty(key, defaultValue);
        return Arrays.asList(value.split(","));
    }
    
    public static boolean isLoaded() {
        return loaded;
    }
    
    public static String getSummary() {
        return String.format(
            "Config: Mode=%s, MainTP=%.2f%%, MainSL=%.2f%%, ExpTP=%.2f%%, ExpSL=%.2f%%, " +
            "MultiProfile=%s, Kelly=%s, PDT=%s",
            getTradingMode(),
            getMainTakeProfitPercent(),
            getMainStopLossPercent(),
            getExperimentalTakeProfitPercent(),
            getExperimentalStopLossPercent(),
            isMultiProfileEnabled(),
            getPositionSizingMethod(),
            isPdtProtectionEnabled()
        );
    }
}
