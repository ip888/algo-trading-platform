import { useEffect, useState } from 'react';

interface HealthCheck {
  name: string;
  status: 'GREEN' | 'YELLOW' | 'RED';
  detail: string;
}

interface BotBehavior {
  overallStatus: 'GREEN' | 'YELLOW' | 'RED';
  circuitBreakerState: string;
  activeCooldowns: Array<{ symbol: string; remainingMinutes: number }>;
  consecutiveStopLosses: Record<string, number>;
  recentTradesWins: number;
  recentTradeLosses: number;
  healthChecks: HealthCheck[];
  urgentExits: Record<string, string>;
  blockedBuys: Record<string, string>;
  pdtDayTradeCount: number;
  pdtBlockedUntilMs: number;
  vixLevel: number;
  marketRegime: string;
  targetSymbols: string;
  postLossCooldowns?: Array<{ symbol: string; expiresAt: number; remainingHours: number }>;
  circuitBreakers?: Record<string, {
    tripped: boolean;
    tripReason: string | null;
    consecutiveLosses: number;
    sessionDrawdownPct: number;
  }>;
  timestamp: number;
}

const STATUS_COLORS: Record<string, string> = {
  GREEN: '#22c55e',
  YELLOW: '#eab308',
  RED: '#ef4444',
};

const STATUS_ICONS: Record<string, string> = {
  GREEN: '🟢',
  YELLOW: '🟡',
  RED: '🔴',
};

// Checks that are shown as a compact top-row summary strip (not full cards)
const SUMMARY_CHECKS = new Set(['VIX / Market Regime', 'Market Hours', 'Entry Gates', 'PDT Day Trades']);

