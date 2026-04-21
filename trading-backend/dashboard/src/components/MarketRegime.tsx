import { Tooltip } from './Tooltip';
import { useEffect, useState } from 'react';
import { CONFIG } from '../config';

// Maps the backend's 6-regime enum to display properties
const REGIME_MAP: Record<string, { label: string; color: string; strategy: string; note?: string }> = {
  STRONG_BULL:    { label: 'STRONG BULL',    color: '#22c55e', strategy: 'MACD Trend + Momentum' },
  WEAK_BULL:      { label: 'WEAK BULL',       color: '#86efac', strategy: 'RSI + Momentum' },
  STRONG_BEAR:    { label: 'STRONG BEAR',     color: '#ef4444', strategy: 'Exits Only (no new longs)', note: '🐻 Bear block active' },
  WEAK_BEAR:      { label: 'WEAK BEAR',       color: '#f97316', strategy: 'MACD Trend' },
  RANGE_BOUND:    { label: 'RANGE BOUND',     color: '#a78bfa', strategy: 'Mean Reversion' },
  HIGH_VOLATILITY:{ label: 'HIGH VOLATILITY', color: '#eab308', strategy: 'MACD Exits Only (no new entries)' },
  UNKNOWN:        { label: 'UNKNOWN',          color: '#6b7280', strategy: 'Determining…' },
};

export const MarketRegime = () => {
  const [status, setStatus] = useState<{ regime: string; vix: number; marketStatus: string } | null>(null);

  useEffect(() => {
    const fetch_ = async () => {
      try {
        const res = await fetch(`${CONFIG.API_BASE_URL}/api/status`);
        if (res.ok) setStatus(await res.json());
      } catch (e) {
        console.error('Bot status fetch failed', e);
      }
    };
    fetch_();
    const interval = setInterval(fetch_, 30000);
    return () => clearInterval(interval);
  }, []);

  if (!status) return null;

  const regime = status.regime || 'UNKNOWN';
  const vix = status.vix ?? 0;
  const marketOpen = status.marketStatus === 'OPEN';
  const info = REGIME_MAP[regime] ?? REGIME_MAP['UNKNOWN'];

  return (
    <div className="panel regime-panel">
      <h2>
        🌊 Market Regime
        <Tooltip text="Detected market regime drives which strategy the bot uses for entries and exits." />
      </h2>

      <div className="regime-gauge">
        <div className="gauge-label" style={{ color: info.color }}>
          {info.label}
        </div>
        <div className="gauge-sublabel">VIX: {vix.toFixed(1)}</div>
      </div>

      <div className="strategy-info">
        <div className="strategy-label">Active Strategy</div>
        <div className="strategy-value" style={{ color: info.color }}>{info.strategy}</div>
      </div>

      {info.note && (
        <div style={{
          marginTop: '8px',
          padding: '5px 9px',
          borderRadius: '6px',
          background: info.color + '18',
          border: `1px solid ${info.color}44`,
          fontSize: '11px',
          color: info.color,
          fontWeight: 600,
        }}>
          {info.note}
        </div>
      )}

      <div className="regime-details">
        <div className="detail-row">
          <span>Market</span>
          <span style={{ color: marketOpen ? '#22c55e' : '#6b7280' }}>
            {marketOpen ? 'OPEN' : 'CLOSED'}
          </span>
        </div>
        <div className="detail-row">
          <span>Risk Level</span>
          <span style={{ color: vix > 25 ? '#ef4444' : vix > 18 ? '#eab308' : '#22c55e' }}>
            {vix > 25 ? 'HIGH — REDUCED SIZE' : vix > 18 ? 'ELEVATED' : 'STANDARD'}
          </span>
        </div>
      </div>
    </div>
  );
};
