//! Dashboard HTML template
//!
//! Contains the main page structure including:
//! - Header with status and controls
//! - Portfolio and P&L cards
//! - Risk sizing information
//! - Positions table with live P&L
//! - Market scan results

pub const TEMPLATE: &str = r#"
    <div class="container">
        <header>
            <div>
                <h1>🪙 Coinbase Trading Bot</h1>
                <span class="refresh-time" id="refreshTime">Loading...</span>
            </div>
            <div class="header-controls">
                <span class="status-badge status-disabled" id="statusBadge">Loading</span>
                <button class="btn btn-secondary" onclick="refreshAll()" id="refreshBtn">🔄 Refresh</button>
                <button class="btn btn-primary" onclick="forceCheck()" id="forceBtn">⚡ Force Check</button>
            </div>
        </header>

        <div class="grid">
            <!-- Portfolio Card -->
            <div class="card">
                <div class="card-header">
                    <span class="card-title">💰 Portfolio Value</span>
                </div>
                <div class="card-value" id="portfolioValue">$--</div>
                <div class="metrics">
                    <div class="metric">
                        <div class="metric-label">Cash</div>
                        <div class="metric-value" id="cashBalance">--</div>
                    </div>
                    <div class="metric">
                        <div class="metric-label">Positions</div>
                        <div class="metric-value" id="positionsValue">--</div>
                    </div>
                    <div class="metric">
                        <div class="metric-label">Reserve</div>
                        <div class="metric-value" id="cashReserve">--</div>
                    </div>
                </div>
            </div>

            <!-- P&L Card -->
            <div class="card">
                <div class="card-header">
                    <span class="card-title">📊 Performance</span>
                </div>
                <div class="card-value" id="totalPnl">$--</div>
                <div class="metrics">
                    <div class="metric">
                        <div class="metric-label">Today</div>
                        <div class="metric-value" id="dailyTrades">--</div>
                    </div>
                    <div class="metric">
                        <div class="metric-label">All Time</div>
                        <div class="metric-value" id="totalTrades">--</div>
                    </div>
                    <div class="metric">
                        <div class="metric-label">Win Rate</div>
                        <div class="metric-value" id="winRate">--</div>
                    </div>
                </div>
            </div>

            <!-- Risk Sizing Card -->
            <div class="card">
                <div class="card-header">
                    <span class="card-title">⚙️ Risk Sizing</span>
                </div>
                <div class="risk-info">
                    <div class="risk-row">
                        <span class="risk-label">Position Size</span>
                        <span id="positionSize">--</span>
                    </div>
                    <div class="risk-row">
                        <span class="risk-label">Risk per Trade</span>
                        <span id="riskPerTrade">--</span>
                    </div>
                    <div class="risk-row">
                        <span class="risk-label">Max per Position</span>
                        <span id="maxPerPosition">--</span>
                    </div>
                    <div class="risk-row">
                        <span class="risk-label">Can Trade</span>
                        <span id="canTrade">--</span>
                    </div>
                </div>
                <div class="metrics" style="margin-top: 16px;">
                    <div class="metric">
                        <div class="metric-label">Positions</div>
                        <div class="metric-value" id="posCount">--</div>
                    </div>
                    <div class="metric">
                        <div class="metric-label">Max New</div>
                        <div class="metric-value" id="maxNew">--</div>
                    </div>
                </div>
            </div>

            <!-- Open Positions -->
            <div class="card wide">
                <div class="card-header">
                    <span class="card-title">📈 Open Positions</span>
                </div>
                <table class="positions-table">
                    <thead>
                        <tr>
                            <th>Symbol</th>
                            <th>Size</th>
                            <th>Entry</th>
                            <th>Current</th>
                            <th>P&L</th>
                            <th>Age</th>
                        </tr>
                    </thead>
                    <tbody id="positionsBody">
                        <tr><td colspan="6" style="text-align: center; color: var(--text-dim);">Loading...</td></tr>
                    </tbody>
                </table>
            </div>

            <!-- Market Scan -->
            <div class="card wide">
                <div class="card-header">
                    <span class="card-title">🔍 Market Scan</span>
                    <button class="btn btn-secondary" onclick="refreshScan()">Scan Now</button>
                </div>
                <div class="scan-grid" id="scanGrid">
                    <div class="scan-item"><span class="scan-symbol">Loading...</span></div>
                </div>
            </div>
        </div>
    </div>
"#;
