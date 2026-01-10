#!/bin/bash

# Trading Bot Stop Script

echo "========================================="
echo "Stopping Alpaca Trading Bot"
echo "========================================="

# Check if running
if ! pgrep -f "alpaca-trading-bot" > /dev/null; then
    echo "ℹ️  Bot is not running"
    exit 0
fi

PID=$(pgrep -f "alpaca-trading-bot")
echo "Found bot running with PID: $PID"
echo "Stopping gracefully..."

# Send SIGTERM for graceful shutdown
kill $PID

# Wait up to 10 seconds for graceful shutdown
for i in {1..10}; do
    if ! pgrep -f "alpaca-trading-bot" > /dev/null; then
        echo "✅ Bot stopped successfully"
        exit 0
    fi
    sleep 1
done

# Force kill if still running
if pgrep -f "alpaca-trading-bot" > /dev/null; then
    echo "⚠️  Forcing shutdown..."
    kill -9 $PID
    sleep 1
    if ! pgrep -f "alpaca-trading-bot" > /dev/null; then
        echo "✅ Bot stopped (forced)"
    else
        echo "❌ Failed to stop bot"
        exit 1
    fi
fi
