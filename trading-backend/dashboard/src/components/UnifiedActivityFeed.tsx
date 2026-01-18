import { useEffect, useState } from 'react';
import { useTradingStore } from '../store/tradingStore';
import './UnifiedActivityFeed.css';

interface ActivityItem {
  id: string;
  timestamp: number;
  type: 'buy' | 'sell' | 'alert' | 'opportunity' | 'info';
  symbol?: string;
  message: string;
  value?: string;
  emoji: string;
}

export function UnifiedActivityFeed() {
  const { orderHistory, activityLog } = useTradingStore();
  const [activities, setActivities] = useState<ActivityItem[]>([]);

  useEffect(() => {
    // Combine orders and activity log into unified feed
    const combined: ActivityItem[] = [];

    // Add orders
    orderHistory.forEach(order => {
      const isBuy = order.side === 'buy';
      combined.push({
        id: `order-${order.timestamp}`,
        timestamp: order.timestamp,
        type: isBuy ? 'buy' : 'sell',
        symbol: order.symbol,
        message: `${order.side.toUpperCase()} ${order.symbol}`,
        value: `${order.quantity} @ $${order.price.toFixed(2)}`,
        emoji: isBuy ? '🟢' : '🔴'
      });
    });

    // Add activity log
    activityLog.forEach(log => {
      const isAlert = log.level === 'WARN' || log.level === 'ERROR';
      const isSuccess = log.level === 'SUCCESS';
      
      combined.push({
        id: log.id,
        timestamp: log.timestamp,
        type: isAlert ? 'alert' : 'info',
        message: log.message,
        emoji: isAlert ? '⚠️' : isSuccess ? '✅' : '💡'
      });
    });

    // Sort by timestamp (newest first)
    combined.sort((a, b) => b.timestamp - a.timestamp);

    // Keep last 20 items
    setActivities(combined.slice(0, 20));
  }, [orderHistory, activityLog]);

  const formatTime = (timestamp: number) => {
    const date = new Date(timestamp);
    return date.toLocaleTimeString('en-US', { 
      hour: '2-digit', 
      minute: '2-digit',
      hour12: true 
    });
  };

  return (
    <div className="unified-activity-feed">
      <div className="feed-header">
        <h3>📊 Activity Feed</h3>
        <span className="feed-count">{activities.length} events</span>
      </div>

      <div className="feed-list">
        {activities.length === 0 ? (
          <div className="feed-empty">
            <p>No activity yet. Waiting for market data...</p>
          </div>
        ) : (
          activities.map(activity => (
            <div key={activity.id} className={`feed-item type-${activity.type}`}>
              <span className="feed-time">{formatTime(activity.timestamp)}</span>
              <span className="feed-emoji">{activity.emoji}</span>
              <div className="feed-content">
                {activity.symbol && (
                  <span className="feed-symbol">{activity.symbol}</span>
                )}
                <span className="feed-message">{activity.message}</span>
                {activity.value && (
                  <span className="feed-value">{activity.value}</span>
                )}
              </div>
            </div>
          ))
        )}
      </div>
    </div>
  );
}
