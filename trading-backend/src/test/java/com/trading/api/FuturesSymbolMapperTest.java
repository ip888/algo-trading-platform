package com.trading.api;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class FuturesSymbolMapperTest {

    // Reference date used in the spec: 2026-04-08 → front month is MESM26 (June 2026)
    private static final LocalDate APR_8_2026 = LocalDate.of(2026, 4, 8);

    @Test
    void toFuturesSymbol_spy_returnsCorrectContract() {
        // On 2026-04-08 the front-month CME quarterly contract is June 2026 (M = Jun, 26)
        String result = FuturesSymbolMapper.toFuturesSymbol("SPY", APR_8_2026);
        assertEquals("MESM26", result);
    }

    @Test
    void toFuturesSymbol_allSixPairs() {
        assertEquals("MESM26",  FuturesSymbolMapper.toFuturesSymbol("SPY", APR_8_2026));
        assertEquals("MNQM26",  FuturesSymbolMapper.toFuturesSymbol("QQQ", APR_8_2026));
        assertEquals("MYMM26",  FuturesSymbolMapper.toFuturesSymbol("DIA", APR_8_2026));
        assertEquals("M2KM26",  FuturesSymbolMapper.toFuturesSymbol("IWM", APR_8_2026));
        assertEquals("MGCM26",  FuturesSymbolMapper.toFuturesSymbol("GLD", APR_8_2026));
        assertEquals("MSIM26",  FuturesSymbolMapper.toFuturesSymbol("SLV", APR_8_2026));
    }

    @Test
    void toFuturesSymbol_nearExpiry_rollsToNextQuarter() {
        // June 2026 expiry: 3rd Friday of June 2026 = June 19, 2026
        // Within 5 days before that = June 14 or later should roll to September 2026 (U26)
        LocalDate withinRollWindow = LocalDate.of(2026, 6, 15); // 4 days before Jun 19
        String result = FuturesSymbolMapper.toFuturesSymbol("SPY", withinRollWindow);
        assertEquals("MESU26", result);
    }

    @Test
    void toFuturesSymbol_exactlyAtRollBoundary_doesNotRollYet() {
        // 5 days before June 19 = June 14 — exactly at boundary, still on current contract
        // Roll condition: asOf > expiry - 5  (strictly more than 5 days elapsed past boundary)
        LocalDate fiveDaysBefore = LocalDate.of(2026, 6, 14);
        String result = FuturesSymbolMapper.toFuturesSymbol("SPY", fiveDaysBefore);
        assertEquals("MESM26", result);
    }

    @Test
    void toFuturesSymbol_fourDaysBeforeExpiry_rollsToNextQuarter() {
        // 4 days before June 19 = June 15 — inside roll window, should roll to Sep
        LocalDate fourDaysBefore = LocalDate.of(2026, 6, 15);
        String result = FuturesSymbolMapper.toFuturesSymbol("SPY", fourDaysBefore);
        assertEquals("MESU26", result);
    }

    @Test
    void toFuturesSymbol_sixDaysBeforeExpiry_doesNotRoll() {
        // 6 days before June 19 = June 13 — outside roll window, still June contract
        LocalDate sixDaysBefore = LocalDate.of(2026, 6, 13);
        String result = FuturesSymbolMapper.toFuturesSymbol("SPY", sixDaysBefore);
        assertEquals("MESM26", result);
    }

    @Test
    void toEquitySymbol_stripsSuffix() {
        Optional<String> result = FuturesSymbolMapper.toEquitySymbol("MESM26");
        assertTrue(result.isPresent());
        assertEquals("SPY", result.get());
    }

    @Test
    void toEquitySymbol_allRoots() {
        assertEquals(Optional.of("SPY"), FuturesSymbolMapper.toEquitySymbol("MESM26"));
        assertEquals(Optional.of("QQQ"), FuturesSymbolMapper.toEquitySymbol("MNQM26"));
        assertEquals(Optional.of("DIA"), FuturesSymbolMapper.toEquitySymbol("MYMM26"));
        assertEquals(Optional.of("IWM"), FuturesSymbolMapper.toEquitySymbol("M2KM26"));
        assertEquals(Optional.of("GLD"), FuturesSymbolMapper.toEquitySymbol("MGCM26"));
        assertEquals(Optional.of("SLV"), FuturesSymbolMapper.toEquitySymbol("MSIM26"));
    }

    @Test
    void toEquitySymbol_unknownSymbol_returnsEmpty() {
        Optional<String> result = FuturesSymbolMapper.toEquitySymbol("XYZM26");
        assertTrue(result.isEmpty());
    }

    @Test
    void toEquitySymbol_nullInput_returnsEmpty() {
        assertTrue(FuturesSymbolMapper.toEquitySymbol(null).isEmpty());
    }

    @Test
    void toEquitySymbol_shortInput_returnsEmpty() {
        assertTrue(FuturesSymbolMapper.toEquitySymbol("AB").isEmpty());
    }

    @Test
    void isMappedSymbol_knownSymbols() {
        assertTrue(FuturesSymbolMapper.isMappedSymbol("SPY"));
        assertTrue(FuturesSymbolMapper.isMappedSymbol("QQQ"));
        assertTrue(FuturesSymbolMapper.isMappedSymbol("DIA"));
        assertTrue(FuturesSymbolMapper.isMappedSymbol("IWM"));
        assertTrue(FuturesSymbolMapper.isMappedSymbol("GLD"));
        assertTrue(FuturesSymbolMapper.isMappedSymbol("SLV"));
    }

    @Test
    void isMappedSymbol_unknownSymbol() {
        assertFalse(FuturesSymbolMapper.isMappedSymbol("AAPL"));
        assertFalse(FuturesSymbolMapper.isMappedSymbol("TSLA"));
        assertFalse(FuturesSymbolMapper.isMappedSymbol("MES"));   // root, not equity
    }

    @Test
    void isMappedSymbol_caseInsensitive() {
        assertTrue(FuturesSymbolMapper.isMappedSymbol("spy"));
        assertTrue(FuturesSymbolMapper.isMappedSymbol("Spy"));
    }

    @Test
    void toFuturesSymbol_unmappedSymbol_throwsException() {
        assertThrows(IllegalArgumentException.class,
            () -> FuturesSymbolMapper.toFuturesSymbol("AAPL", APR_8_2026));
    }

    @Test
    void getAllMappedEquitySymbols_containsAllSix() {
        var symbols = FuturesSymbolMapper.getAllMappedEquitySymbols();
        assertTrue(symbols.contains("SPY"));
        assertTrue(symbols.contains("QQQ"));
        assertTrue(symbols.contains("DIA"));
        assertTrue(symbols.contains("IWM"));
        assertTrue(symbols.contains("GLD"));
        assertTrue(symbols.contains("SLV"));
        assertEquals(6, symbols.size());
    }

    @Test
    void thirdFriday_june2026_isJune19() {
        // Verify our expiry computation: 3rd Friday of June 2026 = June 19
        LocalDate expiry = FuturesSymbolMapper.thirdFriday(2026, 6);
        assertEquals(LocalDate.of(2026, 6, 19), expiry);
    }

    @Test
    void thirdFriday_march2026_isMarch20() {
        // 3rd Friday of March 2026 = March 20
        LocalDate expiry = FuturesSymbolMapper.thirdFriday(2026, 3);
        assertEquals(LocalDate.of(2026, 3, 20), expiry);
    }
}
