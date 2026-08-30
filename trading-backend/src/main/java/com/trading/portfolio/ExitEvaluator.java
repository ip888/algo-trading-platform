package com.trading.portfolio;

import com.trading.analysis.MarketRegimeDetector.MarketRegime;
import com.trading.api.PDTRejectedException;
import com.trading.api.ResilientBrokerClient;
import com.trading.autonomous.ConfigSelfHealer;
import com.trading.config.Config;
import com.trading.exits.ExitStrategyManager;
import com.trading.exits.Phase2ExitStrategies;
import com.trading.exits.TimeDecayExitManager;
import com.trading.exits.TrailingTargetManager;
import com.trading.execution.SmartOrderTypeSelector;
import com.trading.execution.SmartOrderTypeSelector.OrderContext;
import com.trading.persistence.TradeDatabase;
import com.trading.risk.CircuitBreakerState;
import com.trading.risk.RiskManager;
import com.trading.risk.TradePosition;
import com.trading.strategy.TradingProfile;
import com.trading.testing.TestModeSimulator;
import com.trading.websocket.TradingWebSocketHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.DoubleSupplier;
import java.util.function.Supplier;

/**
 * Evaluates and executes every position-exit path for one ProfileManager: risk exits (stops,
 * time decay, pre-earnings, winner-runner, regime flip), take-profit/breakeven checks, signal
 * exits, trailing-stop maintenance, the urgent-retry queue, excess-position cleanup, and the
 * end-of-day flatten.
 *
 * <p>Extracted 2026-08-30 from ProfileManager as the third step of the ProfileManager
 * simplification (after RiskGate, then PositionSizer — see their class Javadocs for the same
 * effort's earlier steps). Unlike PositionSizer, this is a <b>same-shape relocation, not a
 * decision/execution split</b>: the source methods each decide AND execute inline (place the
 * broker order, close the DB trade, update cooldowns/circuit-breakers) with early {@code continue}
 * statements woven through per-position loops. Rebuilding that as a clean "decide, then act"
 * pipeline would mean inventing result types for five-plus different exit reasons and passing
 * back much more mutable state than PositionSizer needed — real redesign risk this stage
 * deliberately avoids. So every method here keeps its original control flow verbatim; only the
 * owning class changed.
 *
 * <p><b>What did NOT move, and why:</b> the max-loss/time-based exit check embedded inside the
 * per-symbol target-scanning loop (the method that also evaluates new entries) stayed on
 * ProfileManager, because it shares local variables ({@code currentPosition}, {@code bar},
 * {@code qty}) with the entry-evaluation code in the same method scope — detaching it would risk
 * subtly changing what the entry path sees. {@code updateDailyPnL} and the {@code todayPnL}/
 * {@code lastResetDate} fields it owns also stayed, because {@code todayPnL} is read directly by
 * the (also-staying) daily-loss and daily-profit-target entry gates; it is passed into this class
 * as a callback instead so both sides keep reading the same single value.
 *
 * <p>{@code pdtBlockedUntil} moved onto {@link RiskGate} (as {@code staticPdtBlockedUntil}, the
 * field already existed there as a dashboard-facing mirror — see RiskGate's Javadoc) so that both
 * this class and the handful of call sites still on ProfileManager read and write the same value;
 * previously ProfileManager kept its own copy and synced it into RiskGate's mirror by hand.
 */
final class ExitEvaluator {

    private static final Logger logger = LoggerFactory.getLogger(ExitEvaluator.class);

    private final TradingProfile profile;
    private final String brokerName;
    private final Config config;
    private final TradeDatabase database;
    private final ResilientBrokerClient client;
    private final PortfolioManager portfolio;
    private final RiskGate riskGate;
    private final ExitStrategyManager exitStrategyManager;
    private final Phase2ExitStrategies phase2ExitStrategies;
    private final TimeDecayExitManager timeDecayExitManager;
    private final TrailingTargetManager trailingTargetManager;
    private final SmartOrderTypeSelector orderTypeSelector;
    private final TestModeSimulator testSimulator;
    private final ConfigSelfHealer configSelfHealer;
    private final double capital;

    /** Symbols with an active breakeven stop already placed — prevents cancel+replace every cycle. */
    private final Set<String> breakevenStopsActive = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Live reads of ProfileManager's per-cycle market snapshot — always the latest value, not captured at construction. */
    private final DoubleSupplier latestVix;
    private final Supplier<MarketRegime> latestRegime;
    private final DoubleSupplier latestEquity;

    /** Feeds ProfileManager's todayPnL — stays there because the daily-loss/profit-target entry gates read it directly. */
    private final BiConsumer<String, Double> updateDailyPnLFn;

    /** EOD-exit-executed-today guard. Per-instance (one ExitEvaluator per ProfileManager), so owned directly rather than shared. */
    private LocalDate eodExitExecutedDate = null;

    ExitEvaluator(TradingProfile profile, String brokerName, Config config, TradeDatabase database,
                  ResilientBrokerClient client, PortfolioManager portfolio, RiskGate riskGate,
                  ExitStrategyManager exitStrategyManager, Phase2ExitStrategies phase2ExitStrategies,
                  TimeDecayExitManager timeDecayExitManager, TrailingTargetManager trailingTargetManager,
                  SmartOrderTypeSelector orderTypeSelector, TestModeSimulator testSimulator,
                  ConfigSelfHealer configSelfHealer, double capital,
                  DoubleSupplier latestVix, Supplier<MarketRegime> latestRegime, DoubleSupplier latestEquity,
                  BiConsumer<String, Double> updateDailyPnLFn) {
        this.profile = profile;
        this.brokerName = brokerName;
        this.config = config;
        this.database = database;
        this.client = client;
        this.portfolio = portfolio;
        this.riskGate = riskGate;
        this.exitStrategyManager = exitStrategyManager;
        this.phase2ExitStrategies = phase2ExitStrategies;
        this.timeDecayExitManager = timeDecayExitManager;
        this.trailingTargetManager = trailingTargetManager;
        this.orderTypeSelector = orderTypeSelector;
        this.testSimulator = testSimulator;
        this.configSelfHealer = configSelfHealer;
        this.capital = capital;
        this.latestVix = latestVix;
        this.latestRegime = latestRegime;
        this.latestEquity = latestEquity;
        this.updateDailyPnLFn = updateDailyPnLFn;
    }

    /**
     * Cancel all existing orders for a symbol to free up held shares before placing a new sell order.
     */
    void cancelExistingOrders(String profilePrefix, String symbol) {
        try {
            var openOrders = client.getOpenOrders(symbol);
            if (openOrders.isArray()) {
                for (var order : openOrders) {
                    String orderId = order.get("id").asText();
                    client.cancelOrder(orderId);
                    logger.info("{} Canceled existing order {} for {} before exit", profilePrefix, orderId, symbol);
                }
            }
        } catch (Exception e) {
            logger.warn("{} Could not cancel existing orders for {}: {}", profilePrefix, symbol, e.getMessage());
        }
    }

    /**
     * Central cleanup for every exit path. Keeps riskGate.globalHeldSymbols(), riskGate.scalpHeldSymbols(),
     * trailingTargetManager, and breakevenStopsActive in sync regardless of how a position exits
     * (TP, SL, time-based, EOD, reconciliation, regime flip, cleanup).
     */
    void clearPositionTracking(String symbol) {
        riskGate.globalHeldSymbols().remove(symbol);
        riskGate.scalpHeldSymbols().remove(symbol);
        trailingTargetManager.removePosition(symbol);
        breakevenStopsActive.remove(symbol);
        database.deleteBotState("trailing:" + symbol + ":" + brokerName);
    }

    /**
     * Arm the re-entry cooldown + last-exit-price gate after a forced exit
     * (time-based, max-loss, stop-loss). Without this, forced exits skip the
     * same-day re-entry protection applied by handleSell, producing churn
     * (e.g. time-exit TLT → immediately re-buy TLT on next cycle's RSI signal).
     */
    void applyPostExitCooldown(String symbol, double exitPrice, double pnl,
                                String profilePrefix, String exitKind) {
        long cooldownMs = config.getStopLossCooldownMs();
        riskGate.stopLossCooldowns().put(symbol, System.currentTimeMillis() + cooldownMs);
        if (pnl < 0) {
            riskGate.lastExitPrices().put(symbol, exitPrice);
            if (riskGate.postLossCooldown() != null) {
                riskGate.postLossCooldown().recordLoss(symbol, System.currentTimeMillis());
                riskGate.consecutiveStopLosses().put(symbol, riskGate.postLossCooldown().getConsecutiveLosses(symbol));
            } else {
                riskGate.consecutiveStopLosses().merge(symbol, 1, Integer::sum);
            }
        } else if (pnl > 0) {
            riskGate.consecutiveStopLosses().remove(symbol);
            if (riskGate.postLossCooldown() != null) riskGate.postLossCooldown().recordWin(symbol);
        }
        CircuitBreakerState cb = riskGate.circuitBreakers().get(brokerName);
        if (cb != null) cb.recordTrade(pnl);
        logger.info("{} {} placed on {}-minute re-entry cooldown after {} exit (pnl=${})",
            profilePrefix, symbol, cooldownMs / 60000, exitKind, String.format("%.2f", pnl));
    }

