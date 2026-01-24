# Coinbase Trading Worker

A high-performance crypto trading bot running on Cloudflare Workers for 24/7 automated trading on Coinbase Advanced Trade API.

## Features

- **24/7 Automated Trading**: Runs on Cloudflare's edge network with 99.99% uptime
- **Mean Reversion Strategy**: Buy the dip with trend and volume filters
- **Volatility-Adaptive TP/SL**: ATR-based dynamic stop-loss and take-profit
- **Risk-Based Position Sizing**: Professional 2% risk per trade, 25% max per position
- **Trailing Stop**: Locks in profits as price moves up
- **Time-Based Exits**: Auto-close stale positions after 12h
- **Web Dashboard**: Real-time portfolio view at `/dashboard`
- **Ultra-Low Cost**: ~$5/month on Cloudflare Workers paid plan

## Architecture

```text
┌─────────────────────────────────────────────────────────────────┐
│                   Cloudflare Edge Network                       │
├─────────────────────────────────────────────────────────────────┤
│  ┌─────────────────┐    ┌────────────────────────────────────┐  │
│  │  Cron Trigger   │───▶│         Worker (WASM/Rust)         │  │
│  │  (every minute) │    │  - Trading Engine                  │  │
│  └─────────────────┘    │  - Strategy Logic (Mean Reversion) │  │
│                         │  - Risk Management                 │  │
│  ┌─────────────────┐    │  - Coinbase API Client             │  │
│  │  HTTP Endpoints │───▶└────────────────────────────────────┘  │
│  │  /              │                    │                       │
│  │  /health        │                    ▼                       │
│  │  /api/*         │    ┌────────────────────────────────────┐  │
│  └─────────────────┘    │            KV Storage              │  │
│                         │  - Trading State (positions, P&L)  │  │
│                         └────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────┘
                               │
                               ▼
                    ┌──────────────────────┐
                    │   Coinbase Advanced  │
                    │       Trade API      │
                    │   - Market Orders    │
                    │   - Account Balance  │
                    │   - Price/Stats Data │
                    └──────────────────────┘
```

## Trading Strategy

### Entry Criteria (Mean Reversion / "Buy the Dip")

1. **Price Position**: In lower 35% of 24h range
2. **Day Change**: Between -2% and +3% (avoid falling knives and FOMO)
3. **Trend Filter**: Price above 6-hour average (uptrend only)
4. **Volume Filter**: 24h volume > $1M USD (liquidity check)

### Exit Criteria (Volatility-Adaptive)

1. **Stop-Loss**: 1× ATR below entry (typically 0.5%-5%)
2. **Take-Profit**: 2× ATR above entry (2:1 risk-reward ratio)
3. **Trailing Stop**: 0.75% below high water mark (lock in profits)
4. **Time Exit**: Auto-close after 12 hours if no TP/SL hit

### Position Sizing (Risk-Based)

```
Position Size = min(
    (Portfolio × 2% Risk) / Stop-Loss%,   // Risk-based
    Portfolio × 25%,                       // Max per position
    Available - 15% Reserve                // Cash reserve
)
```

## Setup

### Prerequisites

- Rust toolchain: `curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh`
- WASM target: `rustup target add wasm32-unknown-unknown`
- Wrangler CLI: `npm install -g wrangler`
- Coinbase Advanced Trade API credentials (generate at coinbase.com/settings/api)

### 1. Clone and Configure

```bash
cd coinbase-worker

# Login to Cloudflare
wrangler login

# Create KV namespace for state storage
wrangler kv:namespace create STATE
# Update wrangler.toml with the returned namespace ID
```

### 2. Set Secrets

Secrets are stored securely in Cloudflare (never in code):

```bash
# Coinbase API Key Name (format: organizations/.../apiKeys/...)
wrangler secret put COINBASE_API_KEY_NAME

# Coinbase Private Key (EC private key in PEM format)
# Paste the full key including BEGIN/END lines
wrangler secret put COINBASE_PRIVATE_KEY
```

### 3. Deploy

```bash
# Build and deploy to production
wrangler deploy

# Or run locally for development
wrangler dev
```

## API Endpoints

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/` | GET | Dashboard UI (web interface) |
| `/health` | GET | Health check with version info |
| `/api/portfolio` | GET | Portfolio with live P&L |
| `/api/positions` | GET | Raw positions data |
| `/api/status` | GET | Trading status and stats |
| `/api/scan` | GET | Market scan results |
| `/api/debug` | GET | Debug info (risk sizing, etc.) |
| `/api/trigger` | POST | Manually trigger a trading cycle |
| `/api/toggle` | POST | Enable/disable trading |

### Examples

```bash
# Health check
curl https://coinbase-worker.YOUR-SUBDOMAIN.workers.dev/health

