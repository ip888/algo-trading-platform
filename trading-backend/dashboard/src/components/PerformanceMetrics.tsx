import { useTradingStore } from '../store/tradingStore';
import { Tooltip } from './Tooltip';

export const PerformanceMetrics = () => {
  const { systemStatus } = useTradingStore();
  
  if (!systemStatus) {
    return (
      <div className="panel">
        <h2>💰 Performance</h2>
        <p>Loading...</p>
      </div>
    );
  }
  
  const pnlClass = systemStatus.totalPnL >= 0 ? 'positive' : 'negative';
  
  return (
    <div className="panel">
      <h2>
        💰 Performance
        <Tooltip text="Financial performance metrics for the current session." />
      </h2>
      
      <div className={`metric-large ${pnlClass}`}>
        ${systemStatus.totalPnL.toFixed(2)}
      </div>
      
      <div className="status-grid">
        <div className="status-item">
          <div className="status-label">
            Total Trades
            <Tooltip text="Total number of completed trades (buy + sell pairs)." />
          </div>
          <div className="status-value">{systemStatus.totalTrades}</div>
        </div>
        
        <div className="status-item">
          <div className="status-label">
            Win Rate
            <Tooltip text="Percentage of profitable trades." />
          </div>
          <div className="status-value">{systemStatus.winRate?.toFixed(1)}%</div>
        </div>
      </div>
    </div>
  );
};
