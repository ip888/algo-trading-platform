import './PerformanceCharts.css';

export function PerformanceCharts() {
  
  // Mock data for charts
  const pnlData = [
    { time: '9:30', value: 0 },
    { time: '10:00', value: 5.2 },
    { time: '11:00', value: 8.7 },
    { time: '12:00', value: 6.3 },
    { time: '13:00', value: 12.1 },
    { time: '14:00', value: 9.8 },
    { time: '15:00', value: 15.4 },
    { time: '16:00', value: 12.45 }
  ];
  
  const maxPnL = Math.max(...pnlData.map(d => d.value));
  const minPnL = Math.min(...pnlData.map(d => d.value));
  const range = maxPnL - minPnL;
  
  return (
    <div className="performance-charts">
      <h3>📈 Performance Analytics</h3>
      
      <div className="charts-grid">
        <div className="chart-card">
          <h4>Real-time P&L</h4>
          <div className="pnl-chart">
            <svg viewBox="0 0 400 150" className="chart-svg">
              <defs>
                <linearGradient id="pnlGradient" x1="0%" y1="0%" x2="0%" y2="100%">
                  <stop offset="0%" stopColor="#10b981" stopOpacity="0.3"/>
                  <stop offset="100%" stopColor="#10b981" stopOpacity="0"/>
                </linearGradient>
              </defs>
              
              {/* Grid lines */}
              {[0, 1, 2, 3, 4].map(i => (
                <line 
                  key={i}
                  x1="0" 
                  y1={i * 37.5} 
                  x2="400" 
                  y2={i * 37.5}
                  stroke="#e5e7eb"
                  strokeWidth="1"
                />
              ))}
              
              {/* P&L line */}
              <polyline
                points={pnlData.map((d, i) => {
                  const x = (i / (pnlData.length - 1)) * 400;
                  const y = 150 - ((d.value - minPnL) / range) * 150;
                  return `${x},${y}`;
                }).join(' ')}
                fill="url(#pnlGradient)"
                stroke="#10b981"
                strokeWidth="2"
              />
              
              {/* Data points */}
              {pnlData.map((d, i) => {
                const x = (i / (pnlData.length - 1)) * 400;
                const y = 150 - ((d.value - minPnL) / range) * 150;
                return (
                  <circle
                    key={i}
                    cx={x}
                    cy={y}
                    r="3"
                    fill="#10b981"
                  />
                );
              })}
            </svg>
            <div className="chart-labels">
              {pnlData.map((d, i) => (
                <span key={i} className="time-label">{d.time}</span>
              ))}
            </div>
          </div>
          <div className="chart-value">+${pnlData[pnlData.length - 1].value.toFixed(2)}</div>
        </div>
        
        <div className="chart-card">
          <h4>Win Rate</h4>
          <div className="win-rate-chart">
            <svg viewBox="0 0 120 120" className="donut-chart">
              <circle cx="60" cy="60" r="50" fill="none" stroke="#e5e7eb" strokeWidth="20"/>
              <circle 
                cx="60" 
                cy="60" 
                r="50" 
                fill="none" 
                stroke="#10b981" 
                strokeWidth="20"
                strokeDasharray="314"
                strokeDashoffset={314 - (314 * 0.652)}
                transform="rotate(-90 60 60)"
              />
              <text x="60" y="65" textAnchor="middle" fontSize="24" fontWeight="700" fill="#1f2937">
                65%
              </text>
            </svg>
          </div>
          <div className="chart-subtitle">15 wins / 23 trades</div>
        </div>
        
        <div className="chart-card">
          <h4>Risk Metrics</h4>
          <div className="risk-metrics">
            <div className="risk-item">
              <span className="risk-label">Max Drawdown</span>
              <span className="risk-value negative">-2.3%</span>
            </div>
            <div className="risk-item">
              <span className="risk-label">Sharpe Ratio</span>
              <span className="risk-value">1.85</span>
            </div>
            <div className="risk-item">
              <span className="risk-label">Avg Win</span>
              <span className="risk-value positive">+$1.82</span>
            </div>
            <div className="risk-item">
              <span className="risk-label">Avg Loss</span>
              <span className="risk-value negative">-$0.95</span>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}
