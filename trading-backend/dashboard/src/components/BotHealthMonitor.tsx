import { useTradingStore } from '../store/tradingStore';
import { useEffect, useState } from 'react';

export const BotHealthMonitor = () => {
  const { systemStatus, lastUpdate, connected } = useTradingStore();
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const interval = window.setInterval(() => setNow(Date.now()), 10000);
    return () => window.clearInterval(interval);
  }, []);
  
  // Calculate time since last update
  const getTimeSinceUpdate = () => {
    if (!lastUpdate) return 'Never';
    const seconds = Math.floor((now - lastUpdate) / 1000);
    if (seconds < 60) return `${seconds}s ago`;
    const minutes = Math.floor(seconds / 60);
    if (minutes < 60) return `${minutes}m ago`;
    const hours = Math.floor(minutes / 60);
    return `${hours}h ${minutes % 60}m ago`;
  };
  
  // Determine health status
  const getHealthStatus = () => {
    if (!connected) return { status: 'Disconnected', color: 'error', icon: '❌' };
    if (!lastUpdate) return { status: 'Starting...', color: 'warning', icon: '⏳' };
    
    const secondsSinceUpdate = (now - lastUpdate) / 1000;
    if (secondsSinceUpdate > 60) return { status: 'Stale', color: 'warning', icon: '⚠️' };
    
    return { status: 'Healthy', color: 'success', icon: '✅' };
  };
  
  const health = getHealthStatus();
  const timeSince = getTimeSinceUpdate();
  
  return (
    <div className="bot-health panel">
      <h2>🏥 Bot Health Monitor</h2>
      
      <div className="health-grid">
        <div className="health-item">
          <div className="health-label">Status</div>
          <div className={`health-value ${health.color}`}>
            {health.icon} {health.status}
          </div>
        </div>
        
        <div className="health-item">
          <div className="health-label">Last Update</div>
          <div className="health-value">
            {timeSince}
          </div>
        </div>
        
        <div className="health-item">
          <div className="health-label">WebSocket</div>
          <div className={`health-value ${connected ? 'success' : 'error'}`}>
            {connected ? '🟢 Connected' : '🔴 Disconnected'}
          </div>
        </div>
        
        <div className="health-item">
          <div className="health-label">Active Positions</div>
          <div className="health-value">
            {systemStatus?.activePositions ?? 0}
          </div>
        </div>
        
        <div className="health-item">
          <div className="health-label">Market Status</div>
          <div className={`health-value ${systemStatus?.marketOpen ? 'success' : 'warning'}`}>
            {systemStatus?.marketOpen ? '🟢 Open' : '🟡 Closed'}
          </div>
        </div>
        
        <div className="health-item">
          <div className="health-label">Trading Mode</div>
          <div className="health-value">
            {systemStatus?.tradingMode || 'Unknown'}
          </div>
        </div>
      </div>
      
      {systemStatus && (
        <div className="health-summary">
          <div className="summary-item">
            <span className="summary-label">Total Trades:</span>
            <span className="summary-value">{systemStatus.totalTrades}</span>
          </div>
          <div className="summary-item">
            <span className="summary-label">Win Rate:</span>
            <span className="summary-value">{systemStatus.winRate.toFixed(1)}%</span>
          </div>
          <div className="summary-item">
            <span className="summary-label">Total P&L:</span>
            <span className={`summary-value ${systemStatus.totalPnL >= 0 ? 'positive' : 'negative'}`}>
              ${systemStatus.totalPnL.toFixed(2)}
            </span>
          </div>
        </div>
      )}
    </div>
  );
};
