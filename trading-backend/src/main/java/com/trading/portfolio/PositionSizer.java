package com.trading.portfolio;

import com.trading.ai.AnomalyDetector;
import com.trading.ai.RiskPredictor;
import com.trading.ai.SentimentAnalyzer;
import com.trading.analysis.AtrCalculator;
import com.trading.analysis.MarketRegimeDetector.MarketRegime;
import com.trading.api.ResilientBrokerClient;
import com.trading.autonomous.AdaptiveParameterManager;
import com.trading.config.Config;
import com.trading.persistence.TradeDatabase;
import com.trading.risk.AdvancedPositionSizer;
import com.trading.risk.RiskManager;
import com.trading.scoring.MLEntryScorer;
import com.trading.sizing.AdaptivePositionSizer;
import com.trading.strategy.TradingProfile;
import com.trading.websocket.TradingWebSocketHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.function.ToDoubleFunction;

/**
 * Computes the final share quantity and stop-loss/take-profit levels for a BUY entry.
 *
 * <p>Extracted 2026-08-30 from {@code ProfileManager.handleBuy()} as the second step of the
 * ProfileManager simplification (see {@link RiskGate}'s Javadoc for the first). This is pure
 * relocation of the existing sizing pipeline — base Kelly sizing, bear-streak penalty, Phase 3
 * adaptive sizing, the buying-power cap, the two AI gates (anomaly / risk), ATR-scaled stop and
 * take-profit, ATR vol-targeted sizing, the hard notional cap, and the afternoon sizing haircut —
 * in that exact order, with no behavior change.
 *
 * <h2>Why the result is a sealed type instead of a plain {@code double}</h2>
 * In the original inline code, two of the AI gates could abort the BUY entirely partway through
 * sizing: anomaly detection on {@code HALT_TRADING} and risk prediction on
 * {@code riskPredictor.isTooRisky(...)} each did an early {@code return;} straight out of
 * {@code handleBuy()}. A plain method extracted from that code can no longer {@code return} out
 * of its caller — so instead of silently losing that abort path (which would let the bot place
 * orders it used to correctly skip), the two outcomes are made explicit as a {@link Result}:
 * {@link Sized} (proceed with this quantity/SL/TP) or {@link Halt} (skip the entry — the reason
 * is already logged, matching what the inline {@code return;} sites used to log). Callers must
 * branch on the result at exactly the point the old code would have returned.
 */
final class PositionSizer {

    private static final Logger logger = LoggerFactory.getLogger(PositionSizer.class);

    /** Outcome of a sizing evaluation: either proceed with an order, or halt the entry. */
    sealed interface Result permits Sized, Halt {}

    /** Proceed with a BUY of {@code quantity} shares at the given stop-loss/take-profit. */
    record Sized(double quantity, double stopLoss, double takeProfit) implements Result {}

    /** Skip this entry. {@code reason} is for diagnostics only — already logged by the sizer. */
    record Halt(String reason) implements Result {}

    private final Config config;
    private final TradeDatabase database;
    private final ResilientBrokerClient client;
    private final RiskManager riskManager;
    private final MLEntryScorer mlEntryScorer;
    private final AdaptivePositionSizer adaptivePositionSizer;
    private final AdaptiveParameterManager adaptiveManager;
    private final AnomalyDetector anomalyDetector;
    private final RiskPredictor riskPredictor;
    private final SentimentAnalyzer sentimentAnalyzer;
    private final PortfolioManager portfolio;
    private final RiskGate riskGate;
    private final TradingProfile profile;

    /**
     * Supplies the max pairwise correlation of {@code symbol} against currently-held positions.
     * Stays owned by ProfileManager (via {@code getMaxCorrelation}) rather than being duplicated
     * here, because the same {@code CorrelationCalculator} instance also backs the separate
     * Tier 2.4 entry-cap gate elsewhere in ProfileManager — one source of truth for correlation.
     */
    private final ToDoubleFunction<String> maxCorrelationFn;

