import { useTradingStore } from '../store/tradingStore';
import { Tooltip } from './Tooltip';
import styles from './AdaptiveParameters.module.css';

export const AdaptiveParameters = () => {
  const { adaptiveParams } = useTradingStore();
  
  if (!adaptiveParams) return null;
  
  const { 
    kellyFraction, 
    maxPosition, 
    minAligned, 
    requireAlignment, 
    winRate, 
    totalTrades,
    lastAdjusted 
  } = adaptiveParams;
  
  return (
    <div className="panel adaptive-panel">
      <h2>
        🤖 Adaptive Parameters
        <Tooltip text="Auto-tuned parameters based on performance" />
      </h2>
      
      <div className={styles.adaptiveGrid}>
        <div className={styles.paramCard}>
          <div className={styles.paramLabel}>Kelly Fraction</div>
          <div className={styles.paramValue}>{(kellyFraction * 100).toFixed(0)}%</div>
          <div className={styles.paramRange}>Range: 10-30%</div>
        </div>
        
        <div className={styles.paramCard}>
          <div className={styles.paramLabel}>Max Position</div>
          <div className={styles.paramValue}>{maxPosition}%</div>
          <div className={styles.paramRange}>Range: 10-25%</div>
        </div>
        
        <div className={styles.paramCard}>
          <div className={styles.paramLabel}>Min Aligned</div>
          <div className={styles.paramValue}>{minAligned}/3</div>
          <div className={styles.paramRange}>Timeframes</div>
        </div>
        
        <div className={styles.paramCard}>
          <div className={styles.paramLabel}>Alignment</div>
          <div className={styles.paramValue}>{requireAlignment ? 'Required' : 'Optional'}</div>
          <div className={styles.paramRange}>Entry filter</div>
        </div>
      </div>
      
      <div className={styles.performanceBasis}>
        <div className={styles.basisRow}>
          <span>Based on:</span>
          <span>{totalTrades} trades, {winRate.toFixed(1)}% win rate</span>
        </div>
        <div className={styles.basisRow}>
          <span>Last adjusted:</span>
          <span>{lastAdjusted}</span>
        </div>
      </div>
    </div>
  );
};
