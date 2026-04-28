package com.trading.risk;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("RiskManager static ATR helpers (Tier 1.2 / 1.3)")
class RiskManagerStaticHelpersTest {

    @Test
    @DisplayName("ATR stop = entry − atr × multiplier within bounds")
    void atrStopHappyPath() {
        double stop = RiskManager.calculateAtrStopLoss(100, 1.0, 2.0, 0.5, 5.0);
        assertEquals(98.0, stop, 1e-9);
    }

    @Test
    @DisplayName("ATR stop is clamped by floorPct so dust ATRs don't yield 0.05% stops")
    void atrStopClampedToFloor() {
        double stop = RiskManager.calculateAtrStopLoss(100, 0.001, 2.0, 0.5, 5.0);
        assertEquals(99.5, stop, 1e-9);
    }

    @Test
    @DisplayName("ATR stop is clamped by ceilingPct so crash-day ATRs don't yield 15% stops")
    void atrStopClampedToCeiling() {
        double stop = RiskManager.calculateAtrStopLoss(100, 10.0, 2.0, 0.5, 5.0);
        assertEquals(95.0, stop, 1e-9);
    }

    @Test
    @DisplayName("Invalid inputs fall back to floor stop")
    void atrStopInvalidInputsFallback() {
        double stop = RiskManager.calculateAtrStopLoss(100, 0, 2.0, 0.5, 5.0);
        assertEquals(99.5, stop, 1e-9);
    }

    @Test
    @DisplayName("ATR take-profit = entry + atr × multiplier with floor")
    void atrTakeProfitHappyPath() {
        double tp = RiskManager.calculateAtrTakeProfit(100, 1.0, 4.0, 0.5);
        assertEquals(104.0, tp, 1e-9);
    }

    @Test
    @DisplayName("ATR take-profit honours minimum distance via floorPct")
    void atrTakeProfitFloor() {
        double tp = RiskManager.calculateAtrTakeProfit(100, 0.001, 4.0, 0.5);
        assertEquals(100.5, tp, 1e-9);
    }

    @Test
    @DisplayName("Vol-targeted size = (equity × risk) / (entry − stop)")
    void volTargetedSizeBasic() {
        double shares = RiskManager.calculateVolTargetedSize(10_000, 100, 98, 0.01);
        assertEquals(50.0, shares, 1e-9);
    }

    @Test
    @DisplayName("Vol-targeted size returns 0 when stop ≥ entry")
    void volTargetedSizeRejectsBadStop() {
        assertEquals(0.0, RiskManager.calculateVolTargetedSize(10_000, 100, 100, 0.01));
        assertEquals(0.0, RiskManager.calculateVolTargetedSize(10_000, 100, 110, 0.01));
    }

    @Test
    @DisplayName("Vol-targeted size returns 0 for non-positive equity / risk")
    void volTargetedSizeRejectsBadInputs() {
        assertEquals(0.0, RiskManager.calculateVolTargetedSize(0, 100, 98, 0.01));
        assertEquals(0.0, RiskManager.calculateVolTargetedSize(10_000, 100, 98, 0));
    }
}
