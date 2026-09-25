package com.trading.ops;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trading.api.BrokerClient;
import com.trading.config.Config;
import com.trading.persistence.TradeDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("DailyCloseJob — fill re-pricing, persisted digest, gap alert")
class DailyCloseJobTest {

    private static final String DB = "test-daily-close.db";
    private static final ObjectMapper M = new ObjectMapper();
    private static final ZoneId ET = ZoneId.of("America/New_York");
    private static final LocalDate DAY = LocalDate.now(ET); // 'today' so the broker comparison applies

    private TradeDatabase db;
    private BrokerClient client;
    private DailyCloseJob job;

    @BeforeEach
    void setUp() throws Exception {
        new File(DB).delete();
        db = new TradeDatabase(DB);
        client = mock(BrokerClient.class, withSettings().mockMaker(org.mockito.MockMakers.SUBCLASS));
        var cfg = mock(Config.class, withSettings().mockMaker(org.mockito.MockMakers.SUBCLASS));
        when(cfg.getDigestGapAlertUsd()).thenReturn(1.0);
        when(client.getPositions()).thenReturn(List.of());
        job = new DailyCloseJob(db, client, cfg, "alpaca");
    }

    @AfterEach void tearDown() { db.close(); new File(DB).delete(); }

    private void seedTrade(Instant entry, Instant exit, double ePx, double xPx) {
        db.recordTradeWithContext("AAPL", "MACD", "MAIN", "alpaca", entry, ePx, 1.0, ePx * 0.99, ePx * 1.02,
            "WEAK_BULL", 10.0, 0.5);
        db.setEntryReason("AAPL", "alpaca", "MACD | MTF Direct Buy | regime=WEAK_BULL vix=10.0 sl=1 tp=2");
        db.closeTrade("AAPL", exit, xPx, 0.0, "alpaca", "time_decay");
    }

    private void account(double equity, double last) throws Exception {
        when(client.getAccount()).thenReturn(M.readTree("{\"equity\":\"" + equity + "\",\"last_equity\":\"" + last + "\"}"));
    }

    @Test
    @DisplayName("re-prices from real fills, persists the digest, and reports no alert when DB matches the broker")
    void digest_matchesBroker_noAlert() throws Exception {
        Instant entry = ZonedDateTime.of(DAY.atTime(10, 0), ET).toInstant();
        Instant exit = entry.plusSeconds(3600);
        seedTrade(entry, exit, 100.00, 101.00);                       // decision-time quote: +1.00
        when(client.getOrderHistory(null, 500)).thenReturn(M.readTree(
            "[{\"symbol\":\"AAPL\",\"side\":\"buy\",\"filled_qty\":\"1\",\"filled_avg_price\":\"100.10\",\"filled_at\":\"" + entry.plusSeconds(1) + "\"},"
          + "{\"symbol\":\"AAPL\",\"side\":\"sell\",\"filled_qty\":\"1\",\"filled_avg_price\":\"100.90\",\"filled_at\":\"" + exit + "\"}]"));
        account(1000.80, 1000.00);                                    // broker truth: +0.80

        var d = job.runFor(DAY);

        assertEquals(1, d.get("trades"));
        assertEquals(0.80, (double) d.get("dbNetPnL"), 0.005, "DB now equals the real-fill P&L");
        assertEquals(0.0, (double) d.get("gap"), 0.01);
        assertTrue(((List<?>) d.get("alerts")).isEmpty());
        assertNotNull(job.loadDigest(DAY.toString()), "digest persisted");
        assertNotNull(job.loadDigest("latest"));
        assertTrue(((java.util.Map<?, ?>) d.get("bySource")).containsKey("MTF Direct Buy"), "attribution from entry_reason");
    }

    @Test
    @DisplayName("a flat day whose DB P&L is >$1 off the broker raises an alert")
    void gapAboveLimit_alerts() throws Exception {
        Instant entry = ZonedDateTime.of(DAY.atTime(10, 0), ET).toInstant();
        seedTrade(entry, entry.plusSeconds(3600), 100.00, 103.00);    // DB says +3.00, no fills to correct it
        when(client.getOrderHistory(null, 500)).thenReturn(M.readTree("[]"));
        account(1000.50, 1000.00);

        var d = job.runFor(DAY);

        assertEquals(1, ((List<?>) d.get("alerts")).size());
    }

    @Test
    @DisplayName("a position still open after the close raises an alert and is not compared")
    void openPositionAfterClose_alerts() throws Exception {
        when(client.getOrderHistory(null, 500)).thenReturn(M.readTree("[]"));
        when(client.getPositions()).thenReturn(List.of(new com.trading.api.model.Position("SPY", 1, 700, 699, 1)));
        account(1000, 1000);

        var d = job.runFor(DAY);

        assertEquals(false, d.get("comparable"));
        assertEquals(1, ((List<?>) d.get("alerts")).size());
    }

    @Test
    @DisplayName("tick runs once after 16:10 ET on a weekday, never before, never on a weekend")
    void tick_schedule() throws Exception {
        when(client.getOrderHistory(null, 500)).thenReturn(M.readTree("[]"));
        account(1000, 1000);

        // fixed weekday for the schedule logic (tick() takes the clock as a parameter)
        var friday = LocalDate.of(2026, 9, 25);
        job.tick(ZonedDateTime.of(friday.atTime(15, 59), ET));
        assertNull(job.loadDigest(friday.toString()), "before 16:10 ET");

        job.tick(ZonedDateTime.of(friday.atTime(16, 11), ET));
        assertNotNull(job.loadDigest(friday.toString()));

        job.tick(ZonedDateTime.of(friday.atTime(16, 30), ET));
        verify(client, times(1)).getOrderHistory(null, 500); // second tick is a no-op

        var saturday = LocalDate.of(2026, 9, 26);
        job.tick(ZonedDateTime.of(saturday.atTime(17, 0), ET));
        assertNull(job.loadDigest(saturday.toString()));
    }
}
