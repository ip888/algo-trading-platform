package com.trading.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.javalin.websocket.WsConnectContext;
import io.javalin.websocket.WsMessageContext;
import io.javalin.websocket.WsCloseContext;
import io.javalin.websocket.WsErrorContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * WebSocket handler using Javalin for real-time trading dashboard updates.
 * Industry-standard bidirectional communication for sub-second latency.
 */
public final class TradingWebSocketHandler {
    private static final Logger logger = LoggerFactory.getLogger(TradingWebSocketHandler.class);
    private static final Map<String, WsConnectContext> sessions = new ConcurrentHashMap<>();
    private static final ObjectMapper mapper = new ObjectMapper()
        .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
    private static final ReentrantLock broadcastLock = new ReentrantLock();
    
    // Cache last status messages for new connections
    private static ObjectNode lastSystemStatus;
    private static ObjectNode lastProcessingStatus;
    private static ObjectNode lastMarketAnalysis;
    
    public static void onConnect(WsConnectContext ctx) {
        sessions.put(ctx.sessionId(), ctx);
        logger.info("WebSocket connected: {} (total: {})", ctx.sessionId(), sessions.size());
        
        // Send cached initial state immediately to populate UI
        if (lastSystemStatus != null) sendToSession(ctx, lastSystemStatus);
        if (lastProcessingStatus != null) sendToSession(ctx, lastProcessingStatus);
        if (lastMarketAnalysis != null) sendToSession(ctx, lastMarketAnalysis);
    }

    /**
     * Broadcast active positions list.
     */
    public static void broadcastPositions(java.util.Collection<com.trading.risk.TradePosition> positions) {
        ObjectNode data = mapper.createObjectNode();
        var positionsArray = mapper.createArrayNode();
        
        for (var pos : positions) {
            ObjectNode posNode = mapper.createObjectNode();
            posNode.put("symbol", pos.symbol());
            posNode.put("quantity", pos.quantity());
            posNode.put("entryPrice", pos.entryPrice());
            posNode.put("currentPrice", pos.highestPrice()); // Using highestPrice as proxy
            
            posNode.put("stopLoss", pos.stopLoss());
            posNode.put("takeProfit", pos.takeProfit());
            posNode.put("entryTime", pos.entryTime().toString());
            positionsArray.add(posNode);
        }
        
        data.set("positions", positionsArray);
        ObjectNode message = createMessage("positions_update", data);
        broadcast(message);
    }

    /**
     * Broadcast portfolio update (extended).
     */
    public static void broadcastPortfolioUpdate(double totalValue, double totalPnL, 
                                               double pnlPercent, int activePositions, double winRate) {
        ObjectNode data = mapper.createObjectNode();
        data.put("totalValue", totalValue);
        data.put("totalPnL", totalPnL);
        data.put("pnlPercent", pnlPercent);
        data.put("activePositions", activePositions);
        data.put("winRate", winRate);
        
        ObjectNode message = createMessage("portfolio_update_full", data);
        broadcast(message);
    }
    
    public static void onClose(WsCloseContext ctx) {
        sessions.remove(ctx.sessionId());
        logger.info("WebSocket closed: {} (remaining: {})", 
            ctx.sessionId(), sessions.size());
    }
    
    public static void onError(WsErrorContext ctx) {
        logger.error("WebSocket error for session {}", ctx.sessionId());
        sessions.remove(ctx.sessionId());
    }
    
    public static void onMessage(WsMessageContext ctx) {
        String message = ctx.message();
        logger.debug("Received message from {}: {}", ctx.sessionId(), message);
        
        try {
            // Parse client message
            JsonNode msgNode = mapper.readTree(message);
            String type = msgNode.has("type") ? msgNode.get("type").asText() : "";
            
            switch (type) {
                case "ping" -> {
                    // Respond to ping with pong
                    ObjectNode pong = mapper.createObjectNode();
                    pong.put("type", "pong");
                    pong.put("timestamp", System.currentTimeMillis());
                    ctx.send(pong.toString());
                }
                case "refresh" -> {
                    // Client requesting data refresh - could trigger immediate broadcast
                    logger.info("Client {} requested data refresh", ctx.sessionId());
                }
                case "subscribe" -> {
                    // Client wants to subscribe to specific symbols
                    if (msgNode.has("symbols")) {
                        String symbols = msgNode.get("symbols").asText();
                        logger.info("Client {} subscribed to symbols: {}", ctx.sessionId(), symbols);
                        // Could store subscription preferences per session
                    }
                }
                default -> logger.warn("Unknown message type from client: {}", type);
            }
        } catch (Exception e) {
            logger.error("Error processing client message: {}", message, e);
        }
    }
    
