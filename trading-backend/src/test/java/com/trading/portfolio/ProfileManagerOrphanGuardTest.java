package com.trading.portfolio;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trading.api.ResilientBrokerClient;
import com.trading.api.model.Position;
import com.trading.config.Config;
import com.trading.exits.ExitStrategyManager;
import com.trading.persistence.TradeDatabase;
import com.trading.risk.TradePosition;
import com.trading.strategy.TradingProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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
 * Tests for two orphan-position guards added in ProfileManager:
 *
 * Fix 2 — SL guard: recovered stop-loss must always be strictly below entry price.
 *   An orphan in a profitable position (currentPrice > entryPrice) would previously
 *   compute a stop above entry, creating a phantom stop trigger on the next dip.
 *
 * Fix 3 — Settlement-lag guard: if the symbol was closed within 15 minutes, skip
 *   orphan registration entirely. Prevents phantom orphan → immediate force-close
 *   sequences caused by T+0 settlement lag at Alpaca.
 */
@DisplayName("ProfileManager — orphan position guards")
class ProfileManagerOrphanGuardTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ProfileManager profileManager;
    private ResilientBrokerClient mockClient;
    private Config mockConfig;
    private TradeDatabase mockDatabase;
    private PortfolioManager portfolio;

    // ── Reflection helpers ──────────────────────────────────────────────────

    private static ProfileManager allocate() throws Exception {
        var unsafeClass = Class.forName("sun.misc.Unsafe");
        var f = unsafeClass.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        var unsafe = f.get(null);
        var m = unsafeClass.getMethod("allocateInstance", Class.class);
        return (ProfileManager) m.invoke(unsafe, ProfileManager.class);
    }

    private void setField(String name, Object value) throws Exception {
        Field f = ProfileManager.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(profileManager, value);
    }

    private void invokeRiskExits() throws Exception {
        Method m = ProfileManager.class.getDeclaredMethod("checkAllPositionsForRiskExits", String.class);
        m.setAccessible(true);
        m.invoke(profileManager, "[MAIN]");
    }

    // ── Test fixtures ───────────────────────────────────────────────────────

    private TradingProfile createMainProfile() {
        return new TradingProfile(
            "MAIN", true, 0.60, 3.0, 1.5, 0.7,
            List.of("SPY", "QQQ"), List.of("SH"),
            20.0, 2.0, "MACD",
            Duration.ofHours(4), Duration.ofDays(5)
        );
    }

    private ResilientBrokerClient createMockClient() {
        return mock(ResilientBrokerClient.class, withSettings()
            .mockMaker(org.mockito.MockMakers.SUBCLASS)
            .defaultAnswer(inv -> {
                if (inv.getMethod().getReturnType() == void.class) return null;
                if (inv.getMethod().getReturnType() == List.class) return List.of();
                if (inv.getMethod().getReturnType() == Optional.class) return Optional.empty();
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
        return cfg;
    }

    /** A broker position: symbol, qty, entry; currentPrice is derived via marketValue. */
    private Position brokerPos(String symbol, double qty, double entryPrice, double currentPrice) {
        return new Position(symbol, qty, currentPrice * qty, entryPrice, (currentPrice - entryPrice) * qty);
    }

    @BeforeEach
    void setUp() throws Exception {
        profileManager = allocate();
        mockClient = createMockClient();
        mockConfig = createMockConfig();
        mockDatabase = mock(TradeDatabase.class, withSettings()
            .mockMaker(org.mockito.MockMakers.SUBCLASS));

        portfolio = new PortfolioManager(List.of("AAPL", "NVDA", "IWM"), 100_000.0);

        // Stub database defaults used in the orphan path
        when(mockDatabase.getOpenTradeRecords(anyString())).thenReturn(List.of());
        // By default, not recently closed — individual tests may override this
        when(mockDatabase.wasRecentlyClosed(anyString(), anyString(), anyLong())).thenReturn(false);

        // Open orders check in orphan path
        ArrayNode emptyOrders = MAPPER.createArrayNode();
        when(mockClient.getOpenOrders(anyString())).thenReturn(emptyOrders);
        // placeNativeStopOrder throws so we don't need a real broker
        doNothing().when(mockClient).placeNativeStopOrder(anyString(), anyDouble(), anyDouble());

        setField("profile", createMainProfile());
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
    }

    // ── Fix 2: SL guard ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("Fix 2 — recovered SL must be below entry price")
    class SlGuard {

        @Test
        @DisplayName("profitable orphan: recovered SL is clamped to below entry (no DB record)")
        void profitableOrphanSlBelowEntry() throws Exception {
            // AAPL at +10% — without the fix, idealStop = max(entry*0.985, current*0.985)
            // = max($98.5, $108.35) = $108.35, which is above entry $100.
            double entryPrice = 100.0;
            double currentPrice = 110.0;
            double qty = 5.0;

            when(mockClient.getPositions()).thenReturn(List.of(
                brokerPos("AAPL", qty, entryPrice, currentPrice)
            ));
            // portfolio has NO tracked position for AAPL → orphan path

            invokeRiskExits();

            Optional<TradePosition> recovered = portfolio.getPosition("AAPL");
            assertTrue(recovered.isPresent(), "Orphan position should be registered in portfolio");

            double sl = recovered.get().stopLoss();
            assertTrue(sl < entryPrice,
                "Recovered SL $" + sl + " must be strictly below entry $" + entryPrice);
        }

        @Test
        @DisplayName("profitable orphan with DB record: corrupted DB SL clamped to below entry")
        void profitableOrphanDbSlClamped() throws Exception {
            // Simulate the IWM/AAPL bug: DB has a stop-loss above entry
            double entryPrice = 269.47;
            double currentPrice = 274.0;
            double qty = 2.0;
            double corruptedSl = 276.63; // above entryPrice — the real bug in prod

            var dbRecord = new TradeDatabase.OpenTradeRecord(
                "IWM", entryPrice, qty, corruptedSl, 277.45,
                java.time.Instant.now().minusSeconds(3600 * 24 * 30), // 30 days ago
                0
            );
            when(mockDatabase.getOpenTradeRecords("alpaca")).thenReturn(List.of(dbRecord));
            when(mockClient.getPositions()).thenReturn(List.of(
                brokerPos("IWM", qty, entryPrice, currentPrice)
            ));

            invokeRiskExits();

            Optional<TradePosition> recovered = portfolio.getPosition("IWM");
            assertTrue(recovered.isPresent(), "Position should be registered");

            double sl = recovered.get().stopLoss();
            assertTrue(sl < entryPrice,
                "DB-loaded SL $" + sl + " (was corrupted: $" + corruptedSl + ") must be clamped below entry $" + entryPrice);
        }

        @Test
        @DisplayName("losing orphan: SL is computed normally (below entry already)")
        void losingOrphanSlUnchanged() throws Exception {
            // Position is underwater by 2% — stays under the 2.5% max-loss threshold so the
            // emergency exit doesn't fire and the position remains registered in portfolio.
            double entryPrice = 100.0;
            double currentPrice = 98.0; // -2%
            double qty = 3.0;

            when(mockClient.getPositions()).thenReturn(List.of(
                brokerPos("NVDA", qty, entryPrice, currentPrice)
            ));

            invokeRiskExits();

            Optional<TradePosition> recovered = portfolio.getPosition("NVDA");
            assertTrue(recovered.isPresent(), "Underwater orphan should still be registered");

            double sl = recovered.get().stopLoss();
            assertTrue(sl < entryPrice,
                "Losing orphan SL $" + sl + " should be below entry $" + entryPrice);
        }
    }

    // ── Fix 3: Settlement-lag guard ──────────────────────────────────────────

    @Nested
    @DisplayName("Fix 3 — settlement-lag guard skips recently-closed symbols")
    class SettlementLagGuard {

        @Test
        @DisplayName("recently-closed symbol is NOT registered as orphan")
        void recentlyClosedSkipped() throws Exception {
            when(mockDatabase.wasRecentlyClosed("AAPL", "alpaca", 15 * 60 * 1000L))
                .thenReturn(true);
            when(mockClient.getPositions()).thenReturn(List.of(
                brokerPos("AAPL", 2.0, 150.0, 152.0)
            ));

            invokeRiskExits();

            assertFalse(portfolio.getPosition("AAPL").isPresent(),
                "Recently-closed symbol must NOT be registered as orphan");
            verify(mockDatabase, never()).recordTrade(
                eq("AAPL"), anyString(), anyString(), anyString(),
                any(), anyDouble(), anyDouble(), anyDouble(), anyDouble());
            verify(mockClient, never()).placeNativeStopOrder(eq("AAPL"), anyDouble(), anyDouble());
        }

        @Test
        @DisplayName("NOT recently-closed symbol IS registered as orphan")
        void notRecentlyClosedRegistered() throws Exception {
            when(mockDatabase.wasRecentlyClosed("NVDA", "alpaca", 15 * 60 * 1000L))
                .thenReturn(false);
            when(mockClient.getPositions()).thenReturn(List.of(
                brokerPos("NVDA", 1.0, 200.0, 198.0) // slight loss, safe SL
            ));

            invokeRiskExits();

            assertTrue(portfolio.getPosition("NVDA").isPresent(),
                "Symbol not recently closed must be registered as orphan");
        }

        @Test
        @DisplayName("settlement check uses the 15-minute window (900_000 ms)")
        void settlementWindowIs15Minutes() throws Exception {
            when(mockClient.getPositions()).thenReturn(List.of(
                brokerPos("IWM", 1.0, 270.0, 268.0)
            ));

            invokeRiskExits();

            verify(mockDatabase).wasRecentlyClosed("IWM", "alpaca", 15 * 60 * 1000L);
        }
    }
}
