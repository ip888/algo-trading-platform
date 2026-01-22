#!/bin/bash
# Deploy Trading Backend to Fly.io
# Usage: ./deploy_fly.sh [--first-time]

set -e

echo "🚀 Deploying Trading Backend to Fly.io (Warsaw region)"

# Check if flyctl is installed
if ! command -v fly &> /dev/null; then
    echo "❌ flyctl not installed. Install with: brew install flyctl"
    echo "   Then run: fly auth login"
    exit 1
fi

# Check if logged in
if ! fly auth whoami &> /dev/null; then
    echo "❌ Not logged in to Fly.io. Run: fly auth login"
    exit 1
fi

# First-time setup
if [[ "$1" == "--first-time" ]]; then
    echo "📝 First-time setup..."
    
    # Create the app
    fly apps create trading-backend --org personal 2>/dev/null || echo "App already exists"
    
    # Set secrets from config.properties
    echo "🔐 Setting secrets..."
    
    # Read secrets from config.properties
    ALPACA_KEY=$(grep "^ALPACA_API_KEY=" config.properties | cut -d= -f2)
    ALPACA_SECRET=$(grep "^ALPACA_API_SECRET=" config.properties | cut -d= -f2)
    KRAKEN_KEY=$(grep "^KRAKEN_API_KEY=" config.properties | cut -d= -f2)
    KRAKEN_SECRET=$(grep "^KRAKEN_API_SECRET=" config.properties | cut -d= -f2)
    
    fly secrets set \
        ALPACA_API_KEY="$ALPACA_KEY" \
        ALPACA_API_SECRET="$ALPACA_SECRET" \
        KRAKEN_API_KEY="$KRAKEN_KEY" \
        KRAKEN_API_SECRET="$KRAKEN_SECRET" \
        --app trading-backend
    
    echo "✅ Secrets configured"
fi

# Build the JAR first
echo "📦 Building JAR..."
mvn clean package -DskipTests -q

if [[ ! -f "target/trading-backend.jar" ]]; then
    echo "❌ Build failed - JAR not found"
    exit 1
fi

echo "✅ JAR built successfully"

# Deploy to Fly.io
echo "🚀 Deploying to Fly.io (Warsaw)..."
fly deploy --region waw

echo ""
echo "✅ Deployment complete!"
echo ""
echo "📊 Useful commands:"
echo "   fly logs                    # View live logs"
echo "   fly status                  # Check deployment status"
echo "   fly ssh console             # SSH into container"
echo "   fly scale show              # Show current scaling"
echo "   fly scale count 0           # Stop (save costs)"
echo "   fly scale count 1           # Start"
echo ""
echo "🌐 Your app: https://trading-backend.fly.dev"
echo ""

# Show status
fly status --app trading-backend
