import { useTradingStore } from '../store/tradingStore';
import { Tooltip } from './Tooltip';
import styles from './CorrelationHeatmap.module.css';

export const CorrelationHeatmap = () => {
  const { positions, correlationData } = useTradingStore();
  
  if (!correlationData) return null;
  
  const symbols = positions?.map(p => p.symbol) || [];
  const { correlations, diversificationScore } = correlationData;
  
  const getCorrelationColor = (corr: number) => {
    const abs = Math.abs(corr);
    if (abs > 0.7) return 'var(--accent-red)';
    if (abs > 0.5) return 'var(--accent-orange)';
    if (abs > 0.3) return 'var(--accent-yellow)';
    return 'var(--accent-green)';
  };
  
  return (
    <div className="panel correlation-panel">
      <h2>
        🔗 Correlation Matrix
        <Tooltip text="Portfolio correlation and diversification analysis" />
      </h2>
      
      <div className={styles.diversificationScore}>
        <div className={styles.scoreLabel}>Diversification Score</div>
        <div className={styles.scoreValue} style={{ 
          color: diversificationScore > 0.7 ? 'var(--accent-green)' : 
                 diversificationScore > 0.5 ? 'var(--accent-yellow)' : 
                 'var(--accent-red)'
        }}>
          {(diversificationScore * 100).toFixed(0)}%
        </div>
        <div className={styles.scoreDesc}>
          {diversificationScore > 0.7 ? 'Well Diversified' : 
           diversificationScore > 0.5 ? 'Moderate' : 
           'Highly Correlated'}
        </div>
      </div>
      
      <div className={styles.correlationGrid} style={{ gridTemplateColumns: `auto repeat(${symbols.length}, 1fr)` }}>
        <div className={styles.gridHeader}></div>
        {symbols.map(sym => (
          <div key={sym} className={styles.gridHeader}>{sym}</div>
        ))}
        
        {symbols.map((sym1, i) => (
          <div key={sym1} className="grid-row">
            <div className={styles.gridHeader}>{sym1}</div>
            {symbols.map((sym2, j) => {
              if (i === j) {
                return <div key={sym2} className={`${styles.gridCell} ${styles.self}`}>1.00</div>;
              }
              const key = i < j ? `${sym1}-${sym2}` : `${sym2}-${sym1}`;
              const corr = correlations[key] || 0.5;
              return (
                <div 
                  key={sym2} 
                  className={styles.gridCell}
                  style={{ 
                    backgroundColor: getCorrelationColor(corr) + '20',
                    color: getCorrelationColor(corr)
                  }}
                >
                  {corr.toFixed(2)}
                </div>
              );
            })}
          </div>
        ))}
      </div>
      
      <div className={styles.correlationLegend}>
        <div className={styles.legendItem}>
          <div className={styles.legendColor} style={{ background: 'var(--accent-green)' }}></div>
          <span>Low (&lt;0.3)</span>
        </div>
        <div className={styles.legendItem}>
          <div className={styles.legendColor} style={{ background: 'var(--accent-yellow)' }}></div>
          <span>Medium (0.3-0.5)</span>
        </div>
        <div className={styles.legendItem}>
          <div className={styles.legendColor} style={{ background: 'var(--accent-orange)' }}></div>
          <span>High (0.5-0.7)</span>
        </div>
        <div className={styles.legendItem}>
          <div className={styles.legendColor} style={{ background: 'var(--accent-red)' }}></div>
          <span>Very High (&gt;0.7)</span>
        </div>
      </div>
    </div>
  );
};
