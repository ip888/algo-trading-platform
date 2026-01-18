import { useTradingStore } from '../store/tradingStore';
import './ProductionMetrics.css';

export function ProductionMetrics() {
  const { } = useTradingStore();
  
  // Mock data - will be replaced with real metrics from backend
  const metrics = {
    circuitBreaker: 'CLOSED',
    apiCalls: 1247,
    apiLimit: 200,
    successRate: 99.2,
    avgLatency: 145,
    failedCalls: 12
  };
  
  const rateLimitPercent = (metrics.apiCalls / (metrics.apiLimit * 60)) * 100;
  
  return (
    <div className="production-metrics">
      <h3>⚡ Production Metrics</h3>
      
      <div className="metrics-grid">
        <div className="metric-card circuit-breaker">
          <div className="metric-label">Circuit Breaker</div>
          <div className={`metric-value cb-${metrics.circuitBreaker.toLowerCase()}`}>
            {metrics.circuitBreaker}
          </div>
          <div className="metric-subtitle">Resilience Status</div>
        </div>
        
        <div className="metric-card">
          <div className="metric-label">API Rate Limit</div>
          <div className="metric-value">{rateLimitPercent.toFixed(1)}%</div>
          <div className="metric-bar">
            <div 
              className="metric-bar-fill" 
              style={{ width: `${Math.min(rateLimitPercent, 100)}%` }}
            />
          </div>
          <div className="metric-subtitle">{metrics.apiCalls} / {metrics.apiLimit * 60} per hour</div>
        </div>
        
        <div className="metric-card">
          <div className="metric-label">Success Rate</div>
          <div className="metric-value success">{metrics.successRate}%</div>
          <div className="metric-subtitle">{metrics.apiCalls - metrics.failedCalls} successful</div>
        </div>
        
        <div className="metric-card">
          <div className="metric-label">Avg Latency</div>
          <div className="metric-value latency">{metrics.avgLatency}ms</div>
          <div className="metric-subtitle">API Response Time</div>
        </div>
      </div>
      
      <div className="metrics-footer">
        <span className="footer-item">🔄 Virtual Threads Active</span>
        <span className="footer-item">🛡️ Retry: 3 attempts</span>
        <span className="footer-item">📊 Prometheus Ready</span>
      </div>
    </div>
  );
}
