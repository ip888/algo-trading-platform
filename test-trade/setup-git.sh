#!/bin/bash
# Git Setup Script for Trading Bot Auto-Push
# GitHub Repository: https://github.com/ipcasj/alpaca-java-bot.git

set -e  # Exit on error

echo "🔧 Setting up Git for auto-push..."

cd /Users/igor/projects/java-edu/test-trade

# 1. Initialize Git (if not already done)
if [ ! -d ".git" ]; then
    echo "📦 Initializing Git repository..."
    git init
    echo "✅ Git initialized"
else
    echo "✅ Git already initialized"
fi

# 2. Configure user
echo "👤 Configuring Git user..."
git config user.name "Trading Bot"
git config user.email "bot@trading.com"
echo "✅ Git user configured"

# 3. Check if remote already exists
if git remote get-url origin &> /dev/null; then
    echo "⚠️  Remote 'origin' already exists:"
    git remote get-url origin
    read -p "Do you want to update it? (y/n) " -n 1 -r
    echo
    if [[ $REPLY =~ ^[Yy]$ ]]; then
        git remote remove origin
        echo "🗑️  Removed old remote"
    else
        echo "✅ Keeping existing remote"
        exit 0
    fi
fi

# 4. Add GitHub remote
echo "🌐 Adding GitHub remote..."
git remote add origin https://github.com/ipcasj/alpaca-java-bot.git
echo "✅ Remote added: https://github.com/ipcasj/alpaca-java-bot.git"

# 5. Verify remote
echo ""
echo "📋 Remote configuration:"
git remote -v

# 6. Check if we should do initial commit
echo ""
read -p "Do you want to make an initial commit and push? (y/n) " -n 1 -r
echo
if [[ $REPLY =~ ^[Yy]$ ]]; then
    echo "📝 Creating initial commit..."
    
    # Add all files
    git add .
    
    # Commit
    git commit -m "Initial commit: Enterprise self-healing trading bot

Features:
- Autonomous error detection with ML anomaly detection
- Light fixes via config adjustments
- Hard fixes via Claude API code generation
- Git auto-commits with detailed messages
- Safe mode for market anomalies
- Full sandbox testing before production

Technology:
- Java 23 with virtual threads
- Claude 3.5 Sonnet for code generation
- JGit for version control
- Maven build system
- React dashboard with WebSocket"
    
    echo "✅ Initial commit created"
    
    # Push to remote
    echo "📤 Pushing to GitHub..."
    git branch -M main
    git push -u origin main
    
    echo "✅ Pushed to GitHub!"
else
    echo "⏭️  Skipping initial commit"
fi

echo ""
echo "🎉 Git setup complete!"
echo ""
echo "Next steps:"
echo "1. Verify on GitHub: https://github.com/ipcasj/alpaca-java-bot"
echo "2. Bot will auto-push to 'auto-heal/*' branches when fixing errors"
echo "3. All commits will be tagged with 'auto-heal-{timestamp}'"
echo ""
echo "Configuration in config.properties:"
echo "  GIT_AUTO_PUSH=true"
echo "  GIT_REMOTE_NAME=origin"