    void handleSell(String symbol, double currentPrice, TradePosition position,
                     String profilePrefix) throws Exception {

        double pnl = position.calculatePnL(currentPrice);

        logger.info("[TRADE_CLOSE] {} {} exit=${} entry=${} pnl=${} reason=signal_sell",
            profile.name(), symbol, String.format("%.2f", currentPrice),
            String.format("%.2f", position.entryPrice()), String.format("%.2f", pnl));

        // Place sell order (skip if in test mode)
        if (testSimulator == null) {
            // Cancel existing orders to free up held shares
            cancelExistingOrders("[" + profile.name() + "]", symbol);

            // Determine optimal order type for signal-based exit
            var orderCtx = new OrderContext(
                symbol, "sell", currentPrice, latestEquity.getAsDouble(), latestVix.getAsDouble(), latestRegime.get(),
                profile.strategyType(), true, false, false
            );
            var orderDecision = orderTypeSelector.selectOrderType(orderCtx);
            client.placeOrder(symbol, position.quantity(), "sell",
                orderDecision.orderType(), orderDecision.timeInForce(), orderDecision.limitPrice());
        }

        // Update portfolio and cross-profile tracker
        portfolio.setPosition(symbol, Optional.empty());
        clearPositionTracking(symbol);

        // Set re-entry cooldown to prevent immediate re-buy
        long cooldownMs = config.getStopLossCooldownMs();
        riskGate.stopLossCooldowns().put(symbol, System.currentTimeMillis() + cooldownMs);
        logger.info("{} {} placed on {}-minute re-entry cooldown after sell", profilePrefix, symbol, cooldownMs / 60000);

        // Record exit price for loss exits — require price improvement before re-entry
        if (pnl < 0) {
            riskGate.lastExitPrices().put(symbol, currentPrice);
            logger.info("{} {} recorded loss exit at ${} — re-entry requires {}% price improvement",
                profilePrefix, symbol, String.format("%.2f", currentPrice), RiskGate.MIN_PRICE_IMPROVEMENT_PERCENT);
            // Tier 1.1: feed per-symbol post-loss cooldown (escalates after consecutive losses).
            if (riskGate.postLossCooldown() != null) {
                long applied = riskGate.postLossCooldown().recordLoss(symbol, System.currentTimeMillis());
                int consecLosses = riskGate.postLossCooldown().getConsecutiveLosses(symbol);
                riskGate.consecutiveStopLosses().put(symbol, consecLosses);
                logger.info("{} {} post-loss cooldown applied: {}h ({} consec losses)",
                    profilePrefix, symbol, applied / (60L * 60 * 1000), consecLosses);
                // Persist so the cooldown survives a restart
                long expiryMs = System.currentTimeMillis() + applied;
                database.saveBotState("cooldown:" + symbol,
                    expiryMs + "," + consecLosses);
                database.saveBotState("consec_sl:" + symbol, String.valueOf(consecLosses));
                logger.info("[COOLDOWN_START] {} {} post-loss cooldown={}h consec={}",
                    profile.name(), symbol, applied / (60L * 60 * 1000), consecLosses);
            } else {
                riskGate.consecutiveStopLosses().merge(symbol, 1, Integer::sum);
            }
        } else {
            // Profitable exit — allow free re-entry at any price, reset consecutive SL counter.
            riskGate.lastExitPrices().remove(symbol);
            riskGate.consecutiveStopLosses().remove(symbol);
            if (riskGate.postLossCooldown() != null) {
                riskGate.postLossCooldown().recordWin(symbol);
                // Clear persisted state for this symbol — no active cooldown
                database.deleteBotState("cooldown:" + symbol);
                database.deleteBotState("consec_sl:" + symbol);
            }
            logger.debug("{} {} consecutive SL counter reset after profitable exit", profilePrefix, symbol);
        }

        // Tier 3.10: feed circuit breaker (per-broker session breaker on consecutive $-losses).
        CircuitBreakerState cb = riskGate.circuitBreakers().get(brokerName);
        if (cb != null) cb.recordTrade(pnl);

        // Close trade in database
        database.closeTrade(symbol, Instant.now(), currentPrice, pnl, brokerName, "signal_sell");
        updateDailyPnLFn.accept(profilePrefix, pnl);

        // Broadcast trade event
        TradingWebSocketHandler.broadcastTradeEvent(
            symbol, "SELL", currentPrice, position.quantity(),
            profile.name() + " Profile (P&L: $" + String.format("%.2f", pnl) + ")"
        );
    }

    void updateTrailingStop(String symbol, double currentPrice, TradePosition position,
                             String profilePrefix) {

        double trailingStopPercent = profile.trailingStopPercent() / 100.0;

        // Base trailing stop: lifts at a fixed percentage below highest price
        var updatedPosition = position.updateTrailingStop(currentPrice, trailingStopPercent);

        // Multi-level tighter trail: at +1% profit trail 0.5% below price, at +2% trail 0.3%.
        // This protects gains more aggressively once a position is solidly profitable,
        // without changing the initial stop distance on new entries.
        double multiLevelStop = trailingTargetManager.updateTrailingStop(position, currentPrice);
        if (multiLevelStop > updatedPosition.stopLoss()) {
            updatedPosition = new TradePosition(
                symbol, position.entryPrice(), position.quantity(),
                multiLevelStop, position.takeProfit(), position.entryTime(),
                Math.max(position.highestPrice(), currentPrice), position.partialExitsExecuted()
            );
        }

        // Persist the tighter stop if it moved up
        if (updatedPosition.stopLoss() > position.stopLoss()) {
            portfolio.setPosition(symbol, Optional.of(updatedPosition));
            database.updateStop(symbol, brokerName, updatedPosition.stopLoss());
            // Persist trailing-target state so it survives a JVM restart.
            // Without this, the multi-level trail level resets to 0 on redeploy and the
            // stop reverts to entry-time calculation, potentially giving back captured gains.
            String trailingEncoded = trailingTargetManager.getEncodedState(symbol);
            if (trailingEncoded != null) {
                database.saveBotState("trailing:" + symbol + ":" + brokerName, trailingEncoded);
            }

            logger.info("{} {}: Trailing stop updated: ${} -> ${}",
                profilePrefix, symbol,
                String.format("%.2f", position.stopLoss()),
                String.format("%.2f", updatedPosition.stopLoss()));

            // Replace the native Alpaca stop order at the new level.
            // Without this, the original lower stop stays on Alpaca and fires too early:
            // e.g. position gains 1%, trailing stop raised to entry, but old stop at entry-1%
            // still on Alpaca → next pullback to entry-1% triggers the stale order, exiting
            // at a loss despite the in-memory trailing stop being at entry (breakeven).
            try {
                var openOrders = client.getOpenOrders(symbol);
                for (var ord : openOrders) {
                    String otype = ord.path("type").asText("");
                    String oside = ord.path("side").asText("");
                    if (("stop".equals(otype) || "stop_limit".equals(otype)) && "sell".equals(oside)) {
                        client.cancelOrder(ord.path("id").asText());
                    }
                }
                client.placeNativeStopOrder(symbol, updatedPosition.quantity(), updatedPosition.stopLoss());
                logger.info("{} {}: Broker stop replaced at ${} (trailing update)",
                    profilePrefix, symbol, String.format("%.2f", updatedPosition.stopLoss()));
            } catch (Exception stopEx) {
                logger.warn("{} {}: Could not replace broker stop after trailing update ({}); in-memory stop still active",
                    profilePrefix, symbol, stopEx.getMessage());
            }
        }
    }

