import { describe, it, expect, beforeEach } from 'vitest';
import { useTradingStore } from '../store/tradingStore';

describe('tradingStore', () => {
  beforeEach(() => {
    // Reset store to initial state before each test
    useTradingStore.setState({
      connected: false,
      systemStatus: null,
      marketData: {},
      positions: [],
      lastUpdate: null,
      activityLog: [],
      processingStatus: null,
      notifications: [],
      adaptiveParams: null,
      correlationData: null,
      timeframeSignals: [],
      rebalanceData: null,
      botStatus: null,
      profitTargets: [],
      accountData: null,
      orderHistory: [],
      marketHours: null,
      healthStatus: null,
      readinessReport: null,
      operationalEvents: [],
    });
  });

  describe('connection state', () => {
    it('should set connected state', () => {
      const { setConnected } = useTradingStore.getState();
      
      setConnected(true);
      expect(useTradingStore.getState().connected).toBe(true);
      
      setConnected(false);
      expect(useTradingStore.getState().connected).toBe(false);
    });
  });

  describe('system status', () => {
    it('should set system status', () => {
      const { setSystemStatus } = useTradingStore.getState();
      const status = {
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
      };
      
      setSystemStatus(status);
      expect(useTradingStore.getState().systemStatus).toEqual(status);
    });
  });

  describe('market data', () => {
    it('should update market data for a symbol', () => {
      const { updateMarketData } = useTradingStore.getState();
      const data = {
        symbol: 'AAPL',
        price: 155.00,
        change: 5.00,
        changePercent: 3.33,
        volume: 45000000,
        trend: 'BULLISH',
        score: 85,
      };
      
      updateMarketData('AAPL', data);
      expect(useTradingStore.getState().marketData['AAPL']).toEqual(data);
    });

    it('should handle multiple symbols', () => {
      const { updateMarketData } = useTradingStore.getState();
      
      updateMarketData('AAPL', { symbol: 'AAPL', price: 155, change: 5, changePercent: 3, volume: 1000, trend: 'UP', score: 80 });
      updateMarketData('MSFT', { symbol: 'MSFT', price: 395, change: 10, changePercent: 2.5, volume: 2000, trend: 'UP', score: 75 });
      
      const state = useTradingStore.getState();
      expect(Object.keys(state.marketData)).toHaveLength(2);
      expect(state.marketData['AAPL'].price).toBe(155);
      expect(state.marketData['MSFT'].price).toBe(395);
    });
  });

  describe('positions', () => {
    it('should set positions array', () => {
      const { setPositions } = useTradingStore.getState();
      const positions = [
        {
          symbol: 'AAPL',
          quantity: 100,
          entryPrice: 150,
          currentPrice: 155,
          stopLoss: 147,
          takeProfit: 160,
          entryTime: '2025-01-15T10:00:00Z',
        },
      ];
      
      setPositions(positions);
      expect(useTradingStore.getState().positions).toEqual(positions);
    });

    it('should replace positions on update', () => {
      const { setPositions } = useTradingStore.getState();
      
      setPositions([{ symbol: 'AAPL', quantity: 100, entryPrice: 150, currentPrice: 155, stopLoss: 147, takeProfit: 160, entryTime: '2025-01-15T10:00:00Z' }]);
      expect(useTradingStore.getState().positions).toHaveLength(1);
      
      setPositions([{ symbol: 'MSFT', quantity: 50, entryPrice: 380, currentPrice: 395, stopLoss: 370, takeProfit: 410, entryTime: '2025-01-15T09:00:00Z' }]);
      expect(useTradingStore.getState().positions).toHaveLength(1);
      expect(useTradingStore.getState().positions[0].symbol).toBe('MSFT');
    });
  });

  describe('activity log', () => {
    it('should add activity entries', () => {
      const { addActivity } = useTradingStore.getState();
      
      addActivity('Test message', 'INFO');
      
      const state = useTradingStore.getState();
      expect(state.activityLog).toHaveLength(1);
      expect(state.activityLog[0].message).toBe('Test message');
      expect(state.activityLog[0].level).toBe('INFO');
    });

    it('should prepend new activities (newest first)', () => {
      const { addActivity } = useTradingStore.getState();
      
      addActivity('First message', 'INFO');
      addActivity('Second message', 'WARN');
      
      const state = useTradingStore.getState();
      expect(state.activityLog[0].message).toBe('Second message');
      expect(state.activityLog[1].message).toBe('First message');
    });

    it('should limit activity log to 50 entries', () => {
      const { addActivity } = useTradingStore.getState();
      
      // Add 60 entries
      for (let i = 0; i < 60; i++) {
        addActivity(`Message ${i}`, 'INFO');
      }
      
      expect(useTradingStore.getState().activityLog).toHaveLength(50);
    });
  });

  describe('notifications', () => {
    it('should add notifications with unique IDs', () => {
      const { addNotification } = useTradingStore.getState();
      
      addNotification('Success notification', 'success');
      addNotification('Error notification', 'error');
      
      const state = useTradingStore.getState();
      expect(state.notifications).toHaveLength(2);
      expect(state.notifications[0].id).not.toBe(state.notifications[1].id);
    });

    it('should clear all notifications', () => {
      const { addNotification, clearNotifications } = useTradingStore.getState();
      
      addNotification('Test', 'info');
      addNotification('Test 2', 'warning');
      
      clearNotifications();
      expect(useTradingStore.getState().notifications).toHaveLength(0);
    });

    it('should limit notifications to 20 entries', () => {
      const { addNotification } = useTradingStore.getState();
      
      for (let i = 0; i < 25; i++) {
        addNotification(`Notification ${i}`, 'info');
      }
      
      expect(useTradingStore.getState().notifications).toHaveLength(20);
    });
  });

  describe('account data', () => {
    it('should set account data and update lastUpdate', () => {
      const { setAccountData } = useTradingStore.getState();
      const accountData = {
        equity: 75000,
        lastEquity: 74500,
        buyingPower: 50000,
        cash: 25000,
        capitalReserve: 5000,
        deployableCapital: 20000,
        mainTakeProfitPercent: 0.75,
        experimentalTakeProfitPercent: 1.0,
        stopLossPercent: 0.5,
      };
      
      setAccountData(accountData);
      
      const state = useTradingStore.getState();
      expect(state.accountData).toEqual(accountData);
      expect(state.lastUpdate).toBeDefined();
      expect(typeof state.lastUpdate).toBe('number');
    });
  });

  describe('order history', () => {
    it('should add order updates', () => {
      const { addOrderUpdate } = useTradingStore.getState();
      const order = {
        profile: 'main',
        symbol: 'AAPL',
        quantity: 100,
        side: 'buy' as const,
        type: 'market',
        status: 'filled',
        price: 155.00,
        timestamp: Date.now(),
      };
      
      addOrderUpdate(order);
      
      const state = useTradingStore.getState();
      expect(state.orderHistory).toHaveLength(1);
      expect(state.orderHistory[0].symbol).toBe('AAPL');
    });

    it('should limit order history to 20 entries', () => {
      const { addOrderUpdate } = useTradingStore.getState();
      
      for (let i = 0; i < 25; i++) {
        addOrderUpdate({
          profile: 'main',
          symbol: `SYM${i}`,
          quantity: 10,
          side: 'buy',
          type: 'market',
          status: 'filled',
          price: 100,
          timestamp: Date.now(),
        });
      }
      
      expect(useTradingStore.getState().orderHistory).toHaveLength(20);
    });
  });

  describe('health status', () => {
    it('should set health status', () => {
      const { setHealthStatus } = useTradingStore.getState();
      const health = {
        overall: 'healthy',
        recommendation: 'System operating normally',
        uptimeSeconds: 86400,
        timestamp: new Date().toISOString(),
        components: [
          { component: 'alpaca', status: 'connected', message: 'OK' },
        ],
      };
      
      setHealthStatus(health);
      expect(useTradingStore.getState().healthStatus).toEqual(health);
    });
  });

  describe('operational events', () => {
    it('should add operational events', () => {
      const { addOperationalEvent } = useTradingStore.getState();
      
      addOperationalEvent({
        eventType: 'CIRCUIT_BREAKER_OPEN',
        severity: 'WARNING',
        message: 'Alpaca circuit breaker opened',
        action: 'Backing off for 30s',
        timestamp: new Date().toISOString(),
      });
      
      const state = useTradingStore.getState();
      expect(state.operationalEvents).toHaveLength(1);
      expect(state.operationalEvents[0].eventType).toBe('CIRCUIT_BREAKER_OPEN');
    });

    it('should limit operational events to 50 entries', () => {
      const { addOperationalEvent } = useTradingStore.getState();
      
      for (let i = 0; i < 60; i++) {
        addOperationalEvent({
          eventType: `EVENT_${i}`,
          severity: 'INFO',
          message: `Event ${i}`,
          action: 'None',
          timestamp: new Date().toISOString(),
        });
      }
      
      expect(useTradingStore.getState().operationalEvents).toHaveLength(50);
    });
  });
});
