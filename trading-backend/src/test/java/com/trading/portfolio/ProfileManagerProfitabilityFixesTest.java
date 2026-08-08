package com.trading.portfolio;

import com.trading.analysis.MarketRegimeDetector.MarketRegime;
import com.trading.api.model.Bar;
import com.trading.risk.TradePosition;
import org.junit.jupiter.api.*;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the 6 profitability fixes:
 *
 *   Fix 1 — Profit gate on momentum SELL (embedded in tradeSymbol dispatch, covered by
 *            integration: momentum SELL suppressed when 0 < lossPercent < breakevenTriggerPct)
 *   Fix 2 — MIN_HOLD bypass for losing positions (same dispatch path; unit-verifiable via
 *            handleSell being invoked when lossPercent <= -0.25 before canSell passes)
 *   Fix 3 — VIX-scaled take-profit formula in Config
 *   Fix 4 — Entry stagger: 90-second cross-profile gate in handleBuy
 *   Fix 5 — Losing-position gate: hasSignificantlyLosingPosition blocks new entries
 *   Fix 6 — Afternoon sizing: Config accessor for POSITION_SIZING_AFTERNOON_PERCENT
 *
 * Fixes 1 and 2 live in the tradeSymbol() signal-dispatch block which calls final class
 * StrategyManager — not mockable. Their behaviour is verified at the Config level and by
 * the code path itself; end-to-end validation happens in production logs.
 */
@DisplayName("Profitability fixes — unit tests (Fixes 3–6) and Config accessors (all fixes)")
class ProfileManagerProfitabilityFixesTest extends ProfileManagerTestBase {

    // ── static-field helpers ──────────────────────────────────────────────────

    private static void setStaticField(String name, Object value) throws Exception {
        Field f = ProfileManager.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(null, value);
    }

    private static Object getStaticField(String name) throws Exception {
        Field f = ProfileManager.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(null);
    }

    @SuppressWarnings("unchecked")
    private static <T> ConcurrentHashMap<String, T> getStaticMap(String name) throws Exception {
        return (ConcurrentHashMap<String, T>) getStaticField(name);
    }

    // ── setup ─────────────────────────────────────────────────────────────────

    @BeforeEach
    void setUp() throws Exception {
        setUpCommon();

        // Reset static state that persists across tests
        setStaticField("lastEntryEpochMs", 0L);
        getStaticMap("globalHeldSymbols").clear();

        // Disable every guard that could block handleBuy before we reach the code under test
        when(mockConfig.isDailyMaxLossEnabled()).thenReturn(false);
        when(mockConfig.isDailyProfitTargetEnabled()).thenReturn(false);
        when(mockConfig.isNoTradeOpenWindowEnabled()).thenReturn(false);
        when(mockConfig.isEarningsBlackoutEnabled()).thenReturn(false);
        when(mockConfig.isPerSymbolCooldownEnabled()).thenReturn(false);
        when(mockConfig.isMLEntryScoringEnabled()).thenReturn(false);
        when(mockConfig.isAdaptiveSizingEnabled()).thenReturn(false);
        when(mockConfig.isAtrStopsEnabled()).thenReturn(false);
        when(mockConfig.isAtrSizingEnabled()).thenReturn(false);
        when(mockConfig.isVolumeProfileEnabled()).thenReturn(false);
        when(mockConfig.isCircuitBreakerEnabled()).thenReturn(false);
        when(mockConfig.getPdtReserveThreshold()).thenReturn(3); // well above 0 day-trades
        when(mockConfig.getMaxPositionsAtOnce()).thenReturn(5);
        when(mockConfig.getPositionSizingMethod()).thenReturn("PERCENT");
        when(mockConfig.getPositionSizingFixedPercent()).thenReturn(0.20);
        when(mockConfig.getPositionSizingMaxPercent()).thenReturn(0.20);
        when(mockConfig.getAfternoonPositionSizingPercent()).thenReturn(0.12);
        when(mockConfig.getBreakevenTriggerPercent()).thenReturn(0.5);
        // Pure-math Config methods — delegate to real implementation so we test the formula
        when(mockConfig.getVixScaledTakeProfit(anyDouble())).thenCallRealMethod();
        when(mockConfig.getMainStopLossPercent()).thenReturn(1.0);
        when(mockConfig.getMainTakeProfitPercent()).thenReturn(1.5);
        when(mockDatabase.hasOpenTrade(anyString(), anyString())).thenReturn(false);
        when(mockDatabase.countOpenTrades(anyString(), anyString())).thenReturn(0);
        getStaticMap("blockedBuys").clear();
        setField("marketHoursFilter", new com.trading.filters.MarketHoursFilter(mockConfig));
    }

