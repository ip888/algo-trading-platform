package com.trading.strategy;

import com.trading.analysis.MarketRegimeDetector.MarketRegime;
import com.trading.analysis.MultiTimeframeAnalyzer;
import com.trading.analysis.MultiTimeframeAnalyzer.MultiTimeframeAnalysis;
import com.trading.api.AlpacaClient;
import com.trading.api.model.Bar;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Manages dynamic strategy switching based on market regime detection.
 * Maps market regimes to optimal trading strategies.
 */
public final class StrategyManager {
    private static final Logger logger = LoggerFactory.getLogger(StrategyManager.class);
    private static final double VOLATILITY_THRESHOLD = 0.015; // 1.5%
    
    // Momentum assets that perform well in uptrends (Gold, safe havens, tech leaders)
    private static final java.util.Set<String> MOMENTUM_ASSETS = java.util.Set.of(
        "GLD", "SLV", "TLT", "XLU",  // Safe havens
        "NVDA", "TSLA", "META",       // Momentum tech
        "XLE", "XLK"                  // Sector leaders
    );
    
    private final AlpacaClient client;
    private final RSIStrategy rsiStrategy;
    private final MACDStrategy macdStrategy;
    private final MeanReversionStrategy meanReversionStrategy;
    private final MomentumStrategy momentumStrategy;
    private final MultiTimeframeAnalyzer multiTimeframeAnalyzer;
    private MarketRegime currentRegime = MarketRegime.RANGE_BOUND;
    private String activeStrategy = "None";

    public StrategyManager(AlpacaClient client) {
        this(client, null);
    }
    
    public StrategyManager(AlpacaClient client, MultiTimeframeAnalyzer multiTimeframeAnalyzer) {
        this.client = client;
        this.rsiStrategy = new RSIStrategy();
        this.macdStrategy = new MACDStrategy();
        this.meanReversionStrategy = new MeanReversionStrategy();
        this.momentumStrategy = new MomentumStrategy();
        this.multiTimeframeAnalyzer = multiTimeframeAnalyzer;
        
        if (multiTimeframeAnalyzer != null) {
            logger.info("StrategyManager initialized with multi-timeframe analysis + Momentum Strategy");
        }
    }

    /**
     * Evaluate trading signal using regime-based strategy selection.
     */
    public TradingSignal evaluate(String symbol, double currentPrice, double positionQty, 
                                 MarketRegime regime) {
        try {
            var bars = client.getMarketHistory(symbol, 100);
            var closes = bars.stream().map(Bar::close).toList();
            
            if (closes.size() < 50) {
                logger.warn("Insufficient history: {} bars (need 50+)", closes.size());
                return new TradingSignal.Hold("Insufficient history");
            }
            
            // Check multi-timeframe alignment if enabled
            if (multiTimeframeAnalyzer != null) {
                MultiTimeframeAnalysis mtfAnalysis = multiTimeframeAnalyzer.analyze(symbol);
                
                logger.debug("Multi-timeframe: {}", mtfAnalysis.getSummary());
                
                // If timeframes are not aligned and alignment is required, hold
                if (!mtfAnalysis.aligned() && mtfAnalysis.confidence() < 0.6) {
                    logger.info("{}: Timeframes not aligned ({}), holding", 
                        symbol, mtfAnalysis.getAlignedCount() + "/" + mtfAnalysis.signals().size());
                    return new TradingSignal.Hold("Timeframes not aligned");
                }
                
                // Use multi-timeframe recommendation if confidence is high
                if (mtfAnalysis.confidence() > 0.7) {
                    return switch (mtfAnalysis.recommendation()) {
                        case BUY -> new TradingSignal.Buy("Multi-timeframe BUY signal");
                        case SELL -> new TradingSignal.Sell("Multi-timeframe SELL signal");
                        case HOLD -> new TradingSignal.Hold("Multi-timeframe HOLD");
                    };
                }
            }
            
            return evaluateWithHistory(symbol, currentPrice, positionQty, closes, regime);
            
        } catch (Exception e) {
            logger.error("Error evaluating strategy", e);
            return new TradingSignal.Hold("Error: " + e.getMessage());
        }
    }

