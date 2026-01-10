package com.trading.core.model;

import java.time.Instant;

public record Bar(
    Instant timestamp,
    double open,
    double high,
    double low,
    double close,
    long volume
) {}