    /**
     * Check ALL Alpaca positions for max loss and time-based exits.
     * This includes positions not in current target symbols (e.g., from previous market regimes).
     * Only the MAIN profile should execute exits to avoid duplicate orders.
     */
    void checkAllPositionsForRiskExits(String profilePrefix) {
        if (!config.isMaxLossExitEnabled()) {
            return; // Feature disabled
        }

        // PDT circuit breaker: skip sell attempts if Alpaca recently rejected with 403 PDT
        if (System.currentTimeMillis() < riskGate.staticPdtBlockedUntil()) {
            logger.debug("{} Skipping risk exits — PDT blocked for {} more seconds",
                profilePrefix, (riskGate.staticPdtBlockedUntil() - System.currentTimeMillis()) / 1000);
            return;
        }

        try {
            var allPositions = client.getPositions();
            // Non-MAIN profiles: only process positions this profile owns to avoid
            // touching MAIN's positions or triggering orphan recovery on foreign symbols.
            var ownedSymbols = profile.isMainProfile() ? null : portfolio.getAllPositions().keySet();

            // Get current portfolio positions for correlation analysis
            java.util.Map<String, Double> portfolioPositions = new java.util.HashMap<>();
            for (var pos : allPositions) {
                portfolioPositions.put(pos.symbol(), pos.marketValue());
            }

            // Fetch open DB records once and index by symbol — avoids N full table scans per cycle.
            var openDbRecords = database.getOpenTradeRecords(brokerName);
            var openDbRecordsBySymbol = openDbRecords.stream()
                .collect(java.util.stream.Collectors.toMap(
                    r -> r.symbol(), r -> r, (a, b) -> a));

            for (var alpacaPos : allPositions) {
                String symbol = alpacaPos.symbol();
                if (ownedSymbols != null && !ownedSymbols.contains(symbol)) continue;
                double qty = alpacaPos.quantity();

                // Skip if a sell order was already placed this cycle (prevents duplicate sells)
                if (riskGate.pendingExitOrders().containsKey(brokerName + ":" + symbol)) {
                    logger.debug("{} {} has pending exit order, skipping risk exit check", profilePrefix, symbol);
                    continue;
                }

                // Guard against division by zero for empty positions
                if (qty == 0) {
                    logger.debug("{} Skipping zero-quantity position: {}", profilePrefix, symbol);
                    continue;
                }

                double currentPrice = alpacaPos.marketValue() / qty;
                double entryPrice = alpacaPos.avgEntryPrice();

                if (entryPrice == 0) {
                    logger.warn("{} Skipping position with zero entry price: {}", profilePrefix, symbol);
                    continue;
                }

                // Get tracked position if exists
                var trackedPos = portfolio.getPosition(symbol);

                // Use enhanced exit strategy if position is tracked
                if (trackedPos.isPresent()) {
                    TradePosition position = trackedPos.get();

                    // Calculate current volatility (simplified - using price movement)
                    double volatility = Math.abs(currentPrice - entryPrice) / entryPrice;

                    // Pre-earnings force-exit. Tier 2.5 only blocks new ENTRIES; an open position
                    // would otherwise ride straight into the announcement (the META scenario:
                    // bought 2026-04-27, held through 2026-04-30 earnings, gapped down ~10%).
                    if (config.isPreEarningsExitEnabled() && riskGate.earningsCalendar() != null
                            && config.getPreEarningsExitHoursBefore() > 0) {
                        try {
                            boolean approachingEarnings = riskGate.earningsCalendar().isInBlackout(
                                symbol, Instant.now(),
                                config.getPreEarningsExitHoursBefore(), 0);
                            if (approachingEarnings) {
                                var exitDecision = ExitStrategyManager.ExitDecision.fullExit(
                                    ExitStrategyManager.ExitType.EARNINGS_PROTECTION,
                                    String.format("earnings within %dh — pre-emptive exit",
                                        config.getPreEarningsExitHoursBefore()),
                                    currentPrice);
                                logger.warn("{} {} 🗓️ PRE-EARNINGS EXIT — {}",
                                    profilePrefix, symbol, exitDecision.reason());
                                try {
                                    cancelExistingOrders(profilePrefix, symbol);
                                    client.placeOrderDirect(symbol, qty, "sell", "market", "day", null);
                                    double earningsPnl = (currentPrice - entryPrice) * qty;
                                    portfolio.setPosition(symbol, Optional.empty());
                                    clearPositionTracking(symbol);
                                    database.closeTrade(symbol, Instant.now(), currentPrice, earningsPnl, brokerName, "pre_earnings");
                                    updateDailyPnLFn.accept(profilePrefix, earningsPnl);
                                    applyPostExitCooldown(symbol, currentPrice, earningsPnl, profilePrefix, "PRE_EARNINGS");
                                    TradingWebSocketHandler.broadcastActivity(
                                        String.format("[%s] 🗓️ PRE-EARNINGS EXIT: %s — %s",
                                            profile.name(), symbol, exitDecision.reason()),
                                        "WARN");
                                    riskGate.pendingExitOrders().put(brokerName + ":" + symbol, System.currentTimeMillis());
                                    continue;
                                } catch (PDTRejectedException e) {
                                    riskGate.setStaticPdtBlockedUntil(System.currentTimeMillis() + millisUntilMarketClose());
                                    logger.warn("{} PDT rejected pre-earnings exit for {}",
                                        profilePrefix, symbol);
                                    continue;
                                } catch (Exception e) {
                                    logger.error("{} Failed pre-earnings exit for {}",
                                        profilePrefix, symbol, e);
                                    riskGate.urgentExitQueue().put(RiskGate.urgentKey(brokerName, symbol),
                                        new RiskGate.UrgentExit(brokerName, symbol, qty,
                                            "pre-earnings", System.currentTimeMillis()));
                                }
                            }
                        } catch (Exception e) {
                            logger.debug("{} Earnings check failed for {}: {}",
                                profilePrefix, symbol, e.getMessage());
                        }
                    }

                    // Winner runner: when TP is first hit, sell 50% at TP and lock a profitable
                    // stop on the remaining 50%, then let it trail higher rather than full-exit.
                    // Level 4 in the partial-exit bitmask = "runner already launched."
                    if (!riskGate.scalpHeldSymbols().contains(symbol)
                            && config.isWinnerRunnerEnabled()
                            && !position.hasPartialExit(4)
                            && position.isTakeProfitHit(currentPrice)) {
                        double lockedStop = position.entryPrice()
                            + (position.takeProfit() - position.entryPrice()) * config.getRunnerLockPct();
                        // Use the profile's own tracked qty (not alpacaPos.quantity() which aggregates
                        // all profiles). If MAIN+EXPERIMENTAL both hold NVDA, Alpaca reports 2×qty
                        // and halfQty would equal the full MAIN position → 403 insufficient-qty error.
                        double halfQty = Math.min(position.quantity() * 0.5, qty);
                        try {
                            cancelExistingOrders(profilePrefix, symbol);
                            client.placeOrderDirect(symbol, halfQty, "sell", "market", "day", null);
                            double runnerPnl = (currentPrice - entryPrice) * halfQty;
                            updateDailyPnLFn.accept(profilePrefix, runnerPnl);
                            // Mark all partial levels done (1-3 are superseded; 4 = runner guard)
                            // and raise TP to 1.5× original so the runner half can run further
                            var marked = position.markPartialExit(1).markPartialExit(2)
                                .markPartialExit(3).markPartialExit(4);
                            double runnerTp = position.takeProfit()
                                + (position.takeProfit() - position.entryPrice()) * 0.5;
                            var runnerPos = new TradePosition(
                                symbol, position.entryPrice(), position.quantity(),
                                lockedStop, runnerTp,
                                position.entryTime(), Math.max(position.highestPrice(), currentPrice),
                                marked.partialExitsExecuted());
                            portfolio.setPosition(symbol, Optional.of(runnerPos));
                            database.updatePartialExits(symbol, brokerName, marked.partialExitsExecuted());
                            database.updateStop(symbol, brokerName, lockedStop);
                            double lockedPnlPct = (lockedStop - position.entryPrice()) / position.entryPrice() * 100;
                            logger.info("{} 🏃 RUNNER TP: {} sold half ({} @ ${}) — locked stop ${} (+{}%)",
                                profilePrefix, symbol,
                                String.format("%.4f", halfQty),
                                String.format("%.2f", currentPrice),
                                String.format("%.2f", lockedStop),
                                String.format("%.2f", lockedPnlPct));
                            TradingWebSocketHandler.broadcastActivity(
                                String.format("[%s] 🏃 RUNNER TP: %s — half sold at $%.2f, runner stop locked at $%.2f (+%.2f%%)",
                                    profile.name(), symbol, currentPrice, lockedStop, lockedPnlPct), "INFO");
                            riskGate.pendingExitOrders().put(brokerName + ":" + symbol, System.currentTimeMillis());
                            continue;
                        } catch (Exception e) {
                            logger.error("{} Runner TP failed for {}: {}", profilePrefix, symbol, e.getMessage());
                        }
                    }

                    // Flat-position time decay: exit stalled positions held N hours with < threshold% P&L.
                    // Frees capital for better opportunities instead of waiting for EOD exit.
                    if (!riskGate.scalpHeldSymbols().contains(symbol) && timeDecayExitManager.shouldExit(position, currentPrice)) {
                        String tdReason = timeDecayExitManager.getExitReason(position, currentPrice);
                        logger.info("{} ⏰ TIME-DECAY EXIT: {} — {}", profilePrefix, symbol, tdReason);
                        try {
                            cancelExistingOrders(profilePrefix, symbol);
                            client.placeOrderDirect(symbol, qty, "sell", "market", "day", null);
                            double tdPnl = (currentPrice - entryPrice) * qty;
                            portfolio.setPosition(symbol, Optional.empty());
                            clearPositionTracking(symbol);
                            database.closeTrade(symbol, Instant.now(), currentPrice, tdPnl, brokerName, "time_decay");
                            updateDailyPnLFn.accept(profilePrefix, tdPnl);
                            applyPostExitCooldown(symbol, currentPrice, tdPnl, profilePrefix, "TIME_DECAY");
                            TradingWebSocketHandler.broadcastActivity(
                                String.format("[%s] ⏰ TIME-DECAY EXIT: %s — %s", profile.name(), symbol, tdReason), "WARN");
                            riskGate.pendingExitOrders().put(brokerName + ":" + symbol, System.currentTimeMillis());
                            continue;
                        } catch (Exception e) {
                            logger.error("{} Time-decay exit failed for {}: {}", profilePrefix, symbol, e.getMessage());
                        }
                    }

                    // Evaluate exit decision using enhanced strategy.
                    // Scalp positions bypass partial exits and scale-out at 1R — they exit
                    // cleanly at the stored 0.40% TP or 0.25% SL as a single full-size order.
                    boolean isScalpPosition = riskGate.scalpHeldSymbols().contains(symbol);
                    var exitDecision = exitStrategyManager.evaluateExit(
                        position, currentPrice, volatility, portfolioPositions, isScalpPosition
                    );

                    if (exitDecision.type() != ExitStrategyManager.ExitType.NONE) {
                        // Use live broker qty for full exits to prevent "insufficient qty" errors
                        // when internal tracker has drifted from actual broker position.
                        double qtyToExit = exitDecision.isPartial()
                            ? position.quantity() * exitDecision.quantity()  // partial: fraction of internal
                            : qty;  // full exit: always use live broker qty

                        logger.info("{} 🎯 ENHANCED EXIT: {} - {}",
                            profilePrefix, symbol, exitDecision.reason());

                        try {
                            cancelExistingOrders(profilePrefix, symbol);
                            // Use direct order for risk exits (bypass circuit breaker - critical protective exit)
                            client.placeOrderDirect(symbol, qtyToExit, "sell", "market", "day", null);

                            if (exitDecision.isPartial()) {
                                logger.info("{} ✅ Partial exit executed: {} ({}% of position)",
                                    profilePrefix, symbol, String.format("%.1f", exitDecision.quantity() * 100));
                                // Mark the partial exit level so it won't re-trigger next cycle
                                if (exitDecision.partialLevel() > 0) {
                                    var marked = position.markPartialExit(exitDecision.partialLevel());
                                    portfolio.setPosition(symbol, Optional.of(marked));
                                    database.updatePartialExits(symbol, brokerName, marked.partialExitsExecuted());
                                }
                            } else {
                                logger.info("{} ✅ Full exit executed: {}", profilePrefix, symbol);
                                double tradePnl = (currentPrice - entryPrice) * qty;
                                portfolio.setPosition(symbol, Optional.empty());
                                clearPositionTracking(symbol);
                                database.closeTrade(symbol, Instant.now(), currentPrice, tradePnl, brokerName, "strategy_exit");
                                updateDailyPnLFn.accept(profilePrefix, tradePnl);
                                // Set re-entry cooldown after full exit
                                riskGate.stopLossCooldowns().put(symbol, System.currentTimeMillis() + config.getStopLossCooldownMs());
                                // Record exit price if loss — require price improvement before re-entry
                                if (currentPrice < entryPrice) {
                                    riskGate.lastExitPrices().put(symbol, currentPrice);
                                    if (riskGate.postLossCooldown() != null) {
                                        riskGate.postLossCooldown().recordLoss(symbol, System.currentTimeMillis());
                                        riskGate.consecutiveStopLosses().put(symbol, riskGate.postLossCooldown().getConsecutiveLosses(symbol));
                                    } else {
                                        riskGate.consecutiveStopLosses().merge(symbol, 1, Integer::sum);
                                    }
                                } else {
                                    riskGate.consecutiveStopLosses().remove(symbol);
                                    if (riskGate.postLossCooldown() != null) riskGate.postLossCooldown().recordWin(symbol);
                                }
                                CircuitBreakerState cbExit = riskGate.circuitBreakers().get(brokerName);
                                if (cbExit != null) cbExit.recordTrade(tradePnl);
                            }

                            TradingWebSocketHandler.broadcastActivity(
                                String.format("[%s] %s EXIT: %s - %s",
                                    profile.name(),
                                    exitDecision.isPartial() ? "PARTIAL" : "FULL",
                                    symbol,
                                    exitDecision.reason()),
                                exitDecision.isPartial() ? "INFO" : "WARN"
                            );

                            // Mark as pending to prevent duplicate sells in this and future cycles
                            riskGate.pendingExitOrders().put(brokerName + ":" + symbol, System.currentTimeMillis());
                            continue;
                        } catch (PDTRejectedException e) {
                            riskGate.setStaticPdtBlockedUntil(System.currentTimeMillis() + millisUntilMarketClose());
                            logger.warn("{} PDT rejected protective exit for {} — blocking until market close ({})",
                                profilePrefix, symbol, Instant.ofEpochMilli(riskGate.staticPdtBlockedUntil()));
                            TradingWebSocketHandler.broadcastActivity(
                                String.format("[%s] ⛔ PDT LIMIT HIT: %s exit blocked — all sells paused until 4PM ET. Positions protected by native stops.",
                                    profile.name(), symbol), "ERROR");
                            continue; // try remaining positions — non-day-trade sells may still succeed
                        } catch (Exception e) {
                            logger.error("{} Failed to place enhanced exit order for {}",
                                profilePrefix, symbol, e);
                            riskGate.urgentExitQueue().put(RiskGate.urgentKey(brokerName, symbol), new RiskGate.UrgentExit(brokerName, symbol, qtyToExit, exitDecision.reason(), System.currentTimeMillis()));
                            TradingWebSocketHandler.broadcastActivity(
                                String.format("[%s] ⚠️ EXIT FAILED, QUEUED FOR RETRY: %s (%s)",
                                    profile.name(), symbol, exitDecision.reason()),
                                "ERROR"
                            );
                        }
                    }
                    // ========== REGIME-AWARE EXIT / STOP TIGHTENING ==========
                    // If regime flips to WEAK_BEAR or STRONG_BEAR while holding a bullish position,
                    // tighten the stop to breakeven (profitable) or exit immediately (losing).
                    // Bearish ETFs (inverse funds) are excluded — they benefit from a falling market.
                    boolean isBearishEtf = profile.bearishSymbols().contains(symbol);
                    MarketRegime currentRegime = latestRegime.get();
                    boolean isInBearishRegime = currentRegime == MarketRegime.WEAK_BEAR
                        || currentRegime == MarketRegime.STRONG_BEAR;
                    if (!isBearishEtf && isInBearishRegime) {
                        double pnlPct = (currentPrice - entryPrice) / entryPrice * 100.0;
                        if (pnlPct > 0 && position.stopLoss() < entryPrice) {
                            double breakevenStop = entryPrice * 1.001;
                            var tightened = new TradePosition(
                                position.symbol(), position.entryPrice(), position.quantity(),
                                breakevenStop, position.takeProfit(), position.entryTime(),
                                position.highestPrice(), position.partialExitsExecuted());
                            portfolio.setPosition(symbol, Optional.of(tightened));
                            logger.info("{} {} 📉 REGIME→BEARISH: tightened SL to breakeven ${} (+{}%)",
                                profilePrefix, symbol,
                                String.format("%.2f", tightened.stopLoss()),
                                String.format("%.2f", pnlPct));
                            TradingWebSocketHandler.broadcastActivity(
                                String.format("[%s] 📉 %s stop tightened to breakeven — regime is %s",
                                    profile.name(), symbol, currentRegime.name()),
                                "WARN");
                        } else if (pnlPct <= -0.5) {
                            logger.warn("{} {} 📉 REGIME→BEARISH: exiting losing position ({}%)",
                                profilePrefix, symbol, String.format("%.2f", pnlPct));
                            TradingWebSocketHandler.broadcastActivity(
                                String.format("[%s] 📉 REGIME EXIT: %s (%.2f%%) — regime is %s",
                                    profile.name(), symbol, pnlPct, currentRegime.name()),
                                "WARN");
                            try {
                                cancelExistingOrders(profilePrefix, symbol);
                                client.placeOrderDirect(symbol, qty, "sell", "market", "day", null);
                                double pnl = (currentPrice - entryPrice) * qty;
                                portfolio.setPosition(symbol, Optional.empty());
                                clearPositionTracking(symbol);
                                database.closeTrade(symbol, Instant.now(), currentPrice, pnl, brokerName, "regime_flip");
                                updateDailyPnLFn.accept(profilePrefix, pnl);
                                applyPostExitCooldown(symbol, currentPrice, pnl, profilePrefix, "REGIME_EXIT");
                                riskGate.pendingExitOrders().put(brokerName + ":" + symbol, System.currentTimeMillis());
                            } catch (Exception e) {
                                logger.error("{} Failed regime exit for {}: {}", profilePrefix, symbol, e.getMessage());
                                riskGate.urgentExitQueue().put(RiskGate.urgentKey(brokerName, symbol),
                                    new RiskGate.UrgentExit(brokerName, symbol, qty, "regime-bearish", System.currentTimeMillis()));
                            }
                            continue;
                        }
                    }

                    continue; // Position handled by enhanced strategy
                }

                // Orphan/untracked-position recovery is MAIN's responsibility only.
                // EXPERIMENTAL positions not in its portfolio are either already closed
                // or managed by broker-side native stops.
                if (!profile.isMainProfile()) continue;

                // Settlement-lag guard: when a position is closed the broker may still report the
                // shares as held for up to ~15 minutes (T+0 settlement lag). Skip orphan registration
                // in that window to prevent phantom orphan → immediate force-close sequences.
                if (database.wasRecentlyClosed(symbol, brokerName, 15 * 60 * 1000L)) {
                    logger.info("{} {}: skipping orphan registration — trade closed within 15 min (settlement lag)",
                        profilePrefix, symbol);
                    continue;
                }

                // First sight of an untracked position: register it (with DB-persisted stops if available,
                // or freshly reconstructed tight stops otherwise) AND attempt a native broker stop. This
                // closes the META-incident hole where fractional fills + post-restart drift left positions
                // completely unprotected. Falls through to the loss-threshold safety net below.
                var existingDbRecord = Optional.ofNullable(openDbRecordsBySymbol.get(symbol));

                double recoveredStop;
                double recoveredTp;
                Instant recoveredEntryTime;
                if (existingDbRecord.isPresent()) {
                    var rec = existingDbRecord.get();
                    recoveredStop = rec.stopLoss();
                    recoveredTp = rec.takeProfit();
                    recoveredEntryTime = rec.entryTime();
                    logger.info("{} {}: untracked position — restored stops from DB (SL=${} TP=${})",
                        profilePrefix, symbol,
                        String.format("%.2f", recoveredStop), String.format("%.2f", recoveredTp));
                } else {
                    double profileSlFraction = Math.max(profile.stopLossPercent(), 1.0) / 100.0;
                    double profileTpFraction = Math.max(profile.takeProfitPercent(), 2.0) / 100.0;
                    double idealStop = Math.max(entryPrice * (1.0 - profileSlFraction), currentPrice * 0.985);
                    recoveredStop = Math.min(idealStop, currentPrice * 0.999);
                    recoveredTp = entryPrice * (1.0 + profileTpFraction);
                    recoveredEntryTime = Instant.now().minus(Duration.ofHours(24));
                    database.recordTrade(symbol, profile.strategyType(), profile.name(), brokerName,
                        recoveredEntryTime, entryPrice, qty, recoveredStop, recoveredTp);
                    logger.warn("{} {}: untracked position with no DB record — reconstructed SL=${} TP=${}",
                        profilePrefix, symbol,
                        String.format("%.2f", recoveredStop), String.format("%.2f", recoveredTp));
                }

                // Guard: recovered SL must always be strictly below entry price.
                // A profitable position (currentPrice > entryPrice) can produce an SL above entry
                // via the "tight 1.5% below current" formula, creating a phantom stop trigger.
                if (recoveredStop >= entryPrice) {
                    double slFraction = Math.max(profile.stopLossPercent(), 1.0) / 100.0;
                    double corrected = entryPrice * (1.0 - slFraction);
                    logger.warn("{} {}: SL ${} was at or above entry ${} — clamped to ${}",
                        profilePrefix, symbol,
                        String.format("%.2f", recoveredStop), String.format("%.2f", entryPrice),
                        String.format("%.2f", corrected));
                    recoveredStop = corrected;
                }

                boolean hasOpenStop = false;
                try {
                    var openOrders = client.getOpenOrders(symbol);
                    if (openOrders != null && openOrders.isArray()) {
                        for (var ord : openOrders) {
                            String otype = ord.has("type") ? ord.get("type").asText("").toLowerCase() : "";
                            if (otype.contains("stop")) { hasOpenStop = true; break; }
                        }
                    }
                } catch (Exception ignored) { /* best-effort detection */ }

                if (!hasOpenStop) {
                    try {
                        client.placeNativeStopOrder(symbol, qty, recoveredStop);
                        logger.warn("{} {}: ⚠️ orphan position recovered — native GTC stop placed @ ${}",
                            profilePrefix, symbol, String.format("%.2f", recoveredStop));
                        TradingWebSocketHandler.broadcastActivity(
                            String.format("[%s] ⚠️ Recovered orphan %s — native stop @ $%.2f",
                                profile.name(), symbol, recoveredStop),
                            "WARN");
                    } catch (Exception stopEx) {
                        logger.error("{} {}: 🚨 ORPHAN POSITION WITHOUT BROKER STOP ({}). Client-side max-loss is the only safety net.",
                            profilePrefix, symbol, stopEx.getMessage());
                        TradingWebSocketHandler.broadcastActivity(
                            String.format("[%s] 🚨 UNPROTECTED orphan %s — broker stop FAILED (%s)",
                                profile.name(), symbol, stopEx.getMessage()),
                            "ERROR");
                    }
                }

                int recoveredPartialMask = existingDbRecord.map(TradeDatabase.OpenTradeRecord::partialExitsExecuted).orElse(0);
                portfolio.setPosition(symbol,
                    Optional.of(new TradePosition(symbol, entryPrice, qty, recoveredStop, recoveredTp,
                        recoveredEntryTime, entryPrice, recoveredPartialMask)));

                // Loss-threshold safety net (also runs after registration as belt-and-suspenders).
                double lossPercent = ((currentPrice - entryPrice) / entryPrice) * 100;

                if (lossPercent <= -config.getMaxLossPercent()) {
                    logger.warn("{} ⚠️ MAX LOSS EXIT (untracked): {} down {}% (limit: -{}%)",
                        profilePrefix, symbol, String.format("%.2f", Math.abs(lossPercent)), String.format("%.1f", config.getMaxLossPercent()));

                    try {
                        cancelExistingOrders(profilePrefix, symbol);
                        // Use direct order for max-loss exit (bypass circuit breaker - critical protective exit)
                        client.placeOrderDirect(symbol, qty, "sell", "market", "day", null);
                        double maxLossPnl = (currentPrice - entryPrice) * qty;
                        portfolio.setPosition(symbol, Optional.empty());
                        clearPositionTracking(symbol);
                        database.closeTrade(symbol, Instant.now(), currentPrice, maxLossPnl, brokerName, "max_loss");
                        updateDailyPnLFn.accept(profilePrefix, maxLossPnl);
                        // Set re-entry cooldown + per-symbol cooldown + circuit-breaker tracking.
                        applyPostExitCooldown(symbol, currentPrice, maxLossPnl, profilePrefix, "MAX_LOSS_UNTRACKED");
                        logger.info("{} ✅ Max loss exit order placed for untracked position {}",
                            profilePrefix, symbol);

                        TradingWebSocketHandler.broadcastActivity(
                            String.format("[%s] MAX LOSS EXIT (untracked): %s (%.2f%% loss)",
                                profile.name(), symbol, Math.abs(lossPercent)),
                            "WARN"
                        );
                    } catch (PDTRejectedException e) {
                        riskGate.setStaticPdtBlockedUntil(System.currentTimeMillis() + millisUntilMarketClose());
                        logger.warn("{} PDT rejected max-loss exit for {} — blocking until market close",
                            profilePrefix, symbol);
                        return;
                    } catch (Exception e) {
                        logger.error("{} Failed to place max loss exit order for {}",
                            profilePrefix, symbol, e);
                        riskGate.urgentExitQueue().put(RiskGate.urgentKey(brokerName, symbol), new RiskGate.UrgentExit(brokerName, symbol, qty,
                            String.format("max loss (%.1f%%)", Math.abs(lossPercent)), System.currentTimeMillis()));
                        TradingWebSocketHandler.broadcastActivity(
                            String.format("[%s] ⚠️ MAX LOSS EXIT FAILED, QUEUED FOR RETRY: %s",
                                profile.name(), symbol),
                            "ERROR"
                        );
                    }
                }
            }
        } catch (Exception e) {
            logger.error("{} Error checking all positions for risk exits", profilePrefix, e);
        }
    }

