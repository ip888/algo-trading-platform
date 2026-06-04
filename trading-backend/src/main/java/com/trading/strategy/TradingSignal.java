package com.trading.strategy;

/**
 * Sealed interface representing all possible trading signals.
 * Uses modern Java sealed types for exhaustive pattern matching.
 */
public sealed interface TradingSignal permits TradingSignal.Buy, TradingSignal.Sell, TradingSignal.Hold, TradingSignal.ScalpBuy {

    record Buy(String reason) implements TradingSignal {}

    record Sell(String reason) implements TradingSignal {}

    record Hold(String reason) implements TradingSignal {}

    /**
     * Intraday scalp entry — carries its own tight SL/TP percents so ProfileManager
     * can bypass the profile's wider swing-trade targets and use scalp-specific levels.
     */
    record ScalpBuy(String reason, double stopLossPercent, double takeProfitPercent) implements TradingSignal {}

    /**
     * Converts signal to legacy string format for backwards compatibility.
     */
    default String toAction() {
        return switch (this) {
            case Buy _ -> "BUY";
            case Sell _ -> "SELL";
            case Hold _ -> "HOLD";
            case ScalpBuy _ -> "BUY";
        };
    }
}
