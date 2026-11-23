package com.trading.dashboard;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TradingApiAuthorizationTest {
    private static final String TOKEN = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghij";

    @Test
    void validBearerTokenIsAccepted() {
        assertTrue(TradingApiAuthorization.isConfigured(TOKEN));
        assertTrue(TradingApiAuthorization.isAuthorized("Bearer " + TOKEN, TOKEN));
    }

    @Test
    void anonymousAndWrongTokenAreDenied() {
        assertFalse(TradingApiAuthorization.isAuthorized(null, TOKEN));
        assertFalse(TradingApiAuthorization.isAuthorized("", TOKEN));
        assertFalse(TradingApiAuthorization.isAuthorized("Basic " + TOKEN, TOKEN));
        assertFalse(TradingApiAuthorization.isAuthorized("Bearer something-else", TOKEN));
        assertFalse(TradingApiAuthorization.isAuthorized("Bearer " + TOKEN + "extra", TOKEN));
    }

    @Test
    void missingOrWeakConfigurationAlwaysFailsClosed() {
        assertFalse(TradingApiAuthorization.isConfigured(null));
        assertFalse(TradingApiAuthorization.isConfigured("short"));
        assertFalse(TradingApiAuthorization.isAuthorized("Bearer short", "short"));
        assertFalse(TradingApiAuthorization.isAuthorized("Bearer " + TOKEN, null));
    }
}
