import { useTradingStore } from '../store/tradingStore';
import { Tooltip } from './Tooltip';

export const AssetRankings = () => {
  const { marketData } = useTradingStore();
  
  const assets = Object.values(marketData).sort((a, b) => b.score - a.score);
  
  // Group by profile
  const mainSymbols = ['SH', 'PSQ', 'RWM', 'DOG', 'SEF', 'ERY', 'REK', 'RXD', 'SIJ', 'SZK'];
  const expSymbols = ['VXX', 'UVXY', 'VIXY', 'SVXY', 'ZSL', 'TBT', 'SDP', 'FAZ', 'TZA', 'YANG', 'BIS', 'SCC', 'SSG', 'MYY', 'EUM', 'SRS'];
  
  const mainAssets = assets.filter(a => mainSymbols.includes(a.symbol));
  const expAssets = assets.filter(a => expSymbols.includes(a.symbol));
  
  // Check if market is open
  const isMarketOpen = () => {
    const est = new Date().toLocaleString('en-US', { timeZone: 'America/New_York' });
    const estDate = new Date(est);
    const hours = estDate.getHours();
    const day = estDate.getDay();
    if (day === 0 || day === 6) return false;
    if (hours < 9 || hours >= 16) return false;
    if (hours === 9 && estDate.getMinutes() < 30) return false;
    return true;
  };
  
  const marketOpen = isMarketOpen();
  
  if (assets.length === 0) {
    return (
      <div className="panel">
        <h2>🏆 Asset Rankings</h2>
        <p>Waiting for market data...</p>
      </div>
    );
  }
  
  const renderAssetGroup = (groupAssets: typeof assets, title: string, color: string) => (
    <div style={{ marginBottom: '16px' }}>
      <h3 style={{ fontSize: '0.9rem', color, marginBottom: '8px', fontWeight: 600 }}>
        {title} ({groupAssets.length})
      </h3>
      <div className="asset-grid" style={{ gridTemplateColumns: 'repeat(auto-fill, minmax(140px, 1fr))' }}>
        {groupAssets.slice(0, 6).map((asset) => (
          <div key={asset.symbol} className="asset-card" style={{ padding: '8px' }}>
            <div className="asset-symbol" style={{ fontSize: '0.85rem' }}>
              {asset.symbol}
              <span className="asset-trend">{asset.trend}</span>
            </div>
            
            <div className="asset-price" style={{ fontSize: '0.8rem' }}>${asset.price.toFixed(2)}</div>
            
            <div className={`asset-change ${asset.change >= 0 ? 'positive' : 'negative'}`} style={{ fontSize: '0.75rem' }}>
              {asset.change >= 0 ? '+' : ''}{asset.change.toFixed(2)} ({asset.changePercent.toFixed(2)}%)
            </div>
            
            <div className="asset-score" style={{ fontSize: '0.75rem' }}>
              Score: <strong>{asset.score.toFixed(0)}</strong>
            </div>
          </div>
        ))}
      </div>
    </div>
  );
  
  return (
    <div className="panel">
      <h2>
        🏆 Asset Rankings by Profile
        <Tooltip text="Top 6 symbols per profile ranked by algorithmic score. Bot trades highest-scoring symbols." />
      </h2>
      
      {!marketOpen && (
        <div className="stale-data-warning">
          ⚠️ Market closed - Scores from last session
        </div>
      )}
      
      {renderAssetGroup(mainAssets, '📊 MAIN Profile', '#3b82f6')}
      {renderAssetGroup(expAssets, '🧪 EXPERIMENTAL Profile', '#a855f7')}
    </div>
  );
};