    /**
     * Check ALL Alpaca positions for take-profit and stop-loss triggers.
     * CRITICAL: This ensures positions not in current target symbols are still monitored.
     * Only the MAIN profile should execute exits to avoid duplicate orders.
     */
    void checkAllPositionsForProfitTargets(String profilePrefix) {
        // PDT circuit breaker: skip sell attempts if Alpaca recently rejected with 403 PDT
        if (System.currentTimeMillis() < riskGate.staticPdtBlockedUntil()) {
            logger.debug("{} Skipping profit target checks — PDT blocked for {} more seconds",
                profilePrefix, (riskGate.staticPdtBlockedUntil() - System.currentTimeMillis()) / 1000);
            return;
        }

        try {
            var alpacaPositions = client.getPositions();
            // Non-MAIN profiles only process their own positions to avoid touching MAIN's.
            var ownedSymbols = profile.isMainProfile() ? null : portfolio.getAllPositions().keySet();
            logger.info("{} 🔍 Checking {} positions for take-profit/stop-loss", profilePrefix, alpacaPositions.size());

            for (var alpacaPos : alpacaPositions) {
                String symbol = alpacaPos.symbol();
                if (ownedSymbols != null && !ownedSymbols.contains(symbol)) continue;
                double qty = alpacaPos.quantity();
                double marketValue = alpacaPos.marketValue();
                double entryPrice = alpacaPos.avgEntryPrice();

                // Guard against division by zero
                if (qty == 0) {
                    logger.debug("{} Skipping zero-quantity position: {}", profilePrefix, symbol);
                    continue;
                }

                if (entryPrice == 0) {
                    logger.warn("{} Skipping position with zero entry price: {}", profilePrefix, symbol);
                    continue;
                }

                // Skip dust positions (< $1 market value) to prevent order spam
                if (Math.abs(marketValue) < 1.0) {
                    logger.debug("{} Skipping dust position: {} (value=${})", profilePrefix, symbol, String.format("%.2f", marketValue));
                    continue;
                }

                // Skip symbols with pending exit orders to prevent duplicate sell/closeTrade calls
                // This fixes the bug where 49+ duplicate trade records were created for one position
                if (riskGate.pendingExitOrders().containsKey(brokerName + ":" + symbol)) {
                    logger.info("{} {} has pending exit order (placed at {}), skipping duplicate check",
                        profilePrefix, symbol,
                        Instant.ofEpochMilli(riskGate.pendingExitOrders().get(brokerName + ":" + symbol)));
                    continue;
                }

                // Calculate current price from market value
                double currentPrice = Math.abs(marketValue / qty);

                // Calculate P&L percentage
                double pnlPercent = ((currentPrice - entryPrice) / entryPrice) * 100.0;

                // Check breakeven stop (move stop to entry at +0.3% profit)
                checkBreakevenStop(profilePrefix, symbol, entryPrice, pnlPercent, qty);

                // ========== PHASE 2 EXIT STRATEGIES ==========
                // Create temporary position for Phase 2 exit evaluation
                var riskManager = new RiskManager(latestEquity.getAsDouble() > 0 ? latestEquity.getAsDouble() : capital);
                var tempPosition = new TradePosition(
                    symbol,
                    entryPrice,
                    qty,
                    riskManager.calculateStopLoss(entryPrice),
                    riskManager.calculateTakeProfit(entryPrice),
                    Instant.now().minus(Duration.ofHours(6)) // Assume held 6 hours
                );

                // Check EOD Profit Lock (Feature #23)
                var eodDecision = phase2ExitStrategies.evaluateEODProfitLock(tempPosition, currentPrice);
                if (eodDecision.type() != ExitStrategyManager.ExitType.NONE) {
                    logger.info("{} 🔒 EOD PROFIT LOCK: {} - {}", profilePrefix, symbol, eodDecision.reason());

                    try {
                        double exitQty = eodDecision.isPartial() ? eodDecision.quantity() : qty;
                        cancelExistingOrders(profilePrefix, symbol);
                        client.placeOrder(symbol, exitQty, "sell", "market", "day", null);

                        double pnlDollars = (currentPrice - entryPrice) * exitQty;
                        database.closeTrade(symbol, Instant.now(), currentPrice, pnlDollars, brokerName, "eod_profit_lock");
                        if (!eodDecision.isPartial()) {
                            portfolio.setPosition(symbol, Optional.empty());
                            clearPositionTracking(symbol);
                            riskGate.pendingExitOrders().put(brokerName + ":" + symbol, System.currentTimeMillis());
                        }

                        TradingWebSocketHandler.broadcastActivity(
                            String.format("[%s] 🔒 EOD PROFIT LOCK: %s sold @ $%.2f (+%.2f%%, $%.2f)",
                                profile.name(), symbol, currentPrice, pnlPercent, pnlDollars),
                            "SUCCESS"
                        );

                        logger.info("{} ✅ EOD profit lock exit executed for {}", profilePrefix, symbol);
                        continue; // Skip other checks for this position
                    } catch (Exception e) {
                        logger.error("{} ❌ Failed to execute EOD profit lock for {}", profilePrefix, symbol, e);
                    }
                }

                // Get profile-specific targets — may be extended if trailing target is active.
                // VIX-scaled TP: low-VIX environments have narrower intraday ranges; the static
                // 1.5% target is rarely reachable at VIX=12 (typical WEAK_BULL). Use VIX*0.075
                // clamped to [0.60%, 1.50%] so the bot exits at realistic levels instead of
                // holding all day for a target the market never gives.
                double takeProfitPercent = config.getVixScaledTakeProfit(latestVix.getAsDouble());

                // When the multi-level trailing stop has locked in profit (stop > entry),
                // extend the TP ceiling to 2× VIX-scaled TP so the winner can keep running.
                // The trailing stop will eventually close it when momentum stalls.
                if (config.isTrailingTargetsEnabled()) {
                    double trailStop = trailingTargetManager.getCurrentStop(symbol);
                    if (trailStop > entryPrice) {
                        takeProfitPercent = config.getVixScaledTakeProfit(latestVix.getAsDouble()) * 2.0;
                    }
                }

                // Log P&L status for each position
                logger.info("{} {} P&L check: current=+{}% vs target=+{}% (entry=${}, now=${})",
                    profilePrefix, symbol, String.format("%.2f", pnlPercent),
                    String.format("%.1f", takeProfitPercent),
                    String.format("%.2f", entryPrice), String.format("%.2f", currentPrice));

                // Check for take-profit trigger
                if (pnlPercent >= takeProfitPercent) {
                    logger.info("{} {} TAKE PROFIT HIT: Entry=${}, Current=${}, P&L=+{}% (target: +{}%)",
                        profilePrefix, symbol, entryPrice, currentPrice,
                        String.format("%.2f", pnlPercent), String.format("%.1f", takeProfitPercent));

                    logger.info("{} Attempting to place SELL order for {} qty={}", profilePrefix, symbol, qty);

                    try {
                        // CRITICAL: Cancel any existing orders for this symbol first
                        // to free up held shares
                        logger.info("{} Canceling any existing orders for {} to free up shares", profilePrefix, symbol);
                        try {
                            var openOrders = client.getOpenOrders(symbol);
                            for (var order : openOrders) {
                                String orderId = order.get("id").asText();
                                client.cancelOrder(orderId);
                                logger.info("{} Canceled order {} for {}", profilePrefix, orderId, symbol);
                            }
                        } catch (Exception cancelEx) {
                            logger.warn("{} No existing orders to cancel for {}: {}", profilePrefix, symbol, cancelEx.getMessage());
                        }

                        // Use smart order type for take-profit exits (limit is fine here)
                        var tpCtx = new OrderContext(
                            symbol, "sell", currentPrice, latestEquity.getAsDouble(), latestVix.getAsDouble(), latestRegime.get(),
                            profile.strategyType(), true, false, false
                        );
                        var tpDecision = orderTypeSelector.selectOrderType(tpCtx);

                        logger.info("{} Calling client.placeOrder({}, {}, sell, {}, {}, {})", profilePrefix, symbol, qty,
                            tpDecision.orderType(), tpDecision.timeInForce(), tpDecision.limitPrice());
                        client.placeOrder(symbol, qty, "sell", tpDecision.orderType(), tpDecision.timeInForce(), tpDecision.limitPrice());
                        TradingWebSocketHandler.broadcastOrderUpdate(
                            profile.name(), symbol, qty, "sell", tpDecision.orderType(), "filled", currentPrice);
                        logger.info("{} ✅ Order API call completed for {}", profilePrefix, symbol);

                        // Calculate actual P&L in dollars
                        double pnlDollars = (currentPrice - entryPrice) * qty;

                        // Record trade close
                        // Set cooldown BEFORE clearing position to close race window between
                        // MAIN and EXPERIMENTAL profiles (position cleared → cooldown set gap = re-buy risk)
                        riskGate.stopLossCooldowns().put(symbol, System.currentTimeMillis() + config.getStopLossCooldownMs());
                        riskGate.consecutiveStopLosses().remove(symbol);
                        riskGate.lastExitPrices().remove(symbol);
                        if (riskGate.postLossCooldown() != null) riskGate.postLossCooldown().recordWin(symbol);
                        CircuitBreakerState cbTp = riskGate.circuitBreakers().get(brokerName);
                        if (cbTp != null) cbTp.recordTrade(pnlDollars);

                        database.closeTrade(symbol, Instant.now(), currentPrice, pnlDollars, brokerName, "take_profit");
                        portfolio.setPosition(symbol, Optional.empty());
                        clearPositionTracking(symbol);

                        // Mark as pending exit to prevent duplicate sell on next cycle
                        riskGate.pendingExitOrders().put(brokerName + ":" + symbol, System.currentTimeMillis());

                        TradingWebSocketHandler.broadcastActivity(
                            String.format("[%s] ✅ TAKE PROFIT: %s sold @ $%.2f (+%.2f%%, $%.2f profit)",
                                profile.name(), symbol, currentPrice, pnlPercent, pnlDollars),
                            "SUCCESS"
                        );

                        logger.info("{} ✅ Take profit exit order placed for {}", profilePrefix, symbol);
                    } catch (PDTRejectedException e) {
                        riskGate.setStaticPdtBlockedUntil(System.currentTimeMillis() + millisUntilMarketClose());
                        logger.warn("{} PDT rejected by Alpaca for {} — blocking sell attempts until market close",
                            profilePrefix, symbol);
                        continue; // try remaining positions — non-day-trade sells may still succeed
                    } catch (Exception e) {
                        logger.error("{} ❌ FAILED to place take profit exit for {} - Exception: {}",
                            profilePrefix, symbol, e.getClass().getName(), e);
                        logger.error("{} Error message: {}", profilePrefix, e.getMessage());
                        // Stack trace logged at debug level to avoid log noise
                        logger.debug("{} Full stack trace:", profilePrefix, e);
                    }
                }
                // NOTE: Stop-loss exits are intentionally NOT checked here.
                // checkAllPositionsForRiskExits() handles stops via evaluateExit() → isStopLossHit()
                // using the position's stored ATR-based stop price. Adding a second flat-% stop here
                // creates two competing triggers with different thresholds — the flat % fires first
                // and overrides the ATR stop (ORCL Jul 10 2026: ATR stop -5% but flat 1% stop triggered
                // at -1.44%, taking a $2.58 loss that the ATR stop would have held through).
            }
        } catch (Exception e) {
            logger.error("{} Error checking positions for profit targets: {}", profilePrefix, e.getMessage(), e);
        }
    }

