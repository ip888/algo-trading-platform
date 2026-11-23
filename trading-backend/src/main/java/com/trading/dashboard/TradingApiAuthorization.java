package com.trading.dashboard;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * P0: fail-closed authorization for mutating trading dashboard actions.
 * The credential must be provisioned as an out-of-band deployment secret;
 * never commit it to config.properties or send it over a query parameter.
 */
public final class TradingApiAuthorization {
    private TradingApiAuthorization() {}

    public static boolean isConfigured(String secret) {
        return secret != null && secret.length() >= 32;
    }

    public static boolean isAuthorized(String bearer, String secret) {
        if (!isConfigured(secret) || bearer == null || !bearer.startsWith("Bearer ")) return false;
        String supplied = bearer.substring(7);
        if (supplied.isEmpty()) return false;
        return MessageDigest.isEqual(
            supplied.getBytes(StandardCharsets.UTF_8),
            secret.getBytes(StandardCharsets.UTF_8));
    }
}
