import { useTradingStore } from '../store/tradingStore';
import { useEffect, useState } from 'react';
import { CONFIG } from '../config';

export const TechnicalAnalysis = () => {
  const store = useTradingStore();
  const marketData = store.marketData;
  const systemStatus = store.systemStatus;
  const [regimeData, setRegimeData] = useState<{vix: number; regime: string} | null>(null);

  // Fetch regime data from REST API as fallback
  useEffect(() => {
    const fetchRegime = async () => {
      try {
        const res = await fetch(`${CONFIG.API_BASE_URL}/api/market/regime`);
        if (res.ok) {
          const data = await res.json();
          setRegimeData({ vix: data.vix || 0, regime: data.regime || 'NEUTRAL' });
        }
      } catch (e) {
        console.error('Failed to fetch regime', e);
      }
    };
    
    if (!systemStatus?.vix) fetchRegime();
    const interval = setInterval(fetchRegime, 30000);
    return () => clearInterval(interval);
  }, [systemStatus]);

  const vix = systemStatus?.vix || regimeData?.vix || 0;
  const marketRegime = systemStatus?.marketTrend || regimeData?.regime || 'ANALYZING';

  const topSymbols = Object.values(marketData)
    .sort((a, b) => (b.score || 0) - (a.score || 0))
    .slice(0, 8);

  return (
    <div className="panel technical-analysis">
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '15px' }}>
        <h2 style={{ margin: 0 }}>📊 Technical Execution Matrix</h2>
        <div style={{ display: 'flex', gap: '15px', fontSize: '10px', fontFamily: 'monospace' }}>
          <div>
            <span style={{ color: 'var(--text-dim)' }}>VIX: </span>
            <span style={{ fontWeight: 800, color: vix > 25 ? 'var(--neon-red)' : 'var(--neon-green)' }}>
              {vix === 0 ? '--.--' : vix.toFixed(2)}
            </span>
          </div>
          <div>
            <span style={{ color: 'var(--text-dim)' }}>REGIME: </span>
            <span style={{ fontWeight: 800, color: 'var(--neon-cyan)' }}>
              {marketRegime}
            </span>
          </div>
        </div>
      </div>

      {topSymbols.length === 0 ? (
        <div className="scanning-state" style={{ padding: '30px', textAlign: 'center', color: 'var(--text-dim)', border: '1px dashed var(--text-muted)', borderRadius: 'var(--radius-sm)' }}>
          <p style={{ fontSize: '0.8rem' }}>🛰 SYNCING SIGNAL FEED...</p>
        </div>
      ) : (
        <div className="table-container">
          <table className="positions-table">
            <thead>
              <tr>
                <th>Identifier</th>
                <th style={{ textAlign: 'right' }}>Price</th>
                <th style={{ textAlign: 'right' }}>Variation</th>
                <th>Strength / Bias</th>
                <th style={{ textAlign: 'right' }}>ML Score</th>
              </tr>
            </thead>
            <tbody>
              {topSymbols.map((tech) => (
                <tr key={tech.symbol}>
                  <td className="font-bold">{tech.symbol}</td>
                  <td style={{ textAlign: 'right' }}>${tech.price.toFixed(2)}</td>
                  <td style={{ textAlign: 'right' }} className={tech.change >= 0 ? 'positive' : 'negative'}>
                    {tech.change >= 0 ? '↑' : '↓'} {Math.abs(tech.changePercent).toFixed(2)}%
                  </td>
                  <td>
                    <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
                      <div style={{ flex: 1, minWidth: '50px', height: '3px', background: 'var(--bg-deep)', borderRadius: '10px', overflow: 'hidden' }}>
                        <div style={{ 
                          width: `${Math.min(100, Math.max(0, tech.score))}%`, 
                          height: '100%', 
                          background: tech.score > 70 ? 'var(--neon-green)' : tech.score < 30 ? 'var(--neon-red)' : 'var(--neon-cyan)' 
                        }} />
                      </div>
                      <span style={{ fontSize: '9px', fontWeight: 700, textTransform: 'uppercase' }}>{tech.trend}</span>
                    </div>
                  </td>
                  <td style={{ textAlign: 'right', fontWeight: 800, color: 'var(--neon-cyan)' }}>{tech.score.toFixed(0)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
};
