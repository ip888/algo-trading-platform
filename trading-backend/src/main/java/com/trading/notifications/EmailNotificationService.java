package com.trading.notifications;

import com.trading.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.mail.*;
import javax.mail.internet.*;
import java.util.Properties;

/**
 * Email Notification Service for trading alerts.
 * 
 * Sends email notifications for:
 * - High correlation warnings
 * - Rebalancing recommendations
 * - Adaptive parameter changes
 * - Large losses or wins
 * - System errors
 * 
 * Uses Gmail SMTP by default (configurable).
 */
public class EmailNotificationService {
    private static final Logger logger = LoggerFactory.getLogger(EmailNotificationService.class);
    
    private final boolean enabled;
    private final String smtpHost;
    private final String smtpPort;
    private final String username;
    private final String password;
    private final String toEmail;
    private final Session session;
    
    public EmailNotificationService(Config config) {
        // Email notifications disabled by default - enable by adding config methods
        this.enabled = false; // TODO: Add config.isEmailNotificationsEnabled()
        this.smtpHost = "smtp.gmail.com";
        this.smtpPort = "587";
        this.username = "";
        this.password = "";
        this.toEmail = "";
        this.session = null;
        
        logger.info("Email notifications: DISABLED (add config to enable)");
    }
    
    /**
     * Send email alert.
     */
    public void sendAlert(String subject, String body) {
        if (!enabled || session == null) {
            logger.debug("Email notification skipped (disabled): {}", subject);
            return;
        }
        
        try {
            Message message = new MimeMessage(session);
            message.setFrom(new InternetAddress(username));
            message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(toEmail));
            message.setSubject("[Trading Bot] " + subject);
            message.setText(body);
            
            Transport.send(message);
            logger.info("📧 Email sent: {}", subject);
        } catch (MessagingException e) {
            logger.error("Failed to send email: {}", subject, e);
        }
    }
    
    /**
     * Alert: High portfolio correlation detected.
     */
    public void alertHighCorrelation(double score, String pairs) {
        String subject = "⚠️ High Portfolio Correlation";
        String body = String.format(
            "Portfolio correlation warning:\n\n" +
            "Diversification Score: %.1f%%\n" +
            "Status: %s\n\n" +
            "Highly Correlated Pairs:\n%s\n\n" +
            "Recommendation: Consider reducing positions in correlated symbols.",
            score * 100,
            score < 0.5 ? "POOR" : score < 0.7 ? "MODERATE" : "GOOD",
            pairs
        );
        sendAlert(subject, body);
    }
    
    /**
     * Alert: Portfolio rebalancing recommended.
     */
    public void alertRebalanceNeeded(double maxDrift, String reason) {
        String subject = "🔄 Rebalancing Recommended";
        String body = String.format(
            "Portfolio rebalancing recommended:\n\n" +
            "Max Drift: %.1f%%\n" +
            "Reason: %s\n\n" +
            "Action: Review portfolio allocation and consider rebalancing.",
            maxDrift * 100,
            reason
        );
        sendAlert(subject, body);
    }
    
    /**
     * Alert: Adaptive parameter changed.
     */
    public void alertAdaptiveChange(String param, double oldVal, double newVal, String reason) {
        String subject = "🤖 Adaptive Parameter Changed";
        String body = String.format(
            "Adaptive parameter adjustment:\n\n" +
            "Parameter: %s\n" +
            "Old Value: %.2f\n" +
            "New Value: %.2f\n" +
            "Reason: %s\n\n" +
            "The bot has automatically adjusted this parameter based on performance.",
            param, oldVal, newVal, reason
        );
        sendAlert(subject, body);
    }
    
    /**
     * Alert: Large profit or loss.
     */
    public void alertLargePnL(String symbol, double pnl, double pnlPercent, boolean isProfit) {
        String emoji = isProfit ? "💰" : "⚠️";
        String subject = String.format("%s Large %s: %s", emoji, isProfit ? "Profit" : "Loss", symbol);
        String body = String.format(
            "%s trade closed:\n\n" +
            "Symbol: %s\n" +
            "P&L: $%.2f (%.1f%%)\n\n" +
            "%s",
            isProfit ? "Profitable" : "Loss",
            symbol, pnl, pnlPercent,
            isProfit ? "Great trade!" : "Review strategy for this symbol."
        );
        sendAlert(subject, body);
    }
    
    /**
     * Alert: System error.
     */
    public void alertSystemError(String error, String details) {
        String subject = "❌ System Error";
        String body = String.format(
            "Trading bot error:\n\n" +
            "Error: %s\n" +
            "Details: %s\n\n" +
            "Action: Check logs and investigate.",
            error, details
        );
        sendAlert(subject, body);
    }
    
    /**
     * Daily summary email.
     */
    public void sendDailySummary(int trades, double totalPnL, double winRate, String topWinner, String topLoser) {
        String subject = "📊 Daily Trading Summary";
        String body = String.format(
            "Daily Performance Summary:\n\n" +
            "Total Trades: %d\n" +
            "Total P&L: $%.2f\n" +
            "Win Rate: %.1f%%\n\n" +
            "Top Winner: %s\n" +
            "Top Loser: %s\n\n" +
            "Keep up the good work!",
            trades, totalPnL, winRate, topWinner, topLoser
        );
        sendAlert(subject, body);
    }
}
