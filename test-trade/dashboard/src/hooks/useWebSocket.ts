import { useEffect, useRef, useCallback } from 'react';
import { useTradingStore } from '../store/tradingStore';

export const useWebSocket = (url: string) => {
  const wsRef = useRef<WebSocket | null>(null);
  const { setConnected, setSystemStatus, updateMarketData, setLastUpdate } = useTradingStore();
  
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
              Object.entries(message.data).forEach(([symbol, data]: [string, any]) => {
                updateMarketData(symbol, data);
              });
              break;
            
            case 'system_status':
              setSystemStatus(message.data);
              break;
            
            case 'connected':
              console.log('Server message:', message.message);
              break;
            
            default:
              console.log('Unknown message type:', message.type);
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
  }, [url, setConnected, setSystemStatus, updateMarketData, setLastUpdate]);
  
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
