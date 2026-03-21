package com.trading.portfolio;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.trading.api.ResilientAlpacaClient;
import com.trading.api.model.Position;
import com.trading.config.Config;
import com.trading.exits.ExitStrategyManager;
import com.trading.persistence.TradeDatabase;
import com.trading.risk.TradePosition;
import com.trading.strategy.TradingProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for ProfileManager sell order behavior, focusing on:
 * - Cancel-before-sell ordering for stop loss and take profit exits
 * - Zero quantity / zero entry price guard clauses
 * - Excess position cleanup with cancel-before-sell
 * - Cooldown expiration cleanup
 * - RiskManager equity tracking
 *
 * Uses Unsafe.allocateInstance to bypass ProfileManager's complex 17-parameter
 * constructor, then sets required fields via reflection. Mocks are created with
 * MockMakers.SUBCLASS since ByteBuddy's inline mock maker cannot instrument
 * Java 25 class files.
 */
@DisplayName("ProfileManager Sell Order Tests")
class ProfileManagerSellOrderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ProfileManager profileManager;

    private ResilientAlpacaClient mockClient;
    private Config mockConfig;
    private TradeDatabase mockDatabase;
    private ExitStrategyManager mockExitStrategyManager;

    // ===================== Utility Methods =====================

    /**
     * Create a ProfileManager instance WITHOUT calling the constructor.
     * Uses sun.misc.Unsafe to allocate the object, then sets fields via reflection.
     */
    @SuppressWarnings("removal")
    private static ProfileManager allocateProfileManager() throws Exception {
        var unsafeClass = Class.forName("sun.misc.Unsafe");
        var unsafeField = unsafeClass.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        var unsafe = unsafeField.get(null);
        var allocateMethod = unsafeClass.getMethod("allocateInstance", Class.class);
        return (ProfileManager) allocateMethod.invoke(unsafe, ProfileManager.class);
    }

    /**
     * Set a private field on ProfileManager via reflection.
     */
    private void setField(String fieldName, Object value) throws Exception {
        Field field = ProfileManager.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(profileManager, value);
    }

    /**
     * Get a private field from ProfileManager via reflection.
     */
    @SuppressWarnings("unchecked")
    private <T> T getField(String fieldName) throws Exception {
        Field field = ProfileManager.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return (T) field.get(profileManager);
    }

    /**
     * Invoke a private method on ProfileManager via reflection.
     */
    private Object invokePrivate(String methodName, Class<?>[] paramTypes, Object... args) throws Exception {
        Method method = ProfileManager.class.getDeclaredMethod(methodName, paramTypes);
        method.setAccessible(true);
        return method.invoke(profileManager, args);
    }

    /**
     * Create a Jackson ArrayNode representing open orders with the given IDs.
     */
    private ArrayNode createOpenOrdersNode(String... orderIds) {
        ArrayNode array = MAPPER.createArrayNode();
        for (String id : orderIds) {
            ObjectNode order = MAPPER.createObjectNode();
            order.put("id", id);
            order.put("type", "limit");
            array.add(order);
        }
        return array;
    }

    /**
     * Create an empty ArrayNode (no open orders).
     */
    private ArrayNode emptyOrdersNode() {
        return MAPPER.createArrayNode();
    }

    /**
     * Create a MAIN TradingProfile with standard test parameters.
     */
    private TradingProfile createMainProfile() {
        return new TradingProfile(
                "MAIN",
                0.60,      // capitalPercent
                1.5,       // takeProfitPercent
                0.8,       // stopLossPercent
                0.7,       // trailingStopPercent
                List.of("SPY", "QQQ"),    // bullishSymbols
                List.of("SH", "PSQ"),     // bearishSymbols
                20.0,      // vixThreshold
                2.0,       // vixHysteresis
                "MACD",    // strategyType
                Duration.ofDays(2),   // minHoldTime
                Duration.ofDays(7)    // maxHoldTime
        );
    }

    /**
     * Create a mock ResilientAlpacaClient using SUBCLASS mock maker
     * (required on Java 25 where inline mocking is not supported).
     */
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

    /**
     * Create a mock Config using SUBCLASS mock maker with sensible defaults
     * for all configuration values used by ProfileManager's exit logic.
     */
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

        // Set essential config defaults
        when(cfg.isMaxLossExitEnabled()).thenReturn(true);
        when(cfg.getMaxLossPercent()).thenReturn(2.0);
        when(cfg.getMaxHoldTimeHours()).thenReturn(48);
        when(cfg.isTrailingTargetsEnabled()).thenReturn(false);
        when(cfg.isTimeDecayExits()).thenReturn(false);
        when(cfg.isMomentumAccelerationExits()).thenReturn(false);
        when(cfg.isPositionHealthScoring()).thenReturn(false);
        when(cfg.getMaxPositionsAtOnce()).thenReturn(5);
        when(cfg.isRegimeDetectionEnabled()).thenReturn(false);
        when(cfg.getStopLossCooldownMs()).thenReturn(1800000L);
        when(cfg.getEodExitTime()).thenReturn("15:30");
        when(cfg.isEodExitEnabled()).thenReturn(false);
        when(cfg.isBreakevenStopEnabled()).thenReturn(false);
        when(cfg.getBreakevenTriggerPercent()).thenReturn(0.5);
        when(cfg.getMinHoldTimeHours()).thenReturn(1);
        when(cfg.getSmartCapitalReservePercent()).thenReturn(0.10);
        when(cfg.getTradingMode()).thenReturn("LIVE");
        when(cfg.isMarketHoursBypassEnabled()).thenReturn(false);
        when(cfg.isAvoidFirst15Minutes()).thenReturn(false);
        when(cfg.isAvoidLast30Minutes()).thenReturn(false);
        when(cfg.isDailyProfitTargetEnabled()).thenReturn(false);
        when(cfg.getDailyProfitTarget()).thenReturn(2.0);
        when(cfg.isReduceRiskAfterTarget()).thenReturn(false);
        when(cfg.isDynamicStopsEnabled()).thenReturn(false);
        when(cfg.isMLEntryScoringEnabled()).thenReturn(false);
        when(cfg.getMLMinScore()).thenReturn(0.6);
        when(cfg.getPositionSizingDefaultWinRate()).thenReturn(0.5);
        when(cfg.isAdaptiveSizingEnabled()).thenReturn(false);
        when(cfg.isVolumeProfileEnabled()).thenReturn(false);
        when(cfg.isAutoRebalanceEnabled()).thenReturn(false);
        when(cfg.getMainTakeProfitPercent()).thenReturn(1.5);
        when(cfg.getMainStopLossPercent()).thenReturn(0.8);
        when(cfg.getMainTrailingStopPercent()).thenReturn(0.7);
        when(cfg.getMainProfileCapitalPercent()).thenReturn(0.60);
        when(cfg.getVixThreshold()).thenReturn(20.0);
        when(cfg.getVixHysteresis()).thenReturn(2.0);
        when(cfg.getMainBullishSymbols()).thenReturn(List.of("SPY", "QQQ"));
        when(cfg.getMainBearishSymbols()).thenReturn(List.of("SH", "PSQ"));
        when(cfg.isEODProfitLockEnabled()).thenReturn(false);
        when(cfg.getPositionSizingMaxCorrelatedPositions()).thenReturn(5);

        return cfg;
    }

    @BeforeEach
    void setUp() throws Exception {
        // Create ProfileManager without calling constructor
        profileManager = allocateProfileManager();

        // Create mocks using SUBCLASS mock maker (works with final classes on Java 25)
        mockClient = createMockClient();
        mockConfig = createMockConfig();
        mockDatabase = mock(TradeDatabase.class, withSettings()
                .mockMaker(org.mockito.MockMakers.SUBCLASS));

        // Create a real ExitStrategyManager with our mock config
        mockExitStrategyManager = new ExitStrategyManager(mockConfig);

        // Create a real portfolio with test symbols
        var symbols = List.of("SPY", "QQQ");
        var portfolio = new PortfolioManager(symbols, 100_000.0);

        // Set required fields on the ProfileManager
        setField("profile", createMainProfile());
        setField("capital", 100_000.0);
        setField("client", mockClient);
        setField("config", mockConfig);
        setField("database", mockDatabase);
        setField("portfolio", portfolio);
        setField("exitStrategyManager", mockExitStrategyManager);
        setField("latestVix", 15.0);
        setField("latestEquity", 100_000.0);
        setField("running", true);

        // Initialize the Phase 2 exit strategies (needed by checkAllPositionsForProfitTargets)
        var phase2ExitStrategies = new com.trading.exits.Phase2ExitStrategies(mockConfig);
        setField("phase2ExitStrategies", phase2ExitStrategies);

        // Initialize the trailing target manager (checked in risk exits even when disabled)
        var trailingTargetManager = new com.trading.exits.TrailingTargetManager(mockConfig);
        setField("trailingTargetManager", trailingTargetManager);

        // Initialize the order type selector used by take-profit sell path
        var orderTypeSelector = new com.trading.execution.SmartOrderTypeSelector();
        setField("orderTypeSelector", orderTypeSelector);

        // Initialize the stopLossCooldowns map
        setField("stopLossCooldowns", new ConcurrentHashMap<String, Long>());

        // Initialize the pendingExitOrders map (prevents duplicate sell/closeTrade calls)
        setField("pendingExitOrders", new ConcurrentHashMap<String, Long>());

        // Initialize the consecutiveStopLosses map (prevents stop-loss churn)
        setField("consecutiveStopLosses", new ConcurrentHashMap<String, Integer>());

        // Initialize latestRegime
        setField("latestRegime", com.trading.analysis.MarketRegimeDetector.MarketRegime.RANGE_BOUND);
    }

    // ===================== Test Cases =====================

    // ---------- 1. checkAllPositionsForProfitTargets: cancel before sell (stop loss) ----------

    @Test
    @DisplayName("1. checkAllPositionsForProfitTargets - cancel before sell on stop loss")
    void testCheckAllPositionsForProfitTargets_cancelBeforeSell_stopLoss() throws Exception {
        // Position at -5% (well beyond 0.8% stop loss threshold)
        double entryPrice = 100.0;
        double qty = 10.0;
        double currentPrice = entryPrice * 0.95; // -5%
        double marketValue = currentPrice * qty;

        Position position = new Position("AAPL", qty, marketValue, entryPrice, (currentPrice - entryPrice) * qty);
        when(mockClient.getPositions()).thenReturn(List.of(position));

        // Open orders exist that need cancelling
        ArrayNode orders = createOpenOrdersNode("order-sl-001");
        when(mockClient.getOpenOrders("AAPL")).thenReturn(orders);

        invokePrivate("checkAllPositionsForProfitTargets", new Class<?>[]{String.class}, "[MAIN]");

        // Verify cancel is called BEFORE placeOrderDirect (stop-loss bypasses circuit breaker)
        InOrder inOrder = inOrder(mockClient);
        inOrder.verify(mockClient).cancelOrder("order-sl-001");
        inOrder.verify(mockClient).placeOrderDirect(eq("AAPL"), eq(qty), eq("sell"), eq("market"), eq("day"), isNull());
    }

    // ---------- 2. checkAllPositionsForProfitTargets: cancel before sell (take profit) ----------

    @Test
    @DisplayName("2. checkAllPositionsForProfitTargets - cancel before sell on take profit")
    void testCheckAllPositionsForProfitTargets_cancelBeforeSell_takeProfit() throws Exception {
        // Position at +5% (well beyond 1.5% take profit threshold)
        double entryPrice = 100.0;
        double qty = 10.0;
        double currentPrice = entryPrice * 1.05; // +5%
        double marketValue = currentPrice * qty;

        Position position = new Position("AAPL", qty, marketValue, entryPrice, (currentPrice - entryPrice) * qty);
        when(mockClient.getPositions()).thenReturn(List.of(position));

        // Open orders exist
        ArrayNode orders = createOpenOrdersNode("order-tp-001");
        when(mockClient.getOpenOrders("AAPL")).thenReturn(orders);

        invokePrivate("checkAllPositionsForProfitTargets", new Class<?>[]{String.class}, "[MAIN]");

        // Verify cancel then sell ordering
        InOrder inOrder = inOrder(mockClient);
        inOrder.verify(mockClient).cancelOrder("order-tp-001");
        inOrder.verify(mockClient).placeOrder(eq("AAPL"), eq(qty), eq("sell"), anyString(), anyString(), any());
    }

    // ---------- 3. checkAllPositionsForRiskExits: cancel before sell (enhanced exit) ----------

    @Test
    @DisplayName("3. checkAllPositionsForRiskExits - cancel before sell on enhanced exit")
    void testCheckAllPositionsForRiskExits_cancelBeforeSell_enhancedExit() throws Exception {
        // Position that triggers stop loss via the enhanced ExitStrategyManager
        double entryPrice = 100.0;
        double qty = 10.0;
        double currentPrice = 98.0; // below stop loss
        double marketValue = currentPrice * qty;

        Position alpacaPos = new Position("SPY", qty, marketValue, entryPrice, (currentPrice - entryPrice) * qty);
        when(mockClient.getPositions()).thenReturn(List.of(alpacaPos));

        // Add tracked position in portfolio so enhanced exit path is used
        PortfolioManager portfolio = getField("portfolio");
        TradePosition trackedPos = new TradePosition(
                "SPY", entryPrice, qty,
                entryPrice * 0.992,  // stopLoss close to entry
                entryPrice * 1.015,  // takeProfit
                Instant.now().minus(Duration.ofHours(1))
        );
        portfolio.setPosition("SPY", Optional.of(trackedPos));

        // Open orders for the symbol
        ArrayNode orders = createOpenOrdersNode("order-risk-001");
        when(mockClient.getOpenOrders("SPY")).thenReturn(orders);

        invokePrivate("checkAllPositionsForRiskExits", new Class<?>[]{String.class}, "[MAIN]");

        // Verify cancel before sell (risk exits bypass circuit breaker via placeOrderDirect)
        InOrder inOrder = inOrder(mockClient);
        inOrder.verify(mockClient).cancelOrder("order-risk-001");
        inOrder.verify(mockClient).placeOrderDirect(eq("SPY"), anyDouble(), eq("sell"), eq("market"), eq("day"), isNull());
    }

    // ---------- 4. checkAllPositionsForRiskExits: zero quantity skipped ----------

    @Test
    @DisplayName("4. checkAllPositionsForRiskExits - zero quantity position is skipped")
    void testCheckAllPositionsForRiskExits_zeroQuantitySkipped() throws Exception {
        Position position = new Position("TSLA", 0, 0, 100.0, 0);
        when(mockClient.getPositions()).thenReturn(List.of(position));

        invokePrivate("checkAllPositionsForRiskExits", new Class<?>[]{String.class}, "[MAIN]");

        verify(mockClient, never()).placeOrder(anyString(), anyDouble(), anyString(), anyString(), anyString(), any());
    }

    // ---------- 5. checkAllPositionsForRiskExits: zero entry price skipped ----------

    @Test
    @DisplayName("5. checkAllPositionsForRiskExits - zero entry price position is skipped")
    void testCheckAllPositionsForRiskExits_zeroEntryPriceSkipped() throws Exception {
        Position position = new Position("TSLA", 5.0, 500.0, 0, -10.0);
        when(mockClient.getPositions()).thenReturn(List.of(position));

        invokePrivate("checkAllPositionsForRiskExits", new Class<?>[]{String.class}, "[MAIN]");

        verify(mockClient, never()).placeOrder(anyString(), anyDouble(), anyString(), anyString(), anyString(), any());
    }

    // ---------- 6. checkAllPositionsForProfitTargets: zero quantity skipped ----------

    @Test
    @DisplayName("6. checkAllPositionsForProfitTargets - zero quantity position is skipped")
    void testCheckAllPositionsForProfitTargets_zeroQuantitySkipped() throws Exception {
        Position position = new Position("GOOG", 0, 0, 150.0, 0);
        when(mockClient.getPositions()).thenReturn(List.of(position));

        invokePrivate("checkAllPositionsForProfitTargets", new Class<?>[]{String.class}, "[MAIN]");

        verify(mockClient, never()).placeOrder(anyString(), anyDouble(), anyString(), anyString(), anyString(), any());
    }

    // ---------- 7. checkAllPositionsForProfitTargets: zero entry price skipped ----------

    @Test
    @DisplayName("7. checkAllPositionsForProfitTargets - zero entry price position is skipped")
    void testCheckAllPositionsForProfitTargets_zeroEntryPriceSkipped() throws Exception {
        Position position = new Position("GOOG", 5.0, 750.0, 0, 0);
        when(mockClient.getPositions()).thenReturn(List.of(position));

        invokePrivate("checkAllPositionsForProfitTargets", new Class<?>[]{String.class}, "[MAIN]");

        verify(mockClient, never()).placeOrder(anyString(), anyDouble(), anyString(), anyString(), anyString(), any());
    }

    // ---------- 8. cleanupExcessPositions: cancel before sell ----------

    @Test
    @DisplayName("8. cleanupExcessPositions - cancel before sell for worst position")
    void testCleanupExcessPositions_cancelBeforeSell() throws Exception {
        // Set max positions to 1 so cleanup triggers with 2 positions
        when(mockConfig.getMaxPositionsAtOnce()).thenReturn(1);

        // Add 2 positions to portfolio
        PortfolioManager portfolio = getField("portfolio");
        TradePosition pos1 = new TradePosition("AAPL", 100.0, 5.0, 99.2, 101.5, Instant.now());
        TradePosition pos2 = new TradePosition("MSFT", 200.0, 3.0, 198.4, 203.0, Instant.now());
        portfolio.setPosition("AAPL", Optional.of(pos1));
        portfolio.setPosition("MSFT", Optional.of(pos2));

        // Return positions sorted with worst P&L first
        Position worstPos = new Position("AAPL", 5.0, 475.0, 100.0, -25.0); // -$25
        Position betterPos = new Position("MSFT", 3.0, 610.0, 200.0, 10.0); // +$10
        when(mockClient.getPositions()).thenReturn(List.of(worstPos, betterPos));

        // Open orders for worst position
        ArrayNode orders = createOpenOrdersNode("order-cleanup-001");
        when(mockClient.getOpenOrders("AAPL")).thenReturn(orders);

        invokePrivate("cleanupExcessPositions", new Class<?>[]{String.class}, "[MAIN]");

        // Verify cancel then sell
        InOrder inOrder = inOrder(mockClient);
        inOrder.verify(mockClient).cancelOrder("order-cleanup-001");
        inOrder.verify(mockClient).placeOrder(eq("AAPL"), eq(5.0), eq("sell"), eq("market"), eq("day"), isNull());
    }

    // ---------- 9. cleanupExcessPositions: zero quantity skipped ----------

    @Test
    @DisplayName("9. cleanupExcessPositions - zero quantity position skipped during cleanup")
    void testCleanupExcessPositions_zeroQuantitySkipped() throws Exception {
        when(mockConfig.getMaxPositionsAtOnce()).thenReturn(1);

        // Add 2 positions to trigger cleanup
        PortfolioManager portfolio = getField("portfolio");
        TradePosition pos1 = new TradePosition("AAPL", 100.0, 5.0, 99.2, 101.5, Instant.now());
        TradePosition pos2 = new TradePosition("MSFT", 200.0, 3.0, 198.4, 203.0, Instant.now());
        portfolio.setPosition("AAPL", Optional.of(pos1));
        portfolio.setPosition("MSFT", Optional.of(pos2));

        // Worst position has zero quantity - should be skipped
        Position zeroQtyPos = new Position("AAPL", 0, 0, 100.0, -25.0);
        Position normalPos = new Position("MSFT", 3.0, 610.0, 200.0, 10.0);
        when(mockClient.getPositions()).thenReturn(List.of(zeroQtyPos, normalPos));

        invokePrivate("cleanupExcessPositions", new Class<?>[]{String.class}, "[MAIN]");

        // placeOrder should NOT be called for the zero-qty AAPL position
        verify(mockClient, never()).placeOrder(eq("AAPL"), anyDouble(), anyString(), anyString(), anyString(), any());
    }

    // ---------- 10. cleanupExpiredCooldowns: removes expired entries ----------

    @Test
    @DisplayName("10. cleanupExpiredCooldowns - removes expired entries, keeps active ones")
    void testCleanupExpiredCooldowns_removesExpired() throws Exception {
        ConcurrentHashMap<String, Long> cooldowns = getField("stopLossCooldowns");

        long now = System.currentTimeMillis();

        // Add expired cooldown (past)
        cooldowns.put("EXPIRED_SYM", now - 60_000); // 1 min ago

        // Add active cooldown (future)
        cooldowns.put("ACTIVE_SYM", now + 600_000); // 10 min from now

        invokePrivate("cleanupExpiredCooldowns", new Class<?>[]{});

        assertFalse(cooldowns.containsKey("EXPIRED_SYM"),
                "Expired cooldown should have been removed");
        assertTrue(cooldowns.containsKey("ACTIVE_SYM"),
                "Active cooldown should still exist");
    }

    // ---------- 11. RiskManager uses latestEquity ----------

    @Test
    @DisplayName("11. checkAllPositionsForProfitTargets uses latestEquity for RiskManager")
    void testRiskManagerUsesLatestEquity() throws Exception {
        // Set latestEquity to a custom value
        double customEquity = 55_000.0;
        setField("latestEquity", customEquity);

        // Position at +5% (triggers take profit)
        double entryPrice = 100.0;
        double qty = 10.0;
        double currentPrice = entryPrice * 1.05;
        double marketValue = currentPrice * qty;

        Position position = new Position("AAPL", qty, marketValue, entryPrice, (currentPrice - entryPrice) * qty);
        when(mockClient.getPositions()).thenReturn(List.of(position));
        when(mockClient.getOpenOrders("AAPL")).thenReturn(emptyOrdersNode());

        invokePrivate("checkAllPositionsForProfitTargets", new Class<?>[]{String.class}, "[MAIN]");

        // Verify a sell order was placed (profit target logic executed)
        verify(mockClient).placeOrder(eq("AAPL"), eq(qty), eq("sell"), anyString(), anyString(), any());

        // Verify latestEquity field is our custom value
        double storedEquity = getField("latestEquity");
        assertEquals(customEquity, storedEquity,
                "latestEquity should remain at custom value");
    }

    // ---------- 12. pendingExitOrders prevents duplicate sell ----------

    @Test
    @DisplayName("12. Pending exit order prevents duplicate sell on next cycle")
    void testPendingExitOrderPreventsDuplicateSell() throws Exception {
        // Position at -5% (triggers stop loss)
        double entryPrice = 100.0;
        double qty = 10.0;
        double currentPrice = entryPrice * 0.95; // -5%
        double marketValue = currentPrice * qty;

        Position position = new Position("AAPL", qty, marketValue, entryPrice, (currentPrice - entryPrice) * qty);
        when(mockClient.getPositions()).thenReturn(List.of(position));
        when(mockClient.getOpenOrders("AAPL")).thenReturn(emptyOrdersNode());

        // First call: should place sell order (stop-loss uses placeOrderDirect to bypass circuit breaker)
        invokePrivate("checkAllPositionsForProfitTargets", new Class<?>[]{String.class}, "[MAIN]");
        verify(mockClient, times(1)).placeOrderDirect(eq("AAPL"), eq(qty), eq("sell"), anyString(), anyString(), any());

        // Verify symbol was added to pendingExitOrders
        ConcurrentHashMap<String, Long> pending = getField("pendingExitOrders");
        assertTrue(pending.containsKey("AAPL"), "AAPL should be in pendingExitOrders after sell");

        // Second call: should skip because pending exit exists (even though position still shows on Alpaca)
        invokePrivate("checkAllPositionsForProfitTargets", new Class<?>[]{String.class}, "[MAIN]");

        // placeOrderDirect should still have been called only once total
        verify(mockClient, times(1)).placeOrderDirect(eq("AAPL"), eq(qty), eq("sell"), anyString(), anyString(), any());
    }

    // ---------- 13. Consecutive stop-loss tracking ----------

    @Test
    @DisplayName("13. Consecutive stop-losses increase cooldown duration")
    void testConsecutiveStopLossesExtendCooldown() throws Exception {
        double entryPrice = 100.0;
        double qty = 10.0;
        double currentPrice = entryPrice * 0.95; // -5% triggers SL
        double marketValue = currentPrice * qty;

        Position position = new Position("GOOGL", qty, marketValue, entryPrice, (currentPrice - entryPrice) * qty);
        when(mockClient.getPositions()).thenReturn(List.of(position));
        when(mockClient.getOpenOrders("GOOGL")).thenReturn(emptyOrdersNode());

        // First SL hit
        invokePrivate("checkAllPositionsForProfitTargets", new Class<?>[]{String.class}, "[MAIN]");

        ConcurrentHashMap<String, Integer> slCounts = getField("consecutiveStopLosses");
        assertEquals(1, slCounts.getOrDefault("GOOGL", 0), "Should have 1 consecutive SL");

        ConcurrentHashMap<String, Long> cooldowns = getField("stopLossCooldowns");
        long firstCooldownExpiry = cooldowns.get("GOOGL");

        // Clear pending exit so we can test second SL hit
        ConcurrentHashMap<String, Long> pending = getField("pendingExitOrders");
        pending.remove("GOOGL");

        // Second SL hit
        invokePrivate("checkAllPositionsForProfitTargets", new Class<?>[]{String.class}, "[MAIN]");

        assertEquals(2, slCounts.getOrDefault("GOOGL", 0), "Should have 2 consecutive SLs");

        long secondCooldownExpiry = cooldowns.get("GOOGL");
        // Extended cooldown (4 hours) should be longer than the standard cooldown
        assertTrue(secondCooldownExpiry > firstCooldownExpiry,
                "Second SL should have a longer cooldown than the first");
    }
}
