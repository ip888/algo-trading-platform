package com.trading.lending;

import com.trading.config.Config;
import com.trading.websocket.TradingWebSocketHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Stock Lending Tracker
 * Tracks passive income from stock lending program
 */
public class StockLendingTracker {
    private static final Logger logger = LoggerFactory.getLogger(StockLendingTracker.class);
    
    private final Config config;
    private Instant lastReport = Instant.now();
    private double totalIncome = 0;
    
    public StockLendingTracker(Config config) {
        this.config = config;
    }
    
    /**
     * Track and report lending income
     * Called periodically to check for new income
     */
    public void trackLendingIncome() {
        if (!config.isStockLendingTracking()) {
            return;
        }
        
        // Check if it's time to report
        long hoursSinceReport = ChronoUnit.HOURS.between(lastReport, Instant.now());
        if (hoursSinceReport < config.getLendingReportIntervalHours()) {
            return;
        }
        
        // Simulate lending income (in production, query Alpaca API)
        double dailyIncome = simulateLendingIncome();
        totalIncome += dailyIncome;
        
        if (dailyIncome > 0) {
            logger.info("💰 Stock Lending Income: ${:.2f} (Total: ${:.2f})",
                dailyIncome, totalIncome);
            
            TradingWebSocketHandler.broadcastActivity(
                String.format("💰 Stock Lending: +$%.2f passive income", dailyIncome),
                "INFO"
            );
        }
        
        lastReport = Instant.now();
    }
    
    /**
     * Simulate lending income
     * In production: query Alpaca stock lending API
     */
    private double simulateLendingIncome() {
        // Simulate $0.50 to $2.00 daily income
        return 0.50 + (Math.random() * 1.50);
    }
    
    /**
     * Get total lending income
     */
    public double getTotalIncome() {
        return totalIncome;
    }
}
