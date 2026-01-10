package com.trading.risk;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.*;

@DisplayName("RiskManager Tests")
class RiskManagerTest {
    
    private RiskManager riskManager;
    private static final double INITIAL_CAPITAL = 10000.0;
    
    @BeforeEach
    void setUp() {
        riskManager = new RiskManager(INITIAL_CAPITAL);
    }
    
    @Test
    @DisplayName("Should calculate position size with 1% risk")
    void shouldCalculatePositionSizeWithOnePercentRisk() {
        double entryPrice = 100.0;
        double positionSize = riskManager.calculatePositionSize(INITIAL_CAPITAL, entryPrice);
        
        // With 1% risk and 2% stop loss, position should be ~5 shares
        // Risk = $100, Stop = $2/share, Size = 100/2 = 50 shares
        assertThat(positionSize).isGreaterThan(0);
        assertThat(positionSize).isLessThan(100); // Sanity check
    }
    
    @ParameterizedTest
    @DisplayName("Should adjust position size based on VIX")
    @CsvSource({
        "15.0, 1.0",    // Low VIX = full size
        "20.0, 1.0",    // Normal VIX = full size
        "40.0, 0.5",    // High VIX = half size
        "60.0, 0.33"    // Extreme VIX = third size
    })
    void shouldAdjustPositionSizeBasedOnVIX(double vix, double expectedFactor) {
        double entryPrice = 100.0;
        double baseSize = riskManager.calculatePositionSize(INITIAL_CAPITAL, entryPrice, 20.0);
        double adjustedSize = riskManager.calculatePositionSize(INITIAL_CAPITAL, entryPrice, vix);
        
        double actualFactor = adjustedSize / baseSize;
        assertThat(actualFactor).isCloseTo(expectedFactor, within(0.1));
    }
    
    @Test
    @DisplayName("Should calculate stop loss at 2% below entry")
    void shouldCalculateStopLoss() {
        double entryPrice = 100.0;
        double stopLoss = riskManager.calculateStopLoss(entryPrice);
        
        assertThat(stopLoss).isEqualTo(98.0);
    }
    
    @Test
    @DisplayName("Should calculate take profit at 4% above entry")
    void shouldCalculateTakeProfit() {
        double entryPrice = 100.0;
        double takeProfit = riskManager.calculateTakeProfit(entryPrice);
        
        assertThat(takeProfit).isEqualTo(104.0);
    }
    
    @Test
    @DisplayName("Should halt trading when max drawdown exceeded")
    void shouldHaltTradingOnMaxDrawdown() {
        double currentEquity = 8500.0; // 15% drawdown
        
        boolean shouldHalt = riskManager.shouldHaltTrading(currentEquity);
        
        assertThat(shouldHalt).isTrue();
    }
    
    @Test
    @DisplayName("Should not halt trading within acceptable drawdown")
    void shouldNotHaltTradingWithinAcceptableDrawdown() {
        double currentEquity = 9500.0; // 5% drawdown
        
        boolean shouldHalt = riskManager.shouldHaltTrading(currentEquity);
        
        assertThat(shouldHalt).isFalse();
    }
    
    @Test
    @DisplayName("Should create position with correct risk parameters")
    void shouldCreatePositionWithCorrectRiskParameters() {
        String symbol = "SPY";
        double entryPrice = 450.0;
        double quantity = 10.0;
        
        TradePosition position = riskManager.createPosition(symbol, entryPrice, quantity);
        
        assertThat(position.symbol()).isEqualTo(symbol);
        assertThat(position.entryPrice()).isEqualTo(entryPrice);
        assertThat(position.quantity()).isEqualTo(quantity);
        assertThat(position.stopLoss()).isEqualTo(441.0); // 2% below
        assertThat(position.takeProfit()).isEqualTo(468.0); // 4% above
    }
    
}
