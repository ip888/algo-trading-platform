import { useTradingStore } from '../store/tradingStore';
import { useEffect, useState } from 'react';
import { CONFIG } from '../config';

export const TechnicalAnalysis = () => {
  const store = useTradingStore();
  const marketData = store.marketData;
  const systemStatus = store.systemStatus;
  const [regimeData, setRegimeData] = useState<{ vix: number; regime: string } | null>(null);

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

    fetchRegime();
    const interval = setInterval(fetchRegime, 30000);
    return () => clearInterval(interval);
  }, []);

  const vix = systemStatus?.vix || regimeData?.vix || 0;
  const marketRegime = systemStatus?.marketTrend || regimeData?.regime || 'ANALYZING';

  // Sort: BUY signals first, then SELL, then by score desc.
  const topSymbols = Object.values(marketData)
    .sort((a, b) => {
      const sigOrder = (r?: string) => r === 'BUY' ? 0 : r === 'SELL' ? 1 : 2;
      const sigDiff = sigOrder(a.recommendation) - sigOrder(b.recommendation);
      if (sigDiff !== 0) return sigDiff;
      return (b.score || 0) - (a.score || 0);
    })
    .slice(0, 10);

  const vixColor = vix === 0 ? 'var(--text-dim)' : vix > 30 ? 'var(--neon-red)' : vix > 20 ? 'var(--neon-amber)' : 'var(--neon-green)';
  const regimeColor = marketRegime.includes('BEAR') ? 'var(--neon-red)' : marketRegime.includes('BULL') ? 'var(--neon-green)' : 'var(--neon-cyan)';

  return (
    <div className="panel technical-analysis">
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '15px' }}>
        <h2 style={{ margin: 0 }}>📊 Technical Execution Matrix</h2>
        <div style={{ display: 'flex', gap: '15px', fontSize: '10px', fontFamily: 'monospace' }}>
          <div>
            <span style={{ color: 'var(--text-dim)' }}>VIX: </span>
            <span style={{ fontWeight: 800, color: vixColor }}>
              {vix === 0 ? '--.--' : vix.toFixed(2)}
            </span>
          </div>
          <div>
            <span style={{ color: 'var(--text-dim)' }}>REGIME: </span>
            <span style={{ fontWeight: 800, color: regimeColor }}>
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
                <th>Asset</th>
                <th style={{ textAlign: 'right' }}>Price</th>
                <th style={{ textAlign: 'right' }}>Chg%</th>
                <th>Strength</th>
                <th style={{ textAlign: 'center' }}>Signal</th>
              </tr>
            </thead>
            <tbody>
              {topSymbols.map((tech) => {
                const chgPct = tech.changePercent ?? 0;
                const hasPrice = tech.price > 0;
                return (
                  <tr key={tech.symbol}>
                    <td className="font-bold">{tech.symbol}</td>
                    <td style={{ textAlign: 'right', fontFamily: 'monospace' }}>
                      {hasPrice ? `$${tech.price.toFixed(2)}` : <span style={{ color: 'var(--text-dim)' }}>---</span>}
                    </td>
                    <td style={{ textAlign: 'right' }}>
                      {chgPct !== 0 ? (
                        <span className={tech.change >= 0 ? 'positive' : 'negative'}>
                          {tech.change >= 0 ? '▲' : '▼'} {Math.abs(chgPct).toFixed(2)}%
                        </span>
                      ) : (
                        <span style={{ color: 'var(--text-dim)' }}>--</span>
                      )}
                    </td>
                    <td>
                      <div style={{ display: 'flex', alignItems: 'center', gap: '6px' }}>
                        <div style={{ flex: 1, minWidth: '50px', height: '3px', background: 'var(--bg-deep)', borderRadius: '10px', overflow: 'hidden' }}>
                          <div style={{
                            width: `${Math.min(100, Math.max(0, tech.score))}%`,
                            height: '100%',
                            background: tech.score > 70 ? 'var(--neon-green)' : tech.score < 30 ? 'var(--neon-red)' : 'var(--neon-cyan)'
                          }} />
                        </div>
                        <span style={{ fontSize: '9px', fontWeight: 700, textTransform: 'uppercase', color: 'var(--text-dim)', minWidth: '28px' }}>
                          {tech.score.toFixed(0)}
                        </span>
                      </div>
                    </td>
                    <td style={{ textAlign: 'center' }}>
                      <span className={`status-tag ${tech.recommendation === 'BUY' ? 'status-buy' : (tech.recommendation === 'SELL' ? 'status-sell' : 'status-hold')}`}>
                        {tech.recommendation || tech.trend || 'HOLD'}
                      </span>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
};
