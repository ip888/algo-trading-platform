#!/bin/bash
# Git Helper Script - Quick Commits for Trading Bot
# Usage: ./git-commit.sh "Your commit message"

set -e

cd /Users/igor/projects/java-edu/test-trade

# Check if message provided
if [ -z "$1" ]; then
    echo "📝 No message provided, using default..."
    MESSAGE="Updated trading bot configuration - $(date '+%Y-%m-%d %H:%M')"
else
    MESSAGE="$1"
fi

echo "📦 Staging all changes..."
git add .

echo "📝 Creating commit..."
git commit -m "$MESSAGE"

echo "📤 Pushing to GitHub..."
git push origin main

echo "✅ Done! Committed and pushed to GitHub"
echo ""
echo "View on GitHub: https://github.com/ipcasj/alpaca-java-bot"
