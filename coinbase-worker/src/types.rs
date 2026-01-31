//! Common types for the trading system
//!
//! All shared data structures used across modules.

use serde::{Deserialize, Serialize};

/// A trading position
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Position {
    pub symbol: String,
    pub quantity: f64,
    pub entry_price: f64,
    pub entry_time: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub high_water_mark: Option<f64>,
    /// Dynamic stop-loss price (ATR-based, set at entry)
    #[serde(default)]
    pub stop_loss_price: Option<f64>,
    /// Dynamic take-profit price (ATR-based, set at entry)
    #[serde(default)]
    pub take_profit_price: Option<f64>,
    /// Volatility (ATR%) at entry time for reference
    #[serde(default)]
    pub entry_volatility: Option<f64>,
}

impl Position {
    /// Calculate unrealized P&L at current price
    pub fn unrealized_pnl(&self, current_price: f64) -> f64 {
        (current_price - self.entry_price) * self.quantity
    }

    /// Calculate unrealized P&L as percentage
    pub fn unrealized_pnl_percent(&self, current_price: f64) -> f64 {
        (current_price - self.entry_price) / self.entry_price * 100.0
    }

    /// Update high water mark for trailing stop
    pub fn update_high_water_mark(&mut self, current_price: f64) {
        match self.high_water_mark {
            Some(hwm) if current_price > hwm => {
                self.high_water_mark = Some(current_price);
            }
            None if current_price > self.entry_price => {
                self.high_water_mark = Some(current_price);
            }
            _ => {}
        }
    }
}

/// Order side (buy or sell)
#[derive(Debug, Clone, Copy, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "UPPERCASE")]
pub enum OrderSide {
    Buy,
    Sell,
}

impl std::fmt::Display for OrderSide {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            OrderSide::Buy => write!(f, "BUY"),
            OrderSide::Sell => write!(f, "SELL"),
        }
    }
}

/// A completed trade
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Trade {
    pub id: String,
    pub symbol: String,
    pub side: OrderSide,
    pub quantity: f64,
    pub price: f64,
    pub total_value: f64,
    pub timestamp: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub pnl: Option<f64>,
}

/// Result of a trading cycle
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TradingCycleResult {
    pub success: bool,
    pub message: String,
    pub positions_opened: usize,
    pub positions_closed: usize,
    pub trades: Vec<Trade>,
    pub cycle_time_ms: u64,
}

impl Default for TradingCycleResult {
    fn default() -> Self {
        Self {
            success: true,
            message: "No action taken".to_string(),
            positions_opened: 0,
            positions_closed: 0,
            trades: vec![],
            cycle_time_ms: 0,
        }
    }
}

/// Persistent trading state stored in Durable Object
#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct TradingStateData {
    /// Trading enabled flag
    pub enabled: bool,

    /// Current open positions
    pub positions: Vec<Position>,

    /// Last trading cycle timestamp
    #[serde(skip_serializing_if = "Option::is_none")]
    pub last_cycle_time: Option<String>,

    /// Total number of trades executed
    pub total_trades: u64,

    /// Total realized P&L (USD)
    pub total_pnl: f64,

    /// Consecutive errors counter
    pub consecutive_errors: u32,

    /// Daily trade count (reset at midnight UTC)
    pub daily_trades: u32,

    /// Day of last trade (for daily reset)
    #[serde(skip_serializing_if = "Option::is_none")]
    pub last_trade_day: Option<String>,
}

impl TradingStateData {
    /// Check if we should pause due to errors
    pub fn should_pause(&self, max_errors: u32) -> bool {
        self.consecutive_errors >= max_errors
    }

    /// Record a successful cycle
    pub fn record_success(&mut self) {
        self.consecutive_errors = 0;
    }

    /// Record an error
    pub fn record_error(&mut self) {
        self.consecutive_errors += 1;
    }

    /// Update daily trade count
    pub fn increment_daily_trades(&mut self, today: &str) {
        if self.last_trade_day.as_deref() != Some(today) {
            self.daily_trades = 0;
            self.last_trade_day = Some(today.to_string());
        }
        self.daily_trades += 1;
    }

    /// Get position by symbol
    pub fn get_position(&self, symbol: &str) -> Option<&Position> {
        self.positions.iter().find(|p| p.symbol == symbol)
    }

