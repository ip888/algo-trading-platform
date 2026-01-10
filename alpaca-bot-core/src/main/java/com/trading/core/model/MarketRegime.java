package com.trading.core.model;

public enum MarketRegime {
    STRONG_BULL,      // Uptrend + high volume + breadth
    WEAK_BULL,        // Uptrend + low volume/breadth
    STRONG_BEAR,      // Downtrend + high volume + breadth
    WEAK_BEAR,        // Downtrend + low volume/breadth
    RANGE_BOUND,      // Sideways + low volatility
    HIGH_VOLATILITY   // Choppy + high VIX
}
