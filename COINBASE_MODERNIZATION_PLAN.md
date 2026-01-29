# Coinbase Trading Bot Modernization Plan

## Executive Summary

The Coinbase worker (Rust/Cloudflare) has been **DISABLED** pending implementation of adaptive trading mechanisms. The goal is to create a **self-tuning system** that works profitably at ANY capital level by dynamically adjusting all parameters based on current conditions.

**Current Status**: Trading disabled, 0 positions, ~$109 portfolio value, -$3.15 realized P&L after 44 trades.

---

## 1. Core Problem: Static Parameters Don't Work

The current implementation uses **hardcoded values** that don't adapt to:
- Capital size (fees have different impact at $100 vs $10,000)
- Market volatility (2% TP may be too tight in high volatility)
- Fee tiers (Coinbase fees decrease with volume)
- Win rate feedback (no learning from past performance)

**Solution**: Build a fully adaptive system where ALL parameters are calculated dynamically.

---

## 2. Adaptive Architecture

### 2.1 Fee-Aware Position Sizing

Instead of hardcoding minimum position size, calculate it dynamically:

```rust
/// Calculate minimum profitable position based on current fee tier
fn calculate_min_position(fee_percent: f64, target_net_profit_percent: f64) -> f64 {
    // Round trip fees
    let round_trip_fee = fee_percent * 2.0;

    // Minimum move needed to break even after fees
    let min_move = round_trip_fee + target_net_profit_percent;

    // For position to be worth it, expected profit must exceed $1
    // position_size * min_move = $1
    // position_size = $1 / min_move
    let min_position = 1.0 / (min_move / 100.0);

    min_position.max(5.0) // Absolute minimum $5
}
```

**Fee Tiers on Coinbase Advanced:**
| 30-Day Volume | Taker Fee | Maker Fee |
|--------------|-----------|-----------|
| $0 - $1K | 0.60% | 0.40% |
| $1K - $10K | 0.40% | 0.25% |
| $10K - $50K | 0.25% | 0.15% |
| $50K+ | 0.20% | 0.10% |

The system should track cumulative volume and adjust fee assumptions accordingly.

### 2.2 Dynamic Take-Profit Calculation

TP should be based on:
1. **ATR (Average True Range)**: Volatility-adaptive
2. **Fee Recovery**: Must exceed 2× round-trip fees
3. **Historical Win Rate**: If win rate is high, can use tighter TP

```rust
/// Calculate dynamic take-profit percentage
fn calculate_dynamic_tp(
    atr_percent: f64,           // Current ATR as % of price
    fee_percent: f64,           // Current fee tier
    historical_win_rate: f64,   // Past performance (0.0 - 1.0)
) -> f64 {
    // Base TP from ATR (typically 1.5-2x ATR)
    let atr_based_tp = atr_percent * 1.5;

    // Minimum TP to recover fees and make profit
    let fee_recovery_tp = fee_percent * 2.0 * 1.5; // 1.5x to ensure profit

    // Adjust for win rate (higher win rate = can use tighter TP)
    let win_rate_multiplier = if historical_win_rate > 0.6 {
        0.9 // Tighter TP if winning consistently
    } else if historical_win_rate < 0.4 {
        1.2 // Wider TP if struggling
    } else {
        1.0
    };

    // Take the larger of ATR-based or fee-recovery, then adjust
    atr_based_tp.max(fee_recovery_tp) * win_rate_multiplier
}
```

### 2.3 Adaptive Stop-Loss

SL should be based on:
1. **ATR**: Wide enough to avoid noise
2. **Risk Budget**: Max % of portfolio willing to lose
3. **Position Size**: Larger positions = tighter SL (to limit $ risk)

```rust
/// Calculate dynamic stop-loss percentage
fn calculate_dynamic_sl(
    atr_percent: f64,
    portfolio_value: f64,
    position_value: f64,
    max_portfolio_risk_percent: f64,  // e.g., 1% of total portfolio
) -> f64 {
    // Risk budget in dollars
    let risk_budget = portfolio_value * (max_portfolio_risk_percent / 100.0);

    // SL percent that keeps loss within budget
    let budget_based_sl = (risk_budget / position_value) * 100.0;

    // ATR-based SL (typically 1-1.5x ATR)
    let atr_based_sl = atr_percent * 1.2;

    // Use the smaller of budget-based or ATR-based
    // But ensure minimum 0.5% to avoid noise triggers
    budget_based_sl.min(atr_based_sl).max(0.5)
}
```

