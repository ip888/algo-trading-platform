package com.trading.config;

import jakarta.validation.constraints.*;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Properties;

/**
 * Configuration manager for Alpaca API credentials and bot settings.
 * Loads from environment variables or config.properties file.
 */
public final class Config {
    private static final Logger logger = LoggerFactory.getLogger(Config.class);
    private static final String CONFIG_FILE = "config.properties";
    private static final String DEFAULT_BASE_URL = "https://paper-api.alpaca.markets";
    
    @NotBlank(message = "API key is required")
    private final String apiKey;
    
    @NotBlank(message = "API secret is required")
    private final String apiSecret;
    
    @NotBlank(message = "Base URL is required")
    @Pattern(regexp = "^https://.*", message = "Base URL must use HTTPS")
    private final String baseUrl;
    
    private final Properties properties;

    public Config() {
        this.properties = loadProperties();
        this.baseUrl = loadBaseUrl();
        var credentials = loadCredentials();
        this.apiKey = credentials[0];
        this.apiSecret = credentials[1];
        
        // Validate configuration
        validate();
        
        if (isValid()) {
            logger.info("Configuration loaded successfully from {}", 
                apiKey != null && !apiKey.isEmpty() ? "environment/file" : "unknown");
        } else {
            logger.warn("Configuration incomplete - missing API credentials");
        }
    }
    
    private Properties loadProperties() {
        var props = new Properties();
        try (var fis = new FileInputStream(CONFIG_FILE)) {
            props.load(fis);
            logger.debug("Loaded properties from {}", CONFIG_FILE);
        } catch (IOException e) {
            logger.debug("No config.properties found");
        }
        return props;
    }

    private String[] loadCredentials() {
        // Try environment variables first
        var envKey = System.getenv("APCA_API_KEY_ID");
        var envSecret = System.getenv("APCA_API_SECRET_KEY");
        
        if (envKey != null && envSecret != null) {
            logger.debug("Loaded credentials from environment variables");
            return new String[]{envKey, envSecret};
        }

        // Fall back to properties file
        var key = properties.getProperty("APCA_API_KEY_ID");
        var secret = properties.getProperty("APCA_API_SECRET_KEY");
        
        if (key != null && secret != null) {
            logger.debug("Loaded credentials from {}", CONFIG_FILE);
            return new String[]{key, secret};
        }
        
        return new String[]{null, null};
    }

    private String loadBaseUrl() {
        return Optional.ofNullable(System.getenv("APCA_API_BASE_URL"))
                .or(() -> Optional.ofNullable(properties.getProperty("APCA_API_BASE_URL")))
                .orElse(DEFAULT_BASE_URL);
    }

    public String apiKey() {
        return apiKey;
    }

    public String apiSecret() {
        return apiSecret;
    }

    public String baseUrl() {
        return baseUrl;
    }
    
    public boolean isValid() {
        return apiKey != null && !apiKey.isEmpty() && 
               apiSecret != null && !apiSecret.isEmpty();
    }
    
    /**
     * Validate configuration using Bean Validation.
     * Throws IllegalStateException if validation fails.
     */
    public void validate() {
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        var violations = validator.validate(this);
        
        if (!violations.isEmpty()) {
            var errorMessages = violations.stream()
                .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                .toList();
            
            throw new IllegalStateException(
                "Configuration validation failed: " + String.join(", ", errorMessages)
            );
        }
    }
    
    // Notification settings
    public String getTelegramBotToken() {
        return getProperty("TELEGRAM_BOT_TOKEN");
    }
    
    public String getTelegramChatId() {
        return getProperty("TELEGRAM_CHAT_ID");
    }
    
    public String getEmailAddress() {
        return getProperty("EMAIL_ADDRESS");
    }
    
    public String getEmailPassword() {
        return getProperty("EMAIL_PASSWORD");
    }
    
    public String getEmailSmtpHost() {
        return getProperty("EMAIL_SMTP_HOST");
    }
    
    public String getEmailSmtpPort() {
        return getProperty("EMAIL_SMTP_PORT");
    }
    
    // Trading mode
    public String getKrakenWebsocketUrl() {
        return getProperty("KRAKEN_WEBSOCKET_URL", "wss://ws.kraken.com");
    }
    
    // ==================== Kraken-Specific Trading Settings ====================
    
    /**
     * Kraken take-profit percentage (default 0.75% for micro-profit)
     */
    public double getKrakenTakeProfitPercent() {
        return Double.parseDouble(getProperty("KRAKEN_TAKE_PROFIT_PERCENT", "0.75")) / 100.0;
    }
    
    /**
     * Kraken stop-loss percentage (default 0.5%)
     */
    public double getKrakenStopLossPercent() {
        return Double.parseDouble(getProperty("KRAKEN_STOP_LOSS_PERCENT", "0.5")) / 100.0;
    }
    
    /**
     * Kraken trailing stop percentage (default 0.5%)
     */
    public double getKrakenTrailingStopPercent() {
        return Double.parseDouble(getProperty("KRAKEN_TRAILING_STOP_PERCENT", "0.5")) / 100.0;
    }
    
    /**
     * Maximum concurrent Kraken positions (default 3)
     */
    public int getKrakenMaxPositions() {
        return Integer.parseInt(getProperty("KRAKEN_MAX_POSITIONS", "3"));
    }
    
    /**
     * Position size in USD for Kraken crypto trades (default $75 to meet minimums)
     */
    public double getKrakenPositionSizeUsd() {
        return Double.parseDouble(getProperty("KRAKEN_POSITION_SIZE_USD", "75"));
    }
    
    /**
     * Kraken trading loop cycle interval in milliseconds (default 45000 = 45 sec)
     * Increased to reduce API calls and prevent rate limiting.
     * Kraken allows ~15 private calls/minute, trading loop uses ~3-4 calls.
     */
    public long getKrakenCycleIntervalMs() {
        return Long.parseLong(getProperty("KRAKEN_CYCLE_INTERVAL_MS", "45000"));
    }
    
    /**
     * Grid trading position size for fishing orders (default $35)
     */
    public double getKrakenGridPositionSize() {
        return Double.parseDouble(getProperty("KRAKEN_GRID_POSITION_SIZE", "35"));
    }
    
