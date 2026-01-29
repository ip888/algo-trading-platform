//! Configuration management for the trading bot

use crate::error::{Result, TradingError};
use worker::Env;

/// Trading bot configuration
#[derive(Debug, Clone)]
pub struct Config {
    /// Environment (production, staging, development)
    pub environment: String,

    /// Log level
    pub log_level: String,

    /// Exit parameters (fallback values)
    pub take_profit_percent: f64,
    pub stop_loss_percent: f64,
    pub trailing_stop_percent: f64,

    /// Volatility-Adaptive TP/SL (ATR-based)
    pub atr_sl_multiplier: f64,     // SL at Nx ATR below entry
    pub atr_tp_multiplier: f64,     // TP at Nx ATR above entry
    pub min_sl_percent: f64,        // Min stop-loss bound
    pub max_sl_percent: f64,        // Max stop-loss bound
    pub min_tp_percent: f64,        // Min take-profit bound
    pub max_tp_percent: f64,        // Max take-profit bound

    /// Dynamic Position Sizing (Risk-Based)
    /// Note: These are BASE values - actual values are adjusted by CapitalTier
    pub max_risk_per_trade_percent: f64,    // Base risk % (tier-adjusted: 0.5-2%)
    pub max_portfolio_per_position: f64,    // Base max % per position (tier-adjusted: 20-80%)
    pub min_position_usd: f64,              // Absolute Coinbase minimum
    pub cash_reserve_percent: f64,          // Keep X% cash for opportunities
    pub max_total_positions: usize,         // Hard safety cap (tier adjusts lower)

    /// Fee Configuration (auto-adjusts based on 30-day volume)
    pub base_fee_percent: f64,              // Default fee tier (0.6% for $0-$1K volume)

    /// Adaptive Entry Threshold
    pub base_entry_threshold: f64,          // Base score needed to enter (0-100)
    pub min_entry_threshold: f64,           // Floor (never too permissive)
    pub max_entry_threshold: f64,           // Ceiling (never too restrictive)

    pub cycle_interval_seconds: u64,

    /// Risk management
    pub daily_trade_limit: u32,
    pub max_consecutive_errors: u32,

    /// Strategy filters
    pub enable_trend_filter: bool,      // Only buy dips in uptrends
    pub enable_volume_filter: bool,     // Only trade high-volume coins
    pub enable_market_regime_filter: bool, // Only trade when BTC is green (market bullish)
    pub min_volume_usd: f64,            // Minimum 24h volume in USD
    pub max_position_age_hours: f64,    // Time-based exit (0 = disabled)

    /// Symbols to trade
    pub symbols: Vec<String>,
}

