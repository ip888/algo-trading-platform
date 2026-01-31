//! Coinbase Worker - 24/7 Crypto Trading Bot for Cloudflare Workers
//!
//! A production-grade crypto trading bot running on Cloudflare Workers.
//!
//! # Architecture
//! - Main entry point handles HTTP requests and scheduled triggers
//! - KV storage for persistent trading state  
//! - Coinbase API client for trading
//!
//! # Features
//! - Mean reversion strategy with trend/volume filters
//! - Volatility-adaptive TP/SL (ATR-based)
//! - Risk-based position sizing
//! - Web dashboard for monitoring
//!
//! # Cost
//! ~$5/month on Cloudflare Workers paid plan

// Clippy configuration for trading code patterns
#![allow(clippy::similar_names)] // state/stats are common trading names
#![allow(clippy::too_many_arguments)] // Trading functions need many params
#![allow(clippy::cast_precision_loss)] // Float casts OK for display
#![allow(clippy::cast_possible_truncation)]
#![allow(clippy::cast_sign_loss)]
#![allow(clippy::too_many_lines)] // Complex trading logic functions
#![allow(clippy::doc_markdown)] // Doc style flexibility
#![allow(clippy::needless_pass_by_value)] // Worker framework patterns
#![allow(clippy::if_not_else)] // Readability preference
#![allow(clippy::map_unwrap_or)] // Explicit error handling preference
#![allow(clippy::manual_clamp)] // Explicit NaN handling in trading code

mod auth;
mod capital_tier;
mod client;
mod config;
mod dashboard;
mod error;
mod strategy;
mod trading;
mod types;

use worker::{
    Context, Env, Request, Response, Router, ScheduleContext, ScheduledEvent, console_log, event,
};

pub use auth::CoinbaseAuth;
pub use capital_tier::{CapitalTier, FeeTier, TierParameters};
pub use client::CoinbaseClient;
pub use config::Config;
pub use error::TradingError;
pub use strategy::TradingStrategy;
pub use trading::TradingEngine;
pub use types::*;

/// Result type alias for worker operations
type WResult<T> = std::result::Result<T, worker::Error>;

const STATE_KEY: &str = "trading_state";

