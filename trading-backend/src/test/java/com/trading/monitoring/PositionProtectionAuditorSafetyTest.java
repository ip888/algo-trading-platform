package com.trading.monitoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PositionProtectionAuditorSafetyTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void stopMustBeActiveSellCoveringActualQuantity() throws Exception {
        var orders = JSON.readTree("""
            [
              {"symbol":"SPY","side":"buy","type":"stop","status":"new","qty":"10"},
              {"symbol":"SPY","side":"sell","type":"stop","status":"canceled","qty":"10"},
              {"symbol":"SPY","side":"sell","type":"stop","status":"new","qty":"1","filled_qty":"0"}
            ]
            """);
        assertFalse(PositionProtectionAuditor.hasSufficientBrokerStop(orders, "SPY", 2));
        assertTrue(PositionProtectionAuditor.hasSufficientBrokerStop(orders, "SPY", 1));
        assertFalse(PositionProtectionAuditor.hasSufficientBrokerStop(orders, "QQQ", 1));
    }

    @Test
    void partiallyFilledStopOnlyCoversRemainingQuantity() throws Exception {
        var orders = JSON.readTree("""
            [{"symbol":"SPY","side":"sell","type":"stop_limit","status":"partially_filled",
              "qty":"3","filled_qty":"1"}]
            """);
        assertTrue(PositionProtectionAuditor.hasSufficientBrokerStop(orders, "SPY", 2));
        assertFalse(PositionProtectionAuditor.hasSufficientBrokerStop(orders, "SPY", 3));
    }

    @Test
    void malformedAndMissingOrdersCannotProveProtection() throws Exception {
        assertFalse(PositionProtectionAuditor.hasSufficientBrokerStop(JSON.readTree("{}"), "SPY", 1));
        assertFalse(PositionProtectionAuditor.hasSufficientBrokerStop(JSON.readTree("[]"), "SPY", 1));
        assertFalse(PositionProtectionAuditor.hasSufficientBrokerStop(JSON.readTree("""
            [{"symbol":"SPY","side":"sell","type":"stop","status":"new","qty":"unknown"}]
            """), "SPY", 1));
    }

    @Test
    void heldBracketStopDoesNotProtectPartiallyFilledParent() throws Exception {
        var heldOrders = JSON.readTree("""
            [{"symbol":"SPY","side":"sell","type":"stop","status":"held",
              "qty":"3","filled_qty":"0"}]
            """);
        assertFalse(PositionProtectionAuditor.hasSufficientBrokerStop(heldOrders, "SPY", 1));
        var acceptedOrders = JSON.readTree("""
            [{"symbol":"SPY","side":"sell","type":"stop","status":"accepted",
              "qty":"3","filled_qty":"0"}]
            """);
        assertFalse(PositionProtectionAuditor.hasSufficientBrokerStop(acceptedOrders, "SPY", 1));
    }

    @Test
    void takeProfitMustBeLimitSellWithSufficientQuantity() throws Exception {
        var orders = JSON.readTree("""
            [{"symbol":"SPY","side":"sell","type":"limit","status":"new","qty":"4"}]
            """);
        assertTrue(PositionProtectionAuditor.hasSufficientTakeProfit(orders, "SPY", 4));
        assertFalse(PositionProtectionAuditor.hasSufficientBrokerStop(orders, "SPY", 4));
        assertFalse(PositionProtectionAuditor.hasSufficientTakeProfit(orders, "SPY", 5));
    }
}
