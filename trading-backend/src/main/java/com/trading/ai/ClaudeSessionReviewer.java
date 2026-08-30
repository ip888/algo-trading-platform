package com.trading.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trading.autonomous.ConfigSelfHealer;
import com.trading.autonomous.ErrorDetector;
import com.trading.persistence.TradeDatabase;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * End-of-session reviewer that sends a daily trading summary to the Claude API and routes
 * the structured config-diff suggestion through {@link ConfigSelfHealer}'s existing
 * backup -&gt; apply -&gt; sandbox-test -&gt; promote/rollback pipeline — the same validated
 * path an exception-triggered self-heal already uses live. This makes the daily review
 * genuinely autonomous rather than a suggestion nobody reads: previously the JSON diff was
 * only written to {@code bot_state} and logged on next startup, with no path to actually
 * apply it. Still gated by ConfigSelfHealer's own rate limit (3 heals/hour, 10 lifetime).
 *
 * The raw suggestion is still stored under key "claude_review:latest" for visibility/audit
 * even when it results in no changes or the healer isn't wired in (e.g. legacy call sites).
 *
 * Call {@link #runEndOfSessionReview(String, double)} after EOD exits complete.
 * Requires CLAUDE_API_KEY env var or CLAUDE_API_KEY in config.properties (via TradeDatabase).
 */
public class ClaudeSessionReviewer {

    private static final Logger logger = LoggerFactory.getLogger(ClaudeSessionReviewer.class);

    private static final String CLAUDE_API_URL = "https://api.anthropic.com/v1/messages";
    private static final String MODEL = "claude-haiku-4-5-20251001";
    private static final String STATE_KEY = "claude_review:latest";
    private static final String LAST_REVIEW_KEY = "claude_review:last_date";

    private final TradeDatabase database;
    private final OkHttpClient httpClient;
    private final String apiKey;
    private final ConfigSelfHealer configSelfHealer;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** Legacy constructor — no self-heal routing, suggestion is only stored/logged. */
    public ClaudeSessionReviewer(TradeDatabase database, String apiKey) {
        this(database, apiKey, null);
    }

    public ClaudeSessionReviewer(TradeDatabase database, String apiKey, ConfigSelfHealer configSelfHealer) {
        this.database = database;
        this.apiKey = apiKey;
        this.configSelfHealer = configSelfHealer;
        this.httpClient = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build();
    }

    /**
     * Runs the end-of-session review. Safe to call repeatedly — skips if already
     * run today. No-ops if apiKey is blank or Claude is unreachable.
     *
     * @param currentRegime  regime name at session end (e.g. "WEAK_BEAR")
     * @param currentVix     VIX at session end
     */
    public void runEndOfSessionReview(String currentRegime, double currentVix) {
        if (apiKey == null || apiKey.isBlank()) {
            logger.debug("CLAUDE_API_KEY not set — skipping end-of-session review");
            return;
        }

        String today = LocalDate.now(ZoneId.of("America/New_York")).toString();
        String lastReviewDate = database.loadBotState(LAST_REVIEW_KEY);
        if (today.equals(lastReviewDate)) {
            logger.debug("End-of-session review already ran today ({})", today);
            return;
        }

        try {
            String summary = buildDailySummary(today, currentRegime, currentVix);
            String suggestion = callClaude(summary);
            if (suggestion != null && !suggestion.isBlank()) {
                database.saveBotState(STATE_KEY, suggestion);
                database.saveBotState(LAST_REVIEW_KEY, today);
                logger.info("Claude end-of-session review stored. Suggestion preview: {}",
                    suggestion.length() > 200 ? suggestion.substring(0, 200) + "..." : suggestion);
                applySuggestionIfActionable(suggestion);
            }
        } catch (Exception e) {
            logger.warn("End-of-session Claude review failed (non-fatal): {}", e.getMessage());
        }
    }

    /**
     * Parses Claude's {"changes":[{"key","from","to","reason"}]} suggestion and, if it
     * contains at least one change and a healer is wired in, routes it through
     * ConfigSelfHealer.heal() as a synthetic ErrorAnalysis — same backup/sandbox-test/
     * promote-or-rollback path as an exception-triggered heal, so a bad or hallucinated
     * suggestion gets caught by the sandbox test rather than applied blind.
     */
    /** Package-visible for testing. */
    void applySuggestionIfActionable(String suggestionJson) {
        if (configSelfHealer == null) {
            logger.debug("No ConfigSelfHealer wired in — suggestion stored for manual review only");
            return;
        }
        Map<String, String> adjustments;
        StringBuilder reasons = new StringBuilder();
        try {
            JsonNode root = objectMapper.readTree(suggestionJson);
            JsonNode changes = root.get("changes");
            if (changes == null || !changes.isArray() || changes.isEmpty()) {
                logger.info("Claude review: no config changes suggested today");
                return;
            }
            adjustments = new LinkedHashMap<>();
            for (JsonNode change : changes) {
                String key = change.path("key").asText(null);
                String to = change.path("to").asText(null);
                if (key == null || to == null) continue;
                adjustments.put(key, to);
                reasons.append(key).append(": ").append(change.path("reason").asText("")).append("; ");
            }
        } catch (Exception e) {
            logger.warn("Could not parse Claude's suggested config diff, skipping auto-apply: {}", e.getMessage());
            return;
        }
        if (adjustments.isEmpty()) {
            return;
        }

        var pattern = new ErrorDetector.ErrorPattern(
            "CLAUDE_SESSION_REVIEW", "", ErrorDetector.Severity.MEDIUM,
            reasons.toString(), adjustments);
        var analysis = new ErrorDetector.ErrorAnalysis(
            "ClaudeSessionReview", "Daily end-of-session review suggestion",
            pattern, ErrorDetector.Severity.MEDIUM, 1, true,
            "Claude end-of-session review " + LocalDate.now(ZoneId.of("America/New_York")));

        logger.info("🔧 Routing Claude's {} suggested change(s) through ConfigSelfHealer: {}",
            adjustments.size(), adjustments.keySet());
        configSelfHealer.heal(analysis);
    }

    private String buildDailySummary(String date, String regime, double vix) {
        var sb = new StringBuilder();
        sb.append("Trading session summary for ").append(date).append(":\n\n");
        sb.append("Market regime: ").append(regime).append("\n");
        sb.append("VIX at close: ").append(String.format("%.2f", vix)).append("\n\n");

        // Today's closed trades — returned as List<Map<String,Object>>
        var closedToday = database.getRecentClosedTrades(20);
        double totalPnl = 0.0;
        int wins = 0, losses = 0;
        var tradeLines = new StringBuilder();
        for (var t : closedToday) {
            double pnl = t.containsKey("pnl") ? (double) t.get("pnl") : 0.0;
            totalPnl += pnl;
            if (pnl > 0) wins++; else losses++;
            String sym      = (String) t.getOrDefault("symbol", "?");
            double entry    = t.containsKey("entryPrice") ? (double) t.get("entryPrice") : 0.0;
            double exit     = t.containsKey("exitPrice")  ? (double) t.get("exitPrice")  : 0.0;
            String strategy = (String) t.getOrDefault("strategy", "?");
            tradeLines.append(String.format("  %s | entry $%.2f | exit $%.2f | P&L $%.2f | strategy: %s\n",
                sym, entry, exit, pnl, strategy));
        }

        sb.append("Trades today: ").append(wins + losses)
            .append(" (").append(wins).append("W / ").append(losses).append("L)\n");
        sb.append("Total P&L: $").append(String.format("%.2f", totalPnl)).append("\n\n");
        sb.append("Trade detail:\n").append(tradeLines);

        sb.append("\nPlease analyze these results and provide a brief JSON config-diff suggestion ")
            .append("in this exact format, with no other text:\n")
            .append("{\"changes\":[{\"key\":\"CONFIG_KEY\",\"from\":\"old\",\"to\":\"new\",\"reason\":\"why\"}]}\n")
            .append("Only suggest changes that are directly supported by today's data. ")
            .append("If no changes are warranted, return {\"changes\":[]}\n");

        return sb.toString();
    }

    private String callClaude(String prompt) throws Exception {
        String body = String.format("""
            {
                "model": "%s",
                "max_tokens": 512,
                "messages": [{"role": "user", "content": %s}]
            }
            """, MODEL, toJsonString(prompt));

        var request = new Request.Builder()
            .url(CLAUDE_API_URL)
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .header("content-type", "application/json")
            .post(RequestBody.create(body, MediaType.get("application/json")))
            .build();

        try (var response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                logger.warn("Claude API returned {}: {}", response.code(),
                    response.body() != null ? response.body().string() : "");
                return null;
            }
            String responseBody = response.body() != null ? response.body().string() : "";
            return extractContent(responseBody);
        }
    }

    private static String extractContent(String json) {
        // Simple extraction — avoids pulling in a full JSON library
        int idx = json.indexOf("\"text\":");
        if (idx < 0) return null;
        int start = json.indexOf('"', idx + 7) + 1;
        int end = json.lastIndexOf('"');
        if (start <= 0 || end <= start) return null;
        // The Claude API escapes the text content; unescape basic sequences
        return json.substring(start, end)
            .replace("\\n", "\n")
            .replace("\\\"", "\"")
            .replace("\\\\", "\\");
    }

    private static String toJsonString(String s) {
        return "\"" + s
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t") + "\"";
    }

    /**
     * Logs the last Claude review suggestion (call on startup so the operator sees it).
     */
    public void logLastReviewIfPresent() {
        String review = database.loadBotState(STATE_KEY);
        String reviewDate = database.loadBotState(LAST_REVIEW_KEY);
        if (review != null && !review.isBlank()) {
            logger.info("📋 Claude session review from {}: {}", reviewDate, review);
        }
    }
}
