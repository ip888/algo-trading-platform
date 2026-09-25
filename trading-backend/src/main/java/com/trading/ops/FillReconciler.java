package com.trading.ops;

import com.fasterxml.jackson.databind.JsonNode;
import com.trading.persistence.TradeDatabase.TradeRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Re-prices closed DB trades from Alpaca's REAL fills.
 *
 * Why: intraday the bot only knows the decision-time quote (entry) and {@code marketValue/qty} at the
 * moment it fires the exit (exit). Those differ from the actual market-order fills by slippage, which is
 * the residual gap left between DB P&L and Alpaca's equity change after the 2026-09-21 fixes
 * (Tue -$0.46, Thu +$0.14). This runs after the close, when every fill has settled, so the trading loop
 * never blocks on it.
 *
 * Matching (one open position per symbol at a time, so a trade's fills are the symbol's fills inside its
 * lifetime): buys filled within ±60s of the recorded entry; sells filled from entry until
 * min(exit+10min, next same-symbol entry). A correction is emitted only when total sold ≈ total bought
 * ≈ the trade's recorded quantity (within {@code qtyTolerance}) — anything ambiguous is left untouched
 * and reported, never guessed.
 */
public final class FillReconciler {

    private static final Logger logger = LoggerFactory.getLogger(FillReconciler.class);
    private static final Duration ENTRY_WINDOW = Duration.ofSeconds(60);
    private static final Duration EXIT_GRACE = Duration.ofMinutes(10);

    public record Correction(int tradeId, String symbol, double oldEntry, double newEntry,
                             double oldExit, double newExit, double oldPnl, double newPnl) {}

    public record Result(List<Correction> corrections, List<String> skipped) {}

    private record Fill(String symbol, String side, double qty, double price, Instant at) {}

    private FillReconciler() {}

    public static Result reconcile(List<TradeRow> trades, JsonNode orders, double qtyTolerance) {
        var fills = new ArrayList<Fill>();
        if (orders != null && orders.isArray()) {
            for (JsonNode o : orders) {
                double q = o.path("filled_qty").asDouble(0);
                double p = o.path("filled_avg_price").asDouble(0);
                String at = o.path("filled_at").asText("");
                if (q <= 0 || p <= 0 || at.isEmpty() || "null".equals(at)) continue;
                try {
                    fills.add(new Fill(o.path("symbol").asText(), o.path("side").asText(), q, p, Instant.parse(at)));
                } catch (Exception ignored) { /* malformed timestamp: skip this order */ }
            }
        }

        var corrections = new ArrayList<Correction>();
        var skipped = new ArrayList<String>();
        var sorted = new ArrayList<>(trades);
        sorted.sort(Comparator.comparing(TradeRow::entryTime));

        for (int i = 0; i < sorted.size(); i++) {
            TradeRow t = sorted.get(i);
            Instant nextEntry = Instant.MAX;
            for (int j = i + 1; j < sorted.size(); j++) {
                if (sorted.get(j).symbol().equals(t.symbol())) { nextEntry = sorted.get(j).entryTime(); break; }
            }
            Instant sellEnd = t.exitTime().plus(EXIT_GRACE);
            if (nextEntry.isBefore(sellEnd)) sellEnd = nextEntry;

            double buyQty = 0, buyVal = 0, sellQty = 0, sellVal = 0;
            for (Fill f : fills) {
                if (!f.symbol().equals(t.symbol())) continue;
                if ("buy".equals(f.side())
                        && !f.at().isBefore(t.entryTime().minus(ENTRY_WINDOW))
                        && !f.at().isAfter(t.entryTime().plus(ENTRY_WINDOW))) {
                    buyQty += f.qty(); buyVal += f.qty() * f.price();
                } else if ("sell".equals(f.side())
                        && !f.at().isBefore(t.entryTime())
                        && !f.at().isAfter(sellEnd)) {
                    sellQty += f.qty(); sellVal += f.qty() * f.price();
                }
            }
            if (buyQty <= 0 || sellQty <= 0) {
                skipped.add(t.symbol() + "#" + t.id() + ": no matching fills (buy=" + buyQty + ", sell=" + sellQty + ")");
                continue;
            }
            if (Math.abs(sellQty - buyQty) / buyQty > qtyTolerance
                    || Math.abs(buyQty - t.quantity()) / t.quantity() > qtyTolerance) {
                skipped.add(String.format("%s#%d: qty mismatch (db=%.4f buy=%.4f sell=%.4f)",
                    t.symbol(), t.id(), t.quantity(), buyQty, sellQty));
                continue;
            }
            double newEntry = buyVal / buyQty;
            double newExit = sellVal / sellQty;
            double newPnl = sellQty * (newExit - newEntry);
            if (Math.abs(newPnl - t.pnl()) < 0.005
                    && Math.abs(newEntry - t.entryPrice()) < 0.0005 && Math.abs(newExit - t.exitPrice()) < 0.0005) {
                continue; // already exact
            }
            corrections.add(new Correction(t.id(), t.symbol(), t.entryPrice(), newEntry,
                t.exitPrice(), newExit, t.pnl(), newPnl));
        }
        logger.debug("FillReconciler: {} correction(s), {} skipped", corrections.size(), skipped.size());
        return new Result(corrections, skipped);
    }
}