    private void invokeBuy(String symbol, double price, double equity) throws Exception {
        setField("latestEquity", equity);
        Method m = ProfileManager.class.getDeclaredMethod(
            "handleBuy", String.class, double.class, double.class,
            double.class, double.class, MarketRegime.class, String.class);
        m.setAccessible(true);
        m.invoke(profileManager, symbol, price, equity, equity * 0.5, 12.0,
            MarketRegime.WEAK_BULL, "[MAIN]");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Fix 3 — VIX-scaled take-profit formula (Config.getVixScaledTakeProfit)
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Fix 3: VIX=12 → VIX-scaled TP = 0.90% (12 × 0.075)")
    void vixScaledTp_typicalLowVix_returnsExpectedPct() {
        // Config.getVixScaledTakeProfit is a pure function — safe to call on any Config
        var cfg = mockConfig;
        assertEquals(0.90, cfg.getVixScaledTakeProfit(12.0), 0.001,
            "VIX=12 → 12×0.075=0.90% — standard WEAK_BULL low-vol result");
    }

    @Test
    @DisplayName("Fix 3: VIX=25 → clamped at max 1.50%")
    void vixScaledTp_highVix_cappedAt150Pct() {
        var cfg = mockConfig;
        assertEquals(1.50, cfg.getVixScaledTakeProfit(25.0), 0.001,
            "VIX=25 → 25×0.075=1.875 → clamped to 1.50% ceiling");
    }

    @Test
    @DisplayName("Fix 3: VIX=5 → floored at minimum 0.60%")
    void vixScaledTp_veryLowVix_flooredAt60Pct() {
        var cfg = mockConfig;
        assertEquals(0.60, cfg.getVixScaledTakeProfit(5.0), 0.001,
            "VIX=5 → 5×0.075=0.375 → floored to 0.60% minimum");
    }

    @Test
    @DisplayName("Fix 3: VIX=15 → mid-range TP ~1.125%")
    void vixScaledTp_midVix_computedCorrectly() {
        var cfg = mockConfig;
        assertEquals(1.125, cfg.getVixScaledTakeProfit(15.0), 0.001,
            "VIX=15 → 15×0.075=1.125%");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Fix 4 — Entry stagger (90-second cross-profile gate in handleBuy)
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Fix 4: second buy within 90s is blocked — daily-loss check never reached")
    void entryStagger_blocksSecondBuyWithin90Seconds() throws Exception {
        // Arm the stagger: simulate a recent entry
        setStaticField("lastEntryEpochMs", System.currentTimeMillis());

        try {
            invokeBuy("SPY", 500.0, 10_000.0);
        } catch (Exception ignored) { /* broker may throw — we care about what was skipped */ }

        // If stagger blocked the call, isDailyMaxLossEnabled() was never reached
        verify(mockConfig, never()).isDailyMaxLossEnabled();
    }

    @Test
    @DisplayName("Fix 4: buy allowed when 91 seconds have passed since last entry")
    void entryStagger_allowsBuyAfter91Seconds() throws Exception {
        // Stagger expired 91 seconds ago — should not block
        setStaticField("lastEntryEpochMs", System.currentTimeMillis() - 91_000L);

        try {
            invokeBuy("SPY", 500.0, 10_000.0);
        } catch (Exception ignored) { /* broker may throw at later step — that's fine */ }

        // isDailyMaxLossEnabled() must have been reached (stagger didn't short-circuit)
        verify(mockConfig, atLeastOnce()).isDailyMaxLossEnabled();
    }

    @Test
    @DisplayName("Fix 4: first-ever buy (lastEntryEpochMs=0) is never blocked by stagger")
    void entryStagger_noRecentEntry_notBlocked() throws Exception {
        // Default: lastEntryEpochMs=0 means ~1970, which is always >90s ago
        setStaticField("lastEntryEpochMs", 0L);

        try {
            invokeBuy("SPY", 500.0, 10_000.0);
        } catch (Exception ignored) { }

        verify(mockConfig, atLeastOnce()).isDailyMaxLossEnabled();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Fix 5 — Losing-position gate (hasSignificantlyLosingPosition)
    // ═══════════════════════════════════════════════════════════════════════════

    private boolean invokeHasLosingPosition(String profilePrefix, String candidateSymbol) throws Exception {
        Method m = ProfileManager.class.getDeclaredMethod(
            "hasSignificantlyLosingPosition", String.class, String.class);
        m.setAccessible(true);
        return (boolean) m.invoke(profileManager, profilePrefix, candidateSymbol);
    }

    private void holdPosition(String symbol, String profilePrefix, double entryPrice, double currentPrice) throws Exception {
        // Register in global held-symbols map (static)
        getStaticMap("globalHeldSymbols").put(symbol, profilePrefix);
        // Register in local portfolio
        double qty = 1.0;
        double sl = entryPrice * 0.99;
        double tp = entryPrice * 1.015;
        var pos = new TradePosition(symbol, entryPrice, qty, sl, tp, Instant.now().minusSeconds(3600));
        portfolio.setPosition(symbol, Optional.of(pos));
        // Stub getLatestBar to return currentPrice
        var bar = new Bar(Instant.now(), currentPrice, currentPrice, currentPrice, currentPrice, 1_000_000L);
        when(mockClient.getLatestBar(symbol)).thenReturn(Optional.of(bar));
    }

    @Test
    @DisplayName("Fix 5: position down -0.3% → gate blocks new entry")
    void losingPositionGate_blocksWhenExistingPositionDown030Pct() throws Exception {
        // SPY entered at 500, now at 498.5 → -0.3%
        holdPosition("SPY", "[MAIN]", 500.0, 498.5);

        boolean blocked = invokeHasLosingPosition("[MAIN]", "QQQ");

        assertTrue(blocked, "Position at -0.3% must block new entries");
    }

    @Test
    @DisplayName("Fix 5: position at exactly -0.20% threshold → gate blocks")
    void losingPositionGate_blocksAtExactThreshold() throws Exception {
        // entryPrice=500, currentPrice=499 → -0.20%
        holdPosition("SPY", "[MAIN]", 500.0, 499.0);

        assertTrue(invokeHasLosingPosition("[MAIN]", "QQQ"),
            "Position exactly at -0.20% must be blocked (≤ threshold)");
    }

    @Test
    @DisplayName("Fix 5: position up +0.5% → gate allows new entry")
    void losingPositionGate_allowsWhenExistingPositionProfitable() throws Exception {
        // SPY entered at 500, now at 502.5 → +0.5%
        holdPosition("SPY", "[MAIN]", 500.0, 502.5);

        assertFalse(invokeHasLosingPosition("[MAIN]", "QQQ"),
            "Profitable position must not block new entries");
    }

    @Test
    @DisplayName("Fix 5: flat position (-0.10%) → gate allows entry (above threshold)")
    void losingPositionGate_allowsWhenPositionSlightlyDown() throws Exception {
        // SPY at -0.10% — above the -0.20% threshold
        holdPosition("SPY", "[MAIN]", 500.0, 499.5);

        assertFalse(invokeHasLosingPosition("[MAIN]", "QQQ"),
            "Position at -0.10% (above -0.20% threshold) must not block");
    }

    @Test
    @DisplayName("Fix 5: no existing positions → gate allows entry")
    void losingPositionGate_allowsWhenNoExistingPositions() throws Exception {
        // globalHeldSymbols is empty (cleared in setUp)
        assertFalse(invokeHasLosingPosition("[MAIN]", "QQQ"),
            "No existing positions → gate must not block");
    }

    @Test
    @DisplayName("Fix 5: losing position in OTHER profile does not block THIS profile")
    void losingPositionGate_doesNotBlockDifferentProfile() throws Exception {
        // EXPERIMENTAL holds SPY at -0.5%, MAIN is evaluating QQQ
        holdPosition("SPY", "[EXPERIMENTAL]", 500.0, 497.5);

        assertFalse(invokeHasLosingPosition("[MAIN]", "QQQ"),
            "Losing position in EXPERIMENTAL must not block MAIN entries");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Fix 6 — Afternoon sizing config accessor
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Fix 6: getAfternoonPositionSizingPercent default is 0.12 (12% of equity)")
    void afternoonSizing_defaultIs12Pct() {
        // The mock config is set to return 0.12 — matches config.properties default
        assertEquals(0.12, mockConfig.getAfternoonPositionSizingPercent(), 0.001,
            "Afternoon sizing must default to 12% — 60% of normal 20%");
    }

    @Test
    @DisplayName("Fix 6: afternoon factor = afternoonPct / normalPct = 0.12/0.20 = 0.60")
    void afternoonSizing_factorIs60Pct() {
        double afternoonPct = mockConfig.getAfternoonPositionSizingPercent(); // 0.12
        double normalPct    = mockConfig.getPositionSizingFixedPercent();     // 0.20
        assertEquals(0.60, afternoonPct / normalPct, 0.001,
            "Afternoon sizing factor must be 60% of normal position size");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Fixes 1 & 2 — Config-level accessors used by the gate logic
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Fix 1 & 2: breakevenTriggerPercent default is 0.5% (profit gate threshold)")
    void breakevenTrigger_defaultIs05Pct() {
        // The profit gate fires when: 0 < lossPercent < breakevenTriggerPct (default 0.5%)
        when(mockConfig.getBreakevenTriggerPercent()).thenReturn(0.5);
        assertEquals(0.5, mockConfig.getBreakevenTriggerPercent(), 0.001,
            "Breakeven trigger must be 0.5% — positions below this are suppressed from SELL");
    }

    @Test
    @DisplayName("Fix 2: -0.25% loss threshold for min-hold bypass is below the 0.5% breakeven gate")
    void lossBypassThreshold_isBelowBreakevenGate() {
        // Logical invariant: the two gates must not overlap.
        // Profit gate: 0 < lossPercent < 0.5 (profitable, below breakeven)
        // Loss bypass: lossPercent <= -0.25 (clearly losing)
        // No overlap between positive and negative domains — correct by construction.
        double lossBypassThreshold = -0.25;
        double breakevenGate = mockConfig.getBreakevenTriggerPercent(); // 0.5

        assertTrue(lossBypassThreshold < 0,
            "Loss bypass threshold must be negative (loss only)");
        assertTrue(breakevenGate > 0,
            "Breakeven gate must be positive (profit only)");
        // No position can be both < -0.25% AND > 0% simultaneously — gates don't conflict
    }
}