    /**
     * Check and apply breakeven stop: move stop loss to entry price when position reaches trigger profit.
     * Cancels both stop and take-profit orders from the original bracket, then places a single GTC stop
     * at entry price. Uses GTC so the stop survives a bot restart. Runs only once per position —
     * subsequent cycles skip via breakevenStopsActive to avoid hammering the Alpaca orders API.
     */
    void checkBreakevenStop(String profilePrefix, String symbol, double entryPrice, double pnlPercent, double qty) {
        if (!config.isBreakevenStopEnabled()) {
            return;
        }
        if (breakevenStopsActive.contains(symbol)) {
            return; // Already placed — don't cancel+replace every cycle
        }

        double triggerPercent = config.getBreakevenTriggerPercent();

        if (pnlPercent >= triggerPercent) {
            logger.info("{} Breakeven stop triggered for {} at +{}% (trigger: +{}%)",
                profilePrefix, symbol, String.format("%.2f", pnlPercent), triggerPercent);

            try {
                // Cancel ALL sell-side orders (stop/stop_limit from original bracket SL leg,
                // and any limit sell from the bracket TP leg) before placing the new stop.
                // Leaving the TP limit active creates two competing sell orders.
                var openOrders = client.getOpenOrders(symbol);
                for (var order : openOrders) {
                    String orderType = order.path("type").asText("");
                    String orderSide = order.path("side").asText("");
                    boolean isSellStop = "stop".equals(orderType) || "stop_limit".equals(orderType);
                    boolean isSellLimit = "limit".equals(orderType) && "sell".equals(orderSide);
                    if (isSellStop || isSellLimit) {
                        String orderId = order.path("id").asText();
                        client.cancelOrder(orderId);
                        logger.info("{} Canceled {} order {} for {} (replacing with breakeven stop)",
                            profilePrefix, orderType, orderId, symbol);
                    }
                }

                // placeNativeStopOrder handles fractional→DAY / whole→GTC automatically
                client.placeNativeStopOrder(symbol, qty, entryPrice);
                breakevenStopsActive.add(symbol);

                logger.info("{} ✅ Breakeven GTC stop placed for {} at ${} (entry price)",
                    profilePrefix, symbol, String.format("%.2f", entryPrice));

                TradingWebSocketHandler.broadcastActivity(
                    String.format("[%s] 🛡️ BREAKEVEN STOP: %s protected at entry $%.2f",
                        profile.name(), symbol, entryPrice),
                    "INFO"
                );
            } catch (Exception e) {
                logger.error("{} Failed to set breakeven stop for {}: {}",
                    profilePrefix, symbol, e.getMessage());
            }
        }
    }

