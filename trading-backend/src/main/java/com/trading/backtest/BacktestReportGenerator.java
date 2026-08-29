package com.trading.backtest;

import com.trading.backtest.WalkForwardBacktestHarness.BacktestTrade;

import java.util.*;

/**
 * Turns a {@link WalkForwardBacktestHarness} run's closed trades into the same shape the
 * live dashboard's {@code /api/trades/export/json} analysis uses (win rate, avg win/loss,
 * P&L by exit-type, by strategy) — so a backtest result and a live week are directly
 * comparable, the way every ad-hoc live-trade-data analysis this session has been done by hand.
 */
public final class BacktestReportGenerator {

    private BacktestReportGenerator() {}

    public record ExitTypeBreakdown(String exitType, double pnl, int count) {}
    public record StrategyBreakdown(String strategy, double pnl, int count, double winRate) {}
    public record SymbolBreakdown(String symbol, double pnl, int count, int wins) {}

    public record BacktestReport(
        int totalTrades,
        int wins,
        int losses,
        double winRate,
        double netPnl,
        double avgWin,
        double avgLoss,
        double startEquity,
        double endEquity,
        double maxDrawdownPercent,
        List<ExitTypeBreakdown> byExitType,
        List<StrategyBreakdown> byStrategy,
        List<SymbolBreakdown> bySymbol,
        List<BacktestTrade> trades
    ) {
        public String summary() {
            var sb = new StringBuilder();
            sb.append(String.format(
                "=== Walk-Forward Backtest ===%nTrades: %d (%dW/%dL, %.1f%% win rate)%n" +
                "Net P&L: $%.2f | Equity: $%.2f -> $%.2f | Max Drawdown: %.1f%%%n" +
                "Avg Win: $%.3f | Avg Loss: $%.3f%n%n",
                totalTrades, wins, losses, winRate * 100,
                netPnl, startEquity, endEquity, maxDrawdownPercent,
                avgWin, avgLoss));
            sb.append("By exit type:\n");
            for (var e : byExitType) {
                sb.append(String.format("  %-14s pnl=%8.2f  count=%d%n", e.exitType(), e.pnl(), e.count()));
            }
            sb.append("By strategy:\n");
            for (var s : byStrategy) {
                sb.append(String.format("  %-14s pnl=%8.2f  count=%d  winRate=%.1f%%%n",
                    s.strategy(), s.pnl(), s.count(), s.winRate() * 100));
            }
            return sb.toString();
        }
    }

    public static BacktestReport generate(List<BacktestTrade> trades, double startEquity,
                                          double endEquity, List<double[]> equityCurve) {
        int wins = 0, losses = 0;
        double grossWin = 0, grossLoss = 0;
        var byExitType = new LinkedHashMap<String, double[]>(); // pnl, count
        var byStrategy = new LinkedHashMap<String, double[]>(); // pnl, count, wins
        var bySymbol = new LinkedHashMap<String, double[]>();   // pnl, count, wins

        for (var t : trades) {
            boolean isWin = t.pnl() > 0;
            if (isWin) { wins++; grossWin += t.pnl(); } else { losses++; grossLoss += Math.abs(t.pnl()); }

            byExitType.computeIfAbsent(t.exitReason(), k -> new double[2]);
            var et = byExitType.get(t.exitReason());
            et[0] += t.pnl(); et[1] += 1;

            byStrategy.computeIfAbsent(t.strategy(), k -> new double[3]);
            var st = byStrategy.get(t.strategy());
            st[0] += t.pnl(); st[1] += 1; if (isWin) st[2] += 1;

            bySymbol.computeIfAbsent(t.symbol(), k -> new double[3]);
            var sy = bySymbol.get(t.symbol());
            sy[0] += t.pnl(); sy[1] += 1; if (isWin) sy[2] += 1;
        }

        int total = trades.size();
        double winRate = total > 0 ? (double) wins / total : 0.0;
        double avgWin = wins > 0 ? grossWin / wins : 0.0;
        double avgLoss = losses > 0 ? -(grossLoss / losses) : 0.0;
        double netPnl = grossWin - grossLoss;

        double peak = startEquity, maxDd = 0.0;
        for (var point : equityCurve) {
            double eq = point[1];
            peak = Math.max(peak, eq);
            if (peak > 0) {
                maxDd = Math.max(maxDd, (peak - eq) / peak * 100.0);
            }
        }

        var exitTypeList = byExitType.entrySet().stream()
            .map(e -> new ExitTypeBreakdown(e.getKey(), e.getValue()[0], (int) e.getValue()[1]))
            .sorted(Comparator.comparingDouble(ExitTypeBreakdown::pnl).reversed())
            .toList();
        var strategyList = byStrategy.entrySet().stream()
            .map(e -> new StrategyBreakdown(e.getKey(), e.getValue()[0], (int) e.getValue()[1],
                e.getValue()[1] > 0 ? e.getValue()[2] / e.getValue()[1] : 0.0))
            .sorted(Comparator.comparingDouble(StrategyBreakdown::pnl).reversed())
            .toList();
        var symbolList = bySymbol.entrySet().stream()
            .map(e -> new SymbolBreakdown(e.getKey(), e.getValue()[0], (int) e.getValue()[1], (int) e.getValue()[2]))
            .sorted(Comparator.comparingDouble(SymbolBreakdown::pnl).reversed())
            .toList();

        return new BacktestReport(total, wins, losses, winRate, netPnl, avgWin, avgLoss,
            startEquity, endEquity, maxDd, exitTypeList, strategyList, symbolList, trades);
    }
}
