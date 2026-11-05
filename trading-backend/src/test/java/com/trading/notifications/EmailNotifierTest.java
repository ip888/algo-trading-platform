package com.trading.notifications;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * Credentials are secrets, never present in a test run -- the only behavior worth covering here
 * is that a missing/blank address or password disables sending entirely and never throws.
 */
@DisplayName("EmailNotifier tests")
class EmailNotifierTest {

    @Test
    @DisplayName("no credentials -- sendEmail is a silent no-op")
    void noCredentialsIsNoOp() {
        var notifier = new EmailNotifier(null, null, null, null);
        assertDoesNotThrow(() -> notifier.sendEmail("subject", "body"));
    }

    @Test
    @DisplayName("blank credentials -- sendEmail is a silent no-op")
    void blankCredentialsIsNoOp() {
        var notifier = new EmailNotifier("", "", null, null);
        assertDoesNotThrow(() -> notifier.sendEmail("subject", "body"));
    }

    @Test
    @DisplayName("only one of address/password set -- still disabled")
    void partialCredentialsIsNoOp() {
        var notifier = new EmailNotifier("someone@gmail.com", null, null, null);
        assertDoesNotThrow(() -> notifier.sendEmail("subject", "body"));
    }
}