/// Main Worker entry point
#[event(fetch)]
async fn fetch(req: Request, env: Env, _ctx: Context) -> WResult<Response> {
    console_error_panic_hook::set_once();

    let router = Router::new();

    router
        // Health check
        .get_async("/health", |_req, ctx| async move {
            let config = match Config::from_env(&ctx.env) {
                Ok(c) => c,
                Err(e) => return Response::error(format!("Config error: {e}"), 500),
            };

            Response::from_json(&serde_json::json!({
                "status": "healthy",
                "version": env!("CARGO_PKG_VERSION"),
                "environment": config.environment,
                "timestamp": chrono::Utc::now().to_rfc3339(),
            }))
        })
        // Dashboard UI
        .get("/", |_req, _ctx| {
            Response::from_html(dashboard::dashboard_html())
        })
        .get("/dashboard", |_req, _ctx| {
            Response::from_html(dashboard::dashboard_html())
        })
        // Get current positions (raw data)
        .get_async("/api/positions", |_req, ctx| async move {
            let state = get_trading_state(&ctx.env).await?;
            Response::from_json(&state.positions)
        })
        // Get portfolio with live P&L
        .get_async("/api/portfolio", |_req, ctx| async move {
            match get_portfolio_with_pnl(&ctx.env).await {
                Ok(result) => Response::from_json(&result),
                Err(e) => Response::from_json(&serde_json::json!({
                    "error": format!("{e}")
                })),
            }
        })
        // Get trading status
        .get_async("/api/status", |_req, ctx| async move {
            let state = get_trading_state(&ctx.env).await?;
            Response::from_json(&serde_json::json!({
                "enabled": state.enabled,
                "positions": state.positions.len(),
                "last_cycle": state.last_cycle_time,
                "total_trades": state.total_trades,
                "total_pnl": state.total_pnl,
                "consecutive_errors": state.consecutive_errors,
                "daily_trades": state.daily_trades,
            }))
        })
        // Get Coinbase account balance
        .get_async("/api/balance", |_req, ctx| async move {
            let auth = match CoinbaseAuth::from_env(&ctx.env) {
                Ok(a) => a,
                Err(e) => {
                    return Response::from_json(&serde_json::json!({
                        "error": format!("{e}")
                    }));
                }
            };
            let client = CoinbaseClient::new(auth);

            match client.get_accounts().await {
                Ok(accounts) => {
                    let balances: Vec<_> = accounts
                        .accounts
                        .iter()
                        .filter(|a| {
                            let val: f64 = a.available_balance.value.parse().unwrap_or(0.0);
                            val > 0.0
                        })
                        .map(|a| {
                            serde_json::json!({
                                "currency": a.currency,
                                "available": a.available_balance.value,
                                "hold": a.hold.value,
                            })
                        })
                        .collect();
                    Response::from_json(&serde_json::json!({
                        "accounts": balances
                    }))
                }
                Err(e) => Response::from_json(&serde_json::json!({
                    "error": format!("{e}")
                })),
            }
        })
        // Manual trade trigger
        .post_async("/api/trigger", |_req, ctx| async move {
            // Wrap in catch_unwind would be nice, but async closures...
            // Instead, try to return more detailed errors
            let result = run_trading_cycle(&ctx.env).await;
            match result {
                Ok(result) => Response::from_json(&result),
                Err(e) => Response::from_json(&serde_json::json!({
                    "error": true,
                    "message": format!("{e}"),
                    "error_type": format!("{e:?}").split('(').next().unwrap_or("Unknown")
                })),
            }
        })
        // Test auth only (debug endpoint)
        .get_async("/api/test-auth", |_req, ctx| async move {
            // Check if secrets exist
            let key_name = match ctx.env.secret("COINBASE_API_KEY_NAME") {
                Ok(s) => s.to_string(),
                Err(_) => {
                    return Response::from_json(&serde_json::json!({
                        "status": "error",
                        "message": "COINBASE_API_KEY_NAME secret not set"
                    }));
                }
            };

            let private_key = match ctx.env.secret("COINBASE_PRIVATE_KEY") {
                Ok(s) => s.to_string(),
                Err(_) => {
                    return Response::from_json(&serde_json::json!({
                        "status": "error",
                        "message": "COINBASE_PRIVATE_KEY secret not set"
                    }));
                }
            };

            // Check key format
            let key_info = if private_key.contains("BEGIN EC PRIVATE KEY") {
                "SEC1 EC format detected"
            } else if private_key.contains("BEGIN PRIVATE KEY") {
                "PKCS#8 format detected"
            } else {
                "Unknown format"
            };

            match CoinbaseAuth::new(key_name, &private_key) {
                Ok(_) => Response::from_json(&serde_json::json!({
                    "status": "ok",
                    "message": "Authentication configured correctly",
                    "key_format": key_info
                })),
                Err(e) => Response::from_json(&serde_json::json!({
                    "status": "error",
                    "message": format!("{e}"),
                    "key_format": key_info
                })),
            }
        })
        // Enable/disable trading
        .post_async("/api/toggle", |mut req, ctx| async move {
            let mut state = get_trading_state(&ctx.env).await?;

            // If body provided with "enabled", use that; otherwise toggle
            let enabled = match req.json::<serde_json::Value>().await {
                Ok(body) => body
                    .get("enabled")
                    .and_then(serde_json::Value::as_bool)
                    .unwrap_or(!state.enabled),
                Err(_) => !state.enabled, // No body = toggle current state
            };

            state.enabled = enabled;
            save_trading_state(&ctx.env, &state).await?;

            Response::from_json(&serde_json::json!({
                "enabled": enabled,
                "message": if enabled { "Trading enabled" } else { "Trading disabled" }
            }))
        })
        // Debug endpoint - show authenticated stats vs public
        .get_async("/api/debug", |_req, ctx| async move {
            match debug_trading_check(&ctx.env).await {
                Ok(result) => Response::from_json(&result),
                Err(e) => Response::from_json(&serde_json::json!({
                    "error": true,
                    "message": format!("{e}")
                })),
            }
        })
        // Scan all symbols and show market analysis
        .get_async("/api/scan", |_req, ctx| async move {
            match scan_all_symbols(&ctx.env).await {
                Ok(result) => Response::from_json(&result),
                Err(e) => Response::from_json(&serde_json::json!({
                    "error": true,
                    "message": format!("{e}")
                })),
            }
        })
        // Fallback
        .run(req, env)
        .await
}

