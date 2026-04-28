# Profitability Evaluation Plan — Tier 1–3 Modifications

This document describes how to measure whether the Tier 1–3 changes (per-symbol post-loss cooldown, ATR stops/sizing, correlation cap, earnings blackout, scale-out at 1R, time-stop, strict regime routing, no-trade open window, equity-curve circuit breaker) make the bot more profitable than the prior baseline.

## 1. What changed (recap)

| Tier | Feature | Where it lives |
|------|---------|----------------|
| 1.1 | Per-symbol post-loss cooldown (24h base / 72h escalated) | `PostLossCooldownTracker` + `ProfileManager.handleBuy` gate |
| 1.2 | ATR-scaled stops & take-profits | `RiskManager.calculateAtrStopLoss`/`calculateAtrTakeProfit` + `ProfileManager.handleBuy` |
| 1.3 | Vol-targeted (ATR-aware) position sizing | `RiskManager.calculateVolTargetedSize` + `AdvancedPositionSizer.calculateAtrPositionSize` |
| 2.4 | Correlation/concentration cap | Existing logic, gated through new entry-flow |
| 2.5 | Earnings-window blackout | `EarningsCalendarService` + Alpha Vantage feed |
| 2.6 | Scale-out at 1R (partial exit) | `ExitStrategyManager.evaluateScaleOutAtR` (slot 4 in partial-exit bitmask) |
| 2.7 | Time-based stop (N bars / max-R move) | `ExitStrategyManager.evaluateTimeStop` |
| 3.8 | Strict regime routing in WEAK_BEAR (no new longs on non-momentum) | `StrategyManager` |
| 3.9 | No-trade window at the open | `MarketHoursFilter.isInOpeningWindow` + handleBuy gate |
| 3.10 | Per-broker session circuit breaker (consecutive losses + drawdown) | `CircuitBreakerState` + `ProfileManager` cycle hook |

Surface area in the dashboard (`/api/bot/behavior`):
- New `postLossCooldowns[]` and `circuitBreakers{}` payload fields
- Two new health-check cards: **Post-Loss Cooldowns (Tier 1.1)** and **Session Circuit Breaker (Tier 3.10)**

## 2. Hypothesis

> The combined Tier 1–3 changes will raise net P&L vs. the prior baseline by reducing average loss size (ATR stops + scale-out + time-stop) and the rate of losing trades (post-loss cooldown + earnings blackout + open window + strict bear routing), while sizing-tail-risk down via vol-targeted sizing and circuit breaker.

The expected directional shifts vs. baseline:

| Metric | Expected direction |
|--------|-------------------|
| Avg loss per losing trade | ↓ (smaller, ATR-bounded stops; time-stop trims dead trades early) |
| Frequency of losing trades on same symbol | ↓ (Tier 1.1) |
| Drawdown around earnings | ↓ (Tier 2.5) |
| Win rate | ≈ flat or slightly ↑ |
| Avg win per winning trade | ↓ slightly (scale-out at 1R books partial sooner) |
| Profit factor (gross win / gross loss) | ↑ |
| Max session drawdown | ↓ (Tier 3.10 circuit breaker) |
| Trades / day | ↓ (more gates in front of `handleBuy`) |

## 3. Evaluation methodology

### 3.1 Baseline reference

Use the **last 30 calendar days of paper-traded results before this branch was merged** as the baseline. Pull from `trades` table:
```sql
SELECT * FROM trades
WHERE close_time >= datetime('now', '-30 days')
  AND close_time < {merge_timestamp};
```

If the baseline window is too noisy (low N), fall back to a backtest run of the prior `StrategyManager` over the same date range using `BacktestController`.

### 3.2 A/B observation window

Run the bot in paper mode for **at least 4 weeks** (≈20 trading days) post-merge. We need this minimum to:
- Capture at least one earnings cycle for a few watchlist symbols (Tier 2.5 only fires near earnings — without one, the gate is dormant).
- Allow the post-loss cooldown to escalate to 72h on at least one repeating loser, otherwise we can't observe Tier 1.1's escalation arm.
- Hit ≥ 50 closed trades (statistical floor for win-rate confidence intervals).

### 3.3 Headline KPIs

For each window (baseline + post-merge), compute from `trades`:

```
profit_factor       = sum(pnl > 0) / |sum(pnl < 0)|
expectancy          = mean(pnl)                 -- $ per trade
win_rate            = count(pnl > 0) / count(*)
avg_win             = mean(pnl | pnl > 0)
avg_loss            = mean(|pnl| | pnl < 0)
payoff_ratio        = avg_win / avg_loss
sharpe (daily)      = mean(daily_pnl) / stddev(daily_pnl) * sqrt(252)
max_drawdown_pct    = max session drawdown over window
trades_per_day      = count(*) / trading_days
```

The **primary success criterion** is:

> profit_factor(post) > 1.10 × profit_factor(baseline) **AND** max_drawdown_pct(post) ≤ max_drawdown_pct(baseline).

If profit factor improves but drawdown gets worse, the circuit breaker (Tier 3.10) is mis-tuned and should be tightened before promoting to live.

### 3.4 Per-feature attribution

For each new gate, count how often it fired and what it likely saved/cost. The dashboard already exposes most of this; for the rest add a daily DB query:

