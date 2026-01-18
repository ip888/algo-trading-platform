package com.trading.api.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;

/**
 * Immutable record representing a market data bar (OHLCV).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Bar(
    @JsonProperty("t") Instant timestamp,
    @JsonProperty("o") double open,
    @JsonProperty("h") double high,
    @JsonProperty("l") double low,
    @JsonProperty("c") double close,
    @JsonProperty("v") long volume
) {
    public Bar {
        if (close <= 0) {
            throw new IllegalArgumentException("Close price must be positive");
        }
    }
}
