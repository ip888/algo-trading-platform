import { useEffect, useRef, useCallback } from 'react';
import { useTradingStore } from '../store/tradingStore';

export const useWebSocket = (url: string) => {
  const wsRef = useRef<WebSocket | null>(null);
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
              Object.entries(message.data).forEach(([symbol, data]: [string, any]) => {
                updateMarketData(symbol, data);
              });
              break;

            case 'market_analysis':
              // Full market analysis: merge per-symbol scores/recommendations into existing marketData
              if (message.data?.scores) {
                Object.entries(message.data.scores).forEach(([symbol, s]: [string, any]) => {
                  mergeMarketData(symbol, { score: s.score, recommendation: s.recommendation });
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
          connect();
        }, 5000);
      };
      
      wsRef.current = ws;
    } catch (error) {
      console.error('Failed to create WebSocket:', error);
      setConnected(false);
    }
  }, [url, setConnected, setSystemStatus, updateMarketData, mergeMarketData, setLastUpdate]);
  
  useEffect(() => {
    connect();
    
    return () => {
      if (wsRef.current) {
        wsRef.current.close();
      }
    };
  }, [connect]);
  
  return wsRef.current;
};
