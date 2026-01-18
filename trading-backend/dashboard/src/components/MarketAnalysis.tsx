import { useTradingStore } from '../store/tradingStore';
import { Tooltip } from './Tooltip';

export const MarketAnalysis = () => {
  const { systemStatus } = useTradingStore();
  
  if (!systemStatus) {
    return (
      <div className="panel">
        <h2>📈 Market Analysis</h2>
        <p>Loading...</p>
      </div>
    );
  }
  
  const getTrendIcon = (trend: string) => {
    switch (trend?.toUpperCase()) {
      case 'BULLISH': return '📈';
      case 'BEARISH': return '📉';
      case 'VOLATILE': return '⚡';
      default: return '➡️';
    }
  };
  
  const getTrendClass = (trend: string) => {
    switch (trend?.toUpperCase()) {
      case 'BULLISH': return 'bullish';
      case 'BEARISH': return 'bearish';
      default: return 'neutral';
    }
  };
  
  // Check if market is open
  const isMarketOpen = () => {
    const est = new Date().toLocaleString('en-US', { timeZone: 'America/New_York' });
    const estDate = new Date(est);
    const hours = estDate.getHours();
    const day = estDate.getDay();
    if (day === 0 || day === 6) return false;
    if (hours < 9 || hours >= 16) return false;
    if (hours === 9 && estDate.getMinutes() < 30) return false;
    return true;
  };
  
  const marketOpen = isMarketOpen();
  
  // Determine regime and strategy based on VIX and trend
  const getRegimeAndStrategy = () => {
    const vix = systemStatus.vix || 0;
    const trend = systemStatus.marketTrend || 'NEUTRAL';
    
    if (vix > 30) {
      return { regime: 'Extreme Volatility', strategy: 'Mean Reversion' };
    } else if (vix > 20) {
      return { regime: 'High Volatility', strategy: 'Cautious Momentum' };
    } else if (trend === 'BULLISH') {
      return { regime: 'Low Volatility', strategy: 'Momentum' };
    } else {
      return { regime: 'Normal', strategy: 'Balanced' };
    }
  };
  
  const { regime, strategy } = getRegimeAndStrategy();
  
  return (
    <div className="panel">
      <h2>
        📈 Market Analysis
        <Tooltip text="Real-time market conditions, volatility, and AI-driven trading recommendations." />
      </h2>
      
      {!marketOpen && (
        <div className="market-closed-notice">
          ⏸️ Market Closed - Last analysis from 4:00 PM ET
        </div>
      )}
      
      <div className="analysis-grid">
        <div className={`trend-badge ${getTrendClass(systemStatus.marketTrend)}`}>
          {getTrendIcon(systemStatus.marketTrend)} {systemStatus.marketTrend}
        </div>
        <div className="regime-badge" style={{ 
          padding: '4px 10px', 
          borderRadius: '12px', 
          fontSize: '11px',
          fontWeight: '600',
          backgroundColor: (systemStatus.vix || 0) > 30 ? 'rgba(239, 68, 68, 0.1)' : 'rgba(59, 130, 246, 0.1)',
          color: (systemStatus.vix || 0) > 30 ? '#ef4444' : '#3b82f6',
          border: `1px solid ${(systemStatus.vix || 0) > 30 ? '#ef4444' : '#3b82f6'}`
        }}>
          {regime} • {strategy}
        </div>
      </div>
      
      <div className="status-grid">
        <div className="status-item">
          <div className="status-label">
            VIX Level
            <Tooltip text="Volatility Index. Measures market fear. High VIX (>30) indicates high risk." />
          </div>
          <div className="status-value">{systemStatus.vix?.toFixed(1) || 'N/A'}</div>
        </div>
        
        <div className="status-item">
          <div className="status-label">
            Market Strength
            <Tooltip text="Aggregate score (0-100) of all tracked assets based on momentum and volume." />
          </div>
          <div className="status-value">{systemStatus.marketStrength?.toFixed(0) || 'N/A'}</div>
        </div>

        <div className="status-item">
          <div className="status-label">
            Sentiment
            <Tooltip text="Avg News Sentiment (-1 to +1). Positive values indicate bullish news." />
          </div>
          <div className="status-value" style={{ 
            color: (systemStatus.sentiment || 0) > 0 ? '#10b981' : ((systemStatus.sentiment || 0) < 0 ? '#ef4444' : 'inherit') 
          }}>
            {(systemStatus.sentiment || 0).toFixed(2)}
          </div>
        </div>
      </div>
      
      <div className="recommendation">
        <strong>📊 AI Recommendation:</strong>
        <p>{systemStatus.recommendation || 'Analyzing market conditions...'}</p>
      </div>
    </div>
  );
};
