import { Tooltip } from './Tooltip';
import { useTradingStore } from '../store/tradingStore';
import { useState, useEffect } from 'react';

export const SystemOverview = () => {
  const { connected, systemStatus } = useTradingStore();
  const [currentTime, setCurrentTime] = useState(new Date());
  
  // Update current time every second
  useEffect(() => {
    const timer = setInterval(() => setCurrentTime(new Date()), 1000);
    return () => clearInterval(timer);
  }, []);
  
  if (!systemStatus) {
    return (
      <div className="panel">
        <h2>⚡ System Overview</h2>
        <p>Loading...</p>
      </div>
    );
  }
  
  // Calculate if market is actually open (client-side verification for extended hours)
  const isMarketActuallyOpen = () => {
    const est = new Date(currentTime.toLocaleString('en-US', { timeZone: 'America/New_York' }));
    const hours = est.getHours();
    const day = est.getDay();
    
    // Weekend check
    if (day === 0 || day === 6) return false;
    
    // Extended hours: 4:00 AM - 8:00 PM ET
    if (hours < 4 || hours >= 20) return false;
    
    return true;
  };
  
  const marketOpen = isMarketActuallyOpen();
  
  // Determine session (PRE_MARKET, REGULAR, POST_MARKET)
  const getCurrentSession = () => {
    if (!marketOpen) return null;
    
    const est = new Date(currentTime.toLocaleString('en-US', { timeZone: 'America/New_York' }));
    const hours = est.getHours();
    
    if (hours < 9 || (hours === 9 && est.getMinutes() < 30)) {
      return 'PRE_MARKET';
    } else if (hours < 16) {
      return 'REGULAR';
    } else {
      return 'POST_MARKET';
    }
  };
  
  const session = getCurrentSession();
  
  // Countdown timer
  const getCountdown = () => {
    const est = new Date(currentTime.toLocaleString('en-US', { timeZone: 'America/New_York' }));
    const hours = est.getHours();
    const minutes = est.getMinutes();
    const day = est.getDay();
    
    if (marketOpen) {
      // Market is open, show time until close (8 PM)
      const closeHour = 20;
      const minutesUntilClose = (closeHour - hours - 1) * 60 + (60 - minutes);
      const hoursLeft = Math.floor(minutesUntilClose / 60);
      const minsLeft = minutesUntilClose % 60;
      return `Closes in ${hoursLeft}h ${minsLeft}m`;
    } else {
      // Market is closed
      if (day === 0) return 'Opens Mon 4:00 AM';
      if (day === 6) return 'Opens Mon 4:00 AM';
      
      // Weekday - calculate time until 4 AM
      const openHour = 4;
      let minutesUntilOpen;
      
      if (hours >= 20 || hours < 4) {
        // After 8 PM or before 4 AM - opens at 4 AM today or tomorrow
        if (hours >= 20) {
          minutesUntilOpen = (24 - hours + openHour - 1) * 60 + (60 - minutes);
        } else {
          minutesUntilOpen = (openHour - hours - 1) * 60 + (60 - minutes);
        }
      } else {
        minutesUntilOpen = 0;
      }
      
      const hoursLeft = Math.floor(minutesUntilOpen / 60);
      const minsLeft = minutesUntilOpen % 60;
      return `Opens in ${hoursLeft}h ${minsLeft}m`;
    }
  };
  
  const countdown = getCountdown();
  const pnlClass = systemStatus.totalPnL >= 0 ? 'positive' : 'negative';
  
  return (
    <div className="panel system-overview">
      <h2>
        ⚡ System Overview
        <Tooltip text="Real-time system status and performance metrics." />
      </h2>
      
      <div className="overview-grid">
        {/* Market Status */}
        <div className="overview-item">
          <div className="overview-label">
            Market
            <Tooltip text="Market hours: 4 AM - 8 PM ET (Extended). Regular: 9:30 AM - 4 PM ET." />
          </div>
          <div className={`overview-value ${marketOpen ? 'active' : 'inactive'}`}>
            {marketOpen ? 'OPEN' : 'CLOSED'}
          </div>
          {session && (
            <div className="overview-subtitle" style={{ 
              color: session === 'REGULAR' ? '#10b981' : '#f59e0b',
              fontWeight: '600',
              fontSize: '11px'
            }}>
              {session === 'PRE_MARKET' && '🌅 PRE-MARKET'}
              {session === 'REGULAR' && '📈 REGULAR'}
              {session === 'POST_MARKET' && '🌆 POST-MARKET'}
            </div>
          )}
          <div className="overview-subtitle">{countdown}</div>
        </div>
        
        {/* Volatility */}
        <div className="overview-item">
          <div className="overview-label">Volatility</div>
          <div className={`overview-value ${systemStatus.volatilityOk ? 'active' : 'warning'}`}>
            {systemStatus.volatilityOk ? 'OK' : 'HIGH'}
          </div>
          <div className="overview-subtitle">VIX: {systemStatus.vix?.toFixed(1) || 'N/A'}</div>
        </div>
        
        {/* Mode */}
        <div className="overview-item">
          <div className="overview-label">Mode</div>
          <div className="overview-value active">{systemStatus.tradingMode || 'N/A'}</div>
          <div className="overview-subtitle">
            <span className={`indicator ${connected ? 'connected' : 'disconnected'}`}>
              {connected ? '🟢' : '🔴'}
            </span>
          </div>
        </div>
        
        {/* P&L */}
        <div className="overview-item">
          <div className="overview-label">
            Total P&L
            <Tooltip text="Unrealized P&L from all open positions (updates every 5 seconds from Alpaca)" />
          </div>
          <div className={`overview-value ${pnlClass}`}>
            ${systemStatus.totalPnL?.toFixed(2) || '0.00'}
          </div>
        </div>
        
        {/* Trades */}
        <div className="overview-item">
          <div className="overview-label">Trades</div>
          <div className="overview-value">{systemStatus.totalTrades || 0}</div>
          <div className="overview-subtitle">Win: {systemStatus.winRate?.toFixed(0) || 0}%</div>
        </div>
      </div>
    </div>
  );
};
