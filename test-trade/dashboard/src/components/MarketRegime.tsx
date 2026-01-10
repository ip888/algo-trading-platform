import { useTradingStore } from '../store/tradingStore';
import { Tooltip } from './Tooltip';

export const MarketRegime = () => {
  const { systemStatus } = useTradingStore();
  
  if (!systemStatus) return null;
  
  const vix = systemStatus.vix || 15.0;
  const trend = systemStatus.marketTrend || 'NEUTRAL';
  
  // Determine Regime State
  let regimeState = 'NORMAL';
  let regimeColor = 'var(--accent-green)';
  let activeStrategy = 'RSI Range';
  
  if (vix > 30) {
    regimeState = 'EXTREME';
    regimeColor = 'var(--accent-red)';
    activeStrategy = 'Mean Reversion';
  } else if (trend === 'BEARISH') {
    regimeState = 'DEFENSIVE';
    regimeColor = 'var(--accent-orange)';
    activeStrategy = 'Inverse ETFs';
  } else if (vix > 20) {
    regimeState = 'VOLATILE';
    regimeColor = 'var(--accent-yellow)';
    activeStrategy = 'MACD Trend';
  }
  
  return (
    <div className="panel regime-panel">
      <h2>
        🌊 Market Regime
        <Tooltip text="Current market conditions and active strategy." />
      </h2>
      
      <div className="regime-gauge">
        <div className="gauge-label" style={{ color: regimeColor }}>
          {regimeState}
        </div>
        <div className="gauge-sublabel">VIX: {vix.toFixed(1)}</div>
      </div>
      
      <div className="strategy-info">
        <div className="strategy-label">Active Strategy</div>
        <div className="strategy-value">{activeStrategy}</div>
      </div>
      
      <div className="regime-details">
        <div className="detail-row">
          <span>Trend</span>
          <span className={`trend-text ${trend.toLowerCase()}`}>{trend}</span>
        </div>
        <div className="detail-row">
          <span>Risk Level</span>
          <span>{vix > 20 ? 'REDUCED SIZE' : 'STANDARD'}</span>
        </div>
      </div>
    </div>
  );
};
