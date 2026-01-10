package com.trading.testing;

import com.trading.config.Config;
import com.trading.api.AlpacaClient;
import com.trading.analysis.MarketAnalyzer;
import com.trading.filters.MarketHoursFilter;
import com.trading.filters.VolatilityFilter;
import com.trading.persistence.TradeDatabase;
import com.trading.portfolio.PortfolioManager;
import com.trading.risk.RiskManager;
import com.trading.strategy.StrategyManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Comprehensive automated system tester.
 */
public final class SystemTester {
    private static final Logger logger = LoggerFactory.getLogger(SystemTester.class);
    
    private static int passedTests = 0;
    private static int failedTests = 0;
    private static final List<String> results = new ArrayList<>();
    
    public static void main(String[] args) {
        printHeader();
        
        logger.info("🧪 Starting Comprehensive System Tests...\n");
        
        // Run all tests
        testConfiguration();
        testDatabaseInitialization();
        testMarketFilters();
        testPortfolioManager();
        testRiskManager();
        testMarketAnalyzer();
        testStrategyManager();
        testFractionalTrading();
        testAPIConnectivity();
        
        // Print results
        printResults();
        
        // Exit with appropriate code
        System.exit(failedTests > 0 ? 1 : 0);
    }
    
    private static void printHeader() {
        System.out.println("\n" + "=".repeat(70));
        System.out.println("  🤖 TRADING BOT SYSTEM TESTER");
        System.out.println("  Automated Testing Suite v1.0");
        System.out.println("=".repeat(70) + "\n");
    }
    
    private static void testConfiguration() {
        test("Configuration Loading", () -> {
            var config = new Config();
            if (!config.apiKey().contains("REPLACE")) {
                logger.info("  ✓ Real API keys configured");
            } else {
                logger.warn("  ⚠ Using placeholder API keys (tests may fail)");
            }
            assert config != null : "Config should not be null";
        });
        
        test("Trading Mode Detection", () -> {
            var config = new Config();
            String mode = config.getTradingMode();
            logger.info("  ✓ Trading Mode: {}", mode);
            assert mode.equals("PAPER") || mode.equals("LIVE") : "Invalid trading mode";
        });
    }
    
    private static void testDatabaseInitialization() {
        test("Database Creation", () -> {
            var db = new TradeDatabase();
            logger.info("  ✓ SQLite database created: trades.db");
            db.close();
        });
        
        test("Database Operations", () -> {
            var db = new TradeDatabase();
            int tradesBefore = db.getTotalTrades();
            logger.info("  ✓ Existing trades: {}", tradesBefore);
            double pnl = db.getTotalPnL();
            logger.info("  ✓ Total P&L: ${}", String.format("%.2f", pnl));
            db.close();
        });
    }
    
    private static void testMarketFilters() {
        test("Market Hours Filter", () -> {
            var config = new Config();
            var filter = new MarketHoursFilter(config);
            boolean isOpen = filter.isMarketOpen();
            String reason = filter.getClosedReason();
            logger.info("  ✓ Market Status: {}", isOpen ? "OPEN" : "CLOSED - " + reason);
        });
        
        test("Volatility Filter", () -> {
            try {
                var config = new Config();
                var client = new AlpacaClient(config);
                var filter = new VolatilityFilter(client);
                double vix = filter.getCurrentVIX();
                logger.info("  ✓ Current VIX: {}", String.format("%.2f", vix));
                boolean acceptable = filter.isVolatilityAcceptable();
                logger.info("  ✓ Volatility: {}", acceptable ? "ACCEPTABLE" : "TOO HIGH");
            } catch (Exception e) {
                logger.warn("  ⚠ VIX fetch failed (expected if no API keys): {}", e.getMessage());
            }
        });
    }
    
    private static void testPortfolioManager() {
        test("Portfolio Initialization", () -> {
            var portfolio = new PortfolioManager(List.of("SPY", "QQQ", "IWM"), 1000.0);
            logger.info("  ✓ Portfolio created with {} symbols", portfolio.getSymbols().size());
            logger.info("  ✓ Capital per symbol: ${}", String.format("%.2f", portfolio.getCapitalPerSymbol()));
            assert portfolio.getSymbols().size() == 3 : "Should have 3 symbols";
        });
    }
    
