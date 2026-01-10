package com.trading.metrics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Professional-grade performance metrics tracker for algorithmic trading.
 * Calculates industry-standard metrics: Sharpe Ratio, Max Drawdown, Win Rate, etc.
 */
public final class PerformanceMetrics {
    private static final Logger logger = LoggerFactory.getLogger(PerformanceMetrics.class);
    private static final double RISK_FREE_RATE = 0.04; // 4% annual risk-free rate
    private static final int TRADING_DAYS_PER_YEAR = 252;
    
    private final List<Trade> trades = new ArrayList<>();
    private final List<Double> dailyReturns = new ArrayList<>();
    private final List<Double> equityCurve = new ArrayList<>();
    
    private double initialCapital;
    private double currentEquity;
    private double peakEquity;
    private double maxDrawdown;
    private double currentDrawdown;
    
    public PerformanceMetrics(double initialCapital) {
        this.initialCapital = initialCapital;
        this.currentEquity = initialCapital;
        this.peakEquity = initialCapital;
        this.maxDrawdown = 0.0;
        this.currentDrawdown = 0.0;
        equityCurve.add(initialCapital);
    }
    
    public void recordTrade(Trade trade) {
        trades.add(trade);
        currentEquity += trade.profitLoss();
        equityCurve.add(currentEquity);
        
        // Update drawdown
        if (currentEquity > peakEquity) {
            peakEquity = currentEquity;
            currentDrawdown = 0.0;
        } else {
            currentDrawdown = (peakEquity - currentEquity) / peakEquity;
            maxDrawdown = Math.max(maxDrawdown, currentDrawdown);
        }
        
        // Calculate daily return
        if (equityCurve.size() > 1) {
            var prevEquity = equityCurve.get(equityCurve.size() - 2);
            var dailyReturn = (currentEquity - prevEquity) / prevEquity;
            dailyReturns.add(dailyReturn);
        }
    }
    
    public double getTotalReturn() {
        return (currentEquity - initialCapital) / initialCapital;
    }
    
    public double getAnnualizedReturn() {
        if (dailyReturns.isEmpty()) return 0.0;
        var avgDailyReturn = dailyReturns.stream()
                .mapToDouble(Double::doubleValue)
                .average()
                .orElse(0.0);
        return Math.pow(1 + avgDailyReturn, TRADING_DAYS_PER_YEAR) - 1;
    }
    
    public double getSharpeRatio() {
        if (dailyReturns.size() < 2) return 0.0;
        
        var avgReturn = dailyReturns.stream()
                .mapToDouble(Double::doubleValue)
                .average()
                .orElse(0.0);
        
        var variance = dailyReturns.stream()
                .mapToDouble(r -> Math.pow(r - avgReturn, 2))
                .average()
                .orElse(0.0);
        
        var stdDev = Math.sqrt(variance);
        
        if (stdDev == 0) return 0.0;
        
        var dailyRiskFreeRate = RISK_FREE_RATE / TRADING_DAYS_PER_YEAR;
        var excessReturn = avgReturn - dailyRiskFreeRate;
        
        return (excessReturn / stdDev) * Math.sqrt(TRADING_DAYS_PER_YEAR);
    }
    
    public double getMaxDrawdown() {
        return maxDrawdown;
    }
    
    public double getCurrentDrawdown() {
        return currentDrawdown;
    }
    
    public double getWinRate() {
        if (trades.isEmpty()) return 0.0;
        long winningTrades = trades.stream()
                .filter(t -> t.profitLoss() > 0)
                .count();
        return (double) winningTrades / trades.size();
    }
    
    public double getProfitFactor() {
        var grossProfit = trades.stream()
                .filter(t -> t.profitLoss() > 0)
                .mapToDouble(Trade::profitLoss)
                .sum();
        
        var grossLoss = Math.abs(trades.stream()
                .filter(t -> t.profitLoss() < 0)
                .mapToDouble(Trade::profitLoss)
                .sum());
        
        return grossLoss == 0 ? 0.0 : grossProfit / grossLoss;
    }
    
    public double getAverageWin() {
        return trades.stream()
                .filter(t -> t.profitLoss() > 0)
                .mapToDouble(Trade::profitLoss)
                .average()
                .orElse(0.0);
    }
    
    public double getAverageLoss() {
        return trades.stream()
                .filter(t -> t.profitLoss() < 0)
                .mapToDouble(Trade::profitLoss)
                .average()
                .orElse(0.0);
    }
    
    public double getCalmarRatio() {
        if (maxDrawdown == 0) return 0.0;
        return getAnnualizedReturn() / maxDrawdown;
    }
    
