package com.trading.portfolio;

import com.trading.risk.CircuitBreakerState;
import com.trading.risk.PostLossCooldownTracker;
import com.trading.earnings.EarningsCalendarService;

import java.time.LocalDate;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Owns the risk/coordination state that used to live directly on {@link ProfileManager} as
 * ~20 {@code static} fields — extracted 2026-08-30 as the first step of splitting that class
 * up (see the "INSTANCE STATE" comment block left in ProfileManager.java for the full history
 * of why this state existed and why it stopped being {@code static}).
 *
 * <p><b>Scope of this extraction:</b> this is a pure state relocation, not a redesign. Every
 * field here is exposed through a simple accessor that returns the live, mutable collection or
 * value — exactly matching how {@code ProfileManager} read and wrote these fields directly
 * before this class existed. The call sites in {@code ProfileManager} were mechanically changed
 * from {@code fieldName} to {@code riskGate.fieldName()}; the mutation logic (which symbol gets
 * a cooldown, when a circuit breaker trips, etc.) still lives in {@code ProfileManager} and was
 * deliberately left untouched, so this extraction can be verified as behavior-identical by the
 * existing test suite rather than requiring new tests for redesigned logic. Turning these into
 * a cleaner domain API (e.g. {@code canEnter(symbol)}, {@code recordStopLoss(symbol)}) is real
 * follow-up work, not bundled here — doing both at once would make a regression much harder to
 * isolate if one showed up.
 *
 * <p>One {@code ProfileManager} owns exactly one {@code RiskGate} (this deployment runs a single
 * broker/profile — see the INSTANCE STATE comment). It is not a singleton and holds no static
 * state itself.
 */
final class RiskGate {

    // Re-entry cooldown after ANY sell (stop loss, take profit, risk exit, etc.)
    // Key = symbol, Value = timestamp when cooldown expires
    private final ConcurrentHashMap<String, Long> stopLossCooldowns = new ConcurrentHashMap<>();

    // Track symbols with pending exit orders to prevent duplicate sells.
    private final ConcurrentHashMap<String, Long> pendingExitOrders = new ConcurrentHashMap<>();

    // Prevent duplicate buy orders placed within the same or back-to-back evaluation cycles.
    // Key = "broker:symbol", value = timestamp of the in-flight buy.
    private final ConcurrentHashMap<String, Long> pendingBuySymbols = new ConcurrentHashMap<>();
    static final long PENDING_BUY_TTL_MS = 5 * 60 * 1000L; // 5 minutes

    // Entry stagger: enforce 90-second minimum spacing between any two new entries.
    private volatile long lastEntryEpochMs = 0L;
    static final long MIN_ENTRY_SPACING_MS = 90_000L; // 90 seconds

    // Active position tracker. Key = symbol, Value = "profileName:brokerName".
    private final ConcurrentHashMap<String, String> globalHeldSymbols = new ConcurrentHashMap<>();

    // Symbols currently held as scalp positions.
    private final Set<String> scalpHeldSymbols = ConcurrentHashMap.newKeySet();

    // Track consecutive stop-loss hits per symbol.
    private final ConcurrentHashMap<String, Integer> consecutiveStopLosses = new ConcurrentHashMap<>();
    static final int MAX_CONSECUTIVE_SL_BEFORE_EXTENDED_COOLDOWN = 2;

    // Track last exit price per symbol.
    private final ConcurrentHashMap<String, Double> lastExitPrices = new ConcurrentHashMap<>();
    static final double MIN_PRICE_IMPROVEMENT_PERCENT = 1.0;

    // Urgent exit queue — symbols whose protective sell failed due to API error.
    private final ConcurrentHashMap<String, UrgentExit> urgentExitQueue = new ConcurrentHashMap<>();

    record UrgentExit(String broker, String symbol, double quantity, String reason, long firstFailedAt) {}

    static String urgentKey(String broker, String symbol) { return broker + ":" + symbol; }

    // Track why buys were most recently blocked per symbol.
    private final ConcurrentHashMap<String, String> blockedBuys = new ConcurrentHashMap<>();

    // Per-symbol post-loss cooldown — Tier 1.1.
    private volatile PostLossCooldownTracker postLossCooldown;

    // Earnings calendar — Tier 2.5.
    private volatile EarningsCalendarService earningsCalendar;

    // Per-broker session circuit breakers — Tier 3.10.
    private final ConcurrentHashMap<String, CircuitBreakerState> circuitBreakers = new ConcurrentHashMap<>();

