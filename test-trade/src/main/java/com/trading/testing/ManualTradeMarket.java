package com.trading.testing;

import com.trading.api.AlpacaClient;
import com.trading.config.Config;
import com.trading.risk.RiskManager;
import com.trading.persistence.TradeDatabase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Market Order test to guarantee immediate fill for validation.
 * Uses MARKET order instead of LIMIT to ensure execution.
 */
public class ManualTradeMarket {
    private static final Logger logger = LoggerFactory.getLogger(ManualTradeMarket.class);
    private static final double INITIAL_CAPITAL = 1000.0;

    public static void main(String[] args) {
        try {
            logger.info("========================================");
            logger.info("MARKET ORDER TEST - FORCING IMMEDIATE FILL");
            logger.info("========================================");
            
            var config = new Config();
            if (!config.isValid()) {
                logger.error("Invalid config");
                return;
            }
            
            var client = new AlpacaClient(config);
            var riskManager = new RiskManager(INITIAL_CAPITAL);
            var database = new TradeDatabase();
            
            String symbol = "SPY";
            
            // Get current price
            var bar = client.getLatestBar(symbol);
            double currentPrice = bar.get().close();
            logger.info("Current Market Price for {}: ${}", symbol, currentPrice);
            
            // Calculate fractional position size
            double positionSize = riskManager.calculatePositionSize(INITIAL_CAPITAL, currentPrice);
            logger.info("Position Size: {} shares (Fractional for small account)", positionSize);
            
            // Calculate stop/profit for tracking (client-side)
            double stopLoss = riskManager.calculateStopLoss(currentPrice);
            double takeProfit = riskManager.calculateTakeProfit(currentPrice);
            
            logger.info("Risk Parameters:");
            logger.info("  Entry (Market): ~${}", String.format("%.2f", currentPrice));
            logger.info("  Stop Loss:      ${}", String.format("%.2f", stopLoss));
            logger.info("  Take Profit:    ${}", String.format("%.2f", takeProfit));
            logger.warn("NOTE: Stop/Profit will be managed CLIENT-SIDE by the bot");
            
            // Place MARKET order (will fill immediately)
            logger.info("Placing MARKET ORDER for immediate execution...");
            client.placeOrder(symbol, positionSize, "buy", "market", "day", null);
            
            logger.info("✅ MARKET ORDER PLACED!");
            logger.info("========================================");
            logger.info("NEXT STEPS:");
            logger.info("1. Check Alpaca Dashboard - you should see the filled position NOW");
            logger.info("2. Watch the bot logs - it will pick up the position in ~10 seconds");
            logger.info("3. The bot will monitor it and trigger Stop Loss or Take Profit");
            logger.info("========================================");
            
            // Record in database for bot tracking
            var position = riskManager.createPosition(symbol, currentPrice, positionSize);
            database.recordTrade(
                symbol, 
                "MANUAL_TEST",
                "TEST",  // profile name
                position.entryTime(),
                position.entryPrice(), 
                position.quantity(),
                position.stopLoss(), 
                position.takeProfit()
            );
            logger.info("Position recorded in database for bot tracking");
            
        } catch (Exception e) {
            logger.error("Market trade failed", e);
        }
    }
}
