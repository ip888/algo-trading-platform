import { useTradingStore } from '../store/tradingStore';
import { memo } from 'react';

const OrderHistoryComponent = () => {
  const { orderHistory } = useTradingStore();
  
  return (
    <div className="panel order-history">
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '15px' }}>
        <h2 style={{ margin: 0 }}>📜 Execution Archive</h2>
        <span style={{ fontSize: '10px', color: 'var(--text-dim)', background: 'var(--bg-deep)', padding: '2px 8px', borderRadius: '4px', border: '1px solid var(--border-dim)', fontFamily: 'monospace' }}>
          ENTRIES: {orderHistory.length}
        </span>
      </div>
      
      {orderHistory.length === 0 ? (
        <div className="scanning-state" style={{ padding: '30px', textAlign: 'center', color: 'var(--text-dim)', border: '1px dashed var(--text-muted)', borderRadius: 'var(--radius-sm)' }}>
          <p style={{ fontSize: '0.8rem' }}>🛰 NO ARCHIVED TRANSACTIONS...</p>
        </div>
      ) : (
        <div className="orders-list" style={{ maxHeight: '300px', overflowY: 'auto', background: 'var(--bg-deep)', padding: '10px', borderRadius: '4px', border: '1px solid var(--border-dim)' }}>
          {orderHistory.map((order, idx) => (
            <div key={idx} style={{ 
              display: 'flex', 
              justifyContent: 'space-between', 
              fontFamily: 'JetBrains Mono, monospace', 
              fontSize: '11px', 
              padding: '6px 0', 
              borderBottom: '1px solid rgba(255,255,255,0.02)' 
            }}>
              <div style={{ display: 'flex', gap: '10px' }}>
                <span style={{ color: 'var(--text-dim)' }}>[{new Date(order.timestamp).toLocaleTimeString([], { hour12: false })}]</span>
                <span style={{ color: order.side === 'buy' ? 'var(--neon-green)' : 'var(--neon-red)', fontWeight: 800 }}>{order.side.toUpperCase()}</span>
                <span style={{ fontWeight: 700 }}>{order.symbol}</span>
              </div>
              <div style={{ display: 'flex', gap: '15px' }}>
                <span style={{ color: 'var(--text-secondary)' }}>{order.quantity.toFixed(3)} @ ${order.price.toFixed(2)}</span>
                <span style={{ 
                  color: order.status === 'filled' ? 'var(--neon-green)' : 'var(--neon-amber)',
                  textTransform: 'uppercase',
                  fontWeight: 800
                }}>{order.status}</span>
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
};

export const OrderHistory = memo(OrderHistoryComponent);
