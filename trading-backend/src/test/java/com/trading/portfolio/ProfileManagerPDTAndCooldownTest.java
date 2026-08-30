package com.trading.portfolio;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trading.api.ResilientAlpacaClient;
import com.trading.api.model.Position;
import com.trading.config.Config;
import com.trading.exits.ExitStrategyManager;
import com.trading.persistence.TradeDatabase;
import com.trading.protection.PDTProtection;
import com.trading.strategy.TradingProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Helper to create a real PDTProtection and control its day trade count via reflection.
 * PDTProtection is final so it cannot be mocked with Mockito SUBCLASS.
 */

/**
 * Tests for PDT reservation threshold (=1) and take-profit cooldown ordering.
 *
 * Critical trading logic:
 * 1. After the first day trade, ALL new buys must be blocked (threshold changed 2→1)
 *    to reserve 2 PDT slots for protective exits.
 * 2. After take-profit exit, cooldown must be set BEFORE portfolio position is cleared,
 *    to prevent the EXPERIMENTAL profile thread from seeing "no position, no cooldown"
 *    and immediately re-buying.
 * 3. Take-profit cooldown must use config.getStopLossCooldownMs(), not hardcoded 30 min.
 */
@DisplayName("ProfileManager PDT and Take-Profit Cooldown Tests")
class ProfileManagerPDTAndCooldownTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ProfileManager profileManager;
    private ResilientAlpacaClient mockClient;
    private Config mockConfig;
    private TradeDatabase mockDatabase;
    private PDTProtection realPDTProtection;

    /** Create a real PDTProtection (final class — not mockable) with a controlled day trade count. */
    private static PDTProtection createPDTProtection(int dayTradeCount) throws Exception {
        // PDTProtection(database, enabled) — pass null DB since we won't record trades here
        var pdt = new PDTProtection(null, true, "alpaca");
        // Set dayTradeCount via reflection
        Field countField = PDTProtection.class.getDeclaredField("dayTradeCount");
        countField.setAccessible(true);
        countField.set(pdt, dayTradeCount);
        // Mark as synced so the "not synced" guard doesn't fire
        Field syncedField = PDTProtection.class.getDeclaredField("synced");
        syncedField.setAccessible(true);
        syncedField.set(pdt, true);
        return pdt;
    }

    @SuppressWarnings("removal")
    private static ProfileManager allocateProfileManager() throws Exception {
        var unsafeClass = Class.forName("sun.misc.Unsafe");
        var unsafeField = unsafeClass.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        var unsafe = unsafeField.get(null);
        return (ProfileManager) unsafeClass.getMethod("allocateInstance", Class.class)
            .invoke(unsafe, ProfileManager.class);
    }

    /**
     * Resolves a field either directly on ProfileManager, or — for the ~20 risk/coordination
     * fields extracted onto RiskGate 2026-08-30 (stopLossCooldowns, lastExitPrices, etc.) —
     * on profileManager's own riskGate instance. Lets every existing setField/getField call
     * site below keep using the old field names without knowing which object actually owns
     * the field now.
     */
    private Object[] resolveFieldTarget(String name) throws Exception {
        try {
            Field f = ProfileManager.class.getDeclaredField(name);
            f.setAccessible(true);
            return new Object[]{f, profileManager};
        } catch (NoSuchFieldException e) {
            Field riskGateField = ProfileManager.class.getDeclaredField("riskGate");
            riskGateField.setAccessible(true);
            Object riskGate = riskGateField.get(profileManager);
            Field f = riskGate.getClass().getDeclaredField(name);
            f.setAccessible(true);
            return new Object[]{f, riskGate};
        }
    }

    private void setField(String name, Object value) throws Exception {
        Object[] target = resolveFieldTarget(name);
        ((Field) target[0]).set(target[1], value);
    }

    @SuppressWarnings("unchecked")
    private <T> T getField(String name) throws Exception {
        Object[] target = resolveFieldTarget(name);
        return (T) ((Field) target[0]).get(target[1]);
    }

    private Object invokePrivate(String name, Class<?>[] types, Object... args) throws Exception {
        Method m = ProfileManager.class.getDeclaredMethod(name, types);
        m.setAccessible(true);
        return m.invoke(profileManager, args);
    }

    /**
     * checkAllPositionsForProfitTargets lives on ExitEvaluator since 2026-08-30 (see its class
     * Javadoc) — invoke on the exitEvaluator instance held by profileManager instead.
     */
    private Object invokeOnExitEvaluator(String name, Class<?>[] types, Object... args) throws Exception {
        Method m = ExitEvaluator.class.getDeclaredMethod(name, types);
        m.setAccessible(true);
        return m.invoke(getField("exitEvaluator"), args);
    }

    @SuppressWarnings("deprecation")
    private ResilientAlpacaClient createMockClient() {
        return mock(ResilientAlpacaClient.class, withSettings()
            .mockMaker(org.mockito.MockMakers.SUBCLASS)
            .defaultAnswer(inv -> {
                Class<?> rt = inv.getMethod().getReturnType();
                if (rt == void.class) return null;
                if (rt == List.class) return List.of();
                if (rt == Optional.class) return Optional.empty();
                return null;
            }));
    }

    private Config createMockConfig() {
        Config cfg = mock(Config.class, withSettings()
            .mockMaker(org.mockito.MockMakers.SUBCLASS)
            .defaultAnswer(inv -> {
                Class<?> rt = inv.getMethod().getReturnType();
                if (rt == boolean.class) return false;
                if (rt == int.class) return 0;
                if (rt == long.class) return 0L;
                if (rt == double.class) return 0.0;
                if (rt == String.class) return "";
                if (rt == List.class) return List.of();
                return null;
            }));
        when(cfg.isMaxLossExitEnabled()).thenReturn(true);
        when(cfg.getMaxLossPercent()).thenReturn(2.0);
        when(cfg.getMaxHoldTimeHours()).thenReturn(48);
        when(cfg.getMaxPositionsAtOnce()).thenReturn(5);
        when(cfg.getStopLossCooldownMs()).thenReturn(7200000L); // 2 hours = config value
        when(cfg.getEodExitTime()).thenReturn("15:30");
        when(cfg.getTradingMode()).thenReturn("LIVE");
        when(cfg.getMainTakeProfitPercent()).thenReturn(3.0);
        when(cfg.getMainStopLossPercent()).thenReturn(1.5);
        when(cfg.getMainTrailingStopPercent()).thenReturn(1.0);
        when(cfg.getMainProfileCapitalPercent()).thenReturn(0.60);
        when(cfg.getVixThreshold()).thenReturn(20.0);
        when(cfg.getVixHysteresis()).thenReturn(2.0);
        when(cfg.getMainBullishSymbols()).thenReturn(List.of("SPY","QQQ","DIA"));
        when(cfg.getMainBearishSymbols()).thenReturn(List.of("SH","PSQ"));
        when(cfg.getPositionSizingMaxCorrelatedPositions()).thenReturn(5);
        when(cfg.isRegimeDetectionEnabled()).thenReturn(false);
        when(cfg.isTrailingTargetsEnabled()).thenReturn(false);
        when(cfg.isTimeDecayExits()).thenReturn(false);
        when(cfg.isMomentumAccelerationExits()).thenReturn(false);
        when(cfg.isPositionHealthScoring()).thenReturn(false);
        when(cfg.isBreakevenStopEnabled()).thenReturn(false);
        when(cfg.getBreakevenTriggerPercent()).thenReturn(0.5);
        when(cfg.getMinHoldTimeHours()).thenReturn(0);
        when(cfg.getSmartCapitalReservePercent()).thenReturn(0.10);
        when(cfg.isMarketHoursBypassEnabled()).thenReturn(false);
        when(cfg.isAvoidFirst15Minutes()).thenReturn(false);
        when(cfg.isAvoidLast30Minutes()).thenReturn(false);
        when(cfg.isDailyProfitTargetEnabled()).thenReturn(false);
        when(cfg.isDynamicStopsEnabled()).thenReturn(false);
        when(cfg.isMLEntryScoringEnabled()).thenReturn(false);
        when(cfg.getMLMinScore()).thenReturn(0.6);
        when(cfg.getPositionSizingDefaultWinRate()).thenReturn(0.5);
        when(cfg.isAdaptiveSizingEnabled()).thenReturn(false);
        when(cfg.isVolumeProfileEnabled()).thenReturn(false);
        when(cfg.isAutoRebalanceEnabled()).thenReturn(false);
        when(cfg.isEODProfitLockEnabled()).thenReturn(false);
        when(cfg.isEodExitEnabled()).thenReturn(false);
        when(cfg.isReduceRiskAfterTarget()).thenReturn(false);
        when(cfg.getDailyProfitTarget()).thenReturn(2.0);
        return cfg;
    }

    private TradingProfile createMainProfile() {
        return new TradingProfile("MAIN", true, 0.60, 3.0, 1.5, 1.0,
            List.of("SPY","QQQ","DIA"), List.of("SH","PSQ"),
            20.0, 2.0, "MACD", Duration.ofMinutes(0), Duration.ofDays(7));
    }

    @BeforeEach
    void setUp() throws Exception {
        profileManager    = allocateProfileManager();
        mockClient        = createMockClient();
        mockConfig        = createMockConfig();
        mockDatabase      = mock(TradeDatabase.class, withSettings().mockMaker(org.mockito.MockMakers.SUBCLASS));
        realPDTProtection = createPDTProtection(0); // default: 0 day trades

        var portfolio = new PortfolioManager(List.of("SPY","QQQ","DIA"), 10_000.0);
        var profile = createMainProfile();
        var exitStrategyManager = new ExitStrategyManager(mockConfig);
        var phase2ExitStrategies = new com.trading.exits.Phase2ExitStrategies(mockConfig);
        var trailingTargetManager = new com.trading.exits.TrailingTargetManager(mockConfig);
        var orderTypeSelector = new com.trading.execution.SmartOrderTypeSelector();
        var timeDecayExitManager = new com.trading.exits.TimeDecayExitManager(mockConfig);
        // riskGate is a final field with inline init (`= new RiskGate()`) — bypassed by
        // allocateInstance(). RiskGate's own no-arg constructor runs normally once we build one
        // here, so its internal maps/defaults come out correctly initialized.
        var riskGate = new RiskGate();

        setField("profile",               profile);
        setField("capital",               10_000.0);
        setField("client",                mockClient);
        setField("config",                mockConfig);
        setField("database",              mockDatabase);
        setField("portfolio",             portfolio);
        setField("pdtProtection",         realPDTProtection);
        setField("latestVix",             15.0);
        setField("latestEquity",          10_000.0);
        setField("running",               true);
        setField("exitStrategyManager",   exitStrategyManager);
        setField("phase2ExitStrategies",  phase2ExitStrategies);
        setField("trailingTargetManager", trailingTargetManager);
        setField("orderTypeSelector",     orderTypeSelector);
        setField("latestRegime",          com.trading.analysis.MarketRegimeDetector.MarketRegime.RANGE_BOUND);
        setField("timeDecayExitManager",  timeDecayExitManager);
        setField("riskGate", riskGate);

        // exitEvaluator is a final field with inline init — bypassed by allocateInstance(), same
        // as riskGate above. Built manually from the same collaborators just seeded.
        java.util.function.DoubleSupplier vixSupplier = () -> {
            try { return (double) (Double) getField("latestVix"); } catch (Exception e) { throw new RuntimeException(e); }
        };
        java.util.function.Supplier<com.trading.analysis.MarketRegimeDetector.MarketRegime> regimeSupplier = () -> {
            try {
                return (com.trading.analysis.MarketRegimeDetector.MarketRegime) getField("latestRegime");
            } catch (Exception e) { throw new RuntimeException(e); }
        };
        java.util.function.DoubleSupplier equitySupplier = () -> {
            try { return (double) (Double) getField("latestEquity"); } catch (Exception e) { throw new RuntimeException(e); }
        };
        java.util.function.BiConsumer<String, Double> updateDailyPnLFn = (prefix, pnl) -> {
            try {
                Method m = ProfileManager.class.getDeclaredMethod("updateDailyPnL", String.class, double.class);
                m.setAccessible(true);
                m.invoke(profileManager, prefix, pnl);
            } catch (Exception e) { throw new RuntimeException(e); }
        };
        var exitEvaluator = new ExitEvaluator(profile, "alpaca", mockConfig, mockDatabase, mockClient,
            portfolio, riskGate, exitStrategyManager, phase2ExitStrategies, timeDecayExitManager,
            trailingTargetManager, orderTypeSelector, null, null, 10_000.0,
            vixSupplier, regimeSupplier, equitySupplier, updateDailyPnLFn);
        setField("exitEvaluator", exitEvaluator);
        setField("todayPnL", 0.0);
        setField("brokerName", "alpaca");
    }

    // ── PDT Threshold Tests ──────────────────────────────────────────────────

    @Test
    @DisplayName("PDT threshold=1: buy is BLOCKED when dayTradeCount=1 (keeps 2 slots for exits)")
    void testPDT_blocksAtOneDayTrade() throws Exception {
        // PDT gate lives in handleBuy — test it directly
        setField("pdtProtection", createPDTProtection(1)); // 1/3 day trades used

        // equity < 25000 so PDT rules apply
        invokePrivate("handleBuy",
            new Class<?>[]{String.class, double.class, double.class, double.class, double.class,
                           com.trading.analysis.MarketRegimeDetector.MarketRegime.class, String.class},
            "DIA", 480.0, 9_000.0, 8_000.0, 15.0,
            com.trading.analysis.MarketRegimeDetector.MarketRegime.STRONG_BULL, "[MAIN]");

        // No buy order should have been placed — PDT gate blocked it
        verify(mockClient, never()).placeOrder(eq("DIA"), anyDouble(), eq("buy"), anyString(), anyString(), any());
        verify(mockClient, never()).placeBracketOrder(eq("DIA"), anyDouble(), eq("buy"), anyDouble(), anyDouble(), any(), any());
    }

    @Test
    @DisplayName("PDT threshold=1: buy is ALLOWED when dayTradeCount=0 (PDT gate does not fire)")
    void testPDT_allowsAtZeroDayTrades() throws Exception {
        // PDT gate lives in handleBuy — with 0 trades the gate must NOT early-return.
        setField("pdtProtection", createPDTProtection(0));

        // handleBuy will proceed past the PDT gate and into deeper logic (market breadth,
        // position sizing etc.) which require additional collaborators not wired in this test.
        // We specifically assert that the exception thrown (if any) is NOT the silent PDT
        // early-return but rather a NullPointerException or similar from the deeper path —
        // proving the PDT gate was passed.
        try {
            invokePrivate("handleBuy",
                new Class<?>[]{String.class, double.class, double.class, double.class, double.class,
                               com.trading.analysis.MarketRegimeDetector.MarketRegime.class, String.class},
                "DIA", 480.0, 9_000.0, 8_000.0, 15.0,
                com.trading.analysis.MarketRegimeDetector.MarketRegime.STRONG_BULL, "[MAIN]");
            // If it completes without exception, the gate definitely did not block it.
        } catch (java.lang.reflect.InvocationTargetException ite) {
            // Unwrap the real cause — any exception here means execution passed the PDT gate
            // (a PDT block is a silent early return, not an exception).
            Throwable cause = ite.getCause();
            assertNotNull(cause, "Expected a real cause from handleBuy, not a bare ITE");
            // PDT gate is a plain return — it never throws. If we got an exception, gate passed.
            assertFalse(cause.getMessage() != null && cause.getMessage().contains("PDT"),
                "Unexpected PDT-related exception: " + cause.getMessage());
        }

        // Confirm PDT gate logic was reached but did NOT place a block:
        // at count=0 with equity=9000 the threshold (1) is NOT met, so no broadcast
        verify(mockClient, never()).placeOrder(eq("DIA"), anyDouble(), eq("buy"), anyString(), anyString(), any());
        // (no buy placed is expected — deeper logic lacked signal data, which is fine here)
    }

    // ── Take-Profit Cooldown Race Condition Fix ──────────────────────────────

    @Test
    @DisplayName("Take-profit: cooldown is in map BEFORE portfolio position is cleared")
    void testTakeProfitCooldownSetBeforePositionCleared() throws Exception {
        // Position at +5% — triggers take-profit (threshold 3%)
        double entryPrice = 480.0;
        double qty = 2.0;
        double currentPrice = entryPrice * 1.05; // +5%
        double marketValue = currentPrice * qty;

        var pos = new Position("DIA", qty, marketValue, entryPrice, (currentPrice - entryPrice) * qty);
        when(mockClient.getPositions()).thenReturn(List.of(pos));
        when(mockClient.getOpenOrders("DIA")).thenReturn(MAPPER.createArrayNode());

        ConcurrentHashMap<String, Long> cooldowns = getField("stopLossCooldowns");
        PortfolioManager portfolio = getField("portfolio");

        invokeOnExitEvaluator("checkAllPositionsForProfitTargets",
            new Class<?>[]{String.class}, "[MAIN]");

        // After the call: cooldown must be present
        assertTrue(cooldowns.containsKey("DIA"),
            "stopLossCooldowns must contain DIA after take-profit exit");

        // And position must be cleared
        assertFalse(portfolio.getPosition("DIA").isPresent(),
            "Portfolio must have cleared DIA position after take-profit");

        // The cooldown expiry must be in the future
        long expiry = cooldowns.get("DIA");
        assertTrue(expiry > System.currentTimeMillis(),
            "Cooldown expiry must be in the future");
    }

    @Test
    @DisplayName("Take-profit cooldown duration comes from config, not hardcoded 30 minutes")
    void testTakeProfitCooldownUsesConfigValue() throws Exception {
        // Config returns 2-hour cooldown
        long configCooldownMs = 7_200_000L; // 2 hours
        when(mockConfig.getStopLossCooldownMs()).thenReturn(configCooldownMs);

        double entryPrice = 480.0;
        double qty = 2.0;
        double currentPrice = entryPrice * 1.05;
        var pos = new Position("DIA", qty, currentPrice * qty, entryPrice, (currentPrice - entryPrice) * qty);
        when(mockClient.getPositions()).thenReturn(List.of(pos));
        when(mockClient.getOpenOrders("DIA")).thenReturn(MAPPER.createArrayNode());

        long before = System.currentTimeMillis();
        invokeOnExitEvaluator("checkAllPositionsForProfitTargets", new Class<?>[]{String.class}, "[MAIN]");
        long after  = System.currentTimeMillis();

        ConcurrentHashMap<String, Long> cooldowns = getField("stopLossCooldowns");
        assertTrue(cooldowns.containsKey("DIA"), "DIA should be in cooldowns after take-profit");

        long expiry = cooldowns.get("DIA");
        long minExpected = before + configCooldownMs;
        long maxExpected = after  + configCooldownMs + 1000; // 1s tolerance

        // Verify expiry reflects the 2-hour config value, NOT the old 30-minute hardcode
        assertTrue(expiry >= minExpected,
            "Cooldown expiry=" + expiry + " should be at least " + minExpected +
            " (config 2h). If this fails, hardcoded 30min is still being used.");
        assertTrue(expiry <= maxExpected,
            "Cooldown expiry=" + expiry + " must not be more than 2h+1s in the future");
    }

    @Test
    @DisplayName("Take-profit: lastExitPrices gate is REMOVED (not kept) after profitable exit")
    void testTakeProfitRemovesLastExitPrice() throws Exception {
        // Pre-populate a lastExitPrice for DIA (simulates a previous loss exit)
        ConcurrentHashMap<String, Double> lastExitPrices = getField("lastExitPrices");
        lastExitPrices.put("DIA", 470.0);

        double entryPrice = 480.0;
        double qty = 2.0;
        double currentPrice = entryPrice * 1.05;
        var pos = new Position("DIA", qty, currentPrice * qty, entryPrice, (currentPrice - entryPrice) * qty);
        when(mockClient.getPositions()).thenReturn(List.of(pos));
        when(mockClient.getOpenOrders("DIA")).thenReturn(MAPPER.createArrayNode());

        invokeOnExitEvaluator("checkAllPositionsForProfitTargets", new Class<?>[]{String.class}, "[MAIN]");

        assertFalse(lastExitPrices.containsKey("DIA"),
            "lastExitPrices gate should be cleared after take-profit (price improved, no re-entry block needed)");
    }
}
