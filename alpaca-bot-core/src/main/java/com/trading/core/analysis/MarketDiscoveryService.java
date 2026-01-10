package com.trading.core.analysis;

import com.trading.core.api.AlpacaClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * Adaptive Universe Selection Engine (Java 25)
 * Scans a broad pool of assets and rotates the active watchlist.
 */
public class MarketDiscoveryService {
    private static final Logger logger = LoggerFactory.getLogger(MarketDiscoveryService.class);
    private final MarketAnalysisService analysisService;
    
    // Master Universe - High Liquidity / High Alpha Potential
    private static final List<String> MASTER_UNIVERSE = List.of(
        "SPY", "QQQ", "IWM", "DIA", "VXX", // Indices / Volatility
        "AAPL", "MSFT", "GOOGL", "AMZN", "META", "TSLA", "NVDA", "AVGO", // Mega Tech
        "AMD", "INTC", "SMH", "SOXL", // Semis
        "NFLX", "COST", "WMT", "JPM", "GS", "V", "MA", // Consumer/Finance
        "XOM", "CVX", "SLB", "PBR", // Energy
        "XLE", "XLK", "XLV", "XLF", "XLI", "XLY", "XLP", "XLB", "XLU", // Sectors
        "COIN", "MARA", "RIOT", "IBIT", // Crypto-related
        "LLY", "NVO", "UNH", "ABBV" // Healthcare
    );

    private List<String> activeWatchlist = new ArrayList<>(List.of("SPY", "QQQ", "IWM", "NVDA", "TSLA"));
    private final int maxActiveSymbols;

    public MarketDiscoveryService(MarketAnalysisService analysisService) {
        this.analysisService = analysisService;
        this.maxActiveSymbols = Integer.parseInt(System.getenv().getOrDefault("MAX_ACTIVE_SYMBOLS", "10"));
    }

    private final java.util.concurrent.locks.ReentrantLock lock = new java.util.concurrent.locks.ReentrantLock();

    public List<String> getActiveWatchlist() {
        lock.lock();
        try {
            return List.copyOf(activeWatchlist);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Scans the Master Universe and promotes the top-scoring symbols to the active watchlist.
     */
    public void refreshWatchlist() {
        logger.info("🔭 Scanning Master Universe ({} symbols) for opportunities...", MASTER_UNIVERSE.size());
        
        // Java 25: Massive parallel scan using Virtual Threads
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var results = analysisService.analyzeMarket(MASTER_UNIVERSE);
            
            // Sort by ML Score (descending)
            var topPicks = results.stream()
                .filter(r -> !r.recommendation().equals("ERROR"))
                .sorted(Comparator.comparingDouble(MarketAnalysisService.AnalysisResult::score).reversed())
                .limit(maxActiveSymbols)
                .map(MarketAnalysisService.AnalysisResult::symbol)
                .collect(Collectors.toList());

            if (!topPicks.isEmpty()) {
                lock.lock();
                try {
                    var added = topPicks.stream().filter(s -> !activeWatchlist.contains(s)).toList();
                    var removed = activeWatchlist.stream().filter(s -> !topPicks.contains(s)).toList();
                    
                    if (!added.isEmpty() || !removed.isEmpty()) {
                        logger.info("🔄 Watchlist Rotated: Added {}, Removed {}", added, removed);
                        this.activeWatchlist = topPicks;
                    } else {
                        logger.info("✅ Watchlist is already optimized.");
                    }
                } finally {
                    lock.unlock();
                }
            }
        } catch (Exception e) {
            logger.error("Watchlist Refresh Failed", e);
        }
    }
}
