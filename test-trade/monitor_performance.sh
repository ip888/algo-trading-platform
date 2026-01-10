#!/bin/bash

# Live Trading Performance Monitor
# Monitors key metrics from trading bot logs

echo "🔍 Live Trading Performance Monitor"
echo "===================================="
echo ""

LOG_FILE="trading-bot.log"

if [ ! -f "$LOG_FILE" ]; then
    echo "❌ Error: $LOG_FILE not found"
    exit 1
fi

echo "📊 REGIME DETECTION"
echo "-------------------"
echo "Recent regime changes:"
grep "REGIME CHANGE" "$LOG_FILE" | tail -5
echo ""

echo "📈 MULTI-TIMEFRAME ANALYSIS"
echo "---------------------------"
echo "Timeframe alignment filtering:"
grep "Timeframes not aligned" "$LOG_FILE" | tail -5
echo ""

echo "💰 POSITION SIZING"
echo "------------------"
echo "Recent Kelly calculations:"
grep "Kelly sizing" "$LOG_FILE" | tail -5
echo ""
echo "Recent position sizes:"
grep "Advanced position sizing" "$LOG_FILE" | tail -5
echo ""

echo "📉 WIN RATE TRACKING"
echo "--------------------"
echo "Symbol statistics updates:"
grep "Updated stats from DB" "$LOG_FILE" | tail -10
echo ""

echo "⚠️  RISK EVENTS"
echo "---------------"
echo "Max loss exits:"
grep "MAX LOSS" "$LOG_FILE" | tail -5
echo ""
echo "Time-based exits:"
grep "TIME-BASED" "$LOG_FILE" | tail -5
echo ""
echo "Portfolio stop loss:"
grep "PORTFOLIO STOP" "$LOG_FILE" | tail -5
echo ""

echo "🎯 TRADE SUMMARY"
echo "----------------"
echo "Recent trades:"
grep -E "BUY executed|SELL executed" "$LOG_FILE" | tail -10
echo ""

echo "📊 ACCOUNT STATUS"
echo "-----------------"
echo "Recent equity updates:"
grep "Account equity" "$LOG_FILE" | tail -5
echo ""

echo "✅ Monitor complete!"
echo ""
echo "💡 Tips:"
echo "  - Run this every hour during trading"
echo "  - Watch for regime changes (should be rare)"
echo "  - Verify Kelly% is positive (>5%)"
echo "  - Check win rates are improving"
echo "  - Monitor position sizes are reasonable"
