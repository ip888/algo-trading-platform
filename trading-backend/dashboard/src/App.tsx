import { useEffect, useState } from 'react';
import { webSocketService } from './services/WebSocketService';
import { useTradingStore } from './store/tradingStore';

// Error Boundary
import { ComponentErrorBoundary } from './components/ComponentErrorBoundary';

// Core Components
import { AccountOverview } from './components/AccountOverview';
import { ProfitTargetsMonitor } from './components/ProfitTargetsMonitor';
import { LiveBotLog } from './components/LiveBotLog';
import { OrderHistory } from './components/OrderHistory';
import { Notifications } from './components/Notifications';
import { TechnicalAnalysis } from './components/TechnicalAnalysis';
import { PositionsTable } from './components/PositionsTable';
import { Watchlist } from './components/Watchlist';
import { SystemStatus } from './components/SystemStatus';
import { SafetyStatus } from './components/SafetyStatus';
import { BotBehaviorMonitor } from './components/BotBehaviorMonitor';
import { BacktestDashboard } from './components/BacktestDashboard';
import SmartFeaturesPanel from './components/SmartFeaturesPanel';
import OperationalEventsPanel from './components/OperationalEventsPanel';
import { BrokerStatus } from './components/BrokerStatus';
import { Activity, TrendingUp } from 'lucide-react';

import './App.css';

function App() {
  const store = useTradingStore();
  const accountData = store.accountData;
  
  useEffect(() => {
    webSocketService.connect();
    
    // Fetch initial account data via REST (WebSocket may not send until trading activity)
    const fetchAccountData = async () => {
      try {
        const res = await fetch('http://localhost:8080/api/account');
        if (res.ok) {
          const data = await res.json();
          store.setAccountData(data);
        }
      } catch (e) {
        console.warn('Failed to fetch initial account data:', e);
      }
    };
    fetchAccountData();
    
    // Refresh account data every 30 seconds
    const interval = setInterval(fetchAccountData, 30000);
    
    return () => {
      webSocketService.disconnect();
      clearInterval(interval);
    };
  }, []);
  
  // Robust Equity Calculation
  // If Alpaca reports 0 equity but non-zero buying power, use buying power/cash as fallback for display.
  const displayEquity = accountData?.equity && accountData.equity > 0 
    ? accountData.equity 
    : (accountData?.cash || accountData?.buyingPower || 0);

  // Session start capital set by bot at startup from live Alpaca equity
  const sessionStartCapital: number = (accountData as any)?.sessionStartCapital || 0;

  // Session P&L: equity vs what we started the session with
  const sessionPnl = sessionStartCapital > 0
    ? displayEquity - sessionStartCapital
    : (accountData?.lastEquity ? displayEquity - accountData.lastEquity : 0);

  const sessionPnlBase = sessionStartCapital > 0 ? sessionStartCapital : (accountData?.lastEquity || displayEquity);
  const pnlPercent = sessionPnlBase > 0 ? (sessionPnl / sessionPnlBase) * 100 : 0;
  const dailyPnl = sessionPnl;
  
  /* New Tab State */
  const [activeTab, setActiveTab] = useState<'live' | 'strategy'>('live');

  return (
    <div className="app">
      <header className="premium-header">
        <div className="header-content">
          <div className="brand">
            <div className="bot-icon">📊</div>
            <div>
              <h1>Multi-Broker Terminal <span className="version">v6.0</span></h1>
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
              <span className="label">SESSION P&L</span>
              <div className={`value ${dailyPnl >= 0 ? 'positive' : 'negative'}`}>
                ${dailyPnl.toLocaleString(undefined, { minimumFractionDigits: 2 })}
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
            <div className="stat-card">
              <span className="label">TOTAL TRADES</span>
              <div className="value highlight">{store.systemStatus?.totalTrades ?? '—'}</div>
            </div>
            <div className="stat-card">
              <span className="label">WIN RATE</span>
              <div className={`value ${(store.systemStatus?.winRate ?? 0) >= 50 ? 'positive' : (store.systemStatus?.winRate ?? 0) >= 30 ? '' : 'negative'}`}>
                {store.systemStatus?.totalTrades ? `${(store.systemStatus.winRate ?? 0).toFixed(1)}%` : '—'}
              </div>
            </div>
          </div>

          <div className="layout-columns">
            {/* LEFT: Financials */}
            <aside className="control-aside">
              <ComponentErrorBoundary name="Broker Status">
                <BrokerStatus />
              </ComponentErrorBoundary>
              <ComponentErrorBoundary name="Account Overview">
                <AccountOverview />
              </ComponentErrorBoundary>
              <ComponentErrorBoundary name="Bot Behavior">
                <BotBehaviorMonitor />
              </ComponentErrorBoundary>
              <ComponentErrorBoundary name="Profit Targets">
                <ProfitTargetsMonitor />
              </ComponentErrorBoundary>
            </aside>

            {/* CENTER: Real-Time Execution Matrix */}
            <section className="insight-section">
              <ComponentErrorBoundary name="Notifications">
                <Notifications />
              </ComponentErrorBoundary>
              <ComponentErrorBoundary name="Positions Table">
                <PositionsTable />
              </ComponentErrorBoundary>
              <ComponentErrorBoundary name="Watchlist">
                <Watchlist />
              </ComponentErrorBoundary>
              <ComponentErrorBoundary name="Technical Analysis">
                <TechnicalAnalysis />
              </ComponentErrorBoundary>
            </section>

            {/* RIGHT: System Intelligence */}
            <aside className="intel-aside">
              <ComponentErrorBoundary name="System Status">
                <SystemStatus />
              </ComponentErrorBoundary>
              <div className="panel" style={{ display: 'flex', flexDirection: 'column', gap: '15px' }}>
                <h3>📖 Intelligence Log</h3>
                <ComponentErrorBoundary name="Live Bot Log" minimal>
                  <LiveBotLog />
                </ComponentErrorBoundary>
              </div>
              <ComponentErrorBoundary name="Order History">
                <OrderHistory />
              </ComponentErrorBoundary>
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
              <ComponentErrorBoundary name="Backtest Dashboard">
                <BacktestDashboard />
              </ComponentErrorBoundary>
            </div>
            <aside className="strategy-sidebar">
              <ComponentErrorBoundary name="Operational Events">
                <OperationalEventsPanel />
              </ComponentErrorBoundary>
              <ComponentErrorBoundary name="Smart Features">
                <SmartFeaturesPanel />
              </ComponentErrorBoundary>
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
