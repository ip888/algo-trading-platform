package com.trading.earnings;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Earnings calendar lookup with TTL cache.
 *
 * Source: Alpha Vantage EARNINGS_CALENDAR endpoint (CSV).
 * The bot uses this to block new entries within a configurable window
 * around an earnings announcement (default: ±24h).
 *
 * Behaviour when API is unreachable / unconfigured:
 *   - Returns empty set (no blackout).
 *   - Logs at debug level so the bot keeps trading rather than halting.
 */
public final class EarningsCalendarService {

    private static final Logger logger = LoggerFactory.getLogger(EarningsCalendarService.class);
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ISO_LOCAL_DATE;

    private final String apiKey;
    private final long cacheTtlMs;
    private final HttpClient httpClient;

    private final ConcurrentHashMap<String, CachedEntry> cache = new ConcurrentHashMap<>();

    public record CachedEntry(Set<LocalDate> dates, Instant fetchedAt) {}

    public EarningsCalendarService(String apiKey, long cacheTtlMs) {
        this.apiKey = apiKey;
        this.cacheTtlMs = cacheTtlMs;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    }

    /**
     * Test/seed constructor — lets tests inject a fixed cache without touching the network.
     */
    public EarningsCalendarService(Map<String, Set<LocalDate>> seed, long cacheTtlMs) {
        this.apiKey = "";
        this.cacheTtlMs = cacheTtlMs;
        this.httpClient = HttpClient.newBuilder().build();
        Instant now = Instant.now();
        seed.forEach((sym, dates) -> cache.put(sym.toUpperCase(), new CachedEntry(dates, now)));
    }

    /**
     * Returns true if {@code at} is within {@code hoursBefore}/{@code hoursAfter}
     * of any known earnings date for {@code symbol}.
     */
    public boolean isInBlackout(String symbol, Instant at, int hoursBefore, int hoursAfter) {
        if (symbol == null || symbol.isBlank()) return false;
        Set<LocalDate> dates = getEarningsDates(symbol);
        if (dates.isEmpty()) return false;

        for (LocalDate d : dates) {
            Instant earningsAt = d.atStartOfDay(java.time.ZoneId.of("America/New_York")).toInstant();
            Instant winStart = earningsAt.minus(Duration.ofHours(hoursBefore));
            Instant winEnd   = earningsAt.plus(Duration.ofHours(hoursAfter));
            if (!at.isBefore(winStart) && !at.isAfter(winEnd)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Fetch earnings dates for a symbol (cached).
     */
    public Set<LocalDate> getEarningsDates(String symbol) {
        String key = symbol.toUpperCase();
        CachedEntry cached = cache.get(key);
        if (cached != null && Duration.between(cached.fetchedAt(), Instant.now()).toMillis() < cacheTtlMs) {
            return cached.dates();
        }
        Set<LocalDate> fresh = fetchFromApi(key);
        cache.put(key, new CachedEntry(fresh, Instant.now()));
        return fresh;
    }

    private Set<LocalDate> fetchFromApi(String symbol) {
        if (apiKey == null || apiKey.isBlank()) return Set.of();
        try {
            String url = "https://www.alphavantage.co/query?function=EARNINGS_CALENDAR&symbol="
                + symbol + "&horizon=3month&apikey=" + apiKey;
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                logger.debug("Earnings API returned {} for {}", resp.statusCode(), symbol);
                return Set.of();
            }
            return parseCsv(resp.body());
        } catch (Exception e) {
            logger.debug("Earnings fetch failed for {}: {}", symbol, e.getMessage());
            return Set.of();
        }
    }

    static Set<LocalDate> parseCsv(String csv) {
        Set<LocalDate> dates = new HashSet<>();
        if (csv == null || csv.isBlank()) return dates;
        String[] lines = csv.split("\\r?\\n");
        if (lines.length < 2) return dates;

        // Header: symbol,name,reportDate,...
        String[] header = lines[0].split(",");
        int reportDateIdx = -1;
        for (int i = 0; i < header.length; i++) {
            if (header[i].trim().equalsIgnoreCase("reportDate")) {
                reportDateIdx = i;
                break;
            }
        }
        if (reportDateIdx < 0) return dates;

        for (int i = 1; i < lines.length; i++) {
            String[] row = lines[i].split(",");
            if (row.length <= reportDateIdx) continue;
            try {
                dates.add(LocalDate.parse(row[reportDateIdx].trim(), DATE_FMT));
            } catch (Exception ignore) { /* skip bad rows */ }
        }
        return dates;
    }

    /** Test hook: clear cache. */
    public void clearCache() {
        cache.clear();
    }

    /** Test hook: snapshot of cache contents. */
    public Map<String, CachedEntry> snapshotCache() {
        return new HashMap<>(cache);
    }
}
