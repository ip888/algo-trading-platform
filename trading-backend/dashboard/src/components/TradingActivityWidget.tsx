import { useState, useEffect } from 'react';
import { CONFIG } from '../config';

interface Trade {
  id: string;
  symbol: string;
  type: 'buy' | 'sell';
  price: number;
  volume: number;
  cost: number;
  fee: number;
  time: number;
  formattedTime: string;
}

interface CapitalDeployment {
  totalEquity: number;
  freeMargin: number;
  deployedCapital: number;
  deploymentPercent: number;
  positions: Array<{
    asset: string;
    amount: number;
    price: number;
    value: number;
  }>;
  positionCount: number;
}

export function TradingActivityWidget() {
  const [trades, setTrades] = useState<Trade[]>([]);
  const [capital, setCapital] = useState<CapitalDeployment | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const fetchData = async () => {
    try {
      const [tradesRes, capitalRes] = await Promise.all([
        fetch(`${CONFIG.API_BASE_URL}/api/kraken/trades`),
        fetch(`${CONFIG.API_BASE_URL}/api/kraken/capital`)
      ]);
      
      if (tradesRes.ok) {
        const data = await tradesRes.json();
        setTrades(data.trades || []);
      }
      
      if (capitalRes.ok) {
        const data = await capitalRes.json();
        setCapital(data);
      }
      
      setError(null);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to fetch');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchData();
    const interval = setInterval(fetchData, 30000); // Refresh every 30s
    return () => clearInterval(interval);
  }, []);

  // Group consecutive buy/sell to calculate P&L
  const calculatePnL = () => {
    const pnlBySymbol: Record<string, { realized: number; trades: number }> = {};
    
    // Simple P&L: pair buy with next sell of same symbol
    const openPositions: Record<string, { price: number; volume: number }[]> = {};
    
    // Process trades in chronological order (reverse since API returns newest first)
    const sortedTrades = [...trades].reverse();
    
    for (const trade of sortedTrades) {
      const symbol = trade.symbol;
      if (!pnlBySymbol[symbol]) {
        pnlBySymbol[symbol] = { realized: 0, trades: 0 };
      }
      if (!openPositions[symbol]) {
        openPositions[symbol] = [];
      }
      
      if (trade.type === 'buy') {
        openPositions[symbol].push({ price: trade.price, volume: trade.volume });
      } else if (trade.type === 'sell') {
        let remainingVolume = trade.volume;
        while (remainingVolume > 0 && openPositions[symbol].length > 0) {
          const pos = openPositions[symbol][0];
          const matched = Math.min(remainingVolume, pos.volume);
          const pnl = (trade.price - pos.price) * matched - trade.fee;
          pnlBySymbol[symbol].realized += pnl;
          pnlBySymbol[symbol].trades++;
          
          pos.volume -= matched;
          remainingVolume -= matched;
          
          if (pos.volume <= 0) {
            openPositions[symbol].shift();
          }
        }
      }
    }
    
    return pnlBySymbol;
  };

  const pnlData = calculatePnL();
  const totalPnL = Object.values(pnlData).reduce((sum, p) => sum + p.realized, 0);

  if (loading) {
    return (
      <div className="panel">
        <h3>📈 Trading Activity Report</h3>
        <div style={{ padding: '20px', textAlign: 'center', color: 'var(--text-dim)' }}>
          Loading...
        </div>
      </div>
    );
  }

  return (
    <div className="panel" style={{ border: 'none', boxShadow: 'none' }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '12px' }}>
        <h3 style={{ margin: 0 }}>📈 Trading Activity Report</h3>
        <span style={{ fontSize: '10px', color: 'var(--text-muted)', fontFamily: 'monospace' }}>
          🦑 KRAKEN 24/7
        </span>
      </div>
      
      {error && (
        <div style={{ color: 'var(--negative)', fontSize: '12px', marginBottom: '8px' }}>
          ⚠️ {error}
        </div>
      )}
      
      {/* Capital Deployment Summary */}
      {capital && (
        <div style={{ 
          background: 'var(--bg-main)', 
          borderRadius: 'var(--radius-sm)', 
          padding: '12px', 
          marginBottom: '12px',
          border: '1px solid var(--border-light)'
        }}>
          <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: '8px' }}>
            <span style={{ color: 'var(--text-muted)', fontSize: '11px' }}>KRAKEN CAPITAL</span>
            <span style={{ color: 'var(--accent)', fontSize: '11px', fontWeight: 600 }}>
              {capital.deploymentPercent.toFixed(1)}% DEPLOYED
            </span>
          </div>
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: '12px', textAlign: 'center' }}>
            <div>
              <div style={{ fontSize: '16px', fontWeight: 600, color: 'var(--text-main)' }}>
                ${capital.totalEquity.toFixed(2)}
              </div>
              <div style={{ fontSize: '10px', color: 'var(--text-muted)' }}>TOTAL</div>
            </div>
            <div>
              <div style={{ fontSize: '16px', fontWeight: 600, color: 'var(--accent)' }}>
                ${capital.deployedCapital.toFixed(2)}
              </div>
              <div style={{ fontSize: '10px', color: 'var(--text-muted)' }}>DEPLOYED</div>
            </div>
            <div>
              <div style={{ fontSize: '16px', fontWeight: 600, color: 'var(--positive)' }}>
                ${capital.freeMargin.toFixed(2)}
              </div>
              <div style={{ fontSize: '10px', color: 'var(--text-muted)' }}>AVAILABLE</div>
            </div>
          </div>
          
          {/* Position Breakdown */}
          {capital.positions.length > 0 && (
            <div style={{ marginTop: '12px', paddingTop: '12px', borderTop: '1px solid var(--border-light)' }}>
              <div style={{ fontSize: '10px', color: 'var(--text-muted)', marginBottom: '6px' }}>POSITIONS</div>
              <div style={{ display: 'flex', flexWrap: 'wrap', gap: '8px' }}>
                {capital.positions.map((pos, i) => (
                  <div key={i} style={{ 
                    background: 'var(--bg-elevated)', 
                    padding: '4px 8px', 
                    borderRadius: '4px',
                    fontSize: '11px'
                  }}>
                    <span style={{ fontWeight: 600 }}>{pos.asset}</span>
                    <span style={{ color: 'var(--text-muted)', marginLeft: '4px' }}>
                      {pos.amount.toFixed(4)} (${pos.value?.toFixed(2) || '?'})
                    </span>
                  </div>
                ))}
              </div>
            </div>
          )}
        </div>
      )}
      
      {/* P&L Summary */}
      <div style={{ 
        background: totalPnL >= 0 ? 'rgba(0, 255, 136, 0.1)' : 'rgba(255, 68, 68, 0.1)',
        borderRadius: 'var(--radius-sm)', 
        padding: '12px', 
        marginBottom: '12px',
        border: `1px solid ${totalPnL >= 0 ? 'var(--positive)' : 'var(--negative)'}`,
        textAlign: 'center'
      }}>
        <div style={{ fontSize: '10px', color: 'var(--text-muted)', marginBottom: '4px' }}>
          REALIZED P&L (RECENT TRADES)
        </div>
        <div style={{ 
          fontSize: '24px', 
          fontWeight: 700, 
          color: totalPnL >= 0 ? 'var(--positive)' : 'var(--negative)'
        }}>
          {totalPnL >= 0 ? '+' : ''}{totalPnL.toFixed(2)} USD
        </div>
        <div style={{ display: 'flex', justifyContent: 'center', gap: '16px', marginTop: '8px', fontSize: '11px' }}>
          {Object.entries(pnlData).map(([symbol, data]) => (
            <span key={symbol} style={{ color: data.realized >= 0 ? 'var(--positive)' : 'var(--negative)' }}>
              {symbol}: {data.realized >= 0 ? '+' : ''}{data.realized.toFixed(2)}
            </span>
          ))}
        </div>
      </div>
      
      {/* Recent Trades Table */}
      <div style={{ fontSize: '10px', color: 'var(--text-muted)', marginBottom: '6px' }}>
        RECENT TRADES ({trades.length})
      </div>
      <div style={{ maxHeight: '200px', overflowY: 'auto' }}>
        <table style={{ width: '100%', fontSize: '11px', borderCollapse: 'collapse' }}>
          <thead>
            <tr style={{ borderBottom: '1px solid var(--border-light)' }}>
              <th style={{ textAlign: 'left', padding: '4px', color: 'var(--text-muted)' }}>TIME</th>
              <th style={{ textAlign: 'left', padding: '4px', color: 'var(--text-muted)' }}>ASSET</th>
              <th style={{ textAlign: 'center', padding: '4px', color: 'var(--text-muted)' }}>TYPE</th>
              <th style={{ textAlign: 'right', padding: '4px', color: 'var(--text-muted)' }}>PRICE</th>
              <th style={{ textAlign: 'right', padding: '4px', color: 'var(--text-muted)' }}>COST</th>
            </tr>
          </thead>
          <tbody>
            {trades.slice(0, 20).map((trade) => (
              <tr key={trade.id} style={{ borderBottom: '1px solid var(--border-light)' }}>
                <td style={{ padding: '4px', fontFamily: 'monospace', color: 'var(--text-dim)' }}>
                  {trade.formattedTime}
                </td>
                <td style={{ padding: '4px', fontWeight: 500 }}>{trade.symbol}</td>
                <td style={{ 
                  textAlign: 'center', 
                  padding: '4px',
                  color: trade.type === 'buy' ? 'var(--positive)' : 'var(--negative)',
                  fontWeight: 600
                }}>
                  {trade.type.toUpperCase()}
                </td>
                <td style={{ textAlign: 'right', padding: '4px', fontFamily: 'monospace' }}>
                  ${trade.price.toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 })}
                </td>
                <td style={{ textAlign: 'right', padding: '4px', fontFamily: 'monospace' }}>
                  ${trade.cost.toFixed(2)}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}
