package com.trading.portfolio;

import com.trading.ai.SentimentAnalyzer;
import com.trading.ai.SignalPredictor;
import com.trading.analysis.CorrelationCalculator;
import com.trading.analysis.MarketBreadthAnalyzer;
import com.trading.analysis.MarketRegimeDetector.MarketRegime;
import com.trading.analysis.VolumeProfileAnalyzer;
import com.trading.api.ResilientBrokerClient;
import com.trading.config.Config;
import com.trading.filters.MarketHoursFilter;
import com.trading.persistence.TradeDatabase;
import com.trading.protection.PDTProtection;
import com.trading.scoring.MLEntryScorer;
import com.trading.strategy.TradingProfile;
import com.trading.websocket.TradingWebSocketHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.function.DoubleSupplier;
import java.util.function.Supplier;

/**
 * Evaluates every pre-sizing gate a candidate BUY must pass before {@link PositionSizer} even
 * runs: cross-profile exclusion, entry stagger, daily loss/profit circuit breakers, PDT
 * reservation, cooldowns, time-of-day windows, regime/VIX guards, earnings blackout, price
 * improvement, gap-down/intraday-downtrend/falling-knife filters, position limits, correlation
 * cap, pending-order dedup, and the AI sentiment/breadth/win-rate/ML-score/volume-profile/
 * ML-prediction filters.
 *
 * <p>Extracted 2026-08-30 from {@code ProfileManager.handleBuy()} as the fourth and final step
 * of the ProfileManager simplification (after RiskGate, PositionSizer, ExitEvaluator — see their
 * class Javadocs for the earlier steps). Like ExitEvaluator, this is a same-shape relocation:
 * every gate already had the form "check a condition, and on failure log + {@code return;}" — the
 * only change is that each {@code return;} became {@code return new Blocked(reason);} so the
 * caller (still {@code handleBuy}) can branch on it exactly where the old inline code returned.
 * No gate's condition, order, or logging changed.
 *
 * <p><b>What did NOT move:</b> the double-entry race guard (claims/releases
 * {@code riskGate.pendingBuySymbols()} across the whole of {@code handleBuy}, including order
 * placement) stays on ProfileManager — it's a lifecycle concern for the whole method, not a
 * single gate. {@code blockBuy()} and {@code hasSignificantlyLosingPosition()} moved here since
 * every caller was a gate below; {@code pendingEntryTimestamps}/{@code STALE_ENTRY_ORDER_MS}
 * likewise, since the pending-order gate was their only reader.
 */
final class EntryEvaluator {

    private static final Logger logger = LoggerFactory.getLogger(EntryEvaluator.class);

    /** Outcome of the full gate chain: either proceed to PositionSizer, or skip this entry. */
    sealed interface Result permits Pass, Blocked {}

    /** All gates passed — handleBuy should proceed to PositionSizer.evaluate(). */
    record Pass() implements Result {}

    /** A gate failed. {@code reason} is for diagnostics only — already logged by the gate. */
    record Blocked(String reason) implements Result {}

    private final TradingProfile profile;
    private final Config config;
    private final TradeDatabase database;
    private final ResilientBrokerClient client;
    private final PortfolioManager portfolio;
    private final RiskGate riskGate;
    private final PDTProtection pdtProtection;
    private final MarketHoursFilter marketHoursFilter;
    private final CorrelationCalculator correlationCalculator;
    private final SentimentAnalyzer sentimentAnalyzer;
    private final SignalPredictor signalPredictor;
    private final MarketBreadthAnalyzer marketBreadthAnalyzer;
    private final MLEntryScorer mlEntryScorer;
    private final VolumeProfileAnalyzer volumeProfileAnalyzer;

    /** Read-only here — owned by ProfileManager because the daily-loss/profit entry gates that stayed there read it directly too. */
    private final DoubleSupplier todayPnL;
    /** Written by ProfileManager's regime-tracking in runTradingCycle(); read-only here for the inverse-ETF persistence gate. */
    private final Supplier<Instant> bearishRegimeMarketStart;

    // Per-symbol: first-seen timestamp of a pending entry order, used to detect and cancel stale
    // orders (e.g. sandbox orders that never fill). Only the pending-order gate below reads/writes
    // this, so — unlike todayPnL/bearishRegimeMarketStart — it's owned outright here.
    private final java.util.concurrent.ConcurrentHashMap<String, Long> pendingEntryTimestamps
        = new java.util.concurrent.ConcurrentHashMap<>();
    private static final long STALE_ENTRY_ORDER_MS = 30 * 60 * 1000L; // 30 minutes

    EntryEvaluator(TradingProfile profile, Config config, TradeDatabase database,
                   ResilientBrokerClient client, PortfolioManager portfolio, RiskGate riskGate,
                   PDTProtection pdtProtection, MarketHoursFilter marketHoursFilter,
                   CorrelationCalculator correlationCalculator, SentimentAnalyzer sentimentAnalyzer,
                   SignalPredictor signalPredictor, MarketBreadthAnalyzer marketBreadthAnalyzer,
                   MLEntryScorer mlEntryScorer, VolumeProfileAnalyzer volumeProfileAnalyzer,
                   DoubleSupplier todayPnL, Supplier<Instant> bearishRegimeMarketStart) {
        this.profile = profile;
        this.config = config;
        this.database = database;
        this.client = client;
        this.portfolio = portfolio;
        this.riskGate = riskGate;
        this.pdtProtection = pdtProtection;
        this.marketHoursFilter = marketHoursFilter;
        this.correlationCalculator = correlationCalculator;
        this.sentimentAnalyzer = sentimentAnalyzer;
        this.signalPredictor = signalPredictor;
        this.marketBreadthAnalyzer = marketBreadthAnalyzer;
        this.mlEntryScorer = mlEntryScorer;
        this.volumeProfileAnalyzer = volumeProfileAnalyzer;
        this.todayPnL = todayPnL;
        this.bearishRegimeMarketStart = bearishRegimeMarketStart;
    }

