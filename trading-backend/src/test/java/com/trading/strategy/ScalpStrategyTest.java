package com.trading.strategy;

import com.trading.api.BrokerClient;
import com.trading.api.model.Bar;
import com.trading.config.Config;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockMakers;

import java.time.*;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * ScalpStrategy unit tests.
 *
 * Entry fires when ALL five conditions hold:
 *   1. In scalp time window (9:45–11:30 or 14:00–15:00 ET)
 *   2. RSI crossed above 50 (prev < 50, current ≥ 50)
 *   3. RSI ≤ rsiBuyMax (58)
 *   4. currentPrice ≥ VWAP
 *   5. Last bar volume ≥ 1.3× 20-bar average
 *
 * Tests that exercise the full entry-decision logic use a subclass that overrides
 * computeIndicators() to inject precise RSI/VWAP/volume values, keeping each test
 * focused on exactly one guard condition.
 *
 * Tests that validate the guard checks (disabled, position, daily limit, time window)
 * use the real strategy with a morning-window clock injected via setNowSupplier().
 *
 * Config and BrokerClient use SUBCLASS mock maker — required on Java 25 (ByteBuddy
 * default does not support Java 25).
 */
@DisplayName("ScalpStrategy — 15-min VWAP+RSI+Volume entry")
class ScalpStrategyTest {

    private static final ZoneId ET = ZoneId.of("America/New_York");

    private Config mockConfig;
    private BrokerClient mockClient;
    private ZonedDateTime fixedNow;

    // A ScalpStrategy subclass whose computeIndicators() returns injected values
    // so tests are independent of complex price-series RSI maths.
    private static class ControlledScalpStrategy extends ScalpStrategy {
        private double rsi = 52.0;
        private double rsiPrev = 47.0;
        private double vwap = 498.0; // below currentPrice=500 → priceAboveVwap=true
        private double volumeRatio = 1.5; // above default multiplier 1.3

        ControlledScalpStrategy(BrokerClient client, Config config) {
            super(client, config);
        }

        @Override
        double[] computeIndicators(double currentPrice, List<Bar> bars, List<Bar> todayBars) {
            return new double[]{rsi, rsiPrev, vwap, volumeRatio};
        }

        void setIndicators(double rsi, double rsiPrev, double vwap, double volRatio) {
            this.rsi = rsi;
            this.rsiPrev = rsiPrev;
            this.vwap = vwap;
            this.volumeRatio = volRatio;
        }
    }

    @BeforeEach
    void setUp() {
        mockConfig = mock(Config.class, withSettings().mockMaker(MockMakers.SUBCLASS));
        mockClient = mock(BrokerClient.class, withSettings().mockMaker(MockMakers.SUBCLASS));

        when(mockConfig.isScalpStrategyEnabled()).thenReturn(true);
        when(mockConfig.getScalpStopLossPercent()).thenReturn(0.35);
        when(mockConfig.getScalpTakeProfitPercent()).thenReturn(0.70);
        when(mockConfig.getScalpMaxDailyTrades()).thenReturn(4);
        when(mockConfig.getScalpRsiBuyMin()).thenReturn(45.0);
        when(mockConfig.getScalpRsiBuyMax()).thenReturn(58.0);
        when(mockConfig.getScalpVolumeMultiplier()).thenReturn(1.3);

        // Fix clock to 10:00 AM ET so isInScalpWindow() returns true in every test
        fixedNow = ZonedDateTime.now(ET).withHour(10).withMinute(0).withSecond(0).withNano(0);
    }

    /** Returns a controlled strategy with a fixed morning clock. */
    private ControlledScalpStrategy controlled() throws Exception {
        var s = new ControlledScalpStrategy(mockClient, mockConfig);
        s.setNowSupplier(() -> fixedNow);
        // Return minimal bars so the bar-fetch path doesn't throw NPE
        var minBars = minimalBars(fixedNow);
        when(mockClient.getBars(any(), any(), anyInt())).thenReturn(minBars);
        return s;
    }

