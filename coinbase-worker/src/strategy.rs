//! Trading strategy implementation
//!
//! Implements a momentum-based trading strategy with:
//! - Entry signals based on price position in 24h range
//! - Take-profit and stop-loss exits
//! - Trailing stop for profit protection

use crate::config::Config;
use crate::types::Position;

/// Trading strategy engine
pub struct TradingStrategy {
    config: Config,
}

/// Market analysis result for a symbol
#[derive(Debug, Clone)]
pub struct MarketAnalysis {
    pub symbol: String,
    pub price: f64,
    pub change_24h: f64,
    pub range_position: f64,  // 0-100, lower = near 24h low
    pub signal: TradingSignal,
    pub confidence: f64,      // 0-1
    pub is_uptrend: bool,     // Trend filter: price above 6h avg
    pub volume_24h: f64,      // Volume filter: 24h volume in USD
    pub rejection_reason: Option<String>,  // Why trade was rejected
}

/// Trading signal
#[derive(Debug, Clone, PartialEq)]
pub enum TradingSignal {
    Buy,
    Sell,
    Hold,
}

impl TradingStrategy {
    /// Create new strategy with configuration
    pub fn new(config: Config) -> Self {
        Self { config }
    }
    
    /// Analyze market conditions for a symbol
    pub fn analyze(
        &self, 
        symbol: &str, 
        price: f64, 
        change_24h: f64, 
        high_24h: f64, 
        low_24h: f64,
        is_uptrend: bool,
        volume_24h: f64,
    ) -> MarketAnalysis {
        // Calculate position in 24h range (0 = at low, 100 = at high)
        let range = high_24h - low_24h;
        let range_position = if range > 0.0 {
            ((price - low_24h) / range * 100.0).clamp(0.0, 100.0)
        } else {
            50.0
        };
        
        // Volume in USD (volume * price)
        let volume_usd = volume_24h * price;
        
        // Check filters
        let mut rejection_reason: Option<String> = None;
        
        // Trend filter: only buy if in uptrend (price above 6h average)
        if self.config.enable_trend_filter && !is_uptrend {
            rejection_reason = Some("Downtrend (price below 6h avg)".to_string());
        }
        
        // Volume filter: only trade high-volume coins
        if self.config.enable_volume_filter && volume_usd < self.config.min_volume_usd {
            rejection_reason = Some(format!("Low volume (${:.0} < ${:.0})", volume_usd, self.config.min_volume_usd));
        }
        
        // Entry criteria (only if filters pass):
        // 1. Price in lower 35% of 24h range (buy the dip)
        // 2. Day change > -2% (avoid falling knives)
        // 3. Day change < 3% (avoid FOMO)
        let (signal, confidence) = if rejection_reason.is_some() {
            (TradingSignal::Hold, 0.0)
        } else if range_position < 35.0 
            && change_24h > -2.0 
            && change_24h < 3.0 
        {
            // Strong buy signal if in lower 25%
            let conf = if range_position < 25.0 { 0.8 } else { 0.6 };
            (TradingSignal::Buy, conf)
        } else if range_position > 80.0 || change_24h > 5.0 {
            // Consider selling if at top of range or big move
            (TradingSignal::Sell, 0.5)
        } else {
            (TradingSignal::Hold, 0.0)
        };
        
        MarketAnalysis {
            symbol: symbol.to_string(),
            price,
            change_24h,
            range_position,
            signal,
            confidence,
            is_uptrend,
            volume_24h: volume_usd,
            rejection_reason,
        }
    }
    
