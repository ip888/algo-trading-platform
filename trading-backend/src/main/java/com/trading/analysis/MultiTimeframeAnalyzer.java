package com.trading.analysis;

import com.trading.api.AlpacaClient;
import com.trading.api.model.Bar;
import com.trading.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Multi-timeframe analysis for improved entry/exit timing.
 * Analyzes trends across multiple timeframes (5M, 15M, 1H, 1D) to:
 * - Confirm trend alignment before entries
 * - Use lower timeframes for precise entry timing
 * - Detect divergences for early warnings
 * - Improve exit timing with lower timeframe reversals
 */
public class MultiTimeframeAnalyzer {
    private static final Logger logger = LoggerFactory.getLogger(MultiTimeframeAnalyzer.class);
    
    private final AlpacaClient client;
    private final Config config;
    private com.trading.autonomous.AdaptiveParameterManager adaptiveManager;
    
    // Cache for timeframe data
    private final Map<String, TimeframeCache> cache = new HashMap<>();
    private static final Duration CACHE_EXPIRY = Duration.ofMinutes(1);
    
    public MultiTimeframeAnalyzer(AlpacaClient client, Config config) {
        this.client = client;
        this.config = config;
        
        logger.info("MultiTimeframeAnalyzer initialized with timeframes: {}", 
            String.join(", ", config.getMultiTimeframeTimeframes()));
    }
    
    /**
     * Set adaptive parameter manager for autonomous tuning.
     */
    public void setAdaptiveManager(com.trading.autonomous.AdaptiveParameterManager manager) {
        this.adaptiveManager = manager;
        logger.info("MultiTimeframeAnalyzer now using adaptive parameter tuning");
    }
    
    /**
     * Timeframe periods for analysis
     */
    public enum Timeframe {
        FIVE_MIN("5Min", 5, 100),      // 5-minute bars, 100 bars = ~8 hours
        FIFTEEN_MIN("15Min", 15, 100), // 15-minute bars, 100 bars = ~1 day
        ONE_HOUR("1Hour", 60, 100),    // 1-hour bars, 100 bars = ~4 days
        ONE_DAY("1Day", 1440, 100);    // Daily bars, 100 bars = ~100 days
        
        private final String alpacaFormat;
        private final int minutes;
        private final int barCount;
        
        Timeframe(String alpacaFormat, int minutes, int barCount) {
            this.alpacaFormat = alpacaFormat;
            this.minutes = minutes;
            this.barCount = barCount;
        }
        
        public String getAlpacaFormat() { return alpacaFormat; }
        public int getMinutes() { return minutes; }
        public int getBarCount() { return barCount; }
    }
    
    /**
     * Trend direction for a timeframe
     */
    public enum TrendDirection {
        STRONG_UP,
        WEAK_UP,
        NEUTRAL,
        WEAK_DOWN,
        STRONG_DOWN
    }
    
    /**
     * Signal type for a timeframe
     */
    public enum SignalType {
        BUY,
        SELL,
        HOLD
    }
    
    /**
     * Analysis result for a single timeframe
     */
    public record TimeframeSignal(
        Timeframe timeframe,
        TrendDirection trend,
        double strength,      // 0.0 to 1.0
        SignalType signal,
        double sma20,
        double sma50,
        double currentPrice
    ) {
        public String getSummary() {
            return String.format("%s: %s (%.0f%%) - %s", 
                timeframe, trend, strength * 100, signal);
        }
    }
    
    /**
     * Complete multi-timeframe analysis
     */
    public record MultiTimeframeAnalysis(
        String symbol,
        List<TimeframeSignal> signals,
        boolean aligned,           // All timeframes agree
        TrendDirection primaryTrend,
        double confidence,         // 0.0 to 1.0
        SignalType recommendation,
        Instant timestamp
    ) {
        public String getSummary() {
            return String.format("%s: %s (%.0f%% confidence) - %s | Aligned: %s",
                symbol, primaryTrend, confidence * 100, recommendation, aligned ? "YES" : "NO");
        }
        
        public int getAlignedCount() {
            if (signals.isEmpty()) return 0;
            TrendDirection primary = signals.get(signals.size() - 1).trend(); // Use daily as primary
            return (int) signals.stream()
                .filter(s -> s.trend() == primary)
                .count();
        }
    }
    
