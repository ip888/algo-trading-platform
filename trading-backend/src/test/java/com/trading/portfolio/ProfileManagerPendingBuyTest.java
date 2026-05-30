package com.trading.portfolio;

import com.trading.analysis.MarketRegimeDetector.MarketRegime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Fix 1 — pendingBuySymbols race guard:
 * A second evaluation cycle must not place a duplicate BUY order while the first
 * order is still in-flight (between placeBracketOrder and database.recordTrade).
 */
@DisplayName("ProfileManager — double-entry race guard (pendingBuySymbols)")
class ProfileManagerPendingBuyTest extends ProfileManagerTestBase {

    @BeforeEach
    void setUp() throws Exception {
        setUpCommon();
        // Make all blocking pre-flight checks pass (no PDT issues, no cooldowns, etc.)
        when(mockConfig.isDailyMaxLossEnabled()).thenReturn(false);
        when(mockConfig.isNoTradeOpenWindowEnabled()).thenReturn(false);
        when(mockConfig.isEarningsBlackoutEnabled()).thenReturn(false);
        when(mockConfig.isPerSymbolCooldownEnabled()).thenReturn(false);
        when(mockConfig.isVolumeProfileEnabled()).thenReturn(false);
        when(mockConfig.isMLEntryScoringEnabled()).thenReturn(false);
        when(mockConfig.getPdtReserveThreshold()).thenReturn(3); // never blocks
        when(mockDatabase.hasOpenTrade(anyString(), anyString())).thenReturn(false);
        when(mockDatabase.countOpenTrades(anyString(), anyString())).thenReturn(0);
        when(mockConfig.getMaxPositionsAtOnce()).thenReturn(5);
    }

    private void invokeBuy(String symbol) throws Exception {
        Method m = ProfileManager.class.getDeclaredMethod(
            "handleBuy", String.class, double.class, double.class,
            double.class, double.class, MarketRegime.class, String.class);
        m.setAccessible(true);
        m.invoke(profileManager, symbol, 500.0, 10_000.0, 5_000.0, 15.0,
            MarketRegime.WEAK_BULL, "[MAIN]");
    }

    @SuppressWarnings("unchecked")
    private ConcurrentHashMap<String, Long> getPendingBuys() throws Exception {
        Field f = ProfileManager.class.getDeclaredField("pendingBuySymbols");
        f.setAccessible(true);
        return (ConcurrentHashMap<String, Long>) f.get(null); // static field
    }

    @Test
    @DisplayName("second handleBuy call is blocked when symbol is in pendingBuySymbols")
    void blocksSecondBuyWhileInFlight() throws Exception {
        // Simulate the first cycle having set the flag (order in-flight, DB not yet written)
        getPendingBuys().put("alpaca:SPY", System.currentTimeMillis());

        invokeBuy("SPY");

        // The broker must NOT receive a second order
        verify(mockClient, never()).placeBracketOrder(eq("SPY"), anyDouble(), anyString(),
            anyDouble(), anyDouble(), any(), any());
        verify(mockClient, never()).placeOrder(eq("SPY"), anyDouble(), anyString(),
            anyString(), anyString(), any());
    }

    @Test
    @DisplayName("buy proceeds normally when pendingBuySymbols does not contain the symbol")
    void allowsBuyWhenNoPendingEntry() throws Exception {
        getPendingBuys().remove("alpaca:SPY"); // ensure clean state

        try {
            invokeBuy("SPY");
        } catch (Exception ignored) { /* expected — no full broker setup */ }

        // getPdtReserveThreshold() is the first config call inside handleBuy after the race guard.
        // If it was invoked, the pendingBuySymbols check was passed.
        verify(mockConfig, atLeastOnce()).getPdtReserveThreshold();
    }

    @Test
    @DisplayName("stale pendingBuySymbols entries are removed by cleanupExpiredCooldowns")
    void staleEntriesAreRemovedByCleanup() throws Exception {
        long expiredTs = System.currentTimeMillis() - 10 * 60 * 1000L; // 10 min ago
        getPendingBuys().put("alpaca:SPY", expiredTs);

        Method cleanup = ProfileManager.class.getDeclaredMethod("cleanupExpiredCooldowns");
        cleanup.setAccessible(true);
        cleanup.invoke(profileManager);

        assertFalse(getPendingBuys().containsKey("alpaca:SPY"),
            "Entry older than TTL (5 min) must be removed by cleanupExpiredCooldowns");
    }

    @Test
    @DisplayName("pendingBuySymbols is keyed by broker:symbol — different broker not blocked")
    void differentBrokerNotBlocked() throws Exception {
        getPendingBuys().put("tradier:SPY", System.currentTimeMillis());

        // alpaca:SPY is NOT in the map → should pass the guard
        try {
            invokeBuy("SPY"); // brokerName is "alpaca" in setUp
        } catch (Exception ignored) { /* expected */ }

        // Guard passed → PDT check is reached
        verify(mockConfig, atLeastOnce()).getPdtReserveThreshold();
    }
}
