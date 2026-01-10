package com.trading.test;

import com.trading.api.AlpacaClient;
import com.trading.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class VixTest {
    private static final Logger logger = LoggerFactory.getLogger(VixTest.class);

    public static void main(String[] args) {
        try {
            Config config = new Config();
            AlpacaClient client = new AlpacaClient(config);
            
            logger.info("Testing VIX data fetching...");
            
            // Test 1: Direct VIX
            try {
                logger.info("1. Fetching VIX...");
                var bar = client.getLatestBar("VIX");
                if (bar.isPresent()) {
                    logger.info("✅ SUCCESS: VIX = {}", bar.get().close());
                } else {
                    logger.info("❌ FAILED: VIX bar empty");
                }
            } catch (Exception e) {
                logger.error("❌ ERROR fetching VIX: {}", e.getMessage());
            }

            // Test 2: VIXY Proxy
            try {
                logger.info("2. Fetching VIXY (Proxy)...");
                var bar = client.getLatestBar("VIXY");
                if (bar.isPresent()) {
                    double price = bar.get().close();
                    double estimated = (price / 2.0) + 2.0;
                    logger.info("✅ SUCCESS: VIXY = {}, Est VIX = {}", price, estimated);
                } else {
                    logger.info("❌ FAILED: VIXY bar empty");
                }
            } catch (Exception e) {
                logger.error("❌ ERROR fetching VIXY: {}", e.getMessage());
            }
            
            // Test 3: UVXY (Leveraged Proxy)
            try {
                logger.info("3. Fetching UVXY...");
                var bar = client.getLatestBar("UVXY");
                if (bar.isPresent()) {
                    logger.info("✅ SUCCESS: UVXY = {}", bar.get().close());
                }
            } catch (Exception e) {
                logger.error("❌ ERROR fetching UVXY: {}", e.getMessage());
            }

            System.exit(0);
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }
    }
}
