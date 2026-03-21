import { useTradingStore } from '../store/tradingStore';
import { useEffect, useRef } from 'react';

interface DeduplicatedLog {
  level: string;
  message: string;
  timestamp: number;
  count: number;
}

export const LiveBotLog = () => {
  const { activityLog } = useTradingStore();
  const logEndRef = useRef<HTMLDivElement>(null);

  // Auto-scroll to bottom when new logs arrive
  useEffect(() => {
    logEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [activityLog]);

  // Deduplicate: consecutive same level+message collapse into one entry with updated time + count
  const deduped: DeduplicatedLog[] = [];
  for (const log of activityLog.slice(-100)) {
    const last = deduped[deduped.length - 1];
    if (last && last.level === log.level && last.message === log.message) {
      last.timestamp = log.timestamp;
      last.count += 1;
    } else {
      deduped.push({ level: log.level, message: log.message, timestamp: log.timestamp, count: 1 });
    }
  }
  const recentLogs = deduped.slice(-15);

  return (
    <div className="panel live-bot-log">
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '15px' }}>
        <h2 style={{ margin: 0 }}>📡 Core Telemetry Stream</h2>
        <span style={{ fontSize: '10px', color: 'var(--text-dim)', background: 'var(--bg-deep)', padding: '2px 8px', borderRadius: '4px', border: '1px solid var(--border-dim)', fontFamily: 'monospace' }}>
          LIVE FEED
        </span>
      </div>

      <div className="log-container" style={{ maxHeight: '300px', overflowY: 'auto', background: 'var(--bg-deep)', padding: '10px', borderRadius: '4px', border: '1px solid var(--border-dim)' }}>
        {recentLogs.length === 0 ? (
          <div style={{ padding: '20px', textAlign: 'center', color: 'var(--text-dim)', fontSize: '0.8rem', fontFamily: 'monospace' }}>
            AWAITING DATA STREAM...
          </div>
        ) : (
          <div className="log-entries" style={{ fontFamily: 'JetBrains Mono, monospace', fontSize: '11px', lineHeight: '1.5' }}>
            {recentLogs.map((log, index) => (
              <div key={index} style={{ marginBottom: '6px', borderBottom: '1px solid rgba(255,255,255,0.02)', paddingBottom: '4px' }}>
                <span style={{ color: 'var(--text-dim)', marginRight: '8px' }}>
                  [{new Date(log.timestamp).toLocaleTimeString([], { hour12: false })}]
                </span>
                <span style={{
                  color: log.level === 'SUCCESS' ? 'var(--neon-green)' :
                         log.level === 'ERROR' ? 'var(--neon-red)' :
                         log.level === 'WARN' ? 'var(--neon-amber)' : 'var(--neon-cyan)',
                  fontWeight: 700,
                  marginRight: '8px'
                }}>
                  {log.level.toUpperCase()}
                </span>
                <span style={{ color: 'var(--text-secondary)' }}>{log.message}</span>
                {log.count > 1 && (
                  <span style={{
                    marginLeft: '8px',
                    padding: '1px 6px',
                    borderRadius: '10px',
                    background: 'rgba(255,255,255,0.08)',
                    color: 'var(--text-dim)',
                    fontSize: '10px',
                    fontWeight: 600,
                  }}>
                    ×{log.count}
                  </span>
                )}
              </div>
            ))}
            <div ref={logEndRef} />
          </div>
        )}
      </div>
    </div>
  );
};
