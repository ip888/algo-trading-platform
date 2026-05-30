# June 3, 2026 — PDT Abolition: Pre-Market Prep Checklist

**When:** After market close on June 3 (after 4:00 PM ET)
**Why:** FINRA PDT rule is abolished June 4. Apply all changes the night before so the bot is ready when markets open.
**Automated:** [Scheduled routine](https://claude.ai/code/routines/trig_01XGJTP21jjbPqAANpU783cN) fires at 5:00 PM ET and handles Steps 1–4 automatically. The manual steps below are for reference and verification.

---

## Background

The Pattern Day Trader (PDT) rule required $25,000 minimum equity to make 4+ day trades per week. The bot was designed around this constraint:
- Held positions for 4+ hours to avoid burning day-trade slots
- Blocked new buys once 2/3 PDT slots were used
- Kept stop-losses wide (1.5%) to avoid getting shaken out when re-entry was expensive
- Disabled EOD exits to avoid unnecessary day trades

All of this changes June 4. The night of June 3 is the right time to reconfigure.

---

## Automated (handled by scheduled routine at 5 PM ET)

### Config changes — `trading-backend/config.properties`

| Setting | Old | New | Reason |
|---------|-----|-----|--------|
| `PDT_PROTECTION_ENABLED` | `true` | `false` | Rule abolished |
| `MIN_HOLD_TIME_HOURS` | `4` | `1` | No PDT penalty for same-day exit |
| `EOD_EXIT_ENABLED` | `false` | `true` | Lock intraday profits, re-enter fresh next day |
| `MAIN_STOP_LOSS_PERCENT` | `1.5` | `1.0` | Tighter stop viable when re-entry is free |

The routine will commit, push, and attempt `fly deploy --remote-only`.

---

## Manual steps (do after the routine fires)

### Step 1 — Verify the deploy completed

```bash
# Check the routine result at:
# https://claude.ai/code/routines/trig_01XGJTP21jjbPqAANpU783cN

# If deploy was flagged as ACTION REQUIRED, run:
cd trading-backend
~/.fly/bin/fly deploy --remote-only --app trading-bot-igor-waw
```

### Step 2 — Verify bot is alive after deploy

```bash
curl https://trading-bot-igor-waw.fly.dev/api/status
# Should show: tradingMode=LIVE, bot healthy
```

Check the logs for successful startup:
```bash
~/.fly/bin/fly logs -a trading-bot-igor-waw --no-tail | tail -30
```

### Step 3 — Code cleanup (dead PDT code) [Optional, low risk]

These are now dead code paths in `ProfileManager.java`. Safe to remove but not urgent:

- `pdtProtection` field and all usages (~15 lines in `handleBuy`)
- `PDTRejectedException` catch blocks in `checkAllPositionsForRiskExits`
- `pdtBlockedUntil` circuit and `staticPdtBlockedUntil` static field
- `staticDayTradeCount` field (used only for dashboard)
- The PDT sync log line in the trading cycle

If doing this: run `mvn test` after, then redeploy. Don't do it late at night before market open — schedule for a safer time.

### Step 4 — Monitor June 4 morning open closely

Watch for:
- ✅ Bot entering positions normally with no PDT blocks in logs
- ✅ Stop-losses firing at ~1% (tighter than before)
- ✅ EOD exit at 3:30 PM on profitable positions
- ✅ Same-day re-entries after stop-losses (new behaviour)
- ⚠️ Any unexpected account restrictions from Alpaca during their June 4 transition

Check Alpaca's announcement: https://alpaca.markets/blog/finra-retires-the-pdt-rule-introducing-alpacas-new-intraday-margin-framework/

---

## Future optimisations (not for June 3)

These need more thought and testing — do them after a week of observing the new behaviour:

1. **Scalping strategy** — With free same-day re-entry, add a short-hold (15–30 min) momentum scalp mode for strong intraday moves
2. **Max positions increase** — SMALL tier is capped at 3; could revisit once account grows
3. **EOD re-entry logic** — If EOD exit fires at 3:30 PM with a profit, consider re-entering the same position at open next day (momentum continuation)
4. **Remove dead PDT code** from `ProfileManager.java` (see Step 3 above)

---

## Rollback plan

If something goes wrong after June 4 deploy, revert in `config.properties`:

```properties
PDT_PROTECTION_ENABLED=true
MIN_HOLD_TIME_HOURS=4
EOD_EXIT_ENABLED=false
MAIN_STOP_LOSS_PERCENT=1.5
```

Then redeploy. The old behaviour is fully restored in one deploy.
