package com.trading.portfolio;

import com.trading.api.model.BracketOrderResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Fail-closed policy for ambiguous broker acknowledgements.
 * This only tests the acknowledgement gate, not broker fill reconciliation.
 */
@DisplayName("P0 bracket submission safety gate")
class ProfileManagerBracketSubmissionSafetyTest {

    @Test
    void failedBracketMustBlockFallback() {
        assertTrue(ProfileManager.bracketSubmissionUnconfirmed(
            BracketOrderResult.failed("SPY", 1.0, "HTTP timeout")));
    }

    @Test
    void missingBrokerResponseMustBlockFallback() {
        assertTrue(ProfileManager.bracketSubmissionUnconfirmed(null));
    }

    @Test
    void acceptedBracketMayProceedToReconciliation() {
        assertFalse(ProfileManager.bracketSubmissionUnconfirmed(
            BracketOrderResult.withBracket("SPY", 1.0)));
    }

    @Test
    void acceptedFractionalEntryMayProceedToProtectionCheck() {
        assertFalse(ProfileManager.bracketSubmissionUnconfirmed(
            BracketOrderResult.withoutBracket("SPY", 0.5)));
    }
}
