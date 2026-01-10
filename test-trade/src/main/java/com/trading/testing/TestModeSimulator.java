package com.trading.testing;

import com.trading.strategy.TradingSignal;
import com.trading.websocket.TradingWebSocketHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Test mode simulator that generates realistic simulated trades for demonstration.
 * This allows testing the full trading pipeline without placing real orders.
 */
public final class TestModeSimulator {
    private static final Logger logger = LoggerFactory.getLogger(TestModeSimulator.class);
    private final Random random = new Random();
    private final Map<String, Integer> cycleCounters = new HashMap<>();
    private final int testFrequency;
    
    public TestModeSimulator(int testFrequencySeconds) {
        this.testFrequency = testFrequencySeconds / 10; // Convert to cycles (10s per cycle)
        logger.info("Test Mode Simulator initialized (will generate test trades every {} cycles)", testFrequency);
    }
    
    /**
     * Evaluate if a test trade should be generated for this symbol.
     * Uses actual market data to make realistic decisions.
     */
    public TradingSignal evaluateTestSignal(String symbol, double currentPrice, double positionQty, 
                                           double rsi, double macd, double signal) {
        // Increment cycle counter for this symbol
        int cycles = cycleCounters.getOrDefault(symbol, 0) + 1;
        cycleCounters.put(symbol, cycles);
        
        // Only generate test trades at the specified frequency
        if (cycles % testFrequency != 0) {
            return null; // No test signal this cycle
        }
        
        // Reset counter
        cycleCounters.put(symbol, 0);
        
        // Generate realistic test signal based on actual market conditions
        // Use relaxed thresholds to ensure trades happen
        
        if (positionQty == 0) {
            // Look for buy opportunities with relaxed thresholds
            if (rsi < 45 || (macd > signal && macd > -1.0)) {
                String reason = String.format("[TEST] Simulated BUY (RSI=%.1f, MACD=%.2f)", rsi, macd);
                TradingWebSocketHandler.broadcastActivity(reason, "SUCCESS");
                logger.info("{}: {}", symbol, reason);
                return new TradingSignal.Buy(reason);
            }
        } else {
            // Look for sell opportunities with relaxed thresholds
            if (rsi > 55 || (macd < signal && macd < 1.0)) {
                String reason = String.format("[TEST] Simulated SELL (RSI=%.1f, MACD=%.2f)", rsi, macd);
                TradingWebSocketHandler.broadcastActivity(reason, "SUCCESS");
                logger.info("{}: {}", symbol, reason);
                return new TradingSignal.Sell(reason);
            }
        }
        
        // If conditions aren't met, occasionally force a trade for demonstration
        if (random.nextDouble() < 0.3) { // 30% chance
            if (positionQty == 0) {
                String reason = "[TEST] Simulated BUY (random demonstration trade)";
                TradingWebSocketHandler.broadcastActivity(reason, "SUCCESS");
                logger.info("{}: {}", symbol, reason);
                return new TradingSignal.Buy(reason);
            } else {
                String reason = "[TEST] Simulated SELL (random demonstration trade)";
                TradingWebSocketHandler.broadcastActivity(reason, "SUCCESS");
                logger.info("{}: {}", symbol, reason);
                return new TradingSignal.Sell(reason);
            }
        }
        
        return null; // No test signal
    }
    
    /**
     * Calculate RSI from price history (simplified version for test mode).
     */
    public double calculateTestRSI(List<Double> prices) {
        if (prices.size() < 14) {
            return 50.0; // Neutral
        }
        
        double avgGain = 0.0;
        double avgLoss = 0.0;
        
        for (int i = 1; i <= 14; i++) {
            double change = prices.get(prices.size() - i) - prices.get(prices.size() - i - 1);
            if (change > 0) {
                avgGain += change;
            } else {
                avgLoss += Math.abs(change);
            }
        }
        
        avgGain /= 14;
        avgLoss /= 14;
        
        if (avgLoss == 0) {
            return 100.0;
        }
        
        double rs = avgGain / avgLoss;
        return 100.0 - (100.0 / (1.0 + rs));
    }
}