    /**
     * Broadcast market update to all connected clients.
     */
    public static void broadcastMarketUpdate(String symbol, double price, double change, 
                                            double changePercent, long volume, String trend, double score, double rsi) {
        ObjectNode data = mapper.createObjectNode();
        ObjectNode symbolData = mapper.createObjectNode();
        
        symbolData.put("symbol", symbol);
        symbolData.put("price", price);
        symbolData.put("change", change);
        symbolData.put("changePercent", changePercent);
        symbolData.put("volume", volume);
        symbolData.put("trend", trend);
        symbolData.put("score", score);
        symbolData.put("rsi", rsi);
        
        data.set(symbol, symbolData);
        
        ObjectNode message = createMessage("MARKET_UPDATE", data);
        broadcast(message);
    }


    /**
     * Broadcast full market analysis update.
     */
    public static void broadcastMarketUpdate(String trend, double vix, String recommendation, 
                                           String topAsset, Map<String, com.trading.analysis.MarketAnalyzer.AssetScore> scores) {
        ObjectNode data = mapper.createObjectNode();
        data.put("trend", trend);
        data.put("vix", vix);
        data.put("recommendation", recommendation);
        data.put("topAsset", topAsset);
        
        ObjectNode scoresNode = mapper.createObjectNode();
        scores.forEach((symbol, score) -> {
            ObjectNode scoreNode = mapper.createObjectNode();
            scoreNode.put("score", score.overallScore());
            scoreNode.put("recommendation", score.recommendation());
            scoresNode.set(symbol, scoreNode);
        });
        data.set("scores", scoresNode);
        
        ObjectNode message = createMessage("market_analysis", data);
        lastMarketAnalysis = message; // Cache for new clients
        broadcast(message);
    }
    
    /**
     * Broadcast trade event to all connected clients.
     */
    public static void broadcastTradeEvent(String symbol, String action, double price, 
                                          double quantity, String strategy) {
        ObjectNode data = mapper.createObjectNode();
        data.put("symbol", symbol);
        data.put("action", action);
        data.put("price", price);
        data.put("quantity", quantity);
        data.put("strategy", strategy);
        
        ObjectNode message = createMessage("trade_event", data);
        broadcast(message);
    }


    
    /**
     * Broadcast system status update.
     */
    public static void broadcastSystemStatus(boolean marketOpen, boolean volatilityOk, 
                                            String tradingMode, int activePositions, 
                                            double totalPnL, String marketTrend, double vix,
                                            String recommendation, double marketStrength, 
                                            int totalTrades, double winRate, double buyingPower) {
        ObjectNode data = mapper.createObjectNode();
        data.put("marketOpen", marketOpen);
        data.put("volatilityOk", volatilityOk);
        data.put("tradingMode", tradingMode);
        data.put("activePositions", activePositions);
        data.put("totalPnL", totalPnL);
        data.put("marketTrend", marketTrend);
        data.put("vix", vix);
        data.put("recommendation", recommendation);
        data.put("marketStrength", marketStrength);
        data.put("totalTrades", totalTrades);
        data.put("winRate", winRate);
        data.put("buyingPower", buyingPower);
        
        ObjectNode message = createMessage("system_status", data);
        lastSystemStatus = message; // Cache for new clients
        broadcast(message);
    }

    /**
     * Broadcast activity log entry.
     */
    public static void broadcastActivity(String message, String level) {
        ObjectNode data = mapper.createObjectNode();
        data.put("message", message);
        data.put("level", level); // INFO, WARN, ERROR, SUCCESS
        
        ObjectNode msg = createMessage("activity_log", data);
        broadcast(msg);
    }
    
    /**
     * Broadcast Phase 3 feature events to all connected clients
     */
    public static void broadcastPhase3Event(String eventType, String jsonData) {
        try {
            ObjectNode data = (ObjectNode) mapper.readTree(jsonData);
            ObjectNode msg = createMessage(eventType, data);
            broadcast(msg);
        } catch (Exception e) {
            logger.error("Failed to broadcast Phase 3 event: {}", e.getMessage());
        }
    }

    /**
     * Broadcast current processing status (which symbol is being analyzed).
     */
    public static void broadcastProcessingStatus(String currentSymbol, int symbolIndex, int totalSymbols, String stage, String details) {
        ObjectNode data = mapper.createObjectNode();
        data.put("currentSymbol", currentSymbol);
        data.put("symbolIndex", symbolIndex);
        data.put("totalSymbols", totalSymbols);
        data.put("progress", (int)((symbolIndex / (double)totalSymbols) * 100));
        data.put("stage", stage);
        data.put("details", details);
        
        ObjectNode msg = createMessage("processing_status", data);
        lastProcessingStatus = msg; // Cache for new clients
        broadcast(msg);
    }
    
    /**
     * Broadcast account data including equity, buying power, and profit targets.
     */
    public static void broadcastAccountData(double equity, double lastEquity, double buyingPower, 
                                            double cash, double capitalReserve, double deployableCapital,
                                            double mainTakeProfit, double expTakeProfit, 
                                            double stopLoss) {
        ObjectNode data = mapper.createObjectNode();
        data.put("equity", equity);
        data.put("lastEquity", lastEquity);
        data.put("buyingPower", buyingPower);
        data.put("cash", cash);
        data.put("capitalReserve", capitalReserve);
        data.put("deployableCapital", deployableCapital);
        data.put("mainTakeProfitPercent", mainTakeProfit);
        data.put("experimentalTakeProfitPercent", expTakeProfit);
        data.put("stopLossPercent", stopLoss);
        
        ObjectNode message = createMessage("account_data", data);
        broadcast(message);
    }
    
