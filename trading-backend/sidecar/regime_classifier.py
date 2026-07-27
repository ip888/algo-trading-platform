"""
Regime classifier sidecar — Flask REST service on port 5001.

Receives market features from MarketRegimeDetector.java via HTTP POST,
returns a RandomForestClassifier prediction that can override or confirm
the rule-based regime classification.

The model is trained online from the bot's own trade history stored in
the SQLite database. On startup it loads existing data; it retrains every
RETRAIN_INTERVAL_HOURS hours while running.

Endpoint: POST /classify
Request JSON:
  {
    "vix": 18.5,
    "breadth": 0.42,
    "trend_strength": -0.3,
    "volume_ratio": 1.1,
    "sma50_distance": -0.02
  }
Response JSON:
  {
    "regime": "WEAK_BEAR",
    "confidence": 0.74,
    "model_trades": 120,
    "fallback": false
  }

If the model has fewer than MIN_SAMPLES trades it returns fallback=true
and the Java side ignores the prediction, keeping its rule-based output.
"""

import os
import sqlite3
import threading
import time
import logging
from datetime import datetime, timezone

from flask import Flask, request, jsonify
import numpy as np
from sklearn.ensemble import RandomForestClassifier
from sklearn.preprocessing import LabelEncoder

logging.basicConfig(level=logging.INFO,
                    format="%(asctime)s [sidecar] %(levelname)s %(message)s")
log = logging.getLogger(__name__)

app = Flask(__name__)

DB_PATH = os.getenv("TRADING_DB_PATH", "/app/trading_bot.db")
RETRAIN_INTERVAL_HOURS = int(os.getenv("RETRAIN_INTERVAL_HOURS", "4"))
MIN_SAMPLES = int(os.getenv("REGIME_MIN_SAMPLES", "30"))

# ── Model state (protected by a read-write lock via threading.Lock) ──────────
model_lock = threading.Lock()
clf: RandomForestClassifier | None = None
le: LabelEncoder | None = None
model_trade_count: int = 0
model_trained_at: datetime | None = None


def load_training_data() -> tuple[np.ndarray, np.ndarray, int]:
    """
    Pull closed trades with full context from the SQLite DB.
    Features: vix_at_entry, breadth_at_entry.
    Label: regime.
    Returns (X, y, n_samples).
    """
    try:
        conn = sqlite3.connect(DB_PATH, timeout=10)
        cur = conn.execute("""
            SELECT regime, vix_at_entry, breadth_at_entry, pnl
            FROM trades
            WHERE status = 'CLOSED'
              AND regime IS NOT NULL
              AND vix_at_entry IS NOT NULL
              AND breadth_at_entry IS NOT NULL
        """)
        rows = cur.fetchall()
        conn.close()
    except Exception as e:
        log.warning("DB read failed: %s", e)
        return np.array([]), np.array([]), 0

    if len(rows) < MIN_SAMPLES:
        return np.array([]), np.array([]), len(rows)

    regimes, vix_vals, breadth_vals = [], [], []
    for regime, vix, breadth, _ in rows:
        regimes.append(regime)
        vix_vals.append(vix if vix is not None else 20.0)
        breadth_vals.append(breadth if breadth is not None else 0.5)

    X = np.column_stack([vix_vals, breadth_vals])
    y = np.array(regimes)
    return X, y, len(rows)


def train_model():
    global clf, le, model_trade_count, model_trained_at
    log.info("Training regime classifier...")
    X, y, n = load_training_data()
    if n < MIN_SAMPLES:
        log.info("Not enough samples (%d < %d) — model stays in fallback mode", n, MIN_SAMPLES)
        with model_lock:
            model_trade_count = n
        return

    encoder = LabelEncoder()
    y_enc = encoder.fit_transform(y)

    forest = RandomForestClassifier(
        n_estimators=100,
        max_depth=6,
        min_samples_leaf=3,
        class_weight="balanced",
        random_state=42,
        n_jobs=-1,
    )
    forest.fit(X, y_enc)

    with model_lock:
        clf = forest
        le = encoder
        model_trade_count = n
        model_trained_at = datetime.now(timezone.utc)

    log.info("Model trained on %d trades, classes: %s", n, list(encoder.classes_))


def retrain_loop():
    while True:
        time.sleep(RETRAIN_INTERVAL_HOURS * 3600)
        try:
            train_model()
        except Exception as e:
            log.error("Retrain failed: %s", e)


@app.route("/health", methods=["GET"])
def health():
    with model_lock:
        trained = clf is not None
        n = model_trade_count
    return jsonify({"status": "ok", "model_ready": trained, "model_trades": n})


@app.route("/classify", methods=["POST"])
def classify():
    data = request.get_json(force=True, silent=True) or {}
    vix = float(data.get("vix", 20.0))
    breadth = float(data.get("breadth", 0.5))

    with model_lock:
        current_clf = clf
        current_le = le
        n = model_trade_count

    if current_clf is None or current_le is None:
        return jsonify({
            "regime": "UNKNOWN",
            "confidence": 0.0,
            "model_trades": n,
            "fallback": True,
        })

    features = np.array([[vix, breadth]])
    proba = current_clf.predict_proba(features)[0]
    idx = int(np.argmax(proba))
    confidence = float(proba[idx])
    regime = current_le.inverse_transform([idx])[0]

    return jsonify({
        "regime": regime,
        "confidence": confidence,
        "model_trades": n,
        "fallback": False,
    })


if __name__ == "__main__":
    train_model()
    threading.Thread(target=retrain_loop, daemon=True).start()
    port = int(os.getenv("SIDECAR_PORT", "5001"))
    log.info("Starting regime classifier on port %d (DB: %s)", port, DB_PATH)
    app.run(host="0.0.0.0", port=port, threaded=True)