    // PDT state — kept for backward-compat (always 0/false since PDT abolished June 4 2026)
    private volatile long staticPdtBlockedUntil = 0;
    private volatile int staticDayTradeCount = 0;

    // Scalp trade count for today.
    private final AtomicInteger staticScalpDailyCount = new AtomicInteger(0);
    private volatile LocalDate scalpCountDate = LocalDate.now();

    // Halt state snapshots — updated each cycle, exposed to dashboard.
    private volatile boolean portfolioStopLossHaltActive = false;
    private volatile boolean maxDrawdownHaltActive = false;
    private volatile double latestVixSnapshot = 0.0;
    private volatile String latestRegimeSnapshot = "UNKNOWN";
    private volatile String latestTargetSymbolsSnapshot = "";

    // ── Accessors — one per field, matching the pre-extraction direct-field-access call sites ──

    ConcurrentHashMap<String, Long> stopLossCooldowns() { return stopLossCooldowns; }
    ConcurrentHashMap<String, Long> pendingExitOrders() { return pendingExitOrders; }
    ConcurrentHashMap<String, Long> pendingBuySymbols() { return pendingBuySymbols; }
    ConcurrentHashMap<String, String> globalHeldSymbols() { return globalHeldSymbols; }
    Set<String> scalpHeldSymbols() { return scalpHeldSymbols; }
    ConcurrentHashMap<String, Integer> consecutiveStopLosses() { return consecutiveStopLosses; }
    ConcurrentHashMap<String, Double> lastExitPrices() { return lastExitPrices; }
    ConcurrentHashMap<String, UrgentExit> urgentExitQueue() { return urgentExitQueue; }
    ConcurrentHashMap<String, String> blockedBuys() { return blockedBuys; }
    ConcurrentHashMap<String, CircuitBreakerState> circuitBreakers() { return circuitBreakers; }
    AtomicInteger staticScalpDailyCount() { return staticScalpDailyCount; }

    long lastEntryEpochMs() { return lastEntryEpochMs; }
    void setLastEntryEpochMs(long v) { lastEntryEpochMs = v; }

    PostLossCooldownTracker postLossCooldown() { return postLossCooldown; }
    void setPostLossCooldown(PostLossCooldownTracker v) { postLossCooldown = v; }

    EarningsCalendarService earningsCalendar() { return earningsCalendar; }
    void setEarningsCalendar(EarningsCalendarService v) { earningsCalendar = v; }

    long staticPdtBlockedUntil() { return staticPdtBlockedUntil; }
    void setStaticPdtBlockedUntil(long v) { staticPdtBlockedUntil = v; }

    int staticDayTradeCount() { return staticDayTradeCount; }
    void setStaticDayTradeCount(int v) { staticDayTradeCount = v; }

    LocalDate scalpCountDate() { return scalpCountDate; }
    void setScalpCountDate(LocalDate v) { scalpCountDate = v; }

    boolean portfolioStopLossHaltActive() { return portfolioStopLossHaltActive; }
    void setPortfolioStopLossHaltActive(boolean v) { portfolioStopLossHaltActive = v; }

    boolean maxDrawdownHaltActive() { return maxDrawdownHaltActive; }
    void setMaxDrawdownHaltActive(boolean v) { maxDrawdownHaltActive = v; }

    double latestVixSnapshot() { return latestVixSnapshot; }
    void setLatestVixSnapshot(double v) { latestVixSnapshot = v; }

    String latestRegimeSnapshot() { return latestRegimeSnapshot; }
    void setLatestRegimeSnapshot(String v) { latestRegimeSnapshot = v; }

    String latestTargetSymbolsSnapshot() { return latestTargetSymbolsSnapshot; }
    void setLatestTargetSymbolsSnapshot(String v) { latestTargetSymbolsSnapshot = v; }

    /** Matches the pre-extraction ProfileManager.getUrgentExitQueue() dashboard method exactly. */
    Map<String, String> urgentExitQueueForDashboard() {
        var result = new java.util.LinkedHashMap<String, String>();
        long now = System.currentTimeMillis();
        urgentExitQueue.forEach((key, exit) ->
            result.put(key, String.format("%s (queued %dm ago)", exit.reason(), (now - exit.firstFailedAt()) / 60000)));
        return java.util.Collections.unmodifiableMap(result);
    }
}
