//! Dashboard JavaScript
//!
//! Client-side logic for the trading dashboard:
//! - API calls to fetch portfolio, positions, scan data
//! - UI updates with formatting utilities
//! - Auto-refresh every 30 seconds
//! - Manual refresh and force check buttons

pub const SCRIPT: &str = r#"
// ============================================================================
// Configuration
// ============================================================================
const CONFIG = {
    refreshInterval: 30000,  // 30 seconds
    apiBase: ''
};

// ============================================================================
// State
// ============================================================================
let lastUpdate = null;

// ============================================================================
// API Functions
// ============================================================================
async function fetchJSON(endpoint) {
    try {
        const res = await fetch(CONFIG.apiBase + endpoint);
        return await res.json();
    } catch (e) {
        console.error(`Error fetching ${endpoint}:`, e);
        return null;
    }
}

// ============================================================================
// Formatting Utilities
// ============================================================================
function formatUSD(value) {
    if (value == null) return '$--';
    if (typeof value === 'string' && value.startsWith('$')) return value;
    if (isNaN(value)) return '$--';
    return '$' + parseFloat(value).toFixed(2);
}

function formatPercent(value) {
    if (value == null) return '--%';
    if (typeof value === 'string' && value.includes('%')) return value;
    if (isNaN(value)) return '--%';
    const sign = value >= 0 ? '+' : '';
    return sign + parseFloat(value).toFixed(2) + '%';
}

function getPnlClass(value) {
    if (typeof value === 'string') {
        if (value.startsWith('+') || (value.startsWith('$') && !value.includes('-'))) return 'positive';
        if (value.includes('-')) return 'negative';
        return 'neutral';
    }
    if (value > 0) return 'positive';
    if (value < 0) return 'negative';
    return 'neutral';
}

function parsePercent(value) {
    if (typeof value === 'string') {
        return parseFloat(value.replace('%', '').replace('+', ''));
    }
    return value || 0;
}

// ============================================================================
// UI Update Functions
// ============================================================================
function updateTimestamp() {
    lastUpdate = new Date();
    document.getElementById('refreshTime').textContent = 'Updated: ' + lastUpdate.toLocaleTimeString();
}

function updateStatus(status) {
    if (!status) return;
    
    const badge = document.getElementById('statusBadge');
    badge.textContent = status.enabled ? 'Trading' : 'Paused';
    badge.className = 'status-badge ' + (status.enabled ? 'status-enabled' : 'status-disabled');
    
    document.getElementById('dailyTrades').textContent = status.daily_trades || '0';
    document.getElementById('totalTrades').textContent = status.total_trades || '0';
    document.getElementById('totalPnl').textContent = formatUSD(status.total_pnl);
    document.getElementById('totalPnl').className = 'card-value ' + getPnlClass(status.total_pnl);
}

function updatePortfolio(debug) {
    if (!debug || !debug.portfolio) return;
    
    const p = debug.portfolio;
    document.getElementById('portfolioValue').textContent = p.total_portfolio || '$--';
    document.getElementById('cashBalance').textContent = p.usd_balance || '--';
    document.getElementById('positionsValue').textContent = p.positions_value || '--';
    document.getElementById('cashReserve').textContent = p.cash_reserve || '--';
}

function updateRiskSizing(debug) {
    if (!debug) return;
    
    if (debug.risk_sizing) {
        const r = debug.risk_sizing;
        document.getElementById('positionSize').textContent = r.final_size || '--';
        document.getElementById('riskPerTrade').textContent = r.max_risk_per_trade || '--';
        document.getElementById('maxPerPosition').textContent = r.max_per_position || '--';
        document.getElementById('canTrade').textContent = r.can_trade 
            ? '✅ Yes' 
            : '❌ No (' + (r.reason || 'N/A') + ')';
    }
    
    if (debug.positions) {
        document.getElementById('posCount').textContent = debug.positions.current + '/' + debug.positions.hard_cap;
        document.getElementById('maxNew').textContent = debug.positions.max_new;
    }
}