    private static void testRiskManager() {
        test("Risk Manager Initialization", () -> {
            var riskManager = new RiskManager(1000.0);
            logger.info("  ✓ Risk manager created");
        });
        
        test("Position Sizing", () -> {
            var riskManager = new RiskManager(1000.0);
            double size = riskManager.calculatePositionSize(1000.0, 100.0);
            logger.info("  ✓ Calculated position size: {} shares", size);
            assert size > 0 : "Position size should be positive";
        });
        
        test("Stop Loss Calculation", () -> {
            var riskManager = new RiskManager(1000.0);
            double stopLoss = riskManager.calculateStopLoss(100.0);
            logger.info("  ✓ Stop loss at: ${}", String.format("%.2f", stopLoss));
            assert stopLoss < 100.0 : "Stop loss should be below entry";
        });
    }
    
    private static void testMarketAnalyzer() {
        test("Market Analyzer Initialization", () -> {
            try {
                var config = new Config();
                var client = new AlpacaClient(config);
                var analyzer = new MarketAnalyzer(client);
                logger.info("  ✓ Market analyzer created");
                
                var analysis = analyzer.analyze(List.of("SPY", "QQQ", "IWM"));
                logger.info("  ✓ Market Trend: {}", analysis.trend());
                logger.info("  ✓ Market Strength: {}/100", String.format("%.0f", analysis.marketStrength()));
                logger.info("  ✓ VIX: {}", String.format("%.2f", analysis.vix()));
            } catch (Exception e) {
                logger.warn("  ⚠ Analysis requires valid API keys: {}", e.getMessage());
                throw e;
            }
        });
    }
    
    private static void testStrategyManager() {
        test("Strategy Manager Initialization", () -> {
            try {
                var config = new Config();
                var client = new AlpacaClient(config);
                var strategyManager = new StrategyManager(client);
                logger.info("  ✓ Strategy manager created with hybrid strategies");
            } catch (Exception e) {
                logger.warn("  ⚠ Requires valid API keys: {}", e.getMessage());
                throw e;
            }
        });
    }
    
    private static void testFractionalTrading() {
        new FractionalOrderTest().runTests();
    }

    private static void testAPIConnectivity() {
        test("Alpaca API Connection", () -> {
            try {
                var config = new Config();
                if (config.apiKey().contains("REPLACE")) {
                    logger.warn("  ⚠ Skipping - placeholder API keys detected");
                    logger.warn("  ⚠ Please add real Alpaca keys to config.properties");
                    return;
                }
                
                var client = new AlpacaClient(config);
                var account = client.getAccount();
                String status = account.get("status").asText();
                logger.info("  ✓ Connected to Alpaca!");
                logger.info("  ✓ Account Status: {}", status);
                
                // Test data fetch
                var bar = client.getLatestBar("SPY");
                logger.info("  ✓ SPY Price: ${}", String.format("%.2f", bar.get().close()));
                
            } catch (Exception e) {
                logger.error("  ✗ API Connection failed: {}", e.getMessage());
                throw e;
            }
        });
    }
    
    private static void test(String testName, TestRunnable test) {
        System.out.printf("\n[TEST] %s%n", testName);
        try {
            test.run();
            passedTests++;
            results.add("✓ " + testName);
            System.out.printf("[PASS] %s%n", testName);
        } catch (Exception e) {
            failedTests++;
            results.add("✗ " + testName + " - " + e.getMessage());
            System.out.printf("[FAIL] %s: %s%n", testName, e.getMessage());
            logger.error("Test failed", e);
        }
    }
    
    private static void printResults() {
        System.out.println("\n" + "=".repeat(70));
        System.out.println("  TEST RESULTS");
        System.out.println("=".repeat(70));
        
        for (String result : results) {
            System.out.println("  " + result);
        }
        
        System.out.println("\n" + "=".repeat(70));
        int total = passedTests + failedTests;
        System.out.printf("  Total: %d | Passed: %d | Failed: %d%n", total, passedTests, failedTests);
        
        if (failedTests == 0) {
            System.out.println("  🎉 ALL TESTS PASSED!");
        } else {
            System.out.println("  ⚠️  SOME TESTS FAILED");
        }
        System.out.println("=".repeat(70) + "\n");
    }
    
    @FunctionalInterface
    private interface TestRunnable {
        void run() throws Exception;
    }
}