    /// Calculate dynamic TP/SL based on volatility (ATR-based)
    /// Returns (stop_loss_price, take_profit_price, sl_percent, tp_percent)
    pub fn calculate_dynamic_tp_sl(&self, entry_price: f64, volatility_percent: f64) -> (f64, f64, f64, f64) {
        // ATR-based calculation
        // volatility_percent is the 24h range as % (high-low)/low * 100
        let atr_percent = volatility_percent / 2.0; // Approximate ATR as half of daily range
        
        // Calculate raw SL/TP percentages based on ATR multipliers
        let raw_sl_percent = atr_percent * self.config.atr_sl_multiplier;
        let raw_tp_percent = atr_percent * self.config.atr_tp_multiplier;
        
        // Clamp to min/max bounds
        let sl_percent = raw_sl_percent.clamp(self.config.min_sl_percent, self.config.max_sl_percent);
        let tp_percent = raw_tp_percent.clamp(self.config.min_tp_percent, self.config.max_tp_percent);
        
        // Calculate actual price levels
        let stop_loss_price = entry_price * (1.0 - sl_percent / 100.0);
        let take_profit_price = entry_price * (1.0 + tp_percent / 100.0);
        
        (stop_loss_price, take_profit_price, sl_percent, tp_percent)
    }
    
    /// Check if a position should be closed (SL/TP hit or time-based)
    /// Uses position-specific dynamic TP/SL if available, falls back to config defaults
    pub fn check_exit(&self, position: &Position, current_price: f64) -> Option<ExitReason> {
        // Check stop-loss (prefer position-specific, fallback to config)
        if let Some(sl_price) = position.stop_loss_price {
            if current_price <= sl_price {
                return Some(ExitReason::StopLoss);
            }
        } else {
            let pnl_percent = (current_price - position.entry_price) / position.entry_price * 100.0;
            if pnl_percent <= -self.config.stop_loss_percent {
                return Some(ExitReason::StopLoss);
            }
        }
        
        // Check take-profit (prefer position-specific, fallback to config)
        if let Some(tp_price) = position.take_profit_price {
            if current_price >= tp_price {
                return Some(ExitReason::TakeProfit);
            }
        } else {
            let pnl_percent = (current_price - position.entry_price) / position.entry_price * 100.0;
            if pnl_percent >= self.config.take_profit_percent {
                return Some(ExitReason::TakeProfit);
            }
        }
        
        // Trailing stop check (uses high water mark from position)
        if let Some(high_water_mark) = position.high_water_mark {
            let trailing_sl_price = high_water_mark * (1.0 - self.config.trailing_stop_percent / 100.0);
            let pnl_percent = (current_price - position.entry_price) / position.entry_price * 100.0;
            if current_price <= trailing_sl_price && pnl_percent > 0.0 {
                return Some(ExitReason::TrailingStop);
            }
        }
        
        // Time-based exit: close if held too long without action
        if self.config.max_position_age_hours > 0.0 {
            if let Ok(entry_time) = chrono::DateTime::parse_from_rfc3339(&position.entry_time) {
                let now = chrono::Utc::now();
                let hours_held = (now.timestamp() - entry_time.timestamp()) as f64 / 3600.0;
                if hours_held >= self.config.max_position_age_hours {
                    return Some(ExitReason::TimeExpired);
                }
            }
        }
        
        None
    }
    
