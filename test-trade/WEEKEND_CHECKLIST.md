# Weekend Testing Checklist - Live Account

**Testing Period:** December 6-8, 2025  
**Mode:** Live account (market closed - safe)  
**Go-Live:** Monday, December 9, 2025

---

## Friday Evening - Initial Setup ✓

### 1. Configuration Verified
- [x] Live credentials in config.properties
- [x] TEST_MODE=true (enabled)
- [x] BYPASS_MARKET_HOURS=true (enabled)
- [x] INITIAL_CAPITAL=$500
- [x] Multi-profile enabled

### 2. Build & Start
```bash
# Clean build
mvn clean compile

# Start bot
mvn exec:java
```

**Watch for:**
- ✅ "Connected to Alpaca! Account Status: ACTIVE"
- ✅ "Dashboard available at: http://localhost:8080"
- ✅ No ERROR messages

### 3. Initial Health Check
```bash
# In another terminal
./health-check.sh
```

**Expected:**
- Bot running ✅
- Health status: UP ✅
- All components: UP ✅
- No errors ✅

### 4. Dashboard Access
```bash
open http://localhost:8080
```

**Verify:**
- [ ] Dashboard loads
- [ ] Real-time updates working
- [ ] Charts rendering
- [ ] WebSocket connected

---

## Saturday - Full Day Testing

### Morning (9 AM - 12 PM)

**Monitor test signals:**
```bash
tail -f trading-bot.log | grep -E "SIGNAL|Order|Position|TEST"
```

**Check every hour:**
- [ ] Bot still running
- [ ] Health status UP
- [ ] Test signals generating
- [ ] Positions tracked
- [ ] Database updating

### Afternoon (1 PM - 5 PM)

**Performance monitoring:**
```bash
# Memory/CPU
top -pid $(pgrep -f TradingBot)

# Health check
./health-check.sh
```

**Database check:**
```bash
sqlite3 trades.db "SELECT COUNT(*) FROM trades;"
sqlite3 trades.db "SELECT symbol, entry_price, pnl FROM trades ORDER BY entry_time DESC LIMIT 5;"
```

### Evening (6 PM - 9 PM)

**Log analysis:**
```bash
# Errors
grep ERROR trading-bot.log

# Warnings
grep WARN trading-bot.log

# Test signals
grep "TEST MODE" trading-bot.log | wc -l
```

---

## Sunday - Final Validation

### Morning (9 AM - 12 PM)

**Overnight stability check:**
- [ ] Bot still running
- [ ] No crashes
- [ ] Memory stable
- [ ] Database intact

### Afternoon (1 PM - 5 PM)

**Prepare for Monday:**
```bash
# Update config for production
nano config.properties

# Change:
TEST_MODE=false
BYPASS_MARKET_HOURS=false
```

**Final health check:**
```bash
./health-check.sh
```

### Evening (6 PM - 9 PM)

**Overnight test:**
```bash
# Let bot run overnight
# Monitor Monday morning before market open
```

---

## Monday Morning - Pre-Market

### 7:00 AM - Check Overnight Run
```bash
# Bot status
ps aux | grep TradingBot

# Recent logs
tail -100 trading-bot.log

# Health check
./health-check.sh
```

### 9:00 AM - Pre-Market Prep
- [ ] Bot running smoothly
- [ ] Health status UP
- [ ] TEST_MODE=false
- [ ] BYPASS_MARKET_HOURS=false
- [ ] Ready for market open

### 9:30 AM - Market Open
**Watch closely:**
- [ ] Market data flowing
- [ ] Strategies evaluating
- [ ] Signals appropriate
- [ ] Orders validated

---

## Quick Commands

**Start bot:**
```bash
mvn exec:java
```

**Health check:**
```bash
./health-check.sh
```

**Monitor logs:**
```bash
tail -f trading-bot.log
```

**Check database:**
```bash
sqlite3 trades.db "SELECT COUNT(*) FROM trades;"
```

**Stop bot:**
```bash
pkill -f TradingBot
```

---

## Success Criteria

- [x] Configuration correct
- [ ] 48+ hours stable operation
- [ ] No critical errors
- [ ] Health checks passing
- [ ] Dashboard functional
- [ ] Database persisting
- [ ] Ready for Monday

---

**Status:** Testing in progress...