    /**
     * Maximum concurrent grid orders (to preserve buying power)
     * Default: 2 (limits capital tied up in pending orders)
     */
    public int getKrakenMaxGridOrders() {
        return Integer.parseInt(getProperty("KRAKEN_MAX_GRID_ORDERS", "2"));
    }
    
    /**
     * Stale order timeout in minutes (orders older than this are canceled)
     * Default: 5 minutes
     */
    public int getKrakenStaleOrderMinutes() {
        return Integer.parseInt(getProperty("KRAKEN_STALE_ORDER_MINUTES", "5"));
    }
    
    // ==================== Platform Configuration ====================
    
    /**
     * Check if Alpaca platform is enabled.
     * Default: true
     */
    public boolean isAlpacaEnabled() {
        return getBooleanProperty("ALPACA_ENABLED", true);
    }
    
    /**
     * Check if Kraken platform is enabled.
     * Default: true
     */
    public boolean isKrakenEnabled() {
        return getBooleanProperty("KRAKEN_ENABLED", true);
    }
    
    public String getTradingMode() {
        return getProperty("TRADING_MODE", "PAPER");
    }
    
    public boolean isLiveTradingMode() {
        return "LIVE".equalsIgnoreCase(getTradingMode());
    }
    
    private String getProperty(String key) {
        return Optional.ofNullable(System.getenv(key))
                .or(() -> Optional.ofNullable(properties.getProperty(key)))
                .orElse(null);
    }
    
    private String getProperty(String key, String defaultValue) {
        return Optional.ofNullable(System.getenv(key))
                .or(() -> Optional.ofNullable(properties.getProperty(key)))
                .orElse(defaultValue);
    }

    /**
     * Check if market hours bypass is enabled (for testing/development).
     */
    public boolean isMarketHoursBypassEnabled() {
        return getBooleanProperty("BYPASS_MARKET_HOURS", true); // 🚨 FORCE BYPASS FOR LIQUIDATION
    }
    
    /**
     * Check if test mode is enabled (simulates trades for demonstration).
     */
    public boolean isTestModeEnabled() {
        return getBooleanProperty("TEST_MODE", false);
    }
    
    /**
     * Get test mode frequency in seconds (how often to generate test trades).
     */
    public int getTestModeFrequency() {
        return getIntProperty("TEST_MODE_FREQUENCY", 60); // Default: every 60 seconds
    }
    
    /**
     * Get initial trading capital.
     */
    @Positive(message = "Initial capital must be positive")
    @Min(value = 100, message = "Minimum capital is $100")
    public double getInitialCapital() {
        return getDoubleProperty("INITIAL_CAPITAL", 1000.0);
    }

    public boolean isPDTProtectionEnabled() {
        return getBooleanProperty("PDT_PROTECTION_ENABLED", true); // Default: enabled for safety
    }

    /**
     * Check if extended hours trading is enabled.
     * Allows trading during pre-market (4:00-9:30 AM) and post-market (4:00-8:00 PM).
     */
    public boolean isExtendedHoursEnabled() {
        return getBooleanProperty("EXTENDED_HOURS_ENABLED", false); // Default: disabled
    }
    
    private boolean getBooleanProperty(String key, boolean defaultValue) {
        String value = getProperty(key);
        if (value == null || value.isEmpty()) {
            return defaultValue;
        }
        return Boolean.parseBoolean(value);
    }
    
    private int getIntProperty(String key, int defaultValue) {
        String value = getProperty(key);
        if (value == null || value.isEmpty()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            logger.warn("Invalid integer value for {}: {}", key, value);
            return defaultValue;
        }
    }


    public String getForcedMarketTrend() {
        return getProperty("FORCE_MARKET_TREND");
    }

    /**
     * Get bullish symbols for normal/low volatility trading.
     */
    public java.util.List<String> getBullishSymbols() {
        String symbols = getProperty("BULLISH_SYMBOLS", "SPY,QQQ,IWM");
        return java.util.Arrays.asList(symbols.split(","));
    }

    /**
     * Get bearish symbols for high volatility trading.
     */
    public java.util.List<String> getBearishSymbols() {
        String symbols = getProperty("BEARISH_SYMBOLS", "SH,PSQ,RWM");
        return java.util.Arrays.asList(symbols.split(","));
    }
    
    /**
     * Get crypto symbols for 24/7 Kraken trading.
     */
    public java.util.List<String> getCryptoSymbols() {
        String symbols = getProperty("CRYPTO_SYMBOLS", "BTC/USD,ETH/USD,SOL/USD");
        return java.util.Arrays.asList(symbols.split(","));
    }

    /**
     * Get VIX threshold for switching to bearish mode.
     */
    public double getVixThreshold() {
        return getDoubleProperty("VIX_THRESHOLD", 20.0);
    }

    /**
     * Get hysteresis value to prevent frequent mode switching.
     */
    public double getVixHysteresis() {
        return getDoubleProperty("VIX_HYSTERESIS", 2.0);
    }

    // ==================== Multi-Profile Configuration ====================
    
    public boolean isMultiProfileEnabled() {
        return getBooleanProperty("MULTI_PROFILE_ENABLED", false);
    }

    // Main Profile Settings    
    public double getMainProfileCapitalPercent() {
        // FIX: Convert integer percentage to decimal (60 → 0.60)
        return getIntProperty("MAIN_PROFILE_CAPITAL_PERCENT", 60) / 100.0;
    }

    public List<String> getMainBullishSymbols() {
        String symbols = getProperty("MAIN_BULLISH_SYMBOLS", "SPY,QQQ,IWM,DIA,XLF,XLE,XLK,XLV,XLI,XLP");
        return java.util.Arrays.asList(symbols.split(","));
    }

    public List<String> getMainBearishSymbols() {
        String symbols = getProperty("MAIN_BEARISH_SYMBOLS", "SH,PSQ,RWM,DOG,SEF,ERY,REK,RXD,SIJ,SZK");
        return java.util.Arrays.asList(symbols.split(","));
    }

