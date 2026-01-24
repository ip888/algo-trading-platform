import { useTradingStore } from '../store/tradingStore';
import { useEffect, useState } from 'react';
import { CONFIG } from '../config';

export const SystemStatus = () => {
  const { systemStatus, botStatus } = useTradingStore();
  const [marketFallback, setMarketFallback] = useState<any>(null);

  // Fallback fetch if WebSocket data is missing
  useEffect(() => {
    const fetchMarket = async () => {
      try {
        const res = await fetch(`${CONFIG.API_BASE_URL}/api/market/status`);
        if (res.ok) setMarketFallback(await res.json());
      } catch (e) {
        console.error('Market status fetch failed', e);
      }
    };
    if (!systemStatus?.vix) fetchMarket();
    // Poll every 30s as backup
    const interval = setInterval(fetchMarket, 30000);
    return () => clearInterval(interval);
  }, [systemStatus]);
  
  // Merge sources
  const currentVix = systemStatus?.vix || marketFallback?.currentVIX || 0;
  const currentTrend = systemStatus?.marketTrend || (marketFallback?.volatilityAcceptable ? 'STABLE' : 'VOLATILE');
  const marketOpen = systemStatus?.marketOpen ?? marketFallback?.isOpen ?? false;

  if (!systemStatus && !marketFallback) {
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
          <span className="value highlight" style={{ color: 'var(--primary)' }}>{systemStatus?.tradingMode || 'LIVE'}</span>
        </div>
        
        <div className="intel-item">
          <span className="label">Exchange Status</span>
          <span className={`value ${marketOpen ? 'positive' : 'negative'}`} style={{ color: marketOpen ? 'var(--success)' : 'var(--error)' }}>
            {marketOpen ? 'OPEN' : 'CLOSED'}
          </span>
        </div>

        <div className="intel-item">
          <span className="label">Volatility (VIX)</span>
          <span className="value" style={{ color: 'var(--warning)' }}>
            {currentVix.toFixed(2)}
          </span>
        </div>

        <div className="intel-item">
          <span className="label">News Sentiment</span>
          <span className="value" style={{ 
            color: (systemStatus?.sentiment || 0) > 0 ? 'var(--neon-green)' : ((systemStatus?.sentiment || 0) < 0 ? 'var(--neon-red)' : 'var(--text-muted)'),
            fontWeight: 600
          }}>
            {(systemStatus?.sentiment || 0).toFixed(2)}
          </span>
        </div>

        <div className="intel-item">
          <span className="label">Market Regime</span>
          <span className="value highlight" style={{ color: 'var(--primary)' }}>{currentTrend}</span>
        </div>
      </div>

      <div style={{ marginTop: '20px', padding: '16px', background: 'var(--bg-main)', borderRadius: 'var(--radius-sm)' }}>
        <div className="label" style={{ fontSize: '11px', fontWeight: 700, color: 'var(--text-muted)', marginBottom: '4px' }}>CORE DECISION:</div>
        <div className="value" style={{ fontSize: '14px', fontWeight: 600 }}>
          {botStatus?.nextAction || systemStatus?.recommendation || (marketOpen ? 'Analyzing market opportunities...' : 'Stock market closed. Waiting for next session.')}
        </div>
      </div>
    </div>
  );
};
