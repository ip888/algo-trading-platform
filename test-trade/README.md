# Complete Trading Bot - README

## 🤖 Professional Algorithmic Trading Bot

A production-ready Java trading bot with hybrid strategies, risk management, multi-symbol support, and comprehensive monitoring.

## ✨ Features

### Strategies
- **RSI** (14-period) - Mean reversion
- **MACD** (12/26/9) - Trend following
- **Bollinger Bands** (20-period, ±2σ) - Volatility mean reversion
- **Regime Detection** - Automatically switches based on market volatility

### Risk Management
- Position sizing (1% capital risk per trade)
- Stop-loss (2% below entry)
- Take-profit (4% above entry, 2:1 reward/risk)
- Trailing stop-loss (locks in profits)
- Max drawdown protection (10% circuit breaker)

### Portfolio Management
- Multi-symbol trading (SPY, QQQ, IWM)
- Capital allocation across symbols
- Independent position tracking

### Market Safety
- Market hours filter (9:30 AM - 4:00 PM EST)
- Volatility filter (VIX > 30 blocks trading)
- Paper/Live trading mode toggle

### Analytics & Monitoring
- SQLite trade database (`trades.db`)
- Web dashboard (http://localhost:8080)
- Real-time P&L tracking
- Performance metrics

### Notifications
- Telegram alerts (optional)
- Email alerts (optional)

## 🚀 Quick Start

### 1. Prerequisites
- Java 23+
- Maven 3.9+
- Alpaca account ([sign up free](https://alpaca.markets))

### 2. Configuration
```bash
cp config.properties.template config.properties
# Edit config.properties with your Alpaca API keys
```

### 3. Run (Paper Trading)
```bash
mvn exec:java
```

### 4. View Dashboard
Open: http://localhost:8080

### 5. Backtest
```bash
mvn exec:java -Dapp.main.class="com.trading.backtesting.Backtester"
```

## 📋 Configuration

### Required
```properties
APCA_API_KEY_ID=your_api_key
APCA_API_SECRET_KEY=your_api_secret
TRADING_MODE=PAPER
```

### Optional
```properties
# Telegram
TELEGRAM_BOT_TOKEN=your_token
TELEGRAM_CHAT_ID=your_chat_id

# Email
EMAIL_ADDRESS=you@gmail.com
EMAIL_PASSWORD=app_password
```

## ⚠️ Going Live

**Before enabling live trading:**
1. Review `PRODUCTION_CHECKLIST.md`
2. Paper trade for 1+ week
3. Verify win rate > 40%
4. Start with small capital ($100-$500)
5. Change `TRADING_MODE=LIVE`
6. Use LIVE Alpaca API keys (not paper)

## 📊 Architecture

```
com.trading
├── bot           - Main application
├── strategy      - Trading strategies
├── risk          - Risk management
├── portfolio     - Multi-symbol coordination
├── filters       - Market filters
├── persistence   - Database
├── dashboard     - Web UI
├── notifications - Alerts
├── api           - Alpaca client
├── config        - Configuration
└── backtesting   - Historical testing
```

## 🛠️ Development

### Build
```bash
mvn clean install
```

### Run Tests
```bash
mvn test
```

### Create JAR
```bash
mvn package
```

## 📈 Performance Metrics

The bot tracks:
- Total P&L
- Win rate
- Profit factor
- Sharpe ratio
- Max drawdown
- Trade count

View in:
- Dashboard (http://localhost:8080)
- Database (`trades.db`)
- Logs

## 🔒 Risk Disclaimer

**This is educational software. Trading involves risk.**
- Start with paper trading
- Never risk more than you can afford to lose
- Past performance ≠ future results
- The bot can lose money
- Monitor it closely

## 📝 License

Educational purposes only.

## 🙏 Acknowledgments

- Alpaca Markets for excellent paper trading API
- Modern Java features (records, sealed classes, pattern matching)
- Community trading strategy research
