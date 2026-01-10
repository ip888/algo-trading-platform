package com.trading.protection;

import com.trading.api.ResilientAlpacaClient;
import com.trading.api.model.Position;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Handles emergency actions when the system detects a critical failure.
 * Primary action is "Flatten All" -> Cancel all orders and close all positions.
 */
public class EmergencyProtocol {
    private static final Logger logger = LoggerFactory.getLogger(EmergencyProtocol.class);
    
    private final ResilientAlpacaClient client;
    private volatile boolean triggered = false;

    public EmergencyProtocol(ResilientAlpacaClient client) {
        this.client = client;
    }

    /**
     * Trigger the emergency protocol.
     * @param reason The cause of the emergency
     */
    public synchronized void trigger(String reason) {
        if (triggered) {
            logger.warn("Emergency protocol already triggered! Ignoring duplicate request: {}", reason);
            return;
        }
        
        triggered = true;
        logger.error("🚨 EMERGENCY PROTOCOL ACTIVATED: {} 🚨", reason);
        
        try {
            flattenAll();
        } catch (Exception e) {
            logger.error("CRITICAL ERROR executing emergency protocol", e);
        }
    }

    /**
     * Cancel all orders and close all positions immediately.
     */
    private void flattenAll() {
        logger.warn("STEP 1: Cancelling all open orders...");
        try {
            client.cancelAllOrders();
            logger.info("✅ All orders cancelled.");
        } catch (Exception e) {
            logger.error("❌ Failed to cancel orders", e);
        }

        logger.warn("STEP 2: Closing all positions...");
        try {
            List<Position> positions = client.getPositions();
            if (positions.isEmpty()) {
                logger.info("No open positions to close.");
                return;
            }

            for (Position pos : positions) {
                try {
                    logger.warn("Closing position: {} ({} shares)", pos.symbol(), pos.quantity());
                    client.placeOrder(
                        pos.symbol(), 
                        Math.abs(pos.quantity()), 
                        pos.quantity() > 0 ? "sell" : "buy", // Close long with sell, short with buy
                        "market", 
                        "day", 
                        null
                    );
                } catch (Exception e) {
                    logger.error("❌ Failed to close position for {}", pos.symbol(), e);
                }
            }
            logger.info("✅ Close commands sent for all users.");
            
        } catch (Exception e) {
            logger.error("❌ Failed to fetch positions for closure", e);
        }
    }
    
    public boolean isTriggered() {
        return triggered;
    }
}
