package com.trading.portfolio;

import com.trading.analysis.MarketRegimeDetector.MarketRegime;
import com.trading.risk.CapitalTierManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Fix 2 — position sizing hard cap:
 * handleBuy() must never send a bracket order whose notional value (qty × price)
 * exceeds equity × tierMaxPositionPercent, regardless of what the position sizer returns.
 *
 * Verifies via the qty argument captured on client.placeBracketOrder().
 */
@DisplayName("ProfileManager — position sizing hard notional cap")
class ProfileManagerHardCapTest extends ProfileManagerTestBase {

    @BeforeEach
    void setUp() throws Exception {
        setUpCommon();
        // Disable every blocking pre-flight check so handleBuy reaches sizing
        when(mockConfig.isDailyMaxLossEnabled()).thenReturn(false);
        when(mockConfig.isNoTradeOpenWindowEnabled()).thenReturn(false);
        when(mockConfig.isEarningsBlackoutEnabled()).thenReturn(false);
        when(mockConfig.isPerSymbolCooldownEnabled()).thenReturn(false);
        when(mockConfig.isVolumeProfileEnabled()).thenReturn(false);
        when(mockConfig.isMLEntryScoringEnabled()).thenReturn(false);
        when(mockConfig.getPdtReserveThreshold()).thenReturn(3);
        when(mockDatabase.hasOpenTrade(anyString(), anyString())).thenReturn(false);
        when(mockDatabase.countOpenTrades(anyString(), anyString())).thenReturn(0);
        when(mockConfig.getMaxPositionsAtOnce()).thenReturn(5);

        // Use flat % sizing (no ATR) to keep test predictable
        when(mockConfig.isAtrStopsEnabled()).thenReturn(false);
        when(mockConfig.isAtrSizingEnabled()).thenReturn(false);
        when(mockConfig.getPositionSizingMethod()).thenReturn("PERCENT");
        when(mockConfig.getPositionSizingMaxPercent()).thenReturn(0.99); // intentionally huge
        when(mockConfig.getMainStopLossPercent()).thenReturn(1.5);
        when(mockConfig.getMainTakeProfitPercent()).thenReturn(3.0);
        when(mockConfig.getPositionSizingFixedPercent()).thenReturn(0.99); // force huge raw size
    }

    private void invokeBuy(String symbol, double price, double equity) throws Exception {
        setField("latestEquity", equity);
        Method m = ProfileManager.class.getDeclaredMethod(
            "handleBuy", String.class, double.class, double.class,
            double.class, double.class, MarketRegime.class, String.class);
        m.setAccessible(true);
        m.invoke(profileManager, symbol, price, equity, equity * 0.5, 15.0,
            MarketRegime.WEAK_BULL, "[MAIN]");
    }

    @Test
    @DisplayName("order quantity never exceeds tierMaxPositionPercent of equity")
    void quantityRespectsTierCap() throws Exception {
        double equity = 1_200.0;   // SMALL tier: 35% max → $420 cap
        double price  = 647.0;     // QQQ-like price — would produce huge qty without cap
        double maxAllowed = CapitalTierManager.getParameters(equity).maxPositionPercent();

        try {
            invokeBuy("QQQ", price, equity);
        } catch (Exception ignored) { /* broker mock may throw — we care about qty */ }

        // Inspect every placeBracketOrder call and assert notional ≤ tier cap
        var calls = org.mockito.Mockito.mockingDetails(mockClient).getInvocations().stream()
            .filter(inv -> inv.getMethod().getName().equals("placeBracketOrder"))
            .toList();

        for (var call : calls) {
            double qty = (double) call.getArguments()[1];
            double notional = qty * price;
            assertTrue(notional <= equity * maxAllowed + 0.01,
                String.format("Notional $%.2f exceeded hard cap $%.2f (qty=%.4f @ $%.2f)",
                    notional, equity * maxAllowed, qty, price));
        }
    }

    @Test
    @DisplayName("CapitalTierManager SMALL tier: maxPositionPercent = 35% for $1,200 account")
    void smallTierMaxIs35Percent() {
        var params = CapitalTierManager.getParameters(1_200.0);
        assertEquals(0.35, params.maxPositionPercent(), 0.001,
            "SMALL tier ($500-$2K) must have 35% max position size");
    }

    @Test
    @DisplayName("CapitalTierManager MICRO tier: maxPositionPercent = 50% for $200 account")
    void microTierMaxIs50Percent() {
        var params = CapitalTierManager.getParameters(200.0); // < $250 = MICRO
        assertEquals(0.50, params.maxPositionPercent(), 0.001,
            "MICRO tier (< $250) allows 50% per position (only 2 concurrent positions)");
    }
}
