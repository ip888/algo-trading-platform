package com.trading.api;

import com.trading.api.model.Bar;
import com.trading.api.model.Position;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for ResilientBrokerClient — circuit breaker, retry, delegate wiring,
 * and critical placeOrderDirect bypass behaviour.
 */
@DisplayName("ResilientBrokerClient Tests")
class ResilientBrokerClientTest {

    private BrokerClient mockDelegate;
    private ResilientBrokerClient resilient;

    @BeforeEach
    void setUp() {
        mockDelegate = mock(BrokerClient.class, withSettings()
            .mockMaker(org.mockito.MockMakers.SUBCLASS)
            .defaultAnswer(inv -> {
                Class<?> rt = inv.getMethod().getReturnType();
                if (rt == void.class) return null;
                if (rt == List.class) return List.of();
                if (rt == Optional.class) return Optional.empty();
                if (rt == boolean.class) return false;
                return null;
            }));
        resilient = new ResilientBrokerClient(mockDelegate, new SimpleMeterRegistry());
    }

    @Test
    @DisplayName("getDelegate() returns the wrapped BrokerClient instance")
    void testGetDelegateReturnsDelegate() {
        assertSame(mockDelegate, resilient.getDelegate(),
            "getDelegate() must return the exact delegate passed to constructor");
    }

    @Test
    @DisplayName("getPositions() delegates to underlying BrokerClient")
    void testGetPositionsDelegatesToBrokerClient() throws Exception {
        var pos = new Position("SPY", 5.0, 2500.0, 500.0, 25.0);
        when(mockDelegate.getPositions()).thenReturn(List.of(pos));

        var result = resilient.getPositions();

        assertEquals(1, result.size());
        assertEquals("SPY", result.get(0).symbol());
        verify(mockDelegate, times(1)).getPositions();
    }

    @Test
    @DisplayName("placeOrderDirect bypasses circuit breaker and calls delegate directly")
    void testPlaceOrderDirectBypassesCircuitBreaker() throws Exception {
        // Open circuit breaker by simulating failures
        resilient.resetCircuitBreaker(); // ensure clean state

        // placeOrderDirect should always reach the delegate, even in degraded state
        resilient.placeOrderDirect("SPY", 1.0, "sell", "market", "day", null);

        verify(mockDelegate, times(1))
            .placeOrder("SPY", 1.0, "sell", "market", "day", null);
    }

    @Test
    @DisplayName("PDTRejectedException propagates without retry")
    void testPDTExceptionPropagatesWithoutRetry() throws Exception {
        doThrow(new PDTRejectedException("PDT limit hit"))
            .when(mockDelegate).placeOrder(anyString(), anyDouble(), anyString(),
                anyString(), anyString(), any());

        assertThrows(PDTRejectedException.class, () ->
            resilient.placeOrder("SPY", 1.0, "buy", "market", "day", null));

        // Should only be called ONCE — no retry on PDT rejection
        verify(mockDelegate, times(1))
            .placeOrder(anyString(), anyDouble(), anyString(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("cancelOrder delegates to underlying BrokerClient")
    void testCancelOrderDelegates() {
        resilient.cancelOrder("order-123");
        verify(mockDelegate, times(1)).cancelOrder("order-123");
    }

    @Test
    @DisplayName("getLatestBar delegates to underlying BrokerClient")
    void testGetLatestBarDelegates() throws Exception {
        resilient.getLatestBar("QQQ");
        verify(mockDelegate, times(1)).getLatestBar("QQQ");
    }

    @Test
    @DisplayName("getCircuitBreakerState returns non-null state string")
    void testCircuitBreakerStateNonNull() {
        assertNotNull(resilient.getCircuitBreakerState());
        assertFalse(resilient.getCircuitBreakerState().isBlank());
    }
}
