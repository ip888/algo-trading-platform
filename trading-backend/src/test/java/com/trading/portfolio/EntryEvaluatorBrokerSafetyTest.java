package com.trading.portfolio;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;

class EntryEvaluatorBrokerSafetyTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2026-11-23T16:00:00Z");

    @Test
    void recentAcceptedBuyBlocksReentryAfterRestart() throws Exception {
        var orders = JSON.readTree("""
            [{"symbol":"SPY","side":"buy","status":"filled",
              "submitted_at":"2026-11-23T15:55:00Z"}]
            """);
        assertTrue(EntryEvaluator.hasRecentBuy(orders, "SPY", NOW, Duration.ofMinutes(30)));
    }

    @Test
    void unknownSubmissionTimeBlocksReentry() throws Exception {
        var orders = JSON.readTree("""
            [{"symbol":"SPY","side":"buy","status":"new"}]
            """);
        assertTrue(EntryEvaluator.hasRecentBuy(orders, "SPY", NOW, Duration.ofMinutes(30)));
    }

    @Test
    void oldBuyOutsideWindowDoesNotBlockForever() throws Exception {
        var orders = JSON.readTree("""
            [{"symbol":"SPY","side":"buy","status":"filled",
              "submitted_at":"2026-11-23T14:00:00Z"}]
            """);
        assertFalse(EntryEvaluator.hasRecentBuy(orders, "SPY", NOW, Duration.ofMinutes(30)));
    }

    @Test
    void otherSymbolsAndSellsDoNotBlockBuy() throws Exception {
        var orders = JSON.readTree("""
            [{"symbol":"QQQ","side":"buy","submitted_at":"2026-11-23T15:59:00Z"},
             {"symbol":"SPY","side":"sell","submitted_at":"2026-11-23T15:59:00Z"}]
            """);
        assertFalse(EntryEvaluator.hasRecentBuy(orders, "SPY", NOW, Duration.ofMinutes(30)));
    }

    @Test
    void noFillTerminalOrderIsSafeForRelease() throws Exception {
        var terminal = JSON.readTree("""
            {"status":"canceled","filled_qty":"0"}
            """);
        var partial = JSON.readTree("""
            {"status":"canceled","filled_qty":"0.5"}
            """);
        assertTrue(EntryEvaluator.canReleaseNoFillIntent(terminal));
        assertFalse(EntryEvaluator.canReleaseNoFillIntent(partial));
        assertFalse(EntryEvaluator.canReleaseNoFillIntent(JSON.readTree("{}")));
    }

    @Test
    void onlyCompletedBrokerConfirmedRoundTripReleasesIntent() throws Exception {
        var buy = JSON.readTree("""
            {"symbol":"SPY","side":"buy","status":"filled","filled_qty":"2",
             "filled_at":"2026-11-23T14:00:00Z"}
            """);
        var partialSell = JSON.readTree("""
            [{"symbol":"SPY","side":"sell","status":"filled","filled_qty":"1",
              "filled_at":"2026-11-23T14:20:00Z"}]
            """);
        var completedSells = JSON.readTree("""
            [{"symbol":"SPY","side":"sell","status":"filled","filled_qty":"1",
              "filled_at":"2026-11-23T14:20:00Z"},
             {"symbol":"SPY","side":"sell","status":"filled","filled_qty":"1",
              "filled_at":"2026-11-23T14:25:00Z"}]
            """);
        assertFalse(EntryEvaluator.canReleaseSettledIntent(buy, partialSell));
        assertTrue(EntryEvaluator.canReleaseSettledIntent(buy, completedSells));
        assertFalse(EntryEvaluator.canReleaseSettledIntent(buy, JSON.readTree("{}")));
    }

    @Test
    void malformedOrderHistoryMustFailClosed() throws Exception {
        assertThrows(IllegalArgumentException.class,
            () -> EntryEvaluator.hasRecentBuy(JSON.readTree("{}"), "SPY", NOW, Duration.ofMinutes(30)));
    }

    @Test
    void invalidTimestampMustFailClosed() throws Exception {
        var orders = JSON.readTree("""
            [{"symbol":"SPY","side":"buy","submitted_at":"malformed"}]
            """);
        assertTrue(EntryEvaluator.hasRecentBuy(orders, "SPY", NOW, Duration.ofMinutes(30)));
    }
}
