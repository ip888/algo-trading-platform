import { http, HttpResponse } from 'msw';

// Mock data matching the trading bot's actual response types
export const mockAccountData = {
  buying_power: '50000.00',
  cash: '25000.00',
  portfolio_value: '75000.00',
  equity: '75000.00',
  last_equity: '74500.00',
  long_market_value: '50000.00',
  short_market_value: '0.00',
  initial_margin: '25000.00',
  maintenance_margin: '15000.00',
  daytrade_count: 2,
  pattern_day_trader: false,
  trading_blocked: false,
  transfers_blocked: false,
  account_blocked: false,
};

export const mockPositions = [
  {
    asset_id: 'aapl-123',
    symbol: 'AAPL',
    qty: '100',
    avg_entry_price: '150.00',
    current_price: '155.00',
    market_value: '15500.00',
    unrealized_pl: '500.00',
    unrealized_plpc: '0.0333',
    change_today: '0.02',
    side: 'long',
  },
  {
    asset_id: 'msft-456',
    symbol: 'MSFT',
    qty: '50',
    avg_entry_price: '380.00',
    current_price: '395.00',
    market_value: '19750.00',
    unrealized_pl: '750.00',
    unrealized_plpc: '0.0395',
    change_today: '0.015',
    side: 'long',
  },
  {
    asset_id: 'googl-789',
    symbol: 'GOOGL',
    qty: '25',
    avg_entry_price: '145.00',
    current_price: '142.00',
    market_value: '3550.00',
    unrealized_pl: '-75.00',
    unrealized_plpc: '-0.0207',
    change_today: '-0.01',
    side: 'long',
  },
];

export const mockOrders = [
  {
    id: 'order-001',
    client_order_id: 'client-001',
    symbol: 'NVDA',
    side: 'buy',
    type: 'limit',
    qty: '10',
    filled_qty: '0',
    limit_price: '450.00',
    status: 'new',
    created_at: '2025-01-15T10:30:00Z',
    submitted_at: '2025-01-15T10:30:00Z',
  },
  {
    id: 'order-002',
    client_order_id: 'client-002',
    symbol: 'AMD',
    side: 'sell',
    type: 'market',
    qty: '20',
    filled_qty: '20',
    status: 'filled',
    filled_avg_price: '125.50',
    created_at: '2025-01-15T09:15:00Z',
    submitted_at: '2025-01-15T09:15:00Z',
    filled_at: '2025-01-15T09:15:01Z',
  },
];

export const mockMarketStatus = {
  is_open: true,
  next_open: '2025-01-16T09:30:00-05:00',
  next_close: '2025-01-15T16:00:00-05:00',
};

export const mockQuotes = {
  AAPL: { bid: 154.90, ask: 155.10, last: 155.00, volume: 45000000 },
  MSFT: { bid: 394.80, ask: 395.20, last: 395.00, volume: 22000000 },
  GOOGL: { bid: 141.90, ask: 142.10, last: 142.00, volume: 18000000 },
};

export const mockTradingMetrics = {
  totalTrades: 156,
  winRate: 0.62,
  profitFactor: 1.85,
  sharpeRatio: 1.42,
  maxDrawdown: 0.08,
  dailyPnL: 1250.00,
  weeklyPnL: 4500.00,
  monthlyPnL: 12800.00,
};

export const mockSystemHealth = {
  status: 'healthy',
  uptime: 86400,
  lastHeartbeat: Date.now(),
  services: {
    alpacaApi: 'connected',
    database: 'healthy',
    websocket: 'active',
  },
  circuitBreakers: {
    alpaca: 'closed',
  },
};

// API handlers
export const handlers = [
  // Account endpoint
  http.get('*/api/account', () => {
    return HttpResponse.json(mockAccountData);
  }),

  // Positions endpoint
  http.get('*/api/positions', () => {
    return HttpResponse.json(mockPositions);
  }),

  // Orders endpoint
  http.get('*/api/orders', () => {
    return HttpResponse.json(mockOrders);
  }),

  // Market status endpoint
  http.get('*/api/market/status', () => {
    return HttpResponse.json(mockMarketStatus);
  }),

  // Quotes endpoint
  http.get('*/api/quotes', () => {
    return HttpResponse.json(mockQuotes);
  }),

  // Metrics endpoint
  http.get('*/api/metrics', () => {
    return HttpResponse.json(mockTradingMetrics);
  }),

  // Health endpoint
  http.get('*/api/health', () => {
    return HttpResponse.json(mockSystemHealth);
  }),

  // Cancel order
  http.delete('*/api/orders/:orderId', ({ params }) => {
    return HttpResponse.json({ id: params.orderId, status: 'cancelled' });
  }),

  // Create order
  http.post('*/api/orders', async ({ request }) => {
    const body = await request.json() as Record<string, unknown>;
    return HttpResponse.json({
      id: `order-${Date.now()}`,
      ...body,
      status: 'new',
      created_at: new Date().toISOString(),
    });
  }),
];
