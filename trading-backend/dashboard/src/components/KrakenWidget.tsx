import React, { useEffect, useState } from 'react';
import { RefreshCw, CheckCircle, AlertTriangle, CloudOff, TrendingUp, TrendingDown, Trash2 } from 'lucide-react';

interface KrakenBalance {
    assets: Record<string, string> | null;
    tradeBalance: {
        eb: string; // Equivalent Balance (Total Assets)
        mf: string; // Free Margin
    };
}

interface KrakenHolding {
    symbol: string;
    displayName: string;
    amount: number;
    price: number;
    value: number;
    change24h: number;
    changePercent: number;
    high24h: number;
    low24h: number;
    direction: 'up' | 'down';
}

interface KrakenPosition {
    id: string;
    symbol: string;
    type: string;
    volume: number;
    cost: number;
    net: number;
    stopLoss: number | null;
    takeProfit: number | null;
}

interface GridStatus {
    performanceStats: Record<string, { wins: number; losses: number; winRate: number }>;
    rsiStats?: Record<string, { rsi: number; isOverbought: boolean; isOversold: boolean; hasEnoughData: boolean }>;
    gridConfig?: {
        levels: number[];
        weights: number[];
        trailingTpActivation: number;
        trailingTpDistance: number;
        maxTakeProfit: number;
        rsiOverbought: number;
        rsiOversold: number;
    };
    arbitrageStatus?: {
        btcChange24h?: number;
        ethChange24h?: number;
        divergence?: number;
        hasOpportunity?: boolean;
        divergenceThreshold?: number;
    };
    trailingTpStates?: Record<string, { isActive: boolean; highWaterMark: number; trailingStop: number }>;
    maxConcurrentOrders?: number;
    openOrders?: number;
    currentGridSize?: number;
}

interface LiquidateResult {
    status: string;
    sold: Array<{ symbol: string; amount: number; pnlPercent: number }>;
    preserved: Array<{ symbol: string; amount: number; pnlPercent: number }>;
    summary: string;
}

import { CONFIG } from '../config';

