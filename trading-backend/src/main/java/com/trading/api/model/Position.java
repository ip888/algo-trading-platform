package com.trading.api.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Immutable record representing an account position.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Position(
    @JsonProperty("symbol") String symbol,
    @JsonProperty("qty") double quantity,
    @JsonProperty("market_value") double marketValue,
    @JsonProperty("avg_entry_price") double avgEntryPrice,
    @JsonProperty("unrealized_pl") double unrealizedPL
) {}
