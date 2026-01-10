#!/bin/bash

# Trading Bot Background Start Script

echo "========================================="
echo "Starting Alpaca Trading Bot (Background)"
echo "========================================="

# Check if JAR exists
if [ ! -f "target/alpaca-trading-bot-1.0.0.jar" ]; then
    echo "❌ JAR file not found. Building..."
    mvn clean package
    if [ $? -ne 0 ]; then
        echo "❌ Build failed!"
        exit 1
    fi
fi

# Check if already running
if pgrep -f "alpaca-trading-bot" > /dev/null; then
    echo "⚠️  Bot is already running!"
    echo "   PID: $(pgrep -f alpaca-trading-bot)"
    echo "   Use ./stop.sh to stop it first"
    exit 1
fi

echo "✅ Starting bot in background..."

# Start in background
nohup java --enable-preview -jar target/alpaca-trading-bot-1.0.0.jar > trading-bot.log 2>&1 &

# Wait a moment and check if it started
sleep 2

if pgrep -f "alpaca-trading-bot" > /dev/null; then
    PID=$(pgrep -f "alpaca-trading-bot")
    echo "✅ Bot started successfully!"
    echo "   PID: $PID"
    echo "   Logs: tail -f trading-bot.log"
    echo "   Health: ./health-check.sh"
    echo "   Dashboard: http://localhost:8080"
    echo ""
    echo "To stop: ./stop.sh"
else
    echo "❌ Bot failed to start. Check trading-bot.log for errors"
    tail -20 trading-bot.log
    exit 1
fi