export const BotBehaviorMonitor = () => {
  const [data, setData] = useState<BotBehavior | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);

  const fetchBehavior = async () => {
    try {
      const res = await fetch('/api/bot/behavior');
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      const json = await res.json();
      setData(json);
      setError(null);
    } catch (e: any) {
      setError(e.message ?? 'Failed to load');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchBehavior();
    const interval = setInterval(fetchBehavior, 15000);
    return () => clearInterval(interval);
  }, []);

  if (loading) {
    return (
      <div className="panel" style={{ padding: '12px' }}>
        <h3>🚦 Bot Behavior</h3>
        <div style={{ color: '#888', fontSize: '13px' }}>Loading...</div>
      </div>
    );
  }

  if (error || !data) {
    return (
      <div className="panel" style={{ padding: '12px' }}>
        <h3>🚦 Bot Behavior</h3>
        <div style={{ color: '#ef4444', fontSize: '13px' }}>⚠ {error || 'No data'}</div>
      </div>
    );
  }

  const overallColor = STATUS_COLORS[data.overallStatus];
  const overallIcon = STATUS_ICONS[data.overallStatus];

  // Split health checks: summary strip vs detailed cards
  const summaryChecks = data.healthChecks.filter(c => SUMMARY_CHECKS.has(c.name));
  const detailChecks  = data.healthChecks.filter(c => !SUMMARY_CHECKS.has(c.name));

  // VIX / regime info
  const vix = data.vixLevel ?? 0;
  const regime = data.marketRegime ?? '';
  const targets = (data.targetSymbols ?? '').split(',').filter(Boolean);
  const isBearish = regime.includes('BEAR');

  return (
    <div className="panel" style={{ padding: '12px' }}>
      {/* Header */}
      <div style={{ display: 'flex', alignItems: 'center', gap: '8px', marginBottom: '10px' }}>
        <h3 style={{ margin: 0 }}>🚦 Bot Behavior</h3>
        <span style={{
          marginLeft: 'auto',
          padding: '2px 10px',
          borderRadius: '12px',
          background: overallColor + '22',
          color: overallColor,
          fontWeight: 700,
          fontSize: '12px',
          border: `1px solid ${overallColor}44`,
        }}>
          {overallIcon} {data.overallStatus}
        </span>
      </div>

      {/* Trading mode bar — VIX, regime, targets */}
      {vix > 0 && (
        <div style={{
          background: isBearish ? '#1a0a00' : '#0a1a0a',
          border: `1px solid ${isBearish ? '#eab30844' : '#22c55e44'}`,
          borderRadius: '6px',
          padding: '8px 10px',
          marginBottom: '10px',
        }}>
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '5px' }}>
            <span style={{ fontSize: '11px', color: '#888', fontWeight: 600 }}>TRADING MODE</span>
            <span style={{
              fontSize: '11px',
              fontWeight: 700,
              color: isBearish ? '#eab308' : '#22c55e',
              padding: '1px 8px',
              background: (isBearish ? '#eab308' : '#22c55e') + '22',
              borderRadius: '10px',
            }}>
              {isBearish ? '🐻 BEARISH' : '🐂 BULLISH'}
            </span>
          </div>
          <div style={{ display: 'flex', gap: '16px', fontSize: '11px', color: '#aaa', marginBottom: '5px' }}>
            <span>VIX <b style={{ color: vix >= 20 ? '#ef4444' : vix >= 16 ? '#eab308' : '#22c55e' }}>{vix.toFixed(1)}</b></span>
            <span>Regime <b style={{ color: '#ddd' }}>{regime.replace('_', ' ')}</b></span>
          </div>
          {targets.length > 0 && (
            <div style={{ display: 'flex', flexWrap: 'wrap', gap: '4px' }}>
              {targets.map(sym => (
                <span key={sym} style={{
                  fontSize: '10px',
                  fontWeight: 700,
                  color: isBearish ? '#eab308' : '#22c55e',
                  background: (isBearish ? '#eab308' : '#22c55e') + '18',
                  border: `1px solid ${(isBearish ? '#eab308' : '#22c55e')}33`,
                  borderRadius: '4px',
                  padding: '1px 5px',
                }}>{sym}</span>
              ))}
            </div>
          )}
        </div>
      )}

      {/* Summary strip — PDT, market hours, entry gates */}
      {summaryChecks.length > 0 && (
        <div style={{ display: 'flex', flexWrap: 'wrap', gap: '6px', marginBottom: '10px' }}>
          {summaryChecks.map(check => {
            const color = STATUS_COLORS[check.status];
            const icon = STATUS_ICONS[check.status];
            return (
              <div key={check.name} title={check.detail} style={{
                background: '#1a1a2e',
                borderRadius: '6px',
                padding: '5px 9px',
                borderLeft: `3px solid ${color}`,
                flex: '1 1 auto',
                minWidth: '120px',
              }}>
                <div style={{ display: 'flex', alignItems: 'center', gap: '4px' }}>
                  <span style={{ fontSize: '11px' }}>{icon}</span>
                  <span style={{ fontWeight: 600, fontSize: '11px', color: '#ddd' }}>{check.name}</span>
                </div>
                <div style={{ fontSize: '10px', color: '#888', marginTop: '2px', lineHeight: 1.3 }}>
                  {check.detail}
                </div>
              </div>
            );
          })}
        </div>
      )}

      {/* Detail checks — circuit breaker, stop losses, win rate, etc. */}
      <div style={{ display: 'flex', flexDirection: 'column', gap: '5px' }}>
        {detailChecks.map(check => {
          const color = STATUS_COLORS[check.status];
          const icon = STATUS_ICONS[check.status];
          return (
            <div key={check.name} style={{
              background: '#1a1a2e',
              borderRadius: '6px',
              padding: '7px 10px',
              borderLeft: `3px solid ${color}`,
            }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: '6px' }}>
                <span style={{ fontSize: '12px' }}>{icon}</span>
                <span style={{ fontWeight: 600, fontSize: '12px', color: '#ddd' }}>{check.name}</span>
              </div>
              <div style={{ fontSize: '11px', color: '#aaa', marginTop: '2px', lineHeight: 1.4 }}>
                {check.detail}
              </div>
            </div>
          );
        })}
      </div>

      {/* Blocked buys */}
      {data.blockedBuys && Object.keys(data.blockedBuys).length > 0 && (
        <div style={{ marginTop: '10px', background: '#1a1500', borderRadius: '6px', padding: '8px 10px', border: '1px solid #eab30844' }}>
          <div style={{ fontSize: '11px', color: '#eab308', fontWeight: 700, marginBottom: '4px' }}>⛔ BLOCKED ENTRIES</div>
          {Object.entries(data.blockedBuys).map(([symbol, reason]) => (
            <div key={symbol} style={{ display: 'flex', justifyContent: 'space-between', fontSize: '11px', padding: '2px 0', borderBottom: '1px solid #333' }}>
              <span style={{ color: '#eab308', fontWeight: 700 }}>{symbol}</span>
              <span style={{ color: '#888', maxWidth: '180px', textAlign: 'right', lineHeight: 1.3 }}>{reason}</span>
            </div>
          ))}
        </div>
      )}

      {/* Urgent exit queue */}
      {data.urgentExits && Object.keys(data.urgentExits).length > 0 && (
        <div style={{ marginTop: '10px', background: '#2a0a0a', borderRadius: '6px', padding: '8px 10px', border: '1px solid #ef444444' }}>
          <div style={{ fontSize: '11px', color: '#ef4444', fontWeight: 700, marginBottom: '4px' }}>⚠ URGENT EXIT QUEUE — RETRYING</div>
          {Object.entries(data.urgentExits).map(([symbol, detail]) => (
            <div key={symbol} style={{ display: 'flex', justifyContent: 'space-between', fontSize: '12px', padding: '2px 0' }}>
              <span style={{ color: '#ef4444', fontWeight: 700 }}>{symbol}</span>
              <span style={{ color: '#aaa' }}>{detail}</span>
            </div>
          ))}
        </div>
      )}

      {/* Active cooldowns */}
      {data.activeCooldowns.length > 0 && (
        <div style={{ marginTop: '10px' }}>
          <div style={{ fontSize: '11px', color: '#888', marginBottom: '4px' }}>COOLING DOWN</div>
          {data.activeCooldowns.map(cd => (
            <div key={cd.symbol} style={{ display: 'flex', justifyContent: 'space-between', fontSize: '12px', padding: '3px 0', borderBottom: '1px solid #333' }}>
              <span style={{ color: '#eab308' }}>{cd.symbol}</span>
              <span style={{ color: '#888' }}>{cd.remainingMinutes}m left</span>
            </div>
          ))}
        </div>
      )}

      {/* Tier 1.1 Post-Loss cooldowns (24h base / 72h escalated) */}
      {data.postLossCooldowns && data.postLossCooldowns.length > 0 && (
        <div style={{ marginTop: '10px', background: '#1a1500', borderRadius: '6px', padding: '8px 10px', border: '1px solid #eab30844' }}>
          <div style={{ fontSize: '11px', color: '#eab308', fontWeight: 700, marginBottom: '4px' }}>
            ⏳ POST-LOSS COOLDOWNS (Tier 1.1)
          </div>
          {data.postLossCooldowns.map(cd => (
            <div key={cd.symbol} style={{ display: 'flex', justifyContent: 'space-between', fontSize: '11px', padding: '2px 0', borderBottom: '1px solid #333' }}>
              <span style={{ color: '#eab308', fontWeight: 700 }}>{cd.symbol}</span>
              <span style={{ color: '#888' }}>{cd.remainingHours}h left</span>
            </div>
          ))}
        </div>
      )}

      {/* Tier 3.10 — Per-broker circuit breakers */}
      {data.circuitBreakers && Object.keys(data.circuitBreakers).length > 0 && (
        <div style={{ marginTop: '10px' }}>
          <div style={{ fontSize: '11px', color: '#888', marginBottom: '4px' }}>SESSION CIRCUIT BREAKERS (Tier 3.10)</div>
          {Object.entries(data.circuitBreakers).map(([broker, cb]) => {
            const color = cb.tripped ? '#ef4444' : cb.consecutiveLosses >= 2 ? '#eab308' : '#22c55e';
            return (
              <div key={broker} style={{
                background: '#1a1a2e',
                borderRadius: '6px',
                padding: '6px 9px',
                marginBottom: '4px',
                borderLeft: `3px solid ${color}`,
              }}>
                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                  <span style={{ fontWeight: 700, fontSize: '11px', color: '#ddd' }}>
                    {broker.toUpperCase()} {cb.tripped && <span style={{ color: '#ef4444' }}>· TRIPPED</span>}
                  </span>
                  <span style={{ fontSize: '10px', color }}>
                    {cb.consecutiveLosses}L · {(cb.sessionDrawdownPct * 100).toFixed(2)}% DD
                  </span>
                </div>
                {cb.tripped && cb.tripReason && (
                  <div style={{ fontSize: '10px', color: '#ef4444', marginTop: '2px' }}>
                    Reason: {cb.tripReason.replace('_', ' ')}
                  </div>
                )}
              </div>
            );
          })}
        </div>
      )}

      <div style={{ fontSize: '10px', color: '#555', marginTop: '10px', textAlign: 'right' }}>
        Updated {new Date(data.timestamp).toLocaleTimeString()}
      </div>
    </div>
  );
};
