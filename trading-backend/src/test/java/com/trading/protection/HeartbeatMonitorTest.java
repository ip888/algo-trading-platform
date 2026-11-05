package com.trading.protection;

import com.trading.bot.TradingBot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-11-05: a heartbeat miss used to auto-invoke the full EmergencyProtocol (cancel all orders +
 * flatten all positions) with no auto-recovery -- a one-time blip silently halted all new entries
 * for ~5 days. These tests cover the replacement: a soft pause (new entries only, never touches
 * positions) that auto-resumes on recovery, rate-limited so a genuinely recurring failure still
 * surfaces instead of being silently smoothed over forever.
 */
@DisplayName("HeartbeatMonitor soft-pause tests")
class HeartbeatMonitorTest {

    private HeartbeatMonitor monitor;

    @BeforeEach
    void setUp() {
        TradingBot.setHeartbeatPaused(false);
        monitor = new HeartbeatMonitor();
        monitor.registerComponent("Test", Duration.ofMillis(50));
    }

    @AfterEach
    void tearDown() {
        TradingBot.setHeartbeatPaused(false);
    }

    @Test
    @DisplayName("missed heartbeat soft-pauses new entries, never touches positions")
    void missedHeartbeatSoftPauses() throws InterruptedException {
        assertFalse(TradingBot.isHeartbeatPaused());
        Thread.sleep(80); // exceed the 50ms timeout without beating
        monitor.checkHealth();
        assertTrue(TradingBot.isHeartbeatPaused());
        assertFalse(monitor.isHealthy());
    }

    @Test
    @DisplayName("recovery auto-resumes new entries")
    void recoveryAutoResumes() throws InterruptedException {
        Thread.sleep(80);
        monitor.checkHealth();
        assertTrue(TradingBot.isHeartbeatPaused());

        monitor.beat("Test");
        monitor.checkHealth();
        assertFalse(TradingBot.isHeartbeatPaused());
        assertTrue(monitor.isHealthy());
    }

    @Test
    @DisplayName("regular beats within timeout never pause")
    void regularBeatsNeverPause() throws InterruptedException {
        for (int i = 0; i < 3; i++) {
            monitor.beat("Test");
            monitor.checkHealth();
            Thread.sleep(10);
        }
        assertFalse(TradingBot.isHeartbeatPaused());
    }

    @Test
    @DisplayName("auto-resume limit: beyond the daily cap, entries stay paused for manual review")
    void autoResumeRateLimited() throws InterruptedException {
        // Cycle through fail -> recover more times than the daily auto-resume cap allows.
        for (int i = 0; i < 5; i++) {
            Thread.sleep(80);
            monitor.checkHealth(); // fails -> soft pause
            monitor.beat("Test");
            monitor.checkHealth(); // recovers -> auto-resume (up to the cap)
        }
        // After exceeding MAX_AUTO_RESUMES_PER_DAY (3), the last recovery should NOT auto-resume.
        assertTrue(TradingBot.isHeartbeatPaused(),
            "a failure recurring more than the daily cap should stay paused for manual review");
    }
}
