//! Dashboard CSS styles
//!
//! Contains all styling for the trading dashboard UI.
//! Uses CSS custom properties (variables) for theming.

pub const STYLES: &str = r"
* { box-sizing: border-box; margin: 0; padding: 0; }

:root {
    --bg: #0d1117;
    --card: #161b22;
    --border: #30363d;
    --text: #c9d1d9;
    --text-dim: #8b949e;
    --green: #3fb950;
    --red: #f85149;
    --blue: #58a6ff;
    --yellow: #d29922;
    --purple: #a371f7;
}

body {
    font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
    background: var(--bg);
    color: var(--text);
    padding: 20px;
    min-height: 100vh;
}

.container { max-width: 1200px; margin: 0 auto; }

/* Header */
header {
    display: flex;
    justify-content: space-between;
    align-items: center;
    margin-bottom: 24px;
    padding-bottom: 16px;
    border-bottom: 1px solid var(--border);
}

h1 { font-size: 24px; font-weight: 600; }

.header-controls {
    display: flex;
    align-items: center;
    gap: 12px;
}

.refresh-time { font-size: 12px; color: var(--text-dim); }

/* Status Badge */
.status-badge {
    padding: 6px 12px;
    border-radius: 20px;
    font-size: 12px;
    font-weight: 600;
    text-transform: uppercase;
}

.status-enabled { background: rgba(63, 185, 80, 0.2); color: var(--green); }
.status-disabled { background: rgba(248, 81, 73, 0.2); color: var(--red); }

/* Buttons */
.btn {
    padding: 8px 16px;
    border-radius: 6px;
    border: none;
    font-size: 13px;
    font-weight: 500;
    cursor: pointer;
    transition: all 0.2s;
}

.btn:disabled { opacity: 0.6; cursor: not-allowed; }
.btn-primary { background: var(--blue); color: #fff; }
.btn-primary:hover:not(:disabled) { background: #4c9aed; }
.btn-secondary { background: var(--border); color: var(--text); }
.btn-secondary:hover:not(:disabled) { background: #3d444d; }

/* Grid Layout */
.grid {
    display: grid;
    grid-template-columns: repeat(auto-fit, minmax(300px, 1fr));
    gap: 16px;
}

.wide { grid-column: 1 / -1; }

/* Cards */
.card {
    background: var(--card);
    border: 1px solid var(--border);
    border-radius: 12px;
    padding: 20px;
}

.card-header {
    display: flex;
    justify-content: space-between;
    align-items: center;
    margin-bottom: 16px;
}

.card-title {
    font-size: 14px;
    color: var(--text-dim);
    text-transform: uppercase;
    letter-spacing: 0.5px;
}

.card-value { font-size: 28px; font-weight: 700; }

/* Metrics Grid */
.metrics {
    display: flex;
    flex-wrap: wrap;
    gap: 16px;
    margin-top: 12px;
}

.metric { flex: 1; min-width: 100px; }
.metric-label { font-size: 11px; color: var(--text-dim); text-transform: uppercase; }
.metric-value { font-size: 18px; font-weight: 600; margin-top: 2px; }

/* Colors */
.positive { color: var(--green); }
.negative { color: var(--red); }
.neutral { color: var(--text-dim); }

/* Risk Info Box */
.risk-info {
    background: rgba(88, 166, 255, 0.1);
    border-radius: 8px;
    padding: 12px;
    margin-top: 12px;
}

.risk-row {
    display: flex;
    justify-content: space-between;
    padding: 4px 0;
    font-size: 13px;
}

.risk-label { color: var(--text-dim); }

/* Positions Table */
.positions-table { width: 100%; margin-top: 12px; }

.positions-table th,
.positions-table td {
    text-align: left;
    padding: 10px 8px;
    border-bottom: 1px solid var(--border);
}

.positions-table th {
    color: var(--text-dim);
    font-weight: 500;
    font-size: 12px;
    text-transform: uppercase;
}

.positions-table tr:last-child td { border-bottom: none; }

/* Scan Grid */
.scan-grid {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(140px, 1fr));
    gap: 10px;
    margin-top: 12px;
}

.scan-item {
    background: rgba(255, 255, 255, 0.03);
    border-radius: 8px;
    padding: 12px;
    text-align: center;
}

.scan-symbol { font-weight: 600; font-size: 14px; }

.scan-signal {
    font-size: 11px;
    margin-top: 4px;
    padding: 3px 8px;
    border-radius: 4px;
    display: inline-block;
}

.signal-buy { background: rgba(63, 185, 80, 0.2); color: var(--green); }
.signal-sell { background: rgba(248, 81, 73, 0.2); color: var(--red); }
.signal-hold { background: rgba(139, 148, 158, 0.2); color: var(--text-dim); }

.scan-price { font-size: 13px; color: var(--text-dim); margin-top: 6px; }
.scan-change { font-size: 12px; margin-top: 2px; }

/* Responsive */
@media (max-width: 600px) {
    .grid { grid-template-columns: 1fr; }
    header { flex-direction: column; gap: 12px; }
    .header-controls { flex-wrap: wrap; justify-content: center; }
}
";
