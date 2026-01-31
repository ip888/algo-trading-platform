//! Coinbase Advanced Trade API client
//!
//! Implements the Coinbase API for:
//! - Account balances
//! - Market data (prices, orderbook)
//! - Order placement and management
//!
//! Rate limits: 30 requests/second (generous!)

use serde::{Deserialize, Serialize};

use crate::auth::CoinbaseAuth;
use crate::error::{Result, TradingError};
use crate::types::OrderSide;

const BASE_URL: &str = "https://api.coinbase.com";

/// Coinbase API client
pub struct CoinbaseClient {
    auth: CoinbaseAuth,
}

/// Account response from Coinbase
#[derive(Debug, Deserialize)]
pub struct AccountsResponse {
    pub accounts: Vec<Account>,
}

/// Individual account
#[derive(Debug, Deserialize)]
pub struct Account {
    pub uuid: String,
    pub name: String,
    pub currency: String,
    pub available_balance: Balance,
    pub hold: Balance,
}

/// Balance value
#[derive(Debug, Deserialize)]
pub struct Balance {
    pub value: String,
    pub currency: String,
}

/// Product (trading pair) info from Coinbase API
#[allow(clippy::struct_field_names)] // Matches Coinbase API schema
#[derive(Debug, Deserialize)]
pub struct Product {
    pub product_id: String,
    pub price: String,
    pub price_percentage_change_24h: String,
    pub volume_24h: String,
    pub base_min_size: String,
    pub base_max_size: String,
    pub quote_increment: String,
    pub base_increment: String,
}

/// Product stats with 24h high/low and trend data
#[derive(Debug, Clone)]
pub struct ProductStats {
    pub price: f64,
    pub change_24h: f64,
    pub high_24h: f64,
    pub low_24h: f64,
    pub volume_24h: f64,
    /// Whether price is in an uptrend (above 6h average)
    pub is_uptrend: bool,
    /// 6-hour average price for trend detection
    pub avg_6h: f64,
}

/// Candle data
#[derive(Debug, Deserialize)]
pub struct CandlesResponse {
    pub candles: Vec<Candle>,
}

/// OHLCV candle data from Coinbase API
#[allow(dead_code)] // Fields available for future use
#[derive(Debug, Deserialize)]
pub struct Candle {
    pub start: String,
    pub low: String,
    pub high: String,
    pub open: String,
    pub close: String,
    pub volume: String,
}

/// Order request
#[derive(Debug, Serialize)]
pub struct OrderRequest {
    pub client_order_id: String,
    pub product_id: String,
    pub side: String,
    pub order_configuration: OrderConfiguration,
}

/// Order configuration
#[derive(Debug, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum OrderConfiguration {
    MarketMarketIoc {
        quote_size: Option<String>,
        base_size: Option<String>,
    },
    LimitLimitGtc {
        base_size: String,
        limit_price: String,
        post_only: bool,
    },
}

/// Order response
#[derive(Debug, Deserialize)]
pub struct OrderResponse {
    pub success: bool,
    pub order_id: Option<String>,
    pub error_response: Option<ErrorResponse>,
}

/// Error response
#[derive(Debug, Deserialize)]
pub struct ErrorResponse {
    pub error: String,
    pub message: String,
    pub error_details: Option<String>,
}

impl CoinbaseClient {
    /// Create new client with authentication
    pub fn new(auth: CoinbaseAuth) -> Self {
        Self { auth }
    }

    /// Get all accounts (balances)
    pub async fn get_accounts(&self) -> Result<AccountsResponse> {
        let path = "/api/v3/brokerage/accounts";
        self.get(path).await
    }

    /// Get USD + USDC balance (both count as available cash)
    pub async fn get_usd_balance(&self) -> Result<f64> {
        let response = self.get_accounts().await?;

        let mut total = 0.0;
        for account in response.accounts {
            // Count both USD and USDC as available cash (USDC is 1:1 with USD)
            if account.currency == "USD" || account.currency == "USDC" {
                if let Ok(val) = account.available_balance.value.parse::<f64>() {
                    total += val;
                }
            }
        }

        Ok(total)
    }

    /// Get product info (price, volume, etc.)
    pub async fn get_product(&self, product_id: &str) -> Result<Product> {
        let path = format!("/api/v3/brokerage/products/{product_id}");
        self.get(&path).await
    }

    /// Get product info using public API (no auth required)
    pub async fn get_product_public(&self, product_id: &str) -> Result<Product> {
        let url = format!("https://api.coinbase.com/api/v3/brokerage/market/products/{product_id}");

        let response = reqwest::Client::new()
            .get(&url)
            .header("Content-Type", "application/json")
            .send()
            .await?;

        Self::handle_response(response).await
    }

