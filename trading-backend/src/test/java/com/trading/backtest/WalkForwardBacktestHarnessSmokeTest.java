package com.trading.backtest;

import com.trading.api.model.Bar;
import com.trading.config.Config;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end smoke test for {@link WalkForwardBacktestHarness}: proves the full replay
 * pipeline (regime detection -> StrategyManager -> position sizing -> exit evaluation ->
 * report generation) runs against synthetic data without exceptions and produces a
 * well-formed report. Does NOT assert specific trade counts/outcomes — crafting synthetic
 * data that reliably clears every live entry gate (MTF alignment, day-change threshold,
 * volume confirmation, etc.) would just be re-deriving those gates' internals in test data,
 * which is brittle and not the point of this test. Validating actual trading outcomes
 * against a historical window requires real Alpaca data (see loadHistory()), which this
 * environment doesn't have credentials for — that run happens separately.
 */
class WalkForwardBacktestHarnessSmokeTest {

    private static final ZoneId ET = ZoneId.of("America/New_York");
    private Config config;

    @BeforeEach
    void setUp() {
        System.setProperty("APCA_API_KEY_ID", "test_key");
        System.setProperty("APCA_API_SECRET_KEY", "test_secret");
        this.config = new Config();
    }

    /** Daily bars, mildly upward-trending, ending `daysAgo` days before `asOf`. */
    private static List<Bar> dailyBars(ZonedDateTime asOf, int count, double startPrice, double dailyDriftPct) {
        var bars = new ArrayList<Bar>();
        double price = startPrice;
        for (int i = count; i >= 1; i--) {
            var day = asOf.minusDays(i).withHour(16).withMinute(0);
            price *= (1 + dailyDriftPct / 100.0);
            bars.add(new Bar(day.toInstant(), price * 0.995, price * 1.01, price * 0.99, price, 5_000_000L));
        }
        return bars;
    }

    /** Intraday bars for one trading day's market hours at a fixed step, flat-ish price. */
    private static List<Bar> intradayBarsForDay(LocalDate day, int stepMinutes, double price) {
        var bars = new ArrayList<Bar>();
        var t = ZonedDateTime.of(day, java.time.LocalTime.of(9, 30), ET);
        var end = ZonedDateTime.of(day, java.time.LocalTime.of(16, 0), ET);
        double p = price;
        while (t.isBefore(end)) {
            p *= 1.0002; // tiny upward drift so "last bar up" checks have something to see
            bars.add(new Bar(t.toInstant(), p * 0.999, p * 1.001, p * 0.998, p, 100_000L));
            t = t.plusMinutes(stepMinutes);
        }
        return bars;
    }

    @Test
    void runProducesAWellFormedReportWithoutThrowing(@TempDir Path tempDir) {
        var now = ZonedDateTime.now(ET);
        var replayEndDay = now.toLocalDate().minusDays(1);
        // Avoid weekends for the synthetic "trading days"
        while (replayEndDay.getDayOfWeek().getValue() >= 6) {
            replayEndDay = replayEndDay.minusDays(1);
        }
        var replayStartDay = replayEndDay.minusDays(4);
        while (replayStartDay.getDayOfWeek().getValue() >= 6) {
            replayStartDay = replayStartDay.minusDays(1);
        }

        var harness = new WalkForwardBacktestHarness(config, tempDir,
            ZonedDateTime.of(replayStartDay, java.time.LocalTime.of(9, 30), ET).toInstant());

        var replayClient = harness.getReplayClient();

        // SPY + 8 sector ETFs: enough daily history for the 200-day MA + breadth checks.
        List<String> regimeSymbols = List.of("SPY", "XLK", "XLF", "XLE", "XLV", "XLI", "XLC", "XLU", "XLB");
        for (String sym : regimeSymbols) {
            var daily = dailyBars(now, 260, 100.0, 0.05);
            replayClient.loadBars(sym, "1Day", daily);
            replayClient.loadBars(sym, "1Day-history", daily);
        }
        replayClient.loadBars("VIXY", "1Day", dailyBars(now, 260, 15.0, 0.0));

        // One traded symbol with daily + intraday history across the replay window.
        String traded = "TEST";
        var tradedDaily = dailyBars(now, 260, 50.0, 0.05);
        replayClient.loadBars(traded, "1Day", tradedDaily);
        replayClient.loadBars(traded, "1Day-history", tradedDaily);

        var day = replayStartDay;
        var fifteenMin = new ArrayList<Bar>();
        var oneMin = new ArrayList<Bar>();
        var fiveMin = new ArrayList<Bar>();
        var oneHour = new ArrayList<Bar>();
        while (!day.isAfter(replayEndDay)) {
            if (day.getDayOfWeek().getValue() < 6) {
                fifteenMin.addAll(intradayBarsForDay(day, 15, 51.0));
                oneMin.addAll(intradayBarsForDay(day, 1, 51.0));
                fiveMin.addAll(intradayBarsForDay(day, 5, 51.0));
                oneHour.addAll(intradayBarsForDay(day, 60, 51.0));
            }
            day = day.plusDays(1);
        }
        replayClient.loadBars(traded, "15Min", fifteenMin);
        replayClient.loadBars(traded, "1Min", oneMin);
        replayClient.loadBars(traded, "5Min", fiveMin);
        replayClient.loadBars(traded, "1Hour", oneHour);

        Instant start = ZonedDateTime.of(replayStartDay, java.time.LocalTime.of(9, 30), ET).toInstant();
        Instant end = ZonedDateTime.of(replayEndDay, java.time.LocalTime.of(16, 0), ET).toInstant();

        var report = assertDoesNotThrow(() -> harness.run(List.of(traded), start, end, 1000.0, 4));

        assertNotNull(report);
        assertTrue(report.totalTrades() >= 0);
        assertEquals(report.wins() + report.losses(), report.totalTrades());
        assertEquals(1000.0, report.startEquity());
        assertTrue(report.trades().size() == report.totalTrades());
    }

