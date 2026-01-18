#!/bin/bash
# ============================================================
# Trading Bot Local Deployment Script
# Zero-cost, self-healing, auto-restart Docker deployment
# ============================================================

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

log_info() { echo -e "${BLUE}ℹ️  $1${NC}"; }
log_success() { echo -e "${GREEN}✅ $1${NC}"; }
log_warn() { echo -e "${YELLOW}⚠️  $1${NC}"; }
log_error() { echo -e "${RED}❌ $1${NC}"; }

# Check Docker is running
check_docker() {
    if ! docker info > /dev/null 2>&1; then
        log_error "Docker is not running. Please start Docker Desktop."
        exit 1
    fi
    log_success "Docker is running"
}

# Build the dashboard first
build_dashboard() {
    log_info "Building React dashboard..."
    if [ -d "dashboard" ]; then
        cd dashboard
        if [ ! -d "node_modules" ]; then
            npm install
        fi
        npm run build
        cd ..
        log_success "Dashboard built"
    else
        log_warn "Dashboard directory not found, skipping"
    fi
}

# Create necessary directories
setup_directories() {
    mkdir -p data logs
    log_success "Data and log directories created"
}

# Main commands
case "${1:-help}" in
    build)
        log_info "Building Trading Bot Docker image..."
        check_docker
        build_dashboard
        docker compose build --no-cache
        log_success "Build complete!"
        ;;
    
    start)
        log_info "Starting Trading Bot..."
        check_docker
        setup_directories
        docker compose up -d
        log_success "Trading Bot started!"
        echo ""
        log_info "Dashboard: http://localhost:8080"
        log_info "Health: http://localhost:8080/api/health"
        echo ""
        log_info "View logs with: $0 logs"
        ;;
    
    stop)
        log_info "Stopping Trading Bot..."
        docker compose down
        log_success "Trading Bot stopped"
        ;;
    
    restart)
        log_info "Restarting Trading Bot..."
        docker compose restart
        log_success "Trading Bot restarted"
        ;;
    
    logs)
        log_info "Showing Trading Bot logs (Ctrl+C to exit)..."
        docker compose logs -f --tail=100
        ;;
    
    status)
        log_info "Trading Bot Status:"
        echo ""
        docker compose ps
        echo ""
        log_info "Health check:"
        curl -s http://localhost:8080/api/health 2>/dev/null || log_warn "Bot not responding"
        ;;
    
    shell)
        log_info "Opening shell in Trading Bot container..."
        docker compose exec trading-bot /bin/bash
        ;;
    
    update)
        log_info "Updating and restarting Trading Bot..."
        check_docker
        build_dashboard
        docker compose build
        docker compose up -d
        log_success "Trading Bot updated and restarted!"
        ;;
    
    clean)
        log_warn "This will remove all containers and volumes!"
        read -p "Are you sure? (y/N) " -n 1 -r
        echo
        if [[ $REPLY =~ ^[Yy]$ ]]; then
            docker compose down -v
            log_success "Cleaned up"
        fi
        ;;
    
    install-autostart)
        log_info "Setting up auto-start on system boot..."
        
        # Create launchd plist for macOS
        PLIST_PATH="$HOME/Library/LaunchAgents/com.trading.bot.plist"
        cat > "$PLIST_PATH" << EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>Label</key>
    <string>com.trading.bot</string>
    <key>ProgramArguments</key>
    <array>
        <string>$SCRIPT_DIR/deploy_local.sh</string>
        <string>start</string>
    </array>
    <key>RunAtLoad</key>
    <true/>
    <key>KeepAlive</key>
    <false/>
    <key>StandardOutPath</key>
    <string>$SCRIPT_DIR/logs/launchd.log</string>
    <key>StandardErrorPath</key>
    <string>$SCRIPT_DIR/logs/launchd.err</string>
</dict>
</plist>
EOF
        launchctl load "$PLIST_PATH" 2>/dev/null || true
        log_success "Auto-start configured!"
        log_info "Trading Bot will start automatically on login"
        ;;
    
    remove-autostart)
        PLIST_PATH="$HOME/Library/LaunchAgents/com.trading.bot.plist"
        if [ -f "$PLIST_PATH" ]; then
            launchctl unload "$PLIST_PATH" 2>/dev/null || true
            rm "$PLIST_PATH"
            log_success "Auto-start removed"
        else
            log_warn "Auto-start not configured"
        fi
        ;;
    
    help|*)
        echo ""
        echo "🤖 Trading Bot Local Deployment"
        echo "================================"
        echo ""
        echo "Usage: $0 <command>"
        echo ""
        echo "Commands:"
        echo "  build              Build Docker image"
        echo "  start              Start Trading Bot (with auto-restart)"
        echo "  stop               Stop Trading Bot"
        echo "  restart            Restart Trading Bot"
        echo "  logs               View live logs"
        echo "  status             Check bot status and health"
        echo "  shell              Open shell in container"
        echo "  update             Rebuild and restart"
        echo "  clean              Remove all containers and data"
        echo ""
        echo "Auto-start (macOS):"
        echo "  install-autostart  Start bot on system login"
        echo "  remove-autostart   Remove auto-start"
        echo ""
        echo "Quick Start:"
        echo "  $0 build && $0 start"
        echo ""
        ;;
esac
