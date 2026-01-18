package com.trading.strategy;

/**
 * Sealed interface representing all possible trading signals.
 * Uses modern Java sealed types for exhaustive pattern matching.
 */
public sealed interface TradingSignal permits TradingSignal.Buy, TradingSignal.Sell, TradingSignal.Hold {
    
    record Buy(String reason) implements TradingSignal {}
    
    record Sell(String reason) implements TradingSignal {}
    
    record Hold(String reason) implements TradingSignal {}
    
    /**
     * Converts signal to legacy string format for backwards compatibility.
     */
    default String toAction() {
        return switch (this) {
            case Buy b -> "BUY";
            case Sell s -> "SELL";
            case Hold h -> "HOLD";
        };
    }
}
