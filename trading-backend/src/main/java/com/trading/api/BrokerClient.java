package com.trading.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.trading.api.model.Bar;
import com.trading.api.model.BracketOrderResult;
import com.trading.api.model.Position;

import java.util.List;
import java.util.Optional;

/**
 * Broker-agnostic interface for market data and order execution.
 * Implemented by AlpacaClient (and future brokers such as Tradier, IBKR).
 * ResilientBrokerClient wraps any BrokerClient with resilience patterns.
 */
public interface BrokerClient {

    JsonNode getAccount() throws Exception;

    boolean validateAccountForTrading();

    JsonNode getClock() throws Exception;

    Optional<Position> getPosition(String symbol);

    List<Position> getPositions() throws Exception;

    Optional<Bar> getLatestBar(String symbol);

    JsonNode getOpenOrders(String symbol);

    JsonNode getNews(String symbol, int limit);

    JsonNode getRecentOrders(String symbol);

    JsonNode getOrderHistory(String symbol, int limit);

    JsonNode getAccountActivities(String activityType, int limit);

    void cancelOrder(String orderId);

    void cancelAllOrders();

    void placeOrder(String symbol, double qty, String side, String type,
                    String timeInForce, Double limitPrice);

    void replaceOrder(String orderId, Double qty, Double limitPrice, Double stopPrice);

    void placeNativeStopOrder(String symbol, double qty, double stopPrice) throws Exception;

    void placeTrailingStopOrder(String symbol, double qty, String side, double trailPercent);

    BracketOrderResult placeBracketOrder(String symbol, double qty, String side,
                                         double takeProfitPrice, double stopLossPrice,
                                         Double stopLossLimitPrice, Double limitPrice);

    String placeBracketOrder(String symbol, double qty, String side,
                             double takeProfitPrice, double stopLossPrice,
                             Double stopLossLimitPrice) throws Exception;

    List<Bar> getBars(String symbol, String timeframe, int limit) throws Exception;

    List<Bar> getMarketHistory(String symbol, int limit) throws Exception;
}
