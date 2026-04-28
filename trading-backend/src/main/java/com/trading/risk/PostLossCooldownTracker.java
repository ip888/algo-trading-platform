package com.trading.risk;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-symbol post-loss cooldown.
 *
 * Distinct from the legacy "stop-loss cooldown" used for re-entry protection
 * after any exit: this tracks losses specifically and escalates the cooldown
 * after consecutive losses on the *same symbol*. Lets the bot keep trading
 * other names while throttling repeated entries on a name that just punished
 * the strategy (the TLT-loses-4x pattern).
 *
 * Single instance is shared across brokers/profiles to ensure both Alpaca and
 * Tradier respect the cooldown for a given symbol.
 */
public final class PostLossCooldownTracker {

    private final long baseCooldownMs;
    private final long extendedCooldownMs;
    private final int extendedAfterLosses;

    private final ConcurrentHashMap<String, Long> cooldownExpiry = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Integer> consecutiveLosses = new ConcurrentHashMap<>();

    public PostLossCooldownTracker(long baseCooldownMs, long extendedCooldownMs, int extendedAfterLosses) {
        if (baseCooldownMs < 0 || extendedCooldownMs < 0) {
            throw new IllegalArgumentException("cooldown durations must be ≥ 0");
        }
        this.baseCooldownMs = baseCooldownMs;
        this.extendedCooldownMs = Math.max(extendedCooldownMs, baseCooldownMs);
        this.extendedAfterLosses = Math.max(2, extendedAfterLosses);
    }

    /** Default factory: 24h base, 72h extended after 2 consecutive losses. */
    public static PostLossCooldownTracker defaults() {
        return new PostLossCooldownTracker(
            24L * 60 * 60 * 1000,
            72L * 60 * 60 * 1000,
            2
        );
    }

    /** Record a loss on {@code symbol} at {@code now} (ms). Returns the cooldown duration applied. */
    public long recordLoss(String symbol, long nowMs) {
        if (symbol == null || symbol.isBlank()) return 0;
        int n = consecutiveLosses.merge(symbol, 1, Integer::sum);
        long duration = (n >= extendedAfterLosses) ? extendedCooldownMs : baseCooldownMs;
        cooldownExpiry.put(symbol, nowMs + duration);
        return duration;
    }

    /** Record a winning close: clears the loss streak so future losses don't auto-escalate. */
    public void recordWin(String symbol) {
        if (symbol == null) return;
        consecutiveLosses.remove(symbol);
    }

    /** True iff the symbol is currently within an active cooldown. */
    public boolean isInCooldown(String symbol, long nowMs) {
        if (symbol == null) return false;
        Long expiry = cooldownExpiry.get(symbol);
        if (expiry == null) return false;
        if (expiry <= nowMs) {
            cooldownExpiry.remove(symbol);
            return false;
        }
        return true;
    }

    public long remainingMs(String symbol, long nowMs) {
        Long expiry = cooldownExpiry.get(symbol);
        if (expiry == null) return 0;
        long rem = expiry - nowMs;
        return rem > 0 ? rem : 0;
    }

    public int getConsecutiveLosses(String symbol) {
        return consecutiveLosses.getOrDefault(symbol, 0);
    }

    /** For tests / status endpoints. */
    public java.util.Map<String, Long> snapshot() {
        return new java.util.HashMap<>(cooldownExpiry);
    }

    public void clear() {
        cooldownExpiry.clear();
        consecutiveLosses.clear();
    }
}
