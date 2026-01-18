import { create } from 'zustand';

interface MarketData {
  symbol: string;
  price: number;
  change: number;
  changePercent: number;
  volume: number;
  trend: string;
  score: number;
  // Rich Analysis Fields
  strategy?: string;
  sentiment?: number;
  regime?: string;
  recommendation?: string;
  reason?: string;
}

interface SystemStatus {
  marketOpen: boolean;
  volatilityOk: boolean;
  tradingMode: string;
  activePositions: number;
  totalPnL: number;
  marketTrend: string;
  vix: number;
  recommendation: string;
  marketStrength: number;
  totalTrades: number;
  winRate: number;
  buyingPower?: number;
  sentiment?: number;
}

interface ActivityLogEntry {
  id: string;
  message: string;
  level: 'INFO' | 'WARN' | 'ERROR' | 'SUCCESS';
  timestamp: number;
}

interface ProcessingStatus {
  currentSymbol: string;
  symbolIndex: number;
  totalSymbols: number;
  progress: number;
  stage?: string;
  details?: string;
}

interface TradePosition {
  symbol: string;
  quantity: number;
  entryPrice: number;
  currentPrice: number;
  stopLoss: number;
  takeProfit: number;
  entryTime: string;
  unrealized_pl?: number;
  unrealized_plpc?: number;
}

interface Notification {
  id: number;
  type: 'success' | 'error' | 'warning' | 'info';
  message: string;
  time: string;
  timestamp: number;
}

interface AdaptiveParams {
  kellyFraction: number;
  maxPosition: number;
  minAligned: number;
  requireAlignment: boolean;
  winRate: number;
  totalTrades: number;
  lastAdjusted: string;
}

interface CorrelationData {
  correlations: Record<string, number>;
  diversificationScore: number;
  highCorrelations: Array<{symbol1: string; symbol2: string; correlation: number}>;
}

interface TimeframeSignal {
  name: string;
  trend: string;
  signal: string;
  strength: number;
}

interface RebalanceData {
  needsRebalance: boolean;
  maxDrift: number;
  drifts: Array<{symbol: string; current: number; target: number; drift: number}>;
  lastRebalance: string;
  nextCheck: string;
}

interface BotStatus {
  marketStatus: string;
  regime: string;
  vix: number;
  nextAction: string;
  waitingFor: string;
}

interface MarketHours {
  isOpen: boolean;
  phase: string;
  nextOpen: string;
  nextClose: string;
  minutesToOpen: number;
  minutesToClose: number;
}

interface HealthComponent {
  component: string;
  status: string;
  message: string;
}

interface HealthStatus {
  overall: string;
  recommendation: string;
  uptimeSeconds: number;
  timestamp: string;
  components: HealthComponent[];
}

interface ReadinessReport {
  ready: boolean;
  alpacaConnected: boolean;
  configLoaded: boolean;
  watchlistReady: boolean;
  message: string;
  timestamp: string;
}

interface OperationalEvent {
  eventType: string;
  severity: string;
  message: string;
  action: string;
  timestamp: string;
}

interface ProfitTarget {
  symbol: string;
  currentPnlPercent: number;
  targetPercent: number;
  distancePercent: number;
  eta: string;
}

interface AccountData {
  equity: number;
  lastEquity: number;
  buyingPower: number;
  cash: number;
  capitalReserve: number;
  deployableCapital: number;
  mainTakeProfitPercent: number;
  experimentalTakeProfitPercent: number;
  stopLossPercent: number;
}

interface OrderUpdate {
  profile: string;
  symbol: string;
  quantity: number;
  side: 'buy' | 'sell';
  type: string;
  status: string;
  price: number;
  timestamp: number;
}

interface TradingStore {
  marketData: Record<string, MarketData>;
  systemStatus: SystemStatus | null;
  activityLog: ActivityLogEntry[];
  processingStatus: ProcessingStatus | null;
  positions: TradePosition[];
  notifications: Notification[];
  connected: boolean;
  lastUpdate: number | null;
  botStatus: BotStatus | null;
  profitTargets: ProfitTarget[];
  accountData: AccountData | null;
  orderHistory: OrderUpdate[];
  adaptiveParams: AdaptiveParams | null;
  correlationData: CorrelationData | null;
  timeframeSignals: TimeframeSignal[];
  rebalanceData: RebalanceData | null;
  marketHours: MarketHours | null;
  healthStatus: HealthStatus | null;
  readinessReport: ReadinessReport | null;
  operationalEvents: OperationalEvent[];
  
