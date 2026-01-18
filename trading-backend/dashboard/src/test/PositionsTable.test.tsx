import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { PositionsTable } from '../components/PositionsTable';
import { useTradingStore } from '../store/tradingStore';

describe('PositionsTable', () => {
  beforeEach(() => {
    // Reset store and mock window width for desktop view
    useTradingStore.setState({
      positions: [],
      marketData: {},
    });
    
    // Mock window.innerWidth for desktop
    vi.stubGlobal('innerWidth', 1024);
  });

  it('shows empty state when no positions', () => {
    render(<PositionsTable />);
    
    expect(screen.getByText('🛰 Active Deployment Matrix')).toBeInTheDocument();
    expect(screen.getByText('Scanning market for entry opportunities...')).toBeInTheDocument();
    expect(screen.getByText('[ WATCHLIST: QQQ, NVDA, SPY, TSLA ]')).toBeInTheDocument();
  });

  it('renders positions table with correct headers', () => {
    useTradingStore.setState({
      positions: [
        {
          symbol: 'AAPL',
          quantity: 100,
          entryPrice: 150.00,
          currentPrice: 155.00,
          stopLoss: 147.00,
          takeProfit: 160.00,
          entryTime: '2025-01-15T10:00:00Z',
          unrealized_pl: 500,
          unrealized_plpc: 0.0333,
        },
      ],
      marketData: {},
    });

    render(<PositionsTable />);
    
    // Check headers
    expect(screen.getByText('Identification')).toBeInTheDocument();
    expect(screen.getByText('Quantity')).toBeInTheDocument();
    expect(screen.getByText('Reference')).toBeInTheDocument();
    expect(screen.getByText('Current')).toBeInTheDocument();
    expect(screen.getByText('Net P&L')).toBeInTheDocument();
    expect(screen.getByText('Safety Bounds')).toBeInTheDocument();
  });

  it('displays position data correctly', () => {
    useTradingStore.setState({
      positions: [
        {
          symbol: 'AAPL',
          quantity: 100,
          entryPrice: 150.00,
          currentPrice: 155.00,
          stopLoss: 147.00,
          takeProfit: 160.00,
          entryTime: '2025-01-15T10:00:00Z',
          unrealized_pl: 500,
          unrealized_plpc: 0.0333,
        },
      ],
      marketData: {},
    });

    render(<PositionsTable />);
    
    // Symbol displayed
    expect(screen.getByText('AAPL')).toBeInTheDocument();
  });

  it('shows positive P&L with correct styling class', () => {
    useTradingStore.setState({
      positions: [
        {
          symbol: 'MSFT',
          quantity: 50,
          entryPrice: 380.00,
          currentPrice: 395.00,
          stopLoss: 370.00,
          takeProfit: 410.00,
          entryTime: '2025-01-15T09:00:00Z',
          unrealized_pl: 750,
          unrealized_plpc: 0.0395,
        },
      ],
      marketData: {},
    });

    render(<PositionsTable />);
    
    // Check the P&L cell has positive class and shows the percentage
    const positiveCell = document.querySelector('.positive');
    expect(positiveCell).toBeInTheDocument();
    expect(positiveCell?.textContent).toContain('3.95');
    expect(positiveCell?.textContent).toContain('%');
  });

  it('shows negative P&L with correct styling class', () => {
    useTradingStore.setState({
      positions: [
        {
          symbol: 'GOOGL',
          quantity: 25,
          entryPrice: 145.00,
          currentPrice: 142.00,
          stopLoss: 140.00,
          takeProfit: 155.00,
          entryTime: '2025-01-15T08:00:00Z',
          unrealized_pl: -75,
          unrealized_plpc: -0.0207,
        },
      ],
      marketData: {},
    });

    render(<PositionsTable />);
    
    // Check negative P&L
    const pnlElements = screen.getAllByText(/-2\.07%/);
    expect(pnlElements.length).toBeGreaterThan(0);
  });

  it('handles multiple positions', () => {
    useTradingStore.setState({
      positions: [
        {
          symbol: 'AAPL',
          quantity: 100,
          entryPrice: 150.00,
          currentPrice: 155.00,
          stopLoss: 147.00,
          takeProfit: 160.00,
          entryTime: '2025-01-15T10:00:00Z',
        },
        {
          symbol: 'MSFT',
          quantity: 50,
          entryPrice: 380.00,
          currentPrice: 395.00,
          stopLoss: 370.00,
          takeProfit: 410.00,
          entryTime: '2025-01-15T09:00:00Z',
        },
        {
          symbol: 'GOOGL',
          quantity: 25,
          entryPrice: 145.00,
          currentPrice: 142.00,
          stopLoss: 140.00,
          takeProfit: 155.00,
          entryTime: '2025-01-15T08:00:00Z',
        },
      ],
      marketData: {},
    });

    render(<PositionsTable />);
    
    expect(screen.getByText('AAPL')).toBeInTheDocument();
    expect(screen.getByText('MSFT')).toBeInTheDocument();
    expect(screen.getByText('GOOGL')).toBeInTheDocument();
  });

  it('uses market data price when available', () => {
    useTradingStore.setState({
      positions: [
        {
          symbol: 'NVDA',
          quantity: 10,
          entryPrice: 450.00,
          currentPrice: 455.00,
          stopLoss: 440.00,
          takeProfit: 480.00,
          entryTime: '2025-01-15T11:00:00Z',
        },
      ],
      marketData: {
        'NVDA': {
          symbol: 'NVDA',
          price: 460.00, // Live price different from stored currentPrice
          change: 10,
          changePercent: 2.2,
          volume: 50000000,
          trend: 'BULLISH',
          score: 85,
        },
      },
    });

    render(<PositionsTable />);
    
    expect(screen.getByText('NVDA')).toBeInTheDocument();
  });

  it('handles string quantity and price values (API compatibility)', () => {
    useTradingStore.setState({
      positions: [
        {
          symbol: 'SPY',
          quantity: '50' as unknown as number, // API might return strings
          entryPrice: '450.00' as unknown as number,
          currentPrice: '455.00' as unknown as number,
          stopLoss: 445,
          takeProfit: 465,
          entryTime: '2025-01-15T10:00:00Z',
        },
      ],
      marketData: {},
    });

    render(<PositionsTable />);
    
    expect(screen.getByText('SPY')).toBeInTheDocument();
  });

  // TP/SL Display Tests
  describe('Stop Loss and Take Profit Display', () => {
    it('displays SL and TP in Safety Bounds column', () => {
      useTradingStore.setState({
        positions: [
          {
            symbol: 'AAPL',
            quantity: 100,
            entryPrice: 150.00,
            currentPrice: 152.00,
            stopLoss: 149.25,  // 0.5% below entry
            takeProfit: 151.125,  // 0.75% above entry
            entryTime: '2025-01-15T10:00:00Z',
          },
        ],
        marketData: {},
      });

      render(<PositionsTable />);
      
      // Check SL and TP are displayed in the Safety Bounds column
      expect(screen.getByText(/SL: \$149\.25/)).toBeInTheDocument();
      expect(screen.getByText(/TP: \$151\.1/)).toBeInTheDocument();  // 151.125 rounds to 151.12 or 151.13
    });

    it('displays SL and TP correctly for each position', () => {
      useTradingStore.setState({
        positions: [
          {
            symbol: 'AAPL',
            quantity: 100,
            entryPrice: 150.00,
            currentPrice: 155.00,
            stopLoss: 147.00,
            takeProfit: 160.00,
            entryTime: '2025-01-15T10:00:00Z',
          },
          {
            symbol: 'MSFT',
            quantity: 50,
            entryPrice: 380.00,
            currentPrice: 395.00,
            stopLoss: 370.00,
            takeProfit: 410.00,
            entryTime: '2025-01-15T09:00:00Z',
          },
        ],
        marketData: {},
      });

      render(<PositionsTable />);
      
      // Check AAPL SL/TP
      expect(screen.getByText(/SL: \$147\.00/)).toBeInTheDocument();
      expect(screen.getByText(/TP: \$160\.00/)).toBeInTheDocument();
      
      // Check MSFT SL/TP
      expect(screen.getByText(/SL: \$370\.00/)).toBeInTheDocument();
      expect(screen.getByText(/TP: \$410\.00/)).toBeInTheDocument();
    });

    it('handles zero SL/TP values', () => {
      useTradingStore.setState({
        positions: [
          {
            symbol: 'TEST',
            quantity: 10,
            entryPrice: 100.00,
            currentPrice: 100.00,
            stopLoss: 0,
            takeProfit: 101.00,  // Still needs valid TP
            entryTime: '2025-01-15T10:00:00Z',
          },
        ],
        marketData: {},
      });

      render(<PositionsTable />);
      
      expect(screen.getByText('TEST')).toBeInTheDocument();
      expect(screen.getByText(/SL: \$0\.00/)).toBeInTheDocument();
    });

    it('formats SL/TP with two decimal places', () => {
      useTradingStore.setState({
        positions: [
          {
            symbol: 'TSLA',
            quantity: 5,
            entryPrice: 245.5678,  // Many decimals
            currentPrice: 250.00,
            stopLoss: 243.123456,  // Many decimals
            takeProfit: 250.987654,  // Many decimals
            entryTime: '2025-01-15T10:00:00Z',
          },
        ],
        marketData: {},
      });

      render(<PositionsTable />);
      
      // Should display with 2 decimal places
      expect(screen.getByText(/SL: \$243\.12/)).toBeInTheDocument();
      expect(screen.getByText(/TP: \$250\.99/)).toBeInTheDocument();
    });
  });
});
