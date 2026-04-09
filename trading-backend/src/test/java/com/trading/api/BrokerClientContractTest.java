package com.trading.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Contract tests ensuring BrokerClient interface is correctly implemented
 * and that AlpacaClient satisfies the contract.
 */
@DisplayName("BrokerClient Contract Tests")
class BrokerClientContractTest {

    @Test
    @DisplayName("AlpacaClient declares 'implements BrokerClient'")
    void testAlpacaClientImplementsBrokerClient() {
        Class<?>[] interfaces = AlpacaClient.class.getInterfaces();
        boolean implementsBrokerClient = Arrays.stream(interfaces)
            .anyMatch(i -> i.equals(BrokerClient.class));
        assertTrue(implementsBrokerClient,
            "AlpacaClient must implement BrokerClient");
    }

    @Test
    @DisplayName("Every method in BrokerClient is implemented by AlpacaClient")
    void testAllBrokerClientMethodsImplementedByAlpacaClient() {
        Set<String> brokerMethods = Arrays.stream(BrokerClient.class.getMethods())
            .map(Method::getName)
            .collect(Collectors.toSet());

        Set<String> alpacaMethods = Arrays.stream(AlpacaClient.class.getMethods())
            .map(Method::getName)
            .collect(Collectors.toSet());

        for (String method : brokerMethods) {
            assertTrue(alpacaMethods.contains(method),
                "AlpacaClient missing method declared in BrokerClient: " + method);
        }
    }

    @Test
    @DisplayName("ResilientBrokerClient wraps BrokerClient (not AlpacaClient directly)")
    void testResilientBrokerClientWrapsInterface() throws Exception {
        var delegateField = ResilientBrokerClient.class.getDeclaredField("delegate");
        delegateField.setAccessible(true);
        assertEquals(BrokerClient.class, delegateField.getType(),
            "ResilientBrokerClient.delegate must be typed as BrokerClient, not AlpacaClient");
    }

    @Test
    @DisplayName("ResilientAlpacaClient is a deprecated shim extending ResilientBrokerClient")
    void testResilientAlpacaClientIsShim() {
        assertTrue(ResilientBrokerClient.class.isAssignableFrom(ResilientAlpacaClient.class),
            "ResilientAlpacaClient must extend ResilientBrokerClient for backward compatibility");
        assertTrue(ResilientAlpacaClient.class.isAnnotationPresent(Deprecated.class),
            "ResilientAlpacaClient must be @Deprecated");
    }

    @Test
    @DisplayName("getDelegate() on ResilientBrokerClient returns BrokerClient type")
    void testGetDelegateReturnsBrokerClient() throws Exception {
        var getDelegateMethod = ResilientBrokerClient.class.getMethod("getDelegate");
        assertEquals(BrokerClient.class, getDelegateMethod.getReturnType(),
            "getDelegate() must return BrokerClient, not AlpacaClient");
    }
}
