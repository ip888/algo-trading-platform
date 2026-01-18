import { useState, useRef, useEffect } from 'react';
import { createChart, ColorType, AreaSeries, LineSeries } from 'lightweight-charts';
import { Play, RefreshCw, ShieldAlert } from 'lucide-react';
import { CONFIG } from '../config';

interface BacktestResult {
  finalValue: number;
  totalReturn: number;
  maxDrawdown: number;
  totalTrades: number;
  winRate: number;
  sharpeRatio: number;
  equityCurve: { date: string, equity: number }[];
  trades: { date: string, type: string, price: number, shares: number, pnl: number }[];
  comparison?: BacktestResult;
}

export const BacktestDashboard = () => {
  const [symbol, setSymbol] = useState('NVDA');
  const [days, setDays] = useState(30);
  const [capital, setCapital] = useState(100000);
  const [takeProfit, setTakeProfit] = useState(0.75);
  const [stopLoss, setStopLoss] = useState(1.0);

  const [showComparison, setShowComparison] = useState(true);
  const [loading, setLoading] = useState(false);
  const [result, setResult] = useState<BacktestResult | null>(null);
  const [error, setError] = useState<string | null>(null);

  const chartContainerRef = useRef<HTMLDivElement>(null);
  const chartRef = useRef<any>(null);
  const strategySeriesRef = useRef<any>(null);
  const comparisonSeriesRef = useRef<any>(null);

  useEffect(() => {
    if (result && chartContainerRef.current) {
      if (chartRef.current) {
        chartRef.current.remove();
      }

      const chart = createChart(chartContainerRef.current, {
        layout: {
          background: { type: ColorType.Solid, color: 'transparent' },
          textColor: '#64748b',
          fontSize: 11,
          fontFamily: "'Inter', sans-serif",
        },
        grid: {
          vertLines: { color: '#f1f5f9' },
          horzLines: { color: '#f1f5f9' },
        },
        width: chartContainerRef.current.clientWidth,
        height: 480,
        timeScale: {
            borderColor: '#e2e8f0',
            timeVisible: true,
        },
        rightPriceScale: {
            borderColor: '#e2e8f0',
        },
        crosshair: {
            mode: 1,
            vertLine: { labelBackgroundColor: '#1e293b', color: '#94a3b8' },
            horzLine: { labelBackgroundColor: '#1e293b', color: '#94a3b8' }
        }
      });

      const series = chart.addSeries(AreaSeries, {
        lineColor: '#2563eb',
        topColor: 'rgba(37, 99, 235, 0.2)',
        bottomColor: 'rgba(37, 99, 235, 0.02)',
        lineWidth: 2,
        title: 'ALGO PERFORMANCE',
      });
      strategySeriesRef.current = series;

      if (result.equityCurve && Array.isArray(result.equityCurve)) {
          const data = result.equityCurve.map(item => ({
            time: item.date,
            value: item.equity
          }));
          series.setData(data);
      }

      if (result.comparison && result.comparison.equityCurve) {
         const comparisonSeries = chart.addSeries(LineSeries, {
             color: '#475569',
             lineWidth: 2,
             lineStyle: 2,
             title: 'BENCHMARK (HOLD)',
             visible: showComparison,
         });
         comparisonSeriesRef.current = comparisonSeries;

         const comparisonData = result.comparison.equityCurve.map(item => ({
             time: item.date,
             value: item.equity
         }));
         comparisonSeries.setData(comparisonData);
      }

      chart.timeScale().fitContent();
      chartRef.current = chart;

      const handleResize = () => {
        if (chartContainerRef.current) {
          chart.applyOptions({ width: chartContainerRef.current.clientWidth });
        }
      };

      window.addEventListener('resize', handleResize);
      return () => window.removeEventListener('resize', handleResize);
    }
  }, [result]);

  useEffect(() => {
    if (comparisonSeriesRef.current && result?.comparison) {
      comparisonSeriesRef.current.applyOptions({ visible: showComparison });
    }
  }, [showComparison, result]);

  const runBacktest = async () => {
    setLoading(true);
    setError(null);
    try {
      const response = await fetch(`${CONFIG.API_BASE_URL}/api/backtest`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ 
            symbol,
            days, 
            capital, 
            takeProfitPercent: takeProfit,
            stopLossPercent: stopLoss
        })
      });
      
      if (!response.ok) throw new Error('Simulation Service Unavailable. Please ensure the Java Core is running and /api/backtest is reachable.');
      const data = await response.json();
      setResult(data);
    } catch (err: any) {
      console.error(err);
      setError(err.message || 'An unknown error occurred during simulation');
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="strategy-lab">
      <div className="panel config-panel">
        <h3>🛰 Research Parameters</h3>
        <div className="form-grid">
          <div className="form-group">
            <label>Asset Symbol</label>
            <input type="text" value={symbol} onChange={e => setSymbol(e.target.value.toUpperCase())} className="input-field" />
          </div>
          <div className="form-group">
            <label>Testing Period (Days)</label>
            <input type="number" value={days} onChange={e => setDays(Number(e.target.value))} className="input-field" />
          </div>
          <div className="form-group">
            <label>Initial Liquidity ($)</label>
            <input type="number" value={capital} onChange={e => setCapital(Number(e.target.value))} className="input-field" />
          </div>
          <div className="form-group">
            <label>Take Profit (%)</label>
            <input type="number" value={takeProfit} step="0.05" onChange={e => setTakeProfit(Number(e.target.value))} className="input-field" />
          </div>
          <div className="form-group">
            <label>Stop Loss (%)</label>
            <input type="number" value={stopLoss} step="0.05" onChange={e => setStopLoss(Number(e.target.value))} className="input-field" />
          </div>
             {result?.comparison && (
                <label style={{ display: 'flex', alignItems: 'center', gap: '8px', cursor: 'pointer', fontSize: '11px' }}>
                    <input type="checkbox" checked={showComparison} onChange={e => setShowComparison(e.target.checked)} />
                    BENCHMARK OVERLAY
                </label>
             )}
          </div>

        <button onClick={runBacktest} disabled={loading} className="run-button">
          {loading ? <RefreshCw className="spin" size={16} /> : <Play size={16} />}
          {loading ? 'COMPUTING STRATEGY...' : 'EXECUTE RESEARCH SIMULATION'}
        </button>
      </div>

      {result && (
        <>
          {/* Strategy Recommendation Panel */}
          <div className="panel recommendation-panel" style={{ 
            background: 'linear-gradient(135deg, rgba(37, 99, 235, 0.08), rgba(16, 185, 129, 0.05))',
            border: '1px solid var(--accent-blue)',
            marginBottom: '20px'
          }}>
            <h3 style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
              💡 Strategy Recommendation
              <span style={{ 
                fontSize: '10px', 
                padding: '2px 8px', 
                borderRadius: '4px',
                background: result.totalReturn > (result.comparison?.totalReturn || 0) ? 'var(--accent-green)' : 'var(--accent-orange)',
                color: '#000'
              }}>
                {result.totalReturn > (result.comparison?.totalReturn || 0) ? 'OUTPERFORMING' : 'UNDERPERFORMING'}
              </span>
            </h3>
            
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(280px, 1fr))', gap: '16px', marginTop: '12px' }}>
              {/* Overall Assessment */}
              <div style={{ padding: '12px', background: 'rgba(0,0,0,0.2)', borderRadius: '8px' }}>
                <div style={{ fontSize: '10px', color: 'var(--text-dim)', marginBottom: '6px' }}>📊 OVERALL ASSESSMENT</div>
                <div style={{ fontSize: '13px', lineHeight: '1.5' }}>
                  {result.winRate >= 60 && result.sharpeRatio > 0.5 ? (
                    <span style={{ color: 'var(--accent-green)' }}>✅ Strong strategy with good risk-adjusted returns</span>
                  ) : result.winRate >= 50 && result.sharpeRatio > 0 ? (
                    <span style={{ color: 'var(--accent-blue)' }}>⚡ Viable strategy, consider parameter tuning</span>
                  ) : result.winRate >= 40 ? (
                    <span style={{ color: 'var(--accent-orange)' }}>⚠️ Marginal strategy, needs optimization</span>
                  ) : (
                    <span style={{ color: 'var(--accent-red)' }}>❌ Strategy underperforming, review parameters</span>
                  )}
                </div>
              </div>

              {/* TP/SL Recommendation */}
              <div style={{ padding: '12px', background: 'rgba(0,0,0,0.2)', borderRadius: '8px' }}>
                <div style={{ fontSize: '10px', color: 'var(--text-dim)', marginBottom: '6px' }}>🎯 TP/SL ADVICE</div>
                <div style={{ fontSize: '12px', lineHeight: '1.6' }}>
                  {(() => {
                    const tpHits = result.trades?.filter(t => t.type === 'TP_SELL').length || 0;
                    const slHits = result.trades?.filter(t => t.type === 'SL_SELL').length || 0;
                    const ratio = tpHits / Math.max(slHits, 1);
                    
                    if (ratio < 0.5) {
                      return <><span style={{color: 'var(--accent-orange)'}}>↑ Increase TP</span> to {(takeProfit * 1.5).toFixed(2)}% or <span style={{color: 'var(--accent-orange)'}}>↓ Decrease SL</span> to {(stopLoss * 0.75).toFixed(2)}%</>;
                    } else if (ratio > 2) {
                      return <><span style={{color: 'var(--accent-green)'}}>✓ Good TP/SL balance.</span> Try tighter SL at {(stopLoss * 0.8).toFixed(2)}% for better risk control</>;
                    } else {
                      return <>Current TP:{takeProfit}% / SL:{stopLoss}% is balanced. Try TP:{(takeProfit * 1.25).toFixed(2)}% for more profit capture</>;
                    }
                  })()}
                </div>
              </div>

              {/* Performance vs Benchmark */}
              <div style={{ padding: '12px', background: 'rgba(0,0,0,0.2)', borderRadius: '8px' }}>
                <div style={{ fontSize: '10px', color: 'var(--text-dim)', marginBottom: '6px' }}>📈 VS BUY & HOLD</div>
                <div style={{ fontSize: '12px', lineHeight: '1.6' }}>
                  {result.comparison && (
                    <>
                      <div>Strategy: <span style={{color: result.totalReturn >= 0 ? 'var(--accent-green)' : 'var(--accent-red)'}}>{result.totalReturn >= 0 ? '+' : ''}{result.totalReturn.toFixed(2)}%</span></div>
                      <div>Benchmark: <span style={{color: result.comparison.totalReturn >= 0 ? 'var(--accent-green)' : 'var(--accent-red)'}}>{result.comparison.totalReturn >= 0 ? '+' : ''}{result.comparison.totalReturn.toFixed(2)}%</span></div>
                      <div style={{ marginTop: '4px', color: result.totalReturn > result.comparison.totalReturn ? 'var(--accent-green)' : 'var(--accent-orange)' }}>
                        {result.totalReturn > result.comparison.totalReturn 
                          ? `✓ Beating benchmark by ${(result.totalReturn - result.comparison.totalReturn).toFixed(2)}%`
                          : `△ ${(result.comparison.totalReturn - result.totalReturn).toFixed(2)}% below benchmark`}
                      </div>
                    </>
                  )}
                </div>
              </div>

              {/* Quick Actions */}
              <div style={{ padding: '12px', background: 'rgba(0,0,0,0.2)', borderRadius: '8px' }}>
                <div style={{ fontSize: '10px', color: 'var(--text-dim)', marginBottom: '6px' }}>⚡ SUGGESTED NEXT TESTS</div>
                <div style={{ fontSize: '11px', lineHeight: '1.8' }}>
                  {result.maxDrawdown > 10 && <div>• Try SL: {(stopLoss * 0.7).toFixed(2)}% to reduce drawdown</div>}
                  {result.winRate < 50 && <div>• Try TP: {(takeProfit * 0.8).toFixed(2)}% for faster exits</div>}
                  {result.totalTrades < 5 && <div>• Extend period to {days * 2} days for more trades</div>}
                  {result.sharpeRatio < 0 && <div>• Test with TP: {(takeProfit + 0.5).toFixed(2)}% SL: {(stopLoss * 0.5).toFixed(2)}%</div>}
                  {result.winRate >= 55 && result.sharpeRatio > 0.5 && <div style={{color: 'var(--accent-green)'}}>• Strategy is profitable - consider live testing</div>}
                </div>
              </div>
            </div>
          </div>

          <div className="stats-strip" style={{ marginBottom: '30px', border: '1px solid var(--border-dim)' }}>
             <div className="stat-card">
               <span className="label">STRATEGY ALPHA</span>
               <div className={`value ${result.totalReturn >= 0 ? 'positive' : 'negative'}`}>
                 {result.totalReturn >= 0 ? '+' : ''}{result.totalReturn?.toFixed(2)}%
               </div>
               <div style={{ fontSize: '9px', color: 'var(--text-dim)', fontFamily: 'monospace' }}>Final Equity: ${result.finalValue?.toLocaleString()}</div>
             </div>
             <div className="stat-card">
               <span className="label">HIT RATE</span>
               <div className="value highlight">{result.winRate?.toFixed(1)}%</div>
               <div style={{ fontSize: '9px', color: 'var(--text-dim)', fontFamily: 'monospace' }}>Total Executed: {result.totalTrades}</div>
             </div>
             <div className="stat-card">
               <span className="label">RISK PROFILE</span>
               <div className="value negative">{result.maxDrawdown?.toFixed(2)}% <span style={{ fontSize: '10px', verticalAlign: 'middle' }}>MDD</span></div>
             </div>
             <div className="stat-card">
               <span className="label">SHARPE RATIO</span>
               <div className="value highlight">{result.sharpeRatio?.toFixed(2)}</div>
             </div>
          </div>

          <div className="panel chart-panel">
            <h3>📈 Equity Trajectory Matrix</h3>
            <div ref={chartContainerRef} className="chart-container" />
          </div>

          <div className="panel results-panel">
            <h3>📜 Simulation Execution Logs</h3>
            <div className="table-container">
              <table className="positions-table">
                <thead>
                  <tr>
                    <th>Timestamp</th>
                    <th>Action</th>
                    <th>Price</th>
                    <th>Volume</th>
                    <th>Realized P&L</th>
                  </tr>
                </thead>
                <tbody>
                  {result.trades && result.trades.map((trade, i) => (
                    <tr key={i}>
                      <td>{trade.date}</td>
                      <td className={trade.type.includes('BUY') ? 'positive' : 'negative'}>{trade.type}</td>
                      <td>${trade.price?.toFixed(2)}</td>
                      <td>{trade.shares}</td>
                      <td className={trade.pnl > 0 ? 'positive' : trade.pnl < 0 ? 'negative' : ''} style={{ fontWeight: 800 }}>
                        {trade.pnl !== 0 && (trade.pnl > 0 ? '+' : '')}${trade.pnl?.toFixed(2)}
                      </td>
                    </tr>
                  ))}
                  {(!result.trades || result.trades.length === 0) && (
                    <tr>
                      <td colSpan={5} style={{ textAlign: 'center', padding: '40px', color: 'var(--text-dim)' }}>
                        SYSTEM INERTIA: No trade triggers met during simulation period.
                      </td>
                    </tr>
                  )}
                </tbody>
              </table>
            </div>
          </div>
        </>
      )}
      
      {error && (
        <div className="panel" style={{ borderColor: 'var(--neon-red)', background: 'rgba(255, 51, 102, 0.05)' }}>
          <h3 style={{ color: 'var(--neon-red)' }}><ShieldAlert size={14} /> SIMULATION ERROR</h3>
          <p style={{ fontSize: '12px', color: 'var(--text-secondary)' }}>{error}</p>
        </div>
      )}
    </div>
  );
};
