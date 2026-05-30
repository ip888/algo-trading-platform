package com.trading.portfolio;

import com.trading.analysis.MarketRegimeDetector.MarketRegime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Fix 4 — daily loss circuit breaker:
 * handleBuy() must block new entries for the rest of the day once today's
 * realized losses exceed DAILY_MAX_LOSS_PERCENT of account equity.
 */
@DisplayName("ProfileManager — daily loss circuit breaker")
class ProfileManagerDailyLossCircuitTest extends ProfileManagerTestBase {

    @BeforeEach
    void setUp() throws Exception {
        setUpCommon();
        // Disable all other blocking checks so only the daily-loss gate is under test
        when(mockConfig.isNoTradeOpenWindowEnabled()).thenReturn(false);
        when(mockConfig.isEarningsBlackoutEnabled()).thenReturn(false);
        when(mockConfig.isPerSymbolCooldownEnabled()).thenReturn(false);
        when(mockConfig.isVolumeProfileEnabled()).thenReturn(false);
        when(mockConfig.isMLEntryScoringEnabled()).thenReturn(false);
        when(mockConfig.getPdtReserveThreshold()).thenReturn(3);
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

    @Test
    @DisplayName("blocks new entries when today's losses exceed daily max loss threshold")
    void blocksEntryWhenDailyLossExceeded() throws Exception {
        // Equity = $10,000, daily max loss = 3% → limit = -$300
        when(mockConfig.isDailyMaxLossEnabled()).thenReturn(true);
        when(mockConfig.getDailyMaxLossPercent()).thenReturn(3.0);
        setField("todayPnL", -350.0); // -3.5% — over the 3% limit

        invokeBuy("SPY");

        verify(mockClient, never()).placeBracketOrder(eq("SPY"), anyDouble(), anyString(),
            anyDouble(), anyDouble(), any(), any());
        verify(mockClient, never()).placeOrder(eq("SPY"), anyDouble(), anyString(),
            anyString(), anyString(), any());
    }

    @Test
    @DisplayName("allows entries when today's losses are below the threshold")
    void allowsEntryWhenWithinDailyLimit() throws Exception {
        when(mockConfig.isDailyMaxLossEnabled()).thenReturn(true);
        when(mockConfig.getDailyMaxLossPercent()).thenReturn(3.0);
        setField("todayPnL", -100.0); // -1% — well within the 3% limit

        try {
            invokeBuy("SPY");
        } catch (Exception ignored) { /* expected — no full broker setup */ }

        // getPdtReserveThreshold() is the first call inside handleBuy after the daily-loss gate.
        // If it was invoked, we know execution passed the daily-loss guard.
        verify(mockConfig, atLeastOnce()).getPdtReserveThreshold();
    }

    @Test
    @DisplayName("circuit breaker disabled: no block even when losses are large")
    void noBlockWhenCircuitBreakerDisabled() throws Exception {
        when(mockConfig.isDailyMaxLossEnabled()).thenReturn(false);
        setField("todayPnL", -9000.0); // enormous loss but feature is off

        try {
            invokeBuy("SPY");
        } catch (Exception ignored) { /* expected */ }

        verify(mockConfig, atLeastOnce()).getPdtReserveThreshold();
    }

    @Test
    @DisplayName("exactly at threshold: not blocked (exclusive lower bound)")
    void exactlyAtThresholdNotBlocked() throws Exception {
        when(mockConfig.isDailyMaxLossEnabled()).thenReturn(true);
        when(mockConfig.getDailyMaxLossPercent()).thenReturn(3.0);
        // equity=$10,000, 3% = $300 → todayPnL=-300 is exactly at limit, NOT below
        setField("todayPnL", -300.0);

        try {
            invokeBuy("SPY");
        } catch (Exception ignored) { /* expected */ }

        verify(mockConfig, atLeastOnce()).getPdtReserveThreshold();
    }
}
