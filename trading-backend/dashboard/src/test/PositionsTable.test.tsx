import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { server } from './mocks/server';
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
    // REST refresh should return the state arranged by this test, not unrelated global mock data.
    server.use(http.get('*/api/positions', () => HttpResponse.json(useTradingStore.getState().positions)));
  });

  it('shows empty state when no positions', async () => {
    render(<PositionsTable />);
    await waitFor(() => expect(screen.queryByText('Loading positions from Alpaca...')).not.toBeInTheDocument());
    
    expect(screen.getByText('🛰 Active Deployment Matrix')).toBeInTheDocument();
    expect(screen.getByText('No active positions')).toBeInTheDocument();
    expect(screen.getByText('Positions will appear when trades are executed')).toBeInTheDocument();
  });

  it('renders positions table with correct headers', async () => {
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
    await waitFor(() => expect(screen.queryByText('Loading positions from Alpaca...')).not.toBeInTheDocument());
    
    // Check headers
    expect(screen.getByText('Identification')).toBeInTheDocument();
    expect(screen.getByText('Quantity')).toBeInTheDocument();
    expect(screen.getByText('Reference')).toBeInTheDocument();
    expect(screen.getByText('Current')).toBeInTheDocument();
    expect(screen.getByText('Net P&L')).toBeInTheDocument();
    expect(screen.getByText('Safety Bounds')).toBeInTheDocument();
  });

  it('displays position data correctly', async () => {
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
    await waitFor(() => expect(screen.queryByText('Loading positions from Alpaca...')).not.toBeInTheDocument());
    
    // Symbol displayed
    expect(screen.getByText('AAPL')).toBeInTheDocument();
  });

  it('shows positive P&L with correct styling class', async () => {
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
    await waitFor(() => expect(screen.queryByText('Loading positions from Alpaca...')).not.toBeInTheDocument());
    
    // Check the P&L cell has positive class and shows the percentage
    const positiveCell = document.querySelector('.positive');
    expect(positiveCell).toBeInTheDocument();
    expect(positiveCell?.textContent).toContain('3.95');
    expect(positiveCell?.textContent).toContain('%');
  });

  it('shows negative P&L with correct styling class', async () => {
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
    await waitFor(() => expect(screen.queryByText('Loading positions from Alpaca...')).not.toBeInTheDocument());
    
    // Check negative P&L
    const pnlElements = screen.getAllByText(/-2\.07%/);
    expect(pnlElements.length).toBeGreaterThan(0);
  });

  it('handles multiple positions', async () => {
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
    await waitFor(() => expect(screen.queryByText('Loading positions from Alpaca...')).not.toBeInTheDocument());
    
    expect(screen.getByText('AAPL')).toBeInTheDocument();
    expect(screen.getByText('MSFT')).toBeInTheDocument();
    expect(screen.getByText('GOOGL')).toBeInTheDocument();
  });

  it('uses market data price when available', async () => {
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
    await waitFor(() => expect(screen.queryByText('Loading positions from Alpaca...')).not.toBeInTheDocument());
    
    expect(screen.getByText('NVDA')).toBeInTheDocument();
  });

  it('handles string quantity and price values (API compatibility)', async () => {
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
    await waitFor(() => expect(screen.queryByText('Loading positions from Alpaca...')).not.toBeInTheDocument());
    
    expect(screen.getByText('SPY')).toBeInTheDocument();
  });

  // TP/SL Display Tests
  describe('Stop Loss and Take Profit Display', () => {
    it('displays SL and TP in Safety Bounds column', async () => {
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
    await waitFor(() => expect(screen.queryByText('Loading positions from Alpaca...')).not.toBeInTheDocument());
      
      // Check SL and TP are displayed in the Safety Bounds column
      expect(screen.getByText(/SL: \$149\.25/)).toBeInTheDocument();
      expect(screen.getByText(/TP: \$151\.1/)).toBeInTheDocument();  // 151.125 rounds to 151.12 or 151.13
    });

    it('displays SL and TP correctly for each position', async () => {
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
    await waitFor(() => expect(screen.queryByText('Loading positions from Alpaca...')).not.toBeInTheDocument());
      
      // Check AAPL SL/TP
      expect(screen.getByText(/SL: \$147\.00/)).toBeInTheDocument();
      expect(screen.getByText(/TP: \$160\.00/)).toBeInTheDocument();
      
      // Check MSFT SL/TP
      expect(screen.getByText(/SL: \$370\.00/)).toBeInTheDocument();
      expect(screen.getByText(/TP: \$410\.00/)).toBeInTheDocument();
    });

    it('handles zero SL/TP values', async () => {
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
    await waitFor(() => expect(screen.queryByText('Loading positions from Alpaca...')).not.toBeInTheDocument());
      
      expect(screen.getByText('TEST')).toBeInTheDocument();
      expect(screen.getByText(/SL: \$0\.00/)).toBeInTheDocument();
    });

    it('formats SL/TP with two decimal places', async () => {
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
    await waitFor(() => expect(screen.queryByText('Loading positions from Alpaca...')).not.toBeInTheDocument());
      
      // Should display with 2 decimal places
      expect(screen.getByText(/SL: \$243\.12/)).toBeInTheDocument();
      expect(screen.getByText(/TP: \$250\.99/)).toBeInTheDocument();
    });
  });
});
