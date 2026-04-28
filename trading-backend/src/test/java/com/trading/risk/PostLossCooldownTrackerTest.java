package com.trading.risk;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("PostLossCooldownTracker Tests (Tier 1.1)")
class PostLossCooldownTrackerTest {

    private static final long BASE = 60_000L;
    private static final long EXTENDED = 180_000L;

    private PostLossCooldownTracker tracker() {
        return new PostLossCooldownTracker(BASE, EXTENDED, 2);
    }

    @Test
    @DisplayName("first loss applies base cooldown")
    void firstLossUsesBase() {
        var t = tracker();
        long applied = t.recordLoss("TLT", 1_000L);
        assertEquals(BASE, applied);
        assertTrue(t.isInCooldown("TLT", 1_000L + 30L));
        assertEquals(1, t.getConsecutiveLosses("TLT"));
    }

    @Test
    @DisplayName("second consecutive loss escalates to extended")
    void secondLossEscalates() {
        var t = tracker();
        t.recordLoss("TLT", 0);
        long applied = t.recordLoss("TLT", BASE + 10);
        assertEquals(EXTENDED, applied);
        assertEquals(2, t.getConsecutiveLosses("TLT"));
    }

    @Test
    @DisplayName("win clears the loss streak so future losses revert to base")
    void winClearsStreak() {
        var t = tracker();
        t.recordLoss("TLT", 0);
        t.recordLoss("TLT", BASE + 10);
        t.recordWin("TLT");
        long applied = t.recordLoss("TLT", BASE * 5);
        assertEquals(BASE, applied);
        assertEquals(1, t.getConsecutiveLosses("TLT"));
    }

    @Test
    @DisplayName("expired cooldown reports as out-of-cooldown")
    void cooldownExpires() {
        var t = tracker();
        t.recordLoss("QQQ", 0);
        assertTrue(t.isInCooldown("QQQ", BASE - 1));
        assertFalse(t.isInCooldown("QQQ", BASE + 1));
    }

    @Test
    @DisplayName("remainingMs decreases over time and floors at 0")
    void remainingMsCounts() {
        var t = tracker();
        t.recordLoss("AAPL", 0);
        assertEquals(BASE, t.remainingMs("AAPL", 0));
        assertEquals(BASE / 2, t.remainingMs("AAPL", BASE / 2));
        assertEquals(0, t.remainingMs("AAPL", BASE * 10));
    }

    @Test
    @DisplayName("blank/null symbol is a no-op")
    void blankSymbolIsNoop() {
        var t = tracker();
        assertEquals(0, t.recordLoss("", 0));
        assertEquals(0, t.recordLoss(null, 0));
        assertFalse(t.isInCooldown(null, 0));
    }

    @Test
    @DisplayName("cooldowns are isolated per symbol")
    void perSymbolIsolation() {
        var t = tracker();
        t.recordLoss("TLT", 0);
        assertTrue(t.isInCooldown("TLT", 30));
        assertFalse(t.isInCooldown("QQQ", 30));
    }

    @Test
    @DisplayName("constructor rejects negative durations")
    void rejectsNegativeDurations() {
        assertThrows(IllegalArgumentException.class,
            () -> new PostLossCooldownTracker(-1, 0, 2));
        assertThrows(IllegalArgumentException.class,
            () -> new PostLossCooldownTracker(0, -1, 2));
    }
}
