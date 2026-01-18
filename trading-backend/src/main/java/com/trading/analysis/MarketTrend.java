package com.trading.analysis;

/**
 * Market trend prediction.
 */
public enum MarketTrend {
    BULLISH("📈 Bullish", "Upward trend detected"),
    BEARISH("📉 Bearish", "Downward trend detected"),
    NEUTRAL("➡️ Neutral", "No clear trend"),
    VOLATILE("⚡ Volatile", "High volatility detected");
    
    private final String display;
    private final String description;
    
    MarketTrend(String display, String description) {
        this.display = display;
        this.description = description;
    }
    
    public String display() {
        return display;
    }
    
    public String description() {
        return description;
    }
}
