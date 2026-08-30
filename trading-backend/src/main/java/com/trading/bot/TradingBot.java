package com.trading.bot;

import com.trading.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Trading bot application entry point.
 *
 * <p>Also hosts a handful of process-wide static controls (safety autopilot, manual
 * pause/panic) that the dashboard and {@link com.trading.portfolio.ProfileManager} reach into
 * from outside — these stay here rather than on {@link MultiBrokerOrchestrator} because they
 * need to be reachable before that class's {@code run()} constructs anything, and because a
 * static home matches how the dashboard already calls them (see each method's Javadoc).
 */
public final class TradingBot {
    private static final Logger logger = LoggerFactory.getLogger(TradingBot.class);

    // Start time for uptime tracking
    private static final long START_TIME = System.currentTimeMillis();
    // Session start equity — set once from live Alpaca account, used for P&L display
    private static volatile double SESSION_START_CAPITAL = 0.0;

    // Safety components — null until installSafetyAutopilot() is called; every accessor below
    // guards against that (no-ops rather than throwing) so a bot run that forgets to install
    // them degrades gracefully instead of crashing on every dashboard heartbeat/panic call.
    private static com.trading.protection.HeartbeatMonitor heartbeatMonitor;
    private static com.trading.protection.EmergencyProtocol emergencyProtocol;

    public static long getStartTime() {
        return START_TIME;
    }

    public static double getSessionStartCapital() {
        return SESSION_START_CAPITAL;
    }

    /**
     * Wires the heartbeat dead-man's-switch and manual panic-stop into whichever code path is
     * actually running the bot. {@code beat()}/{@code isEmergencyTriggered()}/
     * {@code triggerManualPanic()} below all silently no-op while these are null — which is
     * exactly what was happening in production: this bot's only live entry point is
     * {@link MultiBrokerOrchestrator}, and nothing called this setup before it existed, so the
     * dashboard's panic button returned an error instead of flattening positions. Call this once,
     * early, from whichever run path is actually live.
     */
    public static void installSafetyAutopilot(com.trading.protection.HeartbeatMonitor monitor,
                                                com.trading.protection.EmergencyProtocol protocol) {
        heartbeatMonitor = monitor;
        emergencyProtocol = protocol;
    }

    /**
     * Entry point. {@link MultiBrokerOrchestrator} (activated by the {@code BROKERS} env var,
     * e.g. {@code BROKERS=alpaca:100}) is this bot's only supported run path — it is what
     * actually runs in production.
     *
     * <p>Two other run paths (a dual MAIN+EXPERIMENTAL-profile mode, and a legacy
     * single-profile mode that predates {@link com.trading.portfolio.ProfileManager} entirely)
     * used to live here and were removed 2026-08-29. This bot now runs a single Alpaca account
     * only — the removed paths existed for a multi-profile/multi-broker design this deployment
     * no longer uses, and their static, cross-instance-shared state
     * ({@code ProfileManager}'s "shared across profiles" fields) was the direct cause of at
     * least one real double-buy loss (see that class's refactor notes). Keeping unreachable
     * code that *looks* like a supported alternative is itself a risk: a future config change
     * (unsetting {@code BROKERS}) would have silently resurrected a code path nobody had
     * tested in a long time, wired to a completely different safety-system setup (see
     * {@link #installSafetyAutopilot}). Removing it outright is safer than leaving it dormant.
     */
    public static void main(String[] args) {
        var config = new Config();
        if (!config.isValid()) {
            logger.error("Invalid configuration - missing API credentials");
            System.exit(1);
        }

        if (!config.isMultiBrokerEnabled()) {
            logger.error("BROKERS env var is not set (expected e.g. BROKERS=alpaca:100). "
                + "This is the only supported run mode — aborting rather than falling back "
                + "to an untested legacy path.");
            System.exit(1);
        }

        logger.info("Starting Trading Bot ({})", config.getBrokersAllocation());
        new MultiBrokerOrchestrator(config).run();
    }

    /** Called by the dashboard's /api/emergency/panic endpoint — cancels all orders and flattens all positions. */
    public static java.util.Map<String, Object> triggerManualPanic(String reason) {
        if (emergencyProtocol != null) {
            return emergencyProtocol.trigger(reason);
        } else {
            logger.error("Cannot trigger panic: EmergencyProtocol not initialized");
            return java.util.Map.of("status", "error", "message", "EmergencyProtocol not initialized");
        }
    }

    /** Called by the dashboard's /api/emergency/reset endpoint — re-arms the panic protocol after a manual review. */
    public static java.util.Map<String, Object> resetEmergencyProtocol() {
        if (emergencyProtocol != null) {
            return emergencyProtocol.reset();
        } else {
            logger.error("Cannot reset: EmergencyProtocol not initialized");
            return java.util.Map.of("status", "error", "message", "EmergencyProtocol not initialized");
        }
    }

    public static boolean isEmergencyTriggered() {
        return emergencyProtocol != null && emergencyProtocol.isTriggered();
    }

    private static final java.util.concurrent.atomic.AtomicBoolean tradingPaused =
        new java.util.concurrent.atomic.AtomicBoolean(false);

    public static boolean pauseTrading() {
        boolean changed = tradingPaused.compareAndSet(false, true);
        if (changed) logger.warn("⏸ TRADING PAUSED by dashboard request");
        return changed;
    }

    public static boolean resumeTrading() {
        boolean changed = tradingPaused.compareAndSet(true, false);
        if (changed) logger.info("▶ TRADING RESUMED by dashboard request");
        return changed;
    }

    public static boolean isTradingPaused() {
        return tradingPaused.get();
    }

    public static Map<String, Long> getHeartbeatDetails() {
        if (heartbeatMonitor != null) {
            return heartbeatMonitor.getDetails();
        }
        return new HashMap<>();
    }

    public static void beat(String component) {
        if (heartbeatMonitor != null) {
            heartbeatMonitor.beat(component);
        }
    }

    public static boolean isSystemHealthy() {
        if (heartbeatMonitor != null) {
            return heartbeatMonitor.isHealthy();
        }
        return true; // Default to true if monitor not active
    }
}
