package com.trading.ops;

import com.trading.api.model.Bar;
import com.trading.config.Config;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("EventDayDetector — price-based earnings/event-day gate")
class EventDayDetectorTest {

    private static final ZoneId ET = ZoneId.of("America/New_York");

    private Config cfg(boolean enabled) {
        var c = mock(Config.class, withSettings().mockMaker(org.mockito.MockMakers.SUBCLASS));
        when(c.isEventDayGateEnabled()).thenReturn(enabled);
        when(c.getEventDayGapPercent()).thenReturn(3.0);
        when(c.getSingleStockSymbols()).thenReturn("AAPL,META,NVDA");
        return c;
    }

    private static Bar bar(ZonedDateTime t, double open) {
        return new Bar(t.toInstant(), open, open * 1.001, open * 0.999, open, 1000L);
    }

    private EventDayDetector detector(double prevClose, double todayOpen, boolean enabled) {
        var today = LocalDate.now(ET);
        var daily = List.of(new Bar(today.minusDays(1).atTime(16, 0).atZone(ET).toInstant(),
            prevClose, prevClose, prevClose, prevClose, 1000L));
        var intraday = List.of(bar(today.atTime(LocalTime.of(9, 30)).atZone(ET), todayOpen));
        return new EventDayDetector(s -> daily, s -> intraday, cfg(enabled));
    }

    @Test
    @DisplayName("a +5% open gap on a single stock is an event day")
    void bigGap_isEvent() {
        var r = detector(100.0, 105.0, true).eventReason("META");
        assertTrue(r.isPresent());
        assertTrue(r.get().contains("+5.0%"), r.get());
    }

    @Test
    @DisplayName("a -4% gap counts too (direction-agnostic)")
    void bigGapDown_isEvent() {
        assertTrue(detector(100.0, 96.0, true).eventReason("NVDA").isPresent());
    }

    @Test
    @DisplayName("a normal 1% gap is not")
    void smallGap_isNotEvent() {
        assertTrue(detector(100.0, 101.0, true).eventReason("AAPL").isEmpty());
    }

    @Test
    @DisplayName("ETFs are never gated (no earnings)")
    void etf_isNeverGated() {
        assertTrue(detector(100.0, 110.0, true).eventReason("SPY").isEmpty());
    }

    @Test
    @DisplayName("gate disabled -> never blocks")
    void disabled_neverBlocks() {
        assertTrue(detector(100.0, 110.0, false).eventReason("META").isEmpty());
    }

    @Test
    @DisplayName("data failure fails OPEN (a hiccup must not halt trading)")
    void dataFailure_failsOpen() {
        var d = new EventDayDetector(s -> { throw new RuntimeException("api down"); }, s -> List.of(), cfg(true));
        assertTrue(d.eventReason("META").isEmpty());
    }
}
