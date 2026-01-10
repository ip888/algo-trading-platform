import { useState, useEffect } from 'react';
import { Tooltip } from './Tooltip';
import './ProfilePositions.css';
import { CONFIG } from '../config';

interface Position {
  symbol: string;
  qty: number;
  market_value: number;
  unrealized_pl: number;
}

export const ProfilePositions = () => {
  const [positions, setPositions] = useState<Position[]>([]);
  const [loading, setLoading] = useState(true);
  
  useEffect(() => {
    const fetchPositions = async () => {
      try {
        const response = await fetch(`${CONFIG.API_BASE_URL}/api/positions`);
        const data = await response.json();
        setPositions(Array.isArray(data) ? data : []);
        setLoading(false);
      } catch (error) {
        console.error('Failed to fetch positions:', error);
        setLoading(false);
      }
    };
    
    fetchPositions();
    const interval = setInterval(fetchPositions, 5000);
    return () => clearInterval(interval);
  }, []);
  
  if (loading) {
    return (
      <div className="panel">
        <h2>👥 Profile Positions</h2>
        <p>Loading...</p>
      </div>
    );
  }
  
  // Split positions by profile based on symbol type (EXCLUSIVE - no overlap)
  // MAIN: Traditional Inverse ETFs
  // EXPERIMENTAL: Volatility & Leveraged ETFs
  const mainSymbols = ['SH', 'PSQ', 'RWM', 'DOG', 'SEF', 'ERY', 'REK', 'RXD', 'SIJ', 'SZK'];
  const expSymbols = [
    'VXX', 'UVXY', 'VIXY', 'SVXY', 'ZSL', 'TBT', 'SDP', 'FAZ', 'TZA', 
    'YANG', 'BIS', 'SCC', 'SSG', 'MYY', 'EUM', 'SRS'
  ];
  
  const mainPositions = positions.filter(p => mainSymbols.includes(p.symbol));
  const expPositions = positions.filter(p => expSymbols.includes(p.symbol));
  
  const calculateStats = (posArray: Position[]) => {
    const totalValue = posArray.reduce((sum, p) => sum + parseFloat(p.market_value.toString()), 0);
    const totalPnL = posArray.reduce((sum, p) => sum + parseFloat(p.unrealized_pl.toString()), 0);
    const symbols = posArray.map(p => p.symbol).join(', ');
    return { totalValue, totalPnL, symbols, count: posArray.length };
  };
  
  const mainStats = calculateStats(mainPositions);
  const expStats = calculateStats(expPositions);
  
  // Check if market is open (client-side)
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
  
  return (
    <div className="panel profile-positions">
      <h2>
        👥 Multi-Profile Positions
        <Tooltip text="Positions split between MAIN (bullish) and EXPERIMENTAL (inverse/volatility) profiles." />
      </h2>
      
      {!marketOpen && (
        <div className="market-closed-banner">
          ⏸️ Market Closed - Positions from last session
        </div>
      )}
      
      <div className="profile-summary">
        {/* MAIN Profile */}
        <div className="profile-card">
          <div className="profile-header">
            <span className="profile-badge main">MAIN</span>
            <span className="profile-allocation">Bullish ETFs</span>
          </div>
          <div className="profile-stats">
            <div className="stat-row">
              <span className="stat-label">Positions:</span>
              <span className="stat-value">{mainStats.count}</span>
            </div>
            <div className="stat-row">
              <span className="stat-label">Value:</span>
              <span className="stat-value">${mainStats.totalValue.toFixed(2)}</span>
            </div>
            <div className="stat-row">
              <span className="stat-label">P&L:</span>
              <span className={`stat-value ${mainStats.totalPnL >= 0 ? 'positive' : 'negative'}`}>
                ${mainStats.totalPnL.toFixed(2)}
              </span>
            </div>
            <div className="stat-row symbols">
              <span className="stat-label">Symbols:</span>
              <span className="stat-value">{mainStats.symbols || 'None'}</span>
            </div>
            {mainStats.count === 0 && (
              <div className="profile-note-inline">
                💡 No positions - High VIX triggers inverse ETF strategy
              </div>
            )}
          </div>
        </div>
        
        {/* EXPERIMENTAL Profile */}
        <div className="profile-card">
          <div className="profile-header">
            <span className="profile-badge experimental">EXPERIMENTAL</span>
            <span className="profile-allocation">Inverse/Vol ETFs</span>
          </div>
          <div className="profile-stats">
            <div className="stat-row">
              <span className="stat-label">Positions:</span>
              <span className="stat-value">{expStats.count}</span>
            </div>
            <div className="stat-row">
              <span className="stat-label">Value:</span>
              <span className="stat-value">${expStats.totalValue.toFixed(2)}</span>
            </div>
            <div className="stat-row">
              <span className="stat-label">P&L:</span>
              <span className={`stat-value ${expStats.totalPnL >= 0 ? 'positive' : 'negative'}`}>
                ${expStats.totalPnL.toFixed(2)}
              </span>
            </div>
            <div className="stat-row symbols">
              <span className="stat-label">Symbols:</span>
              <span className="stat-value">{expStats.symbols || 'None'}</span>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
};
