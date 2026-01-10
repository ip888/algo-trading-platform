package com.trading.core.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.javalin.websocket.WsConnectContext;
import io.javalin.websocket.WsContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebSocket Handler for the Legacy UI.
 * Keeps the dashboard alive by creating a compatible channel.
 */
public class TradingWebSocketHandler {
    private static final Logger logger = LoggerFactory.getLogger(TradingWebSocketHandler.class);
    private static final Map<String, WsContext> sessions = new ConcurrentHashMap<>();
    private static final ObjectMapper mapper = new ObjectMapper();

    public static void onConnect(WsConnectContext ctx) {
        sessions.put(ctx.sessionId(), ctx);
        logger.info("UI Connected: {}", ctx.sessionId());
    }

    public static void onClose(io.javalin.websocket.WsCloseContext ctx) {
        sessions.remove(ctx.sessionId());
        logger.info("UI Disconnected: {}", ctx.sessionId());
    }

    public static void broadcastSystemStatus(String status, String regime, double vix, String lastAction, int activePositions, double totalPnL) {
        ObjectNode root = mapper.createObjectNode();
        root.put("type", "system_status");
        
        ObjectNode data = mapper.createObjectNode();
        data.put("marketOpen", true); 
        data.put("tradingMode", "AUTONOMOUS (JAVA 25)");
        data.put("marketTrend", regime);
        data.put("vix", vix);
        data.put("recommendation", lastAction);
        data.put("activePositions", activePositions);
        data.put("totalPnL", totalPnL);
        
        root.set("data", data);
        broadcast(root);
        
        // Also update 'bot_status' for components that listen to it
        ObjectNode botRoot = mapper.createObjectNode();
        botRoot.put("type", "bot_status");
        ObjectNode botData = mapper.createObjectNode();
        botData.put("marketStatus", status);
        botData.put("regime", regime);
        botData.put("vix", vix);
        botData.put("nextAction", lastAction);
        botData.put("waitingFor", "MARKET DATA");
        botRoot.set("data", botData);
        broadcast(botRoot);
    }

    public static void broadcastAccountData(double equity, double buyingPower, double cash, double reserve, double deployable) {
        ObjectNode root = mapper.createObjectNode();
        root.put("type", "account_data");
        
        ObjectNode data = mapper.createObjectNode();
        data.put("equity", equity);
        data.put("buyingPower", buyingPower);
        data.put("cash", cash);
        data.put("capitalReserve", reserve);
        data.put("deployableCapital", deployable);
        
        root.set("data", data);
        broadcast(root);
    }

    public static void broadcastPositions(ArrayNode positions) {
        ObjectNode root = mapper.createObjectNode();
        root.put("type", "positions_update");
        
        ObjectNode data = mapper.createObjectNode();
        data.set("positions", positions);
        
        root.set("data", data);
        broadcast(root);
    }

    public static void broadcastLog(String message, String level) {
        ObjectNode root = mapper.createObjectNode();
        root.put("type", "activity_log");
        
        ObjectNode data = mapper.createObjectNode();
        data.put("message", message);
        data.put("level", level);
        data.put("timestamp", System.currentTimeMillis());
        
        root.set("data", data);
        broadcast(root);
    }

    public static void broadcastMarketUpdate(java.util.List<com.trading.core.analysis.MarketAnalysisService.AnalysisResult> results) {
        // UI expects 'market_update' with map of symbol -> data
        ObjectNode root = mapper.createObjectNode();
        root.put("type", "market_update");
        
        ObjectNode data = mapper.createObjectNode();
        for (var res : results) {
            ObjectNode symbolData = mapper.createObjectNode();
            symbolData.put("symbol", res.symbol());
            symbolData.put("price", res.price());
            symbolData.put("trend", res.regime());
            symbolData.put("score", res.score());
            symbolData.put("recommendation", res.recommendation());
            data.set(res.symbol(), symbolData);
        }
        
        root.set("data", data);
        broadcast(root);
    }

    public static void broadcast(ObjectNode message) {
        sessions.values().forEach(ctx -> {
            if (ctx.session.isOpen()) {
                ctx.send(message.toString());
            }
        });
    }
}
