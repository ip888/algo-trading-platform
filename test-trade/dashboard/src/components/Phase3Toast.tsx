import { useEffect, useState } from 'react';
import { Brain, TrendingUp, Target, Activity } from 'lucide-react';
import { Tooltip } from './Tooltip';
import { CONFIG } from '../config';

export const Phase3Toast = () => {
  const [stats, setStats] = useState({
    mlScore: 0,
    adaptiveSize: 0,
    trailingStop: 0,
    fpslIncome: 0,
    marketBreadth: 0,
    activeStrategies: 0
  });

  useEffect(() => {
    const ws = new WebSocket(CONFIG.WS_URL);

    ws.onmessage = (event) => {
      try {
        const data = JSON.parse(event.data);
        
        if (data.type === 'PHASE3_ML_SCORE') {
          setStats(prev => ({ ...prev, mlScore: data.score }));
        }
        
        if (data.type === 'PHASE3_ADAPTIVE_SIZE') {
          setStats(prev => ({ ...prev, adaptiveSize: data.size }));
        }
        
        if (data.type === 'PHASE3_MARKET_BREADTH') {
          setStats(prev => ({ ...prev, marketBreadth: data.breadth }));
        }
        
        if (data.type === 'FPSL_INCOME') {
          setStats(prev => ({ ...prev, fpslIncome: data.income }));
        }
      } catch (e) {
        console.error("Failed to parse WS message", e);
      }
    };

    return () => ws.close();
  }, []);

  return (
    <div className="panel full-width phase3-panel">
      <h2>
        <Brain className="w-5 h-5 text-purple-400" />
        Phase 3: Active AI Trading
        <Tooltip text="Advanced ML-driven features active in real-time." />
      </h2>
      
      <div className="phase3-grid">
        <div className="phase3-card ml-card">
          <div className="card-header">
            <Brain className="w-4 h-4" />
            <span>ML Confidence</span>
          </div>
          <div className="card-value">{stats.mlScore.toFixed(1)}</div>
          <div className="card-sub">Neuromorphic Score</div>
        </div>

        <div className="phase3-card adaptive-card">
          <div className="card-header">
            <TrendingUp className="w-4 h-4" />
            <span>Adaptive Size</span>
          </div>
          <div className="card-value">${stats.adaptiveSize.toFixed(0)}</div>
          <div className="card-sub">Dynamic Allocation</div>
        </div>

        <div className="phase3-card fpsl-card">
          <div className="card-header">
            <Target className="w-4 h-4" />
            <span>FPSL Income</span>
          </div>
          <div className="card-value positive">+${stats.fpslIncome.toFixed(2)}</div>
          <div className="card-sub">Passive Revenue</div>
        </div>

        <div className="phase3-card breadth-card">
          <div className="card-header">
            <Activity className="w-4 h-4" />
            <span>Market Breadth</span>
          </div>
          <div className="card-value">{(stats.marketBreadth * 100).toFixed(0)}%</div>
          <div className="card-sub">Sector Sentiment</div>
        </div>
      </div>

      <style>{`
        .phase3-grid {
          display: grid;
          grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
          gap: 1rem;
        }
        
        .phase3-card {
          background: rgba(255, 255, 255, 0.03);
          border: 1px solid rgba(255, 255, 255, 0.05);
          border-radius: 0.75rem;
          padding: 1rem;
          display: flex;
          flex-direction: column;
          align-items: center;
          text-align: center;
          transition: all 0.2s ease;
        }
        
        .phase3-card:hover {
          transform: translateY(-2px);
          background: rgba(255, 255, 255, 0.05);
        }
        
        .card-header {
          display: flex;
          align-items: center;
          gap: 0.5rem;
          color: var(--text-secondary);
          font-size: 0.8rem;
          margin-bottom: 0.5rem;
          text-transform: uppercase;
          letter-spacing: 0.05em;
        }
        
        .card-value {
          font-size: 1.8rem;
          font-weight: 700;
          margin-bottom: 0.2rem;
          background: linear-gradient(135deg, #fff 0%, #cbd5e1 100%);
          -webkit-background-clip: text;
          -webkit-text-fill-color: transparent;
        }
        
        .ml-card .card-value { background: linear-gradient(135deg, #c084fc 0%, #a855f7 100%); -webkit-text-fill-color: transparent; -webkit-background-clip: text; }
        .adaptive-card .card-value { background: linear-gradient(135deg, #34d399 0%, #10b981 100%); -webkit-text-fill-color: transparent; -webkit-background-clip: text; }
        .fpsl-card .card-value { background: linear-gradient(135deg, #fbbf24 0%, #f59e0b 100%); -webkit-text-fill-color: transparent; -webkit-background-clip: text; }
        .breadth-card .card-value { background: linear-gradient(135deg, #22d3ee 0%, #06b6d4 100%); -webkit-text-fill-color: transparent; -webkit-background-clip: text; }
        
        .card-sub {
          font-size: 0.7rem;
          color: var(--text-muted);
        }
      `}</style>
    </div>
  );
};
