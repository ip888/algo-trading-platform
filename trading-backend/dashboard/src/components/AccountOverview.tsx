import { useTradingStore } from '../store/tradingStore';
import { memo } from 'react';

const TIER_COLORS: Record<string, string> = {
  MICRO:    '#ef4444',
  SMALL:    '#eab308',
  MEDIUM:   '#3b82f6',
  STANDARD: '#22c55e',
  PDT:      '#a855f7',
};

const AccountOverviewComponent = () => {
  const accountData = useTradingStore(state => state.accountData);
  const systemStatus = useTradingStore(state => state.systemStatus);

  if (!accountData) {
    return (
      <div className="panel">
        <h2>💰 Liquidity Analysis</h2>
        <div style={{ padding: '20px', textAlign: 'center', color: 'var(--text-dim)', fontSize: '13px' }}>
          Syncing with Alpaca APIs...
        </div>
      </div>
    );
  }

  const isSyncing = !systemStatus;
  const ad = accountData as any;
  const equity = ad.equity || ad.cash || 0;
  const sessionStart: number = ad.sessionStartCapital || 0;
  const sessionPnl = sessionStart > 0 ? equity - sessionStart : 0;
  const sessionPnlPct = sessionStart > 0 ? (sessionPnl / sessionStart) * 100 : 0;
  const tier: string = ad.capitalTier || '—';
  const tierColor = TIER_COLORS[tier] || '#888';

  return (
    <div className="panel account-overview">
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '16px' }}>
        <h2 style={{ margin: 0 }}>💰 Liquidity Analysis</h2>
        {isSyncing && <span style={{ fontSize: '11px', color: 'var(--warning)', fontWeight: 600 }}>[ REFRESHING ]</span>}
      </div>

      <div className="overview-grid">
        <div className="overview-item">
          <div className="overview-label">TOTAL EQUITY</div>
          <div className="overview-value highlight">${equity.toLocaleString(undefined, { minimumFractionDigits: 2 })}</div>
        </div>

        <div className="overview-item">
          <div className="overview-label">BUYING POWER</div>
          <div className="overview-value" style={{ color: 'var(--primary)' }}>${(ad.buyingPower || 0).toLocaleString(undefined, { minimumFractionDigits: 2 })}</div>
        </div>

        <div className="overview-item">
          <div className="overview-label">AVAILABLE CASH</div>
          <div className="overview-value" style={{ color: 'var(--success)' }}>${(ad.cash || 0).toLocaleString(undefined, { minimumFractionDigits: 2 })}</div>
        </div>

        <div className="overview-item">
          <div className="overview-label">SESSION START</div>
          <div className="overview-value" style={{ color: 'var(--text-muted)' }}>
            {sessionStart > 0 ? `$${sessionStart.toLocaleString(undefined, { minimumFractionDigits: 2 })}` : '—'}
          </div>
        </div>
      </div>

      {/* Session P&L row */}
      {sessionStart > 0 && (
        <div style={{ marginTop: '12px', padding: '8px 10px', background: '#1a1a2e', borderRadius: '6px', display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
          <span style={{ fontSize: '11px', color: '#888', fontWeight: 600 }}>SESSION P&L</span>
          <span style={{ fontSize: '13px', fontWeight: 700, color: sessionPnl >= 0 ? '#22c55e' : '#ef4444' }}>
            {sessionPnl >= 0 ? '+' : ''}${sessionPnl.toFixed(2)} ({sessionPnlPct >= 0 ? '+' : ''}{sessionPnlPct.toFixed(2)}%)
          </span>
        </div>
      )}

      {/* Capital Tier row */}
      {tier !== '—' && (
        <div style={{ marginTop: '8px', padding: '8px 10px', background: '#1a1a2e', borderRadius: '6px', border: `1px solid ${tierColor}33` }}>
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '4px' }}>
            <span style={{ fontSize: '11px', color: '#888', fontWeight: 600 }}>CAPITAL TIER</span>
            <span style={{ fontSize: '12px', fontWeight: 700, color: tierColor, padding: '1px 8px', background: tierColor + '22', borderRadius: '10px' }}>{tier}</span>
          </div>
          <div style={{ fontSize: '11px', color: '#666', display: 'flex', gap: '12px', flexWrap: 'wrap' }}>
            <span>Risk/trade: <b style={{ color: '#aaa' }}>{ad.tierRiskPerTradePercent?.toFixed(1)}%</b></span>
            <span>Max pos: <b style={{ color: '#aaa' }}>{ad.tierMaxPositionPercent?.toFixed(0)}%</b></span>
            <span>Max slots: <b style={{ color: '#aaa' }}>{ad.tierMaxPositions}</b></span>
            <span>Min $: <b style={{ color: '#aaa' }}>${ad.tierMinPositionValue}</b></span>
            {ad.eodExitEnabled === false && <span style={{ color: '#22c55e' }}>EOD OFF</span>}
          </div>
        </div>
      )}
    </div>
  );
};

export const AccountOverview = memo(AccountOverviewComponent);
