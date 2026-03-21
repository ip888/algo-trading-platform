import { useTradingStore } from '../store/tradingStore';
import { memo, useEffect, useState } from 'react';
import { CONFIG } from '../config';

interface RestTrade {
  symbol: string;
  entryTime?: string;
  exitTime?: string;
  entryPrice?: number;
  exitPrice?: number;
  quantity?: number;
  pnl?: number;
  strategy?: string;
}

const OrderHistoryComponent = () => {
  const { orderHistory } = useTradingStore();
  const [restTrades, setRestTrades] = useState<RestTrade[]>([]);

  // Fetch recent closed trades from REST on mount as fallback
  useEffect(() => {
    const fetchTrades = async () => {
      try {
        const res = await fetch(`${CONFIG.API_BASE_URL}/api/trades/recent?limit=20`);
        if (res.ok) {
          const data = await res.json();
          setRestTrades(Array.isArray(data) ? data : []);
        }
      } catch (e) {
        console.error('Failed to fetch trade history', e);
      }
    };
    fetchTrades();
    const interval = setInterval(fetchTrades, 30000);
    return () => clearInterval(interval);
  }, []);

  // Show WS live orders if present, otherwise fall back to REST trades
  const hasLiveOrders = orderHistory.length > 0;

  return (
    <div className="panel order-history">
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '15px' }}>
        <h2 style={{ margin: 0 }}>📜 Execution Archive</h2>
        <span style={{ fontSize: '10px', color: 'var(--text-dim)', background: 'var(--bg-deep)', padding: '2px 8px', borderRadius: '4px', border: '1px solid var(--border-dim)', fontFamily: 'monospace' }}>
          {hasLiveOrders ? `LIVE · ${orderHistory.length}` : `DB · ${restTrades.length}`}
        </span>
      </div>

      {!hasLiveOrders && restTrades.length === 0 ? (
        <div className="scanning-state" style={{ padding: '30px', textAlign: 'center', color: 'var(--text-dim)', border: '1px dashed var(--text-muted)', borderRadius: 'var(--radius-sm)' }}>
          <p style={{ fontSize: '0.8rem' }}>🛰 NO ARCHIVED TRANSACTIONS...</p>
        </div>
      ) : hasLiveOrders ? (
        // Live WebSocket orders (intraday)
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
      ) : (
        // REST fallback: closed trades from DB
        <div className="orders-list" style={{ maxHeight: '300px', overflowY: 'auto', background: 'var(--bg-deep)', padding: '10px', borderRadius: '4px', border: '1px solid var(--border-dim)' }}>
          {restTrades.map((trade, idx) => {
            const pnl = typeof trade.pnl === 'number' ? trade.pnl : null;
            const time = trade.exitTime || trade.entryTime;
            const displayTime = time ? new Date(time).toLocaleString([], { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit', hour12: false }) : '---';
            return (
              <div key={idx} style={{
                display: 'flex',
                justifyContent: 'space-between',
                fontFamily: 'JetBrains Mono, monospace',
                fontSize: '11px',
                padding: '6px 0',
                borderBottom: '1px solid rgba(255,255,255,0.02)'
              }}>
                <div style={{ display: 'flex', gap: '10px' }}>
                  <span style={{ color: 'var(--text-dim)' }}>[{displayTime}]</span>
                  <span style={{ fontWeight: 700 }}>{trade.symbol}</span>
                </div>
                <div style={{ display: 'flex', gap: '12px', alignItems: 'center' }}>
                  {trade.entryPrice != null && trade.exitPrice != null && (
                    <span style={{ color: 'var(--text-dim)' }}>
                      ${trade.entryPrice.toFixed(2)} → ${trade.exitPrice.toFixed(2)}
                    </span>
                  )}
                  {pnl !== null && (
                    <span style={{
                      fontWeight: 800,
                      color: pnl >= 0 ? 'var(--neon-green)' : 'var(--neon-red)'
                    }}>
                      {pnl >= 0 ? '+' : ''}{pnl.toFixed(2)}
                    </span>
                  )}
                </div>
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
};

export const OrderHistory = memo(OrderHistoryComponent);
