import { useTradingStore } from '../store/tradingStore';
import { memo, useEffect } from 'react';
import { CONFIG } from '../config';

const ProfitTargetsMonitorComponent = () => {
  const { profitTargets, setProfitTargets } = useTradingStore();
  
  // Fetch data on initial load (REST fallback)
  useEffect(() => {
    const fetchProfitTargets = async () => {
      try {
        const res = await fetch(`${CONFIG.API_BASE_URL}/api/profit-targets`);
        if (res.ok) {
          const data = await res.json();
          setProfitTargets(data);
        }
      } catch (e) {
        console.error('Failed to fetch profit targets:', e);
      }
    };

    fetchProfitTargets();
    const interval = setInterval(fetchProfitTargets, 15000); // Refresh every 15s
    return () => clearInterval(interval);
  }, [setProfitTargets]);
  
  return (
    <div className="panel profit-targets">
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '15px' }}>
        <h2 style={{ margin: 0 }}>🎯 Deployment Profit Bounds</h2>
        <span style={{ fontSize: '10px', color: 'var(--text-dim)', background: 'var(--bg-deep)', padding: '2px 8px', borderRadius: '4px', border: '1px solid var(--border-dim)', fontFamily: 'monospace' }}>
          TRACKING: {profitTargets.length} OBJECTIVES
        </span>
      </div>
      
      {profitTargets.length === 0 ? (
        <div className="scanning-state" style={{ padding: '30px', textAlign: 'center', color: 'var(--text-dim)', border: '1px dashed var(--text-muted)', borderRadius: 'var(--radius-sm)' }}>
          <p style={{ fontSize: '0.8rem' }}>� NO ACTIVE POSITIONS</p>
          <p style={{ fontSize: '0.7rem', marginTop: '8px', opacity: 0.6 }}>Exit targets will appear when positions are opened</p>
        </div>
      ) : (
        <div className="table-container">
          <table className="positions-table">
            <thead>
              <tr>
                <th>Identifier</th>
                <th>Accrued Yield</th>
                <th>Objective</th>
                <th>Gap</th>
                <th>Projection</th>
              </tr>
            </thead>
            <tbody>
              {profitTargets.map((target) => (
                <tr key={target.symbol}>
                  <td className="font-bold">{target.symbol}</td>
                  <td className={target.currentPnlPercent >= 0 ? 'positive' : 'negative'} style={{ fontWeight: 800 }}>
                    {target.currentPnlPercent >= 0 ? '+' : ''}{target.currentPnlPercent.toFixed(2)}%
                  </td>
                  <td>{target.targetPercent.toFixed(2)}%</td>
                  <td>
                    {target.distancePercent >= 0 
                      ? `${target.distancePercent.toFixed(2)}%` 
                      : 'EXCEEDED'}
                  </td>
                  <td style={{ color: target.eta === 'Soon' ? 'var(--neon-green)' : 'var(--text-secondary)', fontWeight: 700 }}>
                    {target.eta === 'Soon' ? '🎯 IMMINENT' : target.eta.toUpperCase()}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
};

export const ProfitTargetsMonitor = memo(ProfitTargetsMonitorComponent);