    /// Calculate position size using risk-based dynamic sizing
    /// 
    /// Formula: Position = min(RiskAmount / StopLoss%, MaxPortfolioPercent, AvailableAfterReserve)
    /// 
    /// Example with $1000 portfolio, 2% risk, 1.5% stop-loss:
    /// - Risk amount = $1000 × 2% = $20
    /// - Position from risk = $20 / 1.5% = $1,333
    /// - Max per position = $1000 × 25% = $250
    /// - Result = $250 (capped)
    pub fn calculate_position_size(&self, total_portfolio: f64, available_usd: f64, volatility_factor: f64) -> PositionSizeResult {
        // Calculate available after cash reserve
        let reserved_cash = total_portfolio * (self.config.cash_reserve_percent / 100.0);
        let available_for_trading = (total_portfolio - reserved_cash).max(0.0);
        
        // Risk-based sizing: how much can we risk?
        let risk_amount = total_portfolio * (self.config.max_risk_per_trade_percent / 100.0);
        let stop_loss_decimal = self.config.stop_loss_percent / 100.0;
        
        // Position size that would risk exactly our risk amount at stop-loss
        let risk_based_size = if stop_loss_decimal > 0.0 {
            risk_amount / stop_loss_decimal
        } else {
            available_for_trading * 0.25  // Fallback
        };
        
        // Apply volatility adjustment (high volatility = smaller position)
        // volatility_factor: 1.0 = normal, >1.0 = high vol, <1.0 = low vol
        let volatility_adjusted = risk_based_size / volatility_factor.max(0.5);
        
        // Cap at max % of portfolio per position
        let max_per_position = total_portfolio * (self.config.max_portfolio_per_position / 100.0);
        
        // Can't use more than available cash
        let capped_size = volatility_adjusted
            .min(max_per_position)
            .min(available_usd)
            .min(available_for_trading);
        
        // Final size must be above minimum
        let final_size = if capped_size >= self.config.min_position_usd {
            capped_size
        } else {
            0.0  // Can't trade - not enough capital
        };
        
        PositionSizeResult {
            size: final_size,
            risk_based: risk_based_size,
            volatility_adjusted,
            max_per_position,
            available_after_reserve: available_for_trading,
            can_trade: final_size >= self.config.min_position_usd,
            reason: if final_size < self.config.min_position_usd {
                Some(format!("Below ${} minimum", self.config.min_position_usd))
            } else {
                None
            },
        }
    }
    
    /// Calculate how many more positions we can open
    /// Now based purely on available capital, not arbitrary counts
    pub fn max_new_positions(&self, total_portfolio: f64, current_positions: usize) -> usize {
        // Hard safety cap from config (prevents runaway)
        let hard_cap = self.config.max_total_positions;
        if current_positions >= hard_cap {
            return 0;
        }
        
        // Calculate how many more positions we can afford
        // Based on: available capital / position size
        let max_per_position = total_portfolio * (self.config.max_portfolio_per_position / 100.0);
        let reserve = total_portfolio * (self.config.cash_reserve_percent / 100.0);
        let min_size = self.config.min_position_usd;
        
        // Estimate current positions value (assume max size each for conservative calc)
        let positions_value = current_positions as f64 * max_per_position;
        let available_for_new = (total_portfolio - positions_value - reserve).max(0.0);
        
        // How many more positions can we open with remaining capital?
        let capital_based_new = if max_per_position > min_size {
            (available_for_new / max_per_position).floor() as usize
        } else {
            0
        };
        
        // Return minimum of hard cap remaining and capital-based limit
        let remaining_to_cap = hard_cap.saturating_sub(current_positions);
        remaining_to_cap.min(capital_based_new.max(1)) // At least 1 if under hard cap
    }
    
    /// Check if we should enter a new position
    pub fn should_enter(&self, analysis: &MarketAnalysis, current_positions: usize, total_portfolio: f64) -> bool {
        // Check position cap
        if self.max_new_positions(total_portfolio, current_positions) == 0 {
            return false;
        }
        
        // Only enter on Buy signal with sufficient confidence
        analysis.signal == TradingSignal::Buy && analysis.confidence >= 0.6
    }
}

/// Result of position size calculation
#[derive(Debug, Clone)]
pub struct PositionSizeResult {
    pub size: f64,                      // Final position size in USD
    pub risk_based: f64,                // Size based purely on risk calculation
    pub volatility_adjusted: f64,       // After volatility adjustment
    pub max_per_position: f64,          // Max allowed per position
    pub available_after_reserve: f64,   // Available after cash reserve
    pub can_trade: bool,                // Whether we can open a position
    pub reason: Option<String>,         // Why we can't trade (if applicable)
}

/// Reason for exiting a position
#[derive(Debug, Clone, PartialEq)]
pub enum ExitReason {
    StopLoss,
    TakeProfit,
    TrailingStop,
    TimeExpired,
    Manual,
}