impl Config {
    /// Load configuration from Cloudflare environment variables
    pub fn from_env(env: &Env) -> Result<Self> {
        Ok(Self {
            environment: env.var("ENVIRONMENT").map_or_else(|_| "production".to_string(), |v| v.to_string()),
            
            log_level: env.var("LOG_LEVEL").map_or_else(|_| "info".to_string(), |v| v.to_string()),
            
            take_profit_percent: env.var("TAKE_PROFIT_PERCENT")
                .map(|v| v.to_string().parse().unwrap_or(2.0))
                .unwrap_or(2.0),
            
            stop_loss_percent: env.var("STOP_LOSS_PERCENT")
                .map(|v| v.to_string().parse().unwrap_or(1.5))
                .unwrap_or(1.5),
            
            trailing_stop_percent: env.var("TRAILING_STOP_PERCENT")
                .map(|v| v.to_string().parse().unwrap_or(0.75))
                .unwrap_or(0.75),
            
            // Volatility-Adaptive TP/SL
            atr_sl_multiplier: env.var("ATR_SL_MULTIPLIER")
                .map(|v| v.to_string().parse().unwrap_or(1.0))
                .unwrap_or(1.0),
            
            atr_tp_multiplier: env.var("ATR_TP_MULTIPLIER")
                .map(|v| v.to_string().parse().unwrap_or(2.0))
                .unwrap_or(2.0),
            
            min_sl_percent: env.var("MIN_SL_PERCENT")
                .map(|v| v.to_string().parse().unwrap_or(0.5))
                .unwrap_or(0.5),
            
            max_sl_percent: env.var("MAX_SL_PERCENT")
                .map(|v| v.to_string().parse().unwrap_or(5.0))
                .unwrap_or(5.0),
            
            min_tp_percent: env.var("MIN_TP_PERCENT")
                .map(|v| v.to_string().parse().unwrap_or(1.0))
                .unwrap_or(1.0),
            
            max_tp_percent: env.var("MAX_TP_PERCENT")
                .map(|v| v.to_string().parse().unwrap_or(10.0))
                .unwrap_or(10.0),
            
            // Dynamic Position Sizing
            max_risk_per_trade_percent: env.var("MAX_RISK_PER_TRADE_PERCENT")
                .map(|v| v.to_string().parse().unwrap_or(2.0))
                .unwrap_or(2.0),  // Risk 2% of portfolio per trade
            
            max_portfolio_per_position: env.var("MAX_PORTFOLIO_PER_POSITION")
                .map(|v| v.to_string().parse().unwrap_or(25.0))
                .unwrap_or(25.0),  // Max 25% in one position
            
            min_position_usd: env.var("MIN_POSITION_USD")
                .map(|v| v.to_string().parse().unwrap_or(10.0))
                .unwrap_or(10.0),  // Coinbase minimum
            
            cash_reserve_percent: env.var("CASH_RESERVE_PERCENT")
                .map(|v| v.to_string().parse().unwrap_or(15.0))
                .unwrap_or(15.0),  // Keep 15% cash
            
            max_total_positions: env.var("MAX_TOTAL_POSITIONS")
                .map(|v| v.to_string().parse().unwrap_or(8))
                .unwrap_or(8),  // Hard safety cap

            // Fee Configuration
            base_fee_percent: env.var("BASE_FEE_PERCENT")
                .map(|v| v.to_string().parse().unwrap_or(0.60))
                .unwrap_or(0.60),  // Coinbase default tier

            // Adaptive Entry Threshold
            base_entry_threshold: env.var("BASE_ENTRY_THRESHOLD")
                .map(|v| v.to_string().parse().unwrap_or(60.0))
                .unwrap_or(60.0),  // Base score to enter

            min_entry_threshold: env.var("MIN_ENTRY_THRESHOLD")
                .map(|v| v.to_string().parse().unwrap_or(40.0))
                .unwrap_or(40.0),  // Floor

            max_entry_threshold: env.var("MAX_ENTRY_THRESHOLD")
                .map(|v| v.to_string().parse().unwrap_or(85.0))
                .unwrap_or(85.0),  // Ceiling

            cycle_interval_seconds: env.var("CYCLE_INTERVAL_SECONDS")
                .map(|v| v.to_string().parse().unwrap_or(15))
                .unwrap_or(15),
            
            symbols: env.var("SYMBOLS")
                .map(|v| v.to_string().split(',').map(String::from).collect())
                .unwrap_or_else(|_| vec![
                    "BTC-USD".to_string(),
                    "ETH-USD".to_string(),
                    "SOL-USD".to_string(),
                ]),
            
            daily_trade_limit: env.var("DAILY_TRADE_LIMIT")
                .map(|v| v.to_string().parse().unwrap_or(30))
                .unwrap_or(30),
            
            max_consecutive_errors: env.var("MAX_CONSECUTIVE_ERRORS")
                .map(|v| v.to_string().parse().unwrap_or(5))
                .unwrap_or(5),
            
            enable_trend_filter: env.var("ENABLE_TREND_FILTER")
                .map(|v| v.to_string().to_lowercase() == "true")
                .unwrap_or(true),
            
            enable_volume_filter: env.var("ENABLE_VOLUME_FILTER")
                .map(|v| v.to_string().to_lowercase() == "true")
                .unwrap_or(true),
            
            enable_market_regime_filter: env.var("ENABLE_MARKET_REGIME_FILTER")
                .map(|v| v.to_string().to_lowercase() == "true")
                .unwrap_or(true),  // Don't trade when BTC is red
            
            min_volume_usd: env.var("MIN_VOLUME_USD")
                .map(|v| v.to_string().parse().unwrap_or(1_000_000.0))
                .unwrap_or(1_000_000.0),
            
            max_position_age_hours: env.var("MAX_POSITION_AGE_HOURS")
                .map(|v| v.to_string().parse().unwrap_or(48.0))
                .unwrap_or(48.0),  // Give trades 48h to work out
        })
    }
    
    /// Validate configuration
    pub fn validate(&self) -> Result<()> {
        if self.take_profit_percent <= 0.0 {
            return Err(TradingError::Config("take_profit_percent must be positive".into()));
        }
        if self.stop_loss_percent <= 0.0 {
            return Err(TradingError::Config("stop_loss_percent must be positive".into()));
        }
        if self.max_risk_per_trade_percent <= 0.0 || self.max_risk_per_trade_percent > 10.0 {
            return Err(TradingError::Config("max_risk_per_trade_percent must be 0-10%".into()));
        }
        if self.symbols.is_empty() {
            return Err(TradingError::Config("At least one symbol required".into()));
        }
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    
    #[test]
    fn test_config_defaults() {
        // Config validation tests would go here
        // Note: Full tests require mocking Env
    }
}