    /**
     * Analyze symbol across all configured timeframes
     */
    public MultiTimeframeAnalysis analyze(String symbol) {
        try {
            List<TimeframeSignal> signals = new ArrayList<>();
            
            // Analyze each timeframe
            for (Timeframe tf : getConfiguredTimeframes()) {
                try {
                    TimeframeSignal signal = analyzeTimeframe(symbol, tf);
                    signals.add(signal);
                } catch (Exception e) {
                    logger.warn("Failed to analyze {} on {}: {}", symbol, tf, e.getMessage());
                }
            }
            
            if (signals.isEmpty()) {
                logger.warn("No timeframe signals available for {}", symbol);
                return createDefaultAnalysis(symbol);
            }
            
            // Determine primary trend (use daily/longest timeframe)
            TrendDirection primaryTrend = signals.get(signals.size() - 1).trend();
            
            // Check alignment
            boolean aligned = checkAlignment(signals);
            
            // Calculate confidence
            double confidence = calculateConfidence(signals, aligned);
            
            // Generate recommendation
            SignalType recommendation = generateRecommendation(signals, aligned);
            
            logger.debug("Multi-timeframe analysis for {}: {} (confidence: {:.0f}%)", 
                symbol, recommendation, confidence * 100);
            
            return new MultiTimeframeAnalysis(
                symbol, signals, aligned, primaryTrend, 
                confidence, recommendation, Instant.now()
            );
            
        } catch (Exception e) {
            logger.error("Failed to analyze {} across timeframes", symbol, e);
            return createDefaultAnalysis(symbol);
        }
    }
    
    /**
     * Analyze a single timeframe
     */
    private TimeframeSignal analyzeTimeframe(String symbol, Timeframe timeframe) throws Exception {
        // Check cache first
        String cacheKey = symbol + "_" + timeframe;
        TimeframeCache cached = cache.get(cacheKey);
        if (cached != null && Duration.between(cached.timestamp, Instant.now()).compareTo(CACHE_EXPIRY) < 0) {
            return cached.signal;
        }
        
        // Fetch bars for this timeframe
        // Note: Alpaca's getMarketHistory uses daily bars, so we'll need to adapt
        // For now, we'll use daily bars and simulate other timeframes
        List<Bar> bars = client.getMarketHistory(symbol, timeframe.barCount);
        
        if (bars.size() < 50) {
            throw new Exception("Insufficient bars: " + bars.size());
        }
        
        List<Double> closes = bars.stream().map(Bar::close).collect(Collectors.toList());
        double currentPrice = closes.get(closes.size() - 1);
        
        // Calculate SMAs
        double sma20 = calculateSMA(closes, 20);
        double sma50 = calculateSMA(closes, 50);
        
        // Determine trend
        TrendDirection trend = determineTrend(currentPrice, sma20, sma50, closes);
        
        // Calculate trend strength
        double strength = calculateTrendStrength(currentPrice, sma20, sma50);
        
        // Generate signal
        SignalType signal = generateSignal(trend, currentPrice, sma20, sma50);
        
        TimeframeSignal result = new TimeframeSignal(
            timeframe, trend, strength, signal, sma20, sma50, currentPrice
        );
        
        // Cache result
        cache.put(cacheKey, new TimeframeCache(result, Instant.now()));
        
        return result;
    }
    