    /**
     * Runs the full pre-sizing gate chain for one BUY candidate. Assumes the caller already holds
     * the {@code pendingBuySymbols} claim for {@code symbol} (ProfileManager's race guard).
     *
     * @param scalpOverrides non-null only for a ScalpBuy signal; several gates are exempt for scalp
     *                       entries since ScalpStrategy already applies its own tighter time/price filters.
     */
    Result evaluate(String symbol, double currentPrice, double equity, double currentVix,
                     MarketRegime regime, Double[] scalpOverrides, String profilePrefix) {

        // ========== CROSS-PROFILE POSITION EXCLUSION ==========
        // If another profile (MAIN vs EXPERIMENTAL) already holds this symbol, skip.
        // Allowing both profiles to hold the same declining symbol doubles concentration risk.
        // Root cause of XLP×2 and XLV×2 losses on July 7, 2026.
        String currentOwner = riskGate.globalHeldSymbols().get(symbol);
        if (currentOwner != null && !currentOwner.equals(profilePrefix)) {
            logger.info("{} {} BUY skipped — already held by {} (cross-profile exclusion)",
                profilePrefix, symbol, currentOwner);
            return new Blocked("already held by " + currentOwner + " (cross-profile exclusion)");
        }

        // ========== ENTRY STAGGER (90-second minimum spacing) ==========
        // Prevents back-to-back entries on correlated symbols that signal simultaneously.
        // When SPY and QQQ both return BUY on the same 20-second cycle, both would be entered
        // within seconds of each other — doubling exposure to the same directional move.
        // The 90-second gate lets the first position breathe before the second can open.
        long nowMs = System.currentTimeMillis();
        if (nowMs - riskGate.lastEntryEpochMs() < RiskGate.MIN_ENTRY_SPACING_MS) {
            long secsLeft = (RiskGate.MIN_ENTRY_SPACING_MS - (nowMs - riskGate.lastEntryEpochMs())) / 1000;
            logger.info("{} {} BUY SKIPPED — entry stagger: {}s until next entry allowed",
                profilePrefix, symbol, secsLeft);
            return new Blocked("entry stagger: " + secsLeft + "s remaining");
        }

        // ========== DAILY LOSS CIRCUIT BREAKER ==========
        if (config.isDailyMaxLossEnabled()) {
            double maxLoss = -Math.abs(equity * config.getDailyMaxLossPercent() / 100.0);
            double todayPnLValue = todayPnL.getAsDouble();
            if (todayPnLValue < maxLoss) {
                logger.warn("{} {} BUY BLOCKED — daily loss limit hit (today=${}, limit={}%)",
                    profilePrefix, symbol,
                    String.format("%.2f", todayPnLValue),
                    String.format("%.1f", config.getDailyMaxLossPercent()));
                TradingWebSocketHandler.broadcastActivity(
                    String.format("[%s] 🛑 DAILY LOSS LIMIT: no new entries (today=$%.2f)",
                        profile.name(), todayPnLValue),
                    "WARN");
                return new Blocked("daily loss limit hit");
            }
        }

        // ========== LOSING POSITION GATE (Fix 5) ==========
        // If any existing position is already down >0.20%, block new entries until it recovers
        // or is closed. Adding capital while a position is losing compounds directional risk —
        // both trades would suffer together if the move against us continues.
        if (hasSignificantlyLosingPosition(profilePrefix, symbol)) {
            TradingWebSocketHandler.broadcastActivity(
                String.format("[%s] ⛔ %s blocked — an existing position is in significant loss",
                    profile.name(), symbol),
                "WARN");
            return new Blocked("existing position is in significant loss");
        }

        // ========== DAILY PROFIT TARGET HALT ==========
        // Stop new entries once the day's P&L has hit the daily profit target.
        // Exits (stop-loss, take-profit) still run normally — only new buys are blocked.
        // Prevents giving back a strong day chasing marginal late-session signals.
        if (config.isDailyProfitTargetEnabled() && todayPnL.getAsDouble() >= config.getDailyProfitTarget()) {
            logger.info("{} {} BUY BLOCKED — daily profit target reached (today=+${}, target=${})",
                profilePrefix, symbol,
                String.format("%.2f", todayPnL.getAsDouble()),
                String.format("%.2f", config.getDailyProfitTarget()));
            return new Blocked("daily profit target reached");
        }

        // ========== PDT RESERVATION CHECK ==========
        // Reserve the last PDT day-trade slot for protective exits (stop-loss/take-profit).
        // A new buy requires a same-day sell capability — if we're at 2/3 trades, a new
        // buy could use the last slot, then a stop-loss exit gets PDT-blocked.
        // Solution: block all new buys when daytrade_count >= 2.
        int currentDayTrades = pdtProtection.getDayTradeCount();
        riskGate.setStaticDayTradeCount(currentDayTrades); // sync for dashboard
        // Reserve 1 PDT slot for exits (worst case: 1 position needs same-day stop-loss exit).
        // Block new buys only when 2 of 3 day trades are already used.
        // pdtReserveThreshold=2: allows buys at 0/3 and 1/3, blocks at 2/3 to keep 1 exit slot.
        // Old threshold=1 was too aggressive: it blocked ALL trading after the first day trade.
        int pdtReserveThreshold = config.getPdtReserveThreshold(); // default 2
        if (currentDayTrades >= pdtReserveThreshold && equity < 25000) {
            logger.warn("{} {} BUY BLOCKED — PDT reservation: {}/{} day trades used, keeping slots for exits",
                profilePrefix, symbol, currentDayTrades, 3);
            TradingWebSocketHandler.broadcastActivity(
                String.format("[%s] ⛔ BUY BLOCKED: %s — PDT slots reserved for exits (%d/3 used)",
                    profile.name(), symbol, currentDayTrades),
                "WARN"
            );
            return new Blocked("PDT reservation: " + currentDayTrades + "/3 day trades used");
        }

        // ========== STOP LOSS COOLDOWN CHECK ==========
        // Prevent immediate re-entry after stop loss (this was causing repeated losses)
        Long cooldownExpiry = riskGate.stopLossCooldowns().get(symbol);
        if (cooldownExpiry != null && System.currentTimeMillis() < cooldownExpiry) {
            long remainingMin = (cooldownExpiry - System.currentTimeMillis()) / 60000;
            logger.info("{} {} on STOP LOSS COOLDOWN - {} more minutes before re-entry allowed",
                profilePrefix, symbol, remainingMin);
            TradingWebSocketHandler.broadcastActivity(
                String.format("[%s] ⏳ %s cooldown: %d min remaining after stop loss",
                    profile.name(), symbol, remainingMin),
                "INFO"
            );
            return new Blocked("stop loss cooldown: " + remainingMin + " min remaining");
        }

        // ========== PER-SYMBOL POST-LOSS COOLDOWN (Tier 1.1) ==========
        // Distinct from the legacy minute-scale cooldown above: this is a 24h–72h block
        // applied after losses on the *same* symbol, escalating after consecutive losses.
        // Aimed at the TLT-loses-4x pattern. Other symbols keep trading.
        if (config.isPerSymbolCooldownEnabled() && riskGate.postLossCooldown() != null) {
            long now = System.currentTimeMillis();
            if (riskGate.postLossCooldown().isInCooldown(symbol, now)) {
                long remHours = riskGate.postLossCooldown().remainingMs(symbol, now) / (60L * 60 * 1000);
                int losses = riskGate.postLossCooldown().getConsecutiveLosses(symbol);
                String reason = String.format("post-loss cooldown: %dh remaining (%d consec losses)", remHours, losses);
                blockBuy(symbol, reason, currentPrice, regime, currentVix);
                TradingWebSocketHandler.broadcastActivity(
                    String.format("[%s] ⛔ %s post-loss cooldown: %dh left (%d consec losses)",
                        profile.name(), symbol, remHours, losses),
                    "WARN");
                return new Blocked(reason);
            }
        }

        // ========== NO-TRADE OPEN WINDOW (Tier 3.9) ==========
        // Block fresh entries during the noisy first N minutes of the regular session.
        // Exception: ScalpBuy signals are generated by ScalpStrategy which has its OWN time window
        // (9:45–11:30 AM) with VWAP, RSI-cross, and volume filters — those already handle the
        // opening noise better than a blanket time block. Blocking scalp with a 45-min window
        // loses its entire 9:45–10:15 morning window which is often the best momentum period.
        if (config.isNoTradeOpenWindowEnabled()
                && !(scalpOverrides != null)  // scalpOverrides non-null only during ScalpBuy execution
                && marketHoursFilter.isInOpeningWindow(config.getNoTradeOpenWindowMinutes())) {
            String reason = "opening-window block: first " + config.getNoTradeOpenWindowMinutes() + "min";
            blockBuy(symbol, reason, currentPrice, regime, currentVix);
            TradingWebSocketHandler.broadcastActivity(
                String.format("[%s] ⛔ %s blocked: first %d min after open",
                    profile.name(), symbol, config.getNoTradeOpenWindowMinutes()),
                "INFO");
            return new Blocked(reason);
        }

        // ========== MAIN STRATEGY EOD CUTOFF ==========
        // Main strategy targets 1.5% TP and needs 3-4 hours to mature in a WEAK_BULL grind.
        // Entries within 2 hours of EOD cannot hit 1.5% before forced exit — they produce
        // flat or small losses. Scalp is exempt: 0.7% TP is achievable in minutes and its
        // window (14:00–15:00) falls inside this 2-hour block.
        // Root: Aug 3 2026, bug cascade delayed entries to 14:10 — MSFT/XOP closed at -0.02%/-0.46%.
        if (scalpOverrides == null && config.isEodExitEnabled()) {
            try {
                var eodTime = java.time.LocalTime.parse(config.getEodExitTime());
                var mainCutoff = eodTime.minusMinutes(config.getMainEodEntryCutoffMinutes());
                var nowET = java.time.ZonedDateTime.now(java.time.ZoneId.of("America/New_York")).toLocalTime();
                if (!nowET.isBefore(mainCutoff)) {
                    logger.info("{} {}: BUY BLOCKED — within {}min of EOD (main strategy needs 3+ hrs for 1.5% TP)",
                        profilePrefix, symbol, config.getMainEodEntryCutoffMinutes());
                    return new Blocked("within " + config.getMainEodEntryCutoffMinutes() + "min of EOD");
                }
            } catch (Exception e) {
                logger.warn("{} {}: Could not parse EOD time for main-entry cutoff: {}", profilePrefix, symbol, e.getMessage());
            }
        }

        // ========== VIX MINIMUM ENTRY GATE ==========
        // Only block at extreme complacency (VIX < 10.0). VIX-scaled TP/SL already adjusts
        // targets to match the prevailing range — the gate exists only as an absolute safety floor.
        if (scalpOverrides == null && config.isVixEntryGateEnabled()
                && currentVix > 0 && currentVix < config.getVixEntryMinimum()) {
            logger.info("{} {}: BUY BLOCKED — VIX {} below minimum {} (TP {}% unreachable in current range)",
                profilePrefix, symbol,
                String.format("%.1f", currentVix), String.format("%.1f", config.getVixEntryMinimum()),
                String.format("%.2f", config.getVixScaledTakeProfit(currentVix)));
            return new Blocked("VIX " + currentVix + " below minimum " + config.getVixEntryMinimum());
        }

        // ========== LUNCH BLACKOUT / POWER HOURS GATE ==========
        // Core dead zone (configurable, currently 12:00-13:30 ET) blocks new swing entries.
        // Scalp is exempt — its own time window already handles this via ScalpStrategy.
        if (scalpOverrides == null && config.isLunchBlackoutEnabled()) {
            try {
                var nowET = java.time.ZonedDateTime.now(java.time.ZoneId.of("America/New_York")).toLocalTime();
                var lunchStart = java.time.LocalTime.parse(config.getLunchBlackoutStart());
                var lunchEnd = java.time.LocalTime.parse(config.getLunchBlackoutEnd());
                if (!nowET.isBefore(lunchStart) && nowET.isBefore(lunchEnd)) {
                    logger.info("{} {}: BUY BLOCKED — lunch blackout ({}-{} ET, low-movement period)",
                        profilePrefix, symbol, lunchStart, lunchEnd);
                    return new Blocked("lunch blackout " + lunchStart + "-" + lunchEnd + " ET");
                }
            } catch (Exception e) {
                logger.debug("{} Lunch blackout parse error: {}", profilePrefix, e.getMessage());
            }
        }

        // ========== INVERSE ETF / BEARISH ENTRY GUARD (Tier 3.11) ==========
        // Buying inverse ETFs (SQQQ, SH, PSQ, RWM, DOG) is only valid when market
        // conditions genuinely confirm a bear market.  Two gates:
        //
        //   1. VIX gate: real bear markets have elevated VIX (≥ BEAR_ENTRY_VIX_MINIMUM,
        //      default 20).  Low VIX + downtrend = sector rotation / consolidation, not a
        //      confirmed crash.  The regime can briefly call STRONG_BEAR on stale overnight
        //      daily-bar breadth data even when VIX is calm — this gate catches that.
        //
        //   2. Persistence gate: the bearish regime must have been active during market
        //      hours for at least BEAR_ENTRY_PERSISTENCE_MINUTES (default 30 min) before
        //      the first inverse ETF entry is allowed.  This prevents the bot from acting
        //      on a regime flip that appeared at market open from overnight stale data and
        //      then immediately reverses once fresh intraday bars arrive.
        if (profile.bearishSymbols().contains(symbol)
                && (regime == MarketRegime.STRONG_BEAR || regime == MarketRegime.WEAK_BEAR)) {
            // Gate 1: VIX must be elevated — applies to STRONG_BEAR only.
            // STRONG_BEAR can fire from stale overnight breadth data even when VIX is calm.
            // Requiring VIX ≥ BEAR_ENTRY_VIX_MINIMUM guards against false inverse ETF entries.
            // WEAK_BEAR with low VIX is a genuine mild decline (breadth < 50%, trend WEAK_DOWN)
            // — no panic VIX required to profit from it with inverse ETFs (Jul 21 2026).
            if (regime == MarketRegime.STRONG_BEAR) {
                double bearVixMin = config.getBearEntryVixMinimum();
                if (currentVix < bearVixMin) {
                    String reason = String.format(
                        "inverse ETF blocked — VIX %.1f < BEAR_ENTRY_VIX_MINIMUM %.1f (STRONG_BEAR requires panic VIX)",
                        currentVix, bearVixMin);
                    blockBuy(symbol, reason, currentPrice, regime, currentVix);
                    TradingWebSocketHandler.broadcastActivity(
                        String.format("[%s] ⛔ %s blocked: VIX %.1f too low for STRONG_BEAR entry (min %.0f)",
                            profile.name(), symbol, currentVix, bearVixMin),
                        "WARN");
                    return new Blocked(reason);
                }
            }
            // Gate 2: regime persistence during market hours (applies to both STRONG_BEAR and WEAK_BEAR)
            long persistenceMs = config.getBearEntryPersistenceMinutes() * 60_000L;
            Instant regimeStart = bearishRegimeMarketStart.get();
            long elapsedMs = regimeStart != null
                ? java.time.Duration.between(regimeStart, Instant.now()).toMillis()
                : 0L;
            if (elapsedMs < persistenceMs) {
                long remainingMin = (persistenceMs - elapsedMs) / 60_000L + 1;
                String reason = String.format(
                    "inverse ETF blocked — bearish regime persistence %dmin < required %dmin (%d min remaining)",
                    elapsedMs / 60_000L, config.getBearEntryPersistenceMinutes(), remainingMin);
                blockBuy(symbol, reason, currentPrice, regime, currentVix);
                TradingWebSocketHandler.broadcastActivity(
                    String.format("[%s] ⛔ %s blocked: bearish regime not yet confirmed (%d min remaining)",
                        profile.name(), symbol, remainingMin),
                    "INFO");
                return new Blocked(reason);
            }
        }

        // ========== EARNINGS BLACKOUT (Tier 2.5) ==========
        // Avoid entering positions within ±N hours of an earnings announcement.
        // Earnings days are gap-risk events and our backtests show negative EV around them.
        if (config.isEarningsBlackoutEnabled() && riskGate.earningsCalendar() != null) {
            try {
                boolean inBlackout = riskGate.earningsCalendar().isInBlackout(
                    symbol,
                    Instant.now(),
                    config.getEarningsBlackoutHoursBefore(),
                    config.getEarningsBlackoutHoursAfter());
                if (inBlackout) {
                    String reason = String.format("earnings blackout: ±%d/±%dh window",
                        config.getEarningsBlackoutHoursBefore(),
                        config.getEarningsBlackoutHoursAfter());
                    blockBuy(symbol, reason, currentPrice, regime, currentVix);
                    TradingWebSocketHandler.broadcastActivity(
                        String.format("[%s] ⛔ %s earnings blackout active", profile.name(), symbol),
                        "WARN");
                    return new Blocked(reason);
                }
            } catch (Exception e) {
                logger.debug("{} Earnings check failed for {}: {}", profilePrefix, symbol, e.getMessage());
            }
        }

        // ========== PRICE IMPROVEMENT CHECK ==========
        // After a loss exit, only re-enter if price has dropped at least 1% from exit price.
        // Prevents buying back at the same price you just sold at a loss.
        // NOT applied when current price is ABOVE exit price: the stock recovered after the stop —
        // that is a valid re-entry (AMD stopped at $486, recovered to $492 with 90% MTF BUY =
        // post-earnings bounce continuing upward, not re-entering into the same weakness).
        Double lastExit = riskGate.lastExitPrices().get(symbol);
        if (lastExit != null) {
            double improvementPercent = ((lastExit - currentPrice) / lastExit) * 100.0;
            boolean priceAboveExit = currentPrice > lastExit;
            if (priceAboveExit) {
                // Stock recovered above exit — clear the gate and allow re-entry
                logger.info("{} {} price ${} above last exit ${} — stop was at a low, allowing re-entry",
                    profilePrefix, symbol, String.format("%.2f", currentPrice), String.format("%.2f", lastExit));
                riskGate.lastExitPrices().remove(symbol);
            } else if (improvementPercent < RiskGate.MIN_PRICE_IMPROVEMENT_PERCENT) {
                riskGate.blockedBuys().put(symbol, String.format("waiting for price: need %.1f%% below $%.2f exit", RiskGate.MIN_PRICE_IMPROVEMENT_PERCENT, lastExit));
                logger.info("{} {} PRICE IMPROVEMENT CHECK FAILED - last exit=${}, now=${}, need {}% drop but only {}%",
                    profilePrefix, symbol, String.format("%.2f", lastExit), String.format("%.2f", currentPrice),
                    String.format("%.1f", RiskGate.MIN_PRICE_IMPROVEMENT_PERCENT), String.format("%.2f", improvementPercent));
                TradingWebSocketHandler.broadcastActivity(
                    String.format("[%s] ⏳ %s waiting for better price: need %.1f%% below $%.2f exit",
                        profile.name(), symbol, RiskGate.MIN_PRICE_IMPROVEMENT_PERCENT, lastExit),
                    "INFO"
                );
                return new Blocked("waiting for price improvement below last exit $" + lastExit);
            } else {
                // Price has dropped enough below exit — clear gate and allow entry
                logger.info("{} {} PRICE IMPROVED {}% below last exit ${} — allowing re-entry",
                    profilePrefix, symbol, String.format("%.2f", improvementPercent), String.format("%.2f", lastExit));
                riskGate.blockedBuys().remove(symbol);
                riskGate.lastExitPrices().remove(symbol);
            }
        }

        // ========== GAP-DOWN PROTECTION ==========
        // Prevent buying into a stock that is drifting lower from yesterday's close.
        // Applies to gaps between 1-5%: these signal stale BUY data from daily bars + active selling.
        // Gaps >5% are earnings/news resets to a new equilibrium — MTF signal already accounts for
        // post-gap price action, so let it through (AMD -7.3% post-earnings Aug 5 2026 blocked at
        // 1% threshold even with 92% MTF BUY — was a valid post-earnings recovery entry).
        // Stop-loss is anchored to entry price (not yesterday's close), so large gaps don't shrink
        // the stop margin — the gap-down block only makes sense for slow intraday drifts.
        try {
            var recentBars = client.getMarketHistory(symbol, 2);
            if (recentBars.size() >= 2) {
                // Use the MOST RECENT completed bar (last in list = yesterday's close).
                // Bug was: get(0) = older bar (e.g. Thursday), not yesterday (Friday).
                // If stock rallied Friday then gaps down Monday, old code missed the gap.
                double prevClose = recentBars.get(recentBars.size() - 1).close();
                double gapDownPct = (prevClose - currentPrice) / prevClose * 100.0;
                double gapDownThreshold = profile.stopLossPercent();
                // Only block moderate gaps (1-5%) — earnings/news gaps >5% are handled by MTF
                boolean isEarningsGap = gapDownPct > 5.0;
                if (gapDownPct >= gapDownThreshold && !isEarningsGap) {
                    String reason = String.format("gap-down %.1f%% from $%.2f", gapDownPct, prevClose);
                    blockBuy(symbol, reason, currentPrice, regime, currentVix);
                    TradingWebSocketHandler.broadcastActivity(
                        String.format("[%s] ⛔ BUY BLOCKED: %s gap-down %.1f%% from $%.2f",
                            profile.name(), symbol, gapDownPct, prevClose),
                        "WARN"
                    );
                    return new Blocked(reason);
                } else {
                    if (isEarningsGap && gapDownPct >= gapDownThreshold) {
                        logger.info("{} {} gap-down {}% — earnings/news gap, allowing MTF to decide",
                            profilePrefix, symbol, String.format("%.1f", gapDownPct));
                    }
                    riskGate.blockedBuys().remove(symbol); // gap resolved — clear block
                }
            }
        } catch (Exception e) {
            logger.debug("{} Could not check gap-down for {}: {}", profilePrefix, symbol, e.getMessage());
        }

        // ========== INTRADAY TREND CHECK ==========
        // Strategy BUY signals are based on yesterday's daily closes. If the stock is
        // actively falling in the current session, the signal is stale and entry is risky.
        // Check: last completed hourly bar must not show a decline of ≥0.3%, AND
        //        current price must not be more than 0.2% below the previous hour close.
        try {
            var NY = java.time.ZoneId.of("America/New_York");
            var sessionStart = java.time.LocalDate.now(NY).atTime(9, 30).atZone(NY).toInstant();
            var intradayBars = client.getBars(symbol, "1Hour", 8).stream()
                .filter(b -> !b.timestamp().isBefore(sessionStart))
                .toList();
            if (intradayBars.size() >= 2) {
                double lastHourClose = intradayBars.get(intradayBars.size() - 1).close();
                double prevHourClose = intradayBars.get(intradayBars.size() - 2).close();
                double hourlyDeclinePct = (prevHourClose - lastHourClose) / prevHourClose * 100.0;
                double currentVsPrevHour = (prevHourClose - currentPrice) / prevHourClose * 100.0;
                // Block only if BOTH last hour was red AND current price is still below it
                if (hourlyDeclinePct >= 0.3 && currentVsPrevHour >= 0.2) {
                    String reason = String.format("intraday downtrend: last hour -%.1f%%, now -%.1f%% from prev hour",
                        hourlyDeclinePct, currentVsPrevHour);
                    blockBuy(symbol, reason, currentPrice, regime, currentVix);
                    TradingWebSocketHandler.broadcastActivity(
                        String.format("[%s] ⛔ BUY BLOCKED: %s %s", profile.name(), symbol, reason),
                        "WARN"
                    );
                    return new Blocked(reason);
                }

                // Falling-knife filter: catches "dead cat bounce" entries where a 1-hour bounce
                // fires a MACD signal but the stock is still deep in a session downtrend.
                // NVDA example (Aug 10 2026): high=$222, re-entered at $218 (-1.8%) after 3h cooldown.
                // Exempt scalp — ScalpStrategy already requires price > VWAP as its own intraday filter.
                if (scalpOverrides == null) {
                    double sessionHigh = intradayBars.stream().mapToDouble(b -> b.high()).max().orElse(0.0);
                    double sessionOpen = intradayBars.get(0).open();
                    if (sessionHigh > 0 && sessionOpen > 0) {
                        double pctBelowHigh = (sessionHigh - currentPrice) / sessionHigh * 100.0;
                        double pctVsOpen    = (currentPrice - sessionOpen) / sessionOpen * 100.0;
                        if (pctBelowHigh >= 1.5 && pctVsOpen < -0.5) {
                            String reason = String.format(
                                "falling knife: $%.2f is %.1f%% below session high $%.2f and %.1f%% below open $%.2f",
                                currentPrice, pctBelowHigh, sessionHigh, -pctVsOpen, sessionOpen);
                            blockBuy(symbol, reason, currentPrice, regime, currentVix);
                            TradingWebSocketHandler.broadcastActivity(
                                String.format("[%s] ⛔ BUY BLOCKED: %s %s", profile.name(), symbol, reason),
                                "WARN");
                            return new Blocked(reason);
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.debug("{} Could not check intraday trend for {}: {}", profilePrefix, symbol, e.getMessage());
        }

        // ========== POSITION LIMIT CHECK ==========
        // Check position limit BEFORE calculating position size or running AI
        if (portfolio.getActivePositionCount() >= config.getMaxPositionsAtOnce()) {
            logger.warn("{} ⚠️ Max positions reached ({}/{}), skipping new entry for {}",
                profilePrefix,
                portfolio.getActivePositionCount(),
                config.getMaxPositionsAtOnce(),
                symbol);

            TradingWebSocketHandler.broadcastActivity(
                String.format("[%s] ⚠️ SKIPPED: %s (max %d positions reached)",
                    profile.name(), symbol, config.getMaxPositionsAtOnce()),
                "WARN"
            );
            return new Blocked("max positions reached");
        }

        // ========== CORRELATION / CONCENTRATION CAP (Tier 2.4) ==========
        // Block entry if we'd exceed N concurrent positions whose pairwise correlation
        // is above {threshold}. A loss event in correlated names compounds (SPY+QQQ+VTI
        // all dump together), so cap concentration rather than relying on diversification.
        if (config.isCorrelationCapEnabled() && portfolio.getActivePositionCount() > 0) {
            try {
                java.util.List<String> openSymbols = new java.util.ArrayList<>(portfolio.getActiveStoredSymbols());
                if (!openSymbols.contains(symbol)) {
                    java.util.List<String> probe = new java.util.ArrayList<>(openSymbols);
                    probe.add(symbol);
                    var analysis = correlationCalculator.analyzePortfolio(probe);
                    double thr = config.getCorrelationCapThreshold();
                    int maxConc = config.getCorrelationCapMaxConcurrent();
                    int relatedHits = 0;
                    for (var pair : analysis.highCorrelations()) {
                        if ((pair.symbol1().equalsIgnoreCase(symbol) || pair.symbol2().equalsIgnoreCase(symbol))
                                && Math.abs(pair.correlation()) >= thr) {
                            relatedHits++;
                        }
                    }
                    if (relatedHits >= maxConc) {
                        String reason = String.format("correlation cap: %d existing positions ≥%.2f corr (max %d)",
                            relatedHits, thr, maxConc);
                        blockBuy(symbol, reason, currentPrice, regime, currentVix);
                        TradingWebSocketHandler.broadcastActivity(
                            String.format("[%s] ⛔ %s correlation cap: %d ≥%.2f (max %d)",
                                profile.name(), symbol, relatedHits, thr, maxConc),
                            "WARN");
                        return new Blocked(reason);
                    }
                }
            } catch (Exception e) {
                logger.debug("{} Correlation cap check failed for {}: {}", profilePrefix, symbol, e.getMessage());
            }
        }

        // ========== PENDING ORDER CHECK ==========
        // Prevent duplicate orders for the same symbol (fixes order spam issue).
        // If a pending entry order has been sitting >30 min (e.g. sandbox never fills),
        // cancel it so the bot can re-evaluate and place a fresh order.
        try {
            var pendingOrders = client.getOpenOrders(symbol);
            if (pendingOrders.isArray() && pendingOrders.size() > 0) {
                long now = System.currentTimeMillis();
                long firstSeen = pendingEntryTimestamps.computeIfAbsent(symbol, k -> now);
                long ageMs = now - firstSeen;

                if (ageMs >= STALE_ENTRY_ORDER_MS) {
                    logger.warn("{} {} has {} stale pending entry order(s) ({}min old) — cancelling and re-evaluating",
                        profilePrefix, symbol, pendingOrders.size(), ageMs / 60000);
                    for (var order : pendingOrders) {
                        String orderId = order.path("id").asText();
                        if (!orderId.isBlank()) {
                            try { client.cancelOrder(orderId); } catch (Exception ce) {
                                logger.warn("{} Failed to cancel stale entry order {} for {}: {}",
                                    profilePrefix, orderId, symbol, ce.getMessage());
                            }
                        }
                    }
                    pendingEntryTimestamps.remove(symbol);
                    // Fall through to re-evaluate entry this cycle
                } else {
                    logger.info("{} {} already has {} pending order(s) ({}min old), skipping new entry",
                        profilePrefix, symbol, pendingOrders.size(), ageMs / 60000);
                    return new Blocked("pending order already exists");
                }
            } else {
                pendingEntryTimestamps.remove(symbol); // order filled/expired — clear tracking
            }
        } catch (Exception e) {
            logger.debug("{} Could not check pending orders for {}: {}", profilePrefix, symbol, e.getMessage());
        }

        // ========== AI COMPONENT 1: SENTIMENT ANALYSIS ==========
        if (sentimentAnalyzer != null) {
            try {
                boolean isBullish = true; // handleBuy is always a long (bullish) entry
                double sentimentScore = sentimentAnalyzer.getSentimentScore(symbol);

                // Record sentiment for dashboard
                com.trading.ai.AIMetricsTracker.getInstance().recordSentiment(symbol, sentimentScore);

                if (!sentimentAnalyzer.isSentimentPositive(symbol, isBullish)) {
                    // Score source depends on config: real ML sentiment (Alpha Vantage/FinGPT) if
                    // enabled, otherwise a keyword-count fallback over real Alpaca news headlines —
                    // see SentimentAnalyzer's class Javadoc. Both are currently disabled
                    // (ALPHA_VANTAGE_ENABLED/FINGPT_ENABLED=false), so this is keyword-based today.
                    logger.info("{} {}: ❌ AI FILTER - Negative sentiment, skipping trade",
                        profilePrefix, symbol);
                    com.trading.ai.AIMetricsTracker.getInstance().incrementTradesFiltered();
                    return new Blocked("negative sentiment");
                }
                logger.debug("{} {}: ✅ Sentiment check passed", profilePrefix, symbol);
            } catch (Exception e) {
                logger.warn("{} {}: Sentiment analysis failed, continuing: {}",
                    profilePrefix, symbol, e.getMessage());
            }
        }

        // ========== PHASE 3: ECONOMIC CALENDAR BLACKOUT ==========
        // On FOMC/CPI/PCE/NFP release days, macro surprise risk dwarfs any technical signal.
        // Block all new entries; existing positions can run to their natural SL/TP.
        if (config.isEconomicCalendarBlackoutEnabled()) {
            var blackoutDates = config.getEconomicBlackoutDates();
            var today = java.time.LocalDate.now(java.time.ZoneId.of("America/New_York"));
            if (blackoutDates.contains(today)) {
                logger.info("{} {}: ❌ ECONOMIC BLACKOUT — macro event day ({}), no new entries",
                    profilePrefix, symbol, today);
                return new Blocked("economic calendar blackout");
            }
        }

        // ========== PHASE 3: MARKET BREADTH FILTER ==========
        // Skip when regime is RANGE_BOUND at low VIX — the regime detector already
        // overrode low breadth (e.g. VIX < 14 → RANGE_BOUND) meaning it determined
        // this is sector rotation, not a broad decline. Blocking on breadth here would
        // double-count the same concern and produce 0 trades all day (observed Jul 27 2026).
        boolean skipBreadthFilter = (regime == MarketRegime.RANGE_BOUND && currentVix < 15.0);
        if (!skipBreadthFilter && !marketBreadthAnalyzer.isMarketHealthy()) {
            logger.info("{} {}: ❌ PHASE 3 FILTER - Market breadth too low, skipping trade",
                profilePrefix, symbol);
            return new Blocked("market breadth too low");
        }

        // Broadcast market breadth to UI
        double breadth = marketBreadthAnalyzer.getCurrentBreadth();
        TradingWebSocketHandler.broadcastPhase3Event(
            "PHASE3_MARKET_BREADTH",
            String.format("{\"breadth\":%.2f}", breadth)
        );

        // ========== PHASE 3: REGIME WIN-RATE GATE ==========
        // If this symbol has a losing track record in the current regime (≥5 trades, <35% win rate),
        // block new entries to avoid repeating a known bad setup. This adaptive gate prevents
        // the bot from repeatedly entering the same symbol under conditions where it historically loses.
        if (regime != null) {
            String regimeName = regime.name();
            var regimeStats = database.getSymbolStatistics(symbol, regimeName, 5);
            if (regimeStats != null && regimeStats.winRate() < 0.35) {
                logger.warn("{} {}: ❌ WIN-RATE GATE — {}% win rate in {} over {} trades, blocking entry",
                    profilePrefix, symbol,
                    String.format("%.0f", regimeStats.winRate() * 100),
                    regimeName, regimeStats.totalTrades());
                TradingWebSocketHandler.broadcastActivity(
                    String.format("[%s] ⚠️ %s blocked — low win rate in %s (%.0f%% over %d trades)",
                        profile.name(), symbol, regimeName,
                        regimeStats.winRate() * 100, regimeStats.totalTrades()),
                    "WARNING"
                );
                return new Blocked("low win rate in " + regimeName);
            }
        }

        // ========== PHASE 3: ML ENTRY SCORING ==========
        if (config.isMLEntryScoringEnabled()) {
            try {
                // Get recent bars for ML analysis
                var bars = client.getBars(symbol, "15Min", 50);
                double mlScore = mlEntryScorer.scoreEntry(symbol, currentPrice, bars);

                if (!mlEntryScorer.meetsThreshold(mlScore)) {
                    logger.info("{} {}: ❌ PHASE 3 FILTER - ML score too low: {} (min: {}), skipping",
                        profilePrefix, symbol, String.format("%.1f", mlScore), String.format("%.1f", config.getMLMinScore()));
                    return new Blocked("ML score too low: " + mlScore);
                }

                logger.info("{} {}: ✅ PHASE 3 - ML score: {} (passed)",
                    profilePrefix, symbol, String.format("%.1f", mlScore));

                // Broadcast ML score to UI
                TradingWebSocketHandler.broadcastPhase3Event(
                    "PHASE3_ML_SCORE",
                    String.format("{\"symbol\":\"%s\",\"score\":%.1f}", symbol, mlScore)
                );

            } catch (Exception e) {
                logger.warn("{} {}: ML scoring failed, continuing: {}",
                    profilePrefix, symbol, e.getMessage());
            }
        }

        // ========== PHASE 3: VOLUME PROFILE CHECK ==========
        // Skip in RANGE_BOUND — the filter looks for price at a high-volume node (support/resistance),
        // which makes sense for trend entries but not for mean reversion or MACD in sideways markets.
        // In RANGE_BOUND the strategies themselves already gate on RSI and Bollinger bands.
        // At VIX < 15 intraday gaps also push price >1.5% from opening nodes, causing false blocks.
        boolean skipVolumeProfile = (regime == MarketRegime.RANGE_BOUND);
        if (!skipVolumeProfile && config.isVolumeProfileEnabled()) {
            try {
                var bars = client.getBars(symbol, "15Min", 50);
                if (!volumeProfileAnalyzer.isGoodEntryPrice(symbol, currentPrice, bars)) {
                    logger.info("{} {}: ❌ PHASE 3 FILTER - Price not near volume support, skipping",
                        profilePrefix, symbol);
                    TradingWebSocketHandler.broadcastActivity(
                        String.format("[%s] ⛔ %s skipped: price not at volume support", profile.name(), symbol),
                        "INFO"
                    );
                    return new Blocked("price not at volume support");
                }
            } catch (Exception e) {
                logger.debug("{} {}: Volume profile check failed: {}",
                    profilePrefix, symbol, e.getMessage());
            }
        }

        // ========== AI COMPONENT 2: ML PREDICTION ==========
        if (signalPredictor != null) {
            try {
                var now = LocalDateTime.now();
                var tradeStats = database.getTradeStatistics();
                double recentWinRate = tradeStats.totalTrades() > 5 ? tradeStats.winRate() : 0.55; // min 6 trades before trusting stats
                var setup = new com.trading.ai.SignalPredictor.TradingSetup(
                    currentVix,
                    now.getHour(),
                    now.getDayOfWeek(),
                    1.0, // volume ratio (computed by VolumeProfileAnalyzer above, not yet threaded here)
                    recentWinRate,
                    80 // pattern confidence
                );

                double winProb = signalPredictor.predictWinProbability(setup);

                // Record ML prediction for dashboard
                com.trading.ai.AIMetricsTracker.getInstance().recordMLPrediction(winProb * 100);

                // Use config threshold instead of hardcoded 0.60
                double minWinRate = config.getPositionSizingDefaultWinRate();
                if (winProb < minWinRate) {
                    logger.info("{} {}: ❌ AI FILTER - Low win probability: {}%, skipping",
                        profilePrefix, symbol, String.format("%.1f", winProb * 100));
                    com.trading.ai.AIMetricsTracker.getInstance().incrementTradesFiltered();
                    return new Blocked("low win probability: " + (winProb * 100) + "%");
                }
                logger.debug("{} {}: ✅ ML prediction: {}% win probability",
                    profilePrefix, symbol, String.format("%.1f", winProb * 100));
            } catch (Exception e) {
                logger.warn("{} {}: ML prediction failed, continuing: {}",
                    profilePrefix, symbol, e.getMessage());
            }
        }

        return new Pass();
    }

    /**
     * Central blocked-buy handler: stamps riskGate.blockedBuys() map, persists to DB, and emits
     * a structured [BUY_BLOCKED] log line. All entry gates call this instead of
     * writing to riskGate.blockedBuys() + logger separately, so every rejection is captured.
     */
    private void blockBuy(String symbol, String reason, double price, MarketRegime regime, double vix) {
        riskGate.blockedBuys().put(symbol, reason);
        logger.info("[BUY_BLOCKED] {} {} price=${} reason={}",
            profile.name(), symbol, String.format("%.2f", price), reason);
        database.saveBlockedEntry(symbol, profile.name(), reason, price,
            regime != null ? regime.name() : "UNKNOWN", vix);
    }

    /**
     * Blocks new entries while an existing position is bleeding — prevents compounding risk
     * by adding fresh capital when the account is already under stress.
     */
    private boolean hasSignificantlyLosingPosition(String profilePrefix, String candidateSymbol) {
        for (var entry : riskGate.globalHeldSymbols().entrySet()) {
            if (!entry.getValue().equals(profilePrefix)) continue;
            String heldSymbol = entry.getKey();
            try {
                var bar = client.getLatestBar(heldSymbol);
                if (bar.isEmpty()) continue;
                double heldCurrentPrice = bar.get().close();
                var pos = portfolio.getPosition(heldSymbol);
                if (pos.isEmpty()) continue;
                double entryPrice = pos.get().entryPrice();
                double lossPct = (heldCurrentPrice - entryPrice) / entryPrice * 100.0;
                if (lossPct <= -0.20) {
                    logger.info("{} {} BUY BLOCKED — {} already at {}% loss, holding off new entries",
                        profilePrefix, candidateSymbol, heldSymbol, String.format("%.2f", lossPct));
                    return true;
                }
            } catch (Exception e) {
                logger.debug("{} hasSignificantlyLosingPosition: error checking {}: {}",
                    profilePrefix, heldSymbol, e.getMessage());
            }
        }
        return false;
    }
}
