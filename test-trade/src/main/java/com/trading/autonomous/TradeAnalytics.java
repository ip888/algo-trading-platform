package com.trading.autonomous;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Analyzes trade history to learn patterns and improve decision-making.
 * Tracks what works and what doesn't across different market conditions.
 */
public class TradeAnalytics {
    private static final Logger logger = LoggerFactory.getLogger(TradeAnalytics.class);
    
    // Trade history storage
    private final List<TradeRecord> tradeHistory = Collections.synchronizedList(new ArrayList<>());
    
    // Performance by time of day
    private final Map<Integer, WinLossCounter> hourlyPerformance = new ConcurrentHashMap<>();
    
    // Performance by day of week
    private final Map<DayOfWeek, WinLossCounter> dailyPerformance = new ConcurrentHashMap<>();
    
    // Performance by symbol
    private final Map<String, WinLossCounter> symbolPerformance = new ConcurrentHashMap<>();
    
    // Performance by VIX level
    private final Map<String, WinLossCounter> vixPerformance = new ConcurrentHashMap<>();
    
    public TradeAnalytics() {
        logger.info("📊 TradeAnalytics initialized - Learning from every trade");
    }
    
    /**
     * Record a completed trade.
     */
    public void recordTrade(String symbol, Instant entryTime, Instant exitTime,
                           double entryPrice, double exitPrice, double pnl,
                           String strategy, double vixLevel) {
        
        TradeRecord record = new TradeRecord(
            symbol, entryTime, exitTime, entryPrice, exitPrice,
            pnl, strategy, vixLevel
        );
        
        tradeHistory.add(record);
        
        boolean isWin = pnl > 0;
        
        // Update hourly performance
        int hour = LocalDateTime.ofInstant(entryTime, ZoneId.of("America/New_York")).getHour();
        hourlyPerformance.computeIfAbsent(hour, k -> new WinLossCounter()).record(isWin);
        
        // Update daily performance
        DayOfWeek day = LocalDateTime.ofInstant(entryTime, ZoneId.of("America/New_York")).getDayOfWeek();
        dailyPerformance.computeIfAbsent(day, k -> new WinLossCounter()).record(isWin);
        
        // Update symbol performance
        symbolPerformance.computeIfAbsent(symbol, k -> new WinLossCounter()).record(isWin);
        
        // Update VIX performance
        String vixBucket = getVixBucket(vixLevel);
        vixPerformance.computeIfAbsent(vixBucket, k -> new WinLossCounter()).record(isWin);
        
        // Log insights
        if (tradeHistory.size() % 10 == 0) {
            logInsights();
        }
    }
    
    /**
     * Get VIX bucket for categorization.
     */
    private String getVixBucket(double vix) {
        if (vix < 15) return "LOW (< 15)";
        if (vix < 20) return "NORMAL (15-20)";
        if (vix < 30) return "ELEVATED (20-30)";
        return "HIGH (> 30)";
    }
    
    /**
     * Get best trading hours.
     */
    public List<Integer> getBestTradingHours() {
        return hourlyPerformance.entrySet().stream()
            .filter(e -> e.getValue().getWinRate() > 0.65) // 65%+ win rate
            .map(Map.Entry::getKey)
            .sorted()
            .toList();
    }
    
    /**
     * Get worst trading hours to avoid.
     */
    public List<Integer> getWorstTradingHours() {
        return hourlyPerformance.entrySet().stream()
            .filter(e -> e.getValue().getTotalTrades() >= 5) // Min 5 trades
            .filter(e -> e.getValue().getWinRate() < 0.45) // < 45% win rate
            .map(Map.Entry::getKey)
            .sorted()
            .toList();
    }
    
    /**
     * Get best symbols to trade.
     */
    public List<String> getBestSymbols() {
        return symbolPerformance.entrySet().stream()
            .filter(e -> e.getValue().getTotalTrades() >= 5)
            .filter(e -> e.getValue().getWinRate() > 0.70) // 70%+ win rate
            .map(Map.Entry::getKey)
            .sorted()
            .toList();
    }
    