    public int getTotalTrades() {
        return trades.size();
    }
    
    public double getCurrentEquity() {
        return currentEquity;
    }
    
    public void printDashboard() {
        System.out.println("\n" + "=".repeat(70));
        System.out.println("                    PERFORMANCE DASHBOARD");
        System.out.println("=".repeat(70));
        
        // Returns Section
        System.out.println("\n📊 RETURNS");
        System.out.printf("   Initial Capital:       $%,.2f\n", initialCapital);
        System.out.printf("   Final Equity:          $%,.2f\n", currentEquity);
        System.out.printf("   Total Return:          %.2f%%\n", getTotalReturn() * 100);
        System.out.printf("   Annualized Return:     %.2f%%\n", getAnnualizedReturn() * 100);
        
        // Risk Metrics
        System.out.println("\n⚠️  RISK METRICS");
        System.out.printf("   Sharpe Ratio:          %.2f %s\n", getSharpeRatio(), 
            getRating(getSharpeRatio(), 1.0, 2.0));
        System.out.printf("   Maximum Drawdown:      %.2f%% %s\n", getMaxDrawdown() * 100,
            getDrawdownRating(getMaxDrawdown()));
        System.out.printf("   Current Drawdown:      %.2f%%\n", getCurrentDrawdown() * 100);
        System.out.printf("   Calmar Ratio:          %.2f\n", getCalmarRatio());
        
        // Trading Statistics
        System.out.println("\n📈 TRADING STATISTICS");
        System.out.printf("   Total Trades:          %d\n", getTotalTrades());
        System.out.printf("   Win Rate:              %.1f%% %s\n", getWinRate() * 100,
            getRating(getWinRate(), 0.5, 0.6));
        System.out.printf("   Profit Factor:         %.2f %s\n", getProfitFactor(),
            getRating(getProfitFactor(), 1.5, 2.0));
        System.out.printf("   Average Win:           $%.2f\n", getAverageWin());
        System.out.printf("   Average Loss:          $%.2f\n", getAverageLoss());
        System.out.printf("   Win/Loss Ratio:        %.2f\n", 
            getAverageLoss() != 0 ? getAverageWin() / Math.abs(getAverageLoss()) : 0);
        
        // Overall Assessment
        System.out.println("\n🎯 OVERALL ASSESSMENT");
        System.out.println("   " + getOverallAssessment());
        
        System.out.println("\n" + "=".repeat(70) + "\n");
    }
    
    private String getRating(double value, double goodThreshold, double excellentThreshold) {
        if (value >= excellentThreshold) return "⭐⭐⭐ EXCELLENT";
        if (value >= goodThreshold) return "⭐⭐ GOOD";
        if (value >= goodThreshold * 0.7) return "⭐ ACCEPTABLE";
        return "⚠️  NEEDS IMPROVEMENT";
    }
    
    private String getDrawdownRating(double drawdown) {
        if (drawdown < 0.10) return "⭐⭐⭐ EXCELLENT";
        if (drawdown < 0.20) return "⭐⭐ GOOD";
        if (drawdown < 0.30) return "⭐ ACCEPTABLE";
        return "⚠️  HIGH RISK";
    }
    
    private String getOverallAssessment() {
        var sharpe = getSharpeRatio();
        var drawdown = getMaxDrawdown();
        var winRate = getWinRate();
        var profitFactor = getProfitFactor();
        
        int score = 0;
        if (sharpe >= 2.0) score += 3;
        else if (sharpe >= 1.0) score += 2;
        else if (sharpe >= 0.5) score += 1;
        
        if (drawdown < 0.10) score += 3;
        else if (drawdown < 0.20) score += 2;
        else if (drawdown < 0.30) score += 1;
        
        if (profitFactor >= 2.0) score += 2;
        else if (profitFactor >= 1.5) score += 1;
        
        if (winRate >= 0.6) score += 2;
        else if (winRate >= 0.5) score += 1;
        
        if (score >= 9) return "🏆 EXCEPTIONAL - Institutional-grade performance!";
        if (score >= 7) return "✅ STRONG - Professional-level bot";
        if (score >= 5) return "👍 GOOD - Solid performance, room for improvement";
        if (score >= 3) return "⚠️  MODERATE - Needs optimization";
        return "❌ WEAK - Strategy requires major revision";
    }
    
    public record Trade(
        LocalDateTime timestamp,
        String symbol,
        String action,
        int quantity,
        double price,
        double profitLoss
    ) {}
}
