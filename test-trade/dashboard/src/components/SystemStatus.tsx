import { useTradingStore } from '../store/tradingStore';

export const SystemStatus = () => {
  const { systemStatus, botStatus } = useTradingStore();
  
  if (!systemStatus) {
    return (
      <div className="panel status-card">
        <h3>🧠 System Intelligence</h3>
        <div style={{ padding: '20px', textAlign: 'center', color: 'var(--text-dim)', fontSize: '13px' }}>
          Calculating Market Regime...
        </div>
      </div>
    );
  }

  return (
    <div className="panel status-card">
      <h3>🧠 System Intelligence</h3>
      
      <div className="intelligence-grid">
        <div className="intel-item">
          <span className="label">Operation Mode</span>
          <span className="value highlight" style={{ color: 'var(--primary)' }}>{systemStatus.tradingMode}</span>
        </div>
        
        <div className="intel-item">
          <span className="label">Exchange Status</span>
          <span className={`value ${systemStatus.marketOpen ? 'positive' : 'negative'}`} style={{ color: systemStatus.marketOpen ? 'var(--success)' : 'var(--error)' }}>
            {systemStatus.marketOpen ? 'OPEN' : 'CLOSED'}
          </span>
        </div>

        <div className="intel-item">
          <span className="label">Volatility (VIX)</span>
          <span className="value" style={{ color: 'var(--warning)' }}>
            {systemStatus.vix?.toFixed(2) || '---'}
          </span>
        </div>

        <div className="intel-item">
          <span className="label">Market Regime</span>
          <span className="value highlight" style={{ color: 'var(--primary)' }}>{systemStatus.marketTrend || 'INITIALIZING'}</span>
        </div>
      </div>

      <div style={{ marginTop: '20px', padding: '16px', background: 'var(--bg-main)', borderRadius: 'var(--radius-sm)' }}>
        <div className="label" style={{ fontSize: '11px', fontWeight: 700, color: 'var(--text-muted)', marginBottom: '4px' }}>CORE DECISION:</div>
        <div className="value" style={{ fontSize: '14px', fontWeight: 600 }}>
          {botStatus?.nextAction || systemStatus.recommendation || 'Scanning for alpha opportunity...'}
        </div>
      </div>
    </div>
  );
};