    /**
     * Broadcast system status message (for alerts/notifications).
     */
    public static void broadcastSystemStatus(String status, String title, String message, double vix) {
        ObjectNode data = mapper.createObjectNode();
        data.put("status", status);
        data.put("title", title);
        data.put("message", message);
        data.put("vix", vix);
        
        ObjectNode msg = createMessage("system_status_simple", data);
        broadcast(msg);
    }
    
    /**
     * Broadcast order data, including new orders, fills, and cancellations.
     */
    public static void broadcastOrderUpdate(String profile, String symbol, double quantity, String side, String type, String status, double price) {
        ObjectNode data = mapper.createObjectNode();
        data.put("profile", profile);
        data.put("symbol", symbol);
        data.put("quantity", quantity);
        data.put("side", side);
        data.put("type", type);
        data.put("status", status);
        data.put("price", price);
        
        ObjectNode msg = createMessage("order_update", data);
        broadcast(msg);
    }


    /**
     * Broadcast bot status for Real-Time Focus Dashboard.
     */
    public static void broadcastBotStatus(String marketStatus, String regime, double vix,
                                         String nextAction, String waitingFor) {
        ObjectNode data = mapper.createObjectNode();
        data.put("marketStatus", marketStatus);
        data.put("regime", regime);
        data.put("vix", vix);
        data.put("nextAction", nextAction);
        data.put("waitingFor", waitingFor);
        
        ObjectNode message = createMessage("bot_status", data);
        broadcast(message);
    }
    
    /**
     * Broadcast profit targets monitoring data.
     */
    public static void broadcastProfitTargets(java.util.List<ProfitTargetStatus> targets) {
        ObjectNode data = mapper.createObjectNode();
        var targetsArray = mapper.createArrayNode();
        
        for (var target : targets) {
            ObjectNode targetNode = mapper.createObjectNode();
            targetNode.put("symbol", target.symbol());
            targetNode.put("currentPnlPercent", target.currentPnlPercent());
            targetNode.put("targetPercent", target.targetPercent());
            targetNode.put("distancePercent", target.distancePercent());
            targetNode.put("eta", target.eta());
            targetsArray.add(targetNode);
        }
        
        data.set("targets", targetsArray);
        ObjectNode message = createMessage("profit_targets", data);
        broadcast(message);
    }
    
    /**
     * Record for profit target status.
     */
    public record ProfitTargetStatus(
        String symbol,
        double currentPnlPercent,
        double targetPercent,
        double distancePercent,
        String eta
    ) {}
    
    /**
     * Broadcast portfolio update.
     */
    public static void broadcastPortfolioUpdate(double totalValue, double totalPnL, 
                                               double dayPnL, double cashBalance) {
        ObjectNode data = mapper.createObjectNode();
        data.put("totalValue", totalValue);
        data.put("totalPnL", totalPnL);
        data.put("dayPnL", dayPnL);
        data.put("cashBalance", cashBalance);
        
        ObjectNode message = createMessage("portfolio_update", data);
        broadcast(message);
    }
    
    private static ObjectNode createMessage(String type, Object data) {
        ObjectNode message = mapper.createObjectNode();
        message.put("type", type);
        message.put("timestamp", System.currentTimeMillis());
        
        if (data instanceof String) {
            message.put("message", (String) data);
        } else if (data instanceof ObjectNode) {
            message.set("data", (ObjectNode) data);
        }
        
        return message;
    }
    
    /**
     * Broadcast message to all connected clients.
     * Modern approach: ReentrantLock instead of synchronized for better performance and control.
     */
    private static void broadcast(ObjectNode message) {
        String json;
        try {
            json = mapper.writeValueAsString(message);
        } catch (Exception e) {
            logger.error("Failed to serialize message", e);
            return;
        }
        
        broadcastLock.lock();
        try {
            sessions.values().forEach(ctx -> sendToSession(ctx, json));
        } finally {
            broadcastLock.unlock();
        }
    }
    
    private static void sendToSession(WsConnectContext ctx, ObjectNode message) {
        try {
            String json = mapper.writeValueAsString(message);
            sendToSession(ctx, json);
        } catch (Exception e) {
            logger.error("Failed to serialize and send message to session {}", ctx.sessionId(), e);
        }
    }
    
    private static void sendToSession(WsConnectContext ctx, String message) {
        try {
            ctx.send(message);
        } catch (Exception e) {
            logger.error("Failed to send message to session {}", ctx.sessionId(), e);
            sessions.remove(ctx.sessionId());
        }
    }
    
    public static int getConnectedClients() {
        return sessions.size();
    }
}
