#!/bin/bash

# Quick Health Check Script
# Run this periodically during testing

echo "==================================="
echo "Trading Bot Health Check"
echo "Time: $(date)"
echo "==================================="

# Check if bot is running
if pgrep -f "TradingBot" > /dev/null; then
    echo "✅ Bot Status: RUNNING"
    PID=$(pgrep -f "TradingBot")
    echo "   PID: $PID"
else
    echo "❌ Bot Status: NOT RUNNING"
    exit 1
fi

# Health endpoint
echo ""
echo "Health Endpoint:"
HEALTH=$(curl -s http://localhost:8080/health)
STATUS=$(echo $HEALTH | jq -r '.status' 2>/dev/null)

if [ "$STATUS" = "UP" ]; then
    echo "✅ Status: $STATUS"
else
    echo "⚠️  Status: $STATUS"
fi

# Component status
echo ""
echo "Components:"
echo $HEALTH | jq -r '.components | to_entries[] | "  \(.key): \(.value.status)"' 2>/dev/null

# System metrics
echo ""
echo "System Metrics:"
curl -s http://localhost:8080/api/system/status | jq -r '
  "  Trading Mode: \(.tradingMode)",
  "  Active Positions: \(.activePositions)",
  "  PDT Protection: \(.pdtProtectionEnabled)",
  "  Test Mode: \(.testModeEnabled)"
' 2>/dev/null

# Market status
echo ""
echo "Market Status:"
curl -s http://localhost:8080/api/market/status | jq -r '
  "  Market Open: \(.isOpen)",
  "  Volatility OK: \(.volatilityAcceptable)",
  "  Current VIX: \(.currentVIX)"
' 2>/dev/null

# Trade stats
echo ""
echo "Trade Statistics:"
curl -s http://localhost:8080/api/trades/stats | jq -r '
  "  Total Trades: \(.totalTrades)",
  "  Total P&L: $\(.totalPnL)",
  "  Win Rate: \(.winRate * 100)%"
' 2>/dev/null

# Recent errors
echo ""
echo "Recent Errors (last 10):"
tail -10 trading-bot.log | grep ERROR || echo "  No errors found"

# Memory usage
echo ""
echo "Resource Usage:"
ps aux | grep TradingBot | grep -v grep | awk '{print "  CPU: "$3"% | Memory: "$4"%"}'

echo ""
echo "==================================="
echo "Health check complete"
echo "==================================="
