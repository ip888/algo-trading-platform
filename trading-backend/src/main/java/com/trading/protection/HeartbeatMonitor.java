package com.trading.protection;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Monitors the health of various system components by tracking "heartbeat" timestamps.
 * If any component fails to report in within its specified timeout, new entries are soft-paused
 * until it recovers.
 *
 * <p>2026-11-05: found live that a heartbeat miss used to auto-invoke {@link EmergencyProtocol} —
 * the SAME path as the manual panic button, which cancels every order and force-liquidates every
 * open position at market price. That is wildly disproportionate to a single transient blip (one
 * missed heartbeat from one component), and because the resulting trigger had no auto-recovery, a
 * one-time blip on 2026-10-31 silently halted all new entries for ~5 days until a human found and
 * reset it. Per explicit user decision: a heartbeat failure now only soft-pauses new entries via
 * {@link com.trading.bot.TradingBot#setHeartbeatPaused} — existing positions and exits are
 * completely unaffected, and it auto-resumes the moment health recovers. The full flatten-all
 * {@link EmergencyProtocol} is reachable only through the manual panic button (a human decision),
 * never automatically from here.
 */
public class HeartbeatMonitor {
    private static final Logger logger = LoggerFactory.getLogger(HeartbeatMonitor.class);

    // Caps how many times per day this will auto-pause-then-auto-resume before it instead leaves
    // new entries paused and escalates to a louder alert -- a failure that keeps recurring is a
    // real problem worth a human look, not something to keep silently smoothing over.
    private static final int MAX_AUTO_RESUMES_PER_DAY = 3;

    private final Map<String, ComponentStatus> components = new ConcurrentHashMap<>();
    private final AtomicBoolean isSystemHealthy = new AtomicBoolean(true);

    private final java.util.concurrent.atomic.AtomicInteger autoResumesToday =
        new java.util.concurrent.atomic.AtomicInteger(0);
    private volatile java.time.LocalDate autoResumeDate = java.time.LocalDate.now();

    /**
     * Register a component to be monitored.
     * @param componentName Unique name of the component
     * @param timeout Max allowed time between heartbeats
     */
    public void registerComponent(String componentName, Duration timeout) {
        components.put(componentName, new ComponentStatus(timeout));
        logger.info("Registered monitored component: {} (Timeout: {}s)", componentName, timeout.toSeconds());
    }

    /**
     * Record a heartbeat for a specific component.
     */
    public void beat(String componentName) {
        var status = components.get(componentName);
        if (status != null) {
            status.lastBeat = Instant.now();
        } else {
            logger.warn("Received heartbeat for unknown component: {}", componentName);
        }
    }

    /**
     * Check status of all components. Triggers emergency protocol if critical failures detected.
     */
    public void checkHealth() {
        Instant now = Instant.now();
        boolean allHealthy = true;

        for (var entry : components.entrySet()) {
            String name = entry.getKey();
            ComponentStatus status = entry.getValue();
            
            Duration timeSinceLastBeat = Duration.between(status.lastBeat, now);
            
            if (timeSinceLastBeat.compareTo(status.timeout) > 0) {
                logger.error("CRITICAL: Component '{}' requires attention! Last beat: {}s ago (Timeout: {}s)",
                    name, timeSinceLastBeat.toSeconds(), status.timeout.toSeconds());
                allHealthy = false;
            }
        }

        if (!allHealthy && isSystemHealthy.get()) {
            // Transition from Healthy -> Unhealthy: soft-pause new entries only. Existing
            // positions and exits are untouched -- nothing here ever flattens a position.
            logger.error("SYSTEM FAILURE DETECTED: soft-pausing new entries (heartbeat failure)");
            isSystemHealthy.set(false);
            com.trading.bot.TradingBot.setHeartbeatPaused(true);
            com.trading.websocket.TradingWebSocketHandler.broadcastActivity(
                "⚠️ New entries soft-paused — heartbeat failure detected. Existing positions and "
                    + "exits are unaffected; this clears automatically once health recovers.", "WARN");
        } else if (allHealthy && !isSystemHealthy.get()) {
            // Transition from Unhealthy -> Healthy
            logger.info("System health recovered");
            isSystemHealthy.set(true);
            autoResumeEntries();
        }
    }

    /**
     * Auto-resumes new entries once health has recovered, up to {@link #MAX_AUTO_RESUMES_PER_DAY}
     * times per calendar day — a failure that keeps recurring leaves new entries paused and
     * escalates to a louder alert instead of being silently smoothed over every time.
     */
    private void autoResumeEntries() {
        if (!com.trading.bot.TradingBot.isHeartbeatPaused()) return;

        java.time.LocalDate today = java.time.LocalDate.now();
        if (!today.equals(autoResumeDate)) {
            autoResumeDate = today;
            autoResumesToday.set(0);
        }

        if (autoResumesToday.incrementAndGet() > MAX_AUTO_RESUMES_PER_DAY) {
            logger.error("Health recovered but new entries NOT auto-resumed — already hit the "
                + "{}-per-day auto-resume limit. This looks like a RECURRING failure, not a "
                + "one-off blip — needs manual review (POST /api/trading/resume once reviewed).",
                MAX_AUTO_RESUMES_PER_DAY);
            com.trading.websocket.TradingWebSocketHandler.broadcastActivity(
                "🚨 New entries left paused — recurring heartbeat failures today, needs manual "
                    + "review", "CRITICAL");
            com.trading.bot.TradingBot.sendCriticalAlert(
                "Trading bot: new entries paused (recurring heartbeat failures)",
                "Heartbeat failures have recurred more than " + MAX_AUTO_RESUMES_PER_DAY
                    + " times today. New entries remain paused for manual review -- existing "
                    + "positions and exits are unaffected. Check /api/heartbeat, and resume with "
                    + "POST /api/trading/resume once reviewed.");
            return;
        }

        logger.warn("Health recovered — auto-resuming new entries (auto-resume {}/{} today)",
            autoResumesToday.get(), MAX_AUTO_RESUMES_PER_DAY);
        com.trading.bot.TradingBot.setHeartbeatPaused(false);
        com.trading.websocket.TradingWebSocketHandler.broadcastActivity(
            "✅ New entries auto-resumed — health recovered (auto-resume "
                + autoResumesToday.get() + "/" + MAX_AUTO_RESUMES_PER_DAY + " today)", "INFO");
    }

    public boolean isHealthy() {
        return isSystemHealthy.get();
    }
    
    // For dashboard usage
    public Map<String, Long> getDetails() {
        Instant now = Instant.now();
        var details = new ConcurrentHashMap<String, Long>();
        components.forEach((k, v) -> details.put(k, Duration.between(v.lastBeat, now).toSeconds()));
        return details;
    }

    private static class ComponentStatus {
        Instant lastBeat;
        final Duration timeout;

        ComponentStatus(Duration timeout) {
            this.lastBeat = Instant.now();
            this.timeout = timeout;
        }
    }
}
