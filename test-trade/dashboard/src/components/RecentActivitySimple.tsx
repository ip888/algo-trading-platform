import { useTradingStore } from '../store/tradingStore';

export const RecentActivitySimple = () => {
  const { activityLog } = useTradingStore();
  
  // Show only last 5 activities
  const recentActivities = activityLog.slice(0, 5);
  
  return (
    <div className="panel recent-activity-simple">
      <h2>📋 Recent Activity</h2>
      
      {recentActivities.length === 0 ? (
        <p>No recent activity</p>
      ) : (
        <div className="activity-list">
          {recentActivities.map((activity) => {
            const time = new Date(activity.timestamp).toLocaleTimeString('en-US', {
              hour: '2-digit',
              minute: '2-digit'
            });
            
            const levelClass = activity.level.toLowerCase();
            const icon = {
              'SUCCESS': '✅',
              'INFO': '•',
              'WARN': '⚠️',
              'ERROR': '❌'
            }[activity.level] || '•';
            
            return (
              <div key={activity.id} className={`activity-item ${levelClass}`}>
                <span className="activity-time">{time}</span>
                <span className="activity-icon">{icon}</span>
                <span className="activity-message">{activity.message}</span>
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
};
