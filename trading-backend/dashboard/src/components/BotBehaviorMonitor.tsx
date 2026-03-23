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
    const interval = setInterval(fetchBehavior, 15000); // refresh every 15s
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

  return (
    <div className="panel" style={{ padding: '12px' }}>
      {/* Header with overall status */}
      <div style={{ display: 'flex', alignItems: 'center', gap: '8px', marginBottom: '12px' }}>
        <h3 style={{ margin: 0 }}>🚦 Bot Behavior</h3>
        <span
          style={{
            marginLeft: 'auto',
            padding: '2px 10px',
            borderRadius: '12px',
            background: overallColor + '22',
            color: overallColor,
            fontWeight: 700,
            fontSize: '12px',
            border: `1px solid ${overallColor}44`,
          }}
        >
          {overallIcon} {data.overallStatus}
        </span>
      </div>

      {/* Traffic-light checks */}
      <div style={{ display: 'flex', flexDirection: 'column', gap: '6px' }}>
        {data.healthChecks.map((check) => {
          const color = STATUS_COLORS[check.status];
          const icon = STATUS_ICONS[check.status];
          return (
            <div
              key={check.name}
              style={{
                background: '#1a1a2e',
                borderRadius: '6px',
                padding: '7px 10px',
                borderLeft: `3px solid ${color}`,
              }}
            >
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

      {/* Urgent exit queue — failed protective sells */}
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

      {/* Active Cooldowns detail */}
      {data.activeCooldowns.length > 0 && (
        <div style={{ marginTop: '10px' }}>
          <div style={{ fontSize: '11px', color: '#888', marginBottom: '4px' }}>COOLING DOWN</div>
          {data.activeCooldowns.map((cd) => (
            <div
              key={cd.symbol}
              style={{
                display: 'flex',
                justifyContent: 'space-between',
                fontSize: '12px',
                padding: '3px 0',
                borderBottom: '1px solid #333',
              }}
            >
              <span style={{ color: '#eab308' }}>{cd.symbol}</span>
              <span style={{ color: '#888' }}>{cd.remainingMinutes}m left</span>
            </div>
          ))}
        </div>
      )}

      <div style={{ fontSize: '10px', color: '#555', marginTop: '10px', textAlign: 'right' }}>
        Updated {new Date(data.timestamp).toLocaleTimeString()}
      </div>
    </div>
  );
};
