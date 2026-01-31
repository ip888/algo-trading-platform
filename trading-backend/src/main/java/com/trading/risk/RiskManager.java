package com.trading.risk;

import com.trading.config.TradingConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;

/**
 * Risk management system for controlling position sizing and capital protection.
 * All trading parameters are loaded from TradingConfig (config.properties).
 *
 * Integrates CapitalTierManager for adaptive risk based on account size:
 * - MICRO (<$500): Max 2 positions, 0.5% risk per trade
 * - SMALL ($500-$2K): Max 3 positions, 1% risk per trade
 * - MEDIUM ($2K-$5K): Max 4 positions, 1.5% risk per trade
 * - STANDARD ($5K-$25K): Max 5 positions, 2% risk per trade
 * - PDT ($25K+): Max 8 positions, 2% risk per trade
 */
public final class RiskManager {
    private static final Logger logger = LoggerFactory.getLogger(RiskManager.class);

    // Risk parameters loaded from configuration
    private final double riskPerTrade;
    private final double stopLossPct;
    private final double takeProfitPct;
    private final double maxDrawdown;
    private final double trailingStopPct;

    private double peakEquity;
    private double currentEquity; // Track current equity for tier calculations
    private final AdvancedPositionSizer positionSizer; // Optional advanced sizing
    
    public RiskManager(double initialCapital) {
        this(initialCapital, null, TradingConfig.getInstance());
    }
    
    public RiskManager(double initialCapital, AdvancedPositionSizer positionSizer) {
        this(initialCapital, positionSizer, TradingConfig.getInstance());
    }
    
    /**
     * Constructor with explicit config for testing.
     */
    public RiskManager(double initialCapital, AdvancedPositionSizer positionSizer, TradingConfig config) {
        this.peakEquity = initialCapital;
        this.currentEquity = initialCapital;
        this.positionSizer = positionSizer;

        // Load from config (values are percentages like 0.5 for 0.5%)
        this.riskPerTrade = config.getRiskPerTradeDecimal();      // 1% -> 0.01
        this.stopLossPct = config.getStopLossDecimal();           // 0.5% -> 0.005
        this.takeProfitPct = config.getTakeProfitDecimal();       // 0.75% -> 0.0075
        this.maxDrawdown = config.getMaxDrawdownDecimal();        // 50% -> 0.50
        this.trailingStopPct = config.getTrailingStopDecimal();   // 0.5% -> 0.005

        // Log tier info on initialization
        CapitalTierManager.logTierInfo(initialCapital);

        logger.info("RiskManager initialized: SL={}%, TP={}%, Trail={}%, MaxDD={}%",
            String.format("%.2f", stopLossPct * 100),
            String.format("%.2f", takeProfitPct * 100),
            String.format("%.2f", trailingStopPct * 100),
            String.format("%.1f", maxDrawdown * 100));

        if (positionSizer != null) {
            logger.info("RiskManager initialized with AdvancedPositionSizer");
        }
    }
    
    /**
     * Calculate safe position size based on account equity and risk percentage.
     * Uses AdvancedPositionSizer if available, otherwise falls back to simple sizing.
     */
    public double calculatePositionSize(double accountEquity, double entryPrice) {
        return calculatePositionSize(accountEquity, entryPrice, 15.0); // Default VIX
    }

    /**
     * Calculate safe position size with volatility adjustment.
     * Uses AdvancedPositionSizer if available for Kelly Criterion-based sizing.
     */
    public double calculatePositionSize(double accountEquity, double entryPrice, double currentVix) {
        return calculatePositionSize(null, accountEquity, entryPrice, currentVix, stopLossPct);
    }
    
    /**
     * Calculate position size with full parameters.
     * This is the main method that uses AdvancedPositionSizer when available.
     */
    public double calculatePositionSize(String symbol, double accountEquity, double entryPrice, 
                                       double currentVix, double stopLossPercent) {
        // Use AdvancedPositionSizer if available
        if (positionSizer != null && symbol != null) {
            // Calculate volatility from VIX (approximate)
            double volatility = currentVix / 100.0; // Convert VIX to decimal
            
            double size = positionSizer.calculatePositionSize(
                symbol, accountEquity, entryPrice, volatility, stopLossPercent
            );
            
            logger.debug("{}: Advanced position sizing - {} shares", symbol, 
                String.format("%.4f", size));
            
            return size;
        }
        
        // Fallback to simple volatility-adjusted sizing
        return calculateVolatilityAdjustedSize(accountEquity, entryPrice, currentVix);
    }

