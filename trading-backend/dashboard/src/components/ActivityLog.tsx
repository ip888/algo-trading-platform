import { useEffect, useRef } from 'react';
import { useTradingStore } from '../store/tradingStore';
import { Tooltip } from './Tooltip';

export const ActivityLog = () => {
  const { activityLog } = useTradingStore();
  const scrollRef = useRef<HTMLDivElement>(null);

  // Auto-scroll to bottom when new logs arrive
  useEffect(() => {
    if (scrollRef.current) {
      scrollRef.current.scrollTop = scrollRef.current.scrollHeight;
    }
  }, [activityLog]);

  const getLevelColor = (level: string) => {
    switch (level) {
      case 'INFO': return '#00ccff';
      case 'WARN': return '#ffaa00';
      case 'ERROR': return '#ff4444';
      case 'SUCCESS': return '#00ff88';
      default: return '#aaaaaa';
    }
  };

  return (
    <div className="panel" style={{ height: '300px', display: 'flex', flexDirection: 'column' }}>
      <h2>
        📜 Live Activity Log
        <Tooltip text="Real-time stream of internal bot decisions and events." />
      </h2>
      
      <div 
        ref={scrollRef}
        style={{ 
          flex: 1, 
          overflowY: 'auto', 
          background: 'rgba(0,0,0,0.2)', 
          borderRadius: '8px',
          padding: '12px',
          fontFamily: 'monospace',
          fontSize: '0.85rem'
        }}
      >
        {activityLog.length === 0 ? (
          <div style={{ color: '#666', textAlign: 'center', marginTop: '20px' }}>
            Waiting for activity...
          </div>
        ) : (
          activityLog.map((entry) => (
            <div key={entry.id} style={{ marginBottom: '6px', lineHeight: '1.4' }}>
              <span style={{ color: '#666', marginRight: '8px' }}>
                {new Date(entry.timestamp).toLocaleTimeString()}
              </span>
              <span style={{ 
                color: getLevelColor(entry.level), 
                fontWeight: 'bold', 
                marginRight: '8px',
                minWidth: '60px',
                display: 'inline-block'
              }}>
                [{entry.level}]
              </span>
              <span style={{ color: '#ddd', wordBreak: 'break-word', whiteSpace: 'pre-wrap' }}>
                {entry.message}
              </span>
            </div>
          ))
        )}
      </div>
    </div>
  );
};