    /**
     * Determine trend direction
     */
    private TrendDirection determineTrend(double price, double sma20, double sma50, List<Double> closes) {
        boolean priceAbove20 = price > sma20;
        boolean priceAbove50 = price > sma50;
        boolean sma20Above50 = sma20 > sma50;
        
        // Calculate momentum (last 10 bars)
        double momentum = 0;
        if (closes.size() >= 10) {
            double recent = closes.get(closes.size() - 1);
            double past = closes.get(closes.size() - 10);
            momentum = (recent - past) / past;
        }
        
        if (priceAbove20 && priceAbove50 && sma20Above50) {
            return momentum > 0.02 ? TrendDirection.STRONG_UP : TrendDirection.WEAK_UP;
        } else if (!priceAbove20 && !priceAbove50 && !sma20Above50) {
            return momentum < -0.02 ? TrendDirection.STRONG_DOWN : TrendDirection.WEAK_DOWN;
        } else {
            return TrendDirection.NEUTRAL;
        }
    }
    
    /**
     * Calculate trend strength (0.0 to 1.0)
     */
    private double calculateTrendStrength(double price, double sma20, double sma50) {
        // Measure how far price is from SMAs
        double deviation20 = Math.abs(price - sma20) / sma20;
        double deviation50 = Math.abs(price - sma50) / sma50;
        
        // Average deviation as strength indicator
        double avgDeviation = (deviation20 + deviation50) / 2.0;
        
        // Convert to 0-1 scale (cap at 10% deviation = max strength)
        return Math.min(1.0, avgDeviation * 10);
    }
    
    /**
     * Generate signal for timeframe
     * OPTIMIZED v2.0: More active in trends while maintaining safety
     * - STRONG_UP: Buy with trend (momentum), no pullback needed
     * - WEAK_UP: Buy if price within 3% of SMA20 (relaxed from 1% below)
     * - NEUTRAL: Hold (unchanged)
     */
    private SignalType generateSignal(TrendDirection trend, double price, double sma20, double sma50) {
        return switch (trend) {
            case STRONG_UP -> {
                // Strong uptrend: Buy with momentum! Price above SMA20 is GOOD
                // Only skip if severely overbought (>5% above SMA20)
                if (price < sma20 * 1.05) {
                    yield SignalType.BUY;
                }
                yield SignalType.HOLD;  // Too extended, wait for pullback
            }
            case WEAK_UP -> {
                // Weak uptrend: Buy on slight pullbacks or near SMA20
                // Relaxed from 1% below to 3% above (catches more opportunities)
                if (price < sma20 * 1.03) {
                    yield SignalType.BUY;
                }
                yield SignalType.HOLD;
            }
            case STRONG_DOWN, WEAK_DOWN -> {
                // Sell if price rallies to SMA20
                if (price > sma20 * 0.99) {
                    yield SignalType.SELL;
                }
                yield SignalType.HOLD;
            }
            case NEUTRAL -> SignalType.HOLD;
        };
    }
    
    /**
     * Check if timeframes are aligned
     */
    private boolean checkAlignment(List<TimeframeSignal> signals) {
        // Use adaptive parameters if available
        int minAligned = adaptiveManager != null ? 
            adaptiveManager.getAdaptiveMinAligned() : 
            config.getMultiTimeframeMinAligned();
        
        if (signals.size() < minAligned) {
            return false;
        }
        
        // Get primary trend (daily/longest)
        TrendDirection primaryTrend = signals.get(signals.size() - 1).trend();
        
        // Count how many timeframes agree
        long aligned = signals.stream()
            .filter(s -> s.trend() == primaryTrend)
            .count();
        
        return aligned >= minAligned;
    }
    
    /**
     * Calculate confidence based on alignment and strength
     */
    private double calculateConfidence(List<TimeframeSignal> signals, boolean aligned) {
        if (signals.isEmpty()) return 0.5;
        
        // Base confidence
        double confidence = 0.5;
        
        // Increase for alignment
        if (aligned) {
            confidence += 0.2;
        }
        
        // Increase for strong trends
        double avgStrength = signals.stream()
            .mapToDouble(TimeframeSignal::strength)
            .average()
            .orElse(0.5);
        confidence += avgStrength * 0.3;
        
        return Math.max(0.0, Math.min(1.0, confidence));
    }
    
