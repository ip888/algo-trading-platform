import { useEffect, useState } from 'react';
import './Phase2StatusBar.css';
import { CONFIG } from '../config';

interface Phase2Status {
  capitalReserve: number;
  deployableCapital: number;
  tradingMode: string;
  scalpEnabled: boolean;
  scalpDailyCount: number;
  scalpDailyMax: number;
}

export function Phase2StatusBar() {
  const [status, setStatus] = useState<Phase2Status | null>(null);

  useEffect(() => {
    const fetchStatus = async () => {
      try {
        const [statusRes, accountRes] = await Promise.all([
          fetch(`${CONFIG.API_BASE_URL}/api/status`),
          fetch(`${CONFIG.API_BASE_URL}/api/account`)
        ]);
        
        const statusData = await statusRes.json();
        const accountData = await accountRes.json();
        
        setStatus({
          capitalReserve: accountData.capitalReserve || 0,
          deployableCapital: accountData.deployableCapital || 0,
          tradingMode: statusData.tradingMode || 'LIVE',
          scalpEnabled: statusData.scalpEnabled ?? true,
          scalpDailyCount: statusData.scalpDailyCount ?? 0,
          scalpDailyMax: statusData.scalpDailyMax ?? 4,
        });
      } catch (error) {
        console.error('Failed to fetch Phase 2 status:', error);
      }
    };

    fetchStatus();
    const interval = setInterval(fetchStatus, 10000);
    return () => clearInterval(interval);
  }, []);

  if (!status) return null;

  return (
    <div className="phase2-status-bar-compact">
      <div className="status-pill">
        <span className="pill-label">💰 Reserve:</span>
        <span className="pill-value">${status.capitalReserve.toFixed(0)}</span>
      </div>
      <div className="status-pill">
        <span className="pill-label">📊 Deployable:</span>
        <span className="pill-value">${status.deployableCapital.toFixed(0)}</span>
      </div>
      <div className="status-pill mode">
        <span className={`mode-badge ${status.tradingMode.toLowerCase()}`}>
          {status.tradingMode}
        </span>
      </div>
      {status.scalpEnabled && (
        <div className="status-pill" title={`Intraday scalp trades today: ${status.scalpDailyCount} / ${status.scalpDailyMax}`}>
          <span className="pill-label">⚡ Scalp:</span>
          <span className="pill-value" style={{ color: status.scalpDailyCount >= status.scalpDailyMax ? '#f59e0b' : '#a3e635' }}>
            {status.scalpDailyCount}/{status.scalpDailyMax}
          </span>
        </div>
      )}
    </div>
  );
}
