import React, { useEffect, useState } from 'react';
import './AIStatus.css';
import { CONFIG } from '../config';

interface AIMetrics {
  sentimentScore: number;
  sentimentSymbol: string;
  mlWinProbability: number;
  anomalySeverity: number;
  anomalyAction: string;
  riskScore: number;
  tradesFiltered: number;
  lastUpdate: string;
}

export const AIStatus: React.FC = () => {
  const [metrics, setMetrics] = useState<AIMetrics>({
    sentimentScore: 0,
    sentimentSymbol: '-',
    mlWinProbability: 0,
    anomalySeverity: 0,
    anomalyAction: 'CONTINUE',
    riskScore: 0,
    tradesFiltered: 0,
    lastUpdate: new Date().toLocaleTimeString()
  });

  useEffect(() => {
    // Poll for AI metrics every 5 seconds
    const fetchMetrics = async () => {
      try {
        const response = await fetch(`${CONFIG.API_BASE_URL}/api/ai/metrics`);
        if (response.ok) {
          const data = await response.json();
          setMetrics({
            ...data,
            lastUpdate: new Date().toLocaleTimeString()
          });
        }
      } catch (error) {
        console.error('Failed to fetch AI metrics:', error);
      }
    };

    fetchMetrics();
    const interval = setInterval(fetchMetrics, 5000);
    return () => clearInterval(interval);
  }, []);

  const getSentimentColor = (score: number) => {
    if (score > 0.5) return '#10b981'; // Green - very bullish
    if (score > 0.2) return '#3b82f6'; // Blue - bullish
    if (score > -0.2) return '#6b7280'; // Gray - neutral
    if (score > -0.5) return '#f59e0b'; // Orange - bearish
    return '#ef4444'; // Red - very bearish
  };

  const getSentimentLabel = (score: number) => {
    if (score > 0.5) return 'VERY BULLISH';
    if (score > 0.2) return 'BULLISH';
    if (score > -0.2) return 'NEUTRAL';
    if (score > -0.5) return 'BEARISH';
    return 'VERY BEARISH';
  };

  const getAnomalyColor = (severity: number) => {
    if (severity > 75) return '#ef4444'; // Red - critical
    if (severity > 50) return '#f59e0b'; // Orange - high
    if (severity > 25) return '#eab308'; // Yellow - moderate
    return '#10b981'; // Green - low
  };

  const getRiskColor = (score: number) => {
    if (score > 70) return '#ef4444'; // Red - high risk
    if (score > 40) return '#f59e0b'; // Orange - moderate
    return '#10b981'; // Green - low risk
  };

  return (
    <div className="ai-status-widget-compact">
      <div className="widget-header-compact">
        <h4>🧠 AI Intelligence</h4>
        <span className="status-dot active"></span>
      </div>

      <div className="ai-metrics-compact">
        {/* Sentiment Result */}
        <div className="ai-metric-compact">
          <span className="metric-icon-small">📰</span>
          <div className="metric-info">
            <span className="metric-label-small">Sentiment</span>
            <span className="metric-result" style={{ color: getSentimentColor(metrics.sentimentScore) }}>
              {getSentimentLabel(metrics.sentimentScore)} ({metrics.sentimentScore.toFixed(1)})
            </span>
          </div>
        </div>

        {/* ML Prediction Result */}
        <div className="ai-metric-compact">
          <span className="metric-icon-small">🤖</span>
          <div className="metric-info">
            <span className="metric-label-small">ML Prediction</span>
            <span className="metric-result" style={{ 
              color: metrics.mlWinProbability >= 60 ? '#10b981' : '#ef4444' 
            }}>
              {metrics.mlWinProbability >= 60 ? '✅ PASS' : '❌ REJECT'} ({metrics.mlWinProbability.toFixed(0)}%)
            </span>
          </div>
        </div>

        {/* Anomaly Result */}
        <div className="ai-metric-compact">
          <span className="metric-icon-small">🔍</span>
          <div className="metric-info">
            <span className="metric-label-small">Anomaly</span>
            <span className="metric-result" style={{ color: getAnomalyColor(metrics.anomalySeverity) }}>
              {metrics.anomalyAction === 'CONTINUE' ? '✅ Normal' : `⚠️ ${metrics.anomalyAction}`}
            </span>
          </div>
        </div>

        {/* Risk Result */}
        <div className="ai-metric-compact">
          <span className="metric-icon-small">⚡</span>
          <div className="metric-info">
            <span className="metric-label-small">Risk Level</span>
            <span className="metric-result" style={{ color: getRiskColor(metrics.riskScore) }}>
              {metrics.riskScore > 70 ? '🔴 HIGH' : metrics.riskScore > 40 ? '🟡 MOD' : '🟢 LOW'}
            </span>
          </div>
        </div>
      </div>

      {/* AI Impact Summary */}
      <div className="ai-impact">
        <span className="impact-label">Trades Filtered:</span>
        <span className="impact-value">{metrics.tradesFiltered}</span>
        <span className="impact-detail">AI saved from bad trades</span>
      </div>
    </div>
  );
};