    PositionSizer(Config config, TradeDatabase database, ResilientBrokerClient client,
                  RiskManager riskManager, MLEntryScorer mlEntryScorer,
                  AdaptivePositionSizer adaptivePositionSizer, AdaptiveParameterManager adaptiveManager,
                  AnomalyDetector anomalyDetector, RiskPredictor riskPredictor,
                  SentimentAnalyzer sentimentAnalyzer, PortfolioManager portfolio, RiskGate riskGate,
                  TradingProfile profile, ToDoubleFunction<String> maxCorrelationFn) {
        this.config = config;
        this.database = database;
        this.client = client;
        this.riskManager = riskManager;
        this.mlEntryScorer = mlEntryScorer;
        this.adaptivePositionSizer = adaptivePositionSizer;
        this.adaptiveManager = adaptiveManager;
        this.anomalyDetector = anomalyDetector;
        this.riskPredictor = riskPredictor;
        this.sentimentAnalyzer = sentimentAnalyzer;
        this.portfolio = portfolio;
        this.riskGate = riskGate;
        this.profile = profile;
        this.maxCorrelationFn = maxCorrelationFn;
    }

    /**
     * Runs the full sizing pipeline for one BUY candidate.
     *
     * @param currentVix    VIX at evaluation time. Equal to {@code ProfileManager.latestVix} at
     *                      the call site — {@code handleBuy}'s caller sets that field from this
     *                      same value earlier in the same trading cycle, before any BUY is
     *                      evaluated, so the two never diverge within one cycle.
     * @param scalpOverrides non-null only for a ScalpBuy signal; carries its tight fixed SL/TP.
     */
    Result evaluate(String symbol, double currentPrice, double equity, double buyingPower,
                     double currentVix, MarketRegime regime, Double[] scalpOverrides,
                     String profilePrefix) {

        double availableCapital = equity;

        // ========== POSITION SIZING ==========
        // Regime-aware Kelly sizing: rolling win-rate stats conditioned on the current
        // market regime produce more accurate Kelly fractions than the lifetime average.
        double positionSize = riskManager.calculatePositionSize(
            symbol,
            availableCapital,
            currentPrice,
            currentVix,
            profile.stopLossPercent(),
            regime != null ? regime.name() : null
        );

        // ========== REGIME STREAK PENALTY ==========
        // After 3+ consecutive bearish days, reduce position size to limit drawdown
        // during sustained adverse conditions (e.g. a multi-day WEAK_BEAR regime).
        // Scale: day 3 → 75%, day 4 → 60%, day 5+ → 50%.
        if (regime != null) {
            boolean isBearishRegime = (regime == MarketRegime.WEAK_BEAR || regime == MarketRegime.STRONG_BEAR);
            if (isBearishRegime) {
                int bearDays = database.getRegimePersistenceDays(regime.name());
                if (bearDays >= 5) {
                    positionSize *= 0.50;
                    logger.info("{} {}: ⚠️ BEAR STREAK {} days — position halved to ${}", profilePrefix, symbol, bearDays, String.format("%.2f", positionSize));
                } else if (bearDays == 4) {
                    positionSize *= 0.60;
                    logger.info("{} {}: ⚠️ BEAR STREAK {} days — position at 60% ${}", profilePrefix, symbol, bearDays, String.format("%.2f", positionSize));
                } else if (bearDays == 3) {
                    positionSize *= 0.75;
                    logger.info("{} {}: ⚠️ BEAR STREAK {} days — position at 75% ${}", profilePrefix, symbol, bearDays, String.format("%.2f", positionSize));
                }
            }
        }

        // ========== PHASE 3: ADAPTIVE POSITION SIZING ==========
        if (config.isAdaptiveSizingEnabled()) {
            try {
                // Get ML score for sizing
                var bars = client.getBars(symbol, "15Min", 50);
                double mlScore = mlEntryScorer.scoreEntry(symbol, currentPrice, bars);

                // Calculate adaptive size based on ML confidence and VIX
                double adaptiveSize = adaptivePositionSizer.calculateSize(equity, mlScore, currentVix);

                // Check correlation with existing positions
                double maxCorrelation = maxCorrelationFn.applyAsDouble(symbol);
                adaptiveSize = adaptivePositionSizer.adjustForCorrelation(adaptiveSize, maxCorrelation);

                // Use adaptive size instead of basic size
                positionSize = Math.min(positionSize, adaptiveSize);

                logger.info("{} {}: 📊 PHASE 3 - Adaptive sizing: ${} (ML:{} VIX:{} Corr:{})",
                    profilePrefix, symbol, String.format("%.2f", positionSize), String.format("%.1f", mlScore), String.format("%.1f", currentVix), String.format("%.2f", maxCorrelation));

                // Broadcast adaptive sizing to UI
                TradingWebSocketHandler.broadcastPhase3Event(
                    "PHASE3_ADAPTIVE_SIZE",
                    String.format("{\"symbol\":\"%s\",\"size\":%.2f,\"mlScore\":%.1f,\"vix\":%.1f}",
                        symbol, positionSize, mlScore, currentVix)
                );

            } catch (Exception e) {
                logger.warn("{} {}: Adaptive sizing failed, using basic size: {}",
                    profilePrefix, symbol, e.getMessage());
            }
        }

        // Hard cap: never commit more cash than available buying power to avoid broker rejection
        if (positionSize > buyingPower * 0.95) {
            logger.debug("{} {}: Position size ${} capped to buying power ${}",
                profilePrefix, symbol, String.format("%.2f", positionSize), String.format("%.2f", buyingPower * 0.95));
            positionSize = buyingPower * 0.95;
        }

        logger.info("{} {}: 💰 Position sizing: Equity=${}, Calculated={} shares",
            profilePrefix, symbol,
            String.format("%.2f", availableCapital),
            String.format("%.3f", positionSize));

        // ========== AI COMPONENT 3: ANOMALY DETECTION ==========
        if (anomalyDetector != null) {
            try {
                // Check for anomalies
                anomalyDetector.isAnomaly("vix", currentVix);

                var action = anomalyDetector.getRecommendedAction();
                int severity = anomalyDetector.getAnomalySeverity();

                // Record anomaly for dashboard
                com.trading.ai.AIMetricsTracker.getInstance().recordAnomaly(severity, action.toString());

                switch (action) {
                    case HALT_TRADING:
                        logger.warn("{} {}: ⚠️ ANOMALY - Halting trading (severity: {})",
                            profilePrefix, symbol, severity);
                        com.trading.ai.AIMetricsTracker.getInstance().incrementTradesFiltered();
                        return new Halt("anomaly HALT_TRADING severity=" + severity);
                    case REDUCE_SIZE:
                        positionSize *= 0.5;
                        logger.info("{} {}: ⚠️ ANOMALY - Reducing position size 50% (severity: {})",
                            profilePrefix, symbol, severity);
                        break;
                    case TIGHTEN_STOPS:
                        logger.info("{} {}: ⚠️ ANOMALY - Consider tightening stops (severity: {})",
                            profilePrefix, symbol, severity);
                        break;
                    default:
                        // CONTINUE - no action needed
                        break;
                }
            } catch (Exception e) {
                logger.warn("{} {}: Anomaly detection failed, continuing: {}",
                    profilePrefix, symbol, e.getMessage());
            }
        }

        // ========== AI COMPONENT 4: RISK PREDICTION ==========
        if (riskPredictor != null) {
            try {
                var now = LocalDateTime.now();
                var riskSetup = new com.trading.ai.RiskPredictor.TradingSetup(
                    currentVix,
                    30.0, // symbol volatility (would need real data)
                    now.getHour(),
                    now.getDayOfWeek(),
                    portfolio.getActivePositionCount(),
                    config.getCorrelationCapMaxConcurrent(),
                    riskGate.consecutiveStopLosses().values().stream().mapToInt(Integer::intValue).sum(),
                    sentimentAnalyzer != null ? sentimentAnalyzer.getSentimentScore(symbol) : 0.0,
                    true // handleBuy is always a long (bullish) entry
                );

                int riskScore = riskPredictor.calculateRiskScore(riskSetup);

                // Record risk for dashboard
                com.trading.ai.AIMetricsTracker.getInstance().recordRisk(riskScore);

                if (riskPredictor.isTooRisky(riskSetup)) {
                    logger.info("{} {}: ❌ AI FILTER - Risk too high: {}/100, skipping",
                        profilePrefix, symbol, riskScore);
                    com.trading.ai.AIMetricsTracker.getInstance().incrementTradesFiltered();
                    return new Halt("risk score " + riskScore + "/100 too risky");
                }

                // Adjust position size based on risk
                double originalSize = positionSize;
                positionSize = riskPredictor.getRecommendedSize(positionSize, riskScore);

                if (positionSize < originalSize) {
                    logger.info("{} {}: ⚡ Risk-adjusted position: {} → {} shares (risk: {}/100)",
                        profilePrefix, symbol,
                        String.format("%.2f", originalSize),
                        String.format("%.2f", positionSize),
                        riskScore);
                }
            } catch (Exception e) {
                logger.warn("{} {}: Risk prediction failed, continuing: {}",
                    profilePrefix, symbol, e.getMessage());
            }
        }

        // VIX adjustment is already applied inside calculateVolatilityAdjustedSize (20/VIX factor).
        // A second 30% cut here was double-penalizing volatile markets and starving a $365 account
        // of capital. Removed to avoid triple-stacking: MICRO tier risk% + VIX formula + 0.7 here.

        logger.info("{} {}: 🎯 Final position sizing: {} shares",
            profilePrefix, symbol, positionSize);

        // ========== ATR-SCALED STOP / TAKE-PROFIT (Tier 1.2) ==========
        // Replaces flat % stops with ATR-derived stops so volatile names get wider stops
        // (don't get noise-stopped) and quiet names get tighter ones (don't bleed slowly).
        // Falls back to profile flat % if ATR can't be computed (insufficient bars).
        // Scalp entries bypass ATR — their tight fixed SL/TP are carried in scalpOverrides.
        double stopLoss;
        double takeProfit;
        double atr = 0.0;
        if (scalpOverrides != null) {
            // Scalp entry: use the tight SL/TP embedded in the ScalpBuy signal
            stopLoss  = currentPrice * (1.0 - scalpOverrides[0] / 100.0);
            takeProfit = currentPrice * (1.0 + scalpOverrides[1] / 100.0);
            logger.info("{} {} SCALP: SL=${} ({}%) TP=${} ({}%)",
                profilePrefix, symbol,
                String.format("%.2f", stopLoss),
                String.format("%.2f", scalpOverrides[0]),
                String.format("%.2f", takeProfit),
                String.format("%.2f", scalpOverrides[1]));
        } else {
            if (config.isAtrStopsEnabled()) {
                try {
                    int period = config.getAtrPeriodBars();
                    var atrBars = client.getBars(symbol, "1Day", period + 5);
                    atr = AtrCalculator.atr(atrBars, period);
                } catch (Exception e) {
                    logger.debug("{} ATR fetch failed for {}: {}", profilePrefix, symbol, e.getMessage());
                }
            }
            if (atr > 0.0) {
                stopLoss = RiskManager.calculateAtrStopLoss(
                    currentPrice, atr,
                    config.getAtrStopMultiplier(),
                    config.getAtrStopFloorPercent(),
                    config.getAtrStopCeilingPercent());
                double atrTp = RiskManager.calculateAtrTakeProfit(
                    currentPrice, atr,
                    config.getAtrTakeProfitMultiplier(),
                    config.getAtrStopFloorPercent());
                // Cap ATR TP at the profile's configured target — ATR widens for volatility
                // but should never produce a target harder to reach than the configured goal.
                double profileTp = currentPrice * (1.0 + config.getVixScaledTakeProfit(currentVix) / 100.0);
                takeProfit = Math.min(atrTp, profileTp);
                logger.info("{} {}: ATR={} (n={}) → stop=${} ({}%) tp=${} ({}%)",
                    profilePrefix, symbol,
                    String.format("%.4f", atr),
                    config.getAtrPeriodBars(),
                    String.format("%.2f", stopLoss),
                    String.format("%.2f", (currentPrice - stopLoss) / currentPrice * 100.0),
                    String.format("%.2f", takeProfit),
                    String.format("%.2f", (takeProfit - currentPrice) / currentPrice * 100.0));
            } else {
                stopLoss = currentPrice * (1.0 - config.getVixScaledStopLoss(currentVix) / 100.0);
                takeProfit = currentPrice * (1.0 + config.getVixScaledTakeProfit(currentVix) / 100.0);
            }

            // ========== RANGE_BOUND STOP CAP ==========
            // In choppy sideways markets, ATR can produce stops of -3% to -5%, letting positions
            // bleed for hours before stopping out (AAPL -5% ATR stop on Jul 14 2026 in VIX=11.5).
            // Cap at 2× profile SL in RANGE_BOUND so the max loss per trade stays predictable.
            // Example: MAIN_STOP_LOSS_PERCENT=1.0 → cap at 2%, vs ATR stop at 5% raw.
            if (regime == MarketRegime.RANGE_BOUND && atr > 0) {
                double maxStopPct = profile.stopLossPercent() * 2.0;
                double minStopPrice = currentPrice * (1.0 - maxStopPct / 100.0);
                if (stopLoss < minStopPrice) {
                    logger.info("{} {} RANGE_BOUND stop cap: ATR stop ${} ({}%) → capped at ${} ({}%)",
                        profilePrefix, symbol,
                        String.format("%.2f", stopLoss),
                        String.format("%.2f", (currentPrice - stopLoss) / currentPrice * 100.0),
                        String.format("%.2f", minStopPrice),
                        String.format("%.2f", maxStopPct));
                    stopLoss = minStopPrice;
                }
            }
        }

        // ========== ATR-BASED VOL-TARGETED SIZING (Tier 1.3) ==========
        // Override the earlier sizing if ATR-based stop is available: ensures total $-risk is
        // capped at risk_per_trade × equity regardless of per-name volatility.
        if (config.isAtrSizingEnabled() && atr > 0.0 && stopLoss > 0.0 && stopLoss < currentPrice) {
            try {
                AdvancedPositionSizer atrSizer = new AdvancedPositionSizer(config, database);
                atrSizer.setAdaptiveManager(adaptiveManager);
                double volTargetedSize = atrSizer.calculateAtrPositionSize(
                    symbol, availableCapital, currentPrice, stopLoss);
                if (volTargetedSize > 0) {
                    double prev = positionSize;
                    positionSize = Math.min(positionSize, volTargetedSize);
                    logger.info("{} {}: ATR vol-targeted sizing: {} → {} shares (risk={}%)",
                        profilePrefix, symbol,
                        String.format("%.3f", prev),
                        String.format("%.3f", positionSize),
                        String.format("%.2f", config.getAtrSizingRiskPercent() * 100.0));
                }
            } catch (Exception e) {
                logger.debug("{} ATR sizing failed for {}: {}", profilePrefix, symbol, e.getMessage());
            }
        }

        // ========== HARD NOTIONAL CAP (belt-and-suspenders) ==========
        // Belt-and-suspenders: every sizing path above should already respect the tier max,
        // but this catches any edge case where Kelly / adaptive / ATR sizing computes a value
        // larger than the account tier allows (the April QQQ/TLT $19K-on-$1.2K incident).
        double tierMaxNotional = equity * com.trading.risk.CapitalTierManager.getParameters(equity).maxPositionPercent();
        double orderNotional = positionSize * currentPrice;
        if (orderNotional > tierMaxNotional && tierMaxNotional > 0) {
            double capped = tierMaxNotional / currentPrice;
            logger.warn("{} {}: ⚠️ Hard cap fired — sizing {} → {} shares (${} → ${})",
                profilePrefix, symbol,
                String.format("%.3f", positionSize), String.format("%.3f", capped),
                String.format("%.2f", orderNotional), String.format("%.2f", tierMaxNotional));
            positionSize = capped;
        }

        // ========== AFTERNOON POSITION SIZING (Fix 6) ==========
        // After 13:30 ET, liquidity drops, bid-ask spreads widen, and afternoon reversals are
        // more common. Reduce position size to limit afternoon exposure while still allowing
        // entries for scalp signals (which have their own 14:00–15:00 window).
        // Factor = afternoonPct / normalPct — scales all sizing paths proportionally.
        var etNow = java.time.ZonedDateTime.now(java.time.ZoneId.of("America/New_York"));
        if (etNow.getHour() > 13 || (etNow.getHour() == 13 && etNow.getMinute() >= 30)) {
            double afternoonPct = config.getAfternoonPositionSizingPercent(); // default 0.12
            double normalPct    = config.getPositionSizingFixedPercent();     // default 0.20
            if (normalPct > 0 && afternoonPct < normalPct) {
                double afternoonFactor = afternoonPct / normalPct;
                double prevSize = positionSize;
                positionSize = positionSize * afternoonFactor;
                logger.info("{} {}: ⏰ Afternoon sizing (13:30+ ET): {} → {} shares ({}% → {}% sizing)",
                    profilePrefix, symbol,
                    String.format("%.3f", prevSize),
                    String.format("%.3f", positionSize),
                    String.format("%.0f", normalPct * 100),
                    String.format("%.0f", afternoonPct * 100));
            }
        }

        return new Sized(positionSize, stopLoss, takeProfit);
    }
}
