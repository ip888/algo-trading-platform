package com.trading.testing;

import com.trading.api.AlpacaClient;
import com.trading.config.Config;
import com.trading.risk.RiskManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manual test script to force a trade execution for verification.
 * Verifies: Fractional Sizing, Limit Orders, Bracket Orders.
 */
public class ManualTrade {
    private static final Logger logger = LoggerFactory.getLogger(ManualTrade.class);
    private static final double INITIAL_CAPITAL = 1000.0; // Force small account size

    public static void main(String[] args) {
        try {
            logger.info("Starting Manual Trade Test...");
            
            // 1. Initialize components
            var config = new Config();
            if (!config.isValid()) {
                logger.error("Invalid config");
                return;
            }
            
            var client = new AlpacaClient(config);
            var riskManager = new RiskManager(INITIAL_CAPITAL);
            
            String symbol = "SPY";
            
            // 2. Get Market Data
            // Get current price
            var bar = client.getLatestBar(symbol);
            if (bar.isEmpty()) {
                System.out.println("❌ Could not get current price for " + symbol);
                return;
            }
            
            double currentPrice = bar.get().close();
            System.out.println("Current price: $" + currentPrice);
            
            // 3. Calculate Position Size (Fractional)
            // Use capped capital ($1000) to simulate small account
            double positionSize = riskManager.calculatePositionSize(INITIAL_CAPITAL, currentPrice);
            logger.info("Calculated Position Size: {} shares (based on ${} capital)", positionSize, INITIAL_CAPITAL);
            
            // 4. Calculate Bracket Prices
            double limitPrice = currentPrice * 1.001; // Buy slightly above market to ensure fill
            double stopLoss = riskManager.calculateStopLoss(limitPrice);
            double takeProfit = riskManager.calculateTakeProfit(limitPrice);
            
            logger.info("Placing Bracket Order:");
            logger.info("  Entry Limit: ${}", String.format("%.2f", limitPrice));
            logger.info("  Stop Loss:   ${}", String.format("%.2f", stopLoss));
            logger.info("  Take Profit: ${}", String.format("%.2f", takeProfit));
            
            // 5. Place Order
            client.placeBracketOrder(
                symbol,
                positionSize,
                "buy",
                takeProfit,
                stopLoss,
                null, // No stop limit
                limitPrice
            );
            
            logger.info("✅ Order placed successfully! Check Alpaca Dashboard.");
            
        } catch (Exception e) {
            logger.error("Manual trade failed", e);
        }
    }
}
