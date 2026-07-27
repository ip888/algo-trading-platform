package com.trading.analysis;

import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

/**
 * HTTP client for the Python regime-classifier sidecar (port 5001).
 *
 * The sidecar runs a RandomForestClassifier trained on the bot's own trade history.
 * When the sidecar's prediction agrees with the rule-based regime and carries high
 * confidence, MarketRegimeDetector can boost its own confidence score.
 * When the sidecar disagrees, the discrepancy is logged but the rule-based regime wins
 * (until the sidecar has accumulated enough in-distribution samples to trust).
 *
 * Returns null if the sidecar is unreachable or returns fallback=true — callers must
 * handle null and keep using the rule-based result unchanged.
 */
public class RegimeClassifierClient {

    private static final Logger logger = LoggerFactory.getLogger(RegimeClassifierClient.class);

    public record SidecarResult(String regime, double confidence, int modelTrades) {}

    private final String baseUrl;
    private final OkHttpClient http;

    public RegimeClassifierClient(String host, int port) {
        this.baseUrl = "http://" + host + ":" + port;
        this.http = new OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build();
    }

    public RegimeClassifierClient() {
        this("localhost", 5001);
    }

    /**
     * Ask the sidecar to classify the current market state.
     *
     * @param vix     current VIX level
     * @param breadth fraction of sectors advancing (0.0–1.0)
     * @return sidecar result, or null if unavailable / fallback mode
     */
    public SidecarResult classify(double vix, double breadth) {
        String body = String.format(
            "{\"vix\":%.2f,\"breadth\":%.4f}", vix, breadth);

        var request = new Request.Builder()
            .url(baseUrl + "/classify")
            .post(RequestBody.create(body, MediaType.get("application/json")))
            .build();

        try (var response = http.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) return null;
            String json = response.body().string();

            // Minimal JSON parsing — no extra dependency
            if (json.contains("\"fallback\":true")) return null;

            String regime = extractString(json, "regime");
            double confidence = extractDouble(json, "confidence");
            int trades = (int) extractDouble(json, "model_trades");

            if (regime == null || regime.isBlank() || "UNKNOWN".equals(regime)) return null;
            return new SidecarResult(regime, confidence, trades);

        } catch (Exception e) {
            logger.debug("Regime sidecar unavailable: {}", e.getMessage());
            return null;
        }
    }

    /** Returns true if the sidecar is up and has a trained model. */
    public boolean isHealthy() {
        try {
            var request = new Request.Builder().url(baseUrl + "/health").get().build();
            try (var response = http.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) return false;
                return response.body().string().contains("\"model_ready\":true");
            }
        } catch (Exception e) {
            return false;
        }
    }

    private static String extractString(String json, String key) {
        String search = "\"" + key + "\":\"";
        int start = json.indexOf(search);
        if (start < 0) return null;
        start += search.length();
        int end = json.indexOf('"', start);
        return end > start ? json.substring(start, end) : null;
    }

    private static double extractDouble(String json, String key) {
        String search = "\"" + key + "\":";
        int start = json.indexOf(search);
        if (start < 0) return 0.0;
        start += search.length();
        int end = start;
        while (end < json.length() && (Character.isDigit(json.charAt(end))
               || json.charAt(end) == '.' || json.charAt(end) == '-')) end++;
        try { return Double.parseDouble(json.substring(start, end)); } catch (NumberFormatException e) { return 0.0; }
    }
}
