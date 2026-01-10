package com.trading.testing;

import com.trading.api.AlpacaClient;
import com.trading.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Clean the Alpaca Paper account by canceling all orders and closing all positions.
 * USE WITH CAUTION - This closes EVERYTHING.
 */
public class CleanAccount {
    private static final Logger logger = LoggerFactory.getLogger(CleanAccount.class);

    public static void main(String[] args) throws Exception {
        logger.warn("========================================");
        logger.warn("CLEANING ALPACA PAPER ACCOUNT");
        logger.warn("This will CLOSE ALL POSITIONS and CANCEL ALL ORDERS");
        logger.warn("========================================");
        
        var config = new Config();
        var client = new AlpacaClient(config);
        
        // 1. Cancel ALL open orders across all symbols
        logger.info("Step 1: Canceling all open orders...");
        String[] symbols = {"SPY", "QQQ", "IWM", "AAPL", "TSLA", "GOOGL", "AMZN", "MSFT", "NVDA", "META"};
        int canceledCount = 0;
        
        for (String symbol : symbols) {
            try {
                var orders = client.getOpenOrders(symbol);
                for (var order : orders) {
                    String orderId = order.get("id").asText();
                    logger.info("  Canceling order {} for {}", orderId, symbol);
                    client.cancelOrder(orderId);
                    canceledCount++;
                }
            } catch (Exception e) {
                // Ignore - might just mean no orders for this symbol
            }
        }
        logger.info("Canceled {} orders", canceledCount);
        
        // 2. Close ALL open positions
        logger.info("Step 2: Closing all open positions...");
        int closedCount = 0;
        
        for (String symbol : symbols) {
            var posOpt = client.getPosition(symbol);
            if (posOpt.isPresent()) {
                var pos = posOpt.get();
                double qty = pos.quantity();
                logger.info("  Closing position: {} ({} shares)", symbol, qty);
                
                // Place market order to close
                client.placeOrder(symbol, qty, "sell", "market", "day", null);
                closedCount++;
            }
        }
        logger.info("Closed {} positions", closedCount);
        
        logger.info("========================================");
        logger.info("✅ ACCOUNT CLEANED!");
        logger.info("Canceled Orders: {}", canceledCount);
        logger.info("Closed Positions: {}", closedCount);
        logger.info("========================================");
        logger.info("Your Paper account is now EMPTY and ready for testing.");
    }
}
