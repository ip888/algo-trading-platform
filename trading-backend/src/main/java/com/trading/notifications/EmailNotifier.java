package com.trading.notifications;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.mail.*;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;
import java.util.Properties;

/**
 * Email notification service using SMTP.
 * Requires EMAIL_ADDRESS, EMAIL_PASSWORD, EMAIL_SMTP_HOST, EMAIL_SMTP_PORT in config.properties.
 */
public final class EmailNotifier {
    private static final Logger logger = LoggerFactory.getLogger(EmailNotifier.class);
    
    private final String from;
    private final String password;
    private final Session session;
    private final boolean enabled;
    
    public EmailNotifier(String emailAddress, String password, String smtpHost, String smtpPort) {
        this.from = emailAddress;
        this.password = password;
        this.enabled = emailAddress != null && !emailAddress.isEmpty() && 
                      password != null && !password.isEmpty();
        
        if (enabled) {
            Properties props = new Properties();
            props.put("mail.smtp.auth", "true");
            props.put("mail.smtp.starttls.enable", "true");
            props.put("mail.smtp.host", smtpHost != null ? smtpHost : "smtp.gmail.com");
            props.put("mail.smtp.port", smtpPort != null ? smtpPort : "587");
            
            this.session = Session.getInstance(props, new Authenticator() {
                @Override
                protected PasswordAuthentication getPasswordAuthentication() {
                    return new PasswordAuthentication(from, password);
                }
            });
            
            logger.info("Email notifications enabled");
        } else {
            this.session = null;
            logger.info("Email notifications disabled (missing config)");
        }
    }
    
    public void sendEmail(String subject, String body) {
        if (!enabled) {
            return;
        }
        
        try {
            Message message = new MimeMessage(session);
            message.setFrom(new InternetAddress(from));
            message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(from));
            message.setSubject(subject);
            message.setText(body);
            
            Transport.send(message);
            logger.debug("Email sent: {}", subject);
            
        } catch (MessagingException e) {
            logger.error("Error sending email", e);
        }
    }
    
    public void sendTradeAlert(String symbol, String action, double price, String reason) {
        String subject = String.format("Trade Alert: %s %s", action, symbol);
        String body = String.format("Trade Alert\n\n" +
            "Symbol: %s\n" +
            "Action: %s\n" +
            "Price: $%.2f\n" +
            "Reason: %s", symbol, action, price, reason);
        sendEmail(subject, body);
    }
}
