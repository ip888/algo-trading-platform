package com.trading.api.model;

/**
 * Result of attempting to place a bracket order.
 * Tracks whether server-side protection (bracket) was actually applied,
 * or if the order fell back to a simple order due to fractional quantities.
 */
public record BracketOrderResult(
    boolean success,
    boolean hasBracketProtection,
    String symbol,
    double quantity,
    String message
) {

    /**
     * Create a result for a successful bracket order with full protection.
     */
    public static BracketOrderResult withBracket(String symbol, double quantity) {
        return new BracketOrderResult(
            true,
            true,
            symbol,
            quantity,
            "Bracket order placed with server-side SL/TP protection"
        );
    }

    /**
     * Create a result for a successful order but WITHOUT bracket protection.
     * This happens with fractional shares - position needs client-side monitoring.
     */
    public static BracketOrderResult withoutBracket(String symbol, double quantity) {
        return new BracketOrderResult(
            true,
            false,
            symbol,
            quantity,
            "Simple DAY order placed - fractional qty does not support brackets. " +
            "⚠️ Position requires client-side SL/TP monitoring!"
        );
    }

    /**
     * Create a result for a failed order.
     */
    public static BracketOrderResult failed(String symbol, double quantity, String error) {
        return new BracketOrderResult(
            false,
            false,
            symbol,
            quantity,
            "Order failed: " + error
        );
    }

    /**
     * Check if this position needs extra monitoring because it lacks server-side protection.
     */
    public boolean needsClientSideMonitoring() {
        return success && !hasBracketProtection;
    }
}
