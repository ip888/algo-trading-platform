use worker::*;
use serde::{Deserialize, Serialize};

#[derive(Debug, Serialize, Deserialize)]
pub struct Heartbeat {
    pub timestamp: u64,
}

pub async fn get_last_heartbeat(env: &Env) -> Result<Option<u64>> {
    let d1 = env.d1("DB")?;
    // Only select the column we need to avoid mapping issues
    let statement = d1.prepare("SELECT timestamp FROM heartbeats ORDER BY timestamp DESC LIMIT 1");
    let result = statement.first::<Heartbeat>(None).await?;
    
    match result {
        Some(heartbeat) => Ok(Some(heartbeat.timestamp)),
        None => Ok(None),
    }
}

pub async fn save_heartbeat(env: &Env, timestamp: u64, component: &str) -> Result<()> {
    let d1 = env.d1("DB")?;
    // Align with schema.sql: (source, timestamp)
    let statement = d1.prepare("INSERT INTO heartbeats (source, timestamp) VALUES (?, ?)");
    statement.bind(&[component.into(), timestamp.into()])?.run().await?;
    Ok(())
}
