import { useTradingStore } from '../store/tradingStore';
import { Tooltip } from './Tooltip';
import styles from './MultiTimeframeView.module.css';

export const MultiTimeframeView = () => {
  const { timeframeSignals } = useTradingStore();
  
  if (!timeframeSignals || timeframeSignals.length === 0) return null;
  
  const aligned = timeframeSignals.filter(tf => tf.signal === 'BUY').length;
  const isAligned = aligned >= 2;
  
  const getTrendColor = (trend: string) => {
    if (trend === 'BULLISH') return 'var(--accent-green)';
    if (trend === 'BEARISH') return 'var(--accent-red)';
    return 'var(--text-secondary)';
  };
  
  const getSignalColor = (signal: string) => {
    if (signal === 'BUY') return 'var(--accent-green)';
    if (signal === 'SELL') return 'var(--accent-red)';
    return 'var(--accent-yellow)';
  };
  
  return (
    <div className="panel timeframe-panel">
      <h2>
        ⏱️ Multi-Timeframe Analysis
        <Tooltip text="Trend analysis across multiple timeframes" />
      </h2>
      
      <div className={styles.alignmentStatus}>
        <div className={`${styles.statusBadge} ${isAligned ? styles.aligned : styles.notAligned}`}>
          {isAligned ? '✓ Aligned' : '✗ Not Aligned'}
        </div>
        <div className={styles.alignmentCount}>{aligned}/{timeframeSignals.length} timeframes agree</div>
      </div>
      
      <div className={styles.timeframesList}>
        {timeframeSignals.map(tf => (
          <div key={tf.name} className={styles.timeframeRow}>
            <div className={styles.tfName}>{tf.name}</div>
            
            <div className={styles.tfTrend} style={{ color: getTrendColor(tf.trend) }}>
              {tf.trend}
            </div>
            
            <div className={styles.tfSignal} style={{ color: getSignalColor(tf.signal) }}>
              {tf.signal}
            </div>
            
            <div className={styles.tfStrength}>
              <div className={styles.strengthBar}>
                <div 
                  className={styles.strengthFill}
                  style={{ 
                    width: `${tf.strength * 100}%`,
                    background: getTrendColor(tf.trend)
                  }}
                ></div>
              </div>
              <span className={styles.strengthValue}>{(tf.strength * 100).toFixed(0)}%</span>
            </div>
          </div>
        ))}
      </div>
      
      <div className={styles.timeframeNote}>
        {isAligned ? 
          '✓ Entry conditions met - timeframes aligned' : 
          '⚠ Waiting for timeframe alignment before entry'}
      </div>
    </div>
  );
};
