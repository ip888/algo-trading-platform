import { useTradingStore } from '../store/tradingStore';
import './MarketHours.css';

export function MarketHours() {
  const { systemStatus } = useTradingStore();
  
  // Calculate time until market opens/closes
  const getTimeUntil = () => {
    const now = new Date();
    const estTime = new Date(now.toLocaleString('en-US', { timeZone: 'America/New_York' }));
    const hours = estTime.getHours();
    const minutes = estTime.getMinutes();
    
    let targetHour = 9;
    let targetMinute = 30;
    let message = 'Opens in';
    
    if (hours >= 9 && hours < 16) {
      // Market open, show close time
      targetHour = 16;
      targetMinute = 0;
      message = 'Closes in';
    } else if (hours >= 16) {
      // After hours, show tomorrow's open
      targetHour = 9 + 24;
      targetMinute = 30;
      message = 'Opens in';
    }
    
    const currentMinutes = hours * 60 + minutes;
    const targetMinutes = targetHour * 60 + targetMinute;
    const diff = targetMinutes - currentMinutes;
    
    const hoursUntil = Math.floor(diff / 60);
    const minutesUntil = diff % 60;
    
    return { message, hours: hoursUntil, minutes: minutesUntil };
  };
  
  const timeUntil = getTimeUntil();
  const isMarketOpen = systemStatus?.marketOpen || false;
  
  return (
    <div className="market-hours-widget">
      <div className="market-hours-header">
        <h3>🕐 Market Hours</h3>
        <div className={`market-status ${isMarketOpen ? 'open' : 'closed'}`}>
          {isMarketOpen ? '● OPEN' : '● CLOSED'}
        </div>
      </div>
      
      <div className="market-hours-content">
        <div className="hours-info">
          <div className="hours-label">Trading Hours (EST)</div>
          <div className="hours-time">9:30 AM - 4:00 PM</div>
        </div>
        
        <div className="countdown">
          <div className="countdown-label">{timeUntil.message}</div>
          <div className="countdown-time">
            {timeUntil.hours}h {timeUntil.minutes}m
          </div>
        </div>
        
        <div className="current-time">
          <div className="time-label">Current Time (EST)</div>
          <div className="time-value">
            {new Date().toLocaleTimeString('en-US', { 
              timeZone: 'America/New_York',
              hour: '2-digit',
              minute: '2-digit',
              second: '2-digit'
            })}
          </div>
        </div>
      </div>
    </div>
  );
}