/// Scheduled trigger (cron job)
#[event(scheduled)]
async fn scheduled(_event: ScheduledEvent, env: Env, _ctx: ScheduleContext) {
    // Run trading cycle on schedule
    if let Err(e) = run_trading_cycle(&env).await {
        console_log!("Trading cycle error: {}", e);
    }
}

/// Execute a trading cycle
async fn run_trading_cycle(env: &Env) -> std::result::Result<TradingCycleResult, TradingError> {
    let config = Config::from_env(env)?;

    // Get current state from KV
    let mut state = get_trading_state(env)
        .await
        .map_err(|e| TradingError::Trading(e.to_string()))?;

    // Check if trading is enabled
    if !state.enabled {
        return Ok(TradingCycleResult {
            success: true,
            message: "Trading disabled".to_string(),
            positions_opened: 0,
            positions_closed: 0,
            trades: vec![],
            cycle_time_ms: 0,
        });
    }

    // Initialize Coinbase client
    let auth = CoinbaseAuth::from_env(env)?;
    let client = CoinbaseClient::new(auth);

    // Initialize trading engine
    let engine = TradingEngine::new(client, config);

    // Run the trading cycle
    let result = engine.run_cycle(&mut state).await?;

    // Save updated state to KV
    save_trading_state(env, &state)
        .await
        .map_err(|e| TradingError::Trading(e.to_string()))?;

    Ok(result)
}

/// Get trading state from KV storage
async fn get_trading_state(env: &Env) -> WResult<TradingStateData> {
    let kv = env.kv("STATE")?;

    match kv.get(STATE_KEY).json::<TradingStateData>().await? {
        Some(state) => Ok(state),
        None => Ok(TradingStateData::default()),
    }
}

/// Save trading state to KV storage
async fn save_trading_state(env: &Env, state: &TradingStateData) -> WResult<()> {
    let kv = env.kv("STATE")?;
    kv.put(STATE_KEY, state)?.execute().await?;
    Ok(())
}