const KrakenWidget: React.FC = () => {
    const [balance, setBalance] = useState<KrakenBalance | null>(null);
    const [holdings, setHoldings] = useState<KrakenHolding[]>([]);
    const [positions, setPositions] = useState<KrakenPosition[]>([]);
    const [gridStatus, setGridStatus] = useState<GridStatus | null>(null);
    const [error, setError] = useState<string | null>(null);
    const [loading, setLoading] = useState<boolean>(true);
    const [liquidating, setLiquidating] = useState<boolean>(false);
    const [liquidateResult, setLiquidateResult] = useState<LiquidateResult | null>(null);
    const [showAdvanced, setShowAdvanced] = useState<boolean>(false);

    const fetchBalance = async () => {
        try {
            const response = await fetch(`${CONFIG.API_BASE_URL}/api/kraken/balance`);
            if (!response.ok) {
                throw new Error('Failed to fetch Kraken balance');
            }
            const data = await response.json();
            setBalance(data);
            setError(null);
        } catch (err) {
            setError(err instanceof Error ? err.message : 'Unknown error');
        } finally {
            setLoading(false);
        }
    };

    const fetchHoldings = async () => {
        try {
            const response = await fetch(`${CONFIG.API_BASE_URL}/api/kraken/holdings`);
            if (response.ok) {
                const data = await response.json();
                setHoldings(Array.isArray(data) ? data : []);
            }
        } catch (err) {
            console.warn('Failed to fetch holdings:', err);
        }
    };

    const fetchPositions = async () => {
        try {
            const response = await fetch(`${CONFIG.API_BASE_URL}/api/kraken/positions`);
            if (response.ok) {
                const data = await response.json();
                setPositions(Array.isArray(data) ? data : []);
            }
        } catch (err) {
            console.warn('Failed to fetch Kraken positions:', err);
        }
    };

    const fetchGridStatus = async () => {
        try {
            const response = await fetch(`${CONFIG.API_BASE_URL}/api/kraken/grid`);
            if (response.ok) {
                const data = await response.json();
                setGridStatus(data);
            }
        } catch (err) {
            console.warn('Failed to fetch grid status:', err);
        }
    };

    const handleLiquidateLosers = async () => {
        if (!window.confirm('🦑 Sell all LOSING crypto positions?\n\nThis will:\n• Sell holdings with negative P&L\n• Preserve profitable positions\n• Does NOT affect Alpaca stocks')) {
            return;
        }
        
        setLiquidating(true);
        setLiquidateResult(null);
        
        try {
            const response = await fetch(`${CONFIG.API_BASE_URL}/api/kraken/liquidate-losers`, {
                method: 'POST'
            });
            
            if (response.ok) {
                const result = await response.json();
                setLiquidateResult(result);
                // Refresh balance after liquidation
                setTimeout(() => {
                    fetchBalance();
                    fetchHoldings();
                }, 2000);
            } else {
                const error = await response.json();
                alert(`Failed: ${error.message || 'Unknown error'}`);
            }
        } catch (err) {
            alert(`Error: ${err instanceof Error ? err.message : 'Unknown error'}`);
        } finally {
            setLiquidating(false);
        }
    };

    useEffect(() => {
        fetchBalance();
        fetchHoldings();
        fetchPositions();
        fetchGridStatus();
        const interval = setInterval(() => {
            fetchBalance();
            fetchHoldings();
            fetchPositions();
            fetchGridStatus();
        }, 10000);
        return () => clearInterval(interval);
    }, []);

    const formatCurrency = (val: string) => {
        const num = parseFloat(val);
        return isNaN(num) ? '$0.00' : `$${num.toFixed(2)}`;
    };

    return (
        <div className="bg-gray-800 rounded-lg p-4 border border-gray-700 shadow-lg">
            <div className="flex items-center justify-between mb-4">
                <div className="flex items-center gap-2">
                    <span className="text-2xl">🦑</span>
                    <h3 className="font-bold text-white">Kraken Status</h3>
                </div>
                <div className="flex items-center gap-2">
                    {/* Liquidate Losers Button */}
                    {!error && balance && holdings.some(h => h.changePercent < 0) && (
                        <button
                            onClick={handleLiquidateLosers}
                            disabled={liquidating}
                            className={`flex items-center gap-1 px-2 py-1 text-xs rounded transition-colors ${
                                liquidating 
                                    ? 'bg-gray-600 text-gray-400 cursor-not-allowed'
                                    : 'bg-red-600/80 hover:bg-red-600 text-white'
                            }`}
                            title="Sell only losing positions (preserves profitable ones)"
                        >
                            {liquidating ? (
                                <RefreshCw className="w-3 h-3 animate-spin" />
                            ) : (
                                <Trash2 className="w-3 h-3" />
                            )}
                            <span>{liquidating ? 'Selling...' : 'Sell Losers'}</span>
                        </button>
                    )}
                    {loading ? (
                        <RefreshCw className="w-4 h-4 text-blue-400 animate-spin" />
                    ) : error ? (
                        <CloudOff className="w-4 h-4 text-red-400" />
                    ) : (
                        <CheckCircle className="w-4 h-4 text-green-400" />
                    )}
                </div>
            </div>

            {/* Liquidation Result Banner */}
            {liquidateResult && (
                <div className={`mb-3 p-2 rounded text-xs ${
                    liquidateResult.sold.length > 0 ? 'bg-red-900/30 border border-red-700' : 'bg-green-900/30 border border-green-700'
                }`}>
                    <div className="font-semibold mb-1">
                        {liquidateResult.sold.length > 0 ? '🔴 Sold Losers' : '✅ No Losers Found'}
                    </div>
                    <div className="text-gray-300">{liquidateResult.summary}</div>
                    {liquidateResult.sold.length > 0 && (
                        <div className="mt-1 text-red-400">
                            Sold: {liquidateResult.sold.map(s => `${s.symbol} (${s.pnlPercent.toFixed(2)}%)`).join(', ')}
                        </div>
                    )}
                    {liquidateResult.preserved.length > 0 && (
                        <div className="mt-1 text-green-400">
                            Kept: {liquidateResult.preserved.map(p => `${p.symbol} (+${p.pnlPercent.toFixed(2)}%)`).join(', ')}
                        </div>
                    )}
                    <button 
                        onClick={() => setLiquidateResult(null)}
                        className="mt-2 text-gray-500 hover:text-gray-300 text-xs"
                    >
                        Dismiss
                    </button>
                </div>
            )}

            {error ? (
                <div className="flex flex-col gap-2 text-amber-300 text-sm bg-amber-900/20 p-3 rounded">
                    <div className="flex items-center gap-2">
                        <AlertTriangle className="w-4 h-4" />
                        <span className="font-semibold">Kraken Not Configured</span>
                    </div>
                    <p className="text-xs text-gray-400 mt-1">
                        To enable crypto trading, set environment variables:
                    </p>
                    <code className="text-xs bg-gray-900 p-2 rounded block mt-1">
                        KRAKEN_API_KEY=your_api_key<br/>
                        KRAKEN_API_SECRET=your_secret
                    </code>
                    <p className="text-xs text-gray-500 mt-2">
                        Kraken trades 24/7 independently of stock market hours.
                    </p>
                </div>
            ) : balance ? (
                <div className="space-y-3">
                    <div className="flex justify-between items-center bg-gray-700/30 p-2 rounded">
                        <span className="text-gray-400 text-sm">Total Assets</span>
                        <span className="text-xl font-mono text-white">
                            {balance.tradeBalance?.eb ? formatCurrency(balance.tradeBalance.eb) : 'N/A'}
                        </span>
                    </div>
                    
                    {/* Live Holdings with Direction */}
                    {holdings.length > 0 ? (
                        <div className="space-y-2">
                            <div className="text-xs text-gray-400 font-semibold">Holdings ({holdings.length})</div>
                            {holdings.map((holding) => {
                                const isUp = holding.direction === 'up';
                                const changePercent = holding.changePercent || 0;
                                const rsiData = gridStatus?.rsiStats?.[holding.symbol];
                                const trailingTp = gridStatus?.trailingTpStates?.[holding.symbol];
                                
                                return (
                                    <div key={holding.symbol} className="bg-gray-700/50 p-2 rounded">
                                        <div className="flex justify-between items-center">
                                            <div className="flex items-center gap-2">
                                                <span className="text-white font-semibold text-sm">{holding.displayName}</span>
                                                {holding.price && (
                                                    <div className={`flex items-center gap-0.5 ${isUp ? 'text-green-400' : 'text-red-400'}`}>
                                                        {isUp ? <TrendingUp className="w-3 h-3" /> : <TrendingDown className="w-3 h-3" />}
                                                        <span className="text-xs font-mono">
                                                            {isUp ? '+' : ''}{changePercent.toFixed(2)}%
                                                        </span>
                                                    </div>
                                                )}
                                                {/* RSI Badge */}
                                                {rsiData && rsiData.hasEnoughData && (
                                                    <span className={`text-xs px-1 rounded ${
                                                        rsiData.isOverbought ? 'bg-red-600/50 text-red-300' :
                                                        rsiData.isOversold ? 'bg-green-600/50 text-green-300' :
                                                        'bg-gray-600/50 text-gray-300'
                                                    }`}>
                                                        RSI {rsiData.rsi.toFixed(0)}
                                                    </span>
                                                )}
                                            </div>
                                            <span className="text-gray-300 font-mono text-sm">
                                                {holding.amount.toFixed(holding.amount < 1 ? 4 : 2)}
                                            </span>
                                        </div>
                                        {holding.price && (
                                            <div className="flex justify-between text-xs text-gray-400 mt-1">
                                                <span>@${holding.price.toFixed(holding.price < 1 ? 4 : 2)}</span>
                                                <span className="text-white">≈ ${holding.value.toFixed(2)}</span>
                                            </div>
                                        )}
                                        {/* Trailing TP Status */}
                                        {trailingTp?.isActive && (
                                            <div className="flex justify-between text-xs text-yellow-400 mt-1">
                                                <span>🎯 Trailing TP Active</span>
                                                <span>Stop: ${trailingTp.trailingStop.toFixed(2)}</span>
                                            </div>
                                        )}
                                        {holding.high24h && holding.low24h && (
                                            <div className="flex justify-between text-xs text-gray-500 mt-0.5">
                                                <span>L: ${holding.low24h.toFixed(holding.low24h < 1 ? 4 : 2)}</span>
                                                <span>H: ${holding.high24h.toFixed(holding.high24h < 1 ? 4 : 2)}</span>
                                            </div>
                                        )}
                                    </div>
                                );
                            })}
                        </div>
                    ) : (
                        <div className="text-xs text-gray-500 text-center py-2 bg-gray-700/20 rounded">
                            No crypto holdings
                        </div>
                    )}
                    
                    {/* Advanced Strategies Panel */}
                    <div className="border-t border-gray-700 pt-2">
                        <button 
                            onClick={() => setShowAdvanced(!showAdvanced)}
                            className="w-full text-xs text-gray-400 hover:text-gray-200 flex items-center justify-between py-1"
                        >
                            <span className="font-semibold">⚡ Advanced Strategies v2.0</span>
                            <span>{showAdvanced ? '▼' : '▶'}</span>
                        </button>
                        
                        {showAdvanced && gridStatus && (
                            <div className="mt-2 space-y-2 text-xs">
                                {/* Grid Config */}
                                {gridStatus.gridConfig && (
                                    <div className="bg-gray-700/30 p-2 rounded">
                                        <div className="font-semibold text-gray-300 mb-1">Multi-Level Grid</div>
                                        <div className="grid grid-cols-3 gap-1 text-gray-400">
                                            <span>L1: -0.3%</span>
                                            <span>L2: -0.5%</span>
                                            <span>L3: -1.0%</span>
                                        </div>
                                        <div className="text-gray-500 mt-1">
                                            Orders: {gridStatus.openOrders || 0}/{gridStatus.maxConcurrentOrders || 3} | 
                                            Grid: ${gridStatus.currentGridSize?.toFixed(0) || 0}
                                        </div>
                                    </div>
                                )}
                                
                                {/* RSI Momentum */}
                                {gridStatus.rsiStats && Object.keys(gridStatus.rsiStats).length > 0 && (
                                    <div className="bg-gray-700/30 p-2 rounded">
                                        <div className="font-semibold text-gray-300 mb-1">📊 RSI Momentum</div>
                                        <div className="flex flex-wrap gap-2">
                                            {Object.entries(gridStatus.rsiStats).map(([symbol, rsi]) => (
                                                <span 
                                                    key={symbol}
                                                    className={`px-1 py-0.5 rounded ${
                                                        rsi.isOverbought ? 'bg-red-600/30 text-red-300' :
                                                        rsi.isOversold ? 'bg-green-600/30 text-green-300' :
                                                        'bg-gray-600/30 text-gray-400'
                                                    }`}
                                                >
                                                    {symbol.split('/')[0]}: {rsi.rsi.toFixed(0)}
                                                    {rsi.isOverbought && ' 🔴'}
                                                    {rsi.isOversold && ' 🟢'}
                                                </span>
                                            ))}
                                        </div>
                                    </div>
                                )}
                                
                                {/* Trailing TP */}
                                {gridStatus.gridConfig && (
                                    <div className="bg-gray-700/30 p-2 rounded">
                                        <div className="font-semibold text-gray-300 mb-1">🎯 Trailing Take-Profit</div>
                                        <div className="text-gray-400">
                                            Activation: +{gridStatus.gridConfig.trailingTpActivation}% | 
                                            Trail: {gridStatus.gridConfig.trailingTpDistance}% | 
                                            Max: +{gridStatus.gridConfig.maxTakeProfit}%
                                        </div>
                                    </div>
                                )}
                                
                                {/* Cross-Asset Arbitrage */}
                                {gridStatus.arbitrageStatus && gridStatus.arbitrageStatus.btcChange24h !== undefined && (
                                    <div className={`bg-gray-700/30 p-2 rounded ${
                                        gridStatus.arbitrageStatus.hasOpportunity ? 'border border-yellow-600/50' : ''
                                    }`}>
                                        <div className="font-semibold text-gray-300 mb-1">
                                            ⚖️ BTC/ETH Arbitrage
                                            {gridStatus.arbitrageStatus.hasOpportunity && (
                                                <span className="ml-2 text-yellow-400">OPPORTUNITY!</span>
                                            )}
                                        </div>
                                        <div className="flex gap-4 text-gray-400">
                                            <span className={gridStatus.arbitrageStatus.btcChange24h >= 0 ? 'text-green-400' : 'text-red-400'}>
                                                BTC: {gridStatus.arbitrageStatus.btcChange24h >= 0 ? '+' : ''}{gridStatus.arbitrageStatus.btcChange24h?.toFixed(2)}%
                                            </span>
                                            <span className={(gridStatus.arbitrageStatus.ethChange24h ?? 0) >= 0 ? 'text-green-400' : 'text-red-400'}>
                                                ETH: {(gridStatus.arbitrageStatus.ethChange24h ?? 0) >= 0 ? '+' : ''}{gridStatus.arbitrageStatus.ethChange24h?.toFixed(2)}%
                                            </span>
                                            <span className="text-gray-500">
                                                Δ {gridStatus.arbitrageStatus.divergence?.toFixed(2)}%
                                            </span>
                                        </div>
                                    </div>
                                )}
                            </div>
                        )}
                    </div>
                    
                    {/* Margin Positions (if any) */}
                    {positions.length > 0 && (
                        <div className="space-y-2 border-t border-gray-700 pt-2">
                            <div className="text-xs text-gray-400 font-semibold">Margin Positions</div>
                            {positions.map((pos) => {
                                const pnl = pos.net || 0;
                                const pnlPercent = pos.cost > 0 ? (pnl / pos.cost) * 100 : 0;
                                const isPositive = pnl >= 0;
                                const symbol = pos.symbol.replace('ZUSD', '').replace('USD', '');
                                
                                return (
                                    <div key={pos.id} className="bg-gray-700/50 p-2 rounded">
                                        <div className="flex justify-between items-center">
                                            <span className="text-white font-semibold text-sm">{symbol}</span>
                                            <div className={`flex items-center gap-1 ${isPositive ? 'text-green-400' : 'text-red-400'}`}>
                                                {isPositive ? <TrendingUp className="w-3 h-3" /> : <TrendingDown className="w-3 h-3" />}
                                                <span className="font-mono text-sm">
                                                    {isPositive ? '+' : ''}{pnlPercent.toFixed(2)}%
                                                </span>
                                            </div>
                                        </div>
                                        <div className="flex justify-between text-xs text-gray-400 mt-1">
                                            <span>Cost: ${pos.cost.toFixed(2)}</span>
                                            <span className={isPositive ? 'text-green-400' : 'text-red-400'}>
                                                P&L: {isPositive ? '+' : ''}${pnl.toFixed(2)}
                                            </span>
                                        </div>
                                        {(pos.stopLoss || pos.takeProfit) && (
                                            <div className="flex gap-3 text-xs mt-1">
                                                {pos.stopLoss && (
                                                    <span className="text-red-400">SL: ${pos.stopLoss.toFixed(2)}</span>
                                                )}
                                                {pos.takeProfit && (
                                                    <span className="text-green-400">TP: ${pos.takeProfit.toFixed(2)}</span>
                                                )}
                                            </div>
                                        )}
                                    </div>
                                );
                            })}
                        </div>
                    )}
                </div>
            ) : (
                <div className="text-gray-500 text-sm text-center py-4">
                    Initializing...
                </div>
            )}
        </div>
    );
};

export default KrakenWidget;