    /**
     * Retry protective exits that failed in a previous cycle (e.g., Alpaca API was down).
     * Called every cycle so failed exits are retried every ~10 seconds until they succeed.
     * Only MAIN profile drains the queue to avoid duplicate orders.
     */
    void drainUrgentExitQueue(String profilePrefix) {
        // Only MAIN profile drains — prevents both profiles from placing duplicate exit orders
        // for the same symbol when both dequeue the same UrgentExit entry concurrently.
        if (!profile.isMainProfile()) return;
        if (System.currentTimeMillis() < riskGate.staticPdtBlockedUntil()) return;

        for (String key : new java.util.HashSet<>(riskGate.urgentExitQueue().keySet())) {
            RiskGate.UrgentExit exit = riskGate.urgentExitQueue().get(key);
            if (exit == null) continue;
            // Only drain entries owned by THIS broker so multi-broker setups don't cross-fire.
            if (!brokerName.equals(exit.broker())) continue;
            String symbol = exit.symbol();

            long minsWaiting = (System.currentTimeMillis() - exit.firstFailedAt()) / 60000;
            logger.warn("{} 🔄 URGENT EXIT RETRY: {} qty={} reason='{}' ({}m since first fail)",
                profilePrefix, symbol, String.format("%.4f", exit.quantity()), exit.reason(), minsWaiting);

            try {
                // Check if position still exists — native stop may have already filled it.
                // Always use the LIVE qty from broker, not the stale internal qty, to avoid
                // "insufficient qty available" errors when position was partially filled externally.
                var positions = client.getPositions();
                var livePos = positions.stream().filter(p -> p.symbol().equals(symbol)).findFirst();
                if (livePos.isEmpty()) {
                    riskGate.urgentExitQueue().remove(key);
                    logger.info("{} Urgent exit cleared: {} position no longer on broker", profilePrefix, symbol);
                    continue;
                }
                double liveQty = livePos.get().quantity();
                if (Math.abs(liveQty - exit.quantity()) > 0.001) {
                    logger.warn("{} Urgent exit qty mismatch for {}: internal={} broker={} — using broker qty",
                        profilePrefix, symbol, String.format("%.4f", exit.quantity()), String.format("%.4f", liveQty));
                }

                cancelExistingOrders(profilePrefix, symbol);
                client.placeOrderDirect(symbol, liveQty, "sell", "market", "day", null);

                riskGate.urgentExitQueue().remove(key);
                riskGate.pendingExitOrders().put(brokerName + ":" + symbol, System.currentTimeMillis());

                TradingWebSocketHandler.broadcastActivity(
                    String.format("[%s] ✅ URGENT EXIT SUCCEEDED: %s after %dm delay (%s)",
                        profile.name(), symbol, minsWaiting, exit.reason()),
                    "WARN"
                );
                logger.info("{} ✅ Urgent exit succeeded for {} after {}m", profilePrefix, symbol, minsWaiting);

            } catch (PDTRejectedException e) {
                riskGate.setStaticPdtBlockedUntil(System.currentTimeMillis() + millisUntilMarketClose());
                logger.warn("{} PDT rejected urgent exit for {} — blocking until market close. Positions protected by native GTC stops.",
                    profilePrefix, symbol);
                TradingWebSocketHandler.broadcastActivity(
                    String.format("[%s] ⛔ PDT LIMIT: Urgent exit for %s blocked — native stops protecting positions until tomorrow.",
                        profile.name(), symbol), "ERROR");
                break;
            } catch (Exception e) {
                logger.warn("{} Urgent exit retry still failing for {}: {}", profilePrefix, symbol, e.getMessage());
                TradingWebSocketHandler.broadcastActivity(
                    String.format("[%s] ⚠️ URGENT EXIT RETRY FAILED: %s (%dm waiting, will retry) — %s",
                        profile.name(), symbol, minsWaiting, e.getMessage()),
                    "ERROR"
                );
            }
        }
    }

