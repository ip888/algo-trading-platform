package com.trading.testing;

import com.trading.api.AlpacaClient;
import com.trading.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CheckAllPositions {
    private static final Logger logger = LoggerFactory.getLogger(CheckAllPositions.class);

    public static void main(String[] args) throws Exception {
        var config = new Config();
        var client = new AlpacaClient(config);
        
        logger.info("========================================");
        logger.info("CHECKING POSITIONS IN ALPACA ACCOUNT:");
        logger.info("========================================");
        
        // Check known symbols (SPY, QQQ, IWM from our bot config)
        String[] symbols = {"SPY", "QQQ", "IWM"};
        int positionCount = 0;
        
        for (String symbol : symbols) {
            var posOpt = client.getPosition(symbol);
            if (posOpt.isPresent()) {
                positionCount++;
                var pos = posOpt.get();
                logger.info("");
                logger.info("Symbol: {}", pos.symbol());
                logger.info("  Quantity: {} shares", pos.quantity());
                logger.info("  Entry Price: ${}", String.format("%.2f", pos.avgEntryPrice()));
                logger.info("  Current Value: ${}", String.format("%.2f", pos.marketValue()));
                logger.info("  P/L: ${}", String.format("%.2f", pos.unrealizedPL()));
                
                // Check if it's fractional
                boolean isFractional = pos.quantity() % 1 != 0;
                if (isFractional) {
                    logger.info("  ✅ FRACTIONAL TRADE (likely from YOUR bot)");
                } else {
                    logger.info("  ⚠️  WHOLE SHARES (might be from Alpaca Autofill)");
                }
            }
        }
        
        if (positionCount == 0) {
            logger.info("✅ No positions found in tracked symbols - Account is CLEAN for testing");
        }
        
        logger.info("========================================");
        logger.info("CHECKING RECENT ORDERS (To see why positions are missing):");
        logger.info("========================================");
        
        try {
            var orders = client.getRecentOrders("SPY");
            if (orders.isArray() && orders.size() > 0) {
                for (var order : orders) {
                    logger.info("Order ID: {}", order.get("id").asText());
                    logger.info("  Side: {}", order.get("side").asText());
                    logger.info("  Qty: {}", order.get("qty").asText());
                    logger.info("  Status: {}", order.get("status").asText()); // filled, rejected, canceled?
                    logger.info("  Created At: {}", order.get("created_at").asText());
                    if (order.has("filled_at") && !order.get("filled_at").isNull()) {
                        logger.info("  Filled At: {}", order.get("filled_at").asText());
                    }
                    if (order.has("failed_at") && !order.get("failed_at").isNull()) {
                        logger.info("  Failed At: {}", order.get("failed_at").asText());
                    }
                    logger.info("---");
                }
            } else {
                logger.info("No recent orders found for SPY.");
            }
        } catch (Exception e) {
            logger.error("Failed to check orders", e);
        }
        
        logger.info("========================================");
        logger.info("HOW TO IDENTIFY YOUR BOT'S TRADES:");
        logger.info("- Fractional quantities (0.73 shares) = YOUR BOT");
        logger.info("- Whole numbers (10, 50 shares) = Alpaca Autofill");
        logger.info("========================================");
    }
}
