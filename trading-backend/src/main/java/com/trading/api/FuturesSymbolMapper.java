package com.trading.api;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Maps equity ETF symbols to CME micro-futures front-month contracts, and back.
 *
 * Supported mappings:
 *   SPY → MES  (Micro E-mini S&P 500)
 *   QQQ → MNQ  (Micro E-mini Nasdaq-100)
 *   DIA → MYM  (Micro E-mini Dow Jones)
 *   IWM → M2K  (Micro E-mini Russell 2000)
 *   GLD → MGC  (Micro Gold)
 *   SLV → MSI  (Micro Silver)
 *
 * CME quarterly expiry months: March(H), June(M), September(U), December(Z)
 * Expiry: 3rd Friday of the expiry month.
 * Roll rule: if within 5 days of expiry, use the next quarterly contract.
 */
public final class FuturesSymbolMapper {

    /** Equity ETF → futures root code */
    private static final Map<String, String> EQUITY_TO_FUTURES = Map.of(
        "SPY", "MES",
        "QQQ", "MNQ",
        "DIA", "MYM",
        "IWM", "M2K",
        "GLD", "MGC",
        "SLV", "MSI"
    );

    /** Futures root code → equity ETF (reverse map) */
    private static final Map<String, String> FUTURES_TO_EQUITY;

    static {
        var reverse = new java.util.HashMap<String, String>();
        EQUITY_TO_FUTURES.forEach((equity, futures) -> reverse.put(futures, equity));
        FUTURES_TO_EQUITY = Map.copyOf(reverse);
    }

    /** CME quarterly expiry months in order */
    private static final int[] QUARTERLY_MONTHS = {3, 6, 9, 12}; // Mar, Jun, Sep, Dec

    /** Month number → futures month code letter */
    private static final Map<Integer, String> MONTH_CODE;

    static {
        var m = new java.util.HashMap<Integer, String>();
        m.put(1, "F"); m.put(2, "G"); m.put(3, "H");  m.put(4, "J");
        m.put(5, "K"); m.put(6, "M"); m.put(7, "N");  m.put(8, "Q");
        m.put(9, "U"); m.put(10, "V"); m.put(11, "X"); m.put(12, "Z");
        MONTH_CODE = Map.copyOf(m);
    }

    private static final int ROLL_DAYS_BEFORE_EXPIRY = 5;

    private FuturesSymbolMapper() {}

    /**
     * Convert an equity symbol to a front-month futures contract symbol.
     *
     * @param equitySymbol  e.g. "SPY"
     * @param asOf          date to compute the front month from
     * @return              e.g. "MESM26" (June 2026 MES contract)
     * @throws IllegalArgumentException if the equity symbol is not mapped
     */
    public static String toFuturesSymbol(String equitySymbol, LocalDate asOf) {
        String root = EQUITY_TO_FUTURES.get(equitySymbol.toUpperCase());
        if (root == null) {
            throw new IllegalArgumentException("No futures mapping for equity symbol: " + equitySymbol);
        }
        LocalDate expiry = findFrontMonthExpiry(asOf);
        int month = expiry.getMonthValue();
        int year  = expiry.getYear() % 100; // last 2 digits
        return root + MONTH_CODE.get(month) + String.format("%02d", year);
    }

    /**
     * Reverse-map a futures symbol back to its equity ETF symbol.
     *
     * @param futuresSymbol  e.g. "MESM26"
     * @return               Optional containing e.g. "SPY", or empty if not recognized
     */
    public static Optional<String> toEquitySymbol(String futuresSymbol) {
        if (futuresSymbol == null || futuresSymbol.length() < 4) return Optional.empty();
        // Strip trailing month-code (1 letter) + year (2 digits) = 3 chars
        String root = futuresSymbol.substring(0, futuresSymbol.length() - 3);
        return Optional.ofNullable(FUTURES_TO_EQUITY.get(root));
    }

    /**
     * Returns true if the given equity symbol has a futures mapping.
     */
    public static boolean isMappedSymbol(String equitySymbol) {
        return equitySymbol != null && EQUITY_TO_FUTURES.containsKey(equitySymbol.toUpperCase());
    }

    /**
     * Returns all equity symbols that have a futures mapping.
     */
    public static Set<String> getAllMappedEquitySymbols() {
        return EQUITY_TO_FUTURES.keySet();
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Given a reference date, find the front-month quarterly expiry date.
     * If within ROLL_DAYS_BEFORE_EXPIRY of the current quarter's expiry, roll forward.
     */
    private static LocalDate findFrontMonthExpiry(LocalDate asOf) {
        for (int[] yearMonthPair : upcomingQuarterlyMonths(asOf)) {
            int year  = yearMonthPair[0];
            int month = yearMonthPair[1];
            LocalDate expiry = thirdFriday(year, month);
            if (!asOf.isAfter(expiry.minusDays(ROLL_DAYS_BEFORE_EXPIRY))) {
                return expiry;
            }
            // Too close — try next quarter
        }
        throw new IllegalStateException("Could not determine front-month contract for " + asOf);
    }

    /**
     * Generate upcoming quarterly months (year, month) pairs starting from asOf's quarter.
     * Returns enough entries to always find a valid contract.
     */
    private static int[][] upcomingQuarterlyMonths(LocalDate asOf) {
        int[][] result = new int[8][2]; // 8 quarters is more than enough
        int idx = 0;
        int year  = asOf.getYear();
        int month = asOf.getMonthValue();

        // Find the current or next quarterly month
        int qi = 0;
        while (qi < QUARTERLY_MONTHS.length && QUARTERLY_MONTHS[qi] < month) qi++;

        for (int i = 0; i < 8; i++) {
            if (qi >= QUARTERLY_MONTHS.length) {
                qi = 0;
                year++;
            }
            result[idx][0] = year;
            result[idx][1] = QUARTERLY_MONTHS[qi];
            idx++;
            qi++;
        }
        return result;
    }

    /**
     * Compute the 3rd Friday of the given month/year.
     */
    static LocalDate thirdFriday(int year, int month) {
        LocalDate first = LocalDate.of(year, month, 1);
        // Find first Friday
        int daysUntilFriday = (DayOfWeek.FRIDAY.getValue() - first.getDayOfWeek().getValue() + 7) % 7;
        LocalDate firstFriday = first.plusDays(daysUntilFriday);
        return firstFriday.plusWeeks(2); // 3rd Friday = first Friday + 2 weeks
    }
}