    /**
     * Clean up excess positions when over limit.
     * Closes the weakest (most losing) positions first.
     */
    void cleanupExcessPositions(String profilePrefix) throws Exception {
        int maxPositions = config.getMaxPositionsAtOnce();
        int currentPositions = portfolio.getActivePositionCount();

        if (currentPositions <= maxPositions) {
            return; // No cleanup needed
        }

        logger.warn("{} 🧹 Cleanup: {} positions, max is {}",
            profilePrefix, currentPositions, maxPositions);

        // Get all positions sorted by P&L (worst first)
        var positions = client.getPositions();
        var sortedPositions = positions.stream()
            .sorted((a, b) -> Double.compare(
                a.unrealizedPL(),
                b.unrealizedPL()
            ))
            .toList();

        // Close worst positions until we're at limit
        int toClose = currentPositions - maxPositions;
        for (int i = 0; i < toClose && i < sortedPositions.size(); i++) {
            var pos = sortedPositions.get(i);
            String symbol = pos.symbol();
            double qty = pos.quantity();
            double pnl = pos.unrealizedPL();

            // Guard against zero quantity
            if (qty == 0) {
                logger.debug("{} Skipping zero-quantity position during cleanup: {}", profilePrefix, symbol);
                continue;
            }

            logger.info("{} 🧹 Closing weakest position: {} (P&L: ${})",
                profilePrefix, symbol, String.format("%.2f", pnl));

            try {
                cancelExistingOrders(profilePrefix, symbol);
                client.placeOrder(symbol, qty, "sell", "market", "day", null);
                portfolio.setPosition(symbol, Optional.empty());
                clearPositionTracking(symbol);

                TradingWebSocketHandler.broadcastActivity(
                    String.format("[%s] 🧹 CLEANUP: Closed %s (P&L: $%.2f) - reducing to %d positions",
                        profile.name(), symbol, pnl, maxPositions),
                    "INFO"
                );

                // Record trade close and update daily risk tracking
                double currentPrice = Math.abs(pos.marketValue() / qty);
                database.closeTrade(symbol, Instant.now(), currentPrice, pnl, brokerName, "max_positions_cleanup");
                updateDailyPnLFn.accept(profilePrefix, pnl);
                CircuitBreakerState cb = riskGate.circuitBreakers().get(brokerName);
                if (cb != null) cb.recordTrade(pnl);

            } catch (Exception e) {
                logger.error("{} Failed to close {} during cleanup", profilePrefix, symbol, e);
            }
        }
    }