/// Scan all symbols and return market analysis (for /api/scan endpoint)
async fn scan_all_symbols(env: &Env) -> std::result::Result<serde_json::Value, TradingError> {
    let config = Config::from_env(env)?;
    let auth = CoinbaseAuth::from_env(env)?;
    let client = CoinbaseClient::new(auth);
    let strategy = TradingStrategy::new(config.clone());
    let state = get_trading_state(env)
        .await
        .map_err(|e| TradingError::Trading(e.to_string()))?;

    let mut scans = Vec::new();

    for symbol in &config.symbols {
        // Use public endpoint for scan (no auth required)
        let stats = match client.get_product_stats_public(symbol).await {
            Ok(s) => s,
            Err(e) => {
                scans.push(serde_json::json!({
                    "symbol": symbol,
                    "error": format!("{e}")
                }));
                continue;
            }
        };

        let analysis = strategy.analyze(
            symbol,
            stats.price,
            stats.change_24h,
            stats.high_24h,
            stats.low_24h,
            stats.is_uptrend,
            stats.volume_24h,
        );

        let has_position = state.get_position(symbol).is_some();

        scans.push(serde_json::json!({
            "symbol": symbol,
            "price": format!("${:.2}", stats.price),
            "change_24h": format!("{:.2}%", stats.change_24h),
            "range_position": format!("{:.1}%", analysis.range_position),
            "trend": if stats.is_uptrend { "📈 UP" } else { "📉 DOWN" },
            "avg_6h": format!("${:.2}", stats.avg_6h),
            "volume_24h": format!("${:.0}M", stats.volume_24h * stats.price / 1_000_000.0),
            "signal": format!("{:?}", analysis.signal),
            "confidence": format!("{:.0}%", analysis.confidence * 100.0),
            "rejection": analysis.rejection_reason.unwrap_or_else(|| "None".to_string()),
            "has_position": has_position,
        }));
    }

    // Check market regime (BTC trend)
    // Use -1% threshold: allows trading in flat/slightly red markets
    // Only blocks during real dumps (BTC < -1%)
    let btc_stats = client.get_product_stats_public("BTC-USD").await.ok();
    let market_regime = btc_stats
        .as_ref()
        .map(|s| {
            if s.change_24h >= -1.0 {
                "BULLISH"
            } else {
                "BEARISH"
            }
        })
        .unwrap_or("UNKNOWN");
    let btc_change = btc_stats.as_ref().map(|s| s.change_24h).unwrap_or(0.0);

    Ok(serde_json::json!({
        "timestamp": chrono::Utc::now().to_rfc3339(),
        "market_regime": {
            "status": market_regime,
            "btc_24h_change": format!("{:.2}%", btc_change),
            "can_open_new": market_regime == "BULLISH" || !config.enable_market_regime_filter,
        },
        "filters": {
            "trend_filter": config.enable_trend_filter,
            "volume_filter": config.enable_volume_filter,
            "market_regime_filter": config.enable_market_regime_filter,
            "min_volume_usd": format!("${:.0}M", config.min_volume_usd / 1_000_000.0),
            "max_position_age_hours": config.max_position_age_hours,
            "entry_threshold": "25% (stricter)",
        },
        "positions": state.positions.len(),
        "max_positions": config.max_total_positions,
        "symbols": scans,
    }))
}

