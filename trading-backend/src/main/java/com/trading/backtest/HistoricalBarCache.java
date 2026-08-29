package com.trading.backtest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.trading.api.AlpacaClient;
import com.trading.api.model.Bar;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Fetches historical bars once via the real AlpacaClient and persists them to disk,
 * so {@link WalkForwardBacktestHarness} runs can replay the exact same historical
 * window repeatedly without hitting Alpaca or needing credentials on every run.
 *
 * Cache file layout: {@code <cacheDir>/<symbol>_<timeframe>.json} — one JSON array
 * of {@link Bar} per symbol+timeframe, oldest-first (matches AlpacaClient.getBars()'s
 * post-processing order for intraday timeframes).
 */
public final class HistoricalBarCache {
    private static final Logger logger = LoggerFactory.getLogger(HistoricalBarCache.class);

    private final Path cacheDir;
    private final ObjectMapper objectMapper;

    public HistoricalBarCache(Path cacheDir) {
        this.cacheDir = cacheDir;
        this.objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        try {
            Files.createDirectories(cacheDir);
        } catch (IOException e) {
            throw new UncheckedCacheException("Could not create cache dir " + cacheDir, e);
        }
    }

    /**
     * Fetch (if not already cached) and return bars for a symbol+timeframe.
     * Uses the real AlpacaClient, so this requires valid credentials the first time
     * a given symbol+timeframe+limit combination is requested — subsequent runs against
     * the same cache directory never touch the network.
     */
    public List<Bar> getOrFetch(AlpacaClient client, String symbol, String timeframe, int limit) {
        Path file = cacheFile(symbol, timeframe, limit);
        if (Files.exists(file)) {
            try {
                Bar[] cached = objectMapper.readValue(file.toFile(), Bar[].class);
                logger.debug("Cache hit: {} {} bars for {} ({})", cached.length, timeframe, symbol, file);
                return List.of(cached);
            } catch (IOException e) {
                logger.warn("Cache file {} unreadable, refetching: {}", file, e.getMessage());
            }
        }

        try {
            List<Bar> bars = client.getBars(symbol, timeframe, limit);
            objectMapper.writeValue(file.toFile(), bars);
            logger.info("Cached {} {} bars for {} -> {}", bars.size(), timeframe, symbol, file);
            return bars;
        } catch (Exception e) {
            throw new UncheckedCacheException("Failed to fetch bars for " + symbol + " " + timeframe, e);
        }
    }

    /** Same as {@link #getOrFetch} but for daily bars via getMarketHistory (used by MTF's ONE_DAY timeframe path). */
    public List<Bar> getOrFetchMarketHistory(AlpacaClient client, String symbol, int limit) {
        Path file = cacheFile(symbol, "1Day-history", limit);
        if (Files.exists(file)) {
            try {
                Bar[] cached = objectMapper.readValue(file.toFile(), Bar[].class);
                return List.of(cached);
            } catch (IOException e) {
                logger.warn("Cache file {} unreadable, refetching: {}", file, e.getMessage());
            }
        }
        try {
            List<Bar> bars = client.getMarketHistory(symbol, limit);
            objectMapper.writeValue(file.toFile(), bars);
            logger.info("Cached {} daily-history bars for {} -> {}", bars.size(), symbol, file);
            return bars;
        } catch (Exception e) {
            throw new UncheckedCacheException("Failed to fetch market history for " + symbol, e);
        }
    }

    private Path cacheFile(String symbol, String timeframe, int limit) {
        return cacheDir.resolve(symbol + "_" + timeframe + "_" + limit + ".json");
    }

    public static final class UncheckedCacheException extends RuntimeException {
        UncheckedCacheException(String message, Throwable cause) { super(message, cause); }
    }
}
