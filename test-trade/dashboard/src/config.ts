const isLocal = window.location.hostname === 'localhost' || window.location.hostname === '127.0.0.1';

export const CONFIG = {
  API_BASE_URL: isLocal ? 'http://localhost:8080' : 'https://alpaca-bot-core-281335928142.us-central1.run.app',
  WS_URL: isLocal ? (window.location.protocol === 'https:' ? 'wss://' : 'ws://') + (isLocal ? 'localhost:8080' : 'alpaca-bot-core-281335928142.us-central1.run.app') + '/trading' : 'wss://alpaca-bot-core-281335928142.us-central1.run.app/trading',
  CORTEX_URL: 'https://watchdog-worker.ihorpetroff.workers.dev'
};