impl std::fmt::Display for ExitReason {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            ExitReason::StopLoss => write!(f, "Stop Loss"),
            ExitReason::TakeProfit => write!(f, "Take Profit"),
            ExitReason::TrailingStop => write!(f, "Trailing Stop"),
            ExitReason::TimeExpired => write!(f, "Time Expired (12h)"),
            ExitReason::Manual => write!(f, "Manual"),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    
    fn test_config() -> Config {
        Config {
            environment: "test".to_string(),
            log_level: "debug".to_string(),
            take_profit_percent: 1.5,
            stop_loss_percent: 1.0,
            trailing_stop_percent: 0.5,
            atr_sl_multiplier: 1.0,
            atr_tp_multiplier: 2.0,
            min_sl_percent: 0.5,
            max_sl_percent: 5.0,
            min_tp_percent: 1.0,
            max_tp_percent: 10.0,
            max_risk_per_trade_percent: 2.0,
            max_portfolio_per_position: 25.0,
            min_position_usd: 10.0,
            cash_reserve_percent: 15.0,
            max_total_positions: 8,
            cycle_interval_seconds: 15,
            symbols: vec!["BTC-USD".to_string()],
            daily_trade_limit: 30,
            max_consecutive_errors: 5,
            enable_trend_filter: false,  // Disable for basic tests
            enable_volume_filter: false,  // Disable for basic tests
            min_volume_usd: 1_000_000.0,
            max_position_age_hours: 12.0,
        }
    }
    
    #[test]
    fn test_analyze_buy_signal() {
        let strategy = TradingStrategy::new(test_config());
        
        // Price near 24h low, modest decline, uptrend, good volume
        let analysis = strategy.analyze("BTC-USD", 50000.0, -0.5, 52000.0, 49000.0, true, 100.0);
        
        assert_eq!(analysis.signal, TradingSignal::Buy);
        assert!(analysis.range_position < 40.0);
    }
    
    #[test]
    fn test_analyze_avoid_falling_knife() {
        let strategy = TradingStrategy::new(test_config());
        
        // Price crashing hard (-5%)
        let analysis = strategy.analyze("BTC-USD", 48000.0, -5.0, 52000.0, 47000.0, true, 100.0);
        
        assert_eq!(analysis.signal, TradingSignal::Hold);
    }
    
    #[test]
    fn test_check_exit_stop_loss() {
        let strategy = TradingStrategy::new(test_config());
        
        let position = Position {
            symbol: "BTC-USD".to_string(),
            quantity: 0.001,
            entry_price: 50000.0,
            entry_time: "2024-01-01T00:00:00Z".to_string(),
            high_water_mark: None,
            stop_loss_price: None,  // Use config fallback
            take_profit_price: None,
            entry_volatility: None,
        };
        
        // Price dropped 1.5% (below 1% SL)
        let exit = strategy.check_exit(&position, 49250.0);
        assert_eq!(exit, Some(ExitReason::StopLoss));
    }
    
    #[test]
    fn test_check_exit_take_profit() {
        let strategy = TradingStrategy::new(test_config());
        
        let position = Position {
            symbol: "BTC-USD".to_string(),
            quantity: 0.001,
            entry_price: 50000.0,
            entry_time: "2024-01-01T00:00:00Z".to_string(),
            high_water_mark: None,
            stop_loss_price: None,
            take_profit_price: None,  // Use config fallback
            entry_volatility: None,
        };
        
        // Price up 2% (above 1.5% TP)
        let exit = strategy.check_exit(&position, 51000.0);
        assert_eq!(exit, Some(ExitReason::TakeProfit));
    }
    
