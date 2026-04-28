package com.trading.exits;

import com.trading.config.Config;
import com.trading.exits.ExitStrategyManager.ExitDecision;
import com.trading.exits.ExitStrategyManager.ExitType;
import com.trading.risk.TradePosition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Scale-out (Tier 2.6) and time-based stop (Tier 2.7) tests for ExitStrategyManager.
 * Both rely on flags that default to ON in Config, so we just construct it directly.
 */
@DisplayName("ExitStrategyManager — Scale-out (Tier 2.6) and Time-stop (Tier 2.7)")
class ExitStrategyManagerScaleOutAndTimeStopTest {

    private ExitStrategyManager mgr;
    private Config config;

    @BeforeEach
    void setUp() {
        config = new Config();
        mgr = new ExitStrategyManager(config);
    }

    /** Build a position with R = 1.0 ($100 entry, $99 stop, $110 TP) so trigger at 1R = $101. */
    private TradePosition position(Instant entryTime) {
        return new TradePosition("AAPL", 100.0, 10.0, 99.0, 110.0, entryTime);
    }

    @Test
    @DisplayName("scale-out fires once price reaches +1R")
    void scaleOutFiresAt1R() {
        ExitDecision d = mgr.evaluateExit(position(Instant.now()), 101.0, 0.02, new HashMap<>());
        assertEquals(ExitType.SCALE_OUT_1R, d.type());
        assertTrue(d.isPartial());
        assertEquals(config.getScaleOutFraction(), d.quantity(), 1e-9);
        assertTrue(d.partialLevel() > 0, "scale-out must mark a partialLevel for the bitmask");
    }

    @Test
    @DisplayName("scale-out does NOT fire below the trigger")
    void scaleOutSilentBelowTrigger() {
        ExitDecision d = mgr.evaluateExit(position(Instant.now()), 100.50, 0.02, new HashMap<>());
        assertNotEquals(ExitType.SCALE_OUT_1R, d.type());
    }

    @Test
    @DisplayName("scale-out is suppressed once the bitmask slot is set")
    void scaleOutOneShot() {
        TradePosition pos = position(Instant.now());
        ExitDecision first = mgr.evaluateExit(pos, 101.0, 0.02, new HashMap<>());
        assertEquals(ExitType.SCALE_OUT_1R, first.type());

        TradePosition afterMark = pos.markPartialExit(first.partialLevel());
        ExitDecision second = mgr.evaluateExit(afterMark, 101.0, 0.02, new HashMap<>());
        assertNotEquals(ExitType.SCALE_OUT_1R, second.type(), "scale-out must be one-shot per position");
    }

    @Test
    @DisplayName("scale-out does not preempt stop-loss")
    void stopLossWinsOverScaleOut() {
        // Both reachable in theory: at price 99 stop is hit, scale-out trigger 101 isn't.
        ExitDecision d = mgr.evaluateExit(position(Instant.now()), 99.0, 0.02, new HashMap<>());
        assertEquals(ExitType.STOP_LOSS, d.type());
    }

    @Test
    @DisplayName("time-stop fires after N bars when price hasn't moved enough in R")
    void timeStopFires() {
        Instant past = Instant.now().minus(config.getTimeStopBars() * 24L + 1, ChronoUnit.HOURS);
        // Price 100.10 → 0.10R move, well below default 0.5R.
        ExitDecision d = mgr.evaluateExit(position(past), 100.10, 0.02, new HashMap<>());
        assertEquals(ExitType.TIME_STOP, d.type());
        assertFalse(d.isPartial(), "time-stop is a full exit");
    }

    @Test
    @DisplayName("time-stop stays silent if price has moved more than the threshold")
    void timeStopSilentOnDecentMove() {
        Instant past = Instant.now().minus(config.getTimeStopBars() * 24L + 1, ChronoUnit.HOURS);
        // Price 100.80 → 0.80R move, above default 0.5R.
        ExitDecision d = mgr.evaluateExit(position(past), 100.80, 0.02, new HashMap<>());
        assertNotEquals(ExitType.TIME_STOP, d.type());
    }

    @Test
    @DisplayName("time-stop stays silent before the bar threshold")
    void timeStopSilentTooEarly() {
        Instant recent = Instant.now().minus(1, ChronoUnit.HOURS);
        ExitDecision d = mgr.evaluateExit(position(recent), 100.10, 0.02, new HashMap<>());
        assertNotEquals(ExitType.TIME_STOP, d.type());
    }
}