    /// Get mutable position by symbol
    pub fn get_position_mut(&mut self, symbol: &str) -> Option<&mut Position> {
        self.positions.iter_mut().find(|p| p.symbol == symbol)
    }

    /// Add a new position
    pub fn add_position(&mut self, position: Position) {
        self.positions.push(position);
    }

    /// Remove a position by symbol
    pub fn remove_position(&mut self, symbol: &str) -> Option<Position> {
        if let Some(idx) = self.positions.iter().position(|p| p.symbol == symbol) {
            Some(self.positions.remove(idx))
        } else {
            None
        }
    }
}

/// API response for positions endpoint
#[derive(Debug, Serialize, Deserialize)]
pub struct PositionsResponse {
    pub positions: Vec<PositionWithPnl>,
    pub total_value: f64,
    pub total_pnl: f64,
}

/// Position with current P&L info
#[derive(Debug, Serialize, Deserialize)]
pub struct PositionWithPnl {
    pub symbol: String,
    pub quantity: f64,
    pub entry_price: f64,
    pub current_price: f64,
    pub unrealized_pnl: f64,
    pub unrealized_pnl_percent: f64,
}

/// API response for status endpoint
#[derive(Debug, Serialize, Deserialize)]
pub struct StatusResponse {
    pub enabled: bool,
    pub positions_count: usize,
    pub total_trades: u64,
    pub total_pnl: f64,
    pub daily_trades: u32,
    pub consecutive_errors: u32,
    pub last_cycle: Option<String>,
}

/// Health check response
#[derive(Debug, Serialize, Deserialize)]
pub struct HealthResponse {
    pub status: String,
    pub version: String,
    pub environment: String,
    pub uptime: String,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_position_pnl() {
        let pos = Position {
            symbol: "BTC-USD".to_string(),
            quantity: 0.001,
            entry_price: 50000.0,
            entry_time: "2024-01-01T00:00:00Z".to_string(),
            high_water_mark: None,
            stop_loss_price: None,
            take_profit_price: None,
            entry_volatility: None,
        };

        // Price up to 51000
        assert!((pos.unrealized_pnl(51000.0) - 1.0).abs() < 0.0001);
        assert!((pos.unrealized_pnl_percent(51000.0) - 2.0).abs() < 0.0001);
    }

    #[test]
    fn test_high_water_mark() {
        let mut pos = Position {
            symbol: "BTC-USD".to_string(),
            quantity: 0.001,
            entry_price: 50000.0,
            entry_time: "2024-01-01T00:00:00Z".to_string(),
            high_water_mark: None,
            stop_loss_price: None,
            take_profit_price: None,
            entry_volatility: None,
        };

        // Below entry - no HWM
        pos.update_high_water_mark(49500.0);
        assert!(pos.high_water_mark.is_none());

        // Above entry - set HWM
        pos.update_high_water_mark(51000.0);
        assert_eq!(pos.high_water_mark, Some(51000.0));

        // New high - update HWM
        pos.update_high_water_mark(52000.0);
        assert_eq!(pos.high_water_mark, Some(52000.0));

        // Pullback - keep HWM
        pos.update_high_water_mark(51500.0);
        assert_eq!(pos.high_water_mark, Some(52000.0));
    }

    #[test]
    fn test_trading_state_positions() {
        let mut state = TradingStateData::default();

        let pos = Position {
            symbol: "BTC-USD".to_string(),
            quantity: 0.001,
            entry_price: 50000.0,
            entry_time: "2024-01-01T00:00:00Z".to_string(),
            high_water_mark: None,
            stop_loss_price: Some(49250.0),
            take_profit_price: Some(51000.0),
            entry_volatility: Some(3.5),
        };

        state.add_position(pos);
        assert_eq!(state.positions.len(), 1);

        assert!(state.get_position("BTC-USD").is_some());
        assert!(state.get_position("ETH-USD").is_none());

        let removed = state.remove_position("BTC-USD");
        assert!(removed.is_some());
        assert!(state.positions.is_empty());
    }

    #[test]
    fn test_error_tracking() {
        let mut state = TradingStateData::default();

        state.record_error();
        state.record_error();
        assert!(!state.should_pause(5)); // 2 errors, threshold 5

        state.record_error();
        state.record_error();
        state.record_error();
        assert!(state.should_pause(5)); // 5 errors, threshold 5

        state.record_success();
        assert!(!state.should_pause(5)); // Reset after success
    }
}
