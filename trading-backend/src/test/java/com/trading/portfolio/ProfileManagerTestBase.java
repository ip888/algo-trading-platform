package com.trading.portfolio;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trading.api.ResilientBrokerClient;
import com.trading.api.model.Position;
import com.trading.config.Config;
import com.trading.exits.ExitStrategyManager;
import com.trading.persistence.TradeDatabase;
import com.trading.strategy.TradingProfile;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Shared scaffolding for ProfileManager unit tests.
 * Uses sun.misc.Unsafe to allocate ProfileManager without invoking its constructor
 * (which requires a fully-wired dependency graph).
 *
 * Note: BrokerClient is an interface — mocking it via ByteBuddy fails on Java 25.
 * getDelegate() is left returning null (the SUBCLASS mock's default); calls to it
 * are wrapped in try/catch in production code and degrade gracefully.
 */
abstract class ProfileManagerTestBase {

    static final ObjectMapper MAPPER = new ObjectMapper();

    ProfileManager profileManager;
    ResilientBrokerClient mockClient;
    Config mockConfig;
    TradeDatabase mockDatabase;
    PortfolioManager portfolio;

    // ── Reflection helpers ──────────────────────────────────────────────────

    static ProfileManager allocate() throws Exception {
        var unsafeClass = Class.forName("sun.misc.Unsafe");
        var f = unsafeClass.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        var unsafe = f.get(null);
        var m = unsafeClass.getMethod("allocateInstance", Class.class);
        return (ProfileManager) m.invoke(unsafe, ProfileManager.class);
    }

    void setField(String name, Object value) throws Exception {
        Field f = ProfileManager.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(profileManager, value);
    }

    Object getField(String name) throws Exception {
        Field f = ProfileManager.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(profileManager);
    }

    void invokeRiskExits() throws Exception {
        Method m = ProfileManager.class.getDeclaredMethod("checkAllPositionsForRiskExits", String.class);
        m.setAccessible(true);
        m.invoke(profileManager, "[MAIN]");
    }

    // ── Common fixtures ─────────────────────────────────────────────────────

    TradingProfile mainProfile() {
        return new TradingProfile(
            "MAIN", true, 0.60, 3.0, 1.5, 0.7,
            List.of("SPY", "QQQ", "AAPL"), List.of("SH", "PSQ"),
            20.0, 2.0, "MACD",
            Duration.ofHours(4), Duration.ofDays(5)
        );
    }

    ResilientBrokerClient mockClient() {
        // SUBCLASS mock maker works on Java 25; the default ByteBuddy-based maker does not.
        // getDelegate() returns null by default — callers wrap it in try/catch.
        return mock(ResilientBrokerClient.class, withSettings()
            .mockMaker(org.mockito.MockMakers.SUBCLASS)
            .defaultAnswer(inv -> {
                Class<?> rt = inv.getMethod().getReturnType();
                if (rt == void.class) return null;
                if (rt == List.class) return List.of();
                if (rt == Optional.class) return Optional.empty();
                return null;
            }));
    }

    Config mockConfig() {
        var cfg = mock(Config.class, withSettings()
            .mockMaker(org.mockito.MockMakers.SUBCLASS)
            .defaultAnswer(inv -> {
                Class<?> rt = inv.getMethod().getReturnType();
                if (rt == boolean.class) return false;
                if (rt == int.class) return 0;
                if (rt == long.class) return 0L;
                if (rt == double.class) return 0.0;
                return null;
            }));
        when(cfg.isMaxLossExitEnabled()).thenReturn(true);
        when(cfg.getMaxLossPercent()).thenReturn(2.5);
        when(cfg.getMaxHoldTimeHours()).thenReturn(48);
        when(cfg.isPreEarningsExitEnabled()).thenReturn(false);
        when(cfg.isTrailingTargetsEnabled()).thenReturn(false);
        when(cfg.isTimeDecayExits()).thenReturn(false);
        when(cfg.isMomentumAccelerationExits()).thenReturn(false);
        when(cfg.isPositionHealthScoring()).thenReturn(false);
        when(cfg.getStopLossCooldownMs()).thenReturn(0L);
        // Prevent the absolute-hold-cap from firing (default 0L causes every position to exit
        // immediately because holdTime.toHours() >= 0 is always true).
        when(cfg.getMaxAbsoluteHoldHours()).thenReturn(240); // 10 days
        return cfg;
    }

    /** Helper: a broker-side position. currentPrice derived from marketValue. */
    Position brokerPos(String symbol, double qty, double entryPrice, double currentPrice) {
        return new Position(symbol, qty, currentPrice * qty, entryPrice, (currentPrice - entryPrice) * qty);
    }

    void setUpCommon() throws Exception {
        profileManager = allocate();
        mockClient  = mockClient();
        mockConfig  = mockConfig();
        mockDatabase = mock(TradeDatabase.class, withSettings()
            .mockMaker(org.mockito.MockMakers.SUBCLASS));

        portfolio = new PortfolioManager(List.of("SPY", "QQQ", "AAPL", "NVDA"), 100_000.0);

        when(mockDatabase.getOpenTradeRecords(anyString())).thenReturn(List.of());
        when(mockDatabase.wasRecentlyClosed(anyString(), anyString(), anyLong())).thenReturn(false);
        when(mockClient.getOpenOrders(anyString())).thenReturn(MAPPER.createArrayNode());
        doNothing().when(mockClient).placeNativeStopOrder(anyString(), anyDouble(), anyDouble());

        setField("profile", mainProfile());
        setField("capital", 10_000.0);
        setField("client", mockClient);
        setField("config", mockConfig);
        setField("database", mockDatabase);
        setField("portfolio", portfolio);
        setField("exitStrategyManager", new ExitStrategyManager(mockConfig));
        setField("phase2ExitStrategies", new com.trading.exits.Phase2ExitStrategies(mockConfig));
        setField("trailingTargetManager", new com.trading.exits.TrailingTargetManager(mockConfig));
        setField("orderTypeSelector", new com.trading.execution.SmartOrderTypeSelector());
        setField("latestVix", 15.0);
        setField("latestEquity", 10_000.0);
        setField("running", true);
        setField("brokerName", "alpaca");
        setField("stopLossCooldowns", new ConcurrentHashMap<String, Long>());
        setField("pendingExitOrders", new ConcurrentHashMap<String, Long>());
        setField("consecutiveStopLosses", new ConcurrentHashMap<String, Integer>());
        setField("latestRegime", com.trading.analysis.MarketRegimeDetector.MarketRegime.RANGE_BOUND);
        setField("todayPnL", 0.0);
        // PDTProtection is used in handleBuy; allocate a real (disabled) instance so it doesn't NPE.
        setField("pdtProtection",
            new com.trading.protection.PDTProtection(mockDatabase, false, "alpaca"));
        // MarketBreadthAnalyzer is used in handleBuy Phase 3; instantiate with mockConfig
        // (isMarketBreadthFilter() returns false → isMarketHealthy() always returns true).
        setField("marketBreadthAnalyzer",
            new com.trading.analysis.MarketBreadthAnalyzer(mockConfig));
    }
}
