package com.trading.api;

/**
 * Thrown when Alpaca rejects an order with HTTP 403 due to Pattern Day Trader restrictions.
 *
 * This is a business-logic rejection, NOT an infrastructure failure.
 * The circuit breaker should ignore this exception type to prevent
 * cascading lockouts that paralyze the entire bot.
 */
public class PDTRejectedException extends RuntimeException {
    public PDTRejectedException(String message) {
        super(message);
    }

    public PDTRejectedException(String message, Throwable cause) {
        super(message, cause);
    }
}
