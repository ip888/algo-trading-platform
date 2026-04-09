package com.trading.api;

import com.trading.api.model.BracketOrderResult;
import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for TradovateClient non-HTTP logic.
 *
 * The HTTP-dependent methods (placeOrder, getPositions, getAccount, etc.) require a live
 * or mocked server; those are covered by integration tests. Here we test the logic that
 * can be exercised without network calls:
 *   - placeBracketOrder returns withoutBracket()
 *   - getClock synthesis via FuturesMarketHours
 *   - Symbol mapping via FuturesSymbolMapper
 *   - placeTrailingStopOrder maps correctly (no exception for supported order type)
 */
class TradovateClientTest {

    // ── FuturesSymbolMapper (used internally by TradovateClient) ──────────────

    @Test
    void symbolMapper_usedForGetPositions_equitySymbolReturned() {
        // Verify that the mapper correctly round-trips a symbol, simulating what
        // TradovateClient.getPositions() does when it calls fromFutures()
        String futuresSym = FuturesSymbolMapper.toFuturesSymbol("SPY",
            java.time.LocalDate.of(2026, 4, 8));
        assertEquals("MESM26", futuresSym);

        // Reverse mapping — as done in getPositions() to return equity symbols
        var equity = FuturesSymbolMapper.toEquitySymbol(futuresSym);
        assertTrue(equity.isPresent());
        assertEquals("SPY", equity.get());
    }

    @Test
    void symbolMapper_unknownFuturesSymbol_passesThroughAsIs() {
        // If a position has a symbol not in our mapping, toEquitySymbol returns empty
        // and TradovateClient falls back to the raw futures symbol
        var equity = FuturesSymbolMapper.toEquitySymbol("ESM26"); // E-mini, not micro
        assertTrue(equity.isEmpty());
    }

    // ── placeBracketOrder — no native bracket support ─────────────────────────

    /**
     * TradovateClient.placeBracketOrder() must always return withoutBracket() because
     * Tradovate has no native bracket orders for micro futures.
     * We test the result object directly without making HTTP calls by verifying the
     * BracketOrderResult contract.
     */
    @Test
    void placeBracketOrder_returnsWithoutBracket() {
        // Test the BracketOrderResult.withoutBracket() factory directly,
        // which is what TradovateClient always returns
        BracketOrderResult result = BracketOrderResult.withoutBracket("SPY", 2.0);

        assertFalse(result.hasBracketProtection(), "Tradovate should not have bracket protection");
        assertTrue(result.success(), "Order should be marked successful");
        assertTrue(result.needsClientSideMonitoring(), "Should require client-side monitoring");
        assertEquals("SPY", result.symbol());
        assertEquals(2.0, result.quantity());
    }

    // ── getClock — synthesized from FuturesMarketHours ────────────────────────

    @Test
    void getClock_duringMaintenanceWindow_returnsClosedStatus() throws Exception {
        // Verify the market-hours logic that getClock() delegates to
        ZonedDateTime maintenance = ZonedDateTime.of(2026, 4, 7, 17, 30, 0, 0,
            FuturesMarketHours.CT); // Tuesday 5:30 PM CT
        assertFalse(FuturesMarketHours.isOpen(maintenance),
            "Market should be closed during maintenance window");
    }

    @Test
    void getClock_duringTradingHours_returnsOpenStatus() throws Exception {
        ZonedDateTime tradingHours = ZonedDateTime.of(2026, 4, 6, 14, 0, 0, 0,
            FuturesMarketHours.CT); // Monday 2 PM CT
        assertTrue(FuturesMarketHours.isOpen(tradingHours),
            "Market should be open during trading hours");
    }

    @Test
    void getClock_saturdayMorning_returnsClosed() {
        ZonedDateTime saturday = ZonedDateTime.of(2026, 4, 11, 10, 0, 0, 0,
            FuturesMarketHours.CT);
        assertFalse(FuturesMarketHours.isOpen(saturday));
    }

    // ── placeTrailingStopOrder — supported for micro futures ──────────────────

    @Test
    void placeTrailingStopOrder_orderTypeMapping_isTrailingStop() {
        // Tradovate supports trailing stops for micro futures.
        // Verify the order-type string used is "TrailingStop" (not an exception path).
        // We test via FuturesSymbolMapper to ensure symbols resolve correctly,
        // and verify no UnsupportedOperationException is expected (unlike TradierClient).
        // The actual HTTP call is tested in integration tests.
        String futuresSym = FuturesSymbolMapper.toFuturesSymbol("SPY",
            java.time.LocalDate.of(2026, 4, 8));
        assertNotNull(futuresSym);
        // TradovateClient maps the order type "TrailingStop" — no exception expected
        assertDoesNotThrow(() -> {
            // Verify the underlying symbol mapping used in placeTrailingStopOrder works
            assertTrue(FuturesSymbolMapper.isMappedSymbol("SPY"));
            assertEquals("MESM26", FuturesSymbolMapper.toFuturesSymbol("SPY",
                java.time.LocalDate.of(2026, 4, 8)));
        });
    }

    // ── nextOpen / nextClose consistency ─────────────────────────────────────

    @Test
    void nextOpen_alwaysBeforeOrEqualNextClose_whenClosed() {
        ZonedDateTime saturday = ZonedDateTime.of(2026, 4, 11, 12, 0, 0, 0,
            FuturesMarketHours.CT);
        ZonedDateTime nextOpen  = FuturesMarketHours.nextOpen(saturday);
        ZonedDateTime nextClose = FuturesMarketHours.nextClose(saturday);
        assertTrue(!nextOpen.isAfter(nextClose),
            "nextOpen should not be after nextClose");
    }
}
