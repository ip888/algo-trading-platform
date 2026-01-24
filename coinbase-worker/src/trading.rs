//! Trading engine - orchestrates the trading cycle
//!
//! Coordinates strategy, API client, and state management.

use crate::client::CoinbaseClient;
use crate::config::Config;
use crate::error::Result;
use crate::strategy::TradingStrategy;
use crate::types::{TradingStateData, TradingCycleResult, Position, Trade, OrderSide, PositionsResponse, PositionWithPnl, StatusResponse};
use chrono::Utc;

/// Trading engine coordinating all components
pub struct TradingEngine {
    client: CoinbaseClient,
    strategy: TradingStrategy,
    config: Config,
}

impl TradingEngine {
    /// Create new trading engine
    pub fn new(client: CoinbaseClient, config: Config) -> Self {
        let strategy = TradingStrategy::new(config.clone());
        Self {
            client,
            strategy,
            config,
        }
    }
    
    /// Run a complete trading cycle
    /// 
    /// This is called on each scheduled trigger (every 15 seconds by default).
    /// Returns a result with cycle details for logging.
    pub async fn run_cycle(&self, state: &mut TradingStateData) -> Result<TradingCycleResult> {
        let start_time = Utc::now().timestamp_millis();
        let mut result = TradingCycleResult::default();
        
        // Skip if trading disabled or paused due to errors
        if !state.enabled {
            result.message = "Trading disabled".to_string();
            return Ok(result);
        }
        
        if state.should_pause(self.config.max_consecutive_errors) {
            result.message = format!("Paused: {} consecutive errors", state.consecutive_errors);
            result.success = false;
            return Ok(result);
        }
        
        // Check daily trade limit
        let today = Utc::now().format("%Y-%m-%d").to_string();
        if state.daily_trades >= self.config.daily_trade_limit
            && state.last_trade_day.as_deref() == Some(today.as_str()) {
                result.message = "Daily trade limit reached".to_string();
                return Ok(result);
            }
        
        // Process existing positions first (check for exits)
        let positions = state.positions.clone();
        for position in positions {
            match self.process_position(&position, state, &mut result).await {
                Ok(()) => {}
                Err(e) => {
                    worker::console_warn!("Error processing position {}: {}", position.symbol, e);
                    // Continue with other positions, don't fail entire cycle
                }
            }
        }
        
        // Look for new entry opportunities (position cap is handled dynamically in scan_for_entries)
        match self.scan_for_entries(state, &mut result).await {
            Ok(()) => {}
            Err(e) => {
                worker::console_warn!("Error scanning for entries: {}", e);
            }
        }
        
        // Update state
        state.last_cycle_time = Some(Utc::now().to_rfc3339());
        state.record_success();
        
        let end_time = Utc::now().timestamp_millis();
        result.cycle_time_ms = (end_time - start_time).max(0) as u64;
        result.message = format!(
            "Cycle complete: {} positions, {} opened, {} closed",
            state.positions.len(),
            result.positions_opened,
            result.positions_closed
        );
        
        Ok(result)
    }
    
    /// Process an existing position - check for exits, update trailing stop
    async fn process_position(
        &self,
        position: &Position,
        state: &mut TradingStateData,
        result: &mut TradingCycleResult,
    ) -> Result<()> {
        // Get current price
        let current_price = self.client.get_price(&position.symbol).await?;
        
        // Update high water mark
        if let Some(pos) = state.get_position_mut(&position.symbol) {
            pos.update_high_water_mark(current_price);
        }
        
        // Check for exit signals
        if let Some(exit_reason) = self.strategy.check_exit(position, current_price) {
            // Close position
            let order = self.client.market_sell(&position.symbol, position.quantity).await?;
            
            let pnl = position.unrealized_pnl(current_price);
            
            let trade = Trade {
                id: order.order_id.unwrap_or_default(),
                symbol: position.symbol.clone(),
                side: OrderSide::Sell,
                quantity: position.quantity,
                price: current_price,
                total_value: current_price * position.quantity,
                timestamp: Utc::now().to_rfc3339(),
                pnl: Some(pnl),
            };
            
            // Update state
            state.remove_position(&position.symbol);
            state.total_trades += 1;
            state.total_pnl += pnl;
            state.increment_daily_trades(&Utc::now().format("%Y-%m-%d").to_string());
            
            result.positions_closed += 1;
            result.trades.push(trade);
            
            worker::console_log!(
                "Closed {} position: {} @ {} ({}) P&L: ${:.2}",
                position.symbol,
                position.quantity,
                current_price,
                exit_reason,
                pnl
            );
        }
        
        Ok(())
    }
    
