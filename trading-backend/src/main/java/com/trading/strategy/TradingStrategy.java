package com.trading.strategy;

/**
 * Base interface for trading strategies.
 */
public sealed interface TradingStrategy permits MeanReversionStrategy, TrendFollowingStrategy, RSIStrategy, MACDStrategy, BollingerBandsStrategy, MomentumStrategy {
    
    /**
     * Evaluates the trading signal based on current market conditions.
     * 
     * @param symbol The trading symbol
     * @param currentPrice Current market price
     * @param positionQty Current position quantity (0 if no position)
     * @return Trading signal (Buy, Sell, or Hold)
     */
    TradingSignal evaluate(String symbol, double currentPrice, double positionQty);
}