### 2.4 Capital-Tier Adaptive Behavior

Different behaviors at different capital levels:

```rust
enum CapitalTier {
    Micro,      // < $100: Paper trade only, learn the system
    Tiny,       // $100 - $500: 1 position max, very conservative
    Small,      // $500 - $2K: 2 positions, conservative
    Medium,     // $2K - $5K: 3 positions, balanced
    Standard,   // $5K - $25K: 4 positions, normal parameters
    Large,      // $25K+: 5+ positions, full diversification
}

impl CapitalTier {
    fn from_portfolio(value: f64) -> Self {
        match value {
            v if v < 100.0 => CapitalTier::Micro,
            v if v < 500.0 => CapitalTier::Tiny,
            v if v < 2000.0 => CapitalTier::Small,
            v if v < 5000.0 => CapitalTier::Medium,
            v if v < 25000.0 => CapitalTier::Standard,
            _ => CapitalTier::Large,
        }
    }

    fn max_positions(&self) -> usize {
        match self {
            CapitalTier::Micro => 0,  // Paper trade only
            CapitalTier::Tiny => 1,
            CapitalTier::Small => 2,
            CapitalTier::Medium => 3,
            CapitalTier::Standard => 4,
            CapitalTier::Large => 5,
        }
    }

    fn max_position_percent(&self) -> f64 {
        match self {
            CapitalTier::Micro => 0.0,
            CapitalTier::Tiny => 80.0,   // Single concentrated bet
            CapitalTier::Small => 50.0,
            CapitalTier::Medium => 35.0,
            CapitalTier::Standard => 25.0,
            CapitalTier::Large => 20.0,
        }
    }

    fn risk_per_trade_percent(&self) -> f64 {
        match self {
            CapitalTier::Micro => 0.0,
            CapitalTier::Tiny => 0.5,    // Ultra conservative
            CapitalTier::Small => 1.0,
            CapitalTier::Medium => 1.5,
            CapitalTier::Standard => 2.0,
            CapitalTier::Large => 2.0,
        }
    }
}
```

---

## 3. Self-Tuning Strategy Engine

### 3.1 Entry Signal Scoring (Replace Static Thresholds)

Instead of "buy if range_position < 25%", use a multi-factor score:

```rust
struct EntrySignal {
    score: f64,          // 0.0 - 100.0
    factors: Vec<Factor>,
    confidence: f64,
}

struct Factor {
    name: String,
    weight: f64,
    value: f64,
    contribution: f64,
}

fn calculate_entry_score(
    price: f64,
    high_24h: f64,
    low_24h: f64,
    rsi_14: f64,
    volume_ratio: f64,   // current_volume / avg_volume
    btc_correlation: f64,
    trend_strength: f64, // -1.0 to 1.0
) -> EntrySignal {
    let mut factors = Vec::new();

    // Factor 1: Range Position (mean reversion)
    let range = high_24h - low_24h;
    let range_position = (price - low_24h) / range;
    let range_score = (1.0 - range_position) * 100.0; // Lower = better for buying
    factors.push(Factor {
        name: "Range Position".into(),
        weight: 0.25,
        value: range_position,
        contribution: range_score * 0.25,
    });

    // Factor 2: RSI (oversold = good for buying)
    let rsi_score = if rsi_14 < 30.0 {
        100.0 // Oversold
    } else if rsi_14 < 40.0 {
        70.0
    } else if rsi_14 < 50.0 {
        40.0
    } else {
        0.0 // Not oversold
    };
    factors.push(Factor {
        name: "RSI".into(),
        weight: 0.25,
        value: rsi_14,
        contribution: rsi_score * 0.25,
    });

    // Factor 3: Volume Confirmation
    let volume_score = if volume_ratio > 1.5 {
        100.0 // High volume = conviction
    } else if volume_ratio > 1.0 {
        50.0
    } else {
        20.0 // Low volume = skeptical
    };
    factors.push(Factor {
        name: "Volume".into(),
        weight: 0.20,
        value: volume_ratio,
        contribution: volume_score * 0.20,
    });

    // Factor 4: Trend Alignment (don't fight strong trends)
    let trend_score = if trend_strength < -0.3 {
        0.0 // Strong downtrend = avoid
    } else if trend_strength < 0.0 {
        50.0 // Mild downtrend = cautious
    } else {
        80.0 // Uptrend = good
    };
    factors.push(Factor {
        name: "Trend".into(),
        weight: 0.15,
        value: trend_strength,
        contribution: trend_score * 0.15,
    });

    // Factor 5: Market Regime (BTC health)
    let regime_score = if btc_correlation > 0.8 && trend_strength < -0.2 {
        0.0 // High correlation + BTC dumping = danger
    } else {
        70.0
    };
    factors.push(Factor {
        name: "Regime".into(),
        weight: 0.15,
        value: btc_correlation,
        contribution: regime_score * 0.15,
    });

    let total_score: f64 = factors.iter().map(|f| f.contribution).sum();
    let confidence = total_score / 100.0;

    EntrySignal {
        score: total_score,
        factors,
        confidence,
    }
}
```