    /// Get current price for a symbol
    pub async fn get_price(&self, symbol: &str) -> Result<f64> {
        let product = self.get_product(symbol).await?;
        product
            .price
            .parse()
            .map_err(|_| TradingError::CoinbaseApi(format!("Invalid price for {symbol}")))
    }

    /// Get 24h price change percentage
    pub async fn get_price_change_24h(&self, symbol: &str) -> Result<f64> {
        let product = self.get_product(symbol).await?;
        product
            .price_percentage_change_24h
            .parse()
            .map_err(|_| TradingError::CoinbaseApi(format!("Invalid price change for {symbol}")))
    }

    /// Get comprehensive product stats including real 24h high/low and trend
    /// Uses PUBLIC endpoints for market data consistency with /api/scan
    pub async fn get_product_stats(&self, symbol: &str) -> Result<ProductStats> {
        // Use PUBLIC endpoint for product info (same as scan endpoint for consistency)
        let product = self.get_product_public(symbol).await?;
        let price: f64 = product
            .price
            .parse()
            .map_err(|_| TradingError::CoinbaseApi("Invalid price".into()))?;
        let change_24h: f64 = product.price_percentage_change_24h.parse().unwrap_or(0.0);
        let volume_24h: f64 = product.volume_24h.parse().unwrap_or(0.0);

        // Get hourly candles via PUBLIC endpoint (candles don't need auth)
        // Using /market/ path for public access
        let url = format!(
            "https://api.coinbase.com/api/v3/brokerage/market/products/{symbol}/candles?granularity=ONE_HOUR&limit=24"
        );
        let candles: CandlesResponse = match reqwest::Client::new()
            .get(&url)
            .header("Content-Type", "application/json")
            .send()
            .await
        {
            Ok(response) => response
                .json()
                .await
                .unwrap_or(CandlesResponse { candles: vec![] }),
            Err(_) => CandlesResponse { candles: vec![] },
        };

        // Calculate 24h high/low from hourly candles
        let (high_24h, low_24h) = if !candles.candles.is_empty() {
            let mut high = f64::MIN;
            let mut low = f64::MAX;
            for candle in &candles.candles {
                let h: f64 = candle.high.parse().unwrap_or(0.0);
                let l: f64 = candle.low.parse().unwrap_or(f64::MAX);
                if h > high {
                    high = h;
                }
                if l < low {
                    low = l;
                }
            }
            (high, low)
        } else {
            // Fallback: estimate from price change
            (price * 1.02, price * 0.98)
        };

        // Calculate 6h average for trend detection (last 6 candles)
        let avg_6h = if candles.candles.len() >= 6 {
            let sum: f64 = candles
                .candles
                .iter()
                .take(6)
                .filter_map(|c| c.close.parse::<f64>().ok())
                .sum();
            sum / 6.0
        } else {
            price // No trend data, assume neutral
        };

        let is_uptrend = price > avg_6h;

        Ok(ProductStats {
            price,
            change_24h,
            high_24h,
            low_24h,
            volume_24h,
            is_uptrend,
            avg_6h,
        })
    }

    /// Get product stats using public API (no auth required) - for /api/scan
    pub async fn get_product_stats_public(&self, symbol: &str) -> Result<ProductStats> {
        // Get current product info via public endpoint
        let product = self.get_product_public(symbol).await?;
        let price: f64 = product
            .price
            .parse()
            .map_err(|_| TradingError::CoinbaseApi("Invalid price".into()))?;
        let change_24h: f64 = product.price_percentage_change_24h.parse().unwrap_or(0.0);
        let volume_24h: f64 = product.volume_24h.parse().unwrap_or(0.0);

        // Get hourly candles via public endpoint
        let url = format!(
            "https://api.coinbase.com/api/v3/brokerage/market/products/{symbol}/candles?granularity=ONE_HOUR&limit=24"
        );
        let candles: CandlesResponse = match reqwest::Client::new()
            .get(&url)
            .header("Content-Type", "application/json")
            .send()
            .await
        {
            Ok(response) => response
                .json()
                .await
                .unwrap_or(CandlesResponse { candles: vec![] }),
            Err(_) => CandlesResponse { candles: vec![] },
        };

        // Calculate 24h high/low from hourly candles
        let (high_24h, low_24h) = if !candles.candles.is_empty() {
            let mut high = f64::MIN;
            let mut low = f64::MAX;
            for candle in &candles.candles {
                let h: f64 = candle.high.parse().unwrap_or(0.0);
                let l: f64 = candle.low.parse().unwrap_or(f64::MAX);
                if h > high {
                    high = h;
                }
                if l < low {
                    low = l;
                }
            }
            (high, low)
        } else {
            (price * 1.02, price * 0.98)
        };

        // Calculate 6h average for trend detection
        let avg_6h = if candles.candles.len() >= 6 {
            let sum: f64 = candles
                .candles
                .iter()
                .take(6)
                .filter_map(|c| c.close.parse::<f64>().ok())
                .sum();
            sum / 6.0
        } else {
            price
        };

        let is_uptrend = price > avg_6h;

        Ok(ProductStats {
            price,
            change_24h,
            high_24h,
            low_24h,
            volume_24h,
            is_uptrend,
            avg_6h,
        })
    }

