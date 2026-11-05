package com.trading.bot;

import com.trading.notifications.EmailNotifier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * sendCriticalAlert must never throw into a caller (HeartbeatMonitor/DashboardController call it
 * from inside already-critical paths) regardless of whether an alerter was ever installed.
 */
@DisplayName("TradingBot critical-alert facade")
class TradingBotCriticalAlertTest {

    @Test
    @DisplayName("no alerter installed -- silent no-op")
    void noAlerterInstalled() {
        // Can't assert "null before install" -- other test classes in the same JVM may have
        // already called installEmailAlerter. Only the no-throw contract is deterministic here.
        assertDoesNotThrow(() -> TradingBot.sendCriticalAlert("subject", "body"));
    }

    @Test
    @DisplayName("disabled alerter installed -- silent no-op")
    void disabledAlerterInstalled() {
        TradingBot.installEmailAlerter(new EmailNotifier(null, null, null, null));
        assertDoesNotThrow(() -> TradingBot.sendCriticalAlert("subject", "body"));
    }
}
