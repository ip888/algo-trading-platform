package com.trading.testing;

import com.trading.api.AlpacaClient;
import com.trading.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CheckOrderStatus {
    private static final Logger logger = LoggerFactory.getLogger(CheckOrderStatus.class);

    public static void main(String[] args) {
        try {
            var config = new Config();
            var client = new AlpacaClient(config);
            
            logger.info("Checking Open Orders...");
            var orders = client.getOpenOrders("SPY");
            if (orders.isEmpty()) {
                logger.info("No open orders found for SPY.");
            } else {
                orders.forEach(o -> logger.info("Open Order: {}", o));
            }
            
            try {
                var positionOpt = client.getPosition("SPY");
                if (positionOpt.isPresent()) {
                    logger.info("Position found: {} shares of SPY", positionOpt.get().quantity());
                } else {
                    logger.info("No position found for SPY.");
                }
            } catch (Exception e) {
                logger.info("Error fetching position: {}", e.getMessage());
            }
            
        } catch (Exception e) {
            logger.error("Error checking status", e);
        }
    }
}
