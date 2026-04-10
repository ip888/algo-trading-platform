import { useEffect, useRef, useState } from 'react';
import { useTradingStore } from '../store/tradingStore';
import { CONFIG } from '../config';

interface WatchlistItem {
  symbol: string;
  inPosition: boolean;
  isTarget: boolean;
  blocked: boolean;
  blockReason: string | null;
  inCooldown: boolean;
  cooldownMinutes: number;
  entryPrice?: number;
  unrealPct?: number;
  // price overlaid from WebSocket
  price?: number;
  changePercent?: number;
}

// Sort order: in-position first, then targets, then cooling, then blocked, then watching
function statusOrder(item: WatchlistItem): number {
  if (item.inPosition) return 0;
  if (item.isTarget)   return 1;
  if (item.inCooldown) return 2;
  if (item.blocked)    return 3;
  return 4;
}

export const Watchlist = () => {
  const [items, setItems]     = useState<WatchlistItem[]>([]);
  const [status, setStatus]   = useState<'loading' | 'ok' | 'error'>('loading');
  const [errorMsg, setError]  = useState('');
  const baseRef               = useRef<WatchlistItem[]>([]);

  const fetchWatchlist = async () => {
    try {
      const res  = await fetch(`${CONFIG.API_BASE_URL}/api/watchlist`);
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      const data = await res.json();
      const list: WatchlistItem[] = (data.watchlist || []).map((it: any) => ({
        symbol:          String(it.symbol),
        inPosition:      Boolean(it.inPosition),
        isTarget:        Boolean(it.isTarget),
        blocked:         Boolean(it.blocked),
        blockReason:     it.blockReason ?? null,
        inCooldown:      Boolean(it.inCooldown),
        cooldownMinutes: Number(it.cooldownMinutes) || 0,
        entryPrice:      it.entryPrice != null ? Number(it.entryPrice) : undefined,
        unrealPct:       it.unrealPct  != null ? Number(it.unrealPct)  : undefined,
      }));
      baseRef.current = list;
      setItems(list);
      setStatus('ok');
    } catch (e: any) {
      setError(String(e?.message || 'Unknown error'));
      setStatus('error');
    }
  };

  useEffect(() => {
    fetchWatchlist();
    const id = setInterval(fetchWatchlist, 15000);
    return () => clearInterval(id);
  }, []);

  // Overlay live WebSocket prices
  const marketData = useTradingStore(s => s.marketData);
  useEffect(() => {
    if (baseRef.current.length === 0) return;
    setItems(baseRef.current.map(item => {
      const live = marketData[item.symbol];
      return live && live.price > 0
        ? { ...item, price: live.price, changePercent: live.changePercent ?? 0 }
        : item;
    }));
  }, [marketData]);

  if (status === 'loading') return (
    <div className="panel" style={{ marginTop: '15px' }}>
      <h3>🔭 Active Watchlist</h3>
      <div style={{ padding: '20px', textAlign: 'center', color: 'var(--text-dim)', fontSize: '13px' }}>Loading…</div>
    </div>
  );

  if (status === 'error') return (
    <div className="panel" style={{ marginTop: '15px' }}>
      <h3>🔭 Active Watchlist</h3>
      <div style={{ padding: '20px', textAlign: 'center', color: 'var(--neon-red)', fontSize: '12px' }}>⚠ {errorMsg}</div>
    </div>
  );

  const sorted = [...items].sort((a, b) => statusOrder(a) - statusOrder(b) || a.symbol.localeCompare(b.symbol));

  const held    = sorted.filter(i => i.inPosition);
  const targets = sorted.filter(i => !i.inPosition && i.isTarget);
  const cooling = sorted.filter(i => !i.inPosition && !i.isTarget && i.inCooldown);
  const blocked = sorted.filter(i => !i.inPosition && !i.isTarget && !i.inCooldown && i.blocked);
  const watching = sorted.filter(i => !i.inPosition && !i.isTarget && !i.inCooldown && !i.blocked);

  const renderRow = (item: WatchlistItem) => {
    let badge: React.ReactNode;
    if (item.inPosition) {
      badge = <span style={badgeStyle('#22c55e')}>HELD</span>;
    } else if (item.isTarget) {
      badge = <span style={badgeStyle('#38bdf8')}>TARGET</span>;
    } else if (item.inCooldown) {
      badge = <span style={badgeStyle('#f97316')} title={`Cooldown expires in ${item.cooldownMinutes}m`}>COOL {item.cooldownMinutes}m</span>;
    } else if (item.blocked) {
      badge = <span style={badgeStyle('#ef4444')} title={item.blockReason ?? ''}>BLOCKED</span>;
    } else {
      badge = <span style={badgeStyle('#555')}>WATCH</span>;
    }

    const chg = item.changePercent ?? 0;
    return (
      <tr key={item.symbol} style={{ borderBottom: '1px solid #1e1e2e' }}>
        <td style={{ padding: '6px 8px', fontWeight: 700, fontSize: '12px' }}>{item.symbol}</td>
        <td style={{ padding: '6px 8px', textAlign: 'right', fontFamily: 'monospace', fontSize: '12px' }}>
          {item.price && item.price > 0
            ? `$${item.price.toFixed(2)}`
            : <span style={{ color: '#444' }}>—</span>}
        </td>
        <td style={{ padding: '6px 8px', textAlign: 'right', fontSize: '11px' }}>
          {chg !== 0
            ? <span style={{ color: chg >= 0 ? '#22c55e' : '#ef4444' }}>{chg >= 0 ? '▲' : '▼'} {Math.abs(chg).toFixed(2)}%</span>
            : <span style={{ color: '#444' }}>—</span>}
        </td>
        <td style={{ padding: '6px 8px' }}>{badge}</td>
        <td style={{ padding: '6px 8px', fontSize: '10px', maxWidth: '150px' }}>
          {item.inPosition && item.entryPrice != null ? (
            <span style={{ color: item.unrealPct != null && item.unrealPct >= 0 ? '#22c55e' : '#ef4444', fontFamily: 'monospace' }}>
              entry ${item.entryPrice.toFixed(2)}
              {item.unrealPct != null && (
                <> ({item.unrealPct >= 0 ? '+' : ''}{item.unrealPct.toFixed(2)}%)</>
              )}
            </span>
          ) : item.inCooldown ? (
            <span style={{ color: '#f97316' }}>re-entry in {item.cooldownMinutes}m</span>
          ) : item.blocked && item.blockReason ? (
            <span title={item.blockReason} style={{ color: '#888' }}>
              {item.blockReason.length > 28 ? item.blockReason.slice(0, 28) + '…' : item.blockReason}
            </span>
          ) : item.isTarget ? (
            <span style={{ color: '#38bdf8' }}>gate open</span>
          ) : (
            <span style={{ color: '#333' }}>—</span>
          )}
        </td>
      </tr>
    );
  };

  const sectionLabel = (text: string, count: number, color: string) => (
    <tr>
      <td colSpan={5} style={{ padding: '5px 8px 2px', fontSize: '10px', color, fontWeight: 700, letterSpacing: '0.05em', background: '#0d0d1a' }}>
        {text} ({count})
      </td>
    </tr>
  );

  return (
    <div className="panel" style={{ marginTop: '15px', border: 'none', boxShadow: 'none' }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '10px' }}>
        <h3 style={{ margin: 0 }}>🔭 Active Watchlist</h3>
        <span style={{ fontSize: '10px', color: '#555', fontFamily: 'monospace' }}>{items.length} symbols</span>
      </div>
      <div style={{ maxHeight: '360px', overflowY: 'auto' }}>
        <table style={{ width: '100%', fontSize: '12px', borderCollapse: 'collapse' }}>
          <thead>
            <tr style={{ color: '#555', fontSize: '10px', borderBottom: '1px solid #1e1e2e' }}>
              <th style={{ padding: '4px 8px', textAlign: 'left' }}>SYMBOL</th>
              <th style={{ padding: '4px 8px', textAlign: 'right' }}>PRICE</th>
              <th style={{ padding: '4px 8px', textAlign: 'right' }}>CHG%</th>
              <th style={{ padding: '4px 8px', textAlign: 'left' }}>STATUS</th>
              <th style={{ padding: '4px 8px', textAlign: 'left' }}>DETAIL</th>
            </tr>
          </thead>
          <tbody>
            {held.length > 0    && <>{sectionLabel('IN POSITION', held.length,    '#22c55e')}{held.map(renderRow)}</>}
            {targets.length > 0 && <>{sectionLabel('TARGETS',     targets.length, '#38bdf8')}{targets.map(renderRow)}</>}
            {cooling.length > 0 && <>{sectionLabel('COOLING DOWN', cooling.length, '#f97316')}{cooling.map(renderRow)}</>}
            {blocked.length > 0 && <>{sectionLabel('BLOCKED',     blocked.length, '#ef4444')}{blocked.map(renderRow)}</>}
            {watching.length > 0 && <>{sectionLabel('WATCHING',   watching.length, '#555')}{watching.map(renderRow)}</>}
          </tbody>
        </table>
      </div>
      {items.every(i => !i.price) && items.length > 0 && (
        <div style={{ textAlign: 'center', fontSize: '10px', color: '#333', marginTop: '6px', fontStyle: 'italic' }}>
          Prices stream during market hours
        </div>
      )}
    </div>
  );
};

function badgeStyle(color: string): React.CSSProperties {
  return {
    display: 'inline-block',
    padding: '1px 7px',
    borderRadius: '10px',
    fontSize: '10px',
    fontWeight: 700,
    color,
    background: color + '22',
    border: `1px solid ${color}44`,
    whiteSpace: 'nowrap',
  };
}
