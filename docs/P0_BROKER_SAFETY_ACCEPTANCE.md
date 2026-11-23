# P0 Broker Order Safety — Acceptance and Operations Runbook

## Status / safety boundary
This branch is a safety-focused change set. **Green unit tests are not authorization
to enable live trading.** Do not merge/deploy until paper-broker acceptance below
has been performed and approved. Never run CI with real broker credentials.

## Pre-deployment requirements
1. Stop automated entries and inspect all open Alpaca positions/orders.
2. Verify only one trading process/account is active, and the Fly persistent
   volume mounted at `/data` is writable. `DATA_DIR=/data` must point to the
   existing persistent SQLite database, not ephemeral container storage.
3. Make a backup of `/data/trades.db` before schema migration. Do not reset the
   database just to clear an unresolved order.
4. Review GitHub secret-scanning alerts. A tracked Alpha Vantage key was removed
   from current source; **revoke/rotate any genuine previously exposed key** with
   the provider and provision the replacement as an environment/Fly secret.
5. Verify dashboard mutation endpoints are authenticated with an out-of-band
   secret and authorization is fail-closed.
6. Confirm live fractional entry remains disabled until fill-driven native stop
   handling has independent paper validation. Alerts must never be mistaken for
   actual protective orders.

## Test matrix — local simulated broker, then Alpaca paper
- Whole-share bracket BUY accepted with server-provided order ID; no local trade
  until Alpaca confirms `status=filled`, `filled_qty`, and average fill price.
- Same BUY accepted by Alpaca but HTTP response dropped; application restarts;
  reconcile by durable `client_order_id`. Assert **one** broker BUY.
- BUY rejected, expired or canceled with `filled_qty=0`: verify empty positions
  and orders before releasing the durable intent.
- BUY partially filled, pending limit, or cancel/fill race: no replacement BUY
  until broker state is resolved; entry state must reflect actual filled qty.
- Whole-share bracket protection: active SELL stop for at least broker-held shares.
  A HELD/ACCEPTED, canceled, BUY-side, wrong-symbol or insufficient-qty stop
  **must not count** as working protection.
- Fractional candidate: no live entry order submitted.
- SELL request times out after Alpaca accepted it; subsequent cycle and restart
  must not submit a second SELL.
- SELL partial fills: don't release lock or mark full position closed. Confirm
  terminal status and broker-flat account before releasing SELL intent.
- Emergency panic: cancel/confirm open orders and route all sell attempts through
  tracked execution gateway. A failed/unknown result must be reported honestly;
  no fabricated fill or P&L.
- Broker GET 401/429/500/timeout/malformed JSON: no additional BUY risk,
  no false zero-position or zero-risk snapshots.
- Restart with open positions and the SQLite intent journal intact: no duplicate
  orders, and positions are reconstructed only from broker-confirmed state.
- Disable Alpaca network mid-test, then restore. Observe all warnings and
  reconciliation state without automatic entry retries.
- Validate dashboard display does not mark configured local stop prices as
  broker-confirmed stop orders.

## CI (no broker credentials)
GitHub workflow `.github/workflows/ci.yml` runs Java 25 Maven tests,
frontend lint, Vitest and build. Check **both jobs pass on the exact PR head**.
Review test-report artifact and audit failures before promoting.

## Reconciliation / manual intervention
- Check `broker_entry_intents` and `broker_exit_intents` in the durable DB,
  then query Alpaca order history and exact `client_order_id`.
- An unresolved durable intent is a protective lock, **not an error to erase**.
  Do not delete or overwrite it to make the bot trade.
- For 404/timeout/ambiguous order state, leave new BUYs blocked. Verify
  broker order book and fills manually; escalate if still inconsistent.
- Keep records of paper tests, broker order IDs, quantities, timestamps, and
  observed filled/partial/canceled states (do not include API secrets).
- A stop loss limits *intended* downside but cannot guarantee execution price
  in a gap, outage, halt, or thin market.

## Release gate
Require: green CI on final commit; code review; successful paper-broker test
matrix; reconciled account/DB; valid persistent storage; authenticated mutation
endpoints; documented rollback and operator approval. **No automatic live deploy.**
