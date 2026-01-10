import './OperationalEventsPanel.css';
import { useTradingStore } from '../store/tradingStore';

function OperationalEventsPanel() {
  const marketHours = useTradingStore(state => state.marketHours);
  const health = useTradingStore(state => state.healthStatus);
  const readiness = useTradingStore(state => state.readinessReport);
  const events = useTradingStore(state => state.operationalEvents);


  const getPhaseIcon = (phase: string) => {
    switch (phase) {
      case 'OPEN': return '🟢';
      case 'PRE_MARKET': return '🌅';
      case 'POST_MARKET': return '🌆';
      case 'CLOSED': return '🔴';
      default: return '⚪';
    }
  };

  const getStatusColor = (status: string) => {
    switch (status) {
      case 'HEALTHY': return 'var(--accent-green)';
      case 'DEGRADED': return 'var(--accent-orange)';
      case 'CRITICAL': return 'var(--accent-red)';
      case 'EMERGENCY': return 'var(--accent-red)';
      default: return 'var(--text-muted)';
    }
  };

  const formatUptime = (seconds: number) => {
    const hours = Math.floor(seconds / 3600);
    const mins = Math.floor((seconds % 3600) / 60);
    return `${hours}h ${mins}m`;
  };

  return (
    <div className="operational-events">
      <h3>🏥 Operational Status</h3>

      {/* Market Hours Section */}
      <div className="ops-section">
        <h4>Market Hours</h4>
        {marketHours ? (
          <div className="market-status">
            <div className="status-row">
              <span className="label">Status:</span>
              <span className="value">
                {getPhaseIcon(marketHours.phase)} {marketHours.phase}
              </span>
            </div>
            {marketHours.phase === 'CLOSED' && marketHours.minutesToOpen > 0 && (
              <div className="status-row">
                <span className="label">Opens in:</span>
                <span className="value">{Math.floor(marketHours.minutesToOpen / 60)}h {marketHours.minutesToOpen % 60}m</span>
              </div>
            )}
            {marketHours.phase === 'OPEN' && marketHours.minutesToClose > 0 && (
              <div className="status-row">
                <span className="label">Closes in:</span>
                <span className="value">{Math.floor(marketHours.minutesToClose / 60)}h {marketHours.minutesToClose % 60}m</span>
              </div>
            )}
          </div>
        ) : (
          <div className="loading">Loading market hours...</div>
        )}
      </div>

      {/* System Health Section */}
      <div className="ops-section">
        <h4>System Health</h4>
        {health ? (
          <div className="health-status">
            <div className="status-row overall">
              <span className="label">Overall:</span>
              <span className="value" style={{ color: getStatusColor(health.overall) }}>
                {health.overall}
              </span>
            </div>
            <div className="status-row">
              <span className="label">Uptime:</span>
              <span className="value">{formatUptime(health.uptimeSeconds)}</span>
            </div>
            <div className="components">
              {health.components.map((comp, idx) => (
                <div key={idx} className="component-row">
                  <span className="comp-name">{comp.component}</span>
                  <span 
                    className="comp-status" 
                    style={{ color: getStatusColor(comp.status) }}
                  >
                    {comp.status === 'HEALTHY' ? '✓' : comp.status === 'DEGRADED' ? '⚠' : '✗'}
                  </span>
                </div>
              ))}
            </div>
            <div className="recommendation">{health.recommendation}</div>
          </div>
        ) : (
          <div className="loading">Waiting for health check...</div>
        )}
      </div>

      {/* Pre-Market Readiness */}
      {readiness && (
        <div className="ops-section">
          <h4>Pre-Market Readiness</h4>
          <div className={`readiness-badge ${readiness.ready ? 'ready' : 'not-ready'}`}>
            {readiness.ready ? '✅ READY' : '⚠️ NOT READY'}
          </div>
          <div className="readiness-checks">
            <div className="check-row">
              <span>Alpaca API</span>
              <span>{readiness.alpacaConnected ? '✓' : '✗'}</span>
            </div>
            <div className="check-row">
              <span>Config</span>
              <span>{readiness.configLoaded ? '✓' : '✗'}</span>
            </div>
            <div className="check-row">
              <span>Watchlist</span>
              <span>{readiness.watchlistReady ? '✓' : '✗'}</span>
            </div>
          </div>
        </div>
      )}

      {/* Recent Events */}
      {events.length > 0 && (
        <div className="ops-section">
          <h4>Recent Events</h4>
          <div className="events-list">
            {events.slice().reverse().map((event, idx) => (
              <div 
                key={idx} 
                className={`event-row severity-${event.severity.toLowerCase()}`}
              >
                <div className="event-type">{event.eventType}</div>
                <div className="event-message">{event.message}</div>
              </div>
            ))}
          </div>
        </div>
      )}
    </div>
  );
}

export default OperationalEventsPanel;
