package com.trading.ops;

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

import static org.junit.jupiter.api.Assertions.*;

/**
 * Schedule-only tests: `tick()` must only ever consider running outside market hours and no more
 * than once per RUN_INTERVAL. The harness run itself (runNow) needs real Alpaca data and is
 * exercised live via the manual-trigger endpoint, not here -- see WalkForwardBacktestHarnessTest
 * et al. for the harness's own coverage.
 */
@DisplayName("WeeklyValidationJob — schedule gating")
class WeeklyValidationJobTest {

    private static final String DB = "test-weekly-validation.db";
    private static final ZoneId ET = ZoneId.of("America/New_York");
    private TradeDatabase db;
    private WeeklyValidationJob job;

    @BeforeEach
    void setUp() {
        new File(DB).delete();
        db = new TradeDatabase(DB);
        System.setProperty("APCA_API_KEY_ID", "test_key");
        System.setProperty("APCA_API_SECRET_KEY", "test_secret");
        job = new WeeklyValidationJob(db, new Config(), "alpaca");
    }

    @AfterEach
    void tearDown() { db.close(); new File(DB).delete(); }

    @Test
    @DisplayName("never runs during regular market hours, even with no prior run recorded")
    void neverRunsDuringMarketHours() {
        var midSession = ZonedDateTime.of(LocalDate.of(2026, 10, 29), java.time.LocalTime.of(11, 0), ET);
        job.tick(midSession);
        assertNull(db.loadBotState("weekly_validation_last_run"));
    }

    @Test
    @DisplayName("runs after hours when no prior run is recorded")
    void runsAfterHoursWithNoPriorRun() {
        var afterClose = ZonedDateTime.of(LocalDate.of(2026, 10, 29), java.time.LocalTime.of(18, 0), ET);
        job.tick(afterClose);
        assertNotNull(db.loadBotState("weekly_validation_last_run"), "a run attempt (even one that errors on fake creds) must stamp last_run");
    }

    @Test
    @DisplayName("does not re-run within 7 days of the last run")
    void doesNotRerunWithinInterval() {
        db.saveBotState("weekly_validation_last_run", Instant.now().minusSeconds(3600).toString());
        var afterClose = ZonedDateTime.of(LocalDate.of(2026, 10, 29), java.time.LocalTime.of(18, 0), ET);
        job.tick(afterClose);
        var stamp = db.loadBotState("weekly_validation_last_run");
        assertTrue(Instant.parse(stamp).isBefore(Instant.now().minusSeconds(3000)), "must not have re-stamped (no new run)");
    }

    @Test
    @DisplayName("runs again once 7+ days have passed since the last run")
    void rerunsAfterInterval() {
        db.saveBotState("weekly_validation_last_run", Instant.now().minus(java.time.Duration.ofDays(8)).toString());
        var afterClose = ZonedDateTime.of(LocalDate.of(2026, 10, 29), java.time.LocalTime.of(18, 0), ET);
        job.tick(afterClose);
        var stamp = db.loadBotState("weekly_validation_last_run");
        assertTrue(Instant.parse(stamp).isAfter(Instant.now().minusSeconds(60)), "must have re-stamped (new run attempted)");
    }

    @Test
    @DisplayName("loadLatest returns null before any run has ever completed")
    void loadLatest_nullBeforeAnyRun() {
        assertNull(job.loadLatest());
    }
}