    /**
     * Get symbols to avoid.
     */
    public List<String> getSymbolsToAvoid() {
        return symbolPerformance.entrySet().stream()
            .filter(e -> e.getValue().getTotalTrades() >= 5)
            .filter(e -> e.getValue().getWinRate() < 0.40) // < 40% win rate
            .map(Map.Entry::getKey)
            .sorted()
            .toList();
    }
    
    /**
     * Should we trade at this hour?
     */
    public boolean shouldTradeAtHour(int hour) {
        WinLossCounter counter = hourlyPerformance.get(hour);
        if (counter == null || counter.getTotalTrades() < 5) {
            return true; // Not enough data, allow
        }
        return counter.getWinRate() >= 0.50; // At least 50% win rate
    }
    
    /**
     * Should we trade this symbol?
     */
    public boolean shouldTradeSymbol(String symbol) {
        WinLossCounter counter = symbolPerformance.get(symbol);
        if (counter == null || counter.getTotalTrades() < 5) {
            return true; // Not enough data, allow
        }
        return counter.getWinRate() >= 0.45; // At least 45% win rate
    }
    
    /**
     * Log learning insights.
     */
    private void logInsights() {
        logger.info("📊 LEARNING INSIGHTS (after {} trades):", tradeHistory.size());
        
        // Best hours
        List<Integer> bestHours = getBestTradingHours();
        if (!bestHours.isEmpty()) {
            logger.info("   Best trading hours: {}", bestHours);
        }
        
        // Worst hours
        List<Integer> worstHours = getWorstTradingHours();
        if (!worstHours.isEmpty()) {
            logger.warn("   Avoid trading hours: {}", worstHours);
        }
        
        // Best symbols
        List<String> bestSymbols = getBestSymbols();
        if (!bestSymbols.isEmpty()) {
            logger.info("   Best symbols: {}", bestSymbols);
        }
        
        // Symbols to avoid
        List<String> avoidSymbols = getSymbolsToAvoid();
        if (!avoidSymbols.isEmpty()) {
            logger.warn("   Avoid symbols: {}", avoidSymbols);
        }
    }
    
    /**
     * Get comprehensive analytics report.
     */
    public String getAnalyticsReport() {
        StringBuilder report = new StringBuilder();
        report.append("═══════════════════════════════════════════════════════\n");
        report.append("TRADE ANALYTICS REPORT\n");
        report.append("═══════════════════════════════════════════════════════\n");
        report.append(String.format("Total Trades: %d\n\n", tradeHistory.size()));
        
        // Hourly performance
        report.append("Performance by Hour:\n");
        hourlyPerformance.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(e -> report.append(String.format("  %02d:00 - %.1f%% (%d trades)\n",
                e.getKey(), e.getValue().getWinRate() * 100, e.getValue().getTotalTrades())));
        
        report.append("\nPerformance by Day:\n");
        dailyPerformance.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(e -> report.append(String.format("  %s - %.1f%% (%d trades)\n",
                e.getKey(), e.getValue().getWinRate() * 100, e.getValue().getTotalTrades())));
        
        report.append("\nTop Symbols:\n");
        symbolPerformance.entrySet().stream()
            .filter(e -> e.getValue().getTotalTrades() >= 3)
            .sorted((a, b) -> Double.compare(b.getValue().getWinRate(), a.getValue().getWinRate()))
            .limit(5)
            .forEach(e -> report.append(String.format("  %s - %.1f%% (%d trades)\n",
                e.getKey(), e.getValue().getWinRate() * 100, e.getValue().getTotalTrades())));
        
        report.append("═══════════════════════════════════════════════════════\n");
        
        return report.toString();
    }
    
    // Inner classes
    private static class WinLossCounter {
        private int wins = 0;
        private int losses = 0;
        
        void record(boolean isWin) {
            if (isWin) wins++;
            else losses++;
        }
        
        double getWinRate() {
            int total = wins + losses;
            return total > 0 ? (double) wins / total : 0.0;
        }
        
        int getTotalTrades() {
            return wins + losses;
        }
    }
    
    private record TradeRecord(
        String symbol,
        Instant entryTime,
        Instant exitTime,
        double entryPrice,
        double exitPrice,
        double pnl,
        String strategy,
        double vixLevel
    ) {}
}
