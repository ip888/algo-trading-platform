package com.trading.ops;

import com.trading.api.model.Bar;
import com.trading.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Price-based "something happened to this stock today" detector — the working replacement for the
 * earnings calendar, which is inert in production (ALPHA_VANTAGE_API_KEY is a placeholder, so the
 * calendar never returns a date and the ±24h earnings blackout / pre-earnings exit never fire).
 *
 * Rule (single stocks only — ETFs have no earnings): today's regular-session open gapped away from
 * the prior close by at least EVENT_DAY_GAP_PERCENT (default 3%). Earnings reactions, guidance
 * shocks and news gaps all show up this way, with no reliance on a paid data feed and no false
 * positives from news chatter. Blocks NEW entries for that symbol for the rest of the day; existing
 * positions are unaffected (the bot flattens by EOD_EXIT_TIME anyway, so no earnings gap can be held
 * overnight — the 2026-04-27 META -$40 loss was a 74h hold from before that policy).
 *
 * Fails open: missing bars/exceptions → no block (a data hiccup must not halt trading).
 */
public final class EventDayDetector {

    private static final Logger logger = LoggerFactory.getLogger(EventDayDetector.class);
    private static final ZoneId ET = ZoneId.of("America/New_York");

    private record Cached(LocalDate day, Optional<String> reason, long checkedAtMs) {}

    private final Function<String, List<Bar>> dailyHistory; // last bar = prior session close
    private final Function<String, List<Bar>> intradayBars; // 15-min bars, newest last
    private final Config config;
    private final ConcurrentHashMap<String, Cached> cache = new ConcurrentHashMap<>();

    /** Takes data suppliers (not a client type) so both raw and resilient broker clients can feed it. */
    public EventDayDetector(Function<String, List<Bar>> dailyHistory,
                            Function<String, List<Bar>> intradayBars, Config config) {
        this.dailyHistory = dailyHistory;
        this.intradayBars = intradayBars;
        this.config = config;
    }

    /** @return a human-readable reason if {@code symbol} is on an event day, else empty. */
    public Optional<String> eventReason(String symbol) {
        try {
            if (config == null || dailyHistory == null || intradayBars == null || !config.isEventDayGateEnabled()) return Optional.empty();
            if (!singleStocks().contains(symbol.toUpperCase())) return Optional.empty();

            LocalDate today = LocalDate.now(ET);
            Cached c = cache.get(symbol);
            long now = System.currentTimeMillis();
            // A positive result holds all day; a negative one is re-checked every 10 min only until the
            // first session bar exists (after that the open — and thus the gap — can never change).
            if (c != null && c.day().equals(today) && (c.reason().isPresent() || now - c.checkedAtMs() < 600_000L)) {
                return c.reason();
            }
            Optional<String> reason = compute(symbol, today);
            cache.put(symbol, new Cached(today, reason, now));
            return reason;
        } catch (Exception e) {
            logger.debug("EventDayDetector failed open for {}: {}", symbol, e.getMessage());
            return Optional.empty();
        }
    }

    private Optional<String> compute(String symbol, LocalDate today) {
        List<Bar> history = dailyHistory.apply(symbol); // daily; forming bar stripped → last = prior close
        if (history == null || history.isEmpty()) return Optional.empty();
        double prevClose = history.get(history.size() - 1).close();
        if (prevClose <= 0) return Optional.empty();

        List<Bar> intraday = intradayBars.apply(symbol);
        if (intraday == null) return Optional.empty();
        Bar first = intraday.stream()
            .filter(b -> b.timestamp().atZone(ET).toLocalDate().equals(today))
            .filter(b -> !b.timestamp().atZone(ET).toLocalTime().isBefore(java.time.LocalTime.of(9, 30)))
            .findFirst().orElse(null);
        if (first == null) return Optional.empty();

        double gapPct = (first.open() - prevClose) / prevClose * 100.0;
        if (Math.abs(gapPct) >= config.getEventDayGapPercent()) {
            return Optional.of(String.format("event day: opened %+.1f%% vs prior close (%.2f -> %.2f)",
                gapPct, prevClose, first.open()));
        }
        return Optional.empty();
    }

    private Set<String> singleStocks() {
        String csv = config.getSingleStockSymbols();
        if (csv == null || csv.isBlank()) return Set.of();
        return Arrays.stream(csv.split(",")).map(String::trim).map(String::toUpperCase)
            .filter(x -> !x.isEmpty()).collect(Collectors.toSet());
    }
}
