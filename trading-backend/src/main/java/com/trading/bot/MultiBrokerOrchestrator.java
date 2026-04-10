package com.trading.bot;

import com.trading.ai.AnomalyDetector;
import com.trading.ai.RiskPredictor;
import com.trading.ai.SentimentAnalyzer;
import com.trading.ai.SignalPredictor;
import com.trading.analysis.MarketAnalyzer;
import com.trading.api.*;
import com.trading.autonomous.ConfigSelfHealer;
import com.trading.autonomous.ErrorDetector;
import com.trading.config.Config;
import com.trading.dashboard.DashboardServer;
import com.trading.filters.MarketHoursFilter;
import com.trading.filters.VolatilityFilter;
import com.trading.metrics.MetricsService;
import com.trading.persistence.TradeDatabase;
import com.trading.portfolio.ProfileManager;
import com.trading.protection.PDTProtection;
import com.trading.strategy.StrategyManager;
import com.trading.strategy.TradingProfile;
import com.trading.testing.TestModeSimulator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs the trading bot on multiple broker accounts simultaneously.
 *
 * <p>Activation: set the {@code BROKERS} environment variable to a comma-separated list of
 * {@code broker:percent} pairs, e.g.:
 * <pre>
 *   BROKERS=alpaca:80,tradier:100,tradovate:100
 * </pre>
 *
 * <p><strong>Capital isolation</strong>: each broker is completely independent.
 * The percentage is the fraction of <em>that broker's own account balance</em> to allocate
 * to the bot (e.g. {@code alpaca:80} = use 80% of your Alpaca account equity).
 * Capital is fetched from each broker's own {@link BrokerClient#getAccount()} — Alpaca's
 * balance is never used for another broker.
 *
 * <p>Each broker gets its own {@link ProfileManager} running in a dedicated virtual thread
 * with isolated positions, orders, and risk limits.
 *
 * <p>Market data (bars, signals) always comes from Alpaca because Tradovate has no REST bar
 * endpoint and Tradier bars are optional. Order <em>execution</em> goes to each broker's
 * own account.
 *
 * <p>Supported broker names (case-insensitive): {@code alpaca}, {@code tradier},
 * {@code tradovate}, {@code ibkr}.
 */
public final class MultiBrokerOrchestrator {
    private static final Logger logger = LoggerFactory.getLogger(MultiBrokerOrchestrator.class);

    private static final double MIN_CAPITAL_PER_BROKER = 100.0;

    private final Config config;

    public MultiBrokerOrchestrator(Config config) {
        this.config = config;
    }

    // ── Public entry point ────────────────────────────────────────────────────

