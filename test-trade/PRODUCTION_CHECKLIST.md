# Production Trading Checklist

⚠️ **CRITICAL: Use this checklist before enabling LIVE trading mode!**

## Pre-Flight Checklist

### 1. Configuration
- [ ] Review `config.properties` - all values set correctly
- [ ] Verify `TRADING_MODE=PAPER` initially
- [ ] Test with paper trading for at **minimum 1 week**
- [ ] Set appropriate `INITIAL_CAPITAL` in TradingBot.java

### 2. Risk Parameters (in RiskManager.java)
- [ ] Verify `RISK_PER_TRADE` (default: 1%)
- [ ] Verify `STOP_LOSS_PCT` (default: 2%)  
- [ ] Verify `TAKE_PROFIT_PCT` (default: 4%)
- [ ] Verify `MAX_DRAWDOWN` (default: 10%)
- [ ] Verify `TRAILING_STOP_PCT` (default: 2%)

### 3. Testing
- [ ] Run backtester: `mvn exec:java -Dapp.main.class="com.trading.backtesting.Backtester"`
- [ ] Paper trade for 1+ week with real market hours
- [ ] Monitor dashboard at http://localhost:8080
- [ ] Review all trades in `trades.db`
- [ ] Verify win rate > 40%
- [ ] Verify profit factor > 1.5

### 4. Notifications
- [ ] Test Telegram alerts (if enabled)
- [ ] Test Email alerts (if enabled)
- [ ] Ensure you receive trade notifications

### 5. Market Filters
- [ ] Verify market hours filter works (9:30 AM - 4:00 PM EST)
- [ ] Verify VIX volatility filter works (blocks when VIX > 30)

### 6. Final Steps Before LIVE
- [ ] Start with SMALL capital ($100-$500)
- [ ] Change Alpaca API to **LIVE** keys (not paper)
- [ ] Set `TRADING_MODE=LIVE` in config.properties
- [ ] Monitor **CLOSELY** for first week
- [ ] Keep stop-loss tight

## Live Trading Warnings

1. **Never** disable stop-losses
2. **Never** trade more than you can afford to lose
3. **Never** let emotions override the bot
4. **Always** monitor the dashboard daily
5. **Always** review the trade database weekly

## Emergency Stop

To halt trading immediately:
1. Stop the bot (Ctrl+C)
2. Manually close all positions in Alpaca dashboard
3. Set `TRADING_MODE=PAPER` in config

## Support

Paper trading is FREE and unlimited on Alpaca. Use it extensively before going live.

**Good luck! 🚀**