    #[test]
    fn test_dynamic_tp_sl_calculation() {
        let strategy = TradingStrategy::new(test_config());
        
        // Entry at $50,000, volatility 4% (daily range)
        // ATR ≈ 2% (half of range)
        // SL = 1x ATR = 2%, TP = 2x ATR = 4%
        let (sl, tp, sl_pct, tp_pct) = strategy.calculate_dynamic_tp_sl(50000.0, 4.0);
        
        assert!((sl_pct - 2.0).abs() < 0.01);  // 2% SL
        assert!((tp_pct - 4.0).abs() < 0.01);  // 4% TP
        assert!((sl - 49000.0).abs() < 1.0);   // $49,000 SL
        assert!((tp - 52000.0).abs() < 1.0);   // $52,000 TP
        
        // Low volatility: 1% range → 0.5% ATR
        // Should be clamped to min (0.5% SL, 1% TP)
        let (_, _, sl_pct, tp_pct) = strategy.calculate_dynamic_tp_sl(50000.0, 1.0);
        assert_eq!(sl_pct, 0.5);  // Clamped to min
        assert_eq!(tp_pct, 1.0);  // Clamped to min
        
        // High volatility: 12% range → 6% ATR
        // Should be clamped to max (5% SL, 10% TP)
        let (_, _, sl_pct, tp_pct) = strategy.calculate_dynamic_tp_sl(50000.0, 12.0);
        assert_eq!(sl_pct, 5.0);  // Clamped to max
        assert_eq!(tp_pct, 10.0); // Clamped to max
    }
    
    #[test]
    fn test_position_specific_sl_tp() {
        let strategy = TradingStrategy::new(test_config());
        
        // Position with custom SL at $49,000 (2% below $50k entry)
        let recent_time = chrono::Utc::now().to_rfc3339();
        let position = Position {
            symbol: "BTC-USD".to_string(),
            quantity: 0.001,
            entry_price: 50000.0,
            entry_time: recent_time.clone(),
            high_water_mark: None,
            stop_loss_price: Some(49000.0),  // Custom 2% SL
            take_profit_price: Some(52000.0), // Custom 4% TP
            entry_volatility: Some(4.0),
        };
        
        // Price at $48,900 (below custom SL) - should trigger
        let exit = strategy.check_exit(&position, 48900.0);
        assert_eq!(exit, Some(ExitReason::StopLoss));
        
        // Price at $52,100 (above custom TP) - should trigger
        let exit = strategy.check_exit(&position, 52100.0);
        assert_eq!(exit, Some(ExitReason::TakeProfit));
        
        // Price at $50,500 (between SL and TP) - no exit
        let exit = strategy.check_exit(&position, 50500.0);
        assert_eq!(exit, None);
    }
    
    #[test]
    fn test_position_sizing_risk_based() {
        let strategy = TradingStrategy::new(test_config());
        
        // With $1000 portfolio, 2% risk, 1% stop-loss:
        // Risk = $20, Position = $20/0.01 = $2000
        // But capped at 25% = $250
        let sizing = strategy.calculate_position_size(1000.0, 1000.0, 1.0);
        assert!(sizing.can_trade);
        assert_eq!(sizing.size, 250.0);  // Capped at 25%
        
        // With $100 portfolio, 2% risk, 1% stop-loss:
        // Risk = $2, Position = $2/0.01 = $200
        // But capped at 25% = $25, and available after 15% reserve = $85
        let sizing = strategy.calculate_position_size(100.0, 100.0, 1.0);
        assert!(sizing.can_trade);
        assert_eq!(sizing.size, 25.0);  // Capped at 25%
        
        // With $50 portfolio:
        // 25% max = $12.50, which is above $10 min
        let sizing = strategy.calculate_position_size(50.0, 50.0, 1.0);
        assert!(sizing.can_trade);
        assert_eq!(sizing.size, 12.5);
        
        // High volatility (2x) reduces position size
        let sizing_normal = strategy.calculate_position_size(1000.0, 1000.0, 1.0);
        let sizing_high_vol = strategy.calculate_position_size(1000.0, 1000.0, 2.0);
        // With 2x volatility, risk_based is halved, but we're still capped at 25%
        // so both end up at $250
        assert!(sizing_high_vol.volatility_adjusted < sizing_normal.volatility_adjusted);
    }
    