    /// Place a market buy order (by quote size in USD)
    pub async fn market_buy(&self, symbol: &str, usd_amount: f64) -> Result<OrderResponse> {
        let order = OrderRequest {
            client_order_id: uuid::Uuid::new_v4().to_string(),
            product_id: symbol.to_string(),
            side: "BUY".to_string(),
            order_configuration: OrderConfiguration::MarketMarketIoc {
                quote_size: Some(format!("{usd_amount:.2}")),
                base_size: None,
            },
        };

        self.place_order(order).await
    }

    /// Place a market sell order (by base size)
    pub async fn market_sell(&self, symbol: &str, quantity: f64) -> Result<OrderResponse> {
        let order = OrderRequest {
            client_order_id: uuid::Uuid::new_v4().to_string(),
            product_id: symbol.to_string(),
            side: "SELL".to_string(),
            order_configuration: OrderConfiguration::MarketMarketIoc {
                quote_size: None,
                base_size: Some(format!("{quantity:.8}")),
            },
        };

        self.place_order(order).await
    }

    /// Place a limit order
    pub async fn limit_order(
        &self,
        symbol: &str,
        side: OrderSide,
        quantity: f64,
        price: f64,
    ) -> Result<OrderResponse> {
        let order = OrderRequest {
            client_order_id: uuid::Uuid::new_v4().to_string(),
            product_id: symbol.to_string(),
            side: match side {
                OrderSide::Buy => "BUY".to_string(),
                OrderSide::Sell => "SELL".to_string(),
            },
            order_configuration: OrderConfiguration::LimitLimitGtc {
                base_size: format!("{quantity:.8}"),
                limit_price: format!("{price:.2}"),
                post_only: false,
            },
        };

        self.place_order(order).await
    }

    /// Place an order
    async fn place_order(&self, order: OrderRequest) -> Result<OrderResponse> {
        let path = "/api/v3/brokerage/orders";
        self.post(path, &order).await
    }

    /// Perform GET request with authentication
    async fn get<T: for<'de> Deserialize<'de>>(&self, path: &str) -> Result<T> {
        let jwt = self.auth.generate_jwt("GET", path)?;
        let url = format!("{BASE_URL}{path}");

        let response = reqwest::Client::new()
            .get(&url)
            .header("Authorization", format!("Bearer {jwt}"))
            .header("Content-Type", "application/json")
            .send()
            .await?;

        Self::handle_response(response).await
    }

    /// Perform POST request with authentication
    async fn post<T: for<'de> Deserialize<'de>, B: Serialize>(
        &self,
        path: &str,
        body: &B,
    ) -> Result<T> {
        let jwt = self.auth.generate_jwt("POST", path)?;
        let url = format!("{BASE_URL}{path}");

        let response = reqwest::Client::new()
            .post(&url)
            .header("Authorization", format!("Bearer {jwt}"))
            .header("Content-Type", "application/json")
            .json(body)
            .send()
            .await?;

        Self::handle_response(response).await
    }

    /// Handle API response, checking for errors
    async fn handle_response<T: for<'de> Deserialize<'de>>(
        response: reqwest::Response,
    ) -> Result<T> {
        let status = response.status();

        if status == 429 {
            // Rate limited
            let retry_after = response
                .headers()
                .get("Retry-After")
                .and_then(|v| v.to_str().ok())
                .and_then(|v| v.parse().ok())
                .unwrap_or(1);
            return Err(TradingError::RateLimit(retry_after));
        }

        if !status.is_success() {
            let error_text = response
                .text()
                .await
                .unwrap_or_else(|_| "Unknown error".into());
            return Err(TradingError::CoinbaseApi(format!(
                "HTTP {status}: {error_text}"
            )));
        }

        response.json().await.map_err(TradingError::from)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_order_request_serialization() {
        let order = OrderRequest {
            client_order_id: "test-123".to_string(),
            product_id: "BTC-USD".to_string(),
            side: "BUY".to_string(),
            order_configuration: OrderConfiguration::MarketMarketIoc {
                quote_size: Some("100.00".to_string()),
                base_size: None,
            },
        };

        let json = serde_json::to_string(&order).expect("Order serialization should succeed");
        assert!(json.contains("BTC-USD"));
        assert!(json.contains("BUY"));
    }
}
