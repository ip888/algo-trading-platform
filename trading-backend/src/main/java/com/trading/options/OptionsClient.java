package com.trading.options;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trading.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Alpaca Options API Client
 * Fetches options chains and places options orders
 */
public final class OptionsClient {
    private static final Logger logger = LoggerFactory.getLogger(OptionsClient.class);
    private static final String OPTIONS_API_URL = "https://api.alpaca.markets/v2/options";
    private static final String OPTIONS_DATA_URL = "https://data.alpaca.markets/v1beta1/options";
    private static final ObjectMapper objectMapper = new ObjectMapper();
    
    private final HttpClient httpClient;
    private final String apiKey;
    private final String secretKey;
    
    public OptionsClient(Config config) {
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
        this.apiKey = config.apiKey();
        this.secretKey = config.apiSecret();
    }
    
    /**
     * Option contract record
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OptionContract(
        @JsonProperty("id") String id,
        @JsonProperty("symbol") String symbol,
        @JsonProperty("name") String name,
        @JsonProperty("status") String status,
        @JsonProperty("tradable") boolean tradable,
        @JsonProperty("underlying_symbol") String underlyingSymbol,
        @JsonProperty("underlying_asset_id") String underlyingAssetId,
        @JsonProperty("type") String type, // call or put
        @JsonProperty("style") String style, // american or european
        @JsonProperty("strike_price") double strikePrice,
        @JsonProperty("size") int size, // typically 100 shares
        @JsonProperty("expiration_date") String expirationDate,
        @JsonProperty("open_interest") int openInterest,
        @JsonProperty("close_price") double closePrice
    ) {}
    
    /**
     * Option Greeks from snapshot
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OptionGreeks(
        @JsonProperty("delta") double delta,
        @JsonProperty("gamma") double gamma,
        @JsonProperty("theta") double theta,
        @JsonProperty("vega") double vega,
        @JsonProperty("rho") double rho
    ) {}
    
    /**
     * Get options chain for a symbol
     */
    public List<OptionContract> getOptionsChain(String underlyingSymbol, 
                                                  LocalDate expirationDateGte,
                                                  LocalDate expirationDateLte,
                                                  String type) throws Exception {
        logger.info("Fetching options chain for {} ({}-{})", 
            underlyingSymbol, expirationDateGte, expirationDateLte);
        
        var url = String.format("%s/contracts?underlying_symbols=%s&expiration_date_gte=%s&expiration_date_lte=%s&type=%s&status=active&limit=100",
            OPTIONS_API_URL,
            underlyingSymbol,
            expirationDateGte.format(DateTimeFormatter.ISO_LOCAL_DATE),
            expirationDateLte.format(DateTimeFormatter.ISO_LOCAL_DATE),
            type);
        
        var response = sendRequest(url, "GET", null);
        var root = objectMapper.readTree(response);
        
        var contracts = new ArrayList<OptionContract>();
        var contractsArray = root.get("option_contracts");
        
        if (contractsArray != null && contractsArray.isArray()) {
            for (var node : contractsArray) {
                contracts.add(objectMapper.treeToValue(node, OptionContract.class));
            }
        }
        
        logger.info("Found {} {} contracts for {}", contracts.size(), type, underlyingSymbol);
        return contracts;
    }
    
    /**
     * Find ATM (at-the-money) options near current price
     */
    public OptionContract findATMOption(String symbol, double currentPrice, 
                                          LocalDate expiration, String type) throws Exception {
        var expirationLte = expiration.plusDays(7); // Allow some flexibility
        var contracts = getOptionsChain(symbol, expiration, expirationLte, type);
        
        if (contracts.isEmpty()) {
            logger.warn("No {} contracts found for {} near {}", type, symbol, expiration);
            return null;
        }
        
        // Find strike closest to current price
        return contracts.stream()
            .min((a, b) -> Double.compare(
                Math.abs(a.strikePrice() - currentPrice),
                Math.abs(b.strikePrice() - currentPrice)))
            .orElse(null);
    }
    
