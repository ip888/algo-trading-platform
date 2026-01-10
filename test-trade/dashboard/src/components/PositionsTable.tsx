import { useEffect, useState } from 'react';
import { useTradingStore } from '../store/tradingStore';

// Helper to safely convert values that may be strings to numbers
const toNumber = (val: unknown): number => {
  if (typeof val === 'number') return val;
  if (typeof val === 'string') return parseFloat(val) || 0;
  return 0;
};

export const PositionsTable = () => {
  const { positions, marketData } = useTradingStore();
  const [isMobile, setIsMobile] = useState(window.innerWidth < 768);

  useEffect(() => {
    const handleResize = () => setIsMobile(window.innerWidth < 768);
    window.addEventListener('resize', handleResize);
    return () => window.removeEventListener('resize', handleResize);
  }, []);

  if (!positions || positions.length === 0) {
    return (
      <div className="panel" style={{ border: 'none', boxShadow: 'none' }}>
        <h3>🛰 Active Deployment Matrix</h3>
        <div style={{ padding: '40px', textAlign: 'center', color: 'var(--text-dim)', fontStyle: 'italic', border: '1px dashed var(--border-strong)', borderRadius: 'var(--radius-sm)', background: 'var(--bg-main)' }}>
          <div style={{ marginBottom: '10px', fontSize: '24px' }}>📡</div>
          Scanning market for entry opportunities...
          <div style={{ fontSize: '11px', marginTop: '10px', color: 'var(--text-muted)' }}>[ WATCHLIST: QQQ, NVDA, SPY, TSLA ]</div>
        </div>
      </div>
    );
  }

  // Mobile Card View
  if (isMobile) {
    return (
      <div className="panel" style={{ border: 'none', boxShadow: 'none' }}>
        <h3>🛰 Active Deployment Matrix</h3>
        
        <div className="positions-mobile">
          {positions.map((pos) => {
            const quantity = toNumber(pos.quantity);
            const entryPrice = toNumber(pos.entryPrice);
            const currentPrice = toNumber(pos.currentPrice);
            const marketPrice = marketData[pos.symbol]?.price || currentPrice;
            const pnl = (marketPrice - entryPrice) * quantity;
            const pnlPercent = entryPrice > 0 ? ((marketPrice - entryPrice) / entryPrice) * 100 : 0;
            
            return (
              <div key={pos.symbol} className="overview-item" style={{ marginBottom: '12px', border: '1px solid var(--border-light)' }}>
                <div className="position-header" style={{ display: 'flex', justifyContent: 'space-between', borderBottom: '1px solid var(--border-light)', paddingBottom: '8px', marginBottom: '8px' }}>
                  <span className="font-bold">{pos.symbol}</span>
                  <span className={pnl >= 0 ? 'positive' : 'negative'} style={{ fontWeight: 600 }}>
                    {pnl >= 0 ? '+' : ''}{pnlPercent.toFixed(2)}%
                  </span>
                </div>
                <div style={{ fontSize: '13px', display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '8px' }}>
                  <div style={{ color: 'var(--text-muted)' }}>QTY: {quantity.toFixed(4)}</div>
                  <div style={{ color: 'var(--text-muted)' }}>PROFIT: ${pnl.toFixed(2)}</div>
                </div>
              </div>
            );
          })}
        </div>
      </div>
    );
  }

  // Desktop Terminal View
  return (
    <div className="panel" style={{ border: 'none', boxShadow: 'none' }}>
      <h3>🛰 Active Deployment Matrix</h3>
      
      <div className="table-container" style={{ padding: 0 }}>
        <table className="positions-table">
          <thead>
            <tr>
              <th>Identification</th>
              <th>Quantity</th>
              <th>Reference</th>
              <th>Current</th>
              <th>Net P&L</th>
              <th>Safety Bounds</th>
            </tr>
          </thead>
          <tbody>
            {positions.map((pos) => {
              const quantity = toNumber(pos.quantity);
              const entryPrice = toNumber(pos.entryPrice);
              const currentPrice = toNumber(pos.currentPrice);
              const stopLoss = toNumber(pos.stopLoss);
              const takeProfit = toNumber(pos.takeProfit);
              const marketPrice = marketData[pos.symbol]?.price || currentPrice;
              const pnl = (marketPrice - entryPrice) * quantity;
              const pnlPercent = entryPrice > 0 ? ((marketPrice - entryPrice) / entryPrice) * 100 : 0;
              
              return (
                <tr key={pos.symbol}>
                  <td className="font-bold">{pos.symbol}</td>
                  <td>{quantity.toFixed(4)}</td>
                  <td>${entryPrice.toFixed(2)}</td>
                  <td>${marketPrice.toFixed(2)}</td>
                  <td className={pnl >= 0 ? 'positive' : 'negative'} style={{ fontWeight: 600 }}>
                    {pnl >= 0 ? '+' : ''}${pnl.toFixed(2)} ({pnlPercent.toFixed(2)}%)
                  </td>
                  <td style={{ fontSize: '11px', color: 'var(--text-dim)' }}>
                    SL: ${stopLoss.toFixed(2)} | TP: ${takeProfit.toFixed(2)}
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
