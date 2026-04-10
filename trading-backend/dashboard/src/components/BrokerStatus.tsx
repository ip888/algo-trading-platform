import { useEffect, useState } from 'react';
import { CONFIG } from '../config';

interface BrokerInfo {
  name: string;
  sandbox?: boolean;
  connected: boolean;
  equity?: number;
  cash?: number;
  buyingPower?: number;
  error?: string;
}

interface BrokerStatusResponse {
  brokers: BrokerInfo[];
  multiBroker: boolean;
}

const BROKER_COLORS: Record<string, string> = {
  alpaca: '#3b82f6',
  tradier: '#22c55e',
};

const fmt = (n: number) =>
  '$' + n.toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 });

export const BrokerStatus = () => {
  const [data, setData] = useState<BrokerStatusResponse | null>(null);

  const fetchStatus = async () => {
    try {
      const res = await fetch(`${CONFIG.API_BASE_URL}/api/brokers/status`);
      if (res.ok) {
        const json = await res.json();
        setData(json);
      }
    } catch (e) {
      console.warn('Failed to fetch broker status:', e);
    }
  };

  useEffect(() => {
    fetchStatus();
    const interval = setInterval(fetchStatus, 30000);
    return () => clearInterval(interval);
  }, []);

  if (!data) return null;

  // Only show panel when multi-broker or 2+ brokers present
  if (!data.multiBroker && data.brokers.length < 2) return null;

  return (
    <div className="panel">
      <h2>🏦 Active Brokers</h2>
      <div style={{ display: 'flex', flexDirection: 'column', gap: '10px', marginTop: '8px' }}>
        {data.brokers.map((broker) => {
          const color = BROKER_COLORS[broker.name] || '#888';
          return (
            <div
              key={broker.name}
              style={{
                border: `1px solid ${color}44`,
                borderRadius: '6px',
                padding: '10px 12px',
                background: `${color}0a`,
              }}
            >
              {/* Header row */}
              <div style={{ display: 'flex', alignItems: 'center', gap: '8px', marginBottom: '6px' }}>
                <span
                  style={{
                    fontSize: '12px',
                    fontWeight: 700,
                    padding: '2px 8px',
                    borderRadius: '4px',
                    background: `${color}22`,
                    color,
                    border: `1px solid ${color}44`,
                    textTransform: 'uppercase',
                  }}
                >
                  {broker.name}
                </span>

                {broker.sandbox !== undefined && (
                  <span
                    style={{
                      fontSize: '10px',
                      fontWeight: 600,
                      padding: '1px 6px',
                      borderRadius: '3px',
                      background: broker.sandbox ? '#f59e0b22' : '#22c55e22',
                      color: broker.sandbox ? '#f59e0b' : '#22c55e',
                      border: `1px solid ${broker.sandbox ? '#f59e0b44' : '#22c55e44'}`,
                    }}
                  >
                    {broker.sandbox ? 'SANDBOX' : 'LIVE'}
                  </span>
                )}

                <span
                  style={{
                    marginLeft: 'auto',
                    fontSize: '10px',
                    fontWeight: 600,
                    color: broker.connected ? '#22c55e' : '#ef4444',
                  }}
                >
                  {broker.connected ? '● CONNECTED' : '● DISCONNECTED'}
                </span>
              </div>

              {/* Account metrics */}
              {broker.connected ? (
                <div
                  style={{
                    display: 'grid',
                    gridTemplateColumns: '1fr 1fr 1fr',
                    gap: '6px',
                    fontSize: '11px',
                  }}
                >
                  <div>
                    <div style={{ color: 'var(--text-muted)', marginBottom: '2px' }}>EQUITY</div>
                    <div style={{ fontWeight: 600, color: 'var(--text-main)' }}>
                      {fmt(broker.equity ?? 0)}
                    </div>
                  </div>
                  <div>
                    <div style={{ color: 'var(--text-muted)', marginBottom: '2px' }}>CASH</div>
                    <div style={{ fontWeight: 600, color: '#22c55e' }}>
                      {fmt(broker.cash ?? 0)}
                    </div>
                  </div>
                  <div>
                    <div style={{ color: 'var(--text-muted)', marginBottom: '2px' }}>BP</div>
                    <div style={{ fontWeight: 600, color: '#3b82f6' }}>
                      {fmt(broker.buyingPower ?? 0)}
                    </div>
                  </div>
                </div>
              ) : (
                <div style={{ fontSize: '11px', color: '#ef4444', opacity: 0.8 }}>
                  {broker.error || 'Connection failed'}
                </div>
              )}
            </div>
          );
        })}
      </div>
    </div>
  );
};

export default BrokerStatus;
