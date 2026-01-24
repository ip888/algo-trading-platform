//! Coinbase Advanced Trade API authentication
//!
//! Uses ES256 (ECDSA with P-256 and SHA-256) for JWT signing.
//! Coinbase API requires JWT tokens for authentication.

use base64::{engine::general_purpose::STANDARD as BASE64, Engine};
use p256::ecdsa::{SigningKey, Signature, signature::Signer};
use p256::pkcs8::DecodePrivateKey;
use p256::SecretKey;
use serde::{Deserialize, Serialize};
use worker::Env;

use crate::error::{Result, TradingError};

/// Coinbase API authentication handler
pub struct CoinbaseAuth {
    /// API Key Name (e.g., "organizations/.../apiKeys/...")
    api_key_name: String,
    
    /// ECDSA signing key
    signing_key: SigningKey,
}

/// JWT claims for Coinbase API
#[derive(Debug, Serialize, Deserialize)]
struct JwtClaims {
    /// Subject (API key name)
    sub: String,
    
    /// Issuer (must be "cdp")
    iss: String,
    
    /// Not before (Unix timestamp)
    nbf: i64,
    
    /// Expiration (Unix timestamp)
    exp: i64,
    
    /// URI (method + path) - optional for websocket
    #[serde(skip_serializing_if = "Option::is_none")]
    uri: Option<String>,
}

impl CoinbaseAuth {
    /// Create auth handler from environment secrets
    pub fn from_env(env: &Env) -> Result<Self> {
        let api_key_name = env.secret("COINBASE_API_KEY_NAME")
            .map_err(|_| TradingError::Auth("COINBASE_API_KEY_NAME secret not found".into()))?
            .to_string();
        
        let private_key_pem = env.secret("COINBASE_PRIVATE_KEY")
            .map_err(|_| TradingError::Auth("COINBASE_PRIVATE_KEY secret not found".into()))?
            .to_string();
        
        Self::new(api_key_name, &private_key_pem)
    }
    
    /// Create new auth handler
    pub fn new(api_key_name: String, private_key_pem: &str) -> Result<Self> {
        // Handle escaped newlines (from environment variables)
        let pem = private_key_pem.replace("\\n", "\n");
        
        // Parse the PEM - try PKCS#8 first, then SEC1/EC format
        let signing_key = if pem.contains("BEGIN PRIVATE KEY") {
            // PKCS#8 format
            SigningKey::from_pkcs8_pem(&pem)
                .map_err(|e| TradingError::Auth(format!("Failed to parse PKCS#8 key: {e}")))?
        } else if pem.contains("BEGIN EC PRIVATE KEY") {
            // SEC1/EC format - parse via SecretKey
            let base64_content: String = pem
                .lines()
                .filter(|line| !line.starts_with("-----"))
                .collect();
            let der = BASE64.decode(&base64_content)
                .map_err(|e| TradingError::Auth(format!("Failed to decode PEM base64: {e}")))?;
            let secret_key = SecretKey::from_sec1_der(&der)
                .map_err(|e| TradingError::Auth(format!("Failed to parse EC key: {e}")))?;
            SigningKey::from(secret_key)
        } else {
            return Err(TradingError::Auth("Unknown private key format".into()));
        };
        
        Ok(Self {
            api_key_name,
            signing_key,
        })
    }
    
    /// Generate JWT token for API request
    pub fn generate_jwt(&self, method: &str, path: &str) -> Result<String> {
        let now = chrono::Utc::now().timestamp();
        
        // URI format: "METHOD api.coinbase.com/path"
        let uri = format!("{method} api.coinbase.com{path}");
        
        let claims = JwtClaims {
            sub: self.api_key_name.clone(),
            iss: "cdp".to_string(),
            nbf: now,
            exp: now + 120, // 2 minute expiration
            uri: Some(uri),
        };
        
        // Create JWT header (kid = api key name, nonce = random hex)
        let nonce = format!("{:032x}", uuid::Uuid::new_v4().as_u128());
        let header = serde_json::json!({
            "alg": "ES256",
            "typ": "JWT",
            "kid": self.api_key_name,
            "nonce": nonce,
        });
        
        // Encode header and payload
        let header_b64 = Self::base64url_encode(&serde_json::to_vec(&header)?);
        let payload_b64 = Self::base64url_encode(&serde_json::to_vec(&claims)?);
        
        let message = format!("{header_b64}.{payload_b64}");
        
        // Sign the message
        let signature: Signature = self.signing_key.sign(message.as_bytes());
        let signature_b64 = Self::base64url_encode(&signature.to_bytes());
        
        Ok(format!("{message}.{signature_b64}"))
    }
    
    /// `Base64URL` encode (no padding, URL-safe characters)
    fn base64url_encode(data: &[u8]) -> String {
        BASE64.encode(data)
            .replace('+', "-")
            .replace('/', "_")
            .replace('=', "")
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    
    #[test]
    fn test_base64url_encode() {
        let data = b"hello world";
        let encoded = CoinbaseAuth::base64url_encode(data);
        assert!(!encoded.contains('+'));
        assert!(!encoded.contains('/'));
        assert!(!encoded.contains('='));
    }
}
