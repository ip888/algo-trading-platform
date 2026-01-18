package com.trading.validation;

import jakarta.validation.constraints.*;

/**
 * Validated request model for placing orders.
 */
public record OrderRequest(
    @NotBlank(message = "Symbol is required")
    @Pattern(regexp = "^[A-Z]{1,5}$", message = "Symbol must be 1-5 uppercase letters")
    String symbol,
    
    @Positive(message = "Quantity must be positive")
    @DecimalMax(value = "1000000.0", message = "Quantity cannot exceed 1,000,000")
    double quantity,
    
    @NotNull(message = "Side is required")
    OrderSide side,
    
    @NotNull(message = "Order type is required")
    OrderType type,
    
    @Positive(message = "Limit price must be positive when specified")
    Double limitPrice,
    
    @Positive(message = "Stop price must be positive when specified")
    Double stopPrice
) {
    public enum OrderSide {
        BUY, SELL
    }
    
    public enum OrderType {
        MARKET, LIMIT, STOP, STOP_LIMIT
    }
    
    /**
     * Validate that limit price is provided for LIMIT orders.
     */
    public void validateLimitOrder() {
        if (type == OrderType.LIMIT && limitPrice == null) {
            throw new IllegalArgumentException("Limit price is required for LIMIT orders");
        }
    }
    
    /**
     * Validate that stop price is provided for STOP orders.
     */
    public void validateStopOrder() {
        if ((type == OrderType.STOP || type == OrderType.STOP_LIMIT) && stopPrice == null) {
            throw new IllegalArgumentException("Stop price is required for STOP orders");
        }
    }
}
