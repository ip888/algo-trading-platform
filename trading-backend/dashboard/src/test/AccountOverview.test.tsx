import { describe, it, expect, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { AccountOverview } from '../components/AccountOverview';
import { useTradingStore } from '../store/tradingStore';

describe('AccountOverview', () => {
  beforeEach(() => {
    // Reset store to initial state
    useTradingStore.setState({
      accountData: null,
      systemStatus: null,
      connected: false,
    });
  });

  it('shows loading state when no account data', () => {
    render(<AccountOverview />);
    
    expect(screen.getByText('💰 Liquidity Analysis')).toBeInTheDocument();
    expect(screen.getByText('Syncing with Alpaca APIs...')).toBeInTheDocument();
  });

  it('displays account data correctly when available', () => {
    useTradingStore.setState({
      accountData: {
        equity: 75000,
        lastEquity: 74500,
        buyingPower: 50000,
        cash: 25000,
        capitalReserve: 5000,
        deployableCapital: 20000,
        mainTakeProfitPercent: 0.75,
        experimentalTakeProfitPercent: 1.0,
        stopLossPercent: 0.5,
      },
      systemStatus: {
        marketOpen: true,
        volatilityOk: true,
        tradingMode: 'LIVE',
        activePositions: 3,
        totalPnL: 1250,
        marketTrend: 'BULLISH',
        vix: 15.5,
        recommendation: 'CONTINUE',
        marketStrength: 0.75,
        totalTrades: 156,
        winRate: 0.62,
      },
    });

    render(<AccountOverview />);
    
    // Check title
    expect(screen.getByText('💰 Liquidity Analysis')).toBeInTheDocument();
    
    // Check labels
    expect(screen.getByText('TOTAL EQUITY')).toBeInTheDocument();
    expect(screen.getByText('BUYING POWER')).toBeInTheDocument();
    expect(screen.getByText('AVAILABLE CASH')).toBeInTheDocument();
    expect(screen.getByText('LIVE FEED')).toBeInTheDocument();
    
    // Check values are formatted with commas
    expect(screen.getByText('$75,000.00')).toBeInTheDocument();
    expect(screen.getByText('$50,000.00')).toBeInTheDocument();
    expect(screen.getByText('$25,000.00')).toBeInTheDocument();
  });

  it('shows refreshing indicator when systemStatus is null', () => {
    useTradingStore.setState({
      accountData: {
        equity: 10000,
        lastEquity: 10000,
        buyingPower: 10000,
        cash: 5000,
        capitalReserve: 1000,
        deployableCapital: 4000,
        mainTakeProfitPercent: 0.75,
        experimentalTakeProfitPercent: 1.0,
        stopLossPercent: 0.5,
      },
      systemStatus: null,
    });

    render(<AccountOverview />);
    
    expect(screen.getByText('[ REFRESHING ]')).toBeInTheDocument();
  });

  it('handles zero values gracefully', () => {
    useTradingStore.setState({
      accountData: {
        equity: 0,
        lastEquity: 0,
        buyingPower: 0,
        cash: 0,
        capitalReserve: 0,
        deployableCapital: 0,
        mainTakeProfitPercent: 0.75,
        experimentalTakeProfitPercent: 1.0,
        stopLossPercent: 0.5,
      },
      systemStatus: {
        marketOpen: false,
        volatilityOk: true,
        tradingMode: 'PAPER',
        activePositions: 0,
        totalPnL: 0,
        marketTrend: 'NEUTRAL',
        vix: 20,
        recommendation: 'WAIT',
        marketStrength: 0.5,
        totalTrades: 0,
        winRate: 0,
      },
    });

    render(<AccountOverview />);
    
    // Multiple elements show $0.00 (equity, buying power, cash)
    const zeroValueElements = screen.getAllByText('$0.00');
    expect(zeroValueElements.length).toBe(3);
  });
});
