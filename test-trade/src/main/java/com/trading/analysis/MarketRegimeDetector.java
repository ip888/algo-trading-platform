package com.trading.analysis;

import com.trading.api.AlpacaClient;
import com.trading.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Advanced market regime detection using multiple factors:
 * - Trend analysis (50/200 MA crossover)
 * - Volume analysis
 * - Breadth indicators
 * - Sector rotation
 * 
 * Replaces simple VIX threshold with comprehensive regime classification.
 */
public class MarketRegimeDetector {
    private static final Logger logger = LoggerFactory.getLogger(MarketRegimeDetector.class);
    
    private final AlpacaClient client;
    private final Config config;
    private final MarketAnalyzer marketAnalyzer;
    
    // Cache for regime detection (expensive operation)
    private MarketRegimeAnalysis cachedRegime;
    private Instant lastUpdate;
    private final Duration cacheExpiry;
    
    public MarketRegimeDetector(AlpacaClient client, Config config, MarketAnalyzer marketAnalyzer) {
        this.client = client;
        this.config = config;
        this.marketAnalyzer = marketAnalyzer;
        this.cacheExpiry = Duration.ofMinutes(config.getRegimeUpdateIntervalMinutes());
        
        logger.info("MarketRegimeDetector initialized with {}min cache", 
            config.getRegimeUpdateIntervalMinutes());
    }
    
    /**
     * Market regime classifications
     */
    public enum MarketRegime {
        STRONG_BULL,      // Uptrend + high volume + breadth
        WEAK_BULL,        // Uptrend + low volume/breadth
        STRONG_BEAR,      // Downtrend + high volume + breadth
        WEAK_BEAR,        // Downtrend + low volume/breadth
        RANGE_BOUND,      // Sideways + low volatility
        HIGH_VOLATILITY   // Choppy + high VIX
    }
    
    /**
     * Complete regime analysis with confidence
     */
    public record MarketRegimeAnalysis(
        MarketRegime regime,
        double confidence,
        TrendAnalysis trend,
        VolumeAnalysis volume,
        BreadthAnalysis breadth,
        double vix,
        Instant timestamp
    ) {
        public String getSummary() {
            return String.format("%s (%.0f%% confidence) - Trend: %s, Volume: %s, Breadth: %.0f%%",
                regime, confidence * 100, trend.direction, volume.trend, breadth.strength * 100);
        }
    }
    
    /**
     * Trend analysis using moving averages
     */
    public record TrendAnalysis(
        TrendDirection direction,
        double strength,
        double ma50,
        double ma200,
        double currentPrice,
        boolean goldenCross,
        boolean deathCross
    ) {}
    
    public enum TrendDirection {
        STRONG_UP, WEAK_UP, NEUTRAL, WEAK_DOWN, STRONG_DOWN
    }
    
    /**
     * Volume analysis
     */
    public record VolumeAnalysis(
        VolumeTrend trend,
        double currentVolume,
        double avgVolume,
        double volumeRatio
    ) {}
    
    public enum VolumeTrend {
        INCREASING, STABLE, DECREASING
    }
    
    /**
     * Breadth analysis (market participation)
     */
    public record BreadthAnalysis(
        double strength,  // 0.0 to 1.0
        int advancingStocks,
        int decliningStocks,
        double advanceDeclineRatio
    ) {}
    
    /**
     * Get current market regime (cached)
     */
    public MarketRegimeAnalysis getCurrentRegime() {
        if (cachedRegime != null && lastUpdate != null) {
            Duration age = Duration.between(lastUpdate, Instant.now());
            if (age.compareTo(cacheExpiry) < 0) {
                logger.debug("Using cached regime: {} (age: {}s)", 
                    cachedRegime.regime, age.getSeconds());
                return cachedRegime;
            }
        }
        
        // Cache expired or not set, recalculate
        cachedRegime = detectRegime();
        lastUpdate = Instant.now();
        
        logger.info("🎯 Market Regime: {}", cachedRegime.getSummary());
        return cachedRegime;
    }
    
    /**
     * Detect market regime using all factors
     */
    private MarketRegimeAnalysis detectRegime() {
        try {
            // Use SPY as market proxy
            String marketProxy = "SPY";
            
            // Get trend analysis
            TrendAnalysis trend = analyzeTrend(marketProxy);
            
            // Get volume analysis
            VolumeAnalysis volume = analyzeVolume(marketProxy);
            
            // Get breadth analysis (using market momentum as proxy)
            BreadthAnalysis breadth = analyzeBreadth();
            
            // Get current VIX
            double vix = getCurrentVIX();
            
            // Determine regime based on all factors
            MarketRegime regime = classifyRegime(trend, volume, breadth, vix);
            
            // Calculate confidence based on factor agreement
            double confidence = calculateConfidence(trend, volume, breadth, vix, regime);
            
            return new MarketRegimeAnalysis(
                regime, confidence, trend, volume, breadth, vix, Instant.now()
            );
            
        } catch (Exception e) {
            logger.error("Failed to detect market regime", e);
            // Return safe default
            return new MarketRegimeAnalysis(
                MarketRegime.RANGE_BOUND, 0.5,
                new TrendAnalysis(TrendDirection.NEUTRAL, 0.5, 0, 0, 0, false, false),
                new VolumeAnalysis(VolumeTrend.STABLE, 0, 0, 1.0),
                new BreadthAnalysis(0.5, 0, 0, 1.0),
                20.0, Instant.now()
            );
        }
    }
    
