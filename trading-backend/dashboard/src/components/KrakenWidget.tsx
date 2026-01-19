import React, { useEffect, useState } from 'react';
import { RefreshCw, CheckCircle, AlertTriangle, CloudOff, TrendingUp, TrendingDown } from 'lucide-react';

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
}

import { CONFIG } from '../config';

const KrakenWidget: React.FC = () => {
    const [balance, setBalance] = useState<KrakenBalance | null>(null);
    const [holdings, setHoldings] = useState<KrakenHolding[]>([]);
    const [positions, setPositions] = useState<KrakenPosition[]>([]);
    const [_gridStatus, setGridStatus] = useState<GridStatus | null>(null);
    const [error, setError] = useState<string | null>(null);
    const [loading, setLoading] = useState<boolean>(true);

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
                {loading ? (
                    <RefreshCw className="w-4 h-4 text-blue-400 animate-spin" />
                ) : error ? (
                    <CloudOff className="w-4 h-4 text-red-400" />
                ) : (
                    <CheckCircle className="w-4 h-4 text-green-400" />
                )}
            </div>

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
