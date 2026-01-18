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

  private handleMessage(message: any) {
    const store = useTradingStore.getState();
    store.setLastUpdate(Date.now());

    switch (message.type) {
      case 'activity_log':
        if (message.data) {
          store.addActivity(message.data.message, message.data.level);
          
          // Create notifications for important events
          const level = message.data.level;
          if (level === 'SUCCESS') {
            store.addNotification(message.data.message, 'success');
          } else if (level === 'ERROR') {
            store.addNotification(message.data.message, 'error');
          } else if (level === 'WARN') {
            store.addNotification(message.data.message, 'warning');
          }
        }
        break;

      case 'processing_status':
        if (message.data) {
          store.setProcessingStatus(message.data);
        }
        break;

      case 'market_update':
        if (message.data) {
           Object.entries(message.data).forEach(([symbol, data]: [string, any]) => {
             // Ensure data object has expected fields before updating
             if (data && typeof data === 'object' && 'price' in data) {
                 store.updateMarketData(symbol, {
                   symbol,
                   price: data.price,
                   change: data.change,
                   changePercent: data.changePercent,
                   volume: data.volume,
                   trend: data.trend,
                   score: data.score
                 });
             }
           });
        }
        break;

      case 'system_status':
        store.setSystemStatus(message.data);
        break;
        
      case 'trade_event':
        console.log('Trade event:', message.data);
        // Could add a toast notification here
        break;
        
      case 'positions_update':
        if (message.data && message.data.positions) {
          store.setPositions(message.data.positions);
        }
        break;

      case 'portfolio_update':
        // We might want to add portfolio data to the store later
        break;
        
      case 'bot_status':
        if (message.data) {
          store.setBotStatus(message.data);
        }
        break;
        
      case 'profit_targets':
        if (message.data && message.data.targets) {
          store.setProfitTargets(message.data.targets);
        }
        break;
        
      case 'account_data':
        if (message.data) {
          store.setAccountData(message.data);
        }
        break;
        
      case 'order_update':
        if (message.data) {
          const orderUpdate = {
            ...message.data,
            timestamp: Date.now()
          };
          store.addOrderUpdate(orderUpdate);
        }
        break;
        
      case 'market_hours':
        if (message.data) {
          store.setMarketHours(message.data);
        }
        break;
        
      case 'health_status':
        if (message.data) {
          store.setHealthStatus(message.data);
        }
        break;
        
      case 'readiness_report':
        if (message.data) {
          store.setReadinessReport(message.data);
        }
        break;
        
      case 'operational_event':
        if (message.data) {
          store.addOperationalEvent(message.data);
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
