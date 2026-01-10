-- D1 Schema for Alpaca Bot Heartbeats
DROP TABLE IF EXISTS heartbeats;
CREATE TABLE heartbeats (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    source TEXT NOT NULL,      -- e.g., 'java-core'
    timestamp INTEGER NOT NULL -- Unix timestamp (ms)
);

CREATE INDEX idx_heartbeats_timestamp ON heartbeats(timestamp);
