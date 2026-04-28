package com.trading.strategy;

import com.trading.analysis.MarketRegimeDetector.MarketRegime;
import com.trading.analysis.MultiTimeframeAnalyzer;
import com.trading.analysis.MultiTimeframeAnalyzer.MultiTimeframeAnalysis;
import com.trading.api.BrokerClient;
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
    
    // Momentum assets loaded from config (MOMENTUM_ASSETS key), with hardcoded fallback.
    // Use config to tune without redeploying.
    private final java.util.Set<String> momentumAssets;
    
    private final BrokerClient client;
    private final Config config;
    private final RSIStrategy rsiStrategy;
    private final MACDStrategy macdStrategy;
    private final MeanReversionStrategy meanReversionStrategy;
    private final MomentumStrategy momentumStrategy;
    private final MultiTimeframeAnalyzer multiTimeframeAnalyzer;
    private MarketRegime currentRegime = MarketRegime.RANGE_BOUND;
    private String activeStrategy = "None";

    public StrategyManager(BrokerClient client) {
        this(client, null, null);
    }
    
    public StrategyManager(BrokerClient client, MultiTimeframeAnalyzer multiTimeframeAnalyzer) {
        this(client, multiTimeframeAnalyzer, null);
    }
    
    public StrategyManager(BrokerClient client, MultiTimeframeAnalyzer multiTimeframeAnalyzer, Config config) {
        this.client = client;
        this.config = config;
        this.rsiStrategy = new RSIStrategy();
        this.macdStrategy = new MACDStrategy();
        this.meanReversionStrategy = new MeanReversionStrategy();
        this.momentumStrategy = config != null ? new MomentumStrategy(config) : new MomentumStrategy();
        this.multiTimeframeAnalyzer = multiTimeframeAnalyzer;
        this.momentumAssets = config != null ? config.getMomentumAssets()
            : java.util.Set.of("GLD","SLV","TLT","XLU","NVDA","TSLA","META","XLE","XLK","XOP","URA","GRID");

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
            var volumes = bars.stream().mapToLong(Bar::volume).boxed().toList();

            if (closes.size() < 50) {
                logger.warn("Insufficient history: {} bars (need 50+)", closes.size());
                return new TradingSignal.Hold("Insufficient history");
            }

            // Mean reversion legitimately buys below SMA — skip trend/volume guards for it.
            boolean isMeanReversion = (regime == MarketRegime.RANGE_BOUND);

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

                    if (mtfSignal instanceof TradingSignal.Buy && !isMeanReversion) {
                        if (isShortTermDowntrend(closes, currentPrice)) {
                            logger.info("{}: Blocked MTF BUY — downtrend (price below declining SMA)", symbol);
                            return new TradingSignal.Hold("Downtrend — blocking BUY");
                        }
                        if (!isVolumeConfirming(volumes)) {
                            logger.info("{}: Blocked MTF BUY — low volume (below 70% of 20-bar avg)", symbol);
                            return new TradingSignal.Hold("Low volume — BUY not confirmed");
                        }
                    }

                    return mtfSignal;
                }
            }

            var signal = evaluateWithHistory(symbol, currentPrice, positionQty, closes, regime);

            if (signal instanceof TradingSignal.Buy && !isMeanReversion) {
                // 1. Block if price is in a short-term or medium-term downtrend
                if (isShortTermDowntrend(closes, currentPrice)) {
                    logger.info("{}: Blocked {} BUY — downtrend detected", symbol, activeStrategy);
                    return new TradingSignal.Hold("Downtrend — blocking BUY");
                }
                // 2. Block if volume is too low to support the move
                if (!isVolumeConfirming(volumes)) {
                    logger.info("{}: Blocked {} BUY — low volume", symbol, activeStrategy);
                    return new TradingSignal.Hold("Low volume — BUY not confirmed");
                }
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
        boolean isMomentumAsset = momentumAssets.contains(symbol);
        
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
                if (positionQty == 0) {
                    // Bear market: never open new long positions — wait for regime to change.
                    activeStrategy = "Bear Market Block";
                    yield new TradingSignal.Hold("STRONG_BEAR — no new long entries");
                }
                // Already holding: use MACD to find the best exit
                activeStrategy = "MACD Exit (Strong Bear)";
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
                //
                // Tier 3.8 strict mode: in WEAK_BEAR, only manage existing longs — don't open new
                // ones on a non-momentum asset. MACD can fire a BUY on a counter-trend bounce that
                // historically hasn't paid here. If the user disables strict mode, fall back to the
                // old behaviour (MACD for all positionQty values).
                if (config != null && config.isRegimeStrictRoutingEnabled() && positionQty == 0 && !isMomentumAsset) {
                    activeStrategy = "Bear Block (Weak Bear, strict)";
                    yield new TradingSignal.Hold("WEAK_BEAR strict — no new longs on non-momentum asset");
                }
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
    /** Overload for tests — uses last close as live price. */
    boolean isShortTermDowntrend(List<Double> closes) {
        return isShortTermDowntrend(closes, closes.isEmpty() ? 0.0 : closes.get(closes.size() - 1));
    }

    boolean isShortTermDowntrend(List<Double> closes, double livePrice) {
        if (closes.size() < 22) {
            return false;
        }

        int size = closes.size();

        // ---- 10-bar SMA check (short-term) ----
        double sma10Current = smaOf(closes, size - 10, size);
        double sma10Previous = smaOf(closes, size - 11, size - 1);
        // Use live intraday price instead of last daily close — catches today's drop
        boolean shortTermDown = livePrice < sma10Current && sma10Current < sma10Previous;

        if (shortTermDown) {
            double pct = ((sma10Current - livePrice) / sma10Current) * 100;
            logger.debug("Short-term downtrend: price ${} is {}% below declining 10-SMA ${}",
                String.format("%.2f", livePrice), String.format("%.2f", pct), String.format("%.2f", sma10Current));
            return true;
        }

        // ---- 20-bar SMA check (medium-term) ----
        double sma20Current = smaOf(closes, size - 20, size);
        double sma20Previous = smaOf(closes, size - 21, size - 1);
        boolean mediumTermDown = livePrice < sma20Current && sma20Current < sma20Previous;

        if (mediumTermDown) {
            double pct = ((sma20Current - livePrice) / sma20Current) * 100;
            logger.debug("Medium-term downtrend: price ${} is {}% below declining 20-SMA ${}",
                String.format("%.2f", livePrice), String.format("%.2f", pct), String.format("%.2f", sma20Current));
            return true;
        }

        // ---- 50-bar SMA check (macro trend) ----
        // Any stock trading below its 50-bar SMA is in a macro downtrend regardless of short-term bounces.
        // No requirement for SMA to be declining — being below it is sufficient.
        if (size >= 52) {
            double sma50 = smaOf(closes, size - 50, size);
            if (livePrice < sma50) {
                double pct = ((sma50 - livePrice) / sma50) * 100;
                logger.debug("Macro downtrend: price ${} is {:.2f}% below 50-SMA ${}",
                    String.format("%.2f", livePrice), pct, String.format("%.2f", sma50));
                return true;
            }
        }

        return false;
    }

    /**
     * Returns true if the last bar's volume is sufficient to confirm a BUY signal.
     * Low-volume breakouts and rallies frequently fail — require last bar ≥ 70% of
     * the 20-bar average (excluding last bar to avoid partial-day skew).
     */
    private boolean isVolumeConfirming(List<Long> volumes) {
        if (volumes.size() < 22) return true; // insufficient data — don't block

        int last = volumes.size() - 1;
        int avgStart = Math.max(0, last - 20);
        long sum = 0;
        for (int i = avgStart; i < last; i++) sum += volumes.get(i);
        double avgVolume = (double) sum / (last - avgStart);

        if (avgVolume <= 0) return true;

        long lastVolume = volumes.get(last);
        boolean confirming = lastVolume >= avgVolume * 0.70;
        if (!confirming) {
            logger.debug("Low volume: last={} avg={} ratio={:.2f}",
                lastVolume, (long) avgVolume,
                avgVolume > 0 ? lastVolume / avgVolume : 0.0);
        }
        return confirming;
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
