use worker::*;
use serde::{Deserialize, Serialize};

mod utils;
mod d1;
mod alpaca;

#[derive(Serialize, Deserialize, Debug)]
pub struct MarketPacket {
    pub symbol: String,
    pub price: f64,
    pub volume: u64,
    pub spread_percent: f64,
    pub vix_level: f64,
}

#[derive(Serialize, Deserialize)]
pub struct FilterResult {
    pub allow_execution: bool,
    pub reason: String,
    pub score: u32,
}

#[event(fetch)]
pub async fn main(mut req: Request, env: Env, _ctx: worker::Context) -> Result<Response> {
    utils::set_panic_hook();
    
    let path = req.path();
    let method = req.method();
    
    // 0. Global Status Page (Mobile Friendly)
    if path == "/status" && method == Method::Get {
        let last_beat = d1::get_last_heartbeat(&env).await?.unwrap_or(0);
        let now = Date::now().as_millis() as u64;
        let diff = now.saturating_sub(last_beat);
        
        let status_color = if diff < 120_000 { "#00ff88" } else { "#ff4444" };
        let status_text = if diff < 120_000 { "ONLINE" } else { "OFFLINE" };
        let diff_str = format!("{}s ago", diff / 1000);

        let html = format!(r#"
            <!DOCTYPE html>
            <html>
            <head>
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <style>
                    body {{ background: #0a0a0a; color: white; font-family: -apple-system, sans-serif; display: flex; align-items: center; justify-content: center; height: 100vh; margin: 0; }}
                    .card {{ background: #1a1a1a; padding: 2rem; border-radius: 20px; border: 1px solid #333; box-shadow: 0 10px 30px rgba(0,0,0,0.5); text-align: center; width: 80%; max-width: 400px; }}
                    .status {{ font-size: 3rem; font-weight: bold; color: {status_color}; text-shadow: 0 0 20px {status_color}; margin: 1rem 0; }}
                    .label {{ color: #888; text-transform: uppercase; letter-spacing: 2px; font-size: 0.8rem; }}
                    .metric {{ font-size: 1.2rem; margin-top: 1rem; color: #ccc; }}
                    .dot {{ height: 10px; width: 10px; background-color: {status_color}; border-radius: 50%; display: inline-block; margin-right: 10px; }}
                </style>
            </head>
            <body>
                <div class="card">
                    <div class="label">System Cortex Status</div>
                    <div class="status">{status_text}</div>
                    <div class="metric"><span class="dot"></span>Java Core Heartbeat: {diff_str}</div>
                    <div class="metric" style="font-size: 0.9rem; color: #555; margin-top: 2rem;">Tech Excellence v5.0</div>
                </div>
            </body>
            </html>
        "#, status_color=status_color, status_text=status_text, diff_str=diff_str);

        let mut headers = Headers::new();
        headers.set("Content-Type", "text/html")?;
        return Ok(Response::from_html(html)?);
    }

    // 1. Heartbeat Endpoint
    if path == "/heartbeat" && method == Method::Post {
        let timestamp = Date::now().as_millis() as u64;
        d1::save_heartbeat(&env, timestamp, "java-core").await?;
        return Response::ok("Heartbeat Captured ❤️");
    }

    // 2. Cortex Proxy Endpoint
    if path == "/cortex" && method == Method::Post {
        let packet: MarketPacket = req.json().await?;
        console_log!("🧠 Cortex Received Packet: {:?}", packet);

        // Edge Filtering (WASM Speed)
        let (allow, reason, score) = if packet.vix_level > 35.0 {
            (false, "VIX too high - Chaos Protected", 0)
        } else if packet.spread_percent > 0.5 {
            (false, "Spread too wide - Liquidity Protected", 10)
        } else {
            (true, "Signal Passed Edge Filter", 95)
        };

        if allow {
            console_log!("✅ Cortex ALLOWED signal for {}. Score: {}", packet.symbol, score);
            
            // Proxy to Java Core
            let core_url = env.secret("JAVA_CORE_URL")?.to_string();
            let url = format!("{}/analyze", core_url);
            
            let client = reqwest::Client::new();
            let core_res = client.post(&url)
                .json(&packet)
                .send()
                .await
                .map_err(|e| Error::RustError(format!("Core Proxy Error: {}", e)))?;

            let core_json: serde_json::Value = core_res.json().await
                .map_err(|e| Error::RustError(format!("Core JSON Error: {}", e)))?;

            return Response::from_json(&serde_json::json!({
                "decision": "ALLOWED",
                "edge_score": score,
                "edge_reason": reason,
                "core_analysis": core_json
            }));
        } else {
            console_warn!("🚫 Cortex REJECTED signal for {}. Reason: {}", packet.symbol, reason);
            return Response::from_json(&serde_json::json!({
                "decision": "REJECTED",
                "edge_reason": reason,
                "edge_score": score
            }));
        }
    }

    Response::ok("Alpaca Bot Cortex/Watchdog - Online")
}

#[event(scheduled)]
pub async fn cron(_event: ScheduledEvent, env: Env, _ctx: ScheduleContext) {
    utils::set_panic_hook();
    console_log!("⏱️ Watchdog Scheduler Triggered");

    match check_health(&env).await {
        Ok(_) => console_log!("✅ Health Check Passed"),
        Err(e) => console_error!("❌ Health Check Failed/Error: {}", e),
    }
}

async fn check_health(env: &Env) -> Result<()> {
    // 1. Get last heartbeat from D1
    let last_beat_opt = d1::get_last_heartbeat(env).await?;
    
    let now = Date::now().as_millis() as u64;
    let threshold_ms: u64 = 180_000; // 3 minutes - Efficiency First Strategy

    match last_beat_opt {
        Some(last_timestamp) => {
            let elapsed = now.saturating_sub(last_timestamp);
            
            if elapsed > threshold_ms {
                console_error!("💀 DEAD MAN'S SWITCH TRIGGERED! Last beat: {}ms ago", elapsed);
                
                // 2. Trigger Emergency Protocol
                let alpaca = alpaca::AlpacaClient::new(env)?;
                alpaca.close_all_positions().await?;
                
            } else {
                console_log!("❤️ System Alive. Last beat: {}ms ago", elapsed);
            }
        },
        None => {
            console_warn!("⚠️ No heartbeats found in DB. System might be initializing.");
        }
    }

    Ok(())
}
