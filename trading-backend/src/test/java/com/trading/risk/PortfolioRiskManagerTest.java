package com.trading.risk;

import com.trading.config.Config;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PortfolioRiskManagerTest {

    private static final double INITIAL_CAPITAL = 10_000.0;
    private static final double STOP_LOSS_PCT = 5.0; // 5%

    /** Minimal Config subclass — overrides only the two methods under test. */
    private static Config configWith(boolean enabled, double stopLossPct) {
        return new Config() {
            @Override public boolean isPortfolioStopLossEnabled() { return enabled; }
            @Override public double getPortfolioStopLossPercent()  { return stopLossPct; }
        };
    }

    private PortfolioRiskManager riskManager;

    @BeforeEach
    void setUp() {
        riskManager = new PortfolioRiskManager(configWith(true, STOP_LOSS_PCT), INITIAL_CAPITAL);
    }

    @Test
    void shouldHaltTrading_whenDisabled_returnsAlwaysFalse() {
        var manager = new PortfolioRiskManager(configWith(false, STOP_LOSS_PCT), INITIAL_CAPITAL);
        assertFalse(manager.shouldHaltTrading(INITIAL_CAPITAL * 0.5)); // Even 50% loss allowed
    }

    @Test
    void shouldHaltTrading_profitablePortfolio_returnsFalse() {
        assertFalse(riskManager.shouldHaltTrading(INITIAL_CAPITAL * 1.10)); // +10%
    }

    @Test
    void shouldHaltTrading_smallLossUnderLimit_returnsFalse() {
        double equity = INITIAL_CAPITAL * 0.97; // -3% loss, under 5% limit
        assertFalse(riskManager.shouldHaltTrading(equity));
    }

    @Test
    void shouldHaltTrading_exactlyAtLimit_returnsFalse() {
        // -5.0% exactly equals limit, not strictly less than
        double equity = INITIAL_CAPITAL * (1 - STOP_LOSS_PCT / 100);
        assertFalse(riskManager.shouldHaltTrading(equity));
    }

    @Test
    void shouldHaltTrading_justBeyondLimit_returnsTrue() {
        double equity = INITIAL_CAPITAL * 0.9499; // -5.01% → beyond 5% limit
        assertTrue(riskManager.shouldHaltTrading(equity));
    }

    @Test
    void shouldHaltTrading_largeDrawdown_returnsTrue() {
        double equity = INITIAL_CAPITAL * 0.80; // -20% loss
        assertTrue(riskManager.shouldHaltTrading(equity));
    }

    @Test
    void shouldHaltTrading_zeroEquity_returnsTrue() {
        assertTrue(riskManager.shouldHaltTrading(0.0));
    }

    @Test
    void shouldHaltTrading_sameAsInitial_returnsFalse() {
        assertFalse(riskManager.shouldHaltTrading(INITIAL_CAPITAL)); // 0% loss
    }

    @Test
    void shouldHaltTrading_approachingWarningZone_returnsFalse() {
        // 80% of 5% = 4% warning zone threshold — still should not halt
        double equity = INITIAL_CAPITAL * 0.961; // -3.9% — approaching warning
        assertFalse(riskManager.shouldHaltTrading(equity));
    }

    @Test
    void shouldHaltTrading_differentStopLossPercent_respectsConfig() {
        var manager = new PortfolioRiskManager(configWith(true, 10.0), INITIAL_CAPITAL);
        assertFalse(manager.shouldHaltTrading(INITIAL_CAPITAL * 0.95)); // -5% under 10% limit
        assertTrue(manager.shouldHaltTrading(INITIAL_CAPITAL * 0.85));  // -15% exceeds 10% limit
    }
}
