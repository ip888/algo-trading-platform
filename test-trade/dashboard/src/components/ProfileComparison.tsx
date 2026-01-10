import { useState } from 'react';
import './ProfileComparison.css';

export function ProfileComparison() {
  const [selectedProfile, setSelectedProfile] = useState<'MAIN' | 'EXPERIMENTAL' | 'BOTH'>('BOTH');
  
  // Mock data - will be populated from backend
  const profiles = {
    MAIN: {
      name: 'Main Profile',
      strategy: 'MACD Swing Trading',
      capital: 300,
      allocation: '60%',
      positions: 3,
      pnl: 12.45,
      winRate: 65.2,
      trades: 23,
      symbols: 10
    },
    EXPERIMENTAL: {
      name: 'Experimental Profile',
      strategy: 'RSI Day Trading',
      capital: 200,
      allocation: '40%',
      positions: 2,
      pnl: -3.21,
      winRate: 58.7,
      trades: 15,
      symbols: 12
    }
  };
  
  return (
    <div className="profile-comparison">
      <div className="profile-header">
        <h3>👥 Multi-Profile Dashboard</h3>
        <div className="profile-tabs">
          <button 
            className={selectedProfile === 'BOTH' ? 'active' : ''}
            onClick={() => setSelectedProfile('BOTH')}
          >
            Both Profiles
          </button>
          <button 
            className={selectedProfile === 'MAIN' ? 'active main' : ''}
            onClick={() => setSelectedProfile('MAIN')}
          >
            Main
          </button>
          <button 
            className={selectedProfile === 'EXPERIMENTAL' ? 'active experimental' : ''}
            onClick={() => setSelectedProfile('EXPERIMENTAL')}
          >
            Experimental
          </button>
        </div>
      </div>
      
      <div className="profiles-grid">
        {(selectedProfile === 'BOTH' || selectedProfile === 'MAIN') && (
          <div className="profile-card main">
            <div className="profile-card-header">
              <h4>{profiles.MAIN.name}</h4>
              <span className="profile-badge main">MAIN</span>
            </div>
            
            <div className="profile-strategy">{profiles.MAIN.strategy}</div>
            
            <div className="profile-stats">
              <div className="stat-row">
                <span className="stat-label">Capital Allocation</span>
                <span className="stat-value">${profiles.MAIN.capital} ({profiles.MAIN.allocation})</span>
              </div>
              <div className="stat-row">
                <span className="stat-label">Active Positions</span>
                <span className="stat-value">{profiles.MAIN.positions} / {profiles.MAIN.symbols}</span>
              </div>
              <div className="stat-row">
                <span className="stat-label">Total P&L</span>
                <span className={`stat-value ${profiles.MAIN.pnl >= 0 ? 'positive' : 'negative'}`}>
                  ${profiles.MAIN.pnl >= 0 ? '+' : ''}{profiles.MAIN.pnl.toFixed(2)}
                </span>
              </div>
              <div className="stat-row">
                <span className="stat-label">Win Rate</span>
                <span className="stat-value">{profiles.MAIN.winRate}%</span>
              </div>
              <div className="stat-row">
                <span className="stat-label">Total Trades</span>
                <span className="stat-value">{profiles.MAIN.trades}</span>
              </div>
            </div>
          </div>
        )}
        
        {(selectedProfile === 'BOTH' || selectedProfile === 'EXPERIMENTAL') && (
          <div className="profile-card experimental">
            <div className="profile-card-header">
              <h4>{profiles.EXPERIMENTAL.name}</h4>
              <span className="profile-badge experimental">EXPERIMENTAL</span>
            </div>
            
            <div className="profile-strategy">{profiles.EXPERIMENTAL.strategy}</div>
            
            <div className="profile-stats">
              <div className="stat-row">
                <span className="stat-label">Capital Allocation</span>
                <span className="stat-value">${profiles.EXPERIMENTAL.capital} ({profiles.EXPERIMENTAL.allocation})</span>
              </div>
              <div className="stat-row">
                <span className="stat-label">Active Positions</span>
                <span className="stat-value">{profiles.EXPERIMENTAL.positions} / {profiles.EXPERIMENTAL.symbols}</span>
              </div>
              <div className="stat-row">
                <span className="stat-label">Total P&L</span>
                <span className={`stat-value ${profiles.EXPERIMENTAL.pnl >= 0 ? 'positive' : 'negative'}`}>
                  ${profiles.EXPERIMENTAL.pnl >= 0 ? '+' : ''}{profiles.EXPERIMENTAL.pnl.toFixed(2)}
                </span>
              </div>
              <div className="stat-row">
                <span className="stat-label">Win Rate</span>
                <span className="stat-value">{profiles.EXPERIMENTAL.winRate}%</span>
              </div>
              <div className="stat-row">
                <span className="stat-label">Total Trades</span>
                <span className="stat-value">{profiles.EXPERIMENTAL.trades}</span>
              </div>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}
