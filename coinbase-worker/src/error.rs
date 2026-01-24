//! Error types for the trading bot
//!
//! Uses thiserror for ergonomic error definitions.
//! All errors are non-panicking for production safety.

use thiserror::Error;

/// Custom Result type using our Error
pub type Result<T> = std::result::Result<T, TradingError>;

/// Trading bot errors
#[derive(Error, Debug)]
pub enum TradingError {
    /// Configuration errors
    #[error("Configuration error: {0}")]
    Config(String),
    
    /// Authentication errors
    #[error("Authentication error: {0}")]
    Auth(String),
    
    /// Coinbase API errors
    #[error("Coinbase API error: {0}")]
    CoinbaseApi(String),
    
    /// HTTP request errors
    #[error("HTTP error: {0}")]
    Http(String),
    
    /// JSON parsing errors
    #[error("JSON error: {0}")]
    Json(#[from] serde_json::Error),
    
    /// Trading logic errors
    #[error("Trading error: {0}")]
    Trading(String),
    
    /// Order validation errors
    #[error("Order validation error: {0}")]
    OrderValidation(String),
    
    /// Rate limit exceeded
    #[error("Rate limit exceeded: retry after {0} seconds")]
    RateLimit(u64),
    
    /// Insufficient funds
    #[error("Insufficient funds: required {required}, available {available}")]
    InsufficientFunds { required: f64, available: f64 },
    
    /// Position not found
    #[error("Position not found: {0}")]
    PositionNotFound(String),
    
    /// Worker runtime errors
    #[error("Worker error: {0}")]
    Worker(String),
    
    /// Storage errors
    #[error("Storage error: {0}")]
    Storage(String),
}

impl From<worker::Error> for TradingError {
    fn from(err: worker::Error) -> Self {
        TradingError::Worker(err.to_string())
    }
}

impl From<reqwest::Error> for TradingError {
    fn from(err: reqwest::Error) -> Self {
        TradingError::Http(err.to_string())
    }
}

impl From<TradingError> for worker::Error {
    fn from(err: TradingError) -> Self {
        worker::Error::RustError(err.to_string())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    
    #[test]
    fn test_error_display() {
        let err = TradingError::InsufficientFunds { required: 100.0, available: 50.0 };
        assert!(err.to_string().contains("Insufficient funds"));
    }
    
    #[test]
    fn test_error_conversion() {
        let json_err = serde_json::from_str::<i32>("invalid").unwrap_err();
        let err: TradingError = json_err.into();
        assert!(matches!(err, TradingError::Json(_)));
    }
}
