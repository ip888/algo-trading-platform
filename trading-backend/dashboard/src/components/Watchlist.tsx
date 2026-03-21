import { useEffect, useState } from 'react';
import { useTradingStore } from '../store/tradingStore';
import { CONFIG } from '../config';

interface WatchlistItem {
  symbol: string;
  price: number;
  change: number;
  changePercent: number;
  score: number;
  trend: string;
  strategy: string;
  recommendation?: string;
  regime?: string;
}

export const Watchlist = () => {
  const [items, setItems] = useState<WatchlistItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  // Merge live WebSocket market data on top of REST baseline
  const { marketData } = useTradingStore();

  useEffect(() => {
    const fetchWatchlist = async () => {
      try {
        const res = await fetch(`${CONFIG.API_BASE_URL}/api/watchlist`);
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        const data = await res.json();
        const list: WatchlistItem[] = (data.watchlist || []).map((item: any) => ({
          symbol: item.symbol,
          price: item.price || 0,
          change: item.change || 0,
          changePercent: item.changePercent || 0,
          score: item.score || 50,
          trend: item.trend || 'NEUTRAL',
          strategy: 'Stock',
          recommendation: undefined,
          regime: item.regime,
        }));
        setItems(list);
        setError(null);
      } catch (e: any) {
        setError(e.message);
      } finally {
        setLoading(false);
      }
    };

    fetchWatchlist();
    const interval = setInterval(fetchWatchlist, 60000);
    return () => clearInterval(interval);
  }, []);

  if (loading) {
    return (
      <div className="panel" style={{ marginTop: '15px' }}>
        <h3>🔭 Active Watchlist</h3>
        <div style={{ padding: '20px', textAlign: 'center', color: 'var(--text-dim)', fontSize: '13px' }}>
          Loading watchlist...
        </div>
      </div>
    );
  }

  if (error || items.length === 0) {
    return (
      <div className="panel" style={{ marginTop: '15px' }}>
        <h3>🔭 Active Watchlist</h3>
        <div style={{ padding: '20px', textAlign: 'center', color: 'var(--text-dim)', fontSize: '13px' }}>
          {error ? `⚠ ${error}` : 'No symbols configured'}
        </div>
      </div>
    );
  }

  const pinned = ['SPY', 'QQQ', 'IWM', 'DIA'];

  // Overlay live WS prices on REST baseline
  const enriched = items.map(item => {
    const live = marketData[item.symbol];
    if (live && live.price > 0) {
      return {
        ...item,
        price: live.price,
        change: live.change,
        changePercent: live.changePercent,
        score: live.score || item.score,
        trend: live.trend || item.trend,
        recommendation: live.recommendation,
        strategy: live.strategy || item.strategy,
      };
    }
    return item;
  });

  const sorted = [...enriched].sort((a, b) => {
    const aPinned = pinned.includes(a.symbol);
    const bPinned = pinned.includes(b.symbol);
    if (aPinned && !bPinned) return -1;
    if (!aPinned && bPinned) return 1;
    const sigOrder = (r?: string) => r === 'BUY' ? 0 : r === 'SELL' ? 1 : 2;
    const sigDiff = sigOrder(a.recommendation) - sigOrder(b.recommendation);
    if (sigDiff !== 0) return sigDiff;
    return (b.score || 0) - (a.score || 0);
  });

  return (
    <div className="panel" style={{ marginTop: '15px', border: 'none', boxShadow: 'none' }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '10px' }}>
        <h3 style={{ margin: 0 }}>🔭 Active Watchlist</h3>
        <span style={{ fontSize: '10px', color: 'var(--text-dim)', fontFamily: 'monospace' }}>
          {sorted.length} symbols
        </span>
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
            {sorted.map((item) => {
              const hasLivePrice = item.price > 0;
              const chgPct = item.changePercent ?? 0;
              return (
                <tr key={item.symbol} style={{ borderBottom: '1px solid var(--border-subtle)' }}>
                  <td style={{ padding: '8px', fontWeight: 600 }}>{item.symbol}</td>
                  <td style={{ padding: '8px', textAlign: 'right', fontFamily: 'monospace' }}>
                    {hasLivePrice
                      ? `$${item.price.toFixed(2)}`
                      : <span style={{ color: 'var(--text-dim)' }}>---</span>}
                  </td>
                  <td style={{ padding: '8px', textAlign: 'right' }}>
                    {chgPct !== 0 ? (
                      <span style={{ color: chgPct >= 0 ? 'var(--neon-green)' : 'var(--neon-red)' }}>
                        {chgPct >= 0 ? '▲' : '▼'} {Math.abs(chgPct).toFixed(2)}%
                      </span>
                    ) : (
                      <span style={{ color: 'var(--text-dim)' }}>--</span>
                    )}
                  </td>
                  <td style={{ padding: '8px' }}>
                    <span className={`status-tag ${item.recommendation === 'BUY' ? 'status-buy' : item.recommendation === 'SELL' ? 'status-sell' : 'status-hold'}`}>
                      {item.recommendation || 'HOLD'}
                    </span>
                  </td>
                  <td style={{ padding: '8px', color: 'var(--text-dim)', fontSize: '11px' }}>
                    {item.strategy || 'Scanning'}
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
    </div>
  );
};