    /// Scan symbols for new entry opportunities
    async fn scan_for_entries(
        &self,
        state: &mut TradingStateData,
        result: &mut TradingCycleResult,
    ) -> Result<()> {
        // Get available balance (USD + USDC, both count as cash)
        let accounts = self.client.get_accounts().await?;
        let usd_balance: f64 = accounts.accounts
            .iter()
            .filter(|a| a.currency == "USD" || a.currency == "USDC")
            .filter_map(|a| a.available_balance.value.parse::<f64>().ok())
            .sum();
        
        // Calculate total portfolio value (USD + positions value)
        let mut positions_value = 0.0;
        for pos in &state.positions {
            if let Ok(price) = self.client.get_price(&pos.symbol).await {
                positions_value += pos.quantity * price;
            }
        }
        let total_portfolio = usd_balance + positions_value;
        
        worker::console_log!("Portfolio: ${:.2} (${:.2} USD + ${:.2} positions)", 
            total_portfolio, usd_balance, positions_value);
        
        // Check if we can open more positions
        let max_new = self.strategy.max_new_positions(total_portfolio, state.positions.len());
        if max_new == 0 {
            worker::console_log!("At max positions ({}/{})", 
                state.positions.len(), self.config.max_total_positions);
            return Ok(());
        }
        
        // Scan each configured symbol
        for symbol in &self.config.symbols {
            // Skip if already have position
            if state.get_position(symbol).is_some() {
                continue;
            }
            
            // Get real product stats with 24h high/low
            let stats = match self.client.get_product_stats(symbol).await {
                Ok(s) => s,
                Err(e) => {
                    worker::console_warn!("Failed to get stats for {}: {}", symbol, e);
                    continue;
                }
            };
            
            let analysis = self.strategy.analyze(
                symbol, 
                stats.price, 
                stats.change_24h, 
                stats.high_24h, 
                stats.low_24h,
                stats.is_uptrend,
                stats.volume_24h,
            );
            
            // Log rejection reason for debugging
            if let Some(reason) = &analysis.rejection_reason {
                worker::console_log!("{}: Skipped - {}", symbol, reason);
            }
            
            if self.strategy.should_enter(&analysis, state.positions.len(), total_portfolio) {
                // Calculate volatility factor from 24h range
                let range_percent = ((stats.high_24h - stats.low_24h) / stats.low_24h) * 100.0;
                let volatility_factor = (range_percent / 3.0).max(0.5).min(2.0);  // Normalize around 3% range
                
                // Calculate dynamic position size
                let sizing = self.strategy.calculate_position_size(total_portfolio, usd_balance, volatility_factor);
                
                if !sizing.can_trade {
                    worker::console_log!("{}: Can't trade - {}", symbol, sizing.reason.unwrap_or_default());
                    continue;
                }
                
                let position_size = sizing.size;
                let quantity = position_size / stats.price;
                
                // Calculate dynamic TP/SL based on current volatility
                let (stop_loss_price, take_profit_price, sl_pct, tp_pct) = 
                    self.strategy.calculate_dynamic_tp_sl(stats.price, range_percent);
                
                worker::console_log!("{}: Opening ${:.2} position | SL: ${:.2} (-{:.1}%) | TP: ${:.2} (+{:.1}%)",
                    symbol, position_size, stop_loss_price, sl_pct, take_profit_price, tp_pct);
                
                // Place buy order
                let order = match self.client.market_buy(symbol, position_size).await {
                    Ok(o) => o,
                    Err(e) => {
                        worker::console_warn!("Failed to buy {}: {}", symbol, e);
                        continue;
                    }
                };
                
                // Create position with dynamic TP/SL
                let position = Position {
                    symbol: symbol.clone(),
                    quantity,
                    entry_price: stats.price,
                    entry_time: Utc::now().to_rfc3339(),
                    high_water_mark: None,
                    stop_loss_price: Some(stop_loss_price),
                    take_profit_price: Some(take_profit_price),
                    entry_volatility: Some(range_percent),
                };
                
                let trade = Trade {
                    id: order.order_id.unwrap_or_default(),
                    symbol: symbol.clone(),
                    side: OrderSide::Buy,
                    quantity,
                    price: stats.price,
                    total_value: position_size,
                    timestamp: Utc::now().to_rfc3339(),
                    pnl: None,
                };
                
                // Update state
                state.add_position(position);
                state.total_trades += 1;
                state.increment_daily_trades(&Utc::now().format("%Y-%m-%d").to_string());
                
                result.positions_opened += 1;
                result.trades.push(trade);
                
                worker::console_log!(
                    "Opened {} position: {} @ {} (confidence: {:.0}%)",
                    symbol,
                    quantity,
                    stats.price,
                    analysis.confidence * 100.0
                );
            }
        }
        
        Ok(())
    }
    
    /// Get current positions with P&L info
    pub async fn get_positions_with_pnl(&self, state: &TradingStateData) -> Result<PositionsResponse> {
        let mut positions_with_pnl = Vec::new();
        let mut total_value = 0.0;
        let mut total_pnl = 0.0;
        
        for position in &state.positions {
            let current_price = self.client.get_price(&position.symbol).await
                .unwrap_or(position.entry_price);
            
            let pnl = position.unrealized_pnl(current_price);
            let pnl_percent = position.unrealized_pnl_percent(current_price);
            let value = current_price * position.quantity;
            
            positions_with_pnl.push(PositionWithPnl {
                symbol: position.symbol.clone(),
                quantity: position.quantity,
                entry_price: position.entry_price,
                current_price,
                unrealized_pnl: pnl,
                unrealized_pnl_percent: pnl_percent,
            });
            
            total_value += value;
            total_pnl += pnl;
        }
        
        Ok(PositionsResponse {
            positions: positions_with_pnl,
            total_value,
            total_pnl,
        })
    }
    
    /// Get trading status
    pub fn get_status(&self, state: &TradingStateData) -> StatusResponse {
        StatusResponse {
            enabled: state.enabled,
            positions_count: state.positions.len(),
            total_trades: state.total_trades,
            total_pnl: state.total_pnl,
            daily_trades: state.daily_trades,
            consecutive_errors: state.consecutive_errors,
            last_cycle: state.last_cycle_time.clone(),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    
    // Integration tests would go here with mocked client
    // For now, unit tests cover strategy and types modules
}
