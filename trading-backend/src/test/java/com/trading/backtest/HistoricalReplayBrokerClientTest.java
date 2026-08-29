package com.trading.backtest;

import com.trading.api.model.Bar;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the clock-scoping semantics that make replay valid: at any simulated "now",
 * getBars/getMarketHistory/getLatestBar must only see data strictly before that instant —
 * exactly like the live bot never seeing the future. This is the highest-risk new mechanic
 * in the walk-forward backtest harness (see WalkForwardBacktestHarness class Javadoc).
 */
class HistoricalReplayBrokerClientTest {

    private static Bar bar(long epochSeconds, double close) {
        return new Bar(Instant.ofEpochSecond(epochSeconds), close, close, close, close, 1000L);
    }

    @Test
    void getBarsOnlyReturnsBarsStrictlyBeforeSimulatedNow() {
        var client = new HistoricalReplayBrokerClient(Instant.ofEpochSecond(1000));
        var series = List.of(bar(100, 1.0), bar(200, 2.0), bar(300, 3.0), bar(400, 4.0), bar(500, 5.0));
        client.loadBars("TEST", "15Min", series);

        client.advanceTo(Instant.ofEpochSecond(350));
        List<Bar> visible = client.getBars("TEST", "15Min", 10);

        assertEquals(3, visible.size(), "should see only the 3 bars before t=350");
        assertEquals(3.0, visible.get(visible.size() - 1).close(), "last visible bar should be the t=300 one");
    }

    @Test
    void getBarsRespectsLimitTakingMostRecentVisible() {
        var client = new HistoricalReplayBrokerClient(Instant.ofEpochSecond(1000));
        var series = List.of(bar(100, 1.0), bar(200, 2.0), bar(300, 3.0), bar(400, 4.0), bar(500, 5.0));
        client.loadBars("TEST", "15Min", series);

        client.advanceTo(Instant.ofEpochSecond(600));
        List<Bar> visible = client.getBars("TEST", "15Min", 2);

        assertEquals(2, visible.size());
        assertEquals(4.0, visible.get(0).close());
        assertEquals(5.0, visible.get(1).close());
    }

    @Test
    void advancingTheClockRevealsMoreBars() {
        var client = new HistoricalReplayBrokerClient(Instant.ofEpochSecond(50));
        var series = List.of(bar(100, 1.0), bar(200, 2.0), bar(300, 3.0));
        client.loadBars("TEST", "15Min", series);

        assertEquals(0, client.getBars("TEST", "15Min", 10).size(), "nothing visible before the first bar");

        client.advanceTo(Instant.ofEpochSecond(250));
        assertEquals(2, client.getBars("TEST", "15Min", 10).size());

        client.advanceTo(Instant.ofEpochSecond(1000));
        assertEquals(3, client.getBars("TEST", "15Min", 10).size(), "all bars visible once clock passes them");
    }

    @Test
    void getLatestBarReturnsMostRecentVisibleBarAcrossFinestTimeframe() {
        var client = new HistoricalReplayBrokerClient(Instant.ofEpochSecond(1000));
        client.loadBars("TEST", "1Day", List.of(bar(100, 10.0), bar(900_000, 99.0)));
        client.loadBars("TEST", "1Min", List.of(bar(200, 1.0), bar(300, 2.0), bar(400, 3.0)));

        client.advanceTo(Instant.ofEpochSecond(350));
        var latest = client.getLatestBar("TEST");

        assertTrue(latest.isPresent());
        assertEquals(2.0, latest.get().close(), "should prefer the finer 1Min series over 1Day, scoped to simulated now");
    }

    @Test
    void getMarketHistoryIsAlsoClockScoped() {
        var client = new HistoricalReplayBrokerClient(Instant.ofEpochSecond(1000));
        var series = List.of(bar(100, 1.0), bar(200, 2.0), bar(300, 3.0));
        client.loadBars("TEST", "1Day-history", series);

        client.advanceTo(Instant.ofEpochSecond(250));
        List<Bar> visible = client.getMarketHistory("TEST", 10);

        assertEquals(2, visible.size());
    }

    @Test
    void unsupportedMethodsFailLoudlyRatherThanReturningFabricatedData() {
        var client = new HistoricalReplayBrokerClient(Instant.now());
        assertThrows(UnsupportedOperationException.class, client::getPositions);
        assertThrows(UnsupportedOperationException.class,
            () -> client.placeOrder("TEST", 1.0, "buy", "market", "day", null));
    }
}