    /**
     * Generate trading recommendation
     * OPTIMIZED v2.0: More active in uptrends
     * - Allow BUY without alignment if majority bullish and high confidence
     * - In STRONG_UP, bias toward BUY
     */
    private SignalType generateRecommendation(List<TimeframeSignal> signals, boolean aligned) {
        if (signals.isEmpty()) return SignalType.HOLD;
        
        // Count bullish/bearish timeframes
        long bullish = signals.stream()
            .filter(s -> s.trend() == TrendDirection.STRONG_UP || s.trend() == TrendDirection.WEAK_UP)
            .count();
        long bearish = signals.stream()
            .filter(s -> s.trend() == TrendDirection.STRONG_DOWN || s.trend() == TrendDirection.WEAK_DOWN)
            .count();
        
        // Average trend strength
        double avgStrength = signals.stream()
            .mapToDouble(TimeframeSignal::strength)
            .average()
            .orElse(0.5);
        
        // Primary trend from longest timeframe
        TrendDirection primary = signals.get(signals.size() - 1).trend();
        
        // OPTIMIZED: More aggressive in strong uptrends
        // If majority bullish (> 50%) and not in downtrend, allow BUY
        double bullishPct = (double) bullish / signals.size();
        
        if (primary == TrendDirection.STRONG_UP && bullishPct >= 0.5) {
            // Strong uptrend + majority bullish = BUY
            logger.debug("STRONG_UP with {}% bullish timeframes - recommending BUY", 
                String.format("%.0f", bullishPct * 100));
            return SignalType.BUY;
        }
        
        if (bullishPct >= 0.6 && avgStrength >= 0.4) {
            // 60%+ bullish with decent strength = BUY
            logger.debug("{}% bullish timeframes with {:.0f}% strength - recommending BUY", 
                String.format("%.0f", bullishPct * 100), avgStrength * 100);
            return SignalType.BUY;
        }
        
        // Use adaptive parameters for alignment requirement
        boolean requireAlignment = adaptiveManager != null ? 
            adaptiveManager.isAdaptiveRequireAlignment() : 
            config.isMultiTimeframeRequireAlignment();
        
        if (!requireAlignment || aligned) {
            // Use entry timeframe (15M by default) for signal
            String entryTF = config.getMultiTimeframeEntryTimeframe();
            
            Optional<TimeframeSignal> entrySignal = signals.stream()
                .filter(s -> s.timeframe().toString().contains(entryTF))
                .findFirst();
            
            if (entrySignal.isPresent()) {
                return entrySignal.get().signal();
            }
            
            // Fallback to shortest timeframe
            return signals.get(0).signal();
        }
        
        return SignalType.HOLD;
    }
    
    /**
     * Calculate simple moving average
     */
    private double calculateSMA(List<Double> prices, int period) {
        if (prices.size() < period) {
            return prices.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        }
        
        return prices.stream()
            .skip(Math.max(0, prices.size() - period))
            .mapToDouble(Double::doubleValue)
            .average()
            .orElse(0.0);
    }
    
    /**
     * Get configured timeframes
     */
    private List<Timeframe> getConfiguredTimeframes() {
        // For now, return all timeframes
        // In future, could parse from config
        return List.of(
            Timeframe.FIFTEEN_MIN,  // Entry timing
            Timeframe.ONE_HOUR,     // Intermediate trend
            Timeframe.ONE_DAY       // Primary trend
        );
    }
    
    /**
     * Create default analysis when data unavailable
     */
    private MultiTimeframeAnalysis createDefaultAnalysis(String symbol) {
        return new MultiTimeframeAnalysis(
            symbol,
            List.of(),
            false,
            TrendDirection.NEUTRAL,
            0.5,
            SignalType.HOLD,
            Instant.now()
        );
    }
    
    /**
     * Cache entry for timeframe data
     */
    private record TimeframeCache(
        TimeframeSignal signal,
        Instant timestamp
    ) {}
}
