import { useEffect, useState } from 'react';
import { useTradingStore } from '../store/tradingStore';

// Helper to safely convert values that may be strings to numbers
const toNumber = (val: unknown): number => {
  if (typeof val === 'number') return val;
  if (typeof val === 'string') return parseFloat(val) || 0;
  return 0;
};

export const PositionsTable = () => {
  const { positions, setPositions, marketData, connected } = useTradingStore();
  const [isMobile, setIsMobile] = useState(window.innerWidth < 768);
  const [isLoading, setIsLoading] = useState(true);
  const [_lastRefresh, setLastRefresh] = useState<number>(Date.now());

  // Fetch positions via REST on mount and periodically (fallback for WebSocket)
  useEffect(() => {
    const fetchPositions = async () => {
      try {
        const res = await fetch('http://localhost:8080/api/positions');
        if (res.ok) {
          const data = await res.json();
          setPositions(data);
          setLastRefresh(Date.now());
        }
      } catch (e) {
        console.warn('Failed to fetch positions:', e);
      } finally {
        setIsLoading(false);
      }
    };
    
    fetchPositions();
    // Refresh every 15 seconds during market hours
    const interval = setInterval(fetchPositions, 15000);
    return () => clearInterval(interval);
  }, [setPositions]);

  useEffect(() => {
    const handleResize = () => setIsMobile(window.innerWidth < 768);
    window.addEventListener('resize', handleResize);
    return () => window.removeEventListener('resize', handleResize);
  }, []);

  // Loading state
  if (isLoading) {
    return (
      <div className="panel" style={{ border: 'none', boxShadow: 'none' }}>
        <h3>🛰 Active Deployment Matrix</h3>
        <div style={{ padding: '40px', textAlign: 'center', color: 'var(--text-dim)' }}>
          <div style={{ marginBottom: '10px', fontSize: '24px' }}>⏳</div>
          Loading positions from Alpaca...
        </div>
      </div>
    );
  }

  if (!positions || positions.length === 0) {
    return (
      <div className="panel" style={{ border: 'none', boxShadow: 'none' }}>
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
          <h3>🛰 Active Deployment Matrix</h3>
          <span style={{ fontSize: '10px', color: 'var(--text-muted)', opacity: 0.6 }}>
            {connected ? '🟢 LIVE' : '🔴 OFFLINE'}
          </span>
        </div>
        <div style={{ padding: '40px', textAlign: 'center', color: 'var(--text-dim)', fontStyle: 'italic', border: '1px dashed var(--border-strong)', borderRadius: 'var(--radius-sm)', background: 'var(--bg-main)' }}>
          <div style={{ marginBottom: '10px', fontSize: '24px' }}>📡</div>
          No active positions
          <div style={{ fontSize: '11px', marginTop: '10px', color: 'var(--text-muted)' }}>Positions will appear when trades are executed</div>
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
            
            // Use Alpaca's authoritative P&L if available
            const pnl = pos.unrealized_pl !== undefined ? toNumber(pos.unrealized_pl) : (marketPrice - entryPrice) * quantity;
            const pnlPercent = pos.unrealized_plpc !== undefined ? toNumber(pos.unrealized_plpc) * 100 : (entryPrice > 0 ? ((marketPrice - entryPrice) / entryPrice) * 100 : 0);
            
            const mobileBroker = (pos as any).broker || 'alpaca';
            return (
              <div key={pos.symbol} className="overview-item" style={{ marginBottom: '12px', border: '1px solid var(--border-light)' }}>
                <div className="position-header" style={{ display: 'flex', justifyContent: 'space-between', borderBottom: '1px solid var(--border-light)', paddingBottom: '8px', marginBottom: '8px' }}>
                  <div style={{ display: 'flex', alignItems: 'center', gap: '6px' }}>
                    <span className="font-bold">{pos.symbol}</span>
                    <span style={{
                      fontSize: '9px',
                      fontWeight: 700,
                      padding: '1px 5px',
                      borderRadius: '3px',
                      background: mobileBroker === 'tradier' ? '#22c55e22' : '#3b82f622',
                      color: mobileBroker === 'tradier' ? '#22c55e' : '#3b82f6',
                      border: `1px solid ${mobileBroker === 'tradier' ? '#22c55e44' : '#3b82f644'}`
                    }}>
                      {mobileBroker.toUpperCase()}
                    </span>
                  </div>
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
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '12px' }}>
        <h3 style={{ margin: 0 }}>🛰 Active Deployment Matrix</h3>
        <div style={{ display: 'flex', gap: '12px', alignItems: 'center' }}>
          <span style={{ fontSize: '10px', color: 'var(--text-muted)', fontFamily: 'monospace' }}>
            {positions.length} ACTIVE
          </span>
          <span style={{ fontSize: '10px', color: connected ? 'var(--positive)' : 'var(--negative)' }}>
            {connected ? '🟢 LIVE' : '🔴 OFFLINE'}
          </span>
        </div>
      </div>
      
      <div className="table-container" style={{ padding: 0 }}>
        <table className="positions-table">
          <thead>
            <tr>
              <th>Identification</th>
              <th>Broker</th>
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
              
              // Use Alpaca's authoritative P&L if available (avoids mismatched local calc)
              const pnl = pos.unrealized_pl !== undefined ? toNumber(pos.unrealized_pl) : (marketPrice - entryPrice) * quantity;
              const pnlPercent = pos.unrealized_plpc !== undefined ? toNumber(pos.unrealized_plpc) * 100 : (entryPrice > 0 ? ((marketPrice - entryPrice) / entryPrice) * 100 : 0);
              
              // Calculate distance to TP/SL
              const distanceToTP = takeProfit > 0 ? ((takeProfit - marketPrice) / marketPrice * 100).toFixed(2) : null;
              const distanceToSL = stopLoss > 0 ? ((marketPrice - stopLoss) / marketPrice * 100).toFixed(2) : null;
              
              const broker = (pos as any).broker || 'alpaca';
              return (
                <tr key={pos.symbol}>
                  <td className="font-bold">
                    {pos.symbol}
                  </td>
                  <td>
                    <span style={{
                      fontSize: '10px',
                      fontWeight: 700,
                      padding: '2px 6px',
                      borderRadius: '4px',
                      background: broker === 'tradier' ? '#22c55e22' : '#3b82f622',
                      color: broker === 'tradier' ? '#22c55e' : '#3b82f6',
                      border: `1px solid ${broker === 'tradier' ? '#22c55e44' : '#3b82f644'}`
                    }}>
                      {broker.toUpperCase()}
                      {broker === 'tradier' && <span style={{ fontSize: '8px', marginLeft: '3px', color: '#f59e0b' }}>SBX</span>}
                    </span>
                  </td>
                  <td>{quantity.toFixed(4)}</td>
                  <td>${entryPrice.toFixed(2)}</td>
                  <td>${marketPrice.toFixed(2)}</td>
                  <td className={pnl >= 0 ? 'positive' : 'negative'} style={{ fontWeight: 600 }}>
                    {pnl >= 0 ? '+' : ''}${pnl.toFixed(2)} ({pnlPercent.toFixed(2)}%)
                  </td>
                  <td style={{ fontSize: '11px' }}>
                    {stopLoss > 0 || takeProfit > 0 ? (
                      <>
                        <span style={{ color: 'var(--negative)' }}>SL: ${stopLoss.toFixed(2)}</span>
                        {distanceToSL && <span style={{ color: 'var(--text-muted)', fontSize: '9px' }}> ({distanceToSL}%↓)</span>}
                        <span style={{ color: 'var(--text-dim)' }}> | </span>
                        <span style={{ color: 'var(--positive)' }}>TP: ${takeProfit.toFixed(2)}</span>
                        {distanceToTP && <span style={{ color: 'var(--text-muted)', fontSize: '9px' }}> ({distanceToTP}%↑)</span>}
                        <span style={{ color: 'var(--positive)', marginLeft: '8px', fontSize: '10px' }}>✓ Protected</span>
                      </>
                    ) : (
                      <span style={{
                        color: '#ff9800',
                        fontWeight: 500,
                        display: 'inline-flex',
                        alignItems: 'center',
                        gap: '4px',
                        background: 'rgba(255, 152, 0, 0.1)',
                        padding: '2px 6px',
                        borderRadius: '4px'
                      }}>
                        ⚠️ No Bracket
                        <span style={{ fontSize: '9px', color: 'var(--text-muted)', fontWeight: 400 }}>
                          (Client-side SL/TP)
                        </span>
                      </span>
                    )}
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
