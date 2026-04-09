package com.trading.api;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Type alias / backward-compatibility shim.
 * All logic lives in {@link ResilientBrokerClient}.
 * Kept so that files not yet migrated continue to compile without changes.
 *
 * @deprecated Use {@link ResilientBrokerClient} directly.
 */
@Deprecated
public class ResilientAlpacaClient extends ResilientBrokerClient {

    @Deprecated
    public ResilientAlpacaClient(BrokerClient delegate, MeterRegistry meterRegistry) {
        super(delegate, meterRegistry);
    }
}