    public void run() {
        Map<String, Double> allocation = parseAllocation(config.getBrokersAllocation());
        if (allocation.isEmpty()) {
            logger.error("MultiBrokerOrchestrator: BROKERS env var is empty or invalid. "
                + "Expected format: alpaca:80,tradier:100,tradovate:100");
            System.exit(1);
        }

        // Alpaca is always the market-data source for signal generation
        AlpacaClient alpacaDataClient = new AlpacaClient(config);

        // Shared signal/data components — all read-only, safe to share across broker threads
        var multiTimeframeAnalyzer = config.isMultiTimeframeEnabled()
            ? new com.trading.analysis.MultiTimeframeAnalyzer(alpacaDataClient, config) : null;
        var strategyManager    = new StrategyManager(alpacaDataClient, multiTimeframeAnalyzer, config);
        var marketAnalyzer     = new MarketAnalyzer(alpacaDataClient);
        var marketHoursFilter  = new MarketHoursFilter(config);
        var volatilityFilter   = new VolatilityFilter(alpacaDataClient);
        var database           = new TradeDatabase();
        var pdtProtection      = new PDTProtection(database, config.isPDTProtectionEnabled());
        var errorDetector      = new ErrorDetector();
        var configSelfHealer   = new ConfigSelfHealer(config, errorDetector);
        var alphaVantageClient = new com.trading.ai.AlphaVantageClient(
            config.getAlphaVantageApiKey(), config.isAlphaVantageEnabled(),
            config.getAlphaVantageCacheTTL(), config.getAlphaVantageNewsLimit(),
            config.getAlphaVantageMinRelevance());
        var finGPTClient       = new com.trading.ai.FinGPTClient(
            config.getHuggingFaceApiToken(), config.getFinGPTSentimentModel(),
            config.isFinGPTEnabled(), config.getFinGPTCacheTTL());
        var sentimentAnalyzer  = new SentimentAnalyzer(alpacaDataClient, alphaVantageClient, finGPTClient);
        var signalPredictor    = new SignalPredictor(config);
        var anomalyDetector    = new AnomalyDetector();
        var riskPredictor      = new RiskPredictor();

        TestModeSimulator testSimulator = config.isTestModeEnabled()
            ? new TestModeSimulator(config.getTestModeFrequency()) : null;

        // Build a fully isolated ProfileManager per broker
        List<BrokerEntry> entries = new ArrayList<>();
        int profileIndex = 0;
        for (Map.Entry<String, Double> e : allocation.entrySet()) {
            String brokerName  = e.getKey();
            double pctOfBroker = e.getValue();  // % of THIS broker's own balance
            double fraction    = Math.min(pctOfBroker, 100.0) / 100.0;

            // Create the broker's own execution client
            BrokerClient rawClient;
            try {
                rawClient = createBrokerClient(brokerName);
            } catch (Exception ex) {
                logger.error("MultiBrokerOrchestrator: failed to create client for {} — {}. Skipping.",
                    brokerName, ex.getMessage());
                continue;
            }

            // Fetch capital from THIS broker's own account — completely independent
            double brokerCapital = fetchBrokerCapital(rawClient, brokerName);
            double capital       = brokerCapital * fraction;

            if (capital < MIN_CAPITAL_PER_BROKER) {
                logger.warn("MultiBrokerOrchestrator: skipping {} — allocated capital ${} < minimum ${} "
                    + "(broker balance=${}, using {}%)",
                    brokerName,
                    String.format("%.2f", capital),
                    MIN_CAPITAL_PER_BROKER,
                    String.format("%.2f", brokerCapital),
                    String.format("%.0f", pctOfBroker));
                continue;
            }

            var resilient = new ResilientBrokerClient(rawClient,
                MetricsService.getInstance().getRegistry());

            // Name the profile after the broker so logs are unambiguous
            TradingProfile base = profileIndex == 0
                ? TradingProfile.main(config)
                : TradingProfile.experimental(config);
            TradingProfile profile = new TradingProfile(
                brokerName.toUpperCase(),
                base.capitalPercent(), base.takeProfitPercent(), base.stopLossPercent(),
                base.trailingStopPercent(), base.bullishSymbols(), base.bearishSymbols(),
                base.vixThreshold(), base.vixHysteresis(), base.strategyType(),
                base.minHoldTime(), base.maxHoldTime()
            );

            var manager = new ProfileManager(
                profile, capital, resilient, strategyManager,
                marketHoursFilter, volatilityFilter, marketAnalyzer,
                database, pdtProtection, config, testSimulator,
                sentimentAnalyzer, signalPredictor, anomalyDetector, riskPredictor,
                errorDetector, configSelfHealer, brokerName
            );
            entries.add(new BrokerEntry(brokerName, manager, rawClient));
            profileIndex++;

            logger.info("MultiBrokerOrchestrator: [{}] own balance=${} → using {}% = ${}",
                brokerName.toUpperCase(),
                String.format("%.2f", brokerCapital),
                String.format("%.0f", pctOfBroker),
                String.format("%.2f", capital));
        }

        if (entries.isEmpty()) {
            logger.error("MultiBrokerOrchestrator: no valid broker entries — aborting.");
            System.exit(1);
        }

        // Dashboard — uses Alpaca as the health-check broker; shows first profile's portfolio
        var alpacaResilient = new ResilientBrokerClient(alpacaDataClient,
            MetricsService.getInstance().getRegistry());
        var dashboard = new DashboardServer(database,
            entries.get(0).manager().getPortfolio(),
            marketAnalyzer, marketHoursFilter, volatilityFilter, config, alpacaResilient);
        dashboard.start();
        logger.info("Dashboard available at: http://localhost:8080");

        // Shutdown hook — stops all broker managers gracefully
        Runtime.getRuntime().addShutdownHook(Thread.ofVirtual().unstarted(() -> {
            logger.info("MultiBrokerOrchestrator: shutdown signal — stopping all brokers");
            entries.forEach(en -> en.manager().stop());
        }));

        // Launch a virtual thread per broker, staggered by 10 s each to avoid API collisions
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            final BrokerEntry entry = entries.get(i);
            final long delayMs = (long) i * 10_000;
            Thread t = Thread.ofVirtual()
                .name("broker-" + entry.brokerName())
                .start(() -> {
                    if (delayMs > 0) {
                        try {
                            logger.info("MultiBrokerOrchestrator: {} starting in {}s…",
                                entry.brokerName(), delayMs / 1000);
                            Thread.sleep(delayMs);
                        } catch (InterruptedException ex) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }
                    logger.info("MultiBrokerOrchestrator: [{}] trading thread started", entry.brokerName());
                    entry.manager().run();
                });
            threads.add(t);
        }

