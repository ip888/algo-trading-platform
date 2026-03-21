import { useTradingStore } from '../store/tradingStore';
import { useEffect, useState } from 'react';
import { CONFIG } from '../config';

interface WatchlistItem {
  symbol: string;
  price?: number;
  type?: string;
  change?: number;
  changePercent?: number;
  score?: number;
  trend?: string;
  regime?: string;
}

export const Watchlist = () => {
  const { marketData, updateMarketData } = useTradingStore();
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    const fetchWatchlist = async () => {
      // Only show loading spinner on first load
      if (Object.keys(marketData).length === 0) setLoading(true);
      try {
        const res = await fetch(`${CONFIG.API_BASE_URL}/api/watchlist`);
        if (res.ok) {
          const data = await res.json();
          data.watchlist?.forEach((item: WatchlistItem) => {
            // Only populate if no live WS data for this symbol yet
            if (!marketData[item.symbol] || marketData[item.symbol].price === 0) {
              updateMarketData(item.symbol, {
                symbol: item.symbol,
                price: item.price || 0,
                change: item.change || 0,
                changePercent: item.changePercent || 0,
                volume: 0,
                trend: item.trend || 'NEUTRAL',
                score: item.score || 50,
                strategy: 'Stock',
                regime: item.regime,
              });
            }
          });
        }
      } catch (e) {
        console.error('Failed to fetch watchlist', e);
      } finally {
        setLoading(false);
      }
    };

    fetchWatchlist();
    // Refresh every 60s — but don't put marketData in deps to avoid interval spam
    const interval = setInterval(fetchWatchlist, 60000);
    return () => clearInterval(interval);
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  const assets = Object.values(marketData || {});

  if (assets.length === 0) {
    return (
      <div className="panel" style={{ marginTop: '15px' }}>
        <h3>🔭 Active Watchlist</h3>
        <div style={{ padding: '20px', textAlign: 'center', color: 'var(--text-dim)', fontSize: '13px' }}>
          {loading ? 'Loading watchlist...' : 'Waiting for market data...'}
        </div>
      </div>
    );
  }

  const pinned = ['SPY', 'QQQ', 'IWM', 'DIA'];

  const sortedAssets = [...assets].sort((a, b) => {
    const aPinned = pinned.includes(a.symbol);
    const bPinned = pinned.includes(b.symbol);
    if (aPinned && !bPinned) return -1;
    if (!aPinned && bPinned) return 1;
    // BUY signals first, SELL second, HOLD last
    const sigOrder = (r?: string) => r === 'BUY' ? 0 : r === 'SELL' ? 1 : 2;
    const sigDiff = sigOrder(a.recommendation) - sigOrder(b.recommendation);
    if (sigDiff !== 0) return sigDiff;
    return (b.score || 0) - (a.score || 0);
  });

  return (
    <div className="panel" style={{ marginTop: '15px', border: 'none', boxShadow: 'none' }}>
      <h3>🔭 Active Watchlist</h3>
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
            {sortedAssets.map((data) => {
              const hasLivePrice = data.price > 0;
              const chgPct = data.changePercent ?? 0;
              return (
                <tr key={data.symbol} style={{ borderBottom: '1px solid var(--border-subtle)' }}>
                  <td style={{ padding: '8px', fontWeight: 600 }}>{data.symbol}</td>
                  <td style={{ padding: '8px', textAlign: 'right', fontFamily: 'monospace' }}>
                    {hasLivePrice ? `$${data.price.toFixed(2)}` : <span style={{ color: 'var(--text-dim)' }}>---</span>}
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
                    <span className={`status-tag ${data.recommendation === 'BUY' ? 'status-buy' : (data.recommendation === 'SELL' ? 'status-sell' : 'status-hold')}`}>
                      {data.recommendation || 'HOLD'}
                    </span>
                  </td>
                  <td style={{ padding: '8px', color: 'var(--text-dim)', fontSize: '11px' }}>
                    {data.strategy || 'Scanning'}
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
