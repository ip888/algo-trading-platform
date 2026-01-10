import { useTradingStore } from '../store/tradingStore';
import { memo } from 'react';

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
  
  return (
    <div className="panel account-overview">
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '20px' }}>
        <h2 style={{ margin: 0 }}>💰 Liquidity Analysis</h2>
        {isSyncing && <span style={{ fontSize: '11px', color: 'var(--warning)', fontWeight: 600 }}>[ REFRESHING ]</span>}
      </div>
      
      <div className="overview-grid">
        <div className="overview-item">
          <div className="overview-label">TOTAL EQUITY</div>
          <div className="overview-value highlight">${(accountData.equity || accountData.cash || 0).toLocaleString(undefined, { minimumFractionDigits: 2 })}</div>
        </div>
        
        <div className="overview-item">
          <div className="overview-label">BUYING POWER</div>
          <div className="overview-value" style={{ color: 'var(--primary)' }}>${accountData.buyingPower.toLocaleString(undefined, { minimumFractionDigits: 2 })}</div>
        </div>
        
        <div className="overview-item">
          <div className="overview-label">AVAILABLE CASH</div>
          <div className="overview-value" style={{ color: 'var(--success)' }}>${(accountData.cash || 0).toLocaleString(undefined, { minimumFractionDigits: 2 })}</div>
        </div>
        
        <div className="overview-item">
          <div className="overview-label">LIVE FEED</div>
          <div className="overview-value" style={{ fontSize: '12px', color: 'var(--text-muted)' }}>
            CONNECTED (SSL)
          </div>
        </div>
      </div>
    </div>
  );
};

export const AccountOverview = memo(AccountOverviewComponent);
