package com.trading.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

/**
 * 2026-11-05: VIX-aware position-count scaling (catalog item) -- scale-DOWN only, never raises
 * the cap above the given base. Config reads env vars / the properties file, not System
 * properties, so these mock the three dependent getters directly rather than trying to flip
 * VIX_POSITION_SCALING_ENABLED via the real resolution path.
 */
@DisplayName("Config.getEffectiveMaxPositions")
class ConfigTest {

    private Config mockEnabled(double threshold, int highVixMax) {
        var cfg = mock(Config.class, withSettings().mockMaker(org.mockito.MockMakers.SUBCLASS));
        when(cfg.isVixPositionScalingEnabled()).thenReturn(true);
        when(cfg.getVixPositionScalingHighThreshold()).thenReturn(threshold);
        when(cfg.getVixPositionScalingHighVixMaxPositions()).thenReturn(highVixMax);
        when(cfg.getEffectiveMaxPositions(anyInt(), anyDouble())).thenCallRealMethod();
        return cfg;
    }

    @Test
    @DisplayName("disabled -- returns the base unchanged regardless of VIX")
    void disabled() {
        var cfg = mock(Config.class, withSettings().mockMaker(org.mockito.MockMakers.SUBCLASS));
        when(cfg.isVixPositionScalingEnabled()).thenReturn(false);
        when(cfg.getEffectiveMaxPositions(anyInt(), anyDouble())).thenCallRealMethod();

        assertEquals(4, cfg.getEffectiveMaxPositions(4, 40.0),
            "disabled -- high VIX must not reduce the cap");
    }

    @Test
    @DisplayName("enabled, VIX below threshold -- base unchanged")
    void enabledBelowThreshold() {
        var cfg = mockEnabled(25.0, 2);
        assertEquals(4, cfg.getEffectiveMaxPositions(4, 20.0));
    }

    @Test
    @DisplayName("enabled, VIX at the threshold -- cap reduced to the high-VIX max")
    void enabledAtThreshold() {
        var cfg = mockEnabled(25.0, 2);
        assertEquals(2, cfg.getEffectiveMaxPositions(4, 25.0));
    }

    @Test
    @DisplayName("enabled, VIX above threshold -- cap reduced to the high-VIX max")
    void enabledAboveThreshold() {
        var cfg = mockEnabled(25.0, 2);
        assertEquals(2, cfg.getEffectiveMaxPositions(4, 40.0));
    }

    @Test
    @DisplayName("never raises the cap -- a base already below the high-VIX max stays as-is")
    void neverRaisesCap() {
        var cfg = mockEnabled(25.0, 2);
        assertEquals(1, cfg.getEffectiveMaxPositions(1, 40.0),
            "a base of 1 (below the high-VIX max of 2) must never be raised");
    }
}
