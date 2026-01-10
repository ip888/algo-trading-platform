package com.trading.core.analysis;

import com.trading.core.api.AlpacaClient;
import com.trading.core.model.Bar;
import com.trading.core.model.MarketRegime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Stateless Market Regime Detection Service.
 */
public class MarketRegimeService {
    private static final Logger logger = LoggerFactory.getLogger(MarketRegimeService.class);
    private final AlpacaClient client;

    public MarketRegimeService(AlpacaClient client) {
        this.client = client;
    }

    public MarketRegime detectRegime() {
        try {
            // 1. Get VIX (Volatility)
            double vix = getVIX();

            // 2. High Volatility Check
            if (vix > 30.0) {
                return MarketRegime.HIGH_VOLATILITY;
            }

            // 3. Analyze Trend (SPY)
            var trend = analyzeTrend("SPY");

            // 4. Combine Signals
            if (trend == TrendDirection.UP) {
                return (vix < 20) ? MarketRegime.STRONG_BULL : MarketRegime.WEAK_BULL;
            } else if (trend == TrendDirection.DOWN) {
                return (vix > 25) ? MarketRegime.STRONG_BEAR : MarketRegime.WEAK_BEAR;
            } else {
                return MarketRegime.RANGE_BOUND;
            }

        } catch (Exception e) {
            logger.error("Error detecting regime", e);
            return MarketRegime.RANGE_BOUND; // Default safety
        }
    }

    private double getVIX() {
        try {
            return client.getLatestBarAsync("VIXY").join().close(); // Proxy
        } catch (Exception e) {
            logger.warn("Failed to get VIXY, defaulting to 20.0");
            return 20.0;
        }
    }

    private enum TrendDirection { UP, DOWN, SIDEWAYS }

    private TrendDirection analyzeTrend(String symbol) {
        try {
            // Fetch 200 days history for 200 SMA
            List<Bar> history = client.getMarketHistoryAsync(symbol, 200).join();
            if (history.size() < 200) return TrendDirection.SIDEWAYS;

            double currentPrice = history.get(history.size() - 1).close();
            double sma50 = calculateSMA(history, 50);
            double sma200 = calculateSMA(history, 200);

            if (currentPrice > sma50 && sma50 > sma200) return TrendDirection.UP;
            if (currentPrice < sma50 && sma50 < sma200) return TrendDirection.DOWN;
            return TrendDirection.SIDEWAYS;

        } catch (Exception e) {
            logger.error("Trend analysis failed", e);
            return TrendDirection.SIDEWAYS;
        }
    }

    private double calculateSMA(List<Bar> bars, int period) {
        return bars.stream()
            .skip(Math.max(0, bars.size() - period))
            .mapToDouble(Bar::close)
            .average()
            .orElse(0.0);
    }
}