    /** Real (non-overriding) strategy with morning clock. */
    private ScalpStrategy realStrategy() {
        var s = new ScalpStrategy(mockClient, mockConfig);
        s.setNowSupplier(() -> fixedNow);
        return s;
    }

    /** 20 minimal bars all today, constant price 500, normal volume. */
    private List<Bar> minimalBars(ZonedDateTime now) {
        List<Bar> bars = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            Instant ts = now.minusMinutes((long)(20 - i) * 15).toInstant();
            bars.add(new Bar(ts, 500.0, 501.0, 499.0, 500.0, 1_000_000L));
        }
        return bars;
    }

    // ── guard tests (no indicator override needed) ────────────────────────────

    @Test
    @DisplayName("disabled config → HOLD immediately without fetching bars")
    void disabledConfig_holdsWithoutBarFetch() throws Exception {
        when(mockConfig.isScalpStrategyEnabled()).thenReturn(false);
        var s = realStrategy();
        var signal = s.evaluate("SPY", 500.0, 0);
        assertInstanceOf(TradingSignal.Hold.class, signal);
        verify(mockClient, never()).getBars(any(), any(), anyInt());
    }

    @Test
    @DisplayName("positionQty > 0 → HOLD without fetching bars")
    void existingPosition_holdsWithoutBarFetch() throws Exception {
        var s = realStrategy();
        var signal = s.evaluate("SPY", 500.0, 0.5);
        assertInstanceOf(TradingSignal.Hold.class, signal);
        verify(mockClient, never()).getBars(any(), any(), anyInt());
    }

    @Test
    @DisplayName("daily limit reached → HOLD without fetching bars")
    void dailyLimitReached_holds() throws Exception {
        var s = realStrategy();
        s.setDailyScalpCount(4, fixedNow.toLocalDate());
        var signal = s.evaluate("SPY", 500.0, 0);
        assertInstanceOf(TradingSignal.Hold.class, signal);
        assertTrue(((TradingSignal.Hold) signal).reason().contains("daily limit"));
        verify(mockClient, never()).getBars(any(), any(), anyInt());
    }

    @Test
    @DisplayName("outside time window → HOLD without fetching bars")
    void outsideWindow_holds() throws Exception {
        var s = realStrategy();
        // Fix to 1:00 PM ET — between the two windows
        s.setNowSupplier(() -> fixedNow.withHour(13).withMinute(0));
        var signal = s.evaluate("SPY", 500.0, 0);
        assertInstanceOf(TradingSignal.Hold.class, signal);
        verify(mockClient, never()).getBars(any(), any(), anyInt());
    }

    @Test
    @DisplayName("daily counter resets on a new day → entry becomes possible again")
    void dailyCounterResetsNextDay() throws Exception {
        var s = controlled();
        // Simulate yesterday having used all 4 slots
        s.setDailyScalpCount(4, fixedNow.toLocalDate().minusDays(1));

        var signal = s.evaluate("SPY", 500.0, 0);
        assertInstanceOf(TradingSignal.ScalpBuy.class, signal,
            "Counter should reset for today, enabling a new scalp");
        assertEquals(1, s.getDailyScalpCount());
    }

    // ── indicator-condition tests (use ControlledScalpStrategy) ──────────────

    @Test
    @DisplayName("all conditions met → ScalpBuy with configured SL/TP")
    void allConditionsMet_scalpBuy() throws Exception {
        var s = controlled();
        // defaults: RSI=52 (in window), rsiPrev=47 (<50, crossover), VWAP=498 (<500), vol=1.5×
        var signal = s.evaluate("SPY", 500.0, 0);

        assertInstanceOf(TradingSignal.ScalpBuy.class, signal,
            "Expected ScalpBuy but got: " + signal);
        var sb = (TradingSignal.ScalpBuy) signal;
        assertEquals(0.35, sb.stopLossPercent(), 0.001);
        assertEquals(0.70, sb.takeProfitPercent(), 0.001);
        assertTrue(sb.reason().contains("Scalp:"));
    }

    @Test
    @DisplayName("RSI did not cross above 50 (rsiPrev ≥ 50) → HOLD")
    void rsiAlreadyAbove50_noCrossover_holds() throws Exception {
        var s = controlled();
        s.setIndicators(52.0, 51.0, 498.0, 1.5); // rsiPrev=51 → no crossover
        var signal = s.evaluate("SPY", 500.0, 0);
        assertFalse(signal instanceof TradingSignal.ScalpBuy,
            "Should not fire when RSI doesn't cross above 50");
    }

    @Test
    @DisplayName("RSI above buy max (>58) → HOLD even with crossover")
    void rsiTooHigh_holds() throws Exception {
        var s = controlled();
        s.setIndicators(62.0, 47.0, 498.0, 1.5); // rsi=62 > max=58
        var signal = s.evaluate("SPY", 500.0, 0);
        assertFalse(signal instanceof TradingSignal.ScalpBuy,
            "Should not fire when RSI is above buy max");
    }

    @Test
    @DisplayName("price below VWAP → HOLD")
    void priceBelowVwap_holds() throws Exception {
        var s = controlled();
        s.setIndicators(52.0, 47.0, 502.0, 1.5); // VWAP=502 > price=500
        var signal = s.evaluate("SPY", 500.0, 0);
        assertFalse(signal instanceof TradingSignal.ScalpBuy,
            "Should not fire when price is below VWAP");
    }

    @Test
    @DisplayName("low volume → HOLD")
    void lowVolume_holds() throws Exception {
        var s = controlled();
        s.setIndicators(52.0, 47.0, 498.0, 0.8); // vol ratio=0.8 < multiplier=1.3
        var signal = s.evaluate("SPY", 500.0, 0);
        assertFalse(signal instanceof TradingSignal.ScalpBuy,
            "Should not fire when volume is below the confirmation threshold");
    }

    // ── helper unit tests ─────────────────────────────────────────────────────

    @Test
    @DisplayName("calculateVWAP: Σ(typical×vol)/Σvol")
    void calculateVwap_correctFormula() throws Exception {
        var s = realStrategy();
        var bars = List.of(
            new Bar(Instant.now(), 100.0, 102.0, 98.0, 100.0, 1_000L),
            new Bar(Instant.now(), 101.0, 103.0, 99.0, 101.0, 2_000L)
        );
        // typical[0] = (102+98+100)/3 = 100.0, typical[1] = (103+99+101)/3 = 101.0
        // VWAP = (100*1000 + 101*2000) / 3000 = 302000/3000 = 100.667
        assertEquals(100.667, s.calculateVWAP(bars), 0.01);
    }

    @Test
    @DisplayName("volumeRatio: lastBar / 20-bar avg (excluding last)")
    void volumeRatio_correctFormula() throws Exception {
        var s = realStrategy();
        // 2 bars: avg of [bar0] = 1000, last = 2000 → ratio = 2.0
        var bars = List.of(
            new Bar(Instant.now(), 100.0, 101.0, 99.0, 100.0, 1_000L),
            new Bar(Instant.now(), 101.0, 102.0, 100.0, 101.0, 2_000L)
        );
        assertEquals(2.0, s.volumeRatio(bars), 0.01);
    }

    @Test
    @DisplayName("isInScalpWindow: morning (10:00 AM) → true")
    void windowCheck_morningTrue() throws Exception {
        var s = realStrategy();
        s.setNowSupplier(() -> fixedNow.withHour(10).withMinute(0));
        assertTrue(s.isInScalpWindow());
    }

    @Test
    @DisplayName("isInScalpWindow: midday (13:00) → false")
    void windowCheck_middayFalse() throws Exception {
        var s = realStrategy();
        s.setNowSupplier(() -> fixedNow.withHour(13).withMinute(0));
        assertFalse(s.isInScalpWindow());
    }

    @Test
    @DisplayName("isInScalpWindow: afternoon (14:30) → true")
    void windowCheck_afternoonTrue() throws Exception {
        var s = realStrategy();
        s.setNowSupplier(() -> fixedNow.withHour(14).withMinute(30));
        assertTrue(s.isInScalpWindow());
    }
}
