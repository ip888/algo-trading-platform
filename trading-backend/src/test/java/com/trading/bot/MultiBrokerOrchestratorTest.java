package com.trading.bot;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for MultiBrokerOrchestrator parsing logic.
 *
 * Design: each broker uses its OWN account balance independently.
 * The percentage in BROKERS=alpaca:80,tradier:100 means
 * "use 80% of Alpaca's own account equity" — NOT a cross-broker capital split.
 */
class MultiBrokerOrchestratorTest {

    // ── parseAllocation ───────────────────────────────────────────────────────

    @Test
    void parseAllocation_typicalThreeBrokers() {
        Map<String, Double> result = MultiBrokerOrchestrator.parseAllocation(
            "alpaca:80,tradier:100,tradovate:100");
        assertEquals(3, result.size());
        assertEquals(80.0,  result.get("alpaca"));
        assertEquals(100.0, result.get("tradier"));
        assertEquals(100.0, result.get("tradovate"));
    }

    @Test
    void parseAllocation_singleBrokerFullBalance() {
        Map<String, Double> result = MultiBrokerOrchestrator.parseAllocation("alpaca:100");
        assertEquals(1, result.size());
        assertEquals(100.0, result.get("alpaca"));
    }

    @Test
    void parseAllocation_partialBalance() {
        Map<String, Double> result = MultiBrokerOrchestrator.parseAllocation("alpaca:50");
        assertEquals(50.0, result.get("alpaca"));
    }

    @Test
    void parseAllocation_caseInsensitive() {
        Map<String, Double> result = MultiBrokerOrchestrator.parseAllocation("ALPACA:80,Tradier:100");
        assertTrue(result.containsKey("alpaca"));
        assertTrue(result.containsKey("tradier"));
    }

    @Test
    void parseAllocation_spacesAroundDelimiters() {
        Map<String, Double> result = MultiBrokerOrchestrator.parseAllocation(" alpaca : 80 , tradier : 100 ");
        assertEquals(2, result.size());
        assertEquals(80.0,  result.get("alpaca"));
        assertEquals(100.0, result.get("tradier"));
    }

    @Test
    void parseAllocation_emptyString_returnsEmptyMap() {
        assertTrue(MultiBrokerOrchestrator.parseAllocation("").isEmpty());
        assertTrue(MultiBrokerOrchestrator.parseAllocation(null).isEmpty());
        assertTrue(MultiBrokerOrchestrator.parseAllocation("   ").isEmpty());
    }

    @Test
    void parseAllocation_invalidEntry_skipped() {
        Map<String, Double> result = MultiBrokerOrchestrator.parseAllocation("alpaca:80,badentry,tradier:100");
        assertEquals(2, result.size());
        assertFalse(result.containsKey("badentry"));
    }

    @Test
    void parseAllocation_zeroPercent_rejected() {
        Map<String, Double> result = MultiBrokerOrchestrator.parseAllocation("alpaca:0,tradier:100");
        assertEquals(1, result.size());
        assertFalse(result.containsKey("alpaca"),
            "0% should be rejected — bot would trade nothing");
    }

    @Test
    void parseAllocation_overHundredPercent_rejected() {
        Map<String, Double> result = MultiBrokerOrchestrator.parseAllocation("alpaca:101,tradier:100");
        assertEquals(1, result.size());
        assertFalse(result.containsKey("alpaca"),
            "Over 100% of own balance is invalid");
    }

    @Test
    void parseAllocation_nonNumericPercent_skipped() {
        Map<String, Double> result = MultiBrokerOrchestrator.parseAllocation("alpaca:abc,tradier:100");
        assertEquals(1, result.size());
    }

    @Test
    void parseAllocation_preservesInsertionOrder() {
        Map<String, Double> result = MultiBrokerOrchestrator.parseAllocation(
            "tradier:100,alpaca:80,ibkr:100");
        List<String> keys = new java.util.ArrayList<>(result.keySet());
        assertEquals("tradier",  keys.get(0));
        assertEquals("alpaca",   keys.get(1));
        assertEquals("ibkr",     keys.get(2));
    }

    // ── Capital isolation semantics ───────────────────────────────────────────

    @Test
    void capitalIsolation_eachBrokerUsesOwnBalance() {
        // Given: alpaca has $5000, tradier has $3000
        // BROKERS=alpaca:80,tradier:100
        // alpaca bot capital = $5000 * 0.80 = $4000
        // tradier bot capital = $3000 * 1.00 = $3000
        // Total bots capital = $7000 — these are INDEPENDENT, not sliced from a shared pool
        double alpacaBalance = 5_000.0;
        double tradierBalance = 3_000.0;
        Map<String, Double> parsed = MultiBrokerOrchestrator.parseAllocation("alpaca:80,tradier:100");

        double alpacaCapital  = alpacaBalance  * (parsed.get("alpaca")  / 100.0);
        double tradierCapital = tradierBalance * (parsed.get("tradier") / 100.0);

        assertEquals(4_000.0, alpacaCapital,  0.01, "Alpaca uses 80% of its own $5000");
        assertEquals(3_000.0, tradierCapital, 0.01, "Tradier uses 100% of its own $3000");

        // They are NOT linked — changing one doesn't affect the other
        assertNotEquals(alpacaCapital, tradierCapital);
    }

    @Test
    void capitalIsolation_100PercentMeansFullBrokerBalance() {
        double balance = 2_500.0;
        Map<String, Double> parsed = MultiBrokerOrchestrator.parseAllocation("tradier:100");
        double capital = balance * (parsed.get("tradier") / 100.0);
        assertEquals(balance, capital, 0.01);
    }

    @Test
    void capitalIsolation_50PercentMeansHalfBrokerBalance() {
        double balance = 4_000.0;
        Map<String, Double> parsed = MultiBrokerOrchestrator.parseAllocation("alpaca:50");
        double capital = balance * (parsed.get("alpaca") / 100.0);
        assertEquals(2_000.0, capital, 0.01);
    }
}
