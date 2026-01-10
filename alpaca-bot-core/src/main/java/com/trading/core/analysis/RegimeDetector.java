package com.trading.core.analysis;

import com.trading.core.config.Config;
import com.trading.core.api.AlpacaClient;
import com.trading.core.model.Bar;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Advanced market regime detection using multiple indicators.
 * Replaces simple VIX threshold with comprehensive market analysis.
 */
public class RegimeDetector {
    private static final Logger logger = LoggerFactory.getLogger(RegimeDetector.class);

    private static final AtomicReference<com.trading.core.model.MarketRegime> currentRegime = 
        new AtomicReference<>(com.trading.core.model.MarketRegime.RANGE_BOUND);
    private static long lastUpdateTime = 0;
    public static com.trading.core.model.MarketRegime getCurrentRegime() {
        if (!Config.isRegimeDetectionEnabled()) {
            return com.trading.core.model.MarketRegime.RANGE_BOUND;
        }
        
        // Check if cache is stale
        int cacheMinutes = Config.getInt("REGIME_UPDATE_INTERVAL_MINUTES", 15);
        long now = System.currentTimeMillis();
        if (now - lastUpdateTime > cacheMinutes * 60 * 1000) {
            updateRegime();
        }
        
        return currentRegime.get();
    }
    
    private static final java.util.concurrent.locks.ReentrantLock lock = new java.util.concurrent.locks.ReentrantLock();
    private static AlpacaClient client;
    
    public static void initialize(AlpacaClient alpacaClient) {
        client = alpacaClient;
    }

    private static void updateRegime() {
        if (client == null) {
            logger.warn("RegimeDetector not initialized with AlpacaClient. Skipping update.");
            return;
        }

        lock.lock();
        try {
            // -------------------------------------------------------------
            // ARCHITECTURAL DECISION: The Regime Detection Triad
            // -------------------------------------------------------------
            // We use a "Holographic" market view based on 3 pillars:
            // 1. TREND (SPY): S&P 500 is the undisputed "Gravity" of the US market. 
            //    - Price > MA200 = Long-term Bullish.
            //    - Price > MA50  = Medium-term Momentum.
            //    - Why SPY? It captures ~80% of US market cap. No other single ticker is as representative.
            //
            // 2. VOLATILITY (VXX): The Fear Gauge.
            //    - We monitor VXX (VIX Futures) to detect panic.
            //    - VIX > 25 usually invalidates technical signals (Bear/High Vol Regime).
            //
            // 3. BREADTH (Sector Strength):
            //    - Even if SPY is up, if Financials (XLF) and Tech (XLK) are down, the rally is fake.
            //    - We scan 5 key sectors. If < 50% are bullish, the market is fragile.
            // -------------------------------------------------------------

            // 1. Fetch SPY Data (Trend)
            var spyBars = client.getMarketHistoryAsync("SPY", 200).join();
            if (spyBars == null || spyBars.size() < 200) {
                logger.warn("Insufficient SPY data for regime detection");
                return;
            }
            
            double spyPrice = spyBars.get(spyBars.size() - 1).close();
            double ma50 = calculateSMA(spyBars, 50);
            double ma200 = calculateSMA(spyBars, 200);
            
            // 2. Fetch VXX Data (Volatility Proxy)
            var vxxBars = client.getMarketHistoryAsync("VXX", 5).join();
            double vix = (vxxBars != null && !vxxBars.isEmpty()) ? vxxBars.get(vxxBars.size() - 1).close() : 20.0;

            // 3. Calculate Market Breadth (Sector ETF Proxy)
            // key sectors: Tech, Finance, Healthcare, Energy, Consumer
            String[] sectors = {"XLK", "XLF", "XLV", "XLE", "XLY"};
            int aboveSma50 = 0;
            int totalSectors = 0;
            
            for (String sector : sectors) {
                var bars = client.getMarketHistoryAsync(sector, 50).join();
                if (bars != null && !bars.isEmpty()) {
                    double price = bars.get(bars.size() - 1).close();
                    double sma = calculateSMA(bars, 50);
                    if (price > sma) aboveSma50++;
                    totalSectors++;
                }
            }
            
            double breadth = totalSectors > 0 ? (double) aboveSma50 / totalSectors : 0.5;
            
            logger.info("📊 Regime Metrics: SPY=${} (MA50=${}, MA200=${}), VXX={}, Breadth={}%", 
                String.format("%.2f", spyPrice), String.format("%.2f", ma50), String.format("%.2f", ma200), 
                String.format("%.2f", vix), String.format("%.0f", breadth * 100));

            com.trading.core.model.MarketRegime newRegime = detectRegime(spyPrice, ma50, ma200, vix, breadth);
            
            com.trading.core.model.MarketRegime oldRegime = currentRegime.getAndSet(newRegime);
            if (oldRegime != newRegime) {
                logger.info("🌍 Market Regime Changed: {} → {}", oldRegime, newRegime);
            }
            
            lastUpdateTime = System.currentTimeMillis();
        } catch (Exception e) {
            logger.error("Regime detection error", e);
        } finally {
            lock.unlock();
        }
    }

    private static double calculateSMA(java.util.List<com.trading.core.model.Bar> bars, int period) {
        if (bars.size() < period) return 0.0;
        double sum = 0;
        for (int i = bars.size() - period; i < bars.size(); i++) {
            sum += bars.get(i).close();
        }
        return sum / period;
    }
    
    /**
     * Detect regime based on multiple factors.
     */
    private static com.trading.core.model.MarketRegime detectRegime(
            double price, double ma50, double ma200, double vix, double breadth) {
        
        double vixThreshold = Config.getVixThreshold();
        double breadthThreshold = Config.getDouble("REGIME_BREADTH_THRESHOLD", 0.6);
        
        // High volatility overrides other signals
        if (vix > vixThreshold + 5) {
            return com.trading.core.model.MarketRegime.HIGH_VOLATILITY;
        }
        
        // MA crossover signals
        boolean ma50AboveMa200 = ma50 > ma200;
        boolean priceAboveMa50 = price > ma50;
        boolean priceAboveMa200 = price > ma200;
        
        // Bullish Analysis
        if (ma50AboveMa200 && priceAboveMa50) {
            if (breadth > breadthThreshold) return com.trading.core.model.MarketRegime.STRONG_BULL;
            return com.trading.core.model.MarketRegime.WEAK_BULL;
        }
        
        // Bearish Analysis
        if (!ma50AboveMa200 && !priceAboveMa200) {
             if (breadth < (1 - breadthThreshold)) return com.trading.core.model.MarketRegime.STRONG_BEAR;
             return com.trading.core.model.MarketRegime.WEAK_BEAR;
        }
        
        // Default: Range bound
        return com.trading.core.model.MarketRegime.RANGE_BOUND;
    }
    
    /**
     * Get symbols appropriate for current regime.
     */
    public static java.util.List<String> getRegimeSymbols() {
        com.trading.core.model.MarketRegime regime = getCurrentRegime();
        
        return switch (regime) {
            case STRONG_BULL, WEAK_BULL -> Config.getMainBullishSymbols();
            case STRONG_BEAR, WEAK_BEAR -> Config.getMainBearishSymbols();
            case HIGH_VOLATILITY -> Config.getExperimentalBearishSymbols(); // Defensive
            default -> Config.getMainBullishSymbols(); // Default to main
        };
    }
    
    /**
     * Force regime update (for testing or manual override).
     */
    public static void forceUpdate() {
        lastUpdateTime = 0;
        updateRegime();
    }
}
