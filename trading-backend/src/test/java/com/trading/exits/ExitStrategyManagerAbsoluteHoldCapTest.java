package com.trading.exits;

import com.trading.config.Config;
import com.trading.exits.ExitStrategyManager.ExitDecision;
import com.trading.exits.ExitStrategyManager.ExitType;
import com.trading.risk.TradePosition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests the absolute hold cap introduced in evaluateTimeDecayExit().
 * MAX_ABSOLUTE_HOLD_HOURS (default 240h / 10 days) forces a full exit for ANY position
 * — profitable or losing — held beyond the cap. Prevents indefinite hold of stalled losers.
 */
@DisplayName("ExitStrategyManager — Absolute hold cap (MAX_ABSOLUTE_HOLD_HOURS)")
class ExitStrategyManagerAbsoluteHoldCapTest {

    private ExitStrategyManager mgr;

    // entry=$100, stop=$97 (-3%), TP=$106 (+6%)
    private static TradePosition position(Instant entryTime) {
        return new TradePosition("IWM", 100.0, 5.0, 97.0, 106.0, entryTime);
    }

    private static Config mockConfig(int maxAbsoluteHoldHours, int maxHoldHours) {
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
        when(cfg.getMaxAbsoluteHoldHours()).thenReturn(maxAbsoluteHoldHours);
        when(cfg.getMaxHoldTimeHours()).thenReturn(maxHoldHours);
        // Disable all other exit mechanisms so only time-decay fires in our tests
        when(cfg.isCatastrophicLossExitEnabled()).thenReturn(false);
        when(cfg.isScaleOutEnabled()).thenReturn(false);
        when(cfg.isTimeStopEnabled()).thenReturn(false);
        return cfg;
    }

    @BeforeEach
    void setUp() {
        mgr = new ExitStrategyManager(mockConfig(240, 48));
    }

    @Nested
    @DisplayName("Absolute cap fires regardless of P&L")
    class CapFires {

        @Test
        @DisplayName("losing position held 241 hours → TIME_DECAY force exit")
        void losingPositionBeyondCap() {
            Instant entry = Instant.now().minus(241, ChronoUnit.HOURS);
            // current price = $98 → -2% (below entry, well above SL $97)
            ExitDecision d = mgr.evaluateExit(position(entry), 98.0, 0.01, new HashMap<>());
            assertEquals(ExitType.TIME_DECAY, d.type(),
                "Losing position held 241h must be force-exited by absolute cap");
            assertFalse(d.isPartial(), "Absolute cap must be a full exit");
            assertTrue(d.reason().contains("Absolute hold cap") || d.reason().contains("force exit"),
                "Exit reason should mention absolute hold cap: " + d.reason());
        }

        @Test
        @DisplayName("profitable position held 241 hours → TIME_DECAY force exit")
        void profitablePositionBeyondCap() {
            Instant entry = Instant.now().minus(241, ChronoUnit.HOURS);
            // $101 = +1% profit but only 16.7% of TP ($106), below the 25% partial-exit threshold.
            // Partial exits (priority 3) do not fire, so the absolute cap (priority 5) can reach it.
            ExitDecision d = mgr.evaluateExit(position(entry), 101.0, 0.01, new HashMap<>());
            assertEquals(ExitType.TIME_DECAY, d.type(),
                "Profitable position held 241h must also be force-exited by absolute cap");
        }

        @Test
        @DisplayName("break-even position held 241 hours → TIME_DECAY force exit")
        void breakEvenPositionBeyondCap() {
            Instant entry = Instant.now().minus(241, ChronoUnit.HOURS);
            // current price = $100.10 → ~0.1% (break-even range)
            ExitDecision d = mgr.evaluateExit(position(entry), 100.10, 0.01, new HashMap<>());
            assertEquals(ExitType.TIME_DECAY, d.type(),
                "Break-even position held 241h must be force-exited");
        }
    }

    @Nested
    @DisplayName("Absolute cap does not fire before limit")
    class CapSilent {

        @Test
        @DisplayName("losing position held 239 hours → no absolute cap")
        void losingPositionBeforeCap() {
            Instant entry = Instant.now().minus(239, ChronoUnit.HOURS);
            // -2% loss, held just under the 240h cap
            ExitDecision d = mgr.evaluateExit(position(entry), 98.0, 0.01, new HashMap<>());
            // If TIME_DECAY fired, it must not be from the absolute cap
            if (d.type() == ExitType.TIME_DECAY) {
                assertFalse(d.reason().contains("Absolute hold cap"),
                    "Absolute cap must not fire at 239h: " + d.reason());
            }
        }

        @Test
        @DisplayName("fresh losing position → no absolute cap")
        void freshLosingPosition() {
            Instant entry = Instant.now().minus(1, ChronoUnit.HOURS);
            ExitDecision d = mgr.evaluateExit(position(entry), 98.0, 0.01, new HashMap<>());
            assertNotEquals(ExitType.TIME_DECAY, d.type(),
                "Fresh position must not trigger time-decay");
        }
    }

    @Test
    @DisplayName("stop-loss still takes priority over absolute cap")
    void stopLossWinsOverAbsoluteCap() {
        // Enable SL check by keeping default mock — stop loss is hardcoded in evaluateExit priority
        Instant entry = Instant.now().minus(300, ChronoUnit.HOURS);
        // Price dropped below SL ($96 < $97 stop)
        ExitDecision d = mgr.evaluateExit(position(entry), 96.0, 0.01, new HashMap<>());
        assertEquals(ExitType.STOP_LOSS, d.type(),
            "Stop-loss must still take priority over absolute hold cap");
    }

    @Test
    @DisplayName("configurable cap: 10h cap fires at 11 hours")
    void customCapFiresAtConfiguredThreshold() {
        ExitStrategyManager shortCapMgr = new ExitStrategyManager(mockConfig(10, 48));
        Instant entry = Instant.now().minus(11, ChronoUnit.HOURS);
        ExitDecision d = shortCapMgr.evaluateExit(position(entry), 98.0, 0.01, new HashMap<>());
        assertEquals(ExitType.TIME_DECAY, d.type(),
            "Custom 10h cap must fire at 11h held");
    }
}