### 3.2 Adaptive Entry Threshold

The minimum score to enter should adapt based on recent performance:

```rust
struct AdaptiveThreshold {
    base_threshold: f64,       // e.g., 60.0
    recent_win_rate: f64,      // Last 20 trades
    consecutive_losses: u32,
    consecutive_wins: u32,
}

impl AdaptiveThreshold {
    fn current_threshold(&self) -> f64 {
        let mut threshold = self.base_threshold;

        // Tighten threshold after losses (be more selective)
        threshold += (self.consecutive_losses as f64) * 5.0;

        // Loosen after wins (confidence is high)
        threshold -= (self.consecutive_wins as f64) * 2.0;

        // Adjust based on overall win rate
        if self.recent_win_rate < 0.4 {
            threshold += 10.0; // More selective
        } else if self.recent_win_rate > 0.6 {
            threshold -= 5.0; // Can be less selective
        }

        // Clamp to reasonable range
        threshold.clamp(40.0, 85.0)
    }
}
```

---

## 4. Real-Time Protection Improvements

### 4.1 WebSocket Price Monitoring

Replace 1-minute cron with Cloudflare Durable Objects for real-time:

```rust
// Durable Object for real-time price monitoring
pub struct PriceMonitor {
    positions: HashMap<String, Position>,
    websocket: Option<WebSocket>,
}

impl PriceMonitor {
    async fn on_price_update(&mut self, symbol: &str, price: f64) {
        if let Some(position) = self.positions.get(symbol) {
            // Immediate SL/TP check
            if price <= position.stop_loss_price {
                self.execute_stop_loss(symbol, position).await;
            } else if price >= position.take_profit_price {
                self.execute_take_profit(symbol, position).await;
            }

            // Update trailing stop
            if price > position.high_water_mark {
                self.update_trailing_stop(symbol, price);
            }
        }
    }
}
```

### 4.2 Limit Order TP (Until WebSocket is Ready)

Place limit sell orders as TP immediately after entry:

```rust
async fn enter_position(&self, symbol: &str, quantity: f64, entry_price: f64) {
    // 1. Execute market buy
    self.client.market_buy(symbol, quantity).await?;

    // 2. Immediately place limit sell at TP price
    let tp_price = entry_price * (1.0 + self.dynamic_tp_percent / 100.0);
    self.client.limit_order(symbol, OrderSide::Sell, quantity, tp_price).await?;

    // 3. Store position with pending TP order ID
    self.positions.insert(symbol, Position {
        quantity,
        entry_price,
        tp_order_id: Some(tp_order.order_id),
        ..
    });
}
```

---

## 5. Performance Tracking & Self-Correction

### 5.1 Trade Journal

Store detailed trade data for learning:

```rust
struct TradeRecord {
    symbol: String,
    entry_time: DateTime<Utc>,
    exit_time: Option<DateTime<Utc>>,
    entry_price: f64,
    exit_price: Option<f64>,
    quantity: f64,
    pnl: Option<f64>,
    pnl_percent: Option<f64>,

    // Context at entry
    entry_score: f64,
    entry_factors: Vec<Factor>,
    atr_at_entry: f64,
    btc_change_at_entry: f64,

    // Exit reason
    exit_reason: Option<ExitReason>,
}
```

### 5.2 Strategy Performance Metrics

Calculate and expose metrics:

```rust
struct StrategyMetrics {
    total_trades: u32,
    winning_trades: u32,
    losing_trades: u32,
    win_rate: f64,

    total_pnl: f64,
    average_win: f64,
    average_loss: f64,
    profit_factor: f64,  // gross_profit / gross_loss

    max_drawdown: f64,
    sharpe_ratio: f64,

    // By factor analysis
    best_performing_factor: String,
    worst_performing_factor: String,
}
```

### 5.3 Self-Correction Rules

```rust
fn evaluate_and_adjust(&mut self) {
    let metrics = self.calculate_metrics();

    // Rule 1: Pause if drawdown exceeds threshold
    if metrics.max_drawdown > 15.0 {
        self.pause_trading("Drawdown exceeded 15%");
        return;
    }

    // Rule 2: Increase selectivity if win rate drops
    if metrics.win_rate < 0.45 && metrics.total_trades > 20 {
        self.entry_threshold += 5.0;
        self.log("Increased entry threshold due to low win rate");
    }

    // Rule 3: Reduce position size if losing streak
    if self.consecutive_losses >= 3 {
        self.position_size_multiplier *= 0.75;
        self.log("Reduced position size after 3 consecutive losses");
    }

    // Rule 4: Resume normal after winning streak
    if self.consecutive_wins >= 3 {
        self.position_size_multiplier = 1.0;
        self.entry_threshold = self.base_entry_threshold;
    }
}
```

---

## 6. Implementation Roadmap

### Phase 1: Foundation (Week 1-2)
- [ ] Implement `CapitalTier` enum with adaptive parameters
- [ ] Add `calculate_dynamic_tp()` and `calculate_dynamic_sl()`
- [ ] Add fee tier tracking
- [ ] Store trade history in KV

### Phase 2: Entry Improvements (Week 3-4)
- [ ] Implement multi-factor `EntrySignal` scoring
- [ ] Add RSI calculation (from candle data)
- [ ] Add volume ratio calculation
- [ ] Implement `AdaptiveThreshold`

### Phase 3: Exit Improvements (Week 5-6)
- [ ] Implement limit order TP placement
- [ ] Add ATR-based trailing stop
- [ ] Track and cancel stale TP orders

### Phase 4: Self-Tuning (Week 7-8)
- [ ] Implement `StrategyMetrics` calculation
- [ ] Add self-correction rules
- [ ] Add performance dashboard endpoint

### Phase 5: Real-Time (Optional, Week 9+)
- [ ] Migrate to Durable Objects
- [ ] Implement WebSocket price monitoring
- [ ] Sub-second SL/TP execution

---

## 7. Success Criteria

Before re-enabling live trading:

1. **Backtested on 90 days of historical data**
   - Win rate > 50%
   - Profit factor > 1.3
   - Max drawdown < 15%

2. **Paper traded for 2 weeks**
   - Simulated trades match backtest expectations
   - No bugs in execution logic

3. **Adaptive parameters proven**
   - System correctly adjusts to different capital levels
   - Fee calculations accurate
   - TP/SL calculations responsive to volatility

---

## 8. Key Principle

**The bot should NEVER require manual parameter adjustment.**

If capital changes → parameters auto-adjust.
If volatility changes → TP/SL auto-adjust.
If fees change → position sizing auto-adjusts.
If win rate drops → strategy becomes more selective automatically.

This is the path to a truly autonomous trading system.

---

*Last updated: 2026-01-28*
*Status: Planning - Trading Disabled*