/// Debug function - show what trading engine sees for AVAX
async fn debug_trading_check(env: &Env) -> std::result::Result<serde_json::Value, TradingError> {
    let config = Config::from_env(env)?;
    let auth = CoinbaseAuth::from_env(env)?;
    let client = CoinbaseClient::new(auth);
    let strategy = TradingStrategy::new(config.clone());
    let state = get_trading_state(env)
        .await
        .map_err(|e| TradingError::Trading(e.to_string()))?;

    // Get accounts to check balance (USD + USDC both count as cash)
    let accounts = client.get_accounts().await?;
    let usd_balance: f64 = accounts
        .accounts
        .iter()
        .filter(|a| a.currency == "USD" || a.currency == "USDC")
        .filter_map(|a| a.available_balance.value.parse::<f64>().ok())
        .sum();

    // Calculate total portfolio
    let mut positions_value = 0.0;
    for pos in &state.positions {
        if let Ok(price) = client.get_price(&pos.symbol).await {
            positions_value += pos.quantity * price;
        }
    }
    let total_portfolio = usd_balance + positions_value;

    // Get capital tier info for adaptive parameters
    let tier_params = capital_tier::TierParameters::for_portfolio(total_portfolio);
    let fee_tier = capital_tier::FeeTier::from_volume(0.0); // Assume low volume for now

    // Check AVAX specifically
    let symbol = "AVAX-USD";
    let stats = client.get_product_stats(symbol).await?;

    // Calculate volatility factor
    let range_percent = ((stats.high_24h - stats.low_24h) / stats.low_24h) * 100.0;
    let volatility_factor = (range_percent / 3.0).max(0.5).min(2.0);

    let sizing = strategy.calculate_position_size(total_portfolio, usd_balance, volatility_factor);

    let analysis = strategy.analyze(
        symbol,
        stats.price,
        stats.change_24h,
        stats.high_24h,
        stats.low_24h,
        stats.is_uptrend,
        stats.volume_24h,
    );

    let should_enter = strategy.should_enter(&analysis, state.positions.len(), total_portfolio);
    let has_position = state.get_position(symbol).is_some();
    let max_new_positions = strategy.max_new_positions(total_portfolio, state.positions.len());

    Ok(serde_json::json!({
        "capital_tier": {
            "tier": tier_params.tier.name(),
            "recommendation": tier_params.recommendation,
            "can_trade": tier_params.can_trade,
            "max_positions": tier_params.max_positions,
            "max_position_percent": format!("{}%", tier_params.max_position_percent),
            "risk_per_trade": format!("{}%", tier_params.risk_per_trade_percent),
            "entry_threshold_multiplier": format!("{:.2}x", tier_params.entry_threshold_multiplier),
        },
        "fee_tier": {
            "taker_fee": format!("{}%", fee_tier.taker_fee_percent),
            "maker_fee": format!("{}%", fee_tier.maker_fee_percent),
            "round_trip": format!("{}%", fee_tier.round_trip_percent()),
            "min_profitable_tp": format!("{}%", fee_tier.min_profitable_tp(1.0)),
        },
        "portfolio": {
            "total_portfolio": format!("${:.2}", total_portfolio),
            "usd_balance": format!("${:.2}", usd_balance),
            "positions_value": format!("${:.2}", positions_value),
            "cash_reserve": format!("${:.2} ({}%)", total_portfolio * config.cash_reserve_percent / 100.0, config.cash_reserve_percent),
        },
        "risk_sizing": {
            "tier_risk_percent": format!("{}%", tier_params.risk_per_trade_percent),
            "risk_amount": format!("${:.2}", total_portfolio * tier_params.risk_per_trade_percent / 100.0),
            "stop_loss": format!("{}%", config.stop_loss_percent),
            "risk_based_size": format!("${:.2}", sizing.risk_based),
            "volatility_factor": format!("{:.2}x", volatility_factor),
            "volatility_adjusted": format!("${:.2}", sizing.volatility_adjusted),
            "max_per_position": format!("${:.2} ({}%)", sizing.max_per_position, tier_params.max_position_percent),
            "final_size": format!("${:.2}", sizing.size),
            "can_trade": sizing.can_trade,
            "reason": sizing.reason,
        },
        "positions": {
            "current": state.positions.len(),
            "max_new": max_new_positions,
            "tier_cap": tier_params.max_positions,
            "hard_cap": config.max_total_positions,
        },
        "avax_stats": {
            "price": stats.price,
            "change_24h": stats.change_24h,
            "high_24h": stats.high_24h,
            "low_24h": stats.low_24h,
            "range_percent": format!("{:.2}%", range_percent),
            "volume_24h": stats.volume_24h,
            "is_uptrend": stats.is_uptrend,
            "avg_6h": stats.avg_6h,
        },
        "analysis": {
            "signal": format!("{:?}", analysis.signal),
            "confidence": analysis.confidence,
            "range_position": analysis.range_position,
            "rejection_reason": analysis.rejection_reason,
        },
        "decision": {
            "should_enter": should_enter,
            "has_position": has_position,
        },
    }))
}