    /**
     * Evaluate with regime and price history.
     */
    public TradingSignal evaluateWithHistory(String symbol, double currentPrice, double positionQty, 
                                            List<Double> history, MarketRegime regime) {
        currentRegime = regime;
        
        // Check if this is a momentum asset (should use momentum strategy in uptrends)
        boolean isMomentumAsset = MOMENTUM_ASSETS.contains(symbol);
        
        // Select strategy based on regime AND asset type
        TradingSignal signal = switch (regime) {
            case STRONG_BULL -> {
                if (isMomentumAsset) {
                    // Momentum assets in strong bull: Use Momentum Strategy (buy strength)
                    activeStrategy = "Momentum (Strong Bull)";
                    yield momentumStrategy.evaluateWithHistory(symbol, currentPrice, positionQty, history);
                } else {
                    // Regular assets: MACD Trend Following
                    activeStrategy = "MACD Trend";
                    yield macdStrategy.evaluateWithHistory(symbol, currentPrice, positionQty, history);
                }
            }
            case STRONG_BEAR -> {
                // Strong bear → MACD for trend confirmation before shorts
                activeStrategy = "MACD Trend";
                yield macdStrategy.evaluateWithHistory(symbol, currentPrice, positionQty, history);
            }
            case WEAK_BULL -> {
                if (isMomentumAsset) {
                    // Momentum assets in weak bull: Still use Momentum (they often lead)
                    activeStrategy = "Momentum (Weak Bull)";
                    yield momentumStrategy.evaluateWithHistory(symbol, currentPrice, positionQty, history);
                } else {
                    // Regular assets: RSI with confirmation
                    activeStrategy = "RSI Confirmation";
                    yield rsiStrategy.evaluateWithHistory(symbol, currentPrice, positionQty, history);
                }
            }
            case WEAK_BEAR -> {
                // Weak bear → RSI with confirmation
                activeStrategy = "RSI Confirmation";
                yield rsiStrategy.evaluateWithHistory(symbol, currentPrice, positionQty, history);
            }
            case RANGE_BOUND -> {
                // Sideways market → Mean Reversion
                activeStrategy = "Mean Reversion";
                yield meanReversionStrategy.evaluateWithHistory(symbol, currentPrice, positionQty, history);
            }
            case HIGH_VOLATILITY -> {
                // High volatility → Mean Reversion with tight stops
                activeStrategy = "Mean Reversion (Defensive)";
                yield meanReversionStrategy.evaluateWithHistory(symbol, currentPrice, positionQty, history);
            }
        };

        logger.info("Regime: {} (Strategy={}) → Signal: {}", 
            regime, activeStrategy, signal.toAction());

        return signal;
    }

    /**
     * Backward compatibility: evaluate with VolatilityState.
     */
    public TradingSignal evaluate(String symbol, double currentPrice, double positionQty, 
                                 com.trading.filters.VolatilityFilter.VolatilityState volState) {
        try {
            var bars = client.getMarketHistory(symbol, 100);
            var closes = bars.stream().map(Bar::close).toList();
            
            if (closes.size() < 50) {
                logger.warn("Insufficient history: {} bars (need 50+)", closes.size());
                return new TradingSignal.Hold("Insufficient history");
            }
            
            // Convert VolatilityState to MarketRegime for backward compatibility
            MarketRegime regime = switch (volState) {
                case EXTREME -> MarketRegime.HIGH_VOLATILITY;
                case HIGH -> MarketRegime.STRONG_BULL; // Assume trend in high vol
                case NORMAL -> MarketRegime.RANGE_BOUND;
            };
            
            return evaluateWithHistory(symbol, currentPrice, positionQty, closes, regime);
            
        } catch (Exception e) {
            logger.error("Error evaluating strategy", e);
            return new TradingSignal.Hold("Error: " + e.getMessage());
        }
    }

    public String getActiveStrategy() {
        return activeStrategy;
    }

    private double calculateSMA(List<Double> prices) {
        return prices.stream()
                .mapToDouble(Double::doubleValue)
                .average()
                .orElse(0.0);
    }

    private double calculateVolatility(List<Double> prices, double mean) {
        var sumSqDiff = prices.stream()
                .mapToDouble(p -> Math.pow(p - mean, 2))
                .sum();
        return Math.sqrt(sumSqDiff / prices.size());
    }

    public MarketRegime getCurrentRegime() {
        return currentRegime;
    }
    
    /**
     * Get current regime as string (for logging/display).
     */
    public String getCurrentRegimeString() {
        return currentRegime.toString();
    }
}
