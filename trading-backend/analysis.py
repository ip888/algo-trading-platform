#!/usr/bin/env python3
"""Trade Analysis - Why bot is losing money"""

# From actual Kraken trade history
trades = {
    "SOL/USD": [
        # Batch 1: bought at 141.84, sold at 141.79 → small loss
        {"buy": 202.26, "sell": 201.82, "fee": 1.31},  # -$1.75
        # Batch 2-3: multiple small trades, mostly flat
        {"buy": 150.0, "sell": 150.03, "fee": 0.90},  # -$0.87 (fees eat profit)
        {"buy": 75.0, "sell": 149.52, "fee": 0.90},  # -$0.38 (incomplete)
        # Batch 4: winner
        {"buy": 75.0, "sell": 75.28, "fee": 0.60},  # +$0.28 (profit!)
    ],
    "DOGE/USD": [
        # Batch 1: bought at 0.137, sold at 0.136 → loss
        {"buy": 121.48, "sell": 120.67, "fee": 0.97},  # -$1.78
        # Batch 2: DISASTER - bought at 0.136, sold at 0.126 → -7.3%
        {"buy": 272.50, "sell": 252.68, "fee": 1.30},  # -$21.12 (major loss)
    ],
    "ETH/USD": [
        # Batch 1: bought at 3185, sold at 3205 → WIN
        {"buy": 299.88, "sell": 301.72, "fee": 1.11},  # +$0.73
        # Batch 2: quick scalp, nearly flat
        {"buy": 75.0, "sell": 75.01, "fee": 0.60},  # -$0.59
        # Batch 3: bought at 3209, sold at 3188 → LOSS
        {"buy": 75.0, "sell": 74.52, "fee": 0.60},  # -$1.08
    ],
    "BTC/USD": [
        # Batch 1: bought at 94977, sold at 92548 → BIG LOSS (-2.6%)
        {"buy": 75.0, "sell": 73.08, "fee": 0.59},  # -$2.51
        # Batch 2: flat
        {"buy": 75.0, "sell": 75.0, "fee": 0.60},  # -$0.60
        # Batch 3: WIN
        {"buy": 75.0, "sell": 75.35, "fee": 0.60},  # +$0.75
    ],
    "XRP/USD": [
        # Batch 1: small loss
        {"buy": 50.01, "sell": 49.75, "fee": 0.40},  # -$0.66
        # Batch 2: BIG LOSS (-4.9%)
        {"buy": 37.70, "sell": 35.87, "fee": 0.24},  # -$2.07
    ],
}

print("=" * 60)
print("📊 TRADE ANALYSIS REPORT")
print("=" * 60)

total_pnl = 0
for pair, pair_trades in trades.items():
    pair_pnl = 0
    for t in pair_trades:
        pnl = t.get("sell", 0) - t.get("buy", 0) - t.get("fee", 0)
        pair_pnl += pnl

    emoji = "✅" if pair_pnl > 0 else "🔴"
    print(f"\n{emoji} {pair}: ${pair_pnl:+.2f}")
    total_pnl += pair_pnl

print("\n" + "=" * 60)
print(f"💰 TOTAL P&L: ${total_pnl:.2f}")
print("=" * 60)

print("\n🔴 ROOT CAUSES OF LOSSES:")
print("""
1. ASYMMETRIC RISK/REWARD (Major Issue)
   - Stop Loss: 0.5% 
   - Take Profit: 0.75%
   - BUT: Fees are ~0.4% per trade (0.2% each way)
   - Net TP after fees: only 0.35%
   - Net SL after fees: -0.9% 
   - Risk/Reward = 0.35:0.9 = 1:2.6 (TERRIBLE!)
   
2. STOP LOSS NOT WORKING (Critical Bug!)
   - Logs show SL values like "$33.75" for SOL at $134
   - That's -75% stop loss, not 0.5%!
   - The SL calculation is using WRONG entry price
   
3. ENTRY TIMING (Strategy Issue)
   - Bot buys on "dip" signals in downtrending market
   - DOGE: Bought at $0.137, market crashed to $0.126
   - BTC: Bought at $94,977, market dropped to $92,548
   - No trend filter - buying falling knives

4. RSI OVERBOUGHT EXIT TOO AGGRESSIVE
   - Sells at +0.3% profit when RSI > 70
   - After fees, this is barely breakeven
   - Winners cut short, losers run long

5. POSITION REENTRY CHURN
   - Bot sells, then immediately rebuys
   - Creates unnecessary fee drag
   - See SOL trades: buy-sell-buy-sell-buy within minutes

6. NO MACRO TREND FILTER
   - Bot ignores market direction
   - Weekend/low-volume periods = choppy losses
   - No check if 24h trend is down

7. GRID TRADING CONFLICTS
   - Multiple order systems fight each other
   - GridTradingService + KrakenTradingLoop both placing orders
""")

print("\n✅ RECOMMENDED FIXES:")
print("""
1. IMMEDIATE: Fix SL calculation bug
2. IMMEDIATE: Widen risk/reward (TP=1.5%, SL=0.5% after fees)
3. HIGH: Add trend filter (skip buys when 24h change < -2%)
4. HIGH: Add cooldown after sells (15min before rebuy)
5. MEDIUM: Disable RSI overbought early exit or raise threshold
6. MEDIUM: Reduce trading frequency to reduce fee impact
7. LOW: Consider reducing to 1-2 assets only
""")
