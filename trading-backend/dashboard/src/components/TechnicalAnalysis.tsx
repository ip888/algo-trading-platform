import { useEffect, useState } from 'react';
import { useTradingStore } from '../store/tradingStore';
import { CONFIG } from '../config';

interface TargetEntry {
  symbol: string;
  inPosition: boolean;
  blocked: boolean;
  blockReason: string | null;
  inCooldown: boolean;
  cooldownMinutes: number;
  entryPrice?: number;
  unrealPct?: number;
  price?: number;
  changePercent?: number;
}

export const TechnicalAnalysis = () => {
  const [targets, setTargets]     = useState<TargetEntry[]>([]);
  const [allCount, setAllCount]   = useState(0);
  const [vix, setVix]             = useState(0);
  const [regime, setRegime]       = useState('UNKNOWN');
  const [loading, setLoading]     = useState(true);
  const [updatedAt, setUpdatedAt] = useState(0);

  const { marketData, systemStatus } = useTradingStore();

  const fetchData = async () => {
    try {
      const res  = await fetch(`${CONFIG.API_BASE_URL}/api/watchlist`);
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      const data = await res.json();

      setVix(Number(data.vix) || 0);
      setRegime(String(data.regime || 'UNKNOWN'));
      setAllCount((data.watchlist || []).length);

      const list: TargetEntry[] = (data.watchlist || [])
        .filter((it: any) => it.isTarget || it.inPosition)
        .map((it: any) => ({
          symbol:          String(it.symbol),
          inPosition:      Boolean(it.inPosition),
          blocked:         Boolean(it.blocked),
          blockReason:     it.blockReason ?? null,
          inCooldown:      Boolean(it.inCooldown),
          cooldownMinutes: Number(it.cooldownMinutes) || 0,
          entryPrice:      it.entryPrice != null ? Number(it.entryPrice) : undefined,
          unrealPct:       it.unrealPct  != null ? Number(it.unrealPct)  : undefined,
        }));
      setTargets(list);
      setUpdatedAt(Date.now());
    } catch (e) {
      console.error('EntryScanner fetch failed', e);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchData();
    const id = setInterval(fetchData, 15000);
    return () => clearInterval(id);
  }, []);

  // Overlay live WS prices
  const enriched = targets.map(t => {
    const live = marketData[t.symbol];
    return live && live.price > 0
      ? { ...t, price: live.price, changePercent: live.changePercent ?? 0 }
      : t;
  });

  const displayVix    = systemStatus?.vix || vix;
  const displayRegime = systemStatus?.marketTrend || regime;
  const isBearish     = displayRegime.includes('BEAR');
  const vixColor      = displayVix === 0 ? '#555' : displayVix >= 25 ? '#ef4444' : displayVix >= 16 ? '#eab308' : '#22c55e';
  const regimeColor   = isBearish ? '#eab308' : '#22c55e';

  // Entry gate status for each target
  const getGateStatus = (t: TargetEntry): { label: string; color: string; detail: string } => {
    if (t.inPosition)  return { label: 'IN POSITION', color: '#22c55e', detail: 'Currently holding' };
    if (t.inCooldown)  return { label: `COOL ${t.cooldownMinutes}m`, color: '#f97316', detail: `Cooldown — re-entry in ${t.cooldownMinutes}m` };
    if (t.blocked)     return { label: 'BLOCKED', color: '#ef4444', detail: t.blockReason ?? 'Entry blocked' };
    return { label: 'READY', color: '#38bdf8', detail: 'Entry gate open' };
  };

  return (
    <div className="panel technical-analysis">
      {/* Header */}
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '12px' }}>
        <h2 style={{ margin: 0, fontSize: '14px' }}>🎯 Entry Scanner</h2>
        <div style={{ display: 'flex', gap: '12px', fontSize: '10px', fontFamily: 'monospace' }}>
          <span>
            <span style={{ color: '#555' }}>VIX </span>
            <b style={{ color: vixColor }}>{displayVix > 0 ? displayVix.toFixed(1) : '—'}</b>
          </span>
          <span>
            <span style={{ color: '#555' }}>REGIME </span>
            <b style={{ color: regimeColor }}>{isBearish ? '🐻' : '🐂'} {displayRegime.replace('_', ' ')}</b>
          </span>
        </div>
      </div>

      {loading ? (
        <div style={{ padding: '20px', textAlign: 'center', color: '#555', fontSize: '13px' }}>Loading…</div>
      ) : enriched.length === 0 ? (
        <div style={{ padding: '24px 12px', textAlign: 'center', border: '1px dashed #333', borderRadius: '6px' }}>
          <div style={{ fontSize: '13px', color: '#555', marginBottom: '6px' }}>No active targets</div>
          <div style={{ fontSize: '11px', color: '#444' }}>
            {allCount} symbols in watchlist — bot selects targets based on VIX regime each cycle
          </div>
        </div>
      ) : (
        <div>
          <div style={{ fontSize: '10px', color: '#555', marginBottom: '8px' }}>
            {enriched.length} active target{enriched.length !== 1 ? 's' : ''} — entry gate status
          </div>
          <div style={{ display: 'flex', flexDirection: 'column', gap: '5px' }}>
            {enriched.map(t => {
              const gate  = getGateStatus(t);
              const chg   = t.changePercent ?? 0;
              const pnl   = t.unrealPct ?? 0;
              const pnlColor = pnl >= 0 ? '#22c55e' : '#ef4444';
              return (
                <div key={t.symbol} style={{
                  background: '#0d0d1a',
                  borderRadius: '6px',
                  padding: '7px 10px',
                  borderLeft: `3px solid ${gate.color}`,
                }}>
                  {/* Top row: symbol + live price + badge */}
                  <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
                    <span style={{
                      fontWeight: 700, fontSize: '12px', minWidth: '52px',
                      padding: '2px 6px', borderRadius: '4px',
                      background: gate.color + '18', border: `1px solid ${gate.color}44`,
                      color: gate.color, letterSpacing: '0.04em', textAlign: 'center',
                    }}>{t.symbol}</span>

                    {/* Live price (WS) or dash */}
                    <span style={{ fontFamily: 'monospace', fontSize: '12px', color: '#ccc' }}>
                      {t.price && t.price > 0
                        ? `$${t.price.toFixed(2)}`
                        : <span style={{ color: '#444' }}>—</span>}
                    </span>

                    {/* Intraday change % from WS */}
                    {chg !== 0 && (
                      <span style={{ fontSize: '11px', color: chg >= 0 ? '#22c55e' : '#ef4444' }}>
                        {chg >= 0 ? '▲' : '▼'}{Math.abs(chg).toFixed(2)}%
                      </span>
                    )}

                    {/* Gate badge */}
                    <span style={{
                      marginLeft: 'auto',
                      padding: '2px 8px',
                      borderRadius: '10px',
                      fontSize: '10px',
                      fontWeight: 700,
                      color: gate.color,
                      background: gate.color + '22',
                      border: `1px solid ${gate.color}44`,
                      whiteSpace: 'nowrap',
                    }}>
                      {gate.label}
                    </span>
                  </div>

                  {/* Bottom row: context-sensitive detail */}
                  <div style={{ marginTop: '3px', fontSize: '10px', color: '#666', display: 'flex', gap: '12px' }}>
                    {t.inPosition && t.entryPrice != null && (
                      <span>entry <span style={{ color: '#aaa', fontFamily: 'monospace' }}>${t.entryPrice.toFixed(2)}</span></span>
                    )}
                    {t.inPosition && t.unrealPct != null && (
                      <span>P&amp;L <span style={{ color: pnlColor, fontWeight: 700 }}>
                        {pnl >= 0 ? '+' : ''}{pnl.toFixed(2)}%
                      </span></span>
                    )}
                    {t.blocked && t.blockReason && (
                      <span style={{ color: '#ef444499' }}>{t.blockReason}</span>
                    )}
                    {t.inCooldown && !t.inPosition && (
                      <span>re-entry in <span style={{ color: '#f97316' }}>{t.cooldownMinutes}m</span></span>
                    )}
                    {!t.inPosition && !t.blocked && !t.inCooldown && (
                      <span style={{ color: '#38bdf855' }}>entry gate open</span>
                    )}
                  </div>
                </div>
              );
            })}
          </div>
        </div>
      )}

      <div style={{ fontSize: '10px', color: '#333', marginTop: '10px', textAlign: 'right' }}>
        {updatedAt > 0 ? `Updated ${new Date(updatedAt).toLocaleTimeString()}` : ''}
      </div>
    </div>
  );
};