    /**
     * Analyze trend using 50/200 day moving averages
     */
    private TrendAnalysis analyzeTrend(String symbol) throws Exception {
        int shortPeriod = config.getRegimeMAShort();
        int longPeriod = config.getRegimeMALong();
        
        // Get historical bars (need enough for 200-day MA)
        var bars = client.getMarketHistory(symbol, longPeriod + 10);
        
        if (bars.size() < longPeriod) {
            logger.warn("Insufficient bars for MA calculation: {} (need {})", bars.size(), longPeriod);
            return new TrendAnalysis(TrendDirection.NEUTRAL, 0.5, 0, 0, 0, false, false);
        }
        
        // Calculate MAs
        double ma50 = calculateMA(bars, shortPeriod);
        double ma200 = calculateMA(bars, longPeriod);
        double currentPrice = bars.get(bars.size() - 1).close();
        
        // Detect crossovers
        double prevMA50 = calculateMA(bars.subList(0, bars.size() - 1), shortPeriod);
        double prevMA200 = calculateMA(bars.subList(0, bars.size() - 1), longPeriod);
        
        boolean goldenCross = ma50 > ma200 && prevMA50 <= prevMA200;
        boolean deathCross = ma50 < ma200 && prevMA50 >= prevMA200;
        
        // Determine trend direction and strength
        TrendDirection direction;
        double strength;
        
        if (currentPrice > ma50 && ma50 > ma200) {
            // Strong uptrend
            double priceAboveMA = (currentPrice - ma50) / ma50;
            strength = Math.min(1.0, priceAboveMA * 10); // Scale to 0-1
            direction = strength > 0.5 ? TrendDirection.STRONG_UP : TrendDirection.WEAK_UP;
        } else if (currentPrice < ma50 && ma50 < ma200) {
            // Strong downtrend
            double priceBelowMA = (ma50 - currentPrice) / ma50;
            strength = Math.min(1.0, priceBelowMA * 10);
            direction = strength > 0.5 ? TrendDirection.STRONG_DOWN : TrendDirection.WEAK_DOWN;
        } else if (currentPrice > ma50 && ma50 < ma200) {
            // Weak uptrend (price above short MA but below long MA)
            direction = TrendDirection.WEAK_UP;
            strength = 0.4;
        } else if (currentPrice < ma50 && ma50 > ma200) {
            // Weak downtrend
            direction = TrendDirection.WEAK_DOWN;
            strength = 0.4;
        } else {
            // Neutral/choppy
            direction = TrendDirection.NEUTRAL;
            strength = 0.3;
        }
        
        logger.debug("Trend: {} (strength: {:.1f}%), MA50: {:.2f}, MA200: {:.2f}, Price: {:.2f}",
            direction, strength * 100, ma50, ma200, currentPrice);
        
        return new TrendAnalysis(direction, strength, ma50, ma200, currentPrice, goldenCross, deathCross);
    }
    
    /**
     * Analyze volume trends
     */
    private VolumeAnalysis analyzeVolume(String symbol) throws Exception {
        int volumePeriod = config.getRegimeVolumePeriod();
        
        // Get recent bars
        var bars = client.getMarketHistory(symbol, volumePeriod + 5);
        
        if (bars.size() < volumePeriod) {
            return new VolumeAnalysis(VolumeTrend.STABLE, 0, 0, 1.0);
        }
        
        // Calculate average volume
        double avgVolume = bars.stream()
            .limit(volumePeriod)
            .mapToDouble(bar -> bar.volume())
            .average()
            .orElse(0);
        
        double currentVolume = bars.get(bars.size() - 1).volume();
        double volumeRatio = currentVolume / avgVolume;
        
        // Determine trend
        VolumeTrend trend;
        if (volumeRatio > 1.2) {
            trend = VolumeTrend.INCREASING;
        } else if (volumeRatio < 0.8) {
            trend = VolumeTrend.DECREASING;
        } else {
            trend = VolumeTrend.STABLE;
        }
        
        logger.debug("Volume: {} (ratio: {:.2f}x avg)", trend, volumeRatio);
        
        return new VolumeAnalysis(trend, currentVolume, avgVolume, volumeRatio);
    }
    