function updatePositionsTable(portfolio) {
    const tbody = document.getElementById('positionsBody');
    
    if (!portfolio || !portfolio.positions) {
        tbody.innerHTML = '<tr><td colspan="6" style="text-align: center; color: var(--text-dim);">Error loading</td></tr>';
        return;
    }
    
    if (portfolio.positions.length === 0) {
        tbody.innerHTML = '<tr><td colspan="6" style="text-align: center; color: var(--text-dim);">No open positions</td></tr>';
        return;
    }
    
    tbody.innerHTML = portfolio.positions.map(pos => {
        const pnlClass = getPnlClass(pos.unrealized_pnl || pos.pnl_percent);
        return `<tr>
            <td><strong>${pos.symbol.replace('-USD', '')}</strong></td>
            <td>${parseFloat(pos.quantity).toFixed(4)}</td>
            <td>${pos.entry_price || '--'}</td>
            <td>${pos.current_price || '--'}</td>
            <td class="${pnlClass}">${pos.unrealized_pnl || '--'} (${pos.pnl_percent || '--'})</td>
            <td>${pos.hours_held || '--'}</td>
        </tr>`;
    }).join('');
    
    // Win rate calculation
    const wins = portfolio.positions.filter(p => {
        const pnl = p.pnl_percent || '';
        return pnl.startsWith('+') && pnl !== '+0.00%';
    }).length;
    const total = portfolio.positions.length;
    document.getElementById('winRate').textContent = total > 0 ? Math.round(wins / total * 100) + '%' : '--';
}

function updateScanGrid(scan) {
    const grid = document.getElementById('scanGrid');
    
    if (!scan || scan.error) {
        grid.innerHTML = `<div class="scan-item"><span style="color: var(--red);">Error: ${scan?.message || 'Unknown'}</span></div>`;
        return;
    }
    
    if (!scan.symbols || scan.symbols.length === 0) {
        grid.innerHTML = '<div class="scan-item"><span class="scan-symbol">No symbols</span></div>';
        return;
    }
    
    grid.innerHTML = scan.symbols.map(s => {
        const signalClass = s.signal === 'Buy' ? 'signal-buy' : s.signal === 'Sell' ? 'signal-sell' : 'signal-hold';
        const changeVal = parsePercent(s.change_24h);
        const changeClass = changeVal >= 0 ? 'positive' : 'negative';
        const posIndicator = s.has_position ? ' 📍' : '';
        
        return `<div class="scan-item">
            <div class="scan-symbol">${s.symbol.replace('-USD', '')}${posIndicator}</div>
            <div class="scan-signal ${signalClass}">${s.signal}</div>
            <div class="scan-price">${s.price || '--'}</div>
            <div class="scan-change ${changeClass}">${s.change_24h || '--'}</div>
        </div>`;
    }).join('');
}

// ============================================================================
// Main Update Function
// ============================================================================
async function updateDashboard() {
    const [debug, portfolio, status, scan] = await Promise.all([
        fetchJSON('/api/debug'),
        fetchJSON('/api/portfolio'),
        fetchJSON('/api/status'),
        fetchJSON('/api/scan')
    ]);
    
    updateTimestamp();
    updateStatus(status);
    updatePortfolio(debug);
    updateRiskSizing(debug);
    updatePositionsTable(portfolio);
    updateScanGrid(scan);
}

// ============================================================================
// Button Actions
// ============================================================================
async function refreshAll() {
    const btn = document.getElementById('refreshBtn');
    btn.disabled = true;
    btn.textContent = '⏳';
    
    await updateDashboard();
    
    btn.disabled = false;
    btn.textContent = '🔄 Refresh';
}

async function forceCheck() {
    const btn = document.getElementById('forceBtn');
    btn.disabled = true;
    btn.textContent = '⏳ Running...';
    
    try {
        const result = await fetch('/api/trigger', { method: 'POST' });
        const data = await result.json();
        
        if (data.error) {
            alert('❌ Error: ' + data.message);
        } else {
            const msg = data.positions_opened > 0 || data.positions_closed > 0
                ? `✅ Opened: ${data.positions_opened}, Closed: ${data.positions_closed}`
                : '✅ No trades executed';
            alert(msg);
        }
    } catch (e) {
        alert('❌ Error: ' + e.message);
    } finally {
        btn.disabled = false;
        btn.textContent = '⚡ Force Check';
        updateDashboard();
    }
}

async function refreshScan() {
    document.getElementById('scanGrid').innerHTML = '<div class="scan-item"><span class="scan-symbol">Scanning...</span></div>';
    const scan = await fetchJSON('/api/scan');
    updateScanGrid(scan);
}

// ============================================================================
// Initialization
// ============================================================================
updateDashboard();
setInterval(updateDashboard, CONFIG.refreshInterval);
"#;
