import { useTradingStore } from '../store/tradingStore';
import { useEffect, useRef } from 'react';

export const LiveBotLog = () => {
  const { activityLog } = useTradingStore();
  const logEndRef = useRef<HTMLDivElement>(null);
  
  // Auto-scroll to bottom when new logs arrive
  useEffect(() => {
    logEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [activityLog]);
  
  // Get last 10 log entries
  const recentLogs = activityLog.slice(-10);
  
  
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
              </div>
            ))}
            <div ref={logEndRef} />
          </div>
        )}
      </div>
    </div>
  );
};
