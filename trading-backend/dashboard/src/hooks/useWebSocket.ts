import { useEffect, useRef, useCallback } from 'react';
import { useTradingStore } from '../store/tradingStore';

export const useWebSocket = (url: string) => {
  const wsRef = useRef<WebSocket | null>(null);
  const connectRef = useRef<() => void>(() => {});
  const shouldReconnectRef = useRef(true);
  const { setConnected, setSystemStatus, updateMarketData, mergeMarketData, setLastUpdate } = useTradingStore();

  const connect = useCallback(() => {
    try {
      const ws = new WebSocket(url);
      
      ws.onopen = () => {
        console.log('WebSocket connected');
        setConnected(true);
      };
      
      ws.onmessage = (event) => {
        try {
          const message = JSON.parse(event.data);
          setLastUpdate(message.timestamp);
          
          switch (message.type) {
            case 'market_update':
            case 'MARKET_UPDATE':
              // Per-symbol price + signal update (price, change%, score, recommendation)
              Object.entries(message.data as Record<string, unknown>).forEach(([symbol, data]) => {
                if (!data || typeof data !== 'object') return;
                const item = data as Record<string, unknown>;
                updateMarketData(symbol, {
                  symbol,
                  price: Number(item.price) || 0,
                  change: Number(item.change) || 0,
                  changePercent: Number(item.changePercent) || 0,
                  volume: Number(item.volume) || 0,
                  trend: String(item.trend || 'UNKNOWN'),
                  score: Number(item.score) || 0,
                });
              });
              break;

            case 'market_analysis':
              // Full market analysis: merge per-symbol scores/recommendations into existing marketData
              if (message.data?.scores) {
                Object.entries(message.data.scores as Record<string, unknown>).forEach(([symbol, value]) => {
                  if (!value || typeof value !== 'object') return;
                  const item = value as Record<string, unknown>;
                  mergeMarketData(symbol, {
                    score: Number(item.score) || 0,
                    recommendation: String(item.recommendation || ''),
                  });
                });
              }
              break;

            case 'system_status':
              setSystemStatus(message.data);
              break;

            case 'connected':
              console.log('Server message:', message.message);
              break;

            default:
              // Suppress noise for known non-critical types
              if (!['processing_status', 'order_update', 'account_update',
                    'trade_event', 'positions_update', 'activity'].includes(message.type)) {
                console.log('Unknown message type:', message.type);
              }
          }
        } catch (error) {
          console.error('Error parsing WebSocket message:', error);
        }
      };
      
      ws.onerror = (error) => {
        console.error('WebSocket error:', error);
        setConnected(false);
      };
      
      ws.onclose = () => {
        console.log('WebSocket disconnected');
        setConnected(false);
        
        // Reconnect after 5 seconds
        setTimeout(() => {
          console.log('Attempting to reconnect...');
          if (shouldReconnectRef.current) connectRef.current();
        }, 5000);
      };
      
      wsRef.current = ws;
    } catch (error) {
      console.error('Failed to create WebSocket:', error);
      setConnected(false);
    }
  }, [url, setConnected, setSystemStatus, updateMarketData, mergeMarketData, setLastUpdate]);
  
  useEffect(() => {
    shouldReconnectRef.current = true;
    connectRef.current = connect;
    connect();

    return () => {
      shouldReconnectRef.current = false;
      if (wsRef.current) {
        wsRef.current.onclose = null;
        wsRef.current.close();
      }
    };
  }, [connect]);
  
  // Return the ref rather than reading mutable .current during render.
  return wsRef;
};
