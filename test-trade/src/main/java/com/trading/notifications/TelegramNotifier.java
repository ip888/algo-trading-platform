package com.trading.notifications;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

/**
 * Telegram bot notification service.
 * Requires TELEGRAM_BOT_TOKEN and TELEGRAM_CHAT_ID in config.properties.
 */
public final class TelegramNotifier {
    private static final Logger logger = LoggerFactory.getLogger(TelegramNotifier.class);
    
    private final String botToken;
    private final String chatId;
    private final HttpClient httpClient;
    private final boolean enabled;
    
    public TelegramNotifier(String botToken, String chatId) {
        this.botToken = botToken;
        this.chatId = chatId;
        this.httpClient = HttpClient.newHttpClient();
        this.enabled = botToken != null && !botToken.isEmpty() && chatId != null && !chatId.isEmpty();
        
        if (enabled) {
            logger.info("Telegram notifications enabled");
        } else {
            logger.info("Telegram notifications disabled (missing config)");
        }
    }
    
    public void sendMessage(String message) {
        if (!enabled) {
            return;
        }
        
        try {
            String url = String.format("https://api.telegram.org/bot%s/sendMessage", botToken);
            String encodedMessage = URLEncoder.encode(message, StandardCharsets.UTF_8);
            String body = String.format("chat_id=%s&text=%s", chatId, encodedMessage);
            
            var request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
            
            httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenAccept(response -> {
                    if (response.statusCode() == 200) {
                        logger.debug("Telegram notification sent: {}", message);
                    } else {
                        logger.error("Failed to send Telegram notification: {}", response.statusCode());
                    }
                });
                
        } catch (Exception e) {
            logger.error("Error sending Telegram notification", e);
        }
    }
    
    public void sendTradeAlert(String symbol, String action, double price, String reason) {
        String message = String.format("🤖 Trade Alert\n\n" +
            "Symbol: %s\n" +
            "Action: %s\n" +
            "Price: $%.2f\n" +
            "Reason: %s", symbol, action, price, reason);
        sendMessage(message);
    }
}