  setConnected: (connected: boolean) => void;
  setSystemStatus: (status: SystemStatus) => void;
  updateMarketData: (symbol: string, data: MarketData) => void;
  setPositions: (positions: TradePosition[]) => void;
  setLastUpdate: (timestamp: number) => void;
  addActivity: (message: string, level: string) => void;
  setProcessingStatus: (status: ProcessingStatus) => void;
  addNotification: (message: string, type: 'success' | 'error' | 'warning' | 'info') => void;
  clearNotifications: () => void;
  setAdaptiveParams: (params: AdaptiveParams) => void;
  setCorrelationData: (data: CorrelationData) => void;
  setTimeframeSignals: (signals: TimeframeSignal[]) => void;
  setRebalanceData: (data: RebalanceData) => void;
  setBotStatus: (status: BotStatus) => void;
  setProfitTargets: (targets: ProfitTarget[]) => void;
  setAccountData: (data: AccountData) => void;
  addOrderUpdate: (order: OrderUpdate) => void;
  setMarketHours: (data: MarketHours) => void;
  setHealthStatus: (data: HealthStatus) => void;
  setReadinessReport: (data: ReadinessReport) => void;
  addOperationalEvent: (event: OperationalEvent) => void;
}

let notificationId = 0;

const getRelativeTime = (timestamp: number): string => {
  const seconds = Math.floor((Date.now() - timestamp) / 1000);
  if (seconds < 60) return 'just now ';
  const minutes = Math.floor(seconds / 60);
  if (minutes < 60) return `${minutes}m ago`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours}h ago`;
  return `${Math.floor(hours / 24)}d ago`;
};

export const useTradingStore = create<TradingStore>((set) => ({
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
  
  setConnected: (connected) => set({ connected }),
  setSystemStatus: (status) => set({ systemStatus: status }),
  updateMarketData: (symbol, data) =>
    set((state) => ({
      marketData: { ...state.marketData, [symbol]: data },
    })),
  setPositions: (positions) => set({ positions }),
  setLastUpdate: (timestamp) => set({ lastUpdate: timestamp }),
  addActivity: (message, level) => 
    set((state) => {
      const newEntry = {
        id: Math.random().toString(36).substr(2, 9),
        message,
        level: level as any,
        timestamp: Date.now()
      };
      // Keep last 50 messages
      return { activityLog: [newEntry, ...state.activityLog].slice(0, 50) };
    }),
  setProcessingStatus: (status) => set({ processingStatus: status }),
  addNotification: (message, type) =>
    set((state) => {
      const timestamp = Date.now();
      const newNotification: Notification = {
        id: ++notificationId,
        type,
        message,
        time: getRelativeTime(timestamp),
        timestamp
      };
      // Keep last 20 notifications
      return { notifications: [newNotification, ...state.notifications].slice(0, 20) };
    }),
  clearNotifications: () => set({ notifications: [] }),
  setAdaptiveParams: (params) => set({ adaptiveParams: params }),
  setCorrelationData: (data) => set({ correlationData: data }),
  setTimeframeSignals: (signals) => set({ timeframeSignals: signals }),
  setRebalanceData: (data) => set({ rebalanceData: data }),
  setBotStatus: (status) => set({ botStatus: status }),  
  setProfitTargets: (targets) => set({ profitTargets: targets, lastUpdate: Date.now() }),
  
  setAccountData: (data) => set({ accountData: data, lastUpdate: Date.now() }),
  
  addOrderUpdate: (order) => set((state) => ({
    orderHistory: [order, ...state.orderHistory].slice(0, 20),
    lastUpdate: Date.now()
  })),
  
  setMarketHours: (data) => set({ marketHours: data, lastUpdate: Date.now() }),
  setHealthStatus: (data) => set({ healthStatus: data, lastUpdate: Date.now() }),
  setReadinessReport: (data) => set({ readinessReport: data, lastUpdate: Date.now() }),
  addOperationalEvent: (event) => set((state) => ({
    operationalEvents: [event, ...state.operationalEvents].slice(0, 50),
    lastUpdate: Date.now()
  })),
}));
