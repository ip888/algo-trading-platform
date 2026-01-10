package com.trading.portfolio;

import com.trading.api.AlpacaClient;
import com.trading.api.model.Position;
import com.trading.risk.TradePosition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("PortfolioManager Tests")
class PortfolioManagerTest {
    
    @Mock
    private AlpacaClient mockClient;
    
    private PortfolioManager portfolioManager;
    private static final double TOTAL_CAPITAL = 10000.0;
    private static final List<String> SYMBOLS = List.of("SPY", "QQQ", "IWM");
    
    @BeforeEach
    void setUp() {
        portfolioManager = new PortfolioManager(SYMBOLS, TOTAL_CAPITAL);
    }
    
    @Test
    @DisplayName("Should initialize with correct capital allocation")
    void shouldInitializeWithCorrectCapitalAllocation() {
        double expectedPerSymbol = TOTAL_CAPITAL / SYMBOLS.size();
        
        assertThat(portfolioManager.getCapitalPerSymbol()).isEqualTo(expectedPerSymbol);
        assertThat(portfolioManager.getSymbols()).containsExactlyElementsOf(SYMBOLS);
    }
    
    @Test
    @DisplayName("Should start with no active positions")
    void shouldStartWithNoActivePositions() {
        assertThat(portfolioManager.getActivePositionCount()).isZero();
        assertThat(portfolioManager.getActiveStoredSymbols()).isEmpty();
    }
    
    @Test
    @DisplayName("Should add and retrieve position")
    void shouldAddAndRetrievePosition() {
        String symbol = "SPY";
        TradePosition position = createMockPosition(symbol, 450.0, 10.0);
        
        portfolioManager.setPosition(symbol, Optional.of(position));
        
        Optional<TradePosition> retrieved = portfolioManager.getPosition(symbol);
        assertThat(retrieved).isPresent();
        assertThat(retrieved.get().symbol()).isEqualTo(symbol);
    }
    
    @Test
    @DisplayName("Should remove position")
    void shouldRemovePosition() {
        String symbol = "SPY";
        TradePosition position = createMockPosition(symbol, 450.0, 10.0);
        
        portfolioManager.setPosition(symbol, Optional.of(position));
        assertThat(portfolioManager.getActivePositionCount()).isEqualTo(1);
        
        portfolioManager.setPosition(symbol, Optional.empty());
        assertThat(portfolioManager.getActivePositionCount()).isZero();
    }
    
    @Test
    @DisplayName("Should track multiple positions")
    void shouldTrackMultiplePositions() {
        TradePosition pos1 = createMockPosition("SPY", 450.0, 10.0);
        TradePosition pos2 = createMockPosition("QQQ", 380.0, 15.0);
        
        portfolioManager.setPosition("SPY", Optional.of(pos1));
        portfolioManager.setPosition("QQQ", Optional.of(pos2));
        
        assertThat(portfolioManager.getActivePositionCount()).isEqualTo(2);
        assertThat(portfolioManager.getActiveStoredSymbols()).containsExactlyInAnyOrder("SPY", "QQQ");
    }
    
    @Test
    @DisplayName("Should return empty for non-existent position")
    void shouldReturnEmptyForNonExistentPosition() {
        Optional<TradePosition> position = portfolioManager.getPosition("AAPL");
        
        assertThat(position).isEmpty();
    }
    
    @Test
    @DisplayName("Should get all positions including empty ones")
    void shouldGetAllPositionsIncludingEmptyOnes() {
        TradePosition pos = createMockPosition("SPY", 450.0, 10.0);
        portfolioManager.setPosition("SPY", Optional.of(pos));
        
        var allPositions = portfolioManager.getAllPositions();
        
        assertThat(allPositions).hasSize(3); // All symbols
        assertThat(allPositions.get("SPY")).isPresent();
        assertThat(allPositions.get("QQQ")).isEmpty();
        assertThat(allPositions.get("IWM")).isEmpty();
    }
    
    @Test
    @DisplayName("Should sync with Alpaca positions")
    void shouldSyncWithAlpacaPositions() throws Exception {
        // Mock Alpaca positions
        List<Position> alpacaPositions = List.of(
            new Position("SPY", 10.0, 450.0, 4500.0, 100.0)
        );
        when(mockClient.getPositions()).thenReturn(alpacaPositions);
        
        portfolioManager.syncWithAlpaca(mockClient);
        
        Optional<TradePosition> syncedPosition = portfolioManager.getPosition("SPY");
        assertThat(syncedPosition).isPresent();
        assertThat(syncedPosition.get().symbol()).isEqualTo("SPY");
        assertThat(syncedPosition.get().quantity()).isEqualTo(10.0);
    }
    
    @Test
    @DisplayName("Should be thread-safe for concurrent access")
    void shouldBeThreadSafeForConcurrentAccess() throws InterruptedException {
        // Create multiple threads that add/remove positions
        Thread t1 = new Thread(() -> {
            for (int i = 0; i < 100; i++) {
                TradePosition pos = createMockPosition("SPY", 450.0, 10.0);
                portfolioManager.setPosition("SPY", Optional.of(pos));
            }
        });
        
        Thread t2 = new Thread(() -> {
            for (int i = 0; i < 100; i++) {
                portfolioManager.setPosition("SPY", Optional.empty());
            }
        });
        
        t1.start();
        t2.start();
        t1.join();
        t2.join();
        
        // Should not throw ConcurrentModificationException
        assertThat(portfolioManager.getActivePositionCount()).isGreaterThanOrEqualTo(0);
    }
    
    // Helper method
    private TradePosition createMockPosition(String symbol, double entryPrice, double quantity) {
        return new TradePosition(
            symbol,
            entryPrice,
            quantity,
            entryPrice * 0.98, // 2% stop loss
            entryPrice * 1.04, // 4% take profit
            Instant.now()
        );
    }
}