        for (Thread t : threads) {
            try {
                t.join();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
        logger.info("MultiBrokerOrchestrator: all broker threads exited");
    }

    // ── BrokerClient factory ──────────────────────────────────────────────────

    private BrokerClient createBrokerClient(String brokerName) {
        return switch (brokerName.toLowerCase()) {
            case "tradier"   -> new TradierClient(config);
            case "tradovate" -> new TradovateClient(config);
            case "ibkr"      -> new IBKRClient(config);
            default          -> new AlpacaClient(config);   // "alpaca" or unknown
        };
    }

    // ── Capital helpers ───────────────────────────────────────────────────────

    /**
     * Fetches the account equity from a specific broker's own account.
     * Falls back to the config initial capital if the API call fails.
     * Capital is NEVER shared or borrowed from another broker's account.
     */
    static double fetchBrokerCapital(BrokerClient client, String brokerName) {
        try {
            var account = client.getAccount();
            double equity = account.path("equity").asDouble(0);
            if (equity > 0) {
                logger.info("MultiBrokerOrchestrator: [{}] live account equity = ${}",
                    brokerName, String.format("%.2f", equity));
                return equity;
            }
            // Some brokers report cash rather than equity
            double cash = account.path("cash").asDouble(0);
            if (cash > 0) {
                logger.info("MultiBrokerOrchestrator: [{}] live cash balance = ${}",
                    brokerName, String.format("%.2f", cash));
                return cash;
            }
        } catch (Exception e) {
            logger.warn("MultiBrokerOrchestrator: [{}] could not fetch account balance: {}",
                brokerName, e.getMessage());
        }
        // Last resort: use configured initial capital per broker
        double fallback = getConfigCapital(brokerName);
        logger.warn("MultiBrokerOrchestrator: [{}] using config fallback capital = ${}", brokerName, fallback);
        return fallback;
    }

    /**
     * Returns a broker-specific initial capital override from config/env,
     * falling back to the global INITIAL_CAPITAL.
     *
     * Env vars checked (in order):
     *   ALPACA_INITIAL_CAPITAL, TRADIER_INITIAL_CAPITAL, TRADOVATE_INITIAL_CAPITAL,
     *   IBKR_INITIAL_CAPITAL, then INITIAL_CAPITAL.
     */
    private static double getConfigCapital(String brokerName) {
        String envKey = brokerName.toUpperCase() + "_INITIAL_CAPITAL";
        String val = System.getenv(envKey);
        if (val != null && !val.isBlank()) {
            try {
                return Double.parseDouble(val);
            } catch (NumberFormatException ignored) {}
        }
        // Fall back to global config initial capital (static, no Config instance here)
        String global = System.getenv("INITIAL_CAPITAL");
        if (global != null && !global.isBlank()) {
            try {
                return Double.parseDouble(global);
            } catch (NumberFormatException ignored) {}
        }
        return 1000.0;  // absolute last resort
    }

    /**
     * Parses "alpaca:80,tradier:100,tradovate:100" →
     * {"alpaca":80.0, "tradier":100.0, "tradovate":100.0}.
     *
     * The value is the <em>percentage of that broker's own account balance</em> to use.
     * Accepts values 1–100 (inclusive). Returns empty map on parse failure.
     */
    static Map<String, Double> parseAllocation(String allocationStr) {
        Map<String, Double> result = new LinkedHashMap<>();
        if (allocationStr == null || allocationStr.isBlank()) return result;
        for (String part : allocationStr.split(",")) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) continue;
            String[] kv = trimmed.split(":", 2);
            if (kv.length != 2) {
                logger.warn("MultiBrokerOrchestrator: invalid broker entry '{}' — expected 'name:percent'", trimmed);
                continue;
            }
            String name = kv[0].trim().toLowerCase();
            try {
                double pct = Double.parseDouble(kv[1].trim());
                if (pct <= 0 || pct > 100) {
                    logger.warn("MultiBrokerOrchestrator: broker '{}' percent {} out of range (1–100), skipping",
                        name, pct);
                    continue;
                }
                result.put(name, pct);
            } catch (NumberFormatException nfe) {
                logger.warn("MultiBrokerOrchestrator: cannot parse percent for '{}': {}", name, kv[1]);
            }
        }
        return result;
    }

    // ── Internal record ───────────────────────────────────────────────────────

    private record BrokerEntry(String brokerName, ProfileManager manager, BrokerClient rawClient) {}
}
