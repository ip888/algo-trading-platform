import { useEffect, useRef, useState } from 'react';
import { useTradingStore } from '../store/tradingStore';
import { CONFIG } from '../config';

interface WatchlistRow {
  symbol: string;
  price: number;
  change: number;
  changePercent: number;
  score: number;
  trend: string;
  strategy: string;
  recommendation?: string;
}

export const Watchlist = () => {
  const [rows, setRows] = useState<WatchlistRow[]>([]);
  const [status, setStatus] = useState<'loading' | 'ok' | 'error'>('loading');
  const [errorMsg, setErrorMsg] = useState('');
  const baseRef = useRef<WatchlistRow[]>([]);

  // Fetch the symbol baseline from REST once
  useEffect(() => {
    let active = true;
    const load = async () => {
      try {
        const res = await fetch(`${CONFIG.API_BASE_URL}/api/watchlist`);
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        const data = await res.json();
        const list: WatchlistRow[] = (data.watchlist || []).map((item: any) => ({
          symbol: String(item.symbol),
          price: Number(item.price) || 0,
          change: Number(item.change) || 0,
          changePercent: Number(item.changePercent) || 0,
          score: Number(item.score) || 50,
          trend: String(item.trend || 'NEUTRAL'),
          strategy: 'Stock',
          recommendation: undefined,
        }));
        if (!active) return;
        baseRef.current = list;
        setRows(list);
        setStatus('ok');
      } catch (e: any) {
        if (!active) return;
        setErrorMsg(String(e?.message || 'Unknown error'));
        setStatus('error');
      }
    };
    load();
    const id = setInterval(load, 60000);
    return () => { active = false; clearInterval(id); };
  }, []);

  // Overlay live WebSocket prices without subscribing to every tick
  const marketData = useTradingStore(s => s.marketData);
  useEffect(() => {
    if (baseRef.current.length === 0) return;
    const merged = baseRef.current.map(row => {
      const live = marketData[row.symbol];
      if (live && live.price > 0) {
        return { ...row, price: live.price, change: live.change ?? 0, changePercent: live.changePercent ?? 0, score: live.score || row.score, trend: live.trend || row.trend, recommendation: live.recommendation };
      }
      return row;
    });
    setRows(merged);
  }, [marketData]);

  if (status === 'loading') {
    return (
      <div className="panel" style={{ marginTop: '15px' }}>
        <h3>🔭 Active Watchlist</h3>
        <div style={{ padding: '20px', textAlign: 'center', color: 'var(--text-dim)', fontSize: '13px' }}>
          Loading watchlist...
        </div>
      </div>
    );
  }

  if (status === 'error') {
    return (
      <div className="panel" style={{ marginTop: '15px' }}>
        <h3>🔭 Active Watchlist</h3>
        <div style={{ padding: '20px', textAlign: 'center', color: 'var(--neon-red)', fontSize: '12px' }}>
          ⚠ {errorMsg}
        </div>
      </div>
    );
  }

  if (rows.length === 0) {
    return (
      <div className="panel" style={{ marginTop: '15px' }}>
        <h3>🔭 Active Watchlist</h3>
        <div style={{ padding: '20px', textAlign: 'center', color: 'var(--text-dim)', fontSize: '13px' }}>
          No symbols configured
        </div>
      </div>
    );
  }

  const pinned = ['SPY', 'QQQ', 'IWM', 'DIA'];
  const sorted = [...rows].sort((a, b) => {
    const aPinned = pinned.includes(a.symbol);
    const bPinned = pinned.includes(b.symbol);
    if (aPinned && !bPinned) return -1;
    if (!aPinned && bPinned) return 1;
    const sigOrder = (r?: string) => r === 'BUY' ? 0 : r === 'SELL' ? 1 : 2;
    const d = sigOrder(a.recommendation) - sigOrder(b.recommendation);
    if (d !== 0) return d;
    return (b.score || 0) - (a.score || 0);
  });

  return (
    <div className="panel" style={{ marginTop: '15px', border: 'none', boxShadow: 'none' }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '10px' }}>
        <h3 style={{ margin: 0 }}>🔭 Active Watchlist</h3>
        <span style={{ fontSize: '10px', color: 'var(--text-dim)', fontFamily: 'monospace' }}>{sorted.length} symbols</span>
      </div>
      <div className="table-container" style={{ maxHeight: '300px', overflowY: 'auto' }}>
        <table className="watchlist-table" style={{ width: '100%', fontSize: '12px', borderCollapse: 'collapse' }}>
          <thead>
            <tr style={{ color: 'var(--text-muted)', textAlign: 'left', borderBottom: '1px solid var(--border-light)' }}>
              <th style={{ padding: '8px' }}>Asset</th>
              <th style={{ padding: '8px', textAlign: 'right' }}>Price</th>
              <th style={{ padding: '8px', textAlign: 'right' }}>Chg%</th>
              <th style={{ padding: '8px' }}>Signal</th>
              <th style={{ padding: '8px' }}>Strategy</th>
            </tr>
          </thead>
          <tbody>
            {sorted.map(row => {
              const chgPct = row.changePercent ?? 0;
              return (
                <tr key={row.symbol} style={{ borderBottom: '1px solid var(--border-subtle)' }}>
                  <td style={{ padding: '8px', fontWeight: 600 }}>{row.symbol}</td>
                  <td style={{ padding: '8px', textAlign: 'right', fontFamily: 'monospace' }}>
                    {row.price > 0 ? `$${row.price.toFixed(2)}` : <span style={{ color: 'var(--text-dim)' }}>---</span>}
                  </td>
                  <td style={{ padding: '8px', textAlign: 'right' }}>
                    {chgPct !== 0
                      ? <span style={{ color: chgPct >= 0 ? 'var(--neon-green)' : 'var(--neon-red)' }}>{chgPct >= 0 ? '▲' : '▼'} {Math.abs(chgPct).toFixed(2)}%</span>
                      : <span style={{ color: 'var(--text-dim)' }}>--</span>}
                  </td>
                  <td style={{ padding: '8px' }}>
                    <span className={`status-tag ${row.recommendation === 'BUY' ? 'status-buy' : row.recommendation === 'SELL' ? 'status-sell' : 'status-hold'}`}>
                      {row.recommendation || 'HOLD'}
                    </span>
                  </td>
                  <td style={{ padding: '8px', color: 'var(--text-dim)', fontSize: '11px' }}>{row.strategy}</td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
    </div>
  );
};
