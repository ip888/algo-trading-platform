import { useTradingStore } from '../store/tradingStore';
import { useState } from 'react';

export const Notifications = () => {
  const { notifications, clearNotifications } = useTradingStore();
  const [isOpen, setIsOpen] = useState(false);
  
  return (
    <div className="notifications-container panel">
      <div className="notifications-header" onClick={() => setIsOpen(!isOpen)}>
        <h2>
          <span className="toggle-icon">{isOpen ? '▼' : '▶'}</span>
          🔔 Trading Actions ({notifications.length})
        </h2>
        {notifications.length > 0 && (
          <button 
            className="clear-btn" 
            onClick={(e) => {
              e.stopPropagation();
              clearNotifications();
            }}
          >
            Clear
          </button>
        )}
      </div>
      
      {isOpen && (
        <div className="notifications-list">
          {notifications.length === 0 ? (
            <div className="no-notifications">No recent actions</div>
          ) : (
            notifications.map((notif) => (
              <div key={notif.id} className={`notification ${notif.type.toLowerCase()}`}>
                <span className="notification-time">{notif.time}</span>
                <span className="notification-message">{notif.message}</span>
              </div>
            ))
          )}
        </div>
      )}
    </div>
  );
};
