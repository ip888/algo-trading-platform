import { useTradingStore } from '../store/tradingStore';
import { CONFIG } from '../config';

const WS_URL = import.meta.env.VITE_WS_URL || CONFIG.WS_URL;
const RECONNECT_INTERVAL = 3000;

class WebSocketService {
  private ws: WebSocket | null = null;
  private reconnectTimer: number | null = null;

  connect() {
    if (this.ws) return;

    console.log('Connecting to WebSocket...');
    this.ws = new WebSocket(WS_URL);

    this.ws.onopen = () => {
      console.log('WebSocket connected');
      useTradingStore.getState().setConnected(true);
      if (this.reconnectTimer) {
        window.clearTimeout(this.reconnectTimer);
        this.reconnectTimer = null;
      }
    };

    this.ws.onclose = () => {
      console.log('WebSocket disconnected');
      useTradingStore.getState().setConnected(false);
      this.ws = null;
      this.scheduleReconnect();
    };

    this.ws.onerror = (error) => {
      console.error('WebSocket error:', error);
      this.ws?.close();
    };

    this.ws.onmessage = (event) => {
      try {
        const message = JSON.parse(event.data);
        this.handleMessage(message);
      } catch (e) {
        console.error('Failed to parse message:', e);
      }
    };
  }

  private scheduleReconnect() {
    if (this.reconnectTimer) return;
    console.log(`Reconnecting in ${RECONNECT_INTERVAL}ms...`);
    this.reconnectTimer = window.setTimeout(() => {
      this.reconnectTimer = null;
      this.connect();
    }, RECONNECT_INTERVAL);
  }

  private handleMessage(message: { type: string; data?: unknown }) {
    const store = useTradingStore.getState();
    if (message.data == null || typeof message.data !== 'object') return;
    const payload = message.data as Record<string, unknown>;
    store.setLastUpdate(Date.now());

    switch (message.type) {
      case 'activity_log':
        if (payload) {
          store.addActivity(String(payload.message ?? ''), String(payload.level ?? 'INFO'));
          
          // Create notifications for important events
          const level = String(payload.level ?? 'INFO');
          if (level === 'SUCCESS') {
            store.addNotification(String(payload.message ?? ''), 'success');
          } else if (level === 'ERROR') {
            store.addNotification(String(payload.message ?? ''), 'error');
          } else if (level === 'WARN') {
            store.addNotification(String(payload.message ?? ''), 'warning');
          }
        }
        break;

      case 'processing_status':
        if (payload) {
          store.setProcessingStatus(payload as unknown as Parameters<typeof store.setProcessingStatus>[0]);
        }
        break;

      case 'market_update':
        if (payload) {
           Object.entries(payload).forEach(([symbol, value]) => {
             if (!value || typeof value !== 'object') return;
             const data = value as Record<string, unknown>;
             if (!Number.isFinite(Number(data.price))) return;
             store.updateMarketData(symbol, {
               symbol,
               price: Number(data.price),
               change: Number(data.change) || 0,
               changePercent: Number(data.changePercent) || 0,
               volume: Number(data.volume) || 0,
               trend: String(data.trend || 'UNKNOWN'),
               score: Number(data.score) || 0,
             });
           });
        }
        break;

      case 'system_status':
        store.setSystemStatus(payload as unknown as Parameters<typeof store.setSystemStatus>[0]);
        break;
        
      case 'trade_event':
        console.log('Trade event:', message.data);
        // Could add a toast notification here
        break;
        
      case 'positions_update':
        if (Array.isArray(payload.positions)) {
          store.setPositions(payload.positions as Parameters<typeof store.setPositions>[0]);
        }
        break;

      case 'portfolio_update':
        // We might want to add portfolio data to the store later
        break;
        
      case 'bot_status':
        if (payload) {
          store.setBotStatus(payload as unknown as Parameters<typeof store.setBotStatus>[0]);
        }
        break;
        
      case 'profit_targets':
        if (Array.isArray(payload.targets)) {
          store.setProfitTargets(payload.targets as Parameters<typeof store.setProfitTargets>[0]);
        }
        break;
        
      case 'account_data':
        if (payload) {
          store.setAccountData(payload as unknown as Parameters<typeof store.setAccountData>[0]);
        }
        break;
        
      case 'order_update':
        if (payload) {
          const orderUpdate = {
            ...payload,
            timestamp: Date.now()
          };
          store.addOrderUpdate(orderUpdate as Parameters<typeof store.addOrderUpdate>[0]);
        }
        break;
        
      case 'market_hours':
        if (payload) {
          store.setMarketHours(payload as unknown as Parameters<typeof store.setMarketHours>[0]);
        }
        break;
        
      case 'health_status':
        if (payload) {
          store.setHealthStatus(payload as unknown as Parameters<typeof store.setHealthStatus>[0]);
        }
        break;
        
      case 'readiness_report':
        if (payload) {
          store.setReadinessReport(payload as unknown as Parameters<typeof store.setReadinessReport>[0]);
        }
        break;
        
      case 'operational_event':
        if (payload) {
          store.addOperationalEvent(payload as unknown as Parameters<typeof store.addOperationalEvent>[0]);
        }
        break;
    }
  }

  disconnect() {
    if (this.ws) {
      // Prevent reconnect on intentional disconnect
      this.ws.onclose = null;
      this.ws.onerror = null;
      this.ws.onmessage = null;
      this.ws.onopen = null;
      
      this.ws.close();
      this.ws = null;
    }
    if (this.reconnectTimer) {
      window.clearTimeout(this.reconnectTimer);
      this.reconnectTimer = null;
    }
  }
}

export const webSocketService = new WebSocketService();