    public double getMainTakeProfitPercent() {
        return getDoubleProperty("MAIN_TAKE_PROFIT_PERCENT", 0.001); // 🚨 PANIC SELL: 0.001%
    }

    public double getMainStopLossPercent() {
        return getDoubleProperty("MAIN_STOP_LOSS_PERCENT", 0.001);   // 🚨 PANIC SELL: 0.001%
    }

    public double getMainTrailingStopPercent() {
        return getDoubleProperty("MAIN_TRAILING_STOP_PERCENT", 1.5);
    }

    // Experimental Profile Settings
    public double getExperimentalProfileCapitalPercent() {
        // FIX: Convert integer percentage to decimal (40 → 0.40)
        return getIntProperty("EXPERIMENTAL_PROFILE_CAPITAL_PERCENT", 40) / 100.0;
    }

    // ==================== Phase 1: Advanced Trading Strategies ====================
    
    /**
     * Minimum hold time in hours to prevent PDT violations.
     * Default: 16 hours (overnight hold)
     */
    public int getMinHoldTimeHours() {
        return getIntProperty("MIN_HOLD_TIME_HOURS", 16);
    }
    
    /**
     * Whether automated rebalancing is enabled.
     * Default: true
     */
    public boolean isAutoRebalancingEnabled() {
        return getBooleanProperty("ENABLE_AUTO_REBALANCING", true);
    }
    
    /**
     * Day of week for rebalancing (MONDAY, TUESDAY, etc.)
     * Default: MONDAY
     */
    public String getRebalancingDay() {
        return properties.getProperty("REBALANCING_DAY", "MONDAY");
    }
    
    /**
     * Hour of day for rebalancing (0-23)
     * Default: 4 (4 AM)
     */
    public int getRebalancingHour() {
        return getIntProperty("REBALANCING_HOUR", 4);
    }

    public List<String> getExperimentalBullishSymbols() {
        String symbols = getProperty("EXPERIMENTAL_BULLISH_SYMBOLS", "SPY,QQQ,IWM,DIA,XLF,XLE,XLK,XLV,GLD,SLV,TLT,XLU");
        return java.util.Arrays.asList(symbols.split(","));
    }

    public List<String> getExperimentalBearishSymbols() {
        String symbols = getProperty("EXPERIMENTAL_BEARISH_SYMBOLS", "SH,PSQ,RWM,DOG,SEF,ERY,REK,RXD,GLL,ZSL,TBT,SDP");
        return java.util.Arrays.asList(symbols.split(","));
    }

    public double getExperimentalTakeProfitPercent() {
        return getDoubleProperty("EXPERIMENTAL_TAKE_PROFIT_PERCENT", 2.0);
    }

    public double getExperimentalStopLossPercent() {
        return getDoubleProperty("EXPERIMENTAL_STOP_LOSS_PERCENT", 1.0);
    }

    public double getExperimentalTrailingStopPercent() {
        return getDoubleProperty("EXPERIMENTAL_TRAILING_STOP_PERCENT", 0.5);
    }

