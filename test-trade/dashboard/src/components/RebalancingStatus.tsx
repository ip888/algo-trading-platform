import { useTradingStore } from '../store/tradingStore';
import { Tooltip } from './Tooltip';
import styles from './RebalancingStatus.module.css';

export const RebalancingStatus = () => {
  const { rebalanceData } = useTradingStore();
  
  if (!rebalanceData) return null;
  
  const { needsRebalance, maxDrift, drifts, lastRebalance, nextCheck } = rebalanceData;
  
  return (
    <div className="panel rebalance-panel">
      <h2>
        ⚖️ Rebalancing Status
        <Tooltip text="Portfolio allocation drift and rebalancing needs" />
      </h2>
      
      <div className={styles.rebalanceStatus}>
        <div className={`${styles.statusIndicator} ${needsRebalance ? styles.needsRebalance : styles.balanced}`}>
          {needsRebalance ? '⚠ Rebalancing Recommended' : '✓ Well Balanced'}
        </div>
        <div className={styles.maxDrift}>
          Max Drift: <span className={maxDrift > 15 ? styles.high : styles.normal}>{maxDrift.toFixed(1)}%</span>
        </div>
      </div>
      
      <div className={styles.driftList}>
        {drifts.map(d => (
          <div key={d.symbol} className={styles.driftRow}>
            <div className={styles.driftSymbol}>{d.symbol}</div>
            
            <div className={styles.driftBars}>
              <div className={styles.allocationBar}>
                <div className={styles.barLabel}>Current</div>
                <div className={styles.barContainer}>
                  <div 
                    className={`${styles.barFill} ${styles.current}`}
                    style={{ width: `${d.current}%` }}
                  ></div>
                  <span className={styles.barValue}>{d.current.toFixed(0)}%</span>
                </div>
              </div>
              
              <div className={styles.allocationBar}>
                <div className={styles.barLabel}>Target</div>
                <div className={styles.barContainer}>
                  <div 
                    className={`${styles.barFill} ${styles.target}`}
                    style={{ width: `${d.target}%` }}
                  ></div>
                  <span className={styles.barValue}>{d.target.toFixed(0)}%</span>
                </div>
              </div>
            </div>
            
            <div className={`${styles.driftValue} ${Math.abs(d.drift) > 5 ? styles.high : styles.normal}`}>
              {d.drift > 0 ? '+' : ''}{d.drift.toFixed(1)}%
            </div>
          </div>
        ))}
      </div>
      
      <div className={styles.rebalanceInfo}>
        <div className={styles.infoRow}>
          <span>Last Rebalance:</span>
          <span>{lastRebalance}</span>
        </div>
        <div className={styles.infoRow}>
          <span>Next Check:</span>
          <span>{nextCheck}</span>
        </div>
        <div className={styles.infoRow}>
          <span>Trigger Threshold:</span>
          <span>15% drift</span>
        </div>
      </div>
    </div>
  );
};
