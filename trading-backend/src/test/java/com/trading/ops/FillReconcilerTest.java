package com.trading.ops;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trading.persistence.TradeDatabase.TradeRow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("FillReconciler — re-price trades from real Alpaca fills")
class FillReconcilerTest {

    private static final ObjectMapper M = new ObjectMapper();
    private static final Instant T0 = Instant.parse("2026-09-25T14:15:46Z");

    private static String order(String sym, String side, double qty, double px, Instant at) {
        return String.format("{\"symbol\":\"%s\",\"side\":\"%s\",\"filled_qty\":\"%s\",\"filled_avg_price\":\"%s\",\"filled_at\":\"%s\"}",
            sym, side, qty, px, at);
    }

    private static com.fasterxml.jackson.databind.JsonNode orders(String... o) throws Exception {
        return M.readTree("[" + String.join(",", o) + "]");
    }

    private static TradeRow trade(int id, String sym, Instant entry, Instant exit, double ePx, double xPx, double qty, double pnl) {
        return new TradeRow(id, sym, "MACD", entry, exit, ePx, xPx, qty, pnl, "time_decay", "MACD | ORB | regime=WEAK_BULL");
    }

    @Test
    @DisplayName("QQQ partial ladder: entry, exit and P&L come from the three real sells")
    void partialLadder_usesRealFills() throws Exception {
        // Real 2026-09-25 QQQ trade: bought 0.39322 @741.022; sold 0.12976 @742.634, 0.13173 @743.934, 0.13173 @743.152
        var t = trade(1, "QQQ", T0, T0.plusSeconds(3637), 741.0, 743.0, 0.393220272, 0.88);
        var o = orders(
            order("QQQ", "buy", 0.393220272, 741.022, T0.plusSeconds(1)),
            order("QQQ", "sell", 0.12976269, 742.634, T0.plusSeconds(2400)),
            order("QQQ", "sell", 0.131728791, 743.934, T0.plusSeconds(2900)),
            order("QQQ", "sell", 0.131728791, 743.152, T0.plusSeconds(3637)));
        var r = FillReconciler.reconcile(List.of(t), o, 0.03);
        assertEquals(1, r.corrections().size());
        var c = r.corrections().get(0);
        assertEquals(741.022, c.newEntry(), 1e-6);
        assertEquals(0.873, c.newPnl(), 0.005);
    }

    @Test
    @DisplayName("fills of the NEXT same-symbol trade are not attributed to this one")
    void nextTradeFills_areExcluded() throws Exception {
        Instant e2 = T0.plusSeconds(4000);
        var t1 = trade(1, "AAPL", T0, T0.plusSeconds(1000), 100.0, 101.0, 1.0, 1.0);
        var t2 = trade(2, "AAPL", e2, e2.plusSeconds(900), 102.0, 103.0, 1.0, 1.0);
        var o = orders(
            order("AAPL", "buy", 1.0, 100.05, T0.plusSeconds(1)),
            order("AAPL", "sell", 1.0, 100.90, T0.plusSeconds(1000)),
            order("AAPL", "buy", 1.0, 102.10, e2.plusSeconds(1)),
            order("AAPL", "sell", 1.0, 103.20, e2.plusSeconds(900)));
        var r = FillReconciler.reconcile(List.of(t1, t2), o, 0.03);
        assertEquals(2, r.corrections().size());
        assertEquals(0.85, r.corrections().get(0).newPnl(), 0.005);
        assertEquals(1.10, r.corrections().get(1).newPnl(), 0.005);
    }

    @Test
    @DisplayName("ambiguous quantity (sold != bought) is skipped and reported, never guessed")
    void qtyMismatch_isSkipped() throws Exception {
        var t = trade(1, "GLD", T0, T0.plusSeconds(600), 100.0, 101.0, 1.0, 1.0);
        var o = orders(
            order("GLD", "buy", 1.0, 100.0, T0.plusSeconds(1)),
            order("GLD", "sell", 0.5, 101.0, T0.plusSeconds(600)));
        var r = FillReconciler.reconcile(List.of(t), o, 0.03);
        assertTrue(r.corrections().isEmpty());
        assertEquals(1, r.skipped().size());
    }

    @Test
    @DisplayName("already-exact trades produce no correction (idempotent re-run)")
    void exactTrade_noCorrection() throws Exception {
        var t = trade(1, "IWM", T0, T0.plusSeconds(600), 100.0, 101.0, 2.0, 2.0);
        var o = orders(
            order("IWM", "buy", 2.0, 100.0, T0.plusSeconds(1)),
            order("IWM", "sell", 2.0, 101.0, T0.plusSeconds(600)));
        assertTrue(FillReconciler.reconcile(List.of(t), o, 0.03).corrections().isEmpty());
    }
}
