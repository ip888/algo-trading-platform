import { useState, useEffect } from 'react';
import { CONFIG } from '../config';

interface SymbolStats {
  wins: number;
  losses: number;
  totalTrades: number;
  winRate: number;
  volatilityScore: number;
  recentPrices: number[];
}

interface GridStatus {
  availableBalance: number;
  currentGridSize: number;
  openOrders: number;
  maxConcurrentOrders: number;
  balanceRatio: number;
  minGridSize: number;
  maxGridSize: number;
  tradeableSymbols: Record<string, boolean>;
  performanceStats: Record<string, SymbolStats>;
  volatilityStats: Record<string, { score: number; level: string }>;
  canPlaceOrder: boolean;
  krakenEnabled: boolean;
}

export function GridTradingWidget() {
  const [gridStatus, setGridStatus] = useState<GridStatus | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);

  const fetchGridStatus = async () => {
    try {
      const response = await fetch(`${CONFIG.API_BASE_URL}/api/kraken/grid`);
      if (!response.ok) {
        throw new Error(`HTTP ${response.status}`);
      }
      const data = await response.json();
      setGridStatus(data);
      setError(null);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to fetch');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchGridStatus();
    const interval = setInterval(fetchGridStatus, 10000); // Refresh every 10s
    return () => clearInterval(interval);
  }, []);

  if (loading) {
    return (
      <div className="bg-slate-800 rounded-lg p-4 border border-slate-700">
        <div className="animate-pulse flex space-x-4">
          <div className="flex-1 space-y-4 py-1">
            <div className="h-4 bg-slate-700 rounded w-3/4"></div>
            <div className="space-y-2">
              <div className="h-4 bg-slate-700 rounded"></div>
              <div className="h-4 bg-slate-700 rounded w-5/6"></div>
            </div>
          </div>
        </div>
      </div>
    );
  }

  if (error) {
    return (
      <div className="bg-slate-800 rounded-lg p-4 border border-red-500/50">
        <h3 className="text-lg font-semibold text-red-400 mb-2">
          ⚠️ Grid Trading Status
        </h3>
        <p className="text-red-300 text-sm">Error: {error}</p>
      </div>
    );
  }

  if (!gridStatus) return null;

  // Safely get tradeable symbols as entries
  const symbolEntries = gridStatus.tradeableSymbols 
    ? Object.entries(gridStatus.tradeableSymbols) 
    : [];

  const getVolatilityColor = (level: string) => {
    switch (level) {
      case 'HIGH': return 'text-red-400';
      case 'MEDIUM': return 'text-yellow-400';
      case 'LOW': return 'text-green-400';
      default: return 'text-slate-400';
    }
  };

  const getWinRateColor = (winRate: number) => {
    if (winRate >= 60) return 'text-green-400';
    if (winRate >= 40) return 'text-yellow-400';
    return 'text-red-400';
  };

  return (
    <div className="bg-slate-800 rounded-lg p-4 border border-slate-700">
      <h3 className="text-lg font-semibold text-white mb-4 flex items-center gap-2">
        📊 Grid Trading Status
        <span className="text-xs bg-green-500/20 text-green-400 px-2 py-0.5 rounded">
          Dynamic
        </span>
      </h3>

      {/* Grid Configuration */}
      <div className="grid grid-cols-2 gap-4 mb-4">
        <div className="bg-slate-700/50 rounded p-3">
          <div className="text-sm text-slate-400">Available Balance</div>
          <div className="text-xl font-bold text-green-400">
            ${(gridStatus.availableBalance ?? 0).toFixed(2)}
          </div>
        </div>
        <div className="bg-slate-700/50 rounded p-3">
          <div className="text-sm text-slate-400">Current Grid Size</div>
          <div className="text-xl font-bold text-blue-400">
            ${(gridStatus.currentGridSize ?? 0).toFixed(2)}
          </div>
          <div className="text-xs text-slate-500">
            {((gridStatus.balanceRatio ?? 0.8) * 100).toFixed(0)}% of balance
          </div>
        </div>
      </div>

      {/* Orders Status */}
      <div className="bg-slate-700/50 rounded p-3 mb-4">
        <div className="flex justify-between items-center">
          <span className="text-sm text-slate-400">Open Grid Orders</span>
          <span className="text-white font-medium">
            {gridStatus.openOrders ?? 0} / {gridStatus.maxConcurrentOrders ?? 1}
          </span>
        </div>
        <div className="w-full bg-slate-600 rounded-full h-2 mt-2">
          <div 
            className="bg-blue-500 h-2 rounded-full transition-all duration-300"
            style={{ 
              width: `${((gridStatus.openOrders ?? 0) / (gridStatus.maxConcurrentOrders ?? 1)) * 100}%` 
            }}
          ></div>
        </div>
      </div>

      {/* Tradeable Symbols */}
      <div className="mb-4">
        <div className="text-sm text-slate-400 mb-2">Tradeable Symbols</div>
        <div className="flex flex-wrap gap-2">
          {symbolEntries.map(([symbol, canTrade]) => (
            <span 
              key={symbol}
              className={canTrade 
                ? "bg-green-500/20 text-green-400 px-2 py-1 rounded text-sm"
                : "bg-red-500/20 text-red-400 px-2 py-1 rounded text-sm"
              }
            >
              {canTrade ? '✓' : '✗'} {symbol.replace('/USD', '')}
            </span>
          ))}
        </div>
      </div>

      {/* Performance Stats */}
      {Object.keys(gridStatus.performanceStats).length > 0 && (
        <div className="mb-4">
          <div className="text-sm text-slate-400 mb-2">Performance by Symbol</div>
          <div className="space-y-2">
            {Object.entries(gridStatus.performanceStats).map(([symbol, stats]) => (
              <div 
                key={symbol}
                className="bg-slate-700/50 rounded p-2 flex justify-between items-center"
              >
                <span className="text-white font-medium">
                  {symbol.replace('USD', '')}
                </span>
                <div className="flex items-center gap-4 text-sm">
                  <span className="text-green-400">W: {stats.wins}</span>
                  <span className="text-red-400">L: {stats.losses}</span>
                  <span className={getWinRateColor(stats.winRate)}>
                    {stats.winRate.toFixed(0)}%
                  </span>
                </div>
              </div>
            ))}
          </div>
        </div>
      )}

      {/* Volatility Stats */}
      {Object.keys(gridStatus.volatilityStats).length > 0 && (
        <div className="mb-4">
          <div className="text-sm text-slate-400 mb-2">Volatility Levels</div>
          <div className="flex flex-wrap gap-2">
            {Object.entries(gridStatus.volatilityStats).map(([symbol, vol]) => (
              <span 
                key={symbol}
                className={`px-2 py-1 rounded text-sm ${getVolatilityColor(vol.level)} bg-slate-700/50`}
              >
                {symbol.replace('USD', '')}: {vol.level}
              </span>
            ))}
          </div>
        </div>
      )}

      {/* Grid Size Range */}
      <div className="text-xs text-slate-500 mt-4 border-t border-slate-700 pt-3">
        <div className="flex justify-between">
          <span>Min Grid: ${gridStatus.minGridSize}</span>
          <span>Max Grid: ${gridStatus.maxGridSize}</span>
        </div>
      </div>
    </div>
  );
}

export default GridTradingWidget;
