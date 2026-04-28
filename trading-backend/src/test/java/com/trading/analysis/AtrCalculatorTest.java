package com.trading.analysis;

import com.trading.api.model.Bar;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("AtrCalculator Tests (Tier 1.2)")
class AtrCalculatorTest {

    private static Bar bar(double o, double h, double l, double c) {
        return new Bar(Instant.now(), o, h, l, c, 1_000L);
    }

    @Test
    @DisplayName("returns 0 when bars are insufficient for the period")
    void emptyOnTooFewBars() {
        assertEquals(0.0, AtrCalculator.atr(List.of(), 14));
        var bars = new ArrayList<Bar>();
        for (int i = 0; i < 5; i++) bars.add(bar(100, 101, 99, 100));
        assertEquals(0.0, AtrCalculator.atr(bars, 14));
    }

    @Test
    @DisplayName("constant-range bars produce a constant ATR equal to that range")
    void constantRangeProducesConstantAtr() {
        var bars = new ArrayList<Bar>();
        // 20 bars, every bar has range exactly 1.0 and identical closes — TR == 1.0 each.
        for (int i = 0; i < 20; i++) bars.add(bar(100, 100.5, 99.5, 100));
        double atr = AtrCalculator.atr(bars, 14);
        assertEquals(1.0, atr, 1e-9);
    }

    @Test
    @DisplayName("gap-up bar is captured by max(high − prevClose, ...)")
    void gapUpIsCaptured() {
        var bars = new ArrayList<Bar>();
        for (int i = 0; i < 14; i++) bars.add(bar(100, 100.5, 99.5, 100));
        // Big gap-up bar: prev close 100, new low 105, high 106.
        // TR = max(106-105, |106-100|, |105-100|) = 6.0.
        bars.add(bar(105, 106, 105, 105.5));
        double atr = AtrCalculator.atr(bars, 14);
        assertTrue(atr > 1.0, "Gap-up should pull ATR above the 1.0 baseline; got " + atr);
    }

    @Test
    @DisplayName("atrPercent is atr / lastClose")
    void atrPercentDividesByLastClose() {
        var bars = new ArrayList<Bar>();
        for (int i = 0; i < 20; i++) bars.add(bar(100, 100.5, 99.5, 100));
        double pct = AtrCalculator.atrPercent(bars, 14);
        assertEquals(0.01, pct, 1e-9);
    }

    @Test
    @DisplayName("atrPercent returns 0 when atr cannot be computed")
    void atrPercentEmptyOnInsufficientBars() {
        var bars = new ArrayList<Bar>();
        for (int i = 0; i < 3; i++) bars.add(bar(100, 101, 99, 100));
        assertEquals(0.0, AtrCalculator.atrPercent(bars, 14));
    }
}
