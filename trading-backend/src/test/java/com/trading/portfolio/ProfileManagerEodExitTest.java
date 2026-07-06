package com.trading.portfolio;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.trading.api.ResilientAlpacaClient;
import com.trading.api.model.Position;
import com.trading.config.Config;
import com.trading.persistence.TradeDatabase;
import com.trading.strategy.TradingProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for EOD exit correctness.
 *
 * Covers:
 * - EOD exit fires once per day (past-threshold, not narrow window)
 * - EOD exit only cancels STOP orders — never pending sell orders
 * - EOD exit writes database.closeTrade() immediately (no re-entry window)
 * - EOD exit retries if positions remain after first attempt
 * - isGoodEntryTime() blocks entries at/after EOD time regardless of isAvoidFirst15Minutes
 * - isGoodEntryTime() blocks entries even when isAvoidFirst15Minutes=false (the dead-code bug)
 */
@DisplayName("ProfileManager EOD Exit Tests")
class ProfileManagerEodExitTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ProfileManager profileManager;
    private ResilientAlpacaClient mockClient;
    private Config mockConfig;
    private TradeDatabase mockDatabase;
    private PortfolioManager portfolio;

    // ===================== Harness =====================

    @SuppressWarnings("removal")
    private static ProfileManager allocateProfileManager() throws Exception {
        var unsafeClass = Class.forName("sun.misc.Unsafe");
        var unsafeField = unsafeClass.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        var unsafe = unsafeField.get(null);
        var allocateMethod = unsafeClass.getMethod("allocateInstance", Class.class);
        return (ProfileManager) allocateMethod.invoke(unsafe, ProfileManager.class);
    }

    private void setField(String name, Object value) throws Exception {
        Field f = ProfileManager.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(profileManager, value);
    }

    private <T> T getField(String name) throws Exception {
        Field f = ProfileManager.class.getDeclaredField(name);
        f.setAccessible(true);
        @SuppressWarnings("unchecked") T v = (T) f.get(profileManager);
        return v;
    }

    private Object invokePrivate(String name, Class<?>[] types, Object... args) throws Exception {
        Method m = ProfileManager.class.getDeclaredMethod(name, types);
        m.setAccessible(true);
        return m.invoke(profileManager, args);
    }

    private ResilientAlpacaClient createMockClient() {
        return mock(ResilientAlpacaClient.class, withSettings()
                .mockMaker(org.mockito.MockMakers.SUBCLASS)
                .defaultAnswer(inv -> {
                    if (inv.getMethod().getReturnType() == void.class) return null;
                    if (inv.getMethod().getReturnType() == List.class) return List.of();
                    if (inv.getMethod().getReturnType() == Optional.class) return Optional.empty();
                    return null;
                }));
    }

    private Config createMockConfig(String eodTime) {
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
        when(cfg.isEodExitEnabled()).thenReturn(true);
        when(cfg.getEodExitTime()).thenReturn(eodTime);
        when(cfg.isAvoidFirst15Minutes()).thenReturn(false);
        when(cfg.isAvoidLast30Minutes()).thenReturn(false);
        when(cfg.getMaxPositionsAtOnce()).thenReturn(5);
        when(cfg.isMaxLossExitEnabled()).thenReturn(false);
        when(cfg.getStopLossCooldownMs()).thenReturn(1800000L);
        when(cfg.isBreakevenStopEnabled()).thenReturn(false);
        when(cfg.getSmartCapitalReservePercent()).thenReturn(0.10);
        when(cfg.getTradingMode()).thenReturn("LIVE");
        return cfg;
    }

    private TradingProfile createMainProfile() {
        return new TradingProfile("MAIN", true, 0.60, 1.5, 0.8, 0.7,
                List.of("SPY", "QQQ"), List.of("SH"), 20.0, 2.0,
                "MACD", Duration.ofDays(2), Duration.ofDays(7));
    }

    /** Build a single-element ArrayNode representing an open stop sell order. */
    private ArrayNode stopSellOrder(String id) {
        ArrayNode arr = MAPPER.createArrayNode();
        ObjectNode o = MAPPER.createObjectNode();
        o.put("id", id);
        o.put("type", "stop");
        o.put("side", "sell");
        arr.add(o);
        return arr;
    }

    /** Build a single-element ArrayNode representing an open market sell order (pending fill). */
    private ArrayNode marketSellOrder(String id) {
        ArrayNode arr = MAPPER.createArrayNode();
        ObjectNode o = MAPPER.createObjectNode();
        o.put("id", id);
        o.put("type", "market");
        o.put("side", "sell");
        arr.add(o);
        return arr;
    }

    private ArrayNode emptyOrders() {
        return MAPPER.createArrayNode();
    }

    /** One AAPL position: 2 shares at $300 entry, $300 market value = $600 value ($300/share). */
    private Position aaplPosition() {
        return new Position("AAPL", 2.0, 600.0, 300.0, 0.0);
    }

    @BeforeEach
    void setUp() throws Exception {
        profileManager = allocateProfileManager();

        mockClient = createMockClient();
        mockConfig = createMockConfig("15:30");
        mockDatabase = mock(TradeDatabase.class, withSettings()
                .mockMaker(org.mockito.MockMakers.SUBCLASS));

        portfolio = new PortfolioManager(List.of("AAPL", "QQQ"), 100_000.0);

        setField("profile", createMainProfile());
        setField("capital", 100_000.0);
        setField("client", mockClient);
        setField("config", mockConfig);
        setField("database", mockDatabase);
        setField("portfolio", portfolio);
        setField("brokerName", "alpaca");
        setField("latestVix", 15.0);
        setField("latestEquity", 100_000.0);
        setField("running", true);
        setField("eodExitExecutedDate", null);
        setField("stopLossCooldowns", new ConcurrentHashMap<String, Long>());
        setField("pendingExitOrders", new ConcurrentHashMap<String, Long>());
        setField("consecutiveStopLosses", new ConcurrentHashMap<String, Integer>());

        // Phase2 + trailing exits needed by other PM methods
        setField("phase2ExitStrategies", new com.trading.exits.Phase2ExitStrategies(mockConfig));
        setField("trailingTargetManager", new com.trading.exits.TrailingTargetManager(mockConfig));
        setField("orderTypeSelector", new com.trading.execution.SmartOrderTypeSelector());
        setField("exitStrategyManager", new com.trading.exits.ExitStrategyManager(mockConfig));
        setField("latestRegime", com.trading.analysis.MarketRegimeDetector.MarketRegime.RANGE_BOUND);
    }

    // ===================== Tests: isGoodEntryTime EOD block =====================

    @Test
    @DisplayName("isGoodEntryTime blocks entries at EOD time even when isAvoidFirst15Minutes=false")
    void entryBlockedAtEodTime_regardlessOfAvoidFirst15Flag() throws Exception {
        // The old bug: EOD block was inside isAvoidFirst15Minutes guard which defaults false.
        // With the fix, EOD block is always evaluated first.
        when(mockConfig.isEodExitEnabled()).thenReturn(true);
        when(mockConfig.getEodExitTime()).thenReturn("00:00"); // midnight = always past in ET
        when(mockConfig.isAvoidFirst15Minutes()).thenReturn(false);

        Boolean result = (Boolean) invokePrivate("isGoodEntryTime", new Class[]{});

        assertFalse(result, "Entry must be blocked at/after EOD time regardless of isAvoidFirst15Minutes");
    }

    @Test
    @DisplayName("isGoodEntryTime allows entries well before EOD time")
    void entryAllowedBeforeEodTime() throws Exception {
        when(mockConfig.isEodExitEnabled()).thenReturn(true);
        when(mockConfig.getEodExitTime()).thenReturn("23:59"); // far future
        when(mockConfig.isAvoidFirst15Minutes()).thenReturn(false);

        Boolean result = (Boolean) invokePrivate("isGoodEntryTime", new Class[]{});

        assertTrue(result, "Entry must be allowed well before EOD time");
    }

    @Test
    @DisplayName("isGoodEntryTime allows entries when EOD exit is disabled")
    void entryAllowedWhenEodExitDisabled() throws Exception {
        when(mockConfig.isEodExitEnabled()).thenReturn(false);
        when(mockConfig.isAvoidFirst15Minutes()).thenReturn(false);

        Boolean result = (Boolean) invokePrivate("isGoodEntryTime", new Class[]{});

        assertTrue(result, "Entry must be allowed when EOD exit feature is off");
    }

    // ===================== Tests: checkAndExecuteEodExit =====================

    @Test
    @DisplayName("EOD exit fires and sells all positions when time is past 15:30")
    void eodExitFiresWhenTimePassed() throws Exception {
        when(mockConfig.getEodExitTime()).thenReturn("00:00"); // always past
        when(mockClient.getPositions())
                .thenReturn(List.of(aaplPosition()))   // first call: positions present
                .thenReturn(List.of());                // second call: verify closure
        when(mockClient.getOpenOrders("AAPL")).thenReturn(emptyOrders());

        invokePrivate("checkAndExecuteEodExit", new Class[]{String.class}, "[MAIN]");

        verify(mockClient).placeOrder("AAPL", 2.0, "sell", "market", "day", null);
    }

    @Test
    @DisplayName("EOD exit does NOT fire before 15:30")
    void eodExitDoesNotFireBeforeTime() throws Exception {
        when(mockConfig.getEodExitTime()).thenReturn("23:59"); // far future

        invokePrivate("checkAndExecuteEodExit", new Class[]{String.class}, "[MAIN]");

        verify(mockClient, never()).placeOrder(any(), anyDouble(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("EOD exit only fires once per day — once-per-day guard prevents duplicate sells")
    void eodExitOnlyFiresOncePerDay() throws Exception {
        when(mockConfig.getEodExitTime()).thenReturn("00:00");
        when(mockClient.getPositions()).thenReturn(List.of());

        // First invocation — fires, marks date
        invokePrivate("checkAndExecuteEodExit", new Class[]{String.class}, "[MAIN]");
        // Second invocation same day — must be skipped
        invokePrivate("checkAndExecuteEodExit", new Class[]{String.class}, "[MAIN]");

        // getPositions should be called only once (first invocation)
        verify(mockClient, times(1)).getPositions();
    }

    @Test
    @DisplayName("EOD exit skips on EXPERIMENTAL profile — only MAIN executes it")
    void eodExitSkipsForExperimentalProfile() throws Exception {
        var expProfile = new TradingProfile("EXPERIMENTAL", false, 0.40, 1.5, 0.8, 0.7,
                List.of("SPY"), List.of("SH"), 20.0, 2.0, "MACD",
                Duration.ofDays(2), Duration.ofDays(7));
        setField("profile", expProfile);

        when(mockConfig.getEodExitTime()).thenReturn("00:00");

        invokePrivate("checkAndExecuteEodExit", new Class[]{String.class}, "[EXP]");

        verify(mockClient, never()).getPositions();
        verify(mockClient, never()).placeOrder(any(), anyDouble(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("EOD exit cancels STOP orders but does NOT cancel pending market sell orders")
    void eodExitCancelsStopOrdersNotSellOrders() throws Exception {
        when(mockConfig.getEodExitTime()).thenReturn("00:00");
        when(mockClient.getPositions())
                .thenReturn(List.of(aaplPosition()))
                .thenReturn(List.of());

        // Mix of a stop order + a pending market sell (as if a prior cycle already placed it)
        ArrayNode mixedOrders = MAPPER.createArrayNode();
        ObjectNode stopOrder = MAPPER.createObjectNode();
        stopOrder.put("id", "stop-001");
        stopOrder.put("type", "stop");
        stopOrder.put("side", "sell");
        mixedOrders.add(stopOrder);

        ObjectNode pendingSell = MAPPER.createObjectNode();
        pendingSell.put("id", "sell-001");
        pendingSell.put("type", "market");
        pendingSell.put("side", "sell");
        mixedOrders.add(pendingSell);

        when(mockClient.getOpenOrders("AAPL")).thenReturn(mixedOrders);

        invokePrivate("checkAndExecuteEodExit", new Class[]{String.class}, "[MAIN]");

        // Stop order must be cancelled
        verify(mockClient).cancelOrder("stop-001");
        // Pending sell must NOT be cancelled (would create self-cancel loop)
        verify(mockClient, never()).cancelOrder("sell-001");
    }

    @Test
    @DisplayName("EOD exit calls database.closeTrade() immediately — no re-entry window")
    void eodExitClosesDbRecordImmediately() throws Exception {
        when(mockConfig.getEodExitTime()).thenReturn("00:00");
        when(mockClient.getPositions())
                .thenReturn(List.of(aaplPosition()))
                .thenReturn(List.of());
        when(mockClient.getOpenOrders("AAPL")).thenReturn(emptyOrders());

        invokePrivate("checkAndExecuteEodExit", new Class[]{String.class}, "[MAIN]");

        // DB must be closed right after the sell, not via later orphan cleanup
        verify(mockDatabase).closeTrade(eq("AAPL"), any(), anyDouble(), anyDouble(), eq("alpaca"));
    }

    @Test
    @DisplayName("EOD exit retries if positions remain after first sell attempt")
    void eodExitRetriesIfPositionsRemain() throws Exception {
        when(mockConfig.getEodExitTime()).thenReturn("00:00");

        // First invocation: sell placed but position still shows (order pending fill)
        when(mockClient.getPositions())
                .thenReturn(List.of(aaplPosition()))  // entering: has position
                .thenReturn(List.of(aaplPosition())); // verify: still open → retry not done

        when(mockClient.getOpenOrders("AAPL")).thenReturn(emptyOrders());

        invokePrivate("checkAndExecuteEodExit", new Class[]{String.class}, "[MAIN]");

        // eodExitExecutedDate must NOT be set when positions remain
        LocalDate executedDate = getField("eodExitExecutedDate");
        assertNull(executedDate, "eodExitExecutedDate must stay null when positions remain, to allow retry");

        // Second invocation: Alpaca now empty — retry fires and completes
        when(mockClient.getPositions())
                .thenReturn(List.of(aaplPosition()))
                .thenReturn(List.of()); // verify: now empty
        when(mockClient.getOpenOrders("AAPL")).thenReturn(emptyOrders());

        invokePrivate("checkAndExecuteEodExit", new Class[]{String.class}, "[MAIN]");

        LocalDate executedDateAfterSuccess = getField("eodExitExecutedDate");
        assertNotNull(executedDateAfterSuccess, "eodExitExecutedDate must be set once all positions are confirmed closed");
    }

    @Test
    @DisplayName("EOD exit marks date complete when no positions exist (already flat)")
    void eodExitMarksDateWhenAlreadyFlat() throws Exception {
        when(mockConfig.getEodExitTime()).thenReturn("00:00");
        when(mockClient.getPositions()).thenReturn(List.of()); // already flat

        invokePrivate("checkAndExecuteEodExit", new Class[]{String.class}, "[MAIN]");

        LocalDate executedDate = getField("eodExitExecutedDate");
        assertNotNull(executedDate, "eodExitExecutedDate must be set even when already flat (no double-check next cycle)");
        verify(mockClient, never()).placeOrder(any(), anyDouble(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("EOD exit closes multiple positions in a single pass")
    void eodExitClosesMultiplePositions() throws Exception {
        when(mockConfig.getEodExitTime()).thenReturn("00:00");

        var qqq = new Position("QQQ", 1.5, 1110.0, 740.0, 0.0);
        when(mockClient.getPositions())
                .thenReturn(List.of(aaplPosition(), qqq))
                .thenReturn(List.of());
        when(mockClient.getOpenOrders(anyString())).thenReturn(emptyOrders());

        invokePrivate("checkAndExecuteEodExit", new Class[]{String.class}, "[MAIN]");

        verify(mockClient).placeOrder("AAPL", 2.0, "sell", "market", "day", null);
        verify(mockClient).placeOrder("QQQ", 1.5, "sell", "market", "day", null);
        verify(mockDatabase).closeTrade(eq("AAPL"), any(), anyDouble(), anyDouble(), eq("alpaca"));
        verify(mockDatabase).closeTrade(eq("QQQ"), any(), anyDouble(), anyDouble(), eq("alpaca"));
    }

    @Test
    @DisplayName("EOD exit uses absolute quantity (handles negative qty for short positions)")
    void eodExitUsesAbsoluteQty() throws Exception {
        when(mockConfig.getEodExitTime()).thenReturn("00:00");
        var shortPos = new Position("SQQQ", -3.0, 120.0, 40.0, 0.0); // short = negative qty
        when(mockClient.getPositions())
                .thenReturn(List.of(shortPos))
                .thenReturn(List.of());
        when(mockClient.getOpenOrders("SQQQ")).thenReturn(emptyOrders());

        invokePrivate("checkAndExecuteEodExit", new Class[]{String.class}, "[MAIN]");

        verify(mockClient).placeOrder("SQQQ", 3.0, "sell", "market", "day", null);
    }
}
