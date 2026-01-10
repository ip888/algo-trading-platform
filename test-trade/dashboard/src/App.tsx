import { useEffect, useState } from 'react';
import { webSocketService } from './services/WebSocketService';
import { useTradingStore } from './store/tradingStore';

// Core Components
import { AccountOverview } from './components/AccountOverview';
import { ProfitTargetsMonitor } from './components/ProfitTargetsMonitor';
import { LiveBotLog } from './components/LiveBotLog';
import { OrderHistory } from './components/OrderHistory';
import { Notifications } from './components/Notifications';
import { TechnicalAnalysis } from './components/TechnicalAnalysis';
import { PositionsTable } from './components/PositionsTable';
import { SystemStatus } from './components/SystemStatus';
import { SafetyStatus } from './components/SafetyStatus';
import { BacktestDashboard } from './components/BacktestDashboard';
import SmartFeaturesPanel from './components/SmartFeaturesPanel';
import OperationalEventsPanel from './components/OperationalEventsPanel';
import { Activity, TrendingUp } from 'lucide-react';

import './App.css';

function App() {
  const store = useTradingStore();
  const accountData = store.accountData;
  
  useEffect(() => {
    webSocketService.connect();
    return () => webSocketService.disconnect();
  }, []);
  
  // Robust Equity Calculation
  // If Alpaca reports 0 equity but non-zero buying power, use buying power/cash as fallback for display.
  const displayEquity = accountData?.equity && accountData.equity > 0 
    ? accountData.equity 
    : (accountData?.cash || accountData?.buyingPower || 0);

  // Use a more realistic starting capital for Alpaca Paper Trading
  const initialCapital = displayEquity > 50000 ? 100000 : (displayEquity > 5000 ? 10000 : 1000);
  const pnl = displayEquity > 0 ? displayEquity - initialCapital : 0;
  const pnlPercent = initialCapital > 0 && displayEquity > 0 ? (pnl / initialCapital) * 100 : 0;
  
  /* New Tab State */
  const [activeTab, setActiveTab] = useState<'live' | 'strategy'>('live');

  return (
    <div className="app">
      <header className="premium-header">
        <div className="header-content">
          <div className="brand">
            <div className="bot-icon">📊</div>
            <div>
              <h1>Alpaca Global Finance <span className="version">v6.0</span></h1>
              <div className="connection-status">
                <span className={`status-dot ${store.connected ? 'online' : 'offline'}`}></span>
                {store.connected ? 'Terminal Connected' : 'Reconnecting to Core...'}
              </div>
            </div>
          </div>
          
          <div className="nav-tabs">
            <button 
              className={`nav-tab ${activeTab === 'live' ? 'active' : ''}`}
              onClick={() => setActiveTab('live')}
            >
              <Activity size={16} /> Ops Center
            </button>
            <button 
              className={`nav-tab ${activeTab === 'strategy' ? 'active' : ''}`}
              onClick={() => setActiveTab('strategy')}
            >
              <TrendingUp size={16} /> Strategy Lab
            </button>
          </div>

          <div className="header-actions">
            <SafetyStatus />
          </div>
        </div>
      </header>
      
      {activeTab === 'live' ? (
        <main className="dashboard-grid">
          {/* TOP BAR - High-Speed Stats */}
          <div className="stats-strip">
            <div className="stat-card">
              <span className="label">PORTFOLIO P&L</span>
              <div className={`value ${pnl >= 0 ? 'positive' : 'negative'}`}>
                ${pnl.toLocaleString(undefined, { minimumFractionDigits: 2 })}
                <span className="percent">({pnlPercent.toFixed(2)}%)</span>
              </div>
            </div>
            <div className="stat-card">
              <span className="label">TOTAL EQUITY</span>
              <div className="value highlight">${displayEquity.toLocaleString(undefined, { minimumFractionDigits: 2 })}</div>
            </div>
            <div className="stat-card">
              <span className="label">CASH BALANCE</span>
              <div className="value highlight">${(accountData?.cash || 0).toLocaleString(undefined, { minimumFractionDigits: 2 })}</div>
            </div>
            <div className="stat-card">
              <span className="label">ACTIVE POSITIONS</span>
              <div className="value highlight">{store.systemStatus?.activePositions || store.positions?.length || 0}</div>
            </div>
          </div>

          <div className="layout-columns">
            {/* LEFT: Financials */}
            <aside className="control-aside">
              <AccountOverview />
              <ProfitTargetsMonitor />
            </aside>

            {/* CENTER: Real-Time Execution Matrix */}
            <section className="insight-section">
              <Notifications />
              <PositionsTable />
              <TechnicalAnalysis />
            </section>

            {/* RIGHT: System Intelligence */}
            <aside className="intel-aside">
              <SystemStatus />
              <div className="panel" style={{ display: 'flex', flexDirection: 'column', gap: '15px' }}>
                <h3>📖 Intelligence Log</h3>
                <LiveBotLog />
              </div>
              <OrderHistory />
            </aside>
          </div>
        </main>
      ) : (
        <main className="dashboard-backtest">
          <header className="lab-header">
            <h2>🧪 Strategy Research Lab</h2>
            <p>Simulate high-frequency strategies on historical Alpaca market data.</p>
          </header>
          <div className="strategy-layout">
            <div className="strategy-main">
              <BacktestDashboard />
            </div>
            <aside className="strategy-sidebar">
              <OperationalEventsPanel />
              <SmartFeaturesPanel />
            </aside>
          </div>
        </main>
      )}
      
      <footer className="compact-footer">
        <div className="footer-links">
          <span>Java 25 LTS</span>
          <span>•</span>
          <span>Virtual Threads: ENABLED</span>
        </div>
        <div className="footer-timestamp">
          LATENCY: {store.lastUpdate ? `${Math.floor(Date.now() - store.lastUpdate)}ms` : 'N/A'}
        </div>
      </footer>
    </div>
  );
}

export default App;
