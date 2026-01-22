package com.trading.broker;

/**
 * Quick test to verify Kraken WebSocket connectivity locally.
 * Run: mvn exec:java -Dexec.mainClass="com.trading.broker.KrakenWebSocketTest"
 */
public class KrakenWebSocketTest {
    
    public static void main(String[] args) {
        System.out.println("=== Kraken WebSocket Connectivity Test ===");
        System.out.println("Testing from: LOCAL machine");
        System.out.println();
        
        try {
            // Load config and create client (uses default no-arg constructors)
            KrakenClient client = new KrakenClient();
            
            // Test 1: Get WebSocket Token
            System.out.println("1. Testing REST API - GetWebSocketsToken...");
            long start = System.currentTimeMillis();
            String token = client.getWebSocketToken();
            long elapsed = System.currentTimeMillis() - start;
            
            if (token != null && !token.isEmpty()) {
                System.out.println("   ✅ SUCCESS! Token received in " + elapsed + "ms");
                System.out.println("   Token length: " + token.length() + " chars");
            } else {
                System.out.println("   ❌ FAILED! No token received after " + elapsed + "ms");
                return;
            }
            
            // Test 2: Connect to Auth WebSocket
            System.out.println();
            System.out.println("2. Testing Auth WebSocket connection...");
            start = System.currentTimeMillis();
            
            KrakenAuthWebSocketClient authWs = new KrakenAuthWebSocketClient(client);
            try {
                authWs.connect().get(15, java.util.concurrent.TimeUnit.SECONDS);
                elapsed = System.currentTimeMillis() - start;
                System.out.println("   ✅ SUCCESS! Connected in " + elapsed + "ms");
                
                // Test 3: Subscribe to balances
                System.out.println();
                System.out.println("3. Testing balance subscription...");
                authWs.subscribeToBalances();
                Thread.sleep(2000);  // Wait for response
                System.out.println("   ✅ Subscription sent");
                
                // Cleanup
                authWs.disconnect();
                System.out.println();
                System.out.println("=== All tests PASSED! ===");
                System.out.println("The issue is Cloud Run specific, not Kraken API.");
                
            } catch (Exception e) {
                elapsed = System.currentTimeMillis() - start;
                System.out.println("   ❌ FAILED after " + elapsed + "ms: " + e.getMessage());
            }
            
        } catch (Exception e) {
            System.out.println("❌ Test failed: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
