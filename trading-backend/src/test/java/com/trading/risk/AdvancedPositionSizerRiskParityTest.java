package com.trading.risk;

import com.trading.config.Config;
import com.trading.persistence.TradeDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Covers calculateAtrPositionSize as used by the 2026-10-29 risk-parity sizing feature
 * (PositionSizer.evaluate() / WalkForwardBacktestHarness.tryEnter() both call this directly with
 * whatever stop is already in force -- VIX-scaled or scalp -- independent of ATR_STOPS_ENABLED).
 * The underlying formula itself is covered by RiskManagerStaticHelpersTest; this covers the
 * wrapper's deployable-capital, min/max-percent capping, which is what actually governs the
 * size risk-parity sizing can produce once wired into PositionSizer.
 */
@DisplayName("AdvancedPositionSizer.calculateAtrPositionSize — risk-parity sizing")
class AdvancedPositionSizerRiskParityTest {

    private static final String DB = "test-risk-parity-sizer.db";
    private TradeDatabase db;
    private Config config;

    @BeforeEach
    void setUp() {
        new File(DB).delete();
        db = new TradeDatabase(DB);
        config = mock(Config.class, withSettings().mockMaker(org.mockito.MockMakers.SUBCLASS)
            .defaultAnswer(inv -> {
                Class<?> rt = inv.getMethod().getReturnType();
                if (rt == double.class) return 0.0;
                if (rt == int.class) return 0;
                if (rt == boolean.class) return false;
                return null;
            }));
        when(config.getAtrSizingRiskPercent()).thenReturn(0.01); // 1% of equity per trade
        when(config.getPositionSizingMaxPercent()).thenReturn(0.20);
        when(config.getPositionSizingMinPercent()).thenReturn(0.02);
        when(config.getSmartCapitalReservePercent()).thenReturn(0.0);
    }

    @AfterEach
    void tearDown() { db.close(); new File(DB).delete(); }

    @Test
    @DisplayName("a wider stop produces a smaller position, for the same target dollar risk")
    void widerStopProducesSmallerPosition() {
        when(config.getPositionSizingMaxPercent()).thenReturn(1.0); // isolate the risk formula from the cap here
        var sizer = new AdvancedPositionSizer(config, db);
        double tightStopSize = sizer.calculateAtrPositionSize("SPY", 10_000, 100.0, 98.0);  // 2% stop
        double wideStopSize = sizer.calculateAtrPositionSize("SPY", 10_000, 100.0, 96.0);   // 4% stop
        assertTrue(wideStopSize < tightStopSize,
            "a position with a stop twice as far away must be sized smaller to risk the same dollars");
    }

    @Test
    @DisplayName("both stops risk approximately the same dollar amount before caps")
    void bothRiskApproximatelyTheSameDollars() {
        when(config.getPositionSizingMaxPercent()).thenReturn(1.0); // isolate the risk formula from the cap here
        var sizer = new AdvancedPositionSizer(config, db);
        double tight = sizer.calculateAtrPositionSize("SPY", 10_000, 100.0, 98.0);
        double wide = sizer.calculateAtrPositionSize("SPY", 10_000, 100.0, 96.0);
        // $ risk = shares * (entry - stop); both should be ~$100 (1% of $10,000)
        assertEquals(100.0, tight * 2.0, 1.0);
        assertEquals(100.0, wide * 4.0, 1.0);
    }

    @Test
    @DisplayName("respects the configured max-position-percent cap even for a very tight stop")
    void respectsMaxPercentCap() {
        var sizer = new AdvancedPositionSizer(config, db);
        // Stop just 0.01% away -> raw formula would ask for a huge position; must cap at 20% of equity.
        double size = sizer.calculateAtrPositionSize("SPY", 10_000, 100.0, 99.99);
        double maxShares = (10_000 * 0.20) / 100.0;
        assertEquals(maxShares, size, 0.01);
    }

    @Test
    @DisplayName("invalid stop (at or above entry price) returns zero, not a negative or infinite size")
    void invalidStop_returnsZero() {
        var sizer = new AdvancedPositionSizer(config, db);
        assertEquals(0.0, sizer.calculateAtrPositionSize("SPY", 10_000, 100.0, 100.0));
        assertEquals(0.0, sizer.calculateAtrPositionSize("SPY", 10_000, 100.0, 101.0));
    }
}
