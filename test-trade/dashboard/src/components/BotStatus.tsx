import { useTradingStore } from '../store/tradingStore';
import { useEffect, useState } from 'react';
import { CONFIG } from '../config';

export const BotStatus = () => {
  const { botStatus, systemStatus } = useTradingStore();
  const [marketData, setMarketData] = useState<any>(null);
  
  // Fetch market data from REST API as fallback
  useEffect(() => {
    const fetchMarketData = async () => {
      try {
        const response = await fetch(`${CONFIG.API_BASE_URL}/api/market/status`);
        const data = await response.json();
        setMarketData(data);
      } catch (error) {
        console.error('Failed to fetch market data:', error);
      }
    };
    
    // Fetch immediately and then every 10 seconds
    fetchMarketData();
    const interval = setInterval(fetchMarketData, 10000);
    
    return () => clearInterval(interval);
  }, []);
  
  if (!systemStatus && !marketData) {
    return (
      <div className="panel">
        <h2>🤖 Bot Status</h2>
        <p>Loading...</p>
      </div>
    );
  }
  
  // Use WebSocket data if available, otherwise use REST API data
  const vix = systemStatus?.vix || marketData?.currentVIX || 0;
  const marketOpen = systemStatus?.marketOpen ?? marketData?.isOpen ?? false;
  const regime = botStatus?.regime || systemStatus?.marketTrend || 'UNKNOWN';
  
  const marketStatus = marketOpen ? 'OPEN' : 'CLOSED';
  const marketClass = marketOpen ? 'status-open' : 'status-closed';
  
  return (
    <div className="panel bot-status">
      <h2>🤖 Bot Status</h2>
      
      <div className="status-grid">
        <div className="status-item">
          <span className="status-label">Market:</span>
          <span className={`status-badge ${marketClass}`}>{marketStatus}</span>
        </div>
        
        <div className="status-item">
          <span className="status-label">Regime:</span>
          <span className="status-value">{regime}</span>
        </div>
        
        <div className="status-item">
          <span className="status-label">VIX:</span>
          <span className="status-value">{vix > 0 ? vix.toFixed(2) : 'N/A'}</span>
        </div>
      </div>
      
      <div className="bot-action">
        <div className="action-label">Next Action:</div>
        <div className="action-value">
          {botStatus?.nextAction || `Monitoring ${systemStatus?.activePositions || 0} positions for profit targets`}
        </div>
      </div>
      
      {botStatus?.waitingFor && (
        <div className="bot-waiting">
          <small>⏳ Waiting for: {botStatus.waitingFor}</small>
        </div>
      )}
    </div>
  );
};
