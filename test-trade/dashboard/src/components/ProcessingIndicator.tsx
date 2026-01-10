import { useTradingStore } from '../store/tradingStore';

export const ProcessingIndicator = () => {
  const { processingStatus } = useTradingStore();
  
  if (!processingStatus || !processingStatus.currentSymbol) {
    return null;
  }
  
  return (
    <div className="processing-indicator">
      <div className="processing-header">
        <span className="processing-icon">⚡</span>
        <span className="processing-text">Currently Processing</span>
      </div>
      <div className="processing-symbol">{processingStatus.currentSymbol}</div>
      <div className="processing-progress">
        <div className="progress-bar">
          <div 
            className="progress-fill" 
            style={{ width: `${processingStatus.progress}%` }}
          />
        </div>
        <div className="progress-text">
          {processingStatus.symbolIndex} of {processingStatus.totalSymbols}
        </div>
      </div>
    </div>
  );
};
