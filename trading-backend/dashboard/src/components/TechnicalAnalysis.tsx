import { useEffect, useState } from 'react';
import { useTradingStore } from '../store/tradingStore';
import { CONFIG } from '../config';

interface SymbolData {
  symbol: string;
  price: number;
  change: number;
  changePercent: number;
  score: number;
  trend: string;
  recommendation?: string;
}

export const TechnicalAnalysis = () => {
  const [symbols, setSymbols] = useState<SymbolData[]>([]);
  const [vix, setVix] = useState(0);
  const [regime, setRegime] = useState('ANALYZING');
  const [loading, setLoading] = useState(true);

  // Live WS overlay
  const { marketData, systemStatus } = useTradingStore();

  useEffect(() => {
    const fetchData = async () => {
      try {
        const [watchRes, regimeRes] = await Promise.all([
          fetch(`${CONFIG.API_BASE_URL}/api/watchlist`),
          fetch(`${CONFIG.API_BASE_URL}/api/market/regime`),
        ]);

        if (watchRes.ok) {
          const data = await watchRes.json();
          const list: SymbolData[] = (data.watchlist || []).map((item: any) => ({
            symbol: item.symbol,
            price: item.price || 0,
            change: item.change || 0,
            changePercent: item.changePercent || 0,
            score: item.score || 50,
            trend: item.trend || 'NEUTRAL',
            recommendation: undefined,
          }));
          setSymbols(list);
        }

        if (regimeRes.ok) {
          const r = await regimeRes.json();
          setVix(r.vix || 0);
          setRegime(r.regime || r.trend || 'NEUTRAL');
        }
      } catch (e) {
        console.error('TechnicalAnalysis fetch failed', e);
      } finally {
        setLoading(false);
      }
    };

    fetchData();
    const interval = setInterval(fetchData, 30000);
    return () => clearInterval(interval);
  }, []);

  // Prefer live WS data when available
  const displayVix = systemStatus?.vix || vix;
  const displayRegime = systemStatus?.marketTrend || regime;

  // Merge live WS prices on top of REST baseline
  const enriched = symbols.map(s => {
    const live = marketData[s.symbol];
    if (live && live.price > 0) {
      return {
        ...s,
        price: live.price,
        change: live.change,
        changePercent: live.changePercent,
        score: live.score || s.score,
        trend: live.trend || s.trend,
        recommendation: live.recommendation,
      };
    }
    return s;
  });

  const topSymbols = [...enriched]
    .sort((a, b) => {
      const sigOrder = (r?: string) => r === 'BUY' ? 0 : r === 'SELL' ? 1 : 2;
      const sigDiff = sigOrder(a.recommendation) - sigOrder(b.recommendation);
      if (sigDiff !== 0) return sigDiff;
      return (b.score || 0) - (a.score || 0);
    })
    .slice(0, 10);

  const vixColor = displayVix === 0 ? 'var(--text-dim)' : displayVix > 30 ? 'var(--neon-red)' : displayVix > 20 ? 'var(--neon-amber)' : 'var(--neon-green)';
  const regimeColor = displayRegime.includes('BEAR') ? 'var(--neon-red)' : displayRegime.includes('BULL') ? 'var(--neon-green)' : 'var(--neon-cyan)';

  return (
    <div className="panel technical-analysis">
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '15px' }}>
        <h2 style={{ margin: 0 }}>📊 Technical Execution Matrix</h2>
        <div style={{ display: 'flex', gap: '15px', fontSize: '10px', fontFamily: 'monospace' }}>
          <div>
            <span style={{ color: 'var(--text-dim)' }}>VIX: </span>
            <span style={{ fontWeight: 800, color: vixColor }}>
              {displayVix === 0 ? '--.--' : displayVix.toFixed(2)}
            </span>
          </div>
          <div>
            <span style={{ color: 'var(--text-dim)' }}>REGIME: </span>
            <span style={{ fontWeight: 800, color: regimeColor }}>{displayRegime}</span>
          </div>
        </div>
      </div>

      {loading ? (
        <div style={{ padding: '20px', textAlign: 'center', color: 'var(--text-dim)', fontSize: '13px' }}>
          Loading signal matrix...
        </div>
      ) : topSymbols.length === 0 ? (
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
              {topSymbols.map((s) => {
                const chgPct = s.changePercent ?? 0;
                const hasPrice = s.price > 0;
                return (
                  <tr key={s.symbol}>
                    <td className="font-bold">{s.symbol}</td>
                    <td style={{ textAlign: 'right', fontFamily: 'monospace' }}>
                      {hasPrice
                        ? `$${s.price.toFixed(2)}`
                        : <span style={{ color: 'var(--text-dim)' }}>---</span>}
                    </td>
                    <td style={{ textAlign: 'right' }}>
                      {chgPct !== 0 ? (
                        <span className={s.change >= 0 ? 'positive' : 'negative'}>
                          {s.change >= 0 ? '▲' : '▼'} {Math.abs(chgPct).toFixed(2)}%
                        </span>
                      ) : (
                        <span style={{ color: 'var(--text-dim)' }}>--</span>
                      )}
                    </td>
                    <td>
                      <div style={{ display: 'flex', alignItems: 'center', gap: '6px' }}>
                        <div style={{ flex: 1, minWidth: '50px', height: '3px', background: 'var(--bg-deep)', borderRadius: '10px', overflow: 'hidden' }}>
                          <div style={{
                            width: `${Math.min(100, Math.max(0, s.score))}%`,
                            height: '100%',
                            background: s.score > 70 ? 'var(--neon-green)' : s.score < 30 ? 'var(--neon-red)' : 'var(--neon-cyan)'
                          }} />
                        </div>
                        <span style={{ fontSize: '9px', fontWeight: 700, color: 'var(--text-dim)', minWidth: '22px' }}>
                          {s.score.toFixed(0)}
                        </span>
                      </div>
                    </td>
                    <td style={{ textAlign: 'center' }}>
                      <span className={`status-tag ${s.recommendation === 'BUY' ? 'status-buy' : s.recommendation === 'SELL' ? 'status-sell' : 'status-hold'}`}>
                        {s.recommendation || s.trend || 'HOLD'}
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
