package com.trading.execution;

import com.trading.api.AlpacaClient;
import com.trading.config.Config;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Smart Order Router
 * Routes orders intelligently based on spread and market conditions
 */
public class SmartOrderRouter {
    private static final Logger logger = LoggerFactory.getLogger(SmartOrderRouter.class);
    
    private final Config config;
    private final AlpacaClient client;
    
    public SmartOrderRouter(Config config, AlpacaClient client) {
        this.config = config;
        this.client = client;
    }
    
    /**
     * Place order with smart routing
     * Currently uses market orders (quote API integration pending)
     */
    public void placeSmartOrder(String symbol, double quantity, String side) throws Exception {
        // For now, always use market orders
        // TODO: Integrate quote API for spread-based routing in Phase 4
        client.placeOrder(symbol, quantity, side, "market", "day", null);
        
        logger.debug("✅ {} Market order placed: {} {}", symbol, side, quantity);
    }
}
