const isLocal = window.location.hostname === 'localhost' || window.location.hostname === '127.0.0.1';

export const CONFIG = {
  API_BASE_URL: isLocal ? 'http://localhost:8080' : '',  // Empty = same origin
  WS_URL: isLocal 
    ? 'ws://localhost:8080/trading'
    : `wss://${window.location.host}/trading`,
  CORTEX_URL: 'https://watchdog-worker.ihorpetroff.workers.dev'
};
