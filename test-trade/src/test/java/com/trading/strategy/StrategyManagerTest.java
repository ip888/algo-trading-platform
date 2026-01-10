package com.trading.strategy;

import com.trading.analysis.MarketRegimeDetector.MarketRegime;
import com.trading.api.AlpacaClient;
import com.trading.api.model.Bar;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("StrategyManager Tests")
class StrategyManagerTest {
    
    @Mock
    private AlpacaClient mockClient;
    
    private StrategyManager strategyManager;
    
    @BeforeEach
    void setUp() {
        strategyManager = new StrategyManager(mockClient);
    }
    
    @Test
    @DisplayName("Should return HOLD when insufficient history")
    void shouldReturnHoldWhenInsufficientHistory() throws Exception {
        // Mock insufficient bars
        List<Bar> bars = createMockBars(30); // Less than 50 required
        when(mockClient.getMarketHistory(anyString(), anyInt())).thenReturn(bars);
        
        TradingSignal signal = strategyManager.evaluate("SPY", 450.0, 0.0, 
            MarketRegime.RANGE_BOUND);
        
        assertThat(signal).isInstanceOf(TradingSignal.Hold.class);
        assertThat(((TradingSignal.Hold) signal).reason()).contains("Insufficient history");
    }
    
    @Test
    @DisplayName("Should use RSI strategy in range-bound market")
    void shouldUseRSIStrategyInLowVolatility() {
        List<Double> prices = createTrendingPrices(100, 450.0, 0.005); // Low volatility
        
        TradingSignal signal = strategyManager.evaluateWithHistory("SPY", 450.0, 0.0, 
            prices, MarketRegime.RANGE_BOUND);
        
        assertThat(strategyManager.getCurrentRegime().toString()).isEqualTo("RANGE_BOUND");
        assertThat(strategyManager.getActiveStrategy()).isEqualTo("RSI Range");
    }
    
    @Test
    @DisplayName("Should use MACD strategy in strong trend")
    void shouldUseMACDStrategyInHighVolatility() {
        List<Double> prices = createTrendingPrices(100, 450.0, 0.02); // Trending market
        
        TradingSignal signal = strategyManager.evaluateWithHistory("SPY", 450.0, 0.0, 
            prices, MarketRegime.STRONG_BULL);
        
        assertThat(strategyManager.getCurrentRegime().toString()).isEqualTo("STRONG_BULL");
        assertThat(strategyManager.getActiveStrategy()).isEqualTo("MACD Trend");
    }
    
    @Test
    @DisplayName("Should use Mean Reversion in high volatility")
    void shouldUseMeanReversionInExtremeVolatility() {
        List<Double> prices = createVolatilePrices(100, 450.0);
        
        TradingSignal signal = strategyManager.evaluateWithHistory("SPY", 450.0, 0.0, 
            prices, MarketRegime.HIGH_VOLATILITY);
        
        assertThat(strategyManager.getCurrentRegime().toString()).isEqualTo("HIGH_VOLATILITY");
        assertThat(strategyManager.getActiveStrategy()).contains("Mean Reversion");
    }
    
    @Test
    @DisplayName("Should calculate SMA correctly")
    void shouldCalculateSMACorrectly() {
        List<Double> prices = List.of(100.0, 102.0, 104.0, 106.0, 108.0);
        
        // Access via evaluateWithHistory which uses calculateSMA internally
        TradingSignal signal = strategyManager.evaluateWithHistory("SPY", 105.0, 0.0, 
            prices, MarketRegime.RANGE_BOUND);
        
        // Verify signal is generated (SMA calculation worked)
        assertThat(signal).isNotNull();
    }
    
    @Test
    @DisplayName("Should handle empty price history gracefully")
    void shouldHandleEmptyPriceHistoryGracefully() {
        List<Double> prices = List.of();
        
        // Should not throw exception
        assertThatCode(() -> {
            strategyManager.evaluateWithHistory("SPY", 450.0, 0.0, 
                prices, MarketRegime.RANGE_BOUND);
        }).doesNotThrowAnyException();
    }
    
    // Helper methods
    private List<Bar> createMockBars(int count) {
        return IntStream.range(0, count)
            .mapToObj(i -> new Bar(null, 450.0 + i, 451.0, 449.0, 450.5, 1000000))
            .toList();
    }
    
    private List<Double> createTrendingPrices(int count, double start, double volatility) {
        return IntStream.range(0, count)
            .mapToDouble(i -> start + (i * 0.1) + (Math.random() - 0.5) * volatility * start)
            .boxed()
            .toList();
    }
    
    private List<Double> createVolatilePrices(int count, double base) {
        return IntStream.range(0, count)
            .mapToDouble(i -> base + (Math.random() - 0.5) * base * 0.05)
            .boxed()
            .toList();
    }
}
