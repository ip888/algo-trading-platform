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
    // Inverse/short ETFs (SH, PSQ, RWM, DOG, SQQQ) — bypass long-only strict routing in WEAK_BEAR.
    private final java.util.Set<String> inverseEtfAssets;
    // Safe-haven assets that outperform during bear markets — get relaxed MACD gate in WEAK_BEAR.
    private final java.util.Set<String> safeHavenAssets;
    
    private final BrokerClient client;
    private final Config config;
    private final MACDStrategy macdStrategy;
    private final MeanReversionStrategy meanReversionStrategy;
    private final MomentumStrategy momentumStrategy;
    private final MultiTimeframeAnalyzer multiTimeframeAnalyzer;
    private final ScalpStrategy scalpStrategy;
    private MarketRegime currentRegime = MarketRegime.RANGE_BOUND;
    private String activeStrategy = "None";
    private double latestVix = 20.0;

    public StrategyManager(BrokerClient client) {
        this(client, null, null);
    }
    
    public StrategyManager(BrokerClient client, MultiTimeframeAnalyzer multiTimeframeAnalyzer) {
        this(client, multiTimeframeAnalyzer, null);
    }
    
    public StrategyManager(BrokerClient client, MultiTimeframeAnalyzer multiTimeframeAnalyzer, Config config) {
        this.client = client;
        this.config = config;
        this.macdStrategy = new MACDStrategy();
        this.meanReversionStrategy = new MeanReversionStrategy();
        this.momentumStrategy = config != null ? new MomentumStrategy(config) : new MomentumStrategy();
        this.multiTimeframeAnalyzer = multiTimeframeAnalyzer;
        this.scalpStrategy = (config != null && client != null) ? new ScalpStrategy(client, config) : null;
        this.momentumAssets = config != null ? config.getMomentumAssets()
            : java.util.Set.of("GLD","SLV","TLT","XLU","NVDA","TSLA","META","XLE","XLK","XOP","URA","GRID");
        this.inverseEtfAssets = config != null ? config.getInverseEtfSymbols()
            : java.util.Set.of("SH","PSQ","RWM","DOG","SQQQ");
        this.safeHavenAssets = java.util.Set.of("GLD","TLT","XLU","SLV");

        if (multiTimeframeAnalyzer != null) {
            logger.info("StrategyManager initialized with multi-timeframe analysis + Momentum Strategy");
        }
    }

    /** Called by ProfileManager before each evaluate() to pass current VIX for threshold scaling. */
    public void setLatestVix(double vix) {
        this.latestVix = vix;
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
            // Momentum assets in RANGE_BOUND use MACD (not mean reversion), so they still
            // need the downtrend guard. Only true mean-reversion entries (non-momentum assets
            // in RANGE_BOUND) skip the guard. Bug: the old blanket flag allowed MACD entries
            // on AMD/MSFT/QQQ to bypass the downtrend check, causing losses on Jul 7 2026.
            boolean isMeanReversion = (regime == MarketRegime.RANGE_BOUND) && !momentumAssets.contains(symbol);
            // Set to true when MTF strongly confirms BUY for momentum assets — passed to
            // evaluateWithHistory so it can relax the MACD entry threshold in WEAK_BEAR.
            boolean highMtfBuy = false;

            // Check multi-timeframe alignment if enabled
            MultiTimeframeAnalysis latestMtfAnalysis = null;
            if (multiTimeframeAnalyzer != null) {
                MultiTimeframeAnalysis mtfAnalysis = multiTimeframeAnalyzer.analyze(symbol);
                latestMtfAnalysis = mtfAnalysis;

                logger.debug("Multi-timeframe: {}", mtfAnalysis.getSummary());

                // If timeframes are not aligned and alignment is required, hold
                if (!mtfAnalysis.aligned() && mtfAnalysis.confidence() < 0.6) {
                    logger.info("{}: Timeframes not aligned ({}), holding",
                        symbol, mtfAnalysis.getAlignedCount() + "/" + mtfAnalysis.signals().size());
                    return new TradingSignal.Hold("Timeframes not aligned");
                }

                // Use multi-timeframe recommendation if confidence is high
                if (mtfAnalysis.confidence() > 0.7) {
                    // SELL and HOLD are always authoritative — exit or wait immediately.
                    if (mtfAnalysis.recommendation() != MultiTimeframeAnalyzer.SignalType.BUY) {
                        return switch (mtfAnalysis.recommendation()) {
                            case SELL -> new TradingSignal.Sell("Multi-timeframe SELL signal");
                            default   -> new TradingSignal.Hold("Multi-timeframe HOLD");
                        };
                    }

                    // MTF says BUY. For non-range-bound regimes, momentum assets need their
                    // own strict entry conditions (MACD). MTF BUY is necessary but not sufficient
                    // — fall through to evaluateWithHistory() with the high-confidence flag.
                    // Momentum assets AND inverse ETFs defer to evaluateWithHistory for full validation.
                    // Inverse ETFs need the MACD-0.20 bypass in evaluateWithHistory — letting them
                    // return Buy directly here would skip that gate entirely.
                    if (!isMeanReversion && (momentumAssets.contains(symbol) || inverseEtfAssets.contains(symbol))) {
                        if (momentumAssets.contains(symbol)) {
                            highMtfBuy = true;
                            logger.info("{}: MTF BUY ({}%) deferred to regime strategy for entry validation",
                                symbol, (int)(mtfAnalysis.confidence() * 100));
                        } else {
                            logger.info("{}: MTF BUY ({}%) deferred to inverse ETF evaluation",
                                symbol, (int)(mtfAnalysis.confidence() * 100));
                        }
                        // fall through to evaluateWithHistory() below
                    } else {
                        // Non-momentum assets and range-bound: use MTF BUY directly after quality gates.
                        // When all gates block the trade, still try scalp — it uses intraday 15-min
                        // data and is not regime-dependent; a blocked MTF BUY doesn't mean
                        // scalp conditions are also unmet.
                        TradingSignal mtfBlockSignal = null;
                        if (!isMeanReversion) {
                            // In WEAK_BEAR, only safe havens (GLD, TLT, XLU, SLV) may trade via
                            // the direct MTF path — other assets buy against the regime trend.
                            if (regime == MarketRegime.WEAK_BEAR && !safeHavenAssets.contains(symbol)) {
                                logger.info("{}: Blocked MTF BUY — WEAK_BEAR, not a safe-haven asset", symbol);
                                mtfBlockSignal = new TradingSignal.Hold("WEAK_BEAR — MTF BUY blocked for non-safe-haven");
                            } else if (isShortTermDowntrend(closes, currentPrice)) {
                                logger.info("{}: Blocked MTF BUY — downtrend (price below declining SMA)", symbol);
                                mtfBlockSignal = new TradingSignal.Hold("Downtrend — blocking BUY");
                            } else if (!isVolumeConfirming(volumes)) {
                                logger.info("{}: Blocked MTF BUY — low volume (below 70% of 20-bar avg)", symbol);
                                mtfBlockSignal = new TradingSignal.Hold("Low volume — BUY not confirmed");
                            } else if (!closes.isEmpty()) {
                                // Block if stock already down >0.5% intraday — MTF uses 15-min data
                                // and can fire BUY while the stock is actively gapping down on the day.
                                double prevClose = closes.get(closes.size() - 1);
                                if (prevClose > 0) {
                                    double intradayPct = (currentPrice - prevClose) / prevClose * 100.0;
                                    if (intradayPct < -0.50) {
                                        logger.info("{}: Blocked MTF BUY — intraday down {}%",
                                            symbol, String.format("%.2f", intradayPct));
                                        mtfBlockSignal = new TradingSignal.Hold(
                                            String.format("Intraday down %.2f%% — blocking MTF BUY", intradayPct));
                                    }
                                }
                            }
                        }
                        if (mtfBlockSignal != null) {
                            // MTF BUY blocked — give scalp a chance before returning HOLD.
                            // Scalp evaluates intraday conditions independently of regime/MTF.
                            if (scalpStrategy != null && positionQty == 0
                                    && regime != MarketRegime.STRONG_BEAR
                                    && regime != MarketRegime.HIGH_VOLATILITY) {
                                var scalpSignal = scalpStrategy.evaluate(symbol, currentPrice, positionQty);
                                if (scalpSignal instanceof TradingSignal.ScalpBuy) {
                                    activeStrategy = "Scalp (15-min)";
                                    return scalpSignal;
                                }
                            }
                            return mtfBlockSignal;
                        }
                        return new TradingSignal.Buy("Multi-timeframe BUY signal");
                    }
                }
            }

            var signal = evaluateWithHistory(symbol, currentPrice, positionQty, closes, regime, highMtfBuy);

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
                // 3. Block if the symbol is already down > 1.0% from yesterday's close.
                // MACD uses daily bars — a BUY signal from yesterday's data does not mean today
                // is a good day to enter. If the stock is actively declining intraday, entering
                // long adds to an active downtrend (IWM -0.75% day on Jul 20 2026 was entered).
                // Threshold widened 0.5→1.0%: post-earnings dips (AMD -0.50% Aug 5 2026) and
                // normal VIX=12 morning chop create false positives at 0.5%. A full 1% intraday
                // decline is a clearer signal of sustained selling pressure.
                if (!closes.isEmpty()) {
                    double yesterdayClose = closes.get(closes.size() - 1);
                    if (yesterdayClose > 0) {
                        double intradayPct = (currentPrice - yesterdayClose) / yesterdayClose * 100.0;
                        if (intradayPct < -1.00) {
                            logger.info("{}: Blocked {} BUY — down {}% on the day vs yesterday close",
                                symbol, activeStrategy, String.format("%.2f", intradayPct));
                            return new TradingSignal.Hold(
                                String.format("Intraday down %.2f%% — blocking BUY (entry against day trend)", intradayPct));
                        }
                    }
                }
                // 4. MTF confidence gradient veto (0.6–0.7): strategy says BUY but MTF is leaning
                //    SELL/HOLD at medium confidence — don't fight a conflicting signal.
                //    Below 0.6 we already returned HOLD above; above 0.7 MTF was authoritative.
                //    This window specifically catches the ambiguous middle zone.
                if (latestMtfAnalysis != null) {
                    double conf = latestMtfAnalysis.confidence();
                    if (conf >= 0.6 && conf <= 0.7
                            && latestMtfAnalysis.recommendation() != MultiTimeframeAnalyzer.SignalType.BUY) {
                        logger.info("{}: MTF gradient veto — strategy BUY blocked by MTF {} at {}% confidence",
                            symbol, latestMtfAnalysis.recommendation(), (int)(conf * 100));
                        return new TradingSignal.Hold(
                            String.format("MTF gradient veto (%s at %.0f%% confidence)",
                                latestMtfAnalysis.recommendation(), conf * 100));
                    }
                }
            }

            // Scalp overlay — check intraday conditions AFTER regime signal.
            // Fires only when: positionQty==0, scalp window active, and not in a crash regime.
            // ScalpBuy overrides a HOLD from the regime strategy, allowing fast intraday entries
            // the daily-bar strategies miss. Does NOT override an existing SELL signal.
            if (scalpStrategy != null
                    && positionQty == 0
                    && !(signal instanceof TradingSignal.Sell)
                    && regime != MarketRegime.STRONG_BEAR
                    && regime != MarketRegime.HIGH_VOLATILITY) {
                var scalpSignal = scalpStrategy.evaluate(symbol, currentPrice, positionQty);
                if (scalpSignal instanceof TradingSignal.ScalpBuy) {
                    activeStrategy = "Scalp (15-min)";
                    return scalpSignal;
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
        return evaluateWithHistory(symbol, currentPrice, positionQty, history, regime, false);
    }

    /**
     * Evaluate with regime, price history, and MTF confidence flag.
     * highMtfConfidence=true relaxes the MACD entry threshold in WEAK_BEAR for momentum assets.
     */
    public TradingSignal evaluateWithHistory(String symbol, double currentPrice, double positionQty,
                                            List<Double> history, MarketRegime regime,
                                            boolean highMtfConfidence) {
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
                    // Regular assets: MACD Trend Following, gated by RSI to prevent extended entries
                    activeStrategy = "MACD Trend";
                    yield rsiFilteredBuy(
                        macdStrategy.evaluateWithHistory(symbol, currentPrice, positionQty, history),
                        history, symbol, positionQty);
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
                // In a weak bull market, Momentum strategy fires first for ALL symbols.
                // Previously only "momentum assets" got Momentum routing — non-momentum ETFs
                // (IWM, QQQ, XLF, XLV, AAPL etc.) went straight to MACD, which produced
                // 100% MACD trades even in WEAK_BULL (confirmed Jul 28-31 2026: 10/10 MACD).
                // Momentum's RSI, SMA, and 3-bar consistency checks naturally filter bad setups;
                // MACD is the fallback when Momentum says HOLD.
                activeStrategy = "Momentum (Weak Bull)";
                var momSignal = momentumStrategy.evaluateWithHistory(symbol, currentPrice, positionQty, history);
                if (!(momSignal instanceof TradingSignal.Hold)) yield momSignal;
                // Momentum said HOLD — fall back to MACD in "sustained uptrend" mode.
                // histogramThreshold=0.0 activates the sustainedUptrend check in MACDStrategy,
                // which accepts an established positive MACD without requiring a growing histogram.
                // In VIX=12 slow grinds, MACD crossed bullish days ago and the histogram plateaued;
                // the default "growing" requirement permanently blocks entry in these markets.
                // RSI cap raised 65→70: RSI 65-70 is normal momentum in a bull, not overextension.
                activeStrategy = isMomentumAsset ? "MACD Trend (Weak Bull, Mom Fallback)" : "MACD Trend (Weak Bull)";
                var macdSignal = rsiFilteredBuy(
                    macdStrategy.evaluateWithHistory(symbol, currentPrice, positionQty, history, 0.0),
                    history, symbol, positionQty, 70.0);
                if (!(macdSignal instanceof TradingSignal.Hold)) yield macdSignal;

                // Last resort for high-confidence MTF signals: if both Momentum and MACD say
                // HOLD but MTF is ≥80% confident AND price is above SMA-20 AND RSI is normal,
                // enter on trend confirmation. In VIX=12 slow grinds the daily MACD histogram
                // is near-zero (neither growing nor clearly positive), blocking entry despite a
                // clear uptrend confirmed by multiple timeframes. Price > SMA-20 with normal RSI
                // is a minimal "trend intact" check that passes the trade through.
                // RSI cap is 78 here (not 70): in a strong bull, RSI 70-78 is normal momentum,
                // not overextension. The MTF ≥80% gate already filters weak/choppy setups.
                if (highMtfConfidence && positionQty == 0 && history.size() >= 20) {
                    double sma20 = history.stream().skip(history.size() - 20).mapToDouble(d -> d).average().orElse(0);
                    double rsi = RSIStrategy.calculateRSI(history, 14);
                    if (currentPrice > sma20 && rsi >= 35.0 && rsi <= 78.0) {
                        activeStrategy = "MTF Trend Entry (Weak Bull)";
                        logger.info("{}: WEAK_BULL high-MTF trend entry — price ${} above SMA-20 ${}, RSI {}",
                            symbol, String.format("%.2f", currentPrice), String.format("%.2f", sma20),
                            String.format("%.1f", rsi));
                        yield new TradingSignal.Buy(String.format(
                            "WEAK_BULL MTF entry: price above SMA-20 (%.2f > %.2f), RSI=%.1f",
                            currentPrice, sma20, rsi));
                    } else {
                        logger.info("{}: WEAK_BULL MTF entry blocked — price ${} vs SMA-20 ${}, RSI {} (need price>SMA, RSI 35-78)",
                            symbol, String.format("%.2f", currentPrice), String.format("%.2f", sma20),
                            String.format("%.1f", rsi));
                    }
                }
                yield new TradingSignal.Hold("WEAK_BULL: waiting for entry setup");
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
                // Inverse ETFs profit from market decline — bypass the long-only strict routing.
                // SH/PSQ/RWM/SQQQ are in bearishSymbols, not momentumAssets, so without this
                // bypass they'd always be blocked as "non-momentum" in WEAK_BEAR.
                boolean isInverseEtf = inverseEtfAssets.contains(symbol);
                if (isInverseEtf && positionQty == 0) {
                    // Low-VIX WEAK_BEAR (calm breadth-weak market): inverse ETFs move slowly —
                    // 0.20 MACD threshold never fires. Use 0.10 when VIX < 16; keep 0.20 for
                    // genuine high-volatility bear markets where momentum is strong.
                    double currentVix = latestVix > 0 ? latestVix : 20.0;
                    double inverseEtfThreshold = currentVix < 16.0 ? 0.10 : 0.20;
                    activeStrategy = "MACD (Inverse ETF, Weak Bear)";
                    yield macdStrategy.evaluateWithHistory(symbol, currentPrice, positionQty, history, inverseEtfThreshold);
                }
                if (config != null && config.isRegimeStrictRoutingEnabled() && positionQty == 0 && !isMomentumAsset) {
                    activeStrategy = "Bear Block (Weak Bear, strict)";
                    yield new TradingSignal.Hold("WEAK_BEAR strict — no new longs on non-momentum asset");
                }
                // Stricter MACD threshold (0.20) for NEW entries in WEAK_BEAR to filter counter-trend
                // bounces (IWM Jul 15 2026: bought on 0.10 threshold, lost money in declining market).
                // Exceptions — use 0.10 (same as position management) for:
                //   1. Safe havens (GLD, TLT, XLU, SLV): these naturally rally during bear markets,
                //      so they're not going against the regime — they're aligned with it.
                //   2. High-MTF-confidence momentum (>70%): multi-timeframe agreement is strong
                //      enough to override the conservative gate for momentum names like XLE.
                boolean isSafeHaven = safeHavenAssets.contains(symbol);
                boolean useRelaxed = positionQty == 0 && (isSafeHaven || highMtfConfidence);
                double weakBearThreshold = (!useRelaxed && positionQty == 0) ? 0.20 : 0.10;
                activeStrategy = useRelaxed
                    ? (isSafeHaven ? "MACD (Safe Haven, Weak Bear)" : "MACD (MTF-Confirmed, Weak Bear)")
                    : "MACD Trend (Weak Bear)";
                yield macdStrategy.evaluateWithHistory(symbol, currentPrice, positionQty, history, weakBearThreshold);
            }
            case RANGE_BOUND -> {
                // ── Strategy competition in RANGE_BOUND ─────────────────────────
                // Run two strategies per symbol and take the first BUY signal.
                // Single-strategy selection produces 0 signals on flat days where one
                // strategy has nothing to say but another does.
                if (isMomentumAsset) {
                    // Momentum assets: MACD trend first (their primary driver is momentum).
                    // VIX-adaptive threshold: 0.12 at VIX<15 (calm market), 0.20 otherwise.
                    double rangeBoundThreshold = latestVix < 15.0 ? 0.12 : 0.20;
                    activeStrategy = "MACD Trend (Range, Momentum Asset)";
                    var macdSignal = rsiFilteredBuy(
                        macdStrategy.evaluateWithHistory(symbol, currentPrice, positionQty, history, rangeBoundThreshold),
                        history, symbol, positionQty);
                    if (!(macdSignal instanceof TradingSignal.Hold)) yield macdSignal;

                    // MACD says HOLD — momentum asset pulled back. Try mean reversion:
                    // if price is below its 20-day SMA by 2σ, a bounce back to the mean
                    // is tradeable even on a normally-trending name (NVDA/GLD/XLE all
                    // mean-revert within their longer-term uptrends during flat markets).
                    activeStrategy = "Mean Reversion (Range, Fallback)";
                    yield meanReversionStrategy.evaluateWithHistory(symbol, currentPrice, positionQty, history);
                }
                // Non-momentum assets: Mean Reversion first (sideways oscillation is their regime).
                activeStrategy = "Mean Reversion";
                var mrSignal = meanReversionStrategy.evaluateWithHistory(symbol, currentPrice, positionQty, history);
                if (!(mrSignal instanceof TradingSignal.Hold)) yield mrSignal;

                // Mean reversion not at lower band — try MACD trend as second strategy.
                // Uses 0.15 threshold (between default 0.10 and strict 0.20): these are not
                // momentum assets so we need stronger confirmation than for momentum names,
                // but the 0.20 threshold is too strict in a calm low-VIX market.
                activeStrategy = "MACD Trend (Range, Fallback)";
                yield rsiFilteredBuy(
                    macdStrategy.evaluateWithHistory(symbol, currentPrice, positionQty, history, 0.15),
                    history, symbol, positionQty);
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
     * Gates any BUY signal from MACD with an RSI check.
     * RSI > 65: move is already extended — entering here means buying near a short-term peak,
     *           which the 1% stop will hit on the very next daily retrace.
     * RSI < 35: momentum is broken — MACD crossover in a downtrend is a false signal.
     * The sweet spot 35–65 is where MACD trend-following entries have positive expectancy.
     * Has no effect on SELL or HOLD signals, or on exits (positionQty > 0).
     */
    private TradingSignal rsiFilteredBuy(TradingSignal signal, List<Double> history,
                                         String symbol, double positionQty) {
        return rsiFilteredBuy(signal, history, symbol, positionQty, 65.0);
    }

    private TradingSignal rsiFilteredBuy(TradingSignal signal, List<Double> history,
                                         String symbol, double positionQty, double rsiUpperBound) {
        if (!(signal instanceof TradingSignal.Buy) || positionQty > 0) return signal;
        double rsi = RSIStrategy.calculateRSI(history, 14);
        if (rsi > rsiUpperBound) {
            logger.info("{}: MACD BUY blocked — RSI extended ({} > {}, don't chase)",
                symbol, String.format("%.1f", rsi), String.format("%.0f", rsiUpperBound));
            return new TradingSignal.Hold(String.format("MACD BUY filtered: RSI extended (%.1f > %.0f)", rsi, rsiUpperBound));
        }
        if (rsi < 35.0) {
            logger.info("{}: MACD BUY blocked — RSI weak ({} < 35, momentum not confirmed)",
                symbol, String.format("%.1f", rsi));
            return new TradingSignal.Hold(String.format("MACD BUY filtered: RSI too weak (%.1f < 35)", rsi));
        }
        return signal;
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

        // ---- 20-bar SMA check (medium-term) ----
        // Require BOTH: price below declining SMA-20. A post-earnings dip (e.g. AMD -7% day after
        // earnings) can push price below the 10-day SMA while the stock remains above its 20-day SMA
        // — that is a healthy correction in an uptrend, not a downtrend. The 10-bar check was
        // removed because it false-fires on 2-week pullbacks and blocks valid post-earnings bounces.
        double sma20Current = smaOf(closes, size - 20, size);
        double sma20Previous = smaOf(closes, size - 21, size - 1);
        boolean mediumTermDown = livePrice < sma20Current && sma20Current < sma20Previous;

        if (mediumTermDown) {
            double pct = ((sma20Current - livePrice) / sma20Current) * 100;
            logger.info("Downtrend check: price ${} is {}% below declining 20-SMA ${}",
                String.format("%.2f", livePrice), String.format("%.2f", pct), String.format("%.2f", sma20Current));
            return true;
        }

        // ---- 50-bar SMA check (macro trend) ----
        // Any stock trading below its 50-bar SMA is in a macro downtrend regardless of short-term bounces.
        if (size >= 52) {
            double sma50 = smaOf(closes, size - 50, size);
            if (livePrice < sma50) {
                double pct = ((sma50 - livePrice) / sma50) * 100;
                logger.info("Downtrend check: price ${} is {}% below 50-SMA ${}",
                    String.format("%.2f", livePrice), String.format("%.2f", pct), String.format("%.2f", sma50));
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


    public MarketRegime getCurrentRegime() {
        return currentRegime;
    }

    public String getCurrentRegimeString() {
        return currentRegime.toString();
    }

    /**
     * Lightweight scalp-only evaluation — calls ScalpStrategy directly, skipping MTF/regime analysis.
     * Used by the scalp-priority scan in ProfileManager to check high-liquidity symbols every cycle.
     */
    public TradingSignal evaluateScalpOnly(String symbol, double currentPrice, double positionQty) {
        if (scalpStrategy == null) return new TradingSignal.Hold("Scalp disabled");
        return scalpStrategy.evaluate(symbol, currentPrice, positionQty);
    }
}