# Get portfolio with live P&L
curl https://coinbase-worker.YOUR-SUBDOMAIN.workers.dev/api/portfolio

# Market scan
curl https://coinbase-worker.YOUR-SUBDOMAIN.workers.dev/api/scan

# Manual trigger (force a trading cycle)
curl -X POST https://coinbase-worker.YOUR-SUBDOMAIN.workers.dev/api/trigger

# Disable trading
curl -X POST -H "Content-Type: application/json" \
  -d '{"enabled": false}' \
  https://coinbase-worker.YOUR-SUBDOMAIN.workers.dev/api/toggle
```

## Configuration

All configuration via environment variables in `wrangler.toml`:

### Exit Parameters (ATR-Based)

| Variable | Default | Description |
|----------|---------|-------------|
| `ATR_SL_MULTIPLIER` | 1.0 | Stop-loss at N× ATR |
| `ATR_TP_MULTIPLIER` | 2.0 | Take-profit at N× ATR (2:1 R:R) |
| `MIN_SL_PERCENT` | 0.5 | Minimum stop-loss % |
| `MAX_SL_PERCENT` | 5.0 | Maximum stop-loss % |
| `MIN_TP_PERCENT` | 1.0 | Minimum take-profit % |
| `MAX_TP_PERCENT` | 10.0 | Maximum take-profit % |
| `TRAILING_STOP_PERCENT` | 0.75 | Trailing stop distance |

### Position Sizing (Risk-Based)

| Variable | Default | Description |
|----------|---------|-------------|
| `MAX_RISK_PER_TRADE_PERCENT` | 2.0 | Risk % of portfolio per trade |
| `MAX_PORTFOLIO_PER_POSITION` | 25.0 | Max % in single position |
| `MIN_POSITION_USD` | 10 | Minimum position (Coinbase limit) |
| `CASH_RESERVE_PERCENT` | 15.0 | Keep % cash for opportunities |
| `MAX_TOTAL_POSITIONS` | 8 | Hard safety cap |

### Strategy Filters

| Variable | Default | Description |
|----------|---------|-------------|
| `ENABLE_TREND_FILTER` | true | Only buy in uptrends |
| `ENABLE_VOLUME_FILTER` | true | Only trade liquid coins |
| `MIN_VOLUME_USD` | 1000000 | Min 24h volume ($) |
| `MAX_POSITION_AGE_HOURS` | 12 | Time-based exit (hours) |
| `SYMBOLS` | BTC,ETH,SOL,... | Comma-separated trading pairs |

## Project Structure

```
coinbase-worker/
├── Cargo.toml              # Rust dependencies
├── wrangler.toml           # Cloudflare config (non-secret settings)
├── .gitignore              # Excludes secrets, build artifacts
├── README.md               # This file
├── src/
│   ├── lib.rs              # Entry point, HTTP routes, scheduled handler
│   ├── auth.rs             # ES256 JWT authentication for Coinbase API
│   ├── client.rs           # Coinbase Advanced Trade API client
│   ├── config.rs           # Configuration from environment
│   ├── error.rs            # Error types (non-panicking)
│   ├── strategy.rs         # Trading logic, signals, TP/SL calculation
│   ├── trading.rs          # Trading engine orchestration
│   ├── types.rs            # Shared data structures
│   └── dashboard/          # Web UI
│       ├── mod.rs          # Dashboard module
│       ├── css.rs          # Styles
│       ├── html.rs         # HTML template
│       └── js.rs           # JavaScript (API calls, UI updates)
└── build/                  # WASM build output (gitignored)
```

## Local Development

```bash
# Run locally with live reload
wrangler dev

# Run tests
cargo test

# Run Clippy (linting)
cargo clippy

# Build WASM binary
cargo build --target wasm32-unknown-unknown --release
```

## Security

- **Secrets**: API keys stored in Cloudflare Secrets (encrypted, never in code)
- **No Panics**: All error handling uses `Result<T, TradingError>`
- **Input Validation**: All API responses validated before use
- **Rate Limiting**: Respects Coinbase API limits
- **Audit Trail**: All trades logged with timestamps

## Cost Breakdown

| Service | Free Tier | Paid Plan (~$5/mo) |
|---------|-----------|---------------------|
| Workers Requests | 100k/day | 10M/month |
| KV Storage | 100k reads/day | Included |
| KV Writes | 1k/day | Included |
| **Total** | Limited | **~$5/month** |

## Changelog

### v0.1.0 (2026-01-24)

- Initial release with mean reversion strategy
- Volatility-adaptive TP/SL (ATR-based, 2:1 R:R)
- Risk-based position sizing (2% risk, 25% max)
- Capital-based position limits (replaces arbitrary caps)
- Trend and volume filters
- Time-based exits (12h max hold)
- Web dashboard with real-time updates
- Full test coverage (21 tests)

## License

MIT