    private double getDoubleProperty(String key, double defaultValue) {
        String value = getProperty(key);
        if (value == null || value.isEmpty()) {
            return defaultValue;
        }
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            logger.warn("Invalid double value for {}: {}", key, value);
            return defaultValue;
        }
    }
    
    // Rate Limiting Settings
    public long getApiRequestDelayMs() {
        return Long.parseLong(getProperty("API_REQUEST_DELAY_MS", "300"));
    }
    
    public boolean isAdaptiveRateLimitEnabled() {
        return Boolean.parseBoolean(getProperty("ENABLE_ADAPTIVE_RATE_CONTROL", "true"));
    }
    
    public int getSymbolBatchSize() {
        return Integer.parseInt(getProperty("SYMBOL_BATCH_SIZE", "6"));
    }
    
    // ==================== FinGPT AI Integration ====================
    
    /**
     * Check if FinGPT AI integration is enabled.
     * Default: false (must be explicitly enabled)
     */
    public boolean isFinGPTEnabled() {
        return getBooleanProperty("FINGPT_ENABLED", false);
    }
    
    /**
     * Get HuggingFace API token for FinGPT models.
     */
    public String getHuggingFaceApiToken() {
        return getProperty("HUGGINGFACE_API_TOKEN");
    }
    
    /**
     * Get FinGPT sentiment model name.
     * Default: FinGPT/fingpt-sentiment_llama2-13b_lora
     */
    public String getFinGPTSentimentModel() {
        return getProperty("FINGPT_SENTIMENT_MODEL", "FinGPT/fingpt-sentiment_llama2-13b_lora");
    }
    
    /**
     * Get minimum confidence score for FinGPT sentiment to be considered valid.
     * Default: 0.3 (30% confidence)
     */
    public double getFinGPTMinConfidence() {
        return getDoubleProperty("FINGPT_MIN_CONFIDENCE", 0.3);
    }
    
    /**
     * Get FinGPT cache TTL in minutes.
     * Default: 15 minutes
     */
    public int getFinGPTCacheTTL() {
        return getIntProperty("FINGPT_CACHE_TTL_MINUTES", 15);
    }
    
    /**
     * Check if sentiment-based position scaling is enabled.
     * Default: true (when FinGPT is enabled)
     */
    public boolean isSentimentPositionScalingEnabled() {
        return getBooleanProperty("SENTIMENT_POSITION_SCALING", true);
    }
    
    /**
     * Get minimum position size multiplier based on sentiment.
     * Default: 0.5 (50% of base position for low confidence)
     */
    public double getSentimentMinMultiplier() {
        return getDoubleProperty("SENTIMENT_MIN_MULTIPLIER", 0.5);
    }
    
    /**
     * Get maximum position size multiplier based on sentiment.
     * Default: 1.5 (150% of base position for high confidence)
     */
    public double getSentimentMaxMultiplier() {
        return getDoubleProperty("SENTIMENT_MAX_MULTIPLIER", 1.5);
    }
    
    // ==================== Alpha Vantage Sentiment API ====================
    
    /**
     * Check if Alpha Vantage sentiment API is enabled.
     * Default: false (must be explicitly enabled)
     */
    public boolean isAlphaVantageEnabled() {
        return getBooleanProperty("ALPHA_VANTAGE_ENABLED", false);
    }
    
    /**
     * Get Alpha Vantage API key.
     */
    public String getAlphaVantageApiKey() {
        return getProperty("ALPHA_VANTAGE_API_KEY");
    }
    
    /**
     * Get number of news articles to fetch per symbol.
     * Default: 10
     */
    public int getAlphaVantageNewsLimit() {
        return getIntProperty("ALPHA_VANTAGE_NEWS_LIMIT", 10);
    }
    
    /**
     * Get Alpha Vantage cache TTL in minutes.
     * Default: 15 minutes
     */
    public int getAlphaVantageCacheTTL() {
        return getIntProperty("ALPHA_VANTAGE_CACHE_TTL_MINUTES", 15);
    }
    
    /**
     * Get minimum relevance score for news articles.
     * Articles below this threshold are filtered out.
     * Default: 0.3 (30% relevance)
     */
    public double getAlphaVantageMinRelevance() {
        return getDoubleProperty("ALPHA_VANTAGE_MIN_RELEVANCE", 0.3);
    }
    
    // ==================== Advanced Risk Management ====================
    
    /**
     * Check if max loss exit is enabled.
     * Default: true
     */
    public boolean isMaxLossExitEnabled() {
        return getBooleanProperty("MAX_LOSS_EXIT_ENABLED", true);
    }
    
    /**
     * Get maximum loss percent before auto-exit.
     * Default: 10.0 (10%)
     */
    public double getMaxLossPercent() {
        return getDoubleProperty("MAX_LOSS_PERCENT", 10.0);
    }
    
    /**
     * Get maximum hold time in hours before auto-exit.
     * Default: 48 hours (2 days)
     */
    public int getMaxHoldTimeHours() {
        return getIntProperty("MAX_HOLD_TIME_HOURS", 48);
    }
    
    /**
     * Check if portfolio stop loss is enabled.
     * Default: true
     */
    public boolean isPortfolioStopLossEnabled() {
        return getBooleanProperty("PORTFOLIO_STOP_LOSS_ENABLED", true);
    }
    
    /**
     * Get portfolio-level stop loss percent.
     * Default: 5.0 (5%)
     */
    public double getPortfolioStopLossPercent() {
        return getDoubleProperty("PORTFOLIO_STOP_LOSS_PERCENT", 5.0);
    }
    
    // ==================== Advanced Regime Detection ====================
    
    /**
     * Check if advanced regime detection is enabled.
     * Default: true
     */
    public boolean isRegimeDetectionEnabled() {
        return getBooleanProperty("REGIME_DETECTION_ENABLED", true);
    }
    
    /**
     * Get short-term moving average period for trend analysis.
     * Default: 50 days
     */
    public int getRegimeMAShort() {
        return getIntProperty("REGIME_MA_SHORT", 50);
    }
    
    /**
     * Get long-term moving average period for trend analysis.
     * Default: 200 days
     */
    public int getRegimeMALong() {
        return getIntProperty("REGIME_MA_LONG", 200);
    }
    
    /**
     * Get volume analysis period.
     * Default: 20 days
     */
    public int getRegimeVolumePeriod() {
        return getIntProperty("REGIME_VOLUME_PERIOD", 20);
    }
    
    /**
     * Get breadth threshold for regime classification.
     * Default: 0.6 (60%)
     */
    public double getRegimeBreadthThreshold() {
        return getDoubleProperty("REGIME_BREADTH_THRESHOLD", 0.6);
    }
    
    /**
     * Get regime update interval in minutes (for caching).
     * Default: 15 minutes
     */
    public int getRegimeUpdateIntervalMinutes() {
        return getIntProperty("REGIME_UPDATE_INTERVAL_MINUTES", 15);
    }
    
    // ==================== Multi-Timeframe Analysis ====================
    
    /**
     * Check if multi-timeframe analysis is enabled.
     * Default: true
     */
    public boolean isMultiTimeframeEnabled() {
        return getBooleanProperty("MULTI_TIMEFRAME_ENABLED", true);
    }
    
    /**
     * Get list of timeframes to analyze.
     * Default: 15M,1H,1D
     */
    public List<String> getMultiTimeframeTimeframes() {
        String timeframes = getProperty("MULTI_TIMEFRAME_TIMEFRAMES", "15M,1H,1D");
        return Arrays.asList(timeframes.split(","));
    }
    
    /**
     * Check if timeframe alignment is required before trading.
     * Default: true
     */
    public boolean isMultiTimeframeRequireAlignment() {
        return getBooleanProperty("MULTI_TIMEFRAME_REQUIRE_ALIGNMENT", true);
    }
    
    /**
     * Get minimum number of timeframes that must align.
     * Default: 2
     */
    public int getMultiTimeframeMinAligned() {
        return getIntProperty("MULTI_TIMEFRAME_MIN_ALIGNED", 2);
    }
    
    /**
     * Get primary timeframe for regime detection.
     * Default: 1D (daily)
     */
    public String getMultiTimeframePrimary() {
        return getProperty("MULTI_TIMEFRAME_PRIMARY", "1D");
    }
    
    /**
     * Get entry timeframe for precise timing.
     * Default: 15M (15-minute)
     */
    public String getMultiTimeframeEntryTimeframe() {
        return getProperty("MULTI_TIMEFRAME_ENTRY", "15M");
    }
    
    // ==================== Advanced Position Sizing ====================
    
    /**
     * Get position sizing method.
     * Options: KELLY, VOLATILITY, FIXED
     * Default: KELLY
     */
    public String getPositionSizingMethod() {
        return getProperty("POSITION_SIZING_METHOD", "KELLY");
    }
    
    /**
     * Get Kelly Criterion fraction (safety factor).
     * Default: 0.25 (25% of full Kelly)
     */
    public double getPositionSizingKellyFraction() {
        return getDoubleProperty("POSITION_SIZING_KELLY_FRACTION", 0.25);
    }
    
    /**
     * Get Kelly Criterion risk/reward ratio.
     * Default: 2.0 (2:1 reward:risk)
     */
    public double getPositionSizingKellyRiskReward() {
        return getDoubleProperty("POSITION_SIZING_KELLY_RISK_REWARD", 2.0);
    }
    
    /**
     * Get default win rate for new symbols.
     * Default: 0.55 (55%)
     */
    public double getPositionSizingDefaultWinRate() {
        return getDoubleProperty("POSITION_SIZING_DEFAULT_WIN_RATE", 0.50);
    }
    
    /**
     * Get volatility-based risk percent.
     * Default: 0.02 (2% of equity)
     */
    public double getPositionSizingVolatilityRiskPercent() {
        return getDoubleProperty("POSITION_SIZING_VOLATILITY_RISK_PERCENT", 0.02);
    }
    
    /**
     * Check if volatility adjustment is enabled.
     * Default: true
     */
    public boolean isPositionSizingVolatilityAdjustEnabled() {
        return getBooleanProperty("POSITION_SIZING_VOLATILITY_ADJUST_ENABLED", true);
    }
    
    /**
     * Get volatility multiplier for size adjustment.
     * Default: 20.0
     */
    public double getPositionSizingVolatilityMultiplier() {
        return getDoubleProperty("POSITION_SIZING_VOLATILITY_MULTIPLIER", 20.0);
    }
    
    /**
     * Get fixed percentage for position sizing.
     * Default: 0.10 (10% of equity)
     */
    public double getPositionSizingFixedPercent() {
        return getDoubleProperty("POSITION_SIZING_FIXED_PERCENT", 0.10);
    }
    
    /**
     * Get maximum position size as percent of equity.
     * Default: 0.20 (20% of equity)
     */
    public double getPositionSizingMaxPercent() {
        return getDoubleProperty("POSITION_SIZING_MAX_PERCENT", 0.20);
    }
    
    /**
     * Get minimum position size as percent of equity.
     * Default: 0.02 (2% of equity)
     */
    public double getPositionSizingMinPercent() {
        return getDoubleProperty("POSITION_SIZING_MIN_PERCENT", 0.02);
    }
    
    /**
     * Check if correlation-based position limits are enabled.
     * Default: true
     */
    public boolean isPositionSizingCorrelationLimitEnabled() {
        return getBooleanProperty("POSITION_SIZING_CORRELATION_LIMIT_ENABLED", true);
    }
    
    /**
     * Get maximum number of correlated positions allowed.
     * Default: 5
     */
    public int getPositionSizingMaxCorrelatedPositions() {
        return getIntProperty("POSITION_SIZING_MAX_CORRELATED_POSITIONS", 5);
    }
    
    // ==================== EOD Exit Configuration ====================
    
    public boolean isEodExitEnabled() {
        return getBooleanProperty("EOD_EXIT_ENABLED", false);
    }
    
    public String getEodExitTime() {
        return getProperty("EOD_EXIT_TIME", "15:30");
    }
    
    // ==================== Time-Based Profit Taking ====================
    
    public boolean isTimeBasedProfitEnabled() {
        return getBooleanProperty("TIME_BASED_PROFIT_ENABLED", false);
    }
    
    public double getTimeHeld1HourProfit() {
        return getDoubleProperty("TIME_HELD_1_HOUR_PROFIT", 0.5);
    }
    
    public int getTimeHeld1HourExit() {
        return getIntProperty("TIME_HELD_1_HOUR_EXIT", 25);
    }
    
    public double getTimeHeld2HourProfit() {
        return getDoubleProperty("TIME_HELD_2_HOUR_PROFIT", 0.75);
    }
    
    public int getTimeHeld2HourExit() {
        return getIntProperty("TIME_HELD_2_HOUR_EXIT", 25);
    }
    
    public double getTimeHeld4HourProfit() {
        return getDoubleProperty("TIME_HELD_4_HOUR_PROFIT", 1.0);
    }
    
    public int getTimeHeld4HourExit() {
        return getIntProperty("TIME_HELD_4_HOUR_EXIT", 50);
    }
    
    // ==================== Volatility-Adjusted Targets ====================
    
    public boolean isVolatilityAdjustedTargets() {
        return getBooleanProperty("VOLATILITY_ADJUSTED_TARGETS", false);
    }
    
    public double getLowVixTarget() {
        return getDoubleProperty("LOW_VIX_TARGET", 0.5);
    }
    
    public double getNormalVixTarget() {
        return getDoubleProperty("NORMAL_VIX_TARGET", 1.0);
    }
    
    public double getHighVixTarget() {
        return getDoubleProperty("HIGH_VIX_TARGET", 1.5);
    }
    
    public int getVixLowThreshold() {
        return getIntProperty("VIX_LOW_THRESHOLD", 15);
    }
    
    public int getVixHighThreshold() {
        return getIntProperty("VIX_HIGH_THRESHOLD", 20);
    }
    
    // ==================== Win Rate Adaptive Sizing ====================
    
    public boolean isAdaptiveSizingEnabled() {
        return getBooleanProperty("ADAPTIVE_SIZING_ENABLED", true);
    }
    
    public double getHighWinRateMultiplier() {
        return getDoubleProperty("HIGH_WIN_RATE_MULTIPLIER", 1.2);
    }
    
    public double getLowWinRateMultiplier() {
        return getDoubleProperty("LOW_WIN_RATE_MULTIPLIER", 0.7);
    }
    
    public int getHighWinRateThreshold() {
        return getIntProperty("HIGH_WIN_RATE_THRESHOLD", 70);
    }
    
    public int getLowWinRateThreshold() {
        return getIntProperty("LOW_WIN_RATE_THRESHOLD", 50);
    }
    
    // ==================== Correlation Limits ====================
    
    public boolean isCorrelationLimitsEnabled() {
        return getBooleanProperty("CORRELATION_LIMITS_ENABLED", false);
    }
    
    public int getMaxCorrelatedPositions() {
        return getIntProperty("MAX_CORRELATED_POSITIONS", 2);
    }
    
    public double getCorrelationThreshold() {
        return getDoubleProperty("CORRELATION_THRESHOLD", 0.7);
    }
    
    // ==================== Profit Target Ladder ====================
    
    public boolean isProfitLadderEnabled() {
        return getBooleanProperty("PROFIT_LADDER_ENABLED", false);
    }
    
    public double getLadderLevel1Percent() {
        return getDoubleProperty("LADDER_LEVEL_1_PERCENT", 0.5);
    }
    
    public int getLadderLevel1Exit() {
        return getIntProperty("LADDER_LEVEL_1_EXIT", 20);
    }
    
    public double getLadderLevel2Percent() {
        return getDoubleProperty("LADDER_LEVEL_2_PERCENT", 0.75);
    }
    
    public int getLadderLevel2Exit() {
        return getIntProperty("LADDER_LEVEL_2_EXIT", 30);
    }
    
    public double getLadderLevel3Percent() {
        return getDoubleProperty("LADDER_LEVEL_3_PERCENT", 1.0);
    }
    
    public int getLadderLevel3Exit() {
        return getIntProperty("LADDER_LEVEL_3_EXIT", 30);
    }
    
    public double getLadderLevel4Percent() {
        return getDoubleProperty("LADDER_LEVEL_4_PERCENT", 1.5);
    }
    
    public int getLadderLevel4Exit() {
        return getIntProperty("LADDER_LEVEL_4_EXIT", 20);
    }
    
    // ==================== Aggressive Profit Protection ====================
    
    public boolean isAggressiveProfitLock() {
        return getBooleanProperty("AGGRESSIVE_PROFIT_LOCK", false);
    }
    
    public boolean isLockProfitAt03Percent() {
        return getBooleanProperty("LOCK_PROFIT_AT_0_3_PERCENT", false);
    }
    
    public boolean isNeverGoNegativeAfterPositive() {
        return getBooleanProperty("NEVER_GO_NEGATIVE_AFTER_POSITIVE", false);
    }
    
    // ==================== Breakeven Stops ====================
    
    public boolean isBreakevenStopEnabled() {
        return getBooleanProperty("BREAKEVEN_STOP_ENABLED", false);
    }
    
    public double getBreakevenTriggerPercent() {
        return getDoubleProperty("BREAKEVEN_TRIGGER_PERCENT", 0.5);
    }
    
    // ==================== Smart Entry Timing ====================
    
    public boolean isAvoidFirst15Minutes() {
        return getBooleanProperty("AVOID_FIRST_15_MINUTES", false);
    }
    
    public boolean isAvoidLast30Minutes() {
        return getBooleanProperty("AVOID_LAST_30_MINUTES", false);
    }
    
    public String getBestEntryWindowStart() {
        return getProperty("BEST_ENTRY_WINDOW_START", "10:00");
    }
    
    public String getBestEntryWindowEnd() {
        return getProperty("BEST_ENTRY_WINDOW_END", "15:00");
    }
    
    // ==================== Position Concentration Limits ====================
    
    public int getMaxCapitalPerSymbol() {
        return getIntProperty("MAX_CAPITAL_PER_SYMBOL", 25);
    }
    
    public int getMaxCapitalPerSector() {
        return getIntProperty("MAX_CAPITAL_PER_SECTOR", 40);
    }
    
    // ==================== Daily Profit Target ====================
    
    public boolean isDailyProfitTargetEnabled() {
        return getBooleanProperty("DAILY_PROFIT_TARGET_ENABLED", false);
    }
    
    public double getDailyProfitTarget() {
        return getDoubleProperty("DAILY_PROFIT_TARGET", 15.0);
    }
    
    public boolean isStopTradingWhenTargetHit() {
        return getBooleanProperty("STOP_TRADING_WHEN_TARGET_HIT", false);
    }
    
    public boolean isReduceRiskAfterTarget() {
        return getBooleanProperty("REDUCE_RISK_AFTER_TARGET", true);
    }
    
    // ==================== Dynamic Stops ====================
    
    public boolean isDynamicStopsEnabled() {
        return getBooleanProperty("DYNAMIC_STOPS_ENABLED", false);
    }
    
    public double getWeakMarketStopMultiplier() {
        return getDoubleProperty("WEAK_MARKET_STOP_MULTIPLIER", 0.7);
    }
    
    // ==================== Min Position Size ====================
    
    public double getMinPositionSizeDollars() {
        return getDoubleProperty("MIN_POSITION_SIZE_DOLLARS", 100.0);
    }
    
    public int getMaxPositionsAtOnce() {
        return getIntProperty("MAX_POSITIONS_AT_ONCE", 3);
    }
    
    
    public int getMaxTradesPerDay() {
        return getIntProperty("MAX_TRADES_PER_DAY", 8);
    }
    
    // ==================== PHASE 2: ADVANCED PROFIT FEATURES ====================
    
    // Feature 16: Smart Capital Reserve
    public double getSmartCapitalReservePercent() {
        return getDoubleProperty("SMART_CAPITAL_RESERVE_PERCENT", 0.25);
    }
    
    // Feature 17: PDT-Aware Profit Taking
    public boolean isPDTAwareProfitTaking() {
        return getBooleanProperty("PDT_AWARE_PROFIT_TAKING", true);
    }
    
    public double getPDTPartialExitPercent() {
        return getDoubleProperty("PDT_PARTIAL_EXIT_PERCENT", 0.50);
    }
    
    // Feature 18: Micro-Position Scaling
    public boolean isMicroPositionScalingEnabled() {
        return getBooleanProperty("MICRO_POSITION_SCALING", true);
    }
    
    public double getMicroScalingInitialPercent() {
        return getDoubleProperty("MICRO_SCALING_INITIAL_PERCENT", 0.50);
    }
    
    public double getMicroScalingLevel1Profit() {
        return getDoubleProperty("MICRO_SCALING_LEVEL1_PROFIT", 0.5);
    }
    
    public double getMicroScalingLevel2Profit() {
        return getDoubleProperty("MICRO_SCALING_LEVEL2_PROFIT", 1.0);
    }
    
    // Feature 19: Opportunity Tracking
    public boolean isTrackMissedOpportunities() {
        return getBooleanProperty("TRACK_MISSED_OPPORTUNITIES", true);
    }
    
    // Feature 20: Dynamic Stop Tightening
    public boolean isDynamicStopTighteningEnabled() {
        return getBooleanProperty("DYNAMIC_STOP_TIGHTENING", true);
    }
    
    public double getStopTightenLevel1Profit() {
        return getDoubleProperty("STOP_TIGHTEN_LEVEL1_PROFIT", 0.5);
    }
    
    public double getStopTightenLevel2Profit() {
        return getDoubleProperty("STOP_TIGHTEN_LEVEL2_PROFIT", 1.0);
    }
    
    public double getStopTightenLevel3Profit() {
        return getDoubleProperty("STOP_TIGHTEN_LEVEL3_PROFIT", 2.0);
    }
    
    // Feature 21: Profit Velocity Exit
    public boolean isProfitVelocityExitEnabled() {
        return getBooleanProperty("PROFIT_VELOCITY_EXIT", true);
    }
    
    public double getVelocityDropThreshold() {
        return getDoubleProperty("VELOCITY_DROP_THRESHOLD", 0.50);
    }
    
    public double getMinProfitForVelocityExit() {
        return getDoubleProperty("MIN_PROFIT_FOR_VELOCITY_EXIT", 0.3);
    }
    
    // Feature 22: Correlation-Based Exits
    public boolean isCorrelationExitEnabled() {
        return getBooleanProperty("CORRELATION_EXIT_ENABLED", true);
    }
    
    public double getCorrelationExitThreshold() {
        return getDoubleProperty("CORRELATION_EXIT_THRESHOLD", 0.7);
    }
    
    // Feature 23: EOD Profit Lock
    public boolean isEODProfitLockEnabled() {
        return getBooleanProperty("EOD_PROFIT_LOCK", true);
    }
    
    public String getEODProfitLockTime() {
        return getProperty("EOD_PROFIT_LOCK_TIME", "15:30");
    }
    
    public int getEODProfitLockMinHoldHours() {
        return getIntProperty("EOD_PROFIT_LOCK_MIN_HOLD_HOURS", 4);
    }
    
    // Feature 24: Adaptive Position Limits
    public boolean isAdaptivePositionLimitsEnabled() {
        return getBooleanProperty("ADAPTIVE_POSITION_LIMITS", true);
    }
    
    public int getAdaptivePositionLimit(double winRate) {
        if (!isAdaptivePositionLimitsEnabled()) {
            return getMaxPositionsAtOnce();
        }
        
        if (winRate >= 0.70) return 5;
        if (winRate >= 0.60) return 4;
        return 3;
    }
    
    // Feature 25: Quick Profit Scalping
    public boolean isQuickProfitScalpingEnabled() {
        return getBooleanProperty("QUICK_PROFIT_SCALPING", true);
    }
    
    public double getQuickScalp15MinProfit() {
        return getDoubleProperty("QUICK_SCALP_15MIN_PROFIT", 0.3);
    }
    
    public double getQuickScalp30MinProfit() {
        return getDoubleProperty("QUICK_SCALP_30MIN_PROFIT", 0.5);
    }
    
    public double getQuickScalpExitPercent() {
        return getDoubleProperty("QUICK_SCALP_EXIT_PERCENT", 0.50);
    }
    
    // ==================== PHASE 3: ADVANCED FEATURES ====================
    
    // Feature 26: ML-Based Entry Scoring
    public boolean isMLEntryScoringEnabled() {
        return getBooleanProperty("ML_ENTRY_SCORING_ENABLED", false);
    }
    
    public double getMLMinScore() {
        return getDoubleProperty("ML_MIN_SCORE", 45.0);
    }
    
    public double getMLScoreTechnicalWeight() {
        return getDoubleProperty("ML_SCORE_TECHNICAL_WEIGHT", 0.3);
    }
    
    public double getMLScoreSentimentWeight() {
        return getDoubleProperty("ML_SCORE_SENTIMENT_WEIGHT", 0.3);
    }
    
    public double getMLScoreVolumeWeight() {
        return getDoubleProperty("ML_SCORE_VOLUME_WEIGHT", 0.2);
    }
    
    public double getMLScoreMomentumWeight() {
        return getDoubleProperty("ML_SCORE_MOMENTUM_WEIGHT", 0.2);
    }
    
    // Feature 27: Multi-Timeframe Confirmation
    public boolean isMultiTimeframeConfirmation() {
        return getBooleanProperty("MULTI_TIMEFRAME_CONFIRMATION", true);
    }
    
    public int getMTFRequireAgreement() {
        return getIntProperty("MTF_REQUIRE_AGREEMENT", 2);
    }
    
    // Feature 28: Volume Profile
    public boolean isVolumeProfileEnabled() {
        return getBooleanProperty("VOLUME_PROFILE_ENABLED", true);
    }
    
    public double getVolumeNodeThreshold() {
        return getDoubleProperty("VOLUME_NODE_THRESHOLD", 0.005);
    }
    
    // Feature 29: Market Breadth Filter
    public boolean isMarketBreadthFilter() {
        return getBooleanProperty("MARKET_BREADTH_FILTER", true);
    }
    
    public double getMinBreadthRatio() {
        return getDoubleProperty("MIN_BREADTH_RATIO", 0.40);
    }
    
    // Feature 30: Adaptive Position Sizing (new methods for Phase 3)
    public double getAdaptiveSizeMin() {
        return getDoubleProperty("ADAPTIVE_SIZE_MIN", 2.0);
    }
    
    public double getAdaptiveSizeBase() {
        return getDoubleProperty("ADAPTIVE_SIZE_BASE", 4.0);
    }
    
    public double getAdaptiveSizeMax() {
        return getDoubleProperty("ADAPTIVE_SIZE_MAX", 8.0);
    }
    
    public double getAdaptiveSizeHighConfidence() {
        return getDoubleProperty("ADAPTIVE_SIZE_HIGH_CONFIDENCE", 85.0);
    }
    
    public double getAdaptiveSizeVixThreshold() {
        return getDoubleProperty("ADAPTIVE_SIZE_VIX_THRESHOLD", 25.0);
    }
    
    // Feature 31: Correlation-Based Sizing (new method for Phase 3)
    public boolean isCorrelationSizingEnabled() {
        return getBooleanProperty("CORRELATION_SIZING_ENABLED", true);
    }
    
    public double getCorrelationSizeReduction() {
        return getDoubleProperty("CORRELATION_SIZE_REDUCTION", 0.5);
    }
    
    // Feature 32: Auto Rebalancing
    public boolean isAutoRebalanceEnabled() {
        return getBooleanProperty("AUTO_REBALANCE_ENABLED", true);
    }
    
    public double getRebalanceThreshold() {
        return getDoubleProperty("REBALANCE_THRESHOLD", 40.0);
    }
    
    public double getRebalanceTrimPercent() {
        return getDoubleProperty("REBALANCE_TRIM_PERCENT", 50.0);
    }
    
    public int getRebalanceCheckIntervalHours() {
        return getIntProperty("REBALANCE_CHECK_INTERVAL_HOURS", 1);
    }
    
    // Feature 33: Trailing Profit Targets
    public boolean isTrailingTargetsEnabled() {
        return getBooleanProperty("TRAILING_TARGETS_ENABLED", true);
    }
    
    public double getTrailLevel1Profit() {
        return getDoubleProperty("TRAIL_LEVEL_1_PROFIT", 1.0);
    }
    
    public double getTrailLevel1Lock() {
        return getDoubleProperty("TRAIL_LEVEL_1_LOCK", 50.0);
    }
    
    public double getTrailLevel1Trail() {
        return getDoubleProperty("TRAIL_LEVEL_1_TRAIL", 0.5);
    }
    
    public double getTrailLevel2Profit() {
        return getDoubleProperty("TRAIL_LEVEL_2_PROFIT", 2.0);
    }
    
    public double getTrailLevel2Lock() {
        return getDoubleProperty("TRAIL_LEVEL_2_LOCK", 75.0);
    }
    
    public double getTrailLevel2Trail() {
        return getDoubleProperty("TRAIL_LEVEL_2_TRAIL", 0.3);
    }
    
    // Feature 34: Time-Decay Exits
    public boolean isTimeDecayExits() {
        return getBooleanProperty("TIME_DECAY_EXITS", true);
    }
    
    public int getFlatPositionHours() {
        return getIntProperty("FLAT_POSITION_HOURS", 2);
    }
    
    public double getFlatPositionThreshold() {
        return getDoubleProperty("FLAT_POSITION_THRESHOLD", 0.2);
    }
    
    // Feature 35: Momentum Acceleration Exits
    public boolean isMomentumAccelerationExits() {
        return getBooleanProperty("MOMENTUM_ACCELERATION_EXITS", true);
    }
    
    public double getAccelerationThreshold() {
        return getDoubleProperty("ACCELERATION_THRESHOLD", 1.0);
    }
    
    public double getAccelerationExitPercent() {
        return getDoubleProperty("ACCELERATION_EXIT_PERCENT", 75.0);
    }
    
    // Feature 36: Stock Lending Tracking
    public boolean isStockLendingTracking() {
        return getBooleanProperty("STOCK_LENDING_TRACKING", true);
    }
    
    public int getLendingReportIntervalHours() {
        return getIntProperty("LENDING_REPORT_INTERVAL_HOURS", 24);
    }
    
    // Options Trading Features
    public boolean isOptionsEnabled() {
        return isLongStraddleEnabled() || isIronButterflyEnabled() || isCalendarSpreadEnabled();
    }
    
    public boolean isLongStraddleEnabled() {
        return getBooleanProperty("LONG_STRADDLE_ENABLED", false);
    }
    
    public double getStraddleMinVix() {
        return getDoubleProperty("STRADDLE_MIN_VIX", 25.0);
    }
    
    public double getStraddleMaxCostPercent() {
        return getDoubleProperty("STRADDLE_MAX_COST_PERCENT", 5.0);
    }
    
    public boolean isStraddleEarningsOnly() {
        return getBooleanProperty("STRADDLE_EARNINGS_ONLY", true);
    }
    
    public boolean isIronButterflyEnabled() {
        return getBooleanProperty("IRON_BUTTERFLY_ENABLED", false);
    }
    
    public double getButterflyMaxVix() {
        return getDoubleProperty("BUTTERFLY_MAX_VIX", 20.0);
    }
    
    public double getButterflyWingWidth() {
        return getDoubleProperty("BUTTERFLY_WING_WIDTH", 5.0);
    }
    
    public boolean isCalendarSpreadEnabled() {
        return getBooleanProperty("CALENDAR_SPREAD_ENABLED", false);
    }
    
    public int getCalendarNearDays() {
        return getIntProperty("CALENDAR_NEAR_DAYS", 7);
    }
    
    public int getCalendarFarDays() {
        return getIntProperty("CALENDAR_FAR_DAYS", 14);
    }
    
    // Options Risk Management
    public boolean isOptionsRiskManagement() {
        return getBooleanProperty("OPTIONS_RISK_MANAGEMENT", true);
    }
    
    public double getOptionsMaxLossPercent() {
        return getDoubleProperty("OPTIONS_MAX_LOSS_PERCENT", 50.0);
    }
    
    public int getOptionsExitDaysBeforeExp() {
        return getIntProperty("OPTIONS_EXIT_DAYS_BEFORE_EXP", 1);
    }
    
    public double getOptionsMaxAllocation() {
        return getDoubleProperty("OPTIONS_MAX_ALLOCATION", 10.0);
    }
    
    public double getOptionsMaxPerPosition() {
        return getDoubleProperty("OPTIONS_MAX_PER_POSITION", 5.0);
    }
    
    // Feature 41: Position Health Scoring
    public boolean isPositionHealthScoring() {
        return getBooleanProperty("POSITION_HEALTH_SCORING", true);
    }
    
    // Feature 42: Smart Order Routing
    public boolean isSmartOrderRouting() {
        return getBooleanProperty("SMART_ORDER_ROUTING", true);
    }
    
    public double getWideSpreadThreshold() {
        return getDoubleProperty("WIDE_SPREAD_THRESHOLD", 0.005);
    }
}
