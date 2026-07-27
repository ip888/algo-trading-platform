#!/bin/sh
# Launch ML sidecar in background, then start the Java trading bot.
# The sidecar is best-effort — Java bot starts regardless of sidecar status.
TRADING_DB_PATH="${TRADING_DB_PATH:-/app/data/trading_bot.db}" \
  /app/sidecar/.venv/bin/python /app/sidecar/regime_classifier.py &

exec java -jar /app/app.jar