/// Get portfolio with live P&L for each position
async fn get_portfolio_with_pnl(env: &Env) -> std::result::Result<serde_json::Value, TradingError> {
    let config = Config::from_env(env)?;
    let auth = CoinbaseAuth::from_env(env)?;
    let client = CoinbaseClient::new(auth);
    let state = get_trading_state(env)
        .await
        .map_err(|e| TradingError::Trading(e.to_string()))?;

    let mut positions_with_pnl = Vec::new();
    let mut total_invested = 0.0;
    let mut total_current_value = 0.0;
    let mut total_unrealized_pnl = 0.0;

    for position in &state.positions {
        // Get current price
        let current_price = match client.get_price(&position.symbol).await {
            Ok(p) => p,
            Err(_) => position.entry_price, // fallback
        };

        let entry_value = position.quantity * position.entry_price;
        let current_value = position.quantity * current_price;
        let unrealized_pnl = current_value - entry_value;
        let pnl_percent = (current_price - position.entry_price) / position.entry_price * 100.0;

        // Calculate time held
        let entry_time = chrono::DateTime::parse_from_rfc3339(&position.entry_time)
            .map(|dt| dt.with_timezone(&chrono::Utc))
            .unwrap_or_else(|_| chrono::Utc::now());
        let duration = chrono::Utc::now() - entry_time;
        let hours_held = duration.num_minutes() as f64 / 60.0;

        // Exit targets - use position-specific if available, else config defaults
        let (take_profit_price, tp_percent) = if let Some(tp) = position.take_profit_price {
            let pct = (tp / position.entry_price - 1.0) * 100.0;
            (tp, pct)
        } else {
            let tp = position.entry_price * (1.0 + config.take_profit_percent / 100.0);
            (tp, config.take_profit_percent)
        };

        let (stop_loss_price, sl_percent) = if let Some(sl) = position.stop_loss_price {
            let pct = (1.0 - sl / position.entry_price) * 100.0;
            (sl, pct)
        } else {
            let sl = position.entry_price * (1.0 - config.stop_loss_percent / 100.0);
            (sl, config.stop_loss_percent)
        };

        total_invested += entry_value;
        total_current_value += current_value;
        total_unrealized_pnl += unrealized_pnl;

        positions_with_pnl.push(serde_json::json!({
            "symbol": position.symbol,
            "quantity": format!("{:.4}", position.quantity),
            "entry_price": format!("${:.4}", position.entry_price),
            "current_price": format!("${:.4}", current_price),
            "entry_value": format!("${:.2}", entry_value),
            "current_value": format!("${:.2}", current_value),
            "unrealized_pnl": format!("{}{:.2}", if unrealized_pnl >= 0.0 { "+$" } else { "-$" }, unrealized_pnl.abs()),
            "pnl_percent": format!("{}{:.2}%", if pnl_percent >= 0.0 { "+" } else { "" }, pnl_percent),
            "status": if pnl_percent > 0.0 { "🟢 PROFIT" } else if pnl_percent < -0.5 { "🔴 LOSS" } else { "⚪ FLAT" },
            "hours_held": format!("{:.1}h", hours_held),
            "max_hold": format!("{}h", config.max_position_age_hours),
            "take_profit": format!("${:.4} (+{:.1}%)", take_profit_price, tp_percent),
            "stop_loss": format!("${:.4} (-{:.1}%)", stop_loss_price, sl_percent),
            "high_water_mark": position.high_water_mark.map(|h| format!("${h:.4}")),
            "volatility": position.entry_volatility.map(|v| format!("{v:.1}%")),
        }));
    }

    // Get USD + USDC balance (both count as available cash)
    let usd_balance = match client.get_accounts().await {
        Ok(accounts) => accounts
            .accounts
            .iter()
            .filter(|a| a.currency == "USD" || a.currency == "USDC")
            .filter_map(|a| a.available_balance.value.parse::<f64>().ok())
            .sum(),
        Err(_) => 0.0,
    };

    let total_portfolio = usd_balance + total_current_value;
    let total_pnl_percent = if total_invested > 0.0 {
        (total_unrealized_pnl / total_invested) * 100.0
    } else {
        0.0
    };

    Ok(serde_json::json!({
        "timestamp": chrono::Utc::now().to_rfc3339(),
        "summary": {
            "usd_balance": format!("${:.2}", usd_balance),
            "positions_value": format!("${:.2}", total_current_value),
            "total_portfolio": format!("${:.2}", total_portfolio),
            "total_invested": format!("${:.2}", total_invested),
            "unrealized_pnl": format!("{}{:.2}", if total_unrealized_pnl >= 0.0 { "+$" } else { "-$" }, total_unrealized_pnl.abs()),
            "pnl_percent": format!("{}{:.2}%", if total_pnl_percent >= 0.0 { "+" } else { "" }, total_pnl_percent),
            "realized_pnl": format!("${:.2}", state.total_pnl),
            "total_trades": state.total_trades,
        },
        "positions": positions_with_pnl,
    }))
}
