import { useTradingStore } from '../store/tradingStore';
import './ProcessingPipeline.css';

interface ProcessingPipelineProps {
  compact?: boolean;
}

export const ProcessingPipeline = ({ compact = false }: ProcessingPipelineProps) => {
  const { processingStatus } = useTradingStore();
  
  // Check if market is open
  const isMarketOpen = () => {
    const est = new Date().toLocaleString('en-US', { timeZone: 'America/New_York' });
    const estDate = new Date(est);
    const hours = estDate.getHours();
    const day = estDate.getDay();
    if (day === 0 || day === 6) return false;
    if (hours < 9 || hours >= 16) return false;
    if (hours === 9 && estDate.getMinutes() < 30) return false;
    return true;
  };
  
  const marketOpen = isMarketOpen();
  
  if (!marketOpen) {
    return (
      <div className={`panel ${compact ? 'processing-compact' : ''}`}>
        <div className="processing-paused">
          ⏸️ Market Closed - Analysis Paused (Resumes at 9:30 AM ET)
        </div>
      </div>
    );
  }
  
  if (!processingStatus || !processingStatus.currentSymbol) {
    return (
      <div className={`panel ${compact ? 'processing-compact' : ''}`}>
        <div className="processing-idle">
          💤 Idle - Waiting for data
        </div>
      </div>
    );
  }
  
  if (compact) {
    return (
      <div className="panel processing-compact">
        <div className="processing-compact-content">
          <span className="processing-label">Processing:</span>
          <span className="processing-symbol">{processingStatus.currentSymbol || 'Idle'}</span>
          <span className="processing-progress">
            ({processingStatus.symbolIndex || 0}/{processingStatus.totalSymbols || 0})
          </span>
          <div className="processing-bar">
            <div className="processing-bar-fill" style={{ width: `${processingStatus.progress || 0}%` }}></div>
          </div>
        </div>
      </div>
    );
  }
  
  const stages = ['DATA', 'ANALYSIS', 'STRATEGY', 'EXECUTION'];
  const currentStage = processingStatus.stage || 'DATA';
  const currentStageIndex = stages.indexOf(currentStage) === -1 ? 0 : stages.indexOf(currentStage);
  
  return (
    <div className="panel processing-panel">
      <h2>
        ⚡ Processing: <span className="highlight">{processingStatus.currentSymbol}</span>
      </h2>
      
      <div className="pipeline-container">
        {stages.map((stage, index) => {
          let statusClass = 'pending';
          if (index < currentStageIndex) statusClass = 'completed';
          if (index === currentStageIndex) statusClass = 'active';
          if (currentStage === 'SKIPPED' && index === stages.length - 1) statusClass = 'skipped';
          
          return (
            <div key={stage} className={`pipeline-step ${statusClass}`}>
              <div className="step-indicator">
                {statusClass === 'completed' ? '✓' : index + 1}
              </div>
              <div className="step-label">{stage}</div>
            </div>
          );
        })}
      </div>
      
      <div className="pipeline-details">
        <div className="detail-label">Current Action:</div>
        <div className="detail-value">{processingStatus.details || 'Initializing...'}</div>
      </div>
      
      <div className="pipeline-progress">
        <div className="progress-bar">
          <div 
            className="progress-fill" 
            style={{ width: `${processingStatus.progress}%` }}
          />
        </div>
        <div className="progress-text">
          {processingStatus.symbolIndex} of {processingStatus.totalSymbols} symbols
        </div>
      </div>
    </div>
  );
};