    /**
     * Check if it's time for end-of-day exit and close all positions.
     * Only MAIN profile executes this to avoid duplicate orders.
     */
    void checkAndExecuteEodExit(String profilePrefix) {
        // Only MAIN profile should execute EOD exits
        if (!profile.isMainProfile()) {
            return;
        }

        try {
            // Get current time in ET timezone
            var now = java.time.ZonedDateTime.now(java.time.ZoneId.of("America/New_York"));
            var today = now.toLocalDate();
            var currentTime = now.toLocalTime();

            // Parse EOD exit time from config (e.g., "15:30")
            var eodTimeStr = config.getEodExitTime();
            var eodTime = java.time.LocalTime.parse(eodTimeStr);

            // Fire once per day, as soon as currentTime passes eodTime.
            // Previous approach used a ±1-minute window which caused every 10-second cycle to
            // cancel-then-replace the sell order before it could fill (self-cancelling loop).
            if (currentTime.isBefore(eodTime)) {
                return; // not yet time
            }
            if (eodExitExecutedDate != null && eodExitExecutedDate.equals(today)) {
                return; // already ran today
            }

            logger.warn("{} ⏰ END OF DAY EXIT TIME ({}) - Closing all positions", profilePrefix, eodTimeStr);

            // Get all open positions
            var positions = client.getPositions();

            if (positions.isEmpty()) {
                eodExitExecutedDate = today;
                logger.info("{} No positions to close for EOD", profilePrefix);
                return;
            }

            logger.warn("{} 🔴 Closing {} position(s) for end of day", profilePrefix, positions.size());

            // Close each position
            for (var position : positions) {
                String symbol = position.symbol();
                double qty = Math.abs(position.quantity());
                double marketValue = position.marketValue();
                double entryPrice = position.avgEntryPrice();

                if (qty == 0 || entryPrice == 0) {
                    logger.debug("{} Skipping invalid position during EOD exit: {} (qty={}, entry={})",
                        profilePrefix, symbol, qty, entryPrice);
                    continue;
                }

                double currentPrice = Math.abs(marketValue / qty);
                double pnl = (currentPrice - entryPrice) * qty;
                double pnlPercent = ((currentPrice - entryPrice) / entryPrice) * 100;

                // No overnight carries — close everything at EOD every day.

                logger.warn("{} 📊 EOD EXIT: {} - Qty: {}, Entry: ${}, Current: ${}, P&L: ${} ({}%)",
                    profilePrefix, symbol, qty, entryPrice, currentPrice, pnl, String.format("%.2f", pnlPercent));

                try {
                    // Cancel only STOP/STOP_LIMIT orders — never cancel pending sell orders.
                    // The old code cancelled ALL open orders, which could cancel a market sell
                    // placed by a prior 10-second cycle before it filled (self-cancelling loop).
                    var openOrders = client.getOpenOrders(symbol);
                    for (var order : openOrders) {
                        String orderType = order.get("type") != null ? order.get("type").asText() : "";
                        String orderSide = order.get("side") != null ? order.get("side").asText() : "";
                        if (("stop".equals(orderType) || "stop_limit".equals(orderType)) && "sell".equals(orderSide)) {
                            String orderId = order.get("id").asText();
                            logger.info("{} Canceling stop order {} for {} before EOD exit",
                                profilePrefix, orderId, symbol);
                            client.cancelOrder(orderId);
                        }
                    }

                    logger.warn("{} 🔴 EOD SELL: {} - {} shares @ market", profilePrefix, symbol, qty);
                    client.placeOrder(symbol, qty, "sell", "market", "day", null);
                    TradingWebSocketHandler.broadcastOrderUpdate(
                        profile.name(), symbol, qty, "sell", "market", "filled", currentPrice);
                    portfolio.setPosition(symbol, Optional.empty());
                    clearPositionTracking(symbol);
                    // Close DB record immediately so hasOpenTrade() returns false on the next
                    // cycle. Without this, orphan cleanup runs 1-2 cycles later, leaving a window
                    // where isGoodEntryTime() is true + hasOpenTrade() is false → re-entry.
                    database.closeTrade(symbol, Instant.now(), currentPrice, pnl, brokerName, "eod_cleanup");

                    TradingWebSocketHandler.broadcastActivity(
                        String.format("[%s] EOD EXIT: %s - Closed %.3f shares | P&L: $%.2f (%.2f%%)",
                            profile.name(), symbol, qty, pnl, pnlPercent),
                        pnl >= 0 ? "SUCCESS" : "WARNING"
                    );
                    logger.warn("{} ✅ EOD EXIT completed for {}", profilePrefix, symbol);

                } catch (Exception e) {
                    logger.error("{} ❌ Failed to execute EOD exit for {}: {}",
                        profilePrefix, symbol, e.getMessage(), e);
                    TradingWebSocketHandler.broadcastActivity(
                        String.format("[%s] EOD EXIT FAILED: %s - %s",
                            profile.name(), symbol, e.getMessage()),
                        "ERROR"
                    );
                }
            }

            // Mark EOD exit done only if all positions are confirmed closed.
            // If any sell failed, eodExitExecutedDate stays null → retries every 10s
            // until market closes at 16:00 (30-minute retry window).
            var remaining = client.getPositions();
            if (remaining.isEmpty()) {
                eodExitExecutedDate = today;
                logger.warn("{} ✅ END OF DAY EXIT COMPLETE - All positions closed", profilePrefix);
                // Write daily summary to DB and send push notification (async, non-blocking).
                String eodRegime = latestRegime.get() != null ? latestRegime.get().name() : "UNKNOWN";
                double eodVix = latestVix.getAsDouble();
                String eodDate = today.toString();
                Thread.ofVirtual().name("eod-summary").start(() -> {
                    try {
                        var counts = database.getTodayTradeCounts();
                        double netPnl = database.getTodayPnL();
                        int blocked = database.getTodayBlockedCount();
                        database.saveDailySummary(eodDate, counts[0], counts[1], counts[2],
                            netPnl, eodRegime, eodVix, blocked);
                        // ntfy.sh push notification — set NTFY_TOPIC env var to enable.
                        String ntfyTopic = System.getenv("NTFY_TOPIC");
                        if (ntfyTopic != null && !ntfyTopic.isBlank()) {
                            String msg = String.format(
                                "[%s] EOD %s: %d trades (%d✓ %d✗) P&L $%.2f | regime=%s blocked=%d",
                                profile.name(), eodDate, counts[0], counts[1], counts[2],
                                netPnl, eodRegime, blocked);
                            try {
                                var req = java.net.http.HttpRequest.newBuilder()
                                    .uri(java.net.URI.create("https://ntfy.sh/" + ntfyTopic))
                                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(msg))
                                    .header("Title", "Trading Bot EOD")
                                    .header("Priority", netPnl >= 0 ? "default" : "high")
                                    .build();
                                java.net.http.HttpClient.newHttpClient()
                                    .send(req, java.net.http.HttpResponse.BodyHandlers.discarding());
                                logger.info("[DAILY_SUMMARY] ntfy push sent: {}", msg);
                            } catch (Exception ntfyEx) {
                                logger.warn("ntfy push failed: {}", ntfyEx.getMessage());
                            }
                        }
                    } catch (Exception ex) {
                        logger.warn("EOD summary thread failed: {}", ex.getMessage());
                    }
                });

                // Run end-of-session Claude review after all exits are confirmed complete.
                // Async — non-blocking so EOD latency is unaffected.
                String claudeApiKey = System.getenv("CLAUDE_API_KEY");
                if (claudeApiKey != null && !claudeApiKey.isBlank()) {
                    String regimeName = latestRegime.get() != null ? latestRegime.get().name() : "UNKNOWN";
                    double vix = latestVix.getAsDouble();
                    Thread.ofVirtual().name("claude-eod-review").start(() -> {
                        try {
                            var reviewer = new com.trading.ai.ClaudeSessionReviewer(database, claudeApiKey, configSelfHealer);
                            reviewer.runEndOfSessionReview(regimeName, vix);
                        } catch (Exception ex) {
                            logger.warn("EOD Claude review thread failed: {}", ex.getMessage());
                        }
                    });
                }
            } else {
                logger.error("{} ⚠️ EOD EXIT INCOMPLETE - {} position(s) failed to close, will retry",
                    profilePrefix, remaining.size());
            }

        } catch (Exception e) {
            logger.error("{} Error during EOD exit check: {}", profilePrefix, e.getMessage(), e);
        }
    }

    /**
     * Returns milliseconds until the PDT day-trade count resets.
     * - During market hours (before 4PM ET): block until today's close.
     * - After market close: block only until next market OPEN (9:30 AM ET next weekday).
     *   This prevents the block from lasting an entire extra trading day when a PDT
     *   rejection occurs after hours (e.g. during an urgent-exit retry loop post-deploy).
     *
     * Package-private (not private) — the one remaining PDT-catch site still on ProfileManager
     * (in the per-symbol target-scanning loop) calls this via the exitEvaluator field too, so
     * both sides compute the same "how long to block sells" value.
     */
    long millisUntilMarketClose() {
        var NY = java.time.ZoneId.of("America/New_York");
        var now = java.time.ZonedDateTime.now(NY);
        var closeToday = now.toLocalDate().atTime(16, 0).atZone(NY);
        if (now.isBefore(closeToday)) {
            return Duration.between(now, closeToday).toMillis();
        }
        var nextOpen = closeToday.toLocalDate().plusDays(1).atTime(9, 30).atZone(NY);
        while (nextOpen.getDayOfWeek() == java.time.DayOfWeek.SATURDAY ||
               nextOpen.getDayOfWeek() == java.time.DayOfWeek.SUNDAY) {
            nextOpen = nextOpen.plusDays(1);
        }
        return Duration.between(now, nextOpen).toMillis();
    }
}