    private double calculateVolatilityAdjustedSize(double accountEquity, double entryPrice, double currentVix) {
        // Guard against invalid inputs - CRITICAL for real trading
        if (entryPrice <= 0.0 || accountEquity <= 0.0) {
            logger.warn("Invalid input for position sizing: price={}, equity={}", entryPrice, accountEquity);
            return 0.0;
        }

        // Update current equity for tier tracking
        this.currentEquity = accountEquity;

        // Get tier-adjusted risk percentage
        var tierParams = CapitalTierManager.getParameters(accountEquity);
        double tierRiskPerTrade = tierParams.riskPerTradePercent(); // Already decimal (0.01 = 1%)

        double stopLossPrice = calculateStopLoss(entryPrice);
        double riskPerShare = entryPrice - stopLossPrice;

        // Guard against zero risk per share (should never happen with valid stop-loss)
        if (riskPerShare <= 0.0) {
            logger.error("Invalid risk per share: {} - check stop-loss calculation", riskPerShare);
            return 0.0;
        }

        // Use tier-adjusted risk instead of config default
        double dollarRisk = accountEquity * tierRiskPerTrade;

        // Dynamic Risk Adjustment based on VIX
        // Base VIX = 20. If VIX is 40, size is halved.
        double safeVix = Math.max(10.0, currentVix);
        double volatilityFactor = Math.min(1.0, 20.0 / Math.max(20.0, safeVix));
        dollarRisk *= volatilityFactor;

        double shares = dollarRisk / riskPerShare;

        // Apply tier max position percent cap
        double maxPositionValue = accountEquity * tierParams.maxPositionPercent();
        double maxSharesByValue = maxPositionValue / entryPrice;
        shares = Math.min(shares, maxSharesByValue);

        // Ensure minimum position value
        double positionValue = shares * entryPrice;
        if (positionValue < tierParams.minPositionValue()) {
            logger.debug("Position value ${} below tier minimum ${} - skipping",
                String.format("%.2f", positionValue), tierParams.minPositionValue());
            return 0.0;
        }

        // Round to 9 decimal places (Alpaca's fractional trading precision)
        shares = Math.round(shares * 1_000_000_000.0) / 1_000_000_000.0;

        logger.debug("Position sizing [{}]: Equity=${}, TierRisk={}%, VIX={}, Shares={}",
            tierParams.tier(), String.format("%.0f", accountEquity),
            String.format("%.1f", tierRiskPerTrade * 100), currentVix,
            String.format("%.4f", shares));

        return Math.max(0.001, shares); // Minimum 0.001 shares
    }
    
    /**
     * Calculate stop-loss price (configured % below entry for longs).
     */
    public double calculateStopLoss(double entryPrice) {
        return entryPrice * (1.0 - stopLossPct);
    }
    
    /**
     * Calculate take-profit price (configured % above entry for longs).
     */
    public double calculateTakeProfit(double entryPrice) {
        return entryPrice * (1.0 + takeProfitPct);
    }
    
    /**
     * Create a TradePosition with calculated risk parameters.
     * Supports fractional quantities.
     */
    public TradePosition createPosition(String symbol, double entryPrice, double quantity) {
        return new TradePosition(
            symbol,
            entryPrice,
            quantity,
            calculateStopLoss(entryPrice),
            calculateTakeProfit(entryPrice),
            Instant.now()
        );
    }
    
    /**
     * Create a crypto TradePosition with tighter micro-profit settings.
     * Optimized for 24/7 crypto trading with quick TP/SL.
     * Uses: TP 0.75%, SL 0.5%
     */
    public TradePosition createCryptoPosition(String symbol, double entryPrice, double quantity) {
        // Micro-profit crypto settings: tight take-profit and stop-loss
        double cryptoTpPct = 0.0075;  // 0.75% take-profit
        double cryptoSlPct = 0.005;   // 0.5% stop-loss
        
        double stopLoss = entryPrice * (1.0 - cryptoSlPct);
        double takeProfit = entryPrice * (1.0 + cryptoTpPct);
        
        logger.info("🦑 Crypto position created: {} @ ${} | TP=${} (+0.75%) | SL=${} (-0.5%)",
            symbol, String.format("%.4f", entryPrice), 
            String.format("%.4f", takeProfit), String.format("%.4f", stopLoss));
        
        return new TradePosition(
            symbol,
            entryPrice,
            quantity,
            stopLoss,
            takeProfit,
            Instant.now()
        );
    }
    
