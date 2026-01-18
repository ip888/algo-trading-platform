import { useTradingStore } from '../store/tradingStore';
import { useEffect, useState } from 'react';
import { CONFIG } from '../config';

interface WatchlistItem {
  symbol: string;
  price?: number;
  type?: string;
  tradingHours?: string;
  change?: number;
  changePercent?: number;
  score?: number;
  trend?: string;
  regime?: string;
}

export const Watchlist = () => {
  const { marketData, updateMarketData } = useTradingStore();
  const [loading, setLoading] = useState(false);
  
  // Fetch initial watchlist data from REST API if WebSocket hasn't provided data
  useEffect(() => {
    const fetchWatchlist = async () => {
      if (Object.keys(marketData).length > 0) return; // Already have data
      
      setLoading(true);
      try {
        const res = await fetch(`${CONFIG.API_BASE_URL}/api/watchlist`);
        if (res.ok) {
          const data = await res.json();
          // Populate store with watchlist items
          data.watchlist?.forEach((item: WatchlistItem) => {
            updateMarketData(item.symbol, {
              symbol: item.symbol,
              price: item.price || 0,
              change: item.change || 0,
              changePercent: item.changePercent || 0,
              volume: 0,
              trend: item.trend || 'NEUTRAL',
              score: item.score || 50,
              strategy: item.type === 'CRYPTO' ? 'Crypto 24/7' : 'Stock',
              regime: item.regime,
            });
          });
        }
      } catch (e) {
        console.error('Failed to fetch watchlist', e);
      } finally {
        setLoading(false);
      }
    };
    
    fetchWatchlist();
    // Refresh every 60s as backup
    const interval = setInterval(fetchWatchlist, 60000);
    return () => clearInterval(interval);
  }, [marketData, updateMarketData]);
  
  // Convert map to array and sort by score or priority
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

  // Define pinned assets (Crypto/Indices) to always show at top
  const pinned = ['BTC/USD', 'ETH/USD', 'SOL/USD', 'SPY', 'QQQ'];
  
  const sortedAssets = [...assets].sort((a, b) => {
    const aPinned = pinned.includes(a.symbol);
    const bPinned = pinned.includes(b.symbol);
    if (aPinned && !bPinned) return -1;
    if (!aPinned && bPinned) return 1;
    return b.score - a.score; // High score first
  });

  return (
    <div className="panel" style={{ marginTop: '15px', border: 'none', boxShadow: 'none' }}>
      <h3>🔭 Active Watchlist</h3>
      <div className="table-container" style={{ maxHeight: '300px', overflowY: 'auto' }}>
        <table className="watchlist-table" style={{ width: '100%', fontSize: '12px', borderCollapse: 'collapse' }}>
          <thead>
            <tr style={{ color: 'var(--text-muted)', textAlign: 'left', borderBottom: '1px solid var(--border-light)' }}>
              <th style={{ padding: '8px' }}>Asset</th>
              <th style={{ padding: '8px' }}>Price</th>
              <th style={{ padding: '8px' }}>Sentiment</th>
              <th style={{ padding: '8px' }}>Strategy</th>
              <th style={{ padding: '8px' }}>Action</th>
            </tr>
          </thead>
          <tbody>
            {sortedAssets.map((data) => (
              <tr key={data.symbol} style={{ borderBottom: '1px solid var(--border-subtle)' }}>
                <td style={{ padding: '8px', fontWeight: 600 }}>
                  {data.symbol}
                  {data.symbol.includes('/') && <span className="badge badge-warning" style={{marginLeft: '4px', fontSize: '8px'}}>CRYPTO</span>}
                </td>
                <td style={{ padding: '8px' }}>${data.price?.toFixed(2) || '---'}</td>
                <td style={{ padding: '8px' }}>
                  <span style={{ 
                     color: (data.sentiment || 0) > 0.2 ? 'var(--neon-green)' : ((data.sentiment || 0) < -0.2 ? 'var(--neon-red)' : 'var(--text-muted)')
                  }}>
                    {((data.sentiment || 0) * 100).toFixed(0)}%
                  </span>
                </td>
                <td style={{ padding: '8px', color: 'var(--text-dim)' }}>{data.strategy || 'Scanning'}</td>
                <td style={{ padding: '8px' }}>
                   <span className={`status-tag ${data.recommendation === 'BUY' ? 'status-buy' : (data.recommendation === 'SELL' ? 'status-sell' : 'status-hold')}`}>
                     {data.recommendation || 'HOLD'}
                   </span>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
};
