import { useEffect, useState } from 'react';
import './SmartFeaturesPanel.css';
import { CONFIG } from '../config';

interface Profile {
  takeProfitPercent: number;
  stopLossPercent: number;
  trailingStopPercent: number;
  capitalPercent: number;
  bullishSymbols: string[];
  bearishSymbols: string[];
}

interface RiskManagement {
  maxLossPercent: number;
  maxLossExitEnabled: boolean;
  portfolioStopLossPercent: number;
  portfolioStopLossEnabled: boolean;
  pdtProtectionEnabled: boolean;
}

interface PositionSizing {
  method: string;
  kellyFraction: number;
  kellyRiskReward: number;
  defaultWinRate: number;
}

interface AdvancedFeatures {
  regimeDetectionEnabled: boolean;
  multiTimeframeEnabled: boolean;
  multiProfileEnabled: boolean;
  currentRegime: string;
}

interface BotConfig {
  tradingMode: string;
  initialCapital: number;
  mainProfile: Profile;
  experimentalProfile: Profile;
  riskManagement: RiskManagement;
  positionSizing: PositionSizing;
  advancedFeatures: AdvancedFeatures;
  vixSettings: { threshold: number; hysteresis: number };
  rateLimiting: { apiRequestDelayMs: number; symbolBatchSize: number };
}

export default function SmartFeaturesPanel() {
  const [config, setConfig] = useState<BotConfig | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    const fetchConfig = async () => {
      try {
        const res = await fetch(`${CONFIG.API_BASE_URL}/api/system/config`);
        if (!res.ok) throw new Error('Failed to fetch config');
        const data = await res.json();
        setConfig(data);
        setError(null);
      } catch (e) {
        setError((e as Error).message);
      } finally {
        setLoading(false);
      }
    };

    fetchConfig();
    const interval = setInterval(fetchConfig, 30000); // Refresh every 30s
    return () => clearInterval(interval);
  }, []);

  if (loading) return <div className="smart-features loading">Loading config...</div>;
  if (error) return <div className="smart-features error">Error: {error}</div>;
  if (!config) return null;

  return (
    <div className="smart-features">
      <h2>🧠 Smart Trading Features</h2>
      
      {/* Trading Mode Banner */}
      <div className={`mode-banner ${config.tradingMode.toLowerCase()}`}>
        <span className="mode-icon">{config.tradingMode === 'LIVE' ? '🔴' : '📝'}</span>
        <span className="mode-text">{config.tradingMode} TRADING</span>
        <span className="capital">Capital: ${config.initialCapital.toLocaleString()}</span>
      </div>

      {/* Market Regime */}
      <div className="feature-section">
        <h3>🌍 Market Regime</h3>
        <div className={`regime-badge ${config.advancedFeatures.currentRegime.toLowerCase()}`}>
          {config.advancedFeatures.currentRegime}
        </div>
        <div className="vix-info">
          VIX Threshold: {config.vixSettings.threshold} (±{config.vixSettings.hysteresis})
        </div>
      </div>

      {/* Profiles Grid */}
      <div className="profiles-grid">
        <div className="profile main-profile">
          <h3>📊 Main Profile ({config.mainProfile.capitalPercent}%)</h3>
          <div className="profile-stats">
            <div className="stat">
              <span className="label">Take Profit</span>
              <span className="value positive">+{config.mainProfile.takeProfitPercent}%</span>
            </div>
            <div className="stat">
              <span className="label">Stop Loss</span>
              <span className="value negative">-{config.mainProfile.stopLossPercent}%</span>
            </div>
            <div className="stat">
              <span className="label">Trailing Stop</span>
              <span className="value">{config.mainProfile.trailingStopPercent}%</span>
            </div>
          </div>
          <div className="symbols-preview">
            📈 {config.mainProfile.bullishSymbols.slice(0, 5).join(', ')}...
          </div>
        </div>

        <div className="profile exp-profile">
          <h3>🔬 Experimental ({config.experimentalProfile.capitalPercent}%)</h3>
          <div className="profile-stats">
            <div className="stat">
              <span className="label">Take Profit</span>
              <span className="value positive">+{config.experimentalProfile.takeProfitPercent}%</span>
            </div>
            <div className="stat">
              <span className="label">Stop Loss</span>
              <span className="value negative">-{config.experimentalProfile.stopLossPercent}%</span>
            </div>
            <div className="stat">
              <span className="label">Trailing Stop</span>
              <span className="value">{config.experimentalProfile.trailingStopPercent}%</span>
            </div>
          </div>
          <div className="symbols-preview">
            📉 {config.experimentalProfile.bearishSymbols.slice(0, 4).join(', ')}...
          </div>
        </div>
      </div>

      {/* Position Sizing */}
      <div className="feature-section">
        <h3>📐 Position Sizing ({config.positionSizing.method})</h3>
        <div className="sizing-stats">
          <div className="stat">
            <span className="label">Kelly Fraction</span>
            <span className="value">{(config.positionSizing.kellyFraction * 100).toFixed(0)}%</span>
          </div>
          <div className="stat">
            <span className="label">Risk/Reward</span>
            <span className="value">{config.positionSizing.kellyRiskReward}:1</span>
          </div>
          <div className="stat">
            <span className="label">Win Rate</span>
            <span className="value">{(config.positionSizing.defaultWinRate * 100).toFixed(0)}%</span>
          </div>
        </div>
      </div>

      {/* Risk Management */}
      <div className="feature-section">
        <h3>🛡️ Risk Management</h3>
        <div className="risk-grid">
          <div className={`risk-item ${config.riskManagement.pdtProtectionEnabled ? 'active' : ''}`}>
            <span className="icon">{config.riskManagement.pdtProtectionEnabled ? '✅' : '❌'}</span>
            <span className="name">PDT Protection</span>
          </div>
          <div className={`risk-item ${config.riskManagement.maxLossExitEnabled ? 'active' : ''}`}>
            <span className="icon">{config.riskManagement.maxLossExitEnabled ? '✅' : '❌'}</span>
            <span className="name">Max Loss Exit ({config.riskManagement.maxLossPercent}%)</span>
          </div>
          <div className={`risk-item ${config.riskManagement.portfolioStopLossEnabled ? 'active' : ''}`}>
            <span className="icon">{config.riskManagement.portfolioStopLossEnabled ? '✅' : '❌'}</span>
            <span className="name">Portfolio Stop ({config.riskManagement.portfolioStopLossPercent}%)</span>
          </div>
        </div>
      </div>

      {/* Advanced Features */}
      <div className="feature-section">
        <h3>⚡ Advanced Features</h3>
        <div className="features-grid">
          <div className={`feature-item ${config.advancedFeatures.regimeDetectionEnabled ? 'active' : ''}`}>
            🌍 Regime Detection
          </div>
          <div className={`feature-item ${config.advancedFeatures.multiTimeframeEnabled ? 'active' : ''}`}>
            📊 Multi-Timeframe
          </div>
          <div className={`feature-item ${config.advancedFeatures.multiProfileEnabled ? 'active' : ''}`}>
            👥 Multi-Profile
          </div>
        </div>
      </div>

      {/* Rate Limiting */}
      <div className="feature-section small">
        <span className="label">⏱️ API Delay: {config.rateLimiting.apiRequestDelayMs}ms</span>
        <span className="label">📦 Batch Size: {config.rateLimiting.symbolBatchSize}</span>
      </div>
    </div>
  );
}
