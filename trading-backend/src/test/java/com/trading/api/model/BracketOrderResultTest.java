package com.trading.api.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for BracketOrderResult which tracks whether server-side
 * bracket protection was applied to an order.
 */
@DisplayName("BracketOrderResult Tests")
class BracketOrderResultTest {

    @Nested
    @DisplayName("Factory Methods")
    class FactoryMethodTests {

        @Test
        @DisplayName("withBracket() should create result with full protection")
        void testWithBracket() {
            var result = BracketOrderResult.withBracket("AAPL", 10.0);

            assertTrue(result.success());
            assertTrue(result.hasBracketProtection());
            assertEquals("AAPL", result.symbol());
            assertEquals(10.0, result.quantity());
            assertFalse(result.needsClientSideMonitoring());
        }

        @Test
        @DisplayName("withoutBracket() should create result needing client-side monitoring")
        void testWithoutBracket() {
            var result = BracketOrderResult.withoutBracket("GOOGL", 0.5);

            assertTrue(result.success());
            assertFalse(result.hasBracketProtection());
            assertEquals("GOOGL", result.symbol());
            assertEquals(0.5, result.quantity());
            assertTrue(result.needsClientSideMonitoring(),
                "Fractional order without bracket should need client-side monitoring");
        }

        @Test
        @DisplayName("failed() should create result for failed order")
        void testFailed() {
            var result = BracketOrderResult.failed("TSLA", 5.0, "insufficient buying power");

            assertFalse(result.success());
            assertFalse(result.hasBracketProtection());
            assertEquals("TSLA", result.symbol());
            assertTrue(result.message().contains("insufficient buying power"));
            assertFalse(result.needsClientSideMonitoring(),
                "Failed order doesn't need monitoring - there's no position");
        }
    }

    @Nested
    @DisplayName("needsClientSideMonitoring Logic")
    class ClientSideMonitoringTests {

        @Test
        @DisplayName("Should need monitoring only for successful orders without bracket")
        void testNeedsMonitoringLogic() {
            // Successful with bracket - no monitoring needed
            assertFalse(BracketOrderResult.withBracket("A", 1).needsClientSideMonitoring());

            // Successful without bracket - monitoring needed!
            assertTrue(BracketOrderResult.withoutBracket("B", 1).needsClientSideMonitoring());

            // Failed - no monitoring (no position created)
            assertFalse(BracketOrderResult.failed("C", 1, "error").needsClientSideMonitoring());
        }
    }

    @Nested
    @DisplayName("Message Content")
    class MessageContentTests {

        @Test
        @DisplayName("Bracket order message should mention protection")
        void testBracketMessage() {
            var result = BracketOrderResult.withBracket("SPY", 10);
            assertTrue(result.message().toLowerCase().contains("protection"));
        }

        @Test
        @DisplayName("No-bracket message should warn about fractional")
        void testNoBracketMessage() {
            var result = BracketOrderResult.withoutBracket("QQQ", 0.75);
            assertTrue(result.message().toLowerCase().contains("fractional") ||
                       result.message().toLowerCase().contains("bracket"));
            assertTrue(result.message().contains("⚠️") || result.message().toLowerCase().contains("client-side"));
        }

        @Test
        @DisplayName("Failed message should include error")
        void testFailedMessage() {
            var result = BracketOrderResult.failed("IWM", 1, "order rejected");
            assertTrue(result.message().contains("order rejected"));
        }
    }
}
