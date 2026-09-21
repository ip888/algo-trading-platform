package com.trading.persistence;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * closeTrade() used to price the WHOLE original lot at the final exit price, ignoring shares already
 * sold by partial exits — the 2026-09-21 review measured DB +$4.84 vs broker +$2.35. It must now
 * price only the remaining shares at the final exit and add the banked partial proceeds.
 */
@DisplayName("TradeDatabase — partial-exit proceeds are honoured by closeTrade")
class TradeDatabasePartialExitPnLTest {

    private static final String TEST_DB = "test-partial-exit-pnl.db";
    private TradeDatabase db;

    @BeforeEach
    void setUp() {
        new File(TEST_DB).delete();
        db = new TradeDatabase(TEST_DB);
    }

    @AfterEach
    void tearDown() {
        db.close();
        new File(TEST_DB).delete();
    }

    private void open(String symbol, double entry, double qty) {
        db.recordTrade(symbol, "MACD", "MAIN", "alpaca",
            Instant.now().minus(1, ChronoUnit.HOURS), entry, qty, entry * 0.99, entry * 1.02);
    }

    @Test
    @DisplayName("no partial exit → unchanged: (exit - entry) * qty")
    void noPartial_unchanged() {
        open("SPY", 100.0, 10.0);
        db.closeTrade("SPY", Instant.now(), 101.0, 999.0 /* ignored */, "alpaca", "take_profit");
        assertEquals(10.0, db.getTodayPnL(), 0.001);
    }

    @Test
    @DisplayName("half sold at +2, remainder stopped at +0.5 → 5*2 + 5*0.5, NOT 10*0.5")
    void partialThenFinal_usesBankedProceeds() {
        open("SPY", 100.0, 10.0);
        db.recordPartialExitFill("SPY", "alpaca", 5.0, 102.0);
        db.closeTrade("SPY", Instant.now(), 100.5, 0.0, "alpaca", "stop_loss");
        assertEquals(12.5, db.getTodayPnL(), 0.001,
            "5 sh @ +2.00 banked + 5 sh @ +0.50 = 12.50 (the old full-lot formula gave 5.00)");
    }

    @Test
    @DisplayName("two partials then final are all accumulated (5-arg closeTrade too)")
    void twoPartials_fiveArgClose() {
        open("QQQ", 200.0, 9.0);
        db.recordPartialExitFill("QQQ", "alpaca", 3.0, 201.0);  // +3
        db.recordPartialExitFill("QQQ", "alpaca", 3.0, 202.0);  // +6
        db.closeTrade("QQQ", Instant.now(), 203.0, 0.0, "alpaca"); // 3 sh left @ +3 = +9
        assertEquals(18.0, db.getTodayPnL(), 0.001);
    }

    @Test
    @DisplayName("updateEntryPrice re-anchors the open lot so P&L uses the real fill")
    void entryReanchoredToFill() {
        open("NVDA", 100.0, 10.0);
        db.updateEntryPrice("NVDA", "alpaca", 100.10); // paid 10c slippage
        db.closeTrade("NVDA", Instant.now(), 101.0, 0.0, "alpaca", "take_profit");
        assertEquals(9.0, db.getTodayPnL(), 0.001, "(101.00 - 100.10) * 10 = 9.00, not 10.00");
    }
}