    /**
     * Analyze market breadth using momentum as proxy
     * In a real implementation, would use advance/decline data
     */
    private BreadthAnalysis analyzeBreadth() {
        try {
            // Use market analyzer to get momentum across symbols
            var analysis = marketAnalyzer.analyze(List.of("SPY", "QQQ", "IWM", "DIA"));
            
            // Count symbols with positive momentum
            int advancing = 0;
            int declining = 0;
            
            for (var score : analysis.assetScores().values()) {
                if (score.changePercent() > 0) {
                    advancing++;
                } else {
                    declining++;
                }
            }
            
            int total = advancing + declining;
            double adRatio = total > 0 ? (double) advancing / total : 0.5;
            double strength = adRatio; // 0.0 to 1.0
            
            logger.debug("Breadth: {:.0f}% (A/D: {}/{})", strength * 100, advancing, declining);
            
            return new BreadthAnalysis(strength, advancing, declining, adRatio);
            
        } catch (Exception e) {
            logger.warn("Failed to analyze breadth: {}", e.getMessage());
            return new BreadthAnalysis(0.5, 0, 0, 1.0);
        }
    }
    
    /**
     * Get current VIX (or proxy)
     */
    private double getCurrentVIX() {
        try {
            // Try to get VIX
            var bar = client.getLatestBar("VIX");
            if (bar.isPresent()) {
                return bar.get().close();
            }
        } catch (Exception e) {
            logger.debug("VIX not available, using proxy");
        }
        
        // Use VIXY as proxy
        try {
            var bar = client.getLatestBar("VIXY");
            if (bar.isPresent()) {
                double vixyPrice = bar.get().close();
                // Rough conversion: VIXY ≈ VIX * 1.8
                return vixyPrice / 1.8;
            }
        } catch (Exception e) {
            logger.warn("Failed to get VIX proxy: {}", e.getMessage());
        }
        
        return 20.0; // Default moderate volatility
    }
    
    /**
     * Classify regime based on all factors
     */
    private MarketRegime classifyRegime(TrendAnalysis trend, VolumeAnalysis volume, 
                                       BreadthAnalysis breadth, double vix) {
        
        // High volatility regime (VIX > 30)
        if (vix > 30) {
            return MarketRegime.HIGH_VOLATILITY;
        }
        
        // Range bound (neutral trend + low volatility)
        if (trend.direction == TrendDirection.NEUTRAL && vix < 15) {
            return MarketRegime.RANGE_BOUND;
        }
        
        // Bullish regimes
        if (trend.direction == TrendDirection.STRONG_UP || trend.direction == TrendDirection.WEAK_UP) {
            // Strong bull: good volume + breadth
            if (volume.trend == VolumeTrend.INCREASING && breadth.strength > 0.6) {
                return MarketRegime.STRONG_BULL;
            }
            // Weak bull: poor volume or breadth
            return MarketRegime.WEAK_BULL;
        }
        
        // Bearish regimes
        if (trend.direction == TrendDirection.STRONG_DOWN || trend.direction == TrendDirection.WEAK_DOWN) {
            // Strong bear: high volume + weak breadth
            if (volume.trend == VolumeTrend.INCREASING && breadth.strength < 0.4) {
                return MarketRegime.STRONG_BEAR;
            }
            // Weak bear: low volume
            return MarketRegime.WEAK_BEAR;
        }
        
        // Default to range bound
        return MarketRegime.RANGE_BOUND;
    }
    
    /**
     * Calculate confidence based on factor agreement
     */
    private double calculateConfidence(TrendAnalysis trend, VolumeAnalysis volume,
                                      BreadthAnalysis breadth, double vix, MarketRegime regime) {
        
        double confidence = 0.5; // Base confidence
        
        // Increase confidence for strong trend
        confidence += trend.strength * 0.3;
        
        // Increase confidence for volume confirmation
        if ((regime == MarketRegime.STRONG_BULL || regime == MarketRegime.STRONG_BEAR) &&
            volume.trend == VolumeTrend.INCREASING) {
            confidence += 0.1;
        }
        
        // Increase confidence for breadth alignment
        if (regime == MarketRegime.STRONG_BULL && breadth.strength > 0.6) {
            confidence += 0.1;
        } else if (regime == MarketRegime.STRONG_BEAR && breadth.strength < 0.4) {
            confidence += 0.1;
        }
        
        // Decrease confidence for conflicting signals
        if (regime == MarketRegime.STRONG_BULL && breadth.strength < 0.5) {
            confidence -= 0.1;
        } else if (regime == MarketRegime.STRONG_BEAR && breadth.strength > 0.5) {
            confidence -= 0.1;
        }
        
        return Math.max(0.3, Math.min(1.0, confidence));
    }
    
    /**
     * Calculate simple moving average
     */
    private double calculateMA(List<com.trading.api.model.Bar> bars, int period) {
        if (bars.size() < period) {
            return 0;
        }
        
        return bars.stream()
            .skip(Math.max(0, bars.size() - period))
            .mapToDouble(bar -> bar.close())
            .average()
            .orElse(0);
    }
}
