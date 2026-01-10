import React, { useEffect, useState } from 'react';
import { Activity, TrendingUp, Shield, Target, Zap, BarChart3, Brain, Heart } from 'lucide-react';
import { CONFIG } from '../config';

interface Phase3Feature {
  name: string;
  enabled: boolean;
  status: 'active' | 'idle' | 'triggered';
  lastActivity: string;
  impact: string;
  icon: React.ReactNode;
  color: string;
}

interface Phase3Stats {
  mlScore: number;
  adaptiveSize: number;
  trailingStops: number;
  healthScore: number;
  marketBreadth: number;
  momentumSpikes: number;
  timeDecayExits: number;
  volumeProfile: boolean;
}

export default function Phase3Dashboard() {
  const [features, setFeatures] = useState<Phase3Feature[]>([
    {
      name: 'ML Entry Scoring',
      enabled: true,
      status: 'idle',
      lastActivity: 'Never',
      impact: '+0.8%',
      icon: <Brain className="w-5 h-5" />,
      color: 'from-purple-500 to-pink-500'
    },
    {
      name: 'Market Breadth Filter',
      enabled: true,
      status: 'active',
      lastActivity: 'Just now',
      impact: '+0.4%',
      icon: <BarChart3 className="w-5 h-5" />,
      color: 'from-blue-500 to-cyan-500'
    },
    {
      name: 'Adaptive Position Sizing',
      enabled: true,
      status: 'active',
      lastActivity: 'Just now',
      impact: '+0.5%',
      icon: <TrendingUp className="w-5 h-5" />,
      color: 'from-green-500 to-emerald-500'
    },
    {
      name: 'Trailing Profit Targets',
      enabled: true,
      status: 'idle',
      lastActivity: 'Never',
      impact: '+0.7%',
      icon: <Target className="w-5 h-5" />,
      color: 'from-yellow-500 to-orange-500'
    },
    {
      name: 'Time-Decay Exits',
      enabled: true,
      status: 'idle',
      lastActivity: 'Never',
      impact: '+0.3%',
      icon: <Activity className="w-5 h-5" />,
      color: 'from-red-500 to-pink-500'
    },
    {
      name: 'Momentum Acceleration',
      enabled: true,
      status: 'idle',
      lastActivity: 'Never',
      impact: '+0.4%',
      icon: <Zap className="w-5 h-5" />,
      color: 'from-amber-500 to-yellow-500'
    },
    {
      name: 'Position Health Scoring',
      enabled: true,
      status: 'idle',
      lastActivity: 'Never',
      impact: '+0.2%',
      icon: <Heart className="w-5 h-5" />,
      color: 'from-rose-500 to-red-500'
    },
    {
      name: 'Volume Profile Analysis',
      enabled: true,
      status: 'idle',
      lastActivity: 'Never',
      impact: '+0.3%',
      icon: <Shield className="w-5 h-5" />,
      color: 'from-indigo-500 to-purple-500'
    }
  ]);

  const [stats, setStats] = useState<Phase3Stats>({
    mlScore: 0,
    adaptiveSize: 0,
    trailingStops: 0,
    healthScore: 0,
    marketBreadth: 0,
    momentumSpikes: 0,
    timeDecayExits: 0,
    volumeProfile: false
  });

  const [fpslIncome, setFpslIncome] = useState(0);

  useEffect(() => {
    // Connect to WebSocket for Phase 3 updates
    const ws = new WebSocket(CONFIG.WS_URL);

    ws.onmessage = (event) => {
      const data = JSON.parse(event.data);
      
      // Handle Phase 3 feature events
      if (data.type === 'PHASE3_ML_SCORE') {
        setStats(prev => ({ ...prev, mlScore: data.score }));
        updateFeatureActivity('ML Entry Scoring', 'triggered');
      }
      
      if (data.type === 'PHASE3_ADAPTIVE_SIZE') {
        setStats(prev => ({ ...prev, adaptiveSize: data.size }));
        updateFeatureActivity('Adaptive Position Sizing', 'active');
      }
      
      if (data.type === 'PHASE3_TRAILING_STOP') {
        setStats(prev => ({ ...prev, trailingStops: prev.trailingStops + 1 }));
        updateFeatureActivity('Trailing Profit Targets', 'triggered');
      }
      
      if (data.type === 'PHASE3_HEALTH_SCORE') {
        setStats(prev => ({ ...prev, healthScore: data.score }));
        updateFeatureActivity('Position Health Scoring', 'active');
      }
      
      if (data.type === 'PHASE3_MARKET_BREADTH') {
        setStats(prev => ({ ...prev, marketBreadth: data.breadth }));
        updateFeatureActivity('Market Breadth Filter', 'active');
      }
      
      if (data.type === 'PHASE3_MOMENTUM_SPIKE') {
        setStats(prev => ({ ...prev, momentumSpikes: prev.momentumSpikes + 1 }));
        updateFeatureActivity('Momentum Acceleration', 'triggered');
      }
      
      if (data.type === 'PHASE3_TIME_DECAY') {
        setStats(prev => ({ ...prev, timeDecayExits: prev.timeDecayExits + 1 }));
        updateFeatureActivity('Time-Decay Exits', 'triggered');
      }
      
      if (data.type === 'PHASE3_VOLUME_PROFILE') {
        setStats(prev => ({ ...prev, volumeProfile: data.valid }));
        updateFeatureActivity('Volume Profile Analysis', data.valid ? 'active' : 'idle');
      }
      
      if (data.type === 'FPSL_INCOME') {
        setFpslIncome(data.income);
      }
    };

    return () => ws.close();
  }, []);

  const updateFeatureActivity = (name: string, status: 'active' | 'idle' | 'triggered') => {
    setFeatures(prev => prev.map(f => 
      f.name === name 
        ? { ...f, status, lastActivity: new Date().toLocaleTimeString() }
        : f
    ));
  };

  const getStatusColor = (status: string) => {
    switch (status) {
      case 'active': return 'text-green-400';
      case 'triggered': return 'text-yellow-400';
      default: return 'text-gray-400';
    }
  };

  const getStatusDot = (status: string) => {
    switch (status) {
      case 'active': return 'bg-green-500 animate-pulse';
      case 'triggered': return 'bg-yellow-500 animate-pulse';
      default: return 'bg-gray-500';
    }
  };

  const totalImpact = features.reduce((sum, f) => 
    sum + parseFloat(f.impact.replace('%', '').replace('+', '')), 0
  );

  return (
    <div className="space-y-4">
      {/* Header */}
      <div className="bg-gradient-to-r from-purple-900/50 to-pink-900/50 rounded-lg p-4 border border-purple-500/30">
        <div className="flex items-center justify-between">
          <div>
            <h2 className="text-xl font-bold text-white flex items-center gap-2">
              <Brain className="w-6 h-6 text-purple-400" />
              Phase 3 Advanced Features
            </h2>
            <p className="text-sm text-gray-400 mt-1">
              8 AI-powered trading enhancements active
            </p>
          </div>
          <div className="text-right">
            <div className="text-3xl font-bold text-transparent bg-clip-text bg-gradient-to-r from-green-400 to-emerald-400">
              +{totalImpact.toFixed(1)}%
            </div>
            <div className="text-xs text-gray-400">Expected Daily Impact</div>
          </div>
        </div>
      </div>

      {/* FPSL Passive Income */}
      <div className="bg-gradient-to-r from-green-900/30 to-emerald-900/30 rounded-lg p-4 border border-green-500/30">
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-3">
            <div className="w-10 h-10 rounded-full bg-green-500/20 flex items-center justify-center">
              <TrendingUp className="w-5 h-5 text-green-400" />
            </div>
            <div>
              <div className="text-sm font-medium text-gray-300">FPSL Passive Income</div>
              <div className="text-xs text-gray-500">Stock Lending Earnings</div>
            </div>
          </div>
          <div className="text-right">
            <div className="text-2xl font-bold text-green-400">
              ${fpslIncome.toFixed(4)}
            </div>
            <div className="text-xs text-gray-400">Total Earned</div>
          </div>
        </div>
      </div>

      {/* Feature Grid */}
      <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
        {features.map((feature, index) => (
          <div
            key={index}
            className="bg-gray-800/50 rounded-lg p-4 border border-gray-700/50 hover:border-gray-600/50 transition-all"
          >
            <div className="flex items-start justify-between mb-3">
              <div className="flex items-center gap-2">
                <div className={`w-8 h-8 rounded-lg bg-gradient-to-br ${feature.color} p-1.5 flex items-center justify-center`}>
                  {feature.icon}
                </div>
                <div>
                  <div className="text-sm font-medium text-white">{feature.name}</div>
                  <div className="text-xs text-gray-500">{feature.impact} impact</div>
                </div>
              </div>
              <div className="flex items-center gap-2">
                <div className={`w-2 h-2 rounded-full ${getStatusDot(feature.status)}`} />
                <span className={`text-xs font-medium ${getStatusColor(feature.status)}`}>
                  {feature.status}
                </span>
              </div>
            </div>
            
            <div className="text-xs text-gray-400">
              Last activity: {feature.lastActivity}
            </div>
          </div>
        ))}
      </div>

      {/* Live Stats */}
      <div className="bg-gray-800/50 rounded-lg p-4 border border-gray-700/50">
        <h3 className="text-sm font-semibold text-white mb-3">Live Phase 3 Stats</h3>
        <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
          <div>
            <div className="text-xs text-gray-400">ML Score</div>
            <div className="text-lg font-bold text-purple-400">{stats.mlScore.toFixed(1)}</div>
          </div>
          <div>
            <div className="text-xs text-gray-400">Adaptive Size</div>
            <div className="text-lg font-bold text-green-400">${stats.adaptiveSize.toFixed(2)}</div>
          </div>
          <div>
            <div className="text-xs text-gray-400">Market Breadth</div>
            <div className="text-lg font-bold text-blue-400">{(stats.marketBreadth * 100).toFixed(0)}%</div>
          </div>
          <div>
            <div className="text-xs text-gray-400">Health Score</div>
            <div className="text-lg font-bold text-rose-400">{stats.healthScore.toFixed(0)}</div>
          </div>
          <div>
            <div className="text-xs text-gray-400">Trailing Stops</div>
            <div className="text-lg font-bold text-yellow-400">{stats.trailingStops}</div>
          </div>
          <div>
            <div className="text-xs text-gray-400">Momentum Spikes</div>
            <div className="text-lg font-bold text-amber-400">{stats.momentumSpikes}</div>
          </div>
          <div>
            <div className="text-xs text-gray-400">Time-Decay Exits</div>
            <div className="text-lg font-bold text-red-400">{stats.timeDecayExits}</div>
          </div>
          <div>
            <div className="text-xs text-gray-400">Volume Valid</div>
            <div className="text-lg font-bold text-indigo-400">
              {stats.volumeProfile ? '✓' : '✗'}
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}