    /**
     * Check if trading should be halted due to excessive drawdown.
     *
     * SAFETY NOTE: Auto-reset was removed as it could mask real catastrophic losses.
     * If peak equity seems incorrect, use resetPeakEquity() explicitly after manual review.
     */
    public boolean shouldHaltTrading(double currentEquity) {
        // Update peak equity (only if current is higher)
        if (currentEquity > peakEquity) {
            peakEquity = currentEquity;
            logger.debug("Peak equity updated to: ${}", String.format("%.2f", peakEquity));
        }

        double drawdown = (peakEquity - currentEquity) / peakEquity;

        // SAFETY FIX: Removed auto-reset that triggered when peak > 3x current.
        // That behavior could mask real 70%+ losses as "initialization bugs".
        // Instead, log a warning for manual investigation.
        if (peakEquity > currentEquity * 3.0) {
            logger.warn("⚠️ ALERT: Peak equity (${}) is >3x current (${}). " +
                "If this is due to initialization error, call resetPeakEquity() manually. " +
                "NOT auto-resetting to preserve safety.",
                String.format("%.2f", peakEquity), String.format("%.2f", currentEquity));
        }

        if (drawdown > maxDrawdown) {
            logger.error("CRITICAL: Max drawdown exceeded! Peak=${}, Current=${}, Drawdown={}%",
                String.format("%.2f", peakEquity),
                String.format("%.2f", currentEquity),
                String.format("%.2f", drawdown * 100));
            return true;
        }

        logger.debug("Drawdown check: Peak=${}, Current=${}, Drawdown={}%",
            String.format("%.2f", peakEquity),
            String.format("%.2f", currentEquity),
            String.format("%.2f", drawdown * 100));

        return false;
    }

    /**
     * Manually reset peak equity to current value.
     * Use this ONLY after manual investigation confirms the peak was incorrect.
     * This should never be called automatically.
     */
    public void resetPeakEquity(double currentEquity) {
        logger.warn("MANUAL RESET: Peak equity being reset from ${} to ${}",
            String.format("%.2f", peakEquity), String.format("%.2f", currentEquity));
        peakEquity = currentEquity;
        logger.info("Peak equity reset complete. Trading drawdown check will use new baseline.");
    }
    
    /**
     * Get current drawdown percentage.
     */
    public double getCurrentDrawdown(double currentEquity) {
        if (currentEquity > peakEquity) {
            peakEquity = currentEquity;
        }
        return (peakEquity - currentEquity) / peakEquity;
    }
    
    /**
     * Get the trailing stop percentage.
     */
    public double getTrailingStopPercent() {
        return trailingStopPct;
    }
    
    /**
     * Get the stop-loss percentage.
     */
    public double getStopLossPercent() {
        return stopLossPct;
    }
    
    /**
     * Get the take-profit percentage.
     */
    public double getTakeProfitPercent() {
        return takeProfitPct;
    }
    
    /**
     * Update position with trailing stop logic.
     */
    public TradePosition updatePositionTrailingStop(TradePosition position, double currentPrice) {
        return position.updateTrailingStop(currentPrice, trailingStopPct);
    }

    // ==================== Capital Tier Integration ====================

    /**
     * Get the current capital tier based on tracked equity.
     */
    public CapitalTierManager.CapitalTier getCurrentTier() {
        return CapitalTierManager.getTier(currentEquity);
    }

    /**
     * Get tier parameters for current equity level.
     */
    public CapitalTierManager.TierParameters getTierParameters() {
        return CapitalTierManager.getParameters(currentEquity);
    }

    /**
     * Check if a new position can be opened based on tier limits.
     *
     * @param currentPositionCount Current number of open positions
     * @param equity Current account equity
     * @return true if a new position can be opened
     */
    public boolean canOpenPosition(int currentPositionCount, double equity) {
        this.currentEquity = equity;
        var tierParams = CapitalTierManager.getParameters(equity);

        if (currentPositionCount >= tierParams.maxPositions()) {
            logger.info("Position limit reached: {}/{} for {} tier",
                currentPositionCount, tierParams.maxPositions(), tierParams.tier());
            return false;
        }

        if (CapitalTierManager.isAccountTooSmall(equity)) {
            logger.warn("Account too small for trading: ${}", String.format("%.2f", equity));
            return false;
        }

        return true;
    }

    /**
     * Get maximum positions allowed for current tier.
     */
    public int getMaxPositions() {
        return CapitalTierManager.getParameters(currentEquity).maxPositions();
    }

    /**
     * Get tier status message for logging/display.
     */
    public String getTierStatus() {
        return CapitalTierManager.getTierStatus(currentEquity);
    }

    /**
     * Update current equity (call this when equity changes).
     */
    public void updateEquity(double newEquity) {
        if (newEquity != this.currentEquity) {
            var oldTier = CapitalTierManager.getTier(this.currentEquity);
            var newTier = CapitalTierManager.getTier(newEquity);

            this.currentEquity = newEquity;

            if (oldTier != newTier) {
                logger.info("Capital tier changed: {} -> {} (Equity: ${})",
                    oldTier, newTier, String.format("%.2f", newEquity));
                CapitalTierManager.logTierInfo(newEquity);
            }
        }
    }
}