    #[test]
    fn test_max_new_positions() {
        let strategy = TradingStrategy::new(test_config());
        
        // With $1000 portfolio:
        // - 25% max per position = $250
        // - 15% reserve = $150
        // - Available for positions = $850
        // - Max positions = 850/250 = 3.4 → 3 new when at 0
        // But we guarantee at least 1 if under hard cap
        assert!(strategy.max_new_positions(1000.0, 0) >= 1);
        
        // At hard cap (8) - no more positions
        assert_eq!(strategy.max_new_positions(1000.0, 8), 0);
        
        // With positions already, still allows more if capital available
        assert!(strategy.max_new_positions(1000.0, 2) >= 1);
        
        // Tiny portfolio - still allows at least 1 if under cap
        assert!(strategy.max_new_positions(50.0, 0) >= 1);
    }
    
    #[test]
    fn test_trend_filter_blocks_downtrend() {
        let mut config = test_config();
        config.enable_trend_filter = true;
        let strategy = TradingStrategy::new(config);
        
        // Good dip setup but in downtrend (price < 6h avg)
        let analysis = strategy.analyze("BTC-USD", 50000.0, -0.5, 52000.0, 49000.0, false, 100.0);
        
        // Should be rejected due to downtrend
        assert_eq!(analysis.signal, TradingSignal::Hold);
        assert!(analysis.rejection_reason.is_some());
        assert!(analysis.rejection_reason.expect("Should have rejection reason").contains("Downtrend"));
    }
    
    #[test]
    fn test_volume_filter_blocks_low_volume() {
        let mut config = test_config();
        config.enable_volume_filter = true;
        config.min_volume_usd = 1_000_000.0;
        let strategy = TradingStrategy::new(config);
        
        // Good dip setup but low volume (500k < 1M min)
        // volume_24h param is in base units, gets multiplied by price
        let analysis = strategy.analyze("BTC-USD", 50000.0, -0.5, 52000.0, 49000.0, true, 10.0);
        // 10 * 50000 = 500,000 USD volume
        
        // Should be rejected due to low volume
        assert_eq!(analysis.signal, TradingSignal::Hold);
        assert!(analysis.rejection_reason.is_some());
        assert!(analysis.rejection_reason.expect("Should have rejection reason").contains("Low volume"));
    }
    
    #[test]
    fn test_time_based_exit() {
        let strategy = TradingStrategy::new(test_config());
        
        // Position opened 13 hours ago (> 12h max)
        let old_time = chrono::Utc::now() - chrono::Duration::hours(13);
        let position = Position {
            symbol: "BTC-USD".to_string(),
            quantity: 0.001,
            entry_price: 50000.0,
            entry_time: old_time.to_rfc3339(),
            high_water_mark: None,
            stop_loss_price: None,
            take_profit_price: None,
            entry_volatility: None,
        };
        
        // Price hasn't moved much (no TP/SL hit)
        let exit = strategy.check_exit(&position, 50100.0);
        assert_eq!(exit, Some(ExitReason::TimeExpired));
    }
    
    #[test]
    fn test_time_exit_not_triggered_before_limit() {
        let strategy = TradingStrategy::new(test_config());
        
        // Position opened 6 hours ago (< 12h max)
        let recent_time = chrono::Utc::now() - chrono::Duration::hours(6);
        let position = Position {
            symbol: "BTC-USD".to_string(),
            quantity: 0.001,
            entry_price: 50000.0,
            entry_time: recent_time.to_rfc3339(),
            high_water_mark: None,
            stop_loss_price: None,
            take_profit_price: None,
            entry_volatility: None,
        };
        
        // Price hasn't moved much (no TP/SL hit)
        let exit = strategy.check_exit(&position, 50100.0);
        assert_eq!(exit, None);  // No exit yet
    }
}