    /**
     * Place options order
     */
    public JsonNode placeOptionsOrder(String contractSymbol, int qty, 
                                       String side, String type, 
                                       Double limitPrice) throws Exception {
        logger.info("Placing {} order: {} x{} @ {}", 
            side, contractSymbol, qty, limitPrice != null ? limitPrice : "market");
        
        var orderRequest = new StringBuilder();
        orderRequest.append("{");
        orderRequest.append("\"symbol\":\"").append(contractSymbol).append("\",");
        orderRequest.append("\"qty\":").append(qty).append(",");
        orderRequest.append("\"side\":\"").append(side).append("\",");
        orderRequest.append("\"type\":\"").append(type).append("\",");
        orderRequest.append("\"time_in_force\":\"day\"");
        
        if (limitPrice != null && type.equals("limit")) {
            orderRequest.append(",\"limit_price\":").append(limitPrice);
        }
        orderRequest.append("}");
        
        var response = sendRequest("https://api.alpaca.markets/v2/orders", 
            "POST", orderRequest.toString());
        return objectMapper.readTree(response);
    }
    
    /**
     * Execute long call strategy
     * Bullish - profit if stock goes up
     */
    public JsonNode executeLongCall(String symbol, double currentPrice, 
                                      LocalDate expiration, double maxCost) throws Exception {
        logger.info("Executing LONG CALL on {} (max cost: ${})", symbol, maxCost);
        
        var call = findATMOption(symbol, currentPrice, expiration, "call");
        if (call == null) {
            throw new RuntimeException("No suitable call option found for " + symbol);
        }
        
        double optionCost = call.closePrice() * call.size();
        if (optionCost > maxCost) {
            logger.warn("Option cost ${} exceeds max ${}", optionCost, maxCost);
            return null;
        }
        
        return placeOptionsOrder(call.symbol(), 1, "buy", "market", null);
    }
    
    /**
     * Execute long put strategy
     * Bearish - profit if stock goes down
     */
    public JsonNode executeLongPut(String symbol, double currentPrice, 
                                     LocalDate expiration, double maxCost) throws Exception {
        logger.info("Executing LONG PUT on {} (max cost: ${})", symbol, maxCost);
        
        var put = findATMOption(symbol, currentPrice, expiration, "put");
        if (put == null) {
            throw new RuntimeException("No suitable put option found for " + symbol);
        }
        
        double optionCost = put.closePrice() * put.size();
        if (optionCost > maxCost) {
            logger.warn("Option cost ${} exceeds max ${}", optionCost, maxCost);
            return null;
        }
        
        return placeOptionsOrder(put.symbol(), 1, "buy", "market", null);
    }
    
    /**
     * Execute long straddle strategy
     * Neutral - profit from large moves in either direction (high VIX environment)
     */
    public void executeLongStraddle(String symbol, double currentPrice, 
                                      LocalDate expiration, double maxCost) throws Exception {
        logger.info("Executing LONG STRADDLE on {} (max cost: ${})", symbol, maxCost);
        
        var call = findATMOption(symbol, currentPrice, expiration, "call");
        var put = findATMOption(symbol, currentPrice, expiration, "put");
        
        if (call == null || put == null) {
            throw new RuntimeException("Could not find both call and put for straddle on " + symbol);
        }
        
        double totalCost = (call.closePrice() + put.closePrice()) * 100;
        if (totalCost > maxCost) {
            logger.warn("Straddle cost ${} exceeds max ${}", totalCost, maxCost);
            return;
        }
        
        // Buy both legs
        placeOptionsOrder(call.symbol(), 1, "buy", "market", null);
        placeOptionsOrder(put.symbol(), 1, "buy", "market", null);
        
        logger.info("Long straddle placed: {} @ ${} strike", symbol, call.strikePrice());
    }
    
    private String sendRequest(String url, String method, String body) throws Exception {
        var requestBuilder = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("APCA-API-KEY-ID", apiKey)
            .header("APCA-API-SECRET-KEY", secretKey)
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(30));
        
        if (method.equals("POST") && body != null) {
            requestBuilder.POST(HttpRequest.BodyPublishers.ofString(body));
        } else {
            requestBuilder.GET();
        }
        
        var response = httpClient.send(requestBuilder.build(), 
            HttpResponse.BodyHandlers.ofString());
        
        if (response.statusCode() >= 400) {
            throw new RuntimeException("Options API error " + response.statusCode() + ": " + response.body());
        }
        
        return response.body();
    }
}
