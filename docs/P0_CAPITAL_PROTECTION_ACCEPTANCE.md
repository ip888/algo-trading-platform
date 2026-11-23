# P0 Capital Protection — Verification and Release Gate

**Branch:** `fix/p0-broker-order-safety-20261123`  
**PR:** https://github.com/ip888/algo-trading-platform/pull/4  
**Status:** Implemented in draft branch; **NOT cleared for live capital**.

## Implemented controls

1. Durable SQLite entry and exit intent records are created before network submission; a separate `client_order_id` is sent with tracked Alpaca orders.
2. Ambiguous POST failures are not automatically retried. A restart retains the unresolved symbol/intent lock and reconciles by exact client order ID.
3. Failed bracket entry never degrades into an unprotected ordinary market BUY.
4. Entry acceptance is not treated as a fill. Local trade recording requires broker `filled` status, quantity and positive average fill price. Incomplete fills are recovered from broker positions, not fabricated from an acknowledgement.
5. SELL attempts are serialized by durable intent. Pending and partial fills prevent a second SELL; a filled SELL lock persists until broker-confirmed flat reconciliation.
6. Cancel-before-replace checks each prior order's terminal canceled/expired/rejected status **and zero filled quantity**, not just disappearance from the open-order list.
7. Protection checks inspect **active SELL stop quantity** against broker-held shares. Missing/invalid broker responses fail closed for new entries.
8. Fractional LIVE buys are blocked until a separate fill-driven, broker-protected fractional execution lifecycle is implemented and verified.
9. Reconciliation refuses to resurrect stale DB-only positions absent at Alpaca **or fabricate a CANCELLED/$0 result** when the corresponding broker fill is not established. Broker risk API and portfolio-correlation calculation failures do not masquerade as safe/zero exposure.
10. Manual dashboard close and emergency/panic exits use tracked broker execution and cancellation verification. Panic snapshots **all** open orders, including unfilled BUYs, before global cancellation. Unconfirmed results trigger operator alerts.
11. CI uses **dummy** credentials and an invalid localhost broker endpoint; no live order, Fly deployment or live credential access occurs.

## CI evidence

See GitHub Actions on this PR. Required checks:

- `cd trading-backend && mvn -B -ntp clean verify`
- `cd trading-backend/dashboard && npm ci && npm run lint && npm run test:run && npm run build`

JUnit and mock-broker tests cover: network timeout after POST, duplicate suppression after restart, terminal no-fill cancellation, pending cancel/fill races, partial fills, durable SQLite state, risk API outages, bracket payload/client_order_id and position protection quantities.

## Release acceptance criteria (external broker validation required)

**Do not merge/deploy for live trading until ALL of these pass on an isolated Alpaca paper account** and the exact release SHA has full green CI:

- [ ] Paper-only account credentials and paper endpoint verified, with no possibility of hitting the live API.
- [ ] Order accepted but HTTP response lost: exactly one BUY exists after retry/restart.
- [ ] BUY partial-fill/held bracket: no second BUY, all filled exposure tracked; stop protection status and effective shares checked.
- [ ] Stop fills while cancellation pending: no additional market SELL, no short position.
- [ ] SELL network timeout after broker accepted request: one SELL, no repeated POST after restart.
- [ ] Partial SELL: only filled quantity booked, unsold remainder still tracked/protected.
- [ ] Broker API outage/5xx/malformed JSON: no new exposure; operator alert delivered.
- [ ] Panic with a pending BUY and with a concurrently filled stop: broker state reconciled, no blind liquidation.
- [ ] Full real broker fill average price and quantity reconcile with SQLite P&L and inventory.
- [ ] Restart from persistent Fly volume with outstanding orders; no duplicates and no orphaned/unprotected exposure.
- [ ] Test shutdown during market hours; outstanding positions remain broker-side protected.
- [ ] Historical GitGuardian findings investigated and any truly exposed credentials rotated.
- [ ] Review deploy configuration and verify one active trading process per brokerage account.

## Known constraints / risk disclosures

- A stop-market order does not guarantee execution at the stop price; gaps and liquidity still create slippage.
- Alpaca bracket exits become active only once the parent entry is completely filled. Partial parent fills need special scrutiny; no claim of continuous hard-stop protection is made here.
- A broker API outage can prevent reliable flattening; the fail-closed design prioritizes no duplicate orders and surfaces unresolved cases for operator intervention.
- The current CI checks mock responses. **Green CI is not proof of broker-paper or live execution safety.**
- Legacy CLI/testing utilities and broker-specific alternate routing paths must not be used as an independent production entry point without passing the durable execution gateway.
- The `main` branch is unchanged. Keep PR as Draft until the external release checklist is complete.
