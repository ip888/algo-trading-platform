use worker::*;
use reqwest::{Client, Method};
use serde_json::Value;

pub struct AlpacaClient {
    base_url: String,
    key_id: String,
    secret: String,
    client: Client,
}

impl AlpacaClient {
    pub fn new(env: &Env) -> Result<Self> {
        let key_id = env.secret("APCA_API_KEY_ID")?.to_string();
        let secret = env.secret("APCA_API_SECRET_KEY")?.to_string();
        let base_url = env.var("APCA_API_BASE_URL")?.to_string();

        Ok(Self {
            base_url,
            key_id,
            secret,
            client: Client::new(),
        })
    }

    pub async fn close_all_positions(&self) -> Result<()> {
        let url = format!("{}/v2/positions", self.base_url);
        
        console_log!("🚨 EMERGENCY: Attempting to FLATTEN all positions via {}", url);

        let response = self.client
            .request(Method::DELETE, &url)
            .header("APCA-API-KEY-ID", &self.key_id)
            .header("APCA-API-SECRET-KEY", &self.secret)
            .send()
            .await
            .map_err(|e| Error::RustError(format!("Reqwest error: {}", e)))?;

        if response.status().is_success() {
            console_log!("✅ EMERGENCY FLATTEN SUCCESSFUL");
            Ok(())
        } else {
            let status = response.status();
            let text = response.text().await.unwrap_or_default();
            console_error!("❌ FAILED TO FLATTEN: {} - {}", status, text);
            Err(Error::RustError(format!("Alpaca API failed: {}", text)))
        }
    }
}
