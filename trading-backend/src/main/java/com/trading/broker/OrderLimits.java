package com.trading.broker;

/**
 * Order limits for trading pairs
 * @param minVolume Minimum order volume (in base currency)
 * @param minNotional Minimum order value in USD
 */
public record OrderLimits(double minVolume, double minNotional) {
    
    /**
     * Validate if order meets minimums
     */
    public boolean isValid(double volume, double price) {
        double notional = volume * price;
        return volume >= minVolume && notional >= minNotional;
    }
    
    /**
     * Get validation error message
     */
    public String getValidationError(double volume, double price) {
        double notional = volume * price;
        
        if (volume < minVolume) {
            return String.format("Volume %.8f below minimum %.8f", volume, minVolume);
        }
        
        if (notional < minNotional) {
            return String.format("Order value $%.2f below minimum $%.2f", notional, minNotional);
        }
        
        return "Valid";
    }
}
