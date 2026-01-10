package com.trading.autonomous;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages portfolio correlation to prevent over-concentration in correlated positions.
 * Ensures diversification and limits exposure to single sectors/strategies.
 */
public class CorrelationManager {
    private static final Logger logger = LoggerFactory.getLogger(CorrelationManager.class);
    
    // Track positions by sector
    private final Map<String, Set<String>> sectorPositions = new ConcurrentHashMap<>();
    
    // Sector mappings
    private static final Map<String, String> SYMBOL_TO_SECTOR = Map.ofEntries(
        // Broad Market
        Map.entry("SPY", "BROAD_MARKET"),
        Map.entry("SH", "BROAD_MARKET"),
        Map.entry("QQQ", "TECH"),
        Map.entry("PSQ", "TECH"),
        
        // Energy
        Map.entry("XLE", "ENERGY"),
        Map.entry("ERY", "ENERGY"),
        
        // Financials
        Map.entry("XLF", "FINANCIALS"),
        Map.entry("FAZ", "FINANCIALS"),
        
        // Commodities
        Map.entry("GLD", "COMMODITIES"),
        Map.entry("GLL", "COMMODITIES"),
        Map.entry("SLV", "COMMODITIES"),
        Map.entry("ZSL", "COMMODITIES"),
        
        // Volatility
        Map.entry("VXX", "VOLATILITY"),
        Map.entry("UVXY", "VOLATILITY"),
        Map.entry("VIXY", "VOLATILITY"),
        
        // Small Cap
        Map.entry("IWM", "SMALL_CAP"),
        Map.entry("RWM", "SMALL_CAP"),
        Map.entry("TZA", "SMALL_CAP"),
        
        // Bonds
        Map.entry("TLT", "BONDS"),
        Map.entry("TBT", "BONDS"),
        
        // Emerging Markets
        Map.entry("EEM", "EMERGING"),
        Map.entry("EUM", "EMERGING"),
        
        // Real Estate
        Map.entry("IYR", "REAL_ESTATE"),
        Map.entry("SRS", "REAL_ESTATE"),
        
        // Utilities
        Map.entry("XLU", "UTILITIES"),
        Map.entry("SDP", "UTILITIES"),
        
        // Consumer
        Map.entry("XLY", "CONSUMER"),
        Map.entry("SCC", "CONSUMER"),
        
        // Semiconductors
        Map.entry("SMH", "SEMICONDUCTORS"),
        Map.entry("SSG", "SEMICONDUCTORS"),
        
        // Biotech
        Map.entry("XBI", "BIOTECH"),
        Map.entry("BIS", "BIOTECH"),
        
        // Retail
        Map.entry("XRT", "RETAIL"),
        Map.entry("SZK", "RETAIL"),
        
        // Midcap
        Map.entry("MDY", "MIDCAP"),
        Map.entry("MYY", "MIDCAP"),
        
        // China
        Map.entry("FXI", "CHINA"),
        Map.entry("YANG", "CHINA"),
        
        // Homebuilders
        Map.entry("XHB", "HOMEBUILDERS"),
        Map.entry("SRS", "HOMEBUILDERS"),
        
        // Transports
        Map.entry("IYT", "TRANSPORTS"),
        Map.entry("DOG", "TRANSPORTS")
    );
    
    // Limits
    private static final int MAX_POSITIONS_PER_SECTOR = 3;
    private static final double MAX_CORRELATION = 0.7;
    
    public CorrelationManager() {
        logger.info("🧠 CorrelationManager initialized - Diversification enforced");
    }
    
    /**
     * Check if we can add a new position without violating correlation limits.
     */
    public boolean canAddPosition(String symbol, Set<String> currentPositions) {
        String sector = getSector(symbol);
        
        // Count existing positions in this sector
        long sectorCount = currentPositions.stream()
            .filter(pos -> getSector(pos).equals(sector))
            .count();
        
        if (sectorCount >= MAX_POSITIONS_PER_SECTOR) {
            logger.warn("🧠 CORRELATION: Cannot add {} - already have {} positions in {} sector",
                symbol, sectorCount, sector);
            logger.warn("   Existing {} positions: {}", sector,
                currentPositions.stream()
                    .filter(pos -> getSector(pos).equals(sector))
                    .toList());
            return false;
        }
        
        logger.debug("🧠 CORRELATION: {} approved - {} positions in {} sector (max: {})",
            symbol, sectorCount, sector, MAX_POSITIONS_PER_SECTOR);
        return true;
    }
    
    /**
     * Get sector for a symbol.
     */
    private String getSector(String symbol) {
        return SYMBOL_TO_SECTOR.getOrDefault(symbol, "UNKNOWN");
    }
    
    /**
     * Get diversification score (0-100, higher is better).
     */
    public int getDiversificationScore(Set<String> positions) {
        if (positions.isEmpty()) {
            return 100;
        }
        
        // Count unique sectors
        Set<String> sectors = new HashSet<>();
        for (String symbol : positions) {
            sectors.add(getSector(symbol));
        }
        
        // Score based on sector diversity
        int score = (int) ((sectors.size() * 100.0) / positions.size());
        return Math.min(100, score);
    }
    
    /**
     * Get portfolio analysis.
     */
    public String getPortfolioAnalysis(Set<String> positions) {
        Map<String, Long> sectorCounts = new HashMap<>();
        
        for (String symbol : positions) {
            String sector = getSector(symbol);
            sectorCounts.merge(sector, 1L, Long::sum);
        }
        
        StringBuilder analysis = new StringBuilder();
        analysis.append("Portfolio Diversification:\n");
        sectorCounts.forEach((sector, count) ->
            analysis.append(String.format("  %s: %d positions\n", sector, count))
        );
        analysis.append(String.format("Diversification Score: %d/100",
            getDiversificationScore(positions)));
        
        return analysis.toString();
    }
}
