package com.trading.strategy;

import com.trading.analysis.MarketRegimeDetector.MarketRegime;
import com.trading.analysis.MultiTimeframeAnalyzer;
import com.trading.analysis.MultiTimeframeAnalyzer.MultiTimeframeAnalysis;
import com.trading.api.AlpacaClient;
import com.trading.api.model.Bar;
import com.trading.config.Config;
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
    
    // Momentum assets that perform well in uptrends (Gold, safe havens, tech leaders, energy, power)
    private static final java.util.Set<String> MOMENTUM_ASSETS = java.util.Set.of(
        "GLD", "SLV", "TLT", "XLU",  // Safe havens / utilities
        "NVDA", "TSLA", "META",       // Momentum tech
        "XLE", "XLK",                 // Sector leaders
        "XOP",                        // Oil & Gas energy
        "URA", "GRID"                 // Nuclear, grid infrastructure
    );
    
    private final AlpacaClient client;
    private final Config config;
    private final RSIStrategy rsiStrategy;
    private final MACDStrategy macdStrategy;
    private final MeanReversionStrategy meanReversionStrategy;
    private final MomentumStrategy momentumStrategy;
    private final MultiTimeframeAnalyzer multiTimeframeAnalyzer;
    private MarketRegime currentRegime = MarketRegime.RANGE_BOUND;
    private String activeStrategy = "None";

    public StrategyManager(AlpacaClient client) {
        this(client, null, null);
    }
    
    public StrategyManager(AlpacaClient client, MultiTimeframeAnalyzer multiTimeframeAnalyzer) {
        this(client, multiTimeframeAnalyzer, null);
    }
    
    public StrategyManager(AlpacaClient client, MultiTimeframeAnalyzer multiTimeframeAnalyzer, Config config) {
        this.client = client;
        this.config = config;
        this.rsiStrategy = new RSIStrategy();
        this.macdStrategy = new MACDStrategy();
        this.meanReversionStrategy = new MeanReversionStrategy();
        this.momentumStrategy = config != null ? new MomentumStrategy(config) : new MomentumStrategy();
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
                    var mtfSignal = switch (mtfAnalysis.recommendation()) {
                        case BUY -> new TradingSignal.Buy("Multi-timeframe BUY signal");
                        case SELL -> new TradingSignal.Sell("Multi-timeframe SELL signal");
                        case HOLD -> new TradingSignal.Hold("Multi-timeframe HOLD");
                    };

                    // Block BUY signals when short-term price trend is bearish
                    if (mtfSignal instanceof TradingSignal.Buy && isShortTermDowntrend(closes)) {
                        logger.info("{}: Blocked MTF BUY signal - short-term downtrend detected " +
                            "(price below declining 10-bar SMA)", symbol);
                        return new TradingSignal.Hold("Short-term downtrend - blocking BUY");
                    }

                    return mtfSignal;
                }
            }

            var signal = evaluateWithHistory(symbol, currentPrice, positionQty, closes, regime);

            // Block BUY signals from individual strategies when short-term trend is bearish
            if (signal instanceof TradingSignal.Buy && isShortTermDowntrend(closes)) {
                logger.info("{}: Blocked {} BUY signal - short-term downtrend detected",
                    symbol, activeStrategy);
                return new TradingSignal.Hold("Short-term downtrend - blocking BUY");
            }

            return signal;

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
                // FIX: RSI mean-reversion on inverse ETFs (SH, PSQ, SQQQ) gives backwards signals.
                // RSI<30 on SH means the market has been RISING (inverse ETF fell) — wrong time to buy.
                // Use MACD for all symbols in bear regime: it reads trend direction correctly on both
                // regular stocks AND inverse ETFs.
                activeStrategy = "MACD Trend (Weak Bear)";
                yield macdStrategy.evaluateWithHistory(symbol, currentPrice, positionQty, history);
            }
            case RANGE_BOUND -> {
                // Sideways market → Mean Reversion
                activeStrategy = "Mean Reversion";
                yield meanReversionStrategy.evaluateWithHistory(symbol, currentPrice, positionQty, history);
            }
            case HIGH_VOLATILITY -> {
                // FIX: In high volatility, only manage exits — no new entries.
                // Mean Reversion on inverse ETFs in volatile bear markets also gives backwards signals.
                // If holding a position, use MACD to detect exits. Otherwise block new entries.
                activeStrategy = "MACD Exit Only (HighVol)";
                if (positionQty > 0) {
                    yield macdStrategy.evaluateWithHistory(symbol, currentPrice, positionQty, history);
                }
                yield new TradingSignal.Hold("High volatility — no new entries");
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

    /**
     * Detect downtrend to block BUY signals on falling stocks.
     * Returns true when EITHER of:
     *  - Short-term (10-bar): price below declining 10-SMA
     *  - Medium-term (20-bar): price below declining 20-SMA
     *
     * This catches both recent pullbacks AND sustained multi-week downtrends
     * where lagging indicators (MACD, SMA50) still look bullish.
     */
    boolean isShortTermDowntrend(List<Double> closes) {
        if (closes.size() < 22) {
            return false;
        }

        int size = closes.size();

        // ---- 10-bar SMA check (short-term) ----
        double sma10Current = smaOf(closes, size - 10, size);
        double sma10Previous = smaOf(closes, size - 11, size - 1);
        double currentPrice = closes.get(size - 1);
        boolean shortTermDown = currentPrice < sma10Current && sma10Current < sma10Previous;

        if (shortTermDown) {
            double pct = ((sma10Current - currentPrice) / sma10Current) * 100;
            logger.debug("Short-term downtrend: price ${} is {:.2f}% below declining 10-SMA ${}",
                String.format("%.2f", currentPrice), pct, String.format("%.2f", sma10Current));
            return true;
        }

        // ---- 20-bar SMA check (medium-term) ----
        double sma20Current = smaOf(closes, size - 20, size);
        double sma20Previous = smaOf(closes, size - 21, size - 1);
        boolean mediumTermDown = currentPrice < sma20Current && sma20Current < sma20Previous;

        if (mediumTermDown) {
            double pct = ((sma20Current - currentPrice) / sma20Current) * 100;
            logger.debug("Medium-term downtrend: price ${} is {:.2f}% below declining 20-SMA ${}",
                String.format("%.2f", currentPrice), pct, String.format("%.2f", sma20Current));
            return true;
        }

        return false;
    }

    /** Average of closes[from..to) */
    private double smaOf(List<Double> closes, int from, int to) {
        double sum = 0;
        for (int i = from; i < to; i++) sum += closes.get(i);
        return sum / (to - from);
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