    @Test
    void intrabarScan_closesAtStopWhenAWickTouchesItBetweenSteps(@TempDir Path tempDir) {
        var t0 = ZonedDateTime.of(LocalDate.of(2026, 9, 22), java.time.LocalTime.of(10, 0), ET).toInstant();
        var h = new WalkForwardBacktestHarness(config, tempDir, t0);
        var mins = new ArrayList<Bar>();
        for (int i = 0; i < 15; i++) {
            double low = i == 7 ? 99.70 : 99.95; // one wick below the 99.75 stop, close still above it
            mins.add(new Bar(t0.plusSeconds(60L * i), 100.0, 100.05, low, 100.0, 1000L));
        }
        h.getReplayClient().loadBars("SPY", "1Min", mins);
        h.openPositionForTest("SPY", new com.trading.risk.TradePosition("SPY", 100.0, 1.0, 99.75, 100.40, t0), "SCALP");

        assertTrue(h.scanIntrabarExit("SPY", t0, t0.plusSeconds(900)));
    }

    @Test
    void intrabarScan_takeProfitAndNoTouch(@TempDir Path tempDir) {
        var t0 = ZonedDateTime.of(LocalDate.of(2026, 9, 22), java.time.LocalTime.of(10, 0), ET).toInstant();
        var h = new WalkForwardBacktestHarness(config, tempDir, t0);
        var mins = new ArrayList<Bar>();
        for (int i = 0; i < 5; i++) mins.add(new Bar(t0.plusSeconds(60L * i), 100.0, 100.10, 99.90, 100.0, 1000L));
        h.getReplayClient().loadBars("SPY", "1Min", mins);
        h.openPositionForTest("SPY", new com.trading.risk.TradePosition("SPY", 100.0, 1.0, 99.75, 100.40, t0), "SCALP");
        assertFalse(h.scanIntrabarExit("SPY", t0, t0.plusSeconds(300)), "no level touched");

        // fresh harness: the 1-min series is cached per harness after its first scan
        var h2 = new WalkForwardBacktestHarness(config, tempDir.resolve("b"), t0);
        // take-profit is bot-polled live (fractional positions have no native TP): it needs a CLOSE at/above TP,
        // a wick to the target that closes back below must NOT count.
        mins.add(new Bar(t0.plusSeconds(300), 100.0, 100.60, 99.95, 100.2, 1000L));
        h2.getReplayClient().loadBars("SPY", "1Min", mins);
        h2.openPositionForTest("SPY", new com.trading.risk.TradePosition("SPY", 100.0, 1.0, 99.75, 100.40, t0), "SCALP");
        assertFalse(h2.scanIntrabarExit("SPY", t0, t0.plusSeconds(360)), "wick above TP but close below: no exit");

        var h3 = new WalkForwardBacktestHarness(config, tempDir.resolve("c"), t0);
        var closeAbove = new ArrayList<>(mins.subList(0, 5));
        closeAbove.add(new Bar(t0.plusSeconds(300), 100.0, 100.60, 99.95, 100.45, 1000L));
        h3.getReplayClient().loadBars("SPY", "1Min", closeAbove);
        h3.openPositionForTest("SPY", new com.trading.risk.TradePosition("SPY", 100.0, 1.0, 99.75, 100.40, t0), "SCALP");
        assertTrue(h3.scanIntrabarExit("SPY", t0, t0.plusSeconds(360)), "close at/above TP exits");
    }

    @Test
    void winnerRunner_sellsHalfAtTakeProfit_thenStopsOutAtLockedLevel_andFinalPnlIncludesBothLegs(@TempDir Path tempDir) {
        var t0 = ZonedDateTime.of(LocalDate.of(2026, 9, 22), java.time.LocalTime.of(10, 0), ET).toInstant();
        var h = new WalkForwardBacktestHarness(config, tempDir, t0);
        var mins = new ArrayList<Bar>();
        mins.add(new Bar(t0, 100.0, 100.9, 100.0, 100.85, 1000L));                 // close >= TP 100.80 -> runner
        mins.add(new Bar(t0.plusSeconds(60), 100.8, 100.85, 100.2, 100.3, 1000L)); // wick through locked stop 100.56
        h.getReplayClient().loadBars("ORBX", "1Min", mins);
        h.openPositionForTest("ORBX", new com.trading.risk.TradePosition("ORBX", 100.0, 2.0, 99.3, 100.80, t0), "ORB");

        assertTrue(h.scanIntrabarExit("ORBX", t0, t0.plusSeconds(120)));

        var trade = h.closedTradesForTest().get(0);
        // half (1.0 sh) at 100.85 = +0.85 ; remainder (1.0 sh) stopped at 100.56 = +0.56
        assertEquals(1.41, trade.pnl(), 0.02);
        assertEquals(2.0, trade.quantity(), 1e-9, "reports the original size");
    }
}
