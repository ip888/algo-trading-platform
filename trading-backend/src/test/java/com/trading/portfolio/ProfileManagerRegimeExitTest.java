package com.trading.portfolio;

import com.trading.analysis.MarketRegimeDetector.MarketRegime;
import com.trading.persistence.TradeDatabase;
import com.trading.risk.TradePosition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Fix 5 — regime-aware exits:
 * When regime turns WEAK_BEAR or STRONG_BEAR:
 *   - Profitable bullish positions must have their stop tightened to breakeven.
 *   - Losing bullish positions (>0.5% down) must be exited immediately.
 *   - Bearish ETFs (e.g. SH) must NOT be touched.
 */
@DisplayName("ProfileManager — regime-aware exit / stop tightening")
class ProfileManagerRegimeExitTest extends ProfileManagerTestBase {

    @BeforeEach
    void setUp() throws Exception {
        setUpCommon();
        when(mockConfig.isMaxLossExitEnabled()).thenReturn(true);
        when(mockConfig.getMaxLossPercent()).thenReturn(2.5);
        when(mockConfig.isPreEarningsExitEnabled()).thenReturn(false);
        when(mockConfig.isTrailingTargetsEnabled()).thenReturn(false);
        when(mockConfig.isTimeDecayExits()).thenReturn(false);
        when(mockConfig.isMomentumAccelerationExits()).thenReturn(false);
        when(mockConfig.isPositionHealthScoring()).thenReturn(false);
        when(mockConfig.getStopLossCooldownMs()).thenReturn(0L);
    }

    /** Places a tracked in-memory position and stubs the broker to return it. */
    private void placePosition(String symbol, double entryPrice, double currentPrice, double qty) {
        double sl = entryPrice * 0.985;
        double tp = entryPrice * 1.03;
        var pos = new TradePosition(symbol, entryPrice, qty, sl, tp,
            Instant.now().minusSeconds(3600), entryPrice, 0);
        portfolio.setPosition(symbol, Optional.of(pos));

        var brokerPosition = brokerPos(symbol, qty, entryPrice, currentPrice);
        when(mockClient.getPositions()).thenReturn(List.of(brokerPosition));

        // DB open record for the symbol
        var rec = new TradeDatabase.OpenTradeRecord(
            symbol, entryPrice, qty, sl, tp, Instant.now().minusSeconds(3600), 0);
        when(mockDatabase.getOpenTradeRecords(anyString())).thenReturn(List.of(rec));
    }

    @Nested
    @DisplayName("Profitable bullish position in WEAK_BEAR — stop tightened to breakeven")
    class ProfitableLongTightened {

        @Test
        @DisplayName("stop is moved to at or above entry price when position is profitable")
        void stopTightenedToBreakeven() throws Exception {
            setField("latestRegime", MarketRegime.WEAK_BEAR);
            // +0.2%: profitable but progress to TP = 0.2/3 = 6.7% < 25% partial-exit threshold
            placePosition("SPY", 500.0, 501.0, 1.0);

            invokeRiskExits();

            var updated = portfolio.getPosition("SPY");
            assertTrue(updated.isPresent(), "Position must still be open after tightening");
            assertTrue(updated.get().stopLoss() >= 500.0,
                "Stop must be >= entry price ($500) after tightening — was: " + updated.get().stopLoss());
        }

        @Test
        @DisplayName("no exit order placed — only stop is adjusted in memory")
        void noExitOrderPlaced() throws Exception {
            setField("latestRegime", MarketRegime.WEAK_BEAR);
            placePosition("SPY", 500.0, 501.0, 1.0);

            invokeRiskExits();

            verify(mockClient, never()).placeOrderDirect(eq("SPY"), anyDouble(),
                eq("sell"), anyString(), anyString(), any());
        }

        @Test
        @DisplayName("same behaviour in STRONG_BEAR regime")
        void worksInStrongBear() throws Exception {
            setField("latestRegime", MarketRegime.STRONG_BEAR);
            placePosition("SPY", 500.0, 501.0, 1.0);

            invokeRiskExits();

            var updated = portfolio.getPosition("SPY");
            assertTrue(updated.isPresent());
            assertTrue(updated.get().stopLoss() >= 500.0);
        }
    }

    @Nested
    @DisplayName("Losing bullish position in WEAK_BEAR — exited immediately")
    class LosingLongExited {

        @Test
        @DisplayName("placeOrderDirect called for a losing long in bearish regime")
        void losingPositionIsExited() throws Exception {
            setField("latestRegime", MarketRegime.WEAK_BEAR);
            placePosition("SPY", 500.0, 494.0, 1.0); // -1.2% — losing and > 0.5% threshold

            invokeRiskExits();

            verify(mockClient, atLeastOnce()).placeOrderDirect(
                eq("SPY"), anyDouble(), eq("sell"), anyString(), anyString(), any());
        }

        @Test
        @DisplayName("position removed from portfolio after regime exit")
        void positionRemovedAfterExit() throws Exception {
            setField("latestRegime", MarketRegime.WEAK_BEAR);
            placePosition("SPY", 500.0, 494.0, 1.0);

            invokeRiskExits();

            assertTrue(portfolio.getPosition("SPY").isEmpty(),
                "Portfolio must have no SPY position after regime exit");
        }

        @Test
        @DisplayName("tiny loss (< 0.5%) is NOT exited — only tightened or left alone")
        void tinyLossNotExited() throws Exception {
            setField("latestRegime", MarketRegime.WEAK_BEAR);
            placePosition("SPY", 500.0, 499.0, 1.0); // -0.2% — below 0.5% exit threshold

            invokeRiskExits();

            verify(mockClient, never()).placeOrderDirect(eq("SPY"), anyDouble(),
                eq("sell"), anyString(), anyString(), any());
        }
    }

    @Nested
    @DisplayName("Bearish ETFs must not be touched in bearish regime")
    class BearishEtfExcluded {

        @Test
        @DisplayName("SH (bearish ETF) stop is NOT tightened even in WEAK_BEAR")
        void bearishEtfNotTouched() throws Exception {
            setField("latestRegime", MarketRegime.WEAK_BEAR);
            // SH is in profile.bearishSymbols(); +0.2% keeps progress below partial-exit threshold
            placePosition("SH", 30.0, 30.06, 5.0);

            double originalStop = portfolio.getPosition("SH").get().stopLoss();
            invokeRiskExits();

            var pos = portfolio.getPosition("SH");
            if (pos.isPresent()) {
                assertEquals(originalStop, pos.get().stopLoss(), 0.001,
                    "Bearish ETF stop must not be modified by the regime check");
            }
            // Position may also remain untouched entirely — both outcomes acceptable
        }
    }

    @Nested
    @DisplayName("No-op in non-bearish regimes")
    class NoopInBullRegime {

        @Test
        @DisplayName("profitable long in WEAK_BULL — stop not tightened to breakeven")
        void noTighteningInBullRegime() throws Exception {
            setField("latestRegime", MarketRegime.WEAK_BULL);
            placePosition("SPY", 500.0, 501.0, 1.0);

            invokeRiskExits();

            var pos = portfolio.getPosition("SPY");
            if (pos.isPresent()) {
                assertTrue(pos.get().stopLoss() <= 500.0,
                    "In WEAK_BULL the stop must not be raised above entry ($500)");
            }
        }
    }
}