| Gate | Measurement | Source |
|------|-------------|--------|
| 1.1 post-loss cooldown | `postLossCooldowns[]` snapshot per cycle, # of `handleBuy` blocks tagged "post-loss cooldown" | `bot.log` `grep "post-loss cooldown blocked"` |
| 1.2 ATR stops | distribution of stop-distance % vs. flat % baseline | log "ATR stop=" lines |
| 1.3 ATR sizing | distribution of position $-risk vs. flat-% sizing | log "vol-targeted size=" lines |
| 2.4 correlation cap | # of blocks tagged "correlation cap" | `blockedBuys` map |
| 2.5 earnings blackout | # of blocks tagged "earnings blackout" | `blockedBuys` map |
| 2.6 scale-out 1R | count of `ExitType.SCALE_OUT_1R` rows in `trades` | `SELECT COUNT(*) FROM trades WHERE exit_type='SCALE_OUT_1R'` |
| 2.7 time-stop | count of `ExitType.TIME_STOP` rows | same query |
| 3.8 strict bear routing | count of "WEAK_BEAR strict — no new longs" hold signals | `bot.log` |
| 3.9 no-trade open window | count of "open-window blocked" events | `bot.log` |
| 3.10 circuit breaker | count of cycle-halts + post-trip session P&L | log + `circuitBreakers` snapshot |

The **secondary attribution claim** is per-feature:

> Of the trades that were *blocked* by gate G, the hypothetical P&L (entry at signal time, exit at the natural ExitStrategyManager decision the same cycle would have produced) is net negative. If positive, that gate is hurting expectancy and needs reconsideration.

This is computable offline by replaying the rejected signals through the same bar history (we already capture entry signals + market state in logs).

### 3.5 Regression checks (no-go signals)

Halt promotion to live and revert / debug if any of:
- Profit factor drops below 1.0 in any rolling 10-trading-day window.
- Circuit breaker trips ≥ 2 sessions in 4 weeks (means daily-loss tolerance is too tight or strategy is broken).
- Trades/day drops > 60% — gates may be over-aggressive, starving the strategy.
- Any single gate's "blocked-trade-would-have-won" rate > 60% over ≥ 30 blocked entries (gate cost > benefit).

## 4. Operational steps

### Week 0 — Pre-flight
- [ ] Snapshot baseline metrics from prod DB at merge time, save to `docs/eval/baseline_metrics.json`.
- [ ] Verify Alpha Vantage `EARNINGS_CALENDAR` feed works in paper mode (Tier 2.5 needs it; without the feed, blackout is dormant — ok but note it).
- [ ] Tag the merge commit (`git tag eval-start`).
- [ ] Confirm dashboard renders new "Post-Loss Cooldowns" and "Session Circuit Breaker" cards with no errors.

### Week 1–4 — Observation
- [ ] Daily: pull `trades`, `bot.log`, and the `/api/bot/behavior` snapshot at session close. Persist to `docs/eval/daily/YYYY-MM-DD.json`.
- [ ] Watch for the no-go signals (§3.5). If hit → freeze, investigate, decide.
- [ ] After every week, recompute KPIs and compare to baseline running-totals.

### Week 5 — Decision
- [ ] Compute final §3.3 KPIs. Compare against baseline.
- [ ] Run §3.4 per-feature attribution. Each gate must justify itself.
- [ ] If primary success criterion (§3.3) is met **and** no gate fails its attribution check → promote to live trading at reduced size for an additional 2 weeks before full size.
- [ ] If primary criterion not met → either tune (most likely candidates: circuit-breaker drawdown threshold, scale-out fraction, time-stop bar count) or roll back specific tiers.

## 5. Quick-look queries

A handful of SQL queries to keep nearby. Run from sqlite3 on `bot.db`:

```sql
-- Profit factor per week
SELECT
  strftime('%Y-W%W', close_time) AS week,
  ROUND(SUM(CASE WHEN pnl > 0 THEN pnl ELSE 0 END) /
        NULLIF(ABS(SUM(CASE WHEN pnl < 0 THEN pnl ELSE 0 END)), 0), 2) AS profit_factor,
  COUNT(*) AS trades,
  ROUND(SUM(pnl), 2) AS net_pnl
FROM trades
WHERE close_time IS NOT NULL
GROUP BY week
ORDER BY week;

-- Exits by type, post-merge
SELECT exit_type, COUNT(*), ROUND(AVG(pnl), 2) AS avg_pnl, ROUND(SUM(pnl), 2) AS net
FROM trades
WHERE close_time >= '<merge-date>'
GROUP BY exit_type
ORDER BY net DESC;

-- Were scale-outs net positive?
SELECT
  COUNT(*) AS partial_count,
  ROUND(AVG(pnl), 2) AS avg_partial_pnl
FROM trades
WHERE exit_type = 'SCALE_OUT_1R';

-- Did time-stop trim losers (vs. baseline avg loss)?
SELECT
  COUNT(*) AS time_stop_count,
  ROUND(AVG(pnl), 2) AS avg_time_stop_pnl
FROM trades
WHERE exit_type = 'TIME_STOP';
```

## 6. Tuning playbook

If the post-merge window underperforms, default tuning order (least invasive first):

1. **Loosen the open-window gate** (`noTradeOpenWindowMinutes` 30 → 15) — reclaims trades without affecting risk.
2. **Loosen the time-stop** (`timeStopBars` 5 → 7, or `timeStopMaxMoveR` 0.5 → 0.4) — fewer premature exits.
3. **Soften the post-loss cooldown** (`baseCooldown` 24h → 12h) — keeps Tier 1.1 escalation on repeat losers but lets one-off losses re-enter sooner.
4. **Raise circuit-breaker drawdown threshold** (5% → 7%) — only if it's tripping for normal volatility.
5. **Disable strict regime routing** (`regimeStrictRoutingEnabled = false`) — last resort: it's the single largest entry-gate by volume.

Rolled-back features should be one at a time so attribution stays clean.
