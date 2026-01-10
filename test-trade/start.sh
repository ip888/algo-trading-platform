#!/bin/bash

# Trading Bot Start Script

echo "========================================="
echo "Starting Alpaca Trading Bot"
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

echo "✅ Starting bot..."
echo ""

# Start the bot with preview features enabled
java --enable-preview -jar target/alpaca-trading-bot-1.0.0.jar

# Note: This runs in foreground. For background, use:
# nohup java --enable-preview -jar target/alpaca-trading-bot-1.0.0.jar > bot.log 2>&1 &
