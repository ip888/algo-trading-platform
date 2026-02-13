package com.trading.execution;

import com.trading.analysis.MarketRegimeDetector.MarketRegime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Determines the optimal order type (LIMIT vs MARKET) based on current trading conditions.
 *
 * Decision factors:
 * - Market regime and VIX (high volatility = market orders for guaranteed fills)
 * - Strategy type (momentum needs speed; mean-reversion can wait for better price)
 * - Order side (exits prefer market for certainty; entries can use limit to save cost)
 * - Account equity (smaller accounts benefit more from limit order savings)
 * - Spread context (wide spreads favor limit orders to avoid slippage)
 */
public final class SmartOrderTypeSelector {
    private static final Logger logger = LoggerFactory.getLogger(SmartOrderTypeSelector.class);

    /** Result of the order type decision. */
    public record OrderTypeDecision(
        String orderType,      // "market" or "limit"
        Double limitPrice,     // null for market orders; calculated price for limit
        String timeInForce,    // "day" or "gtc"
        String reason          // human-readable explanation
    ) {
        public boolean isLimit() {
            return "limit".equals(orderType);
        }
    }

    /** Context for making the order type decision. */
    public record OrderContext(
        String symbol,
        String side,           // "buy" or "sell"
        double currentPrice,
        double accountEquity,
        double vix,
        MarketRegime regime,
        String strategyType,   // "MACD", "RSI", "MOMENTUM", "MEAN_REVERSION", etc.
        boolean isExitOrder,   // true for SL/TP/signal exits
        boolean isEmergency,   // true for emergency/panic exits
        boolean isStopLoss     // true specifically for stop-loss exits
    ) {}

    // Limit order price offset: how far from current price to place limit
    // Tight enough to fill quickly, wide enough to save vs market order slippage
    private static final double BUY_LIMIT_OFFSET_PERCENT = 0.03;   // 0.03% above current for buys
    private static final double SELL_LIMIT_OFFSET_PERCENT = 0.03;  // 0.03% below current for sells

    // VIX thresholds
    private static final double VIX_HIGH = 25.0;
    private static final double VIX_ELEVATED = 20.0;

    // Equity threshold: below this, limit orders matter more for fee savings
    private static final double SMALL_ACCOUNT_THRESHOLD = 2000.0;

    /**
     * Determine the optimal order type for the given context.
     */
    public OrderTypeDecision selectOrderType(OrderContext ctx) {
        // Rule 1: Emergency exits are ALWAYS market orders
        if (ctx.isEmergency()) {
            return marketOrder(ctx, "Emergency exit requires guaranteed fill");
        }

        // Rule 2: Stop-loss exits are ALWAYS market orders
        if (ctx.isStopLoss()) {
            return marketOrder(ctx, "Stop-loss exit requires guaranteed fill");
        }

        // Rule 3: High VIX + volatile regime = market orders (price can move fast)
        if (ctx.vix() > VIX_HIGH && ctx.regime() == MarketRegime.HIGH_VOLATILITY) {
            return marketOrder(ctx, "High VIX (%.1f) + volatile regime".formatted(ctx.vix()));
        }

        // Rule 4: Exit orders in volatile conditions use market
        if (ctx.isExitOrder() && ctx.vix() > VIX_HIGH) {
            return marketOrder(ctx, "Exit in high volatility (VIX=%.1f)".formatted(ctx.vix()));
        }

        // Rule 5: Momentum and trend-following entries prefer market orders for speed
        // (catching the move matters more than a few cents savings)
        if (!ctx.isExitOrder() && isSpeedCriticalStrategy(ctx.strategyType())) {
            if (ctx.regime() == MarketRegime.STRONG_BULL || ctx.regime() == MarketRegime.STRONG_BEAR) {
                return marketOrder(ctx, "Trend entry with %s strategy".formatted(ctx.strategyType()));
            }
        }

        // Rule 6: Range-bound market with mean-reversion/RSI strategy -> use limit orders
        // (price is oscillating, we can wait for a better fill)
        if (!ctx.isExitOrder() && isPricePatientStrategy(ctx.strategyType())) {
            return limitOrder(ctx, "Mean-reversion/RSI entry in favorable conditions");
        }

        // Rule 7: Small accounts (<$2K) prefer limit orders to minimize slippage cost
        if (ctx.accountEquity() < SMALL_ACCOUNT_THRESHOLD && ctx.vix() < VIX_HIGH) {
            return limitOrder(ctx, "Small account ($%.0f) optimizing for fill cost".formatted(ctx.accountEquity()));
        }

        // Rule 8: Take-profit exits can use limit orders (not urgent, price is favorable)
        if (ctx.isExitOrder() && !ctx.isStopLoss() && ctx.vix() < VIX_ELEVATED) {
            return limitOrder(ctx, "Take-profit exit in calm market");
        }

        // Rule 9: Calm market entries (low VIX, range-bound) favor limit orders
        if (!ctx.isExitOrder() && ctx.vix() < VIX_ELEVATED
                && (ctx.regime() == MarketRegime.RANGE_BOUND || ctx.regime() == null)) {
            return limitOrder(ctx, "Entry in calm market (VIX=%.1f)".formatted(ctx.vix()));
        }

        // Default: market order (safe fallback)
        return marketOrder(ctx, "Default: market order for reliability");
    }

    private boolean isSpeedCriticalStrategy(String strategyType) {
        if (strategyType == null) return false;
        return switch (strategyType.toUpperCase()) {
            case "MOMENTUM", "TREND_FOLLOWING", "TREND" -> true;
            default -> false;
        };
    }

    private boolean isPricePatientStrategy(String strategyType) {
        if (strategyType == null) return false;
        return switch (strategyType.toUpperCase()) {
            case "MEAN_REVERSION", "RSI", "BOLLINGER_BANDS", "BOLLINGER" -> true;
            default -> false;
        };
    }

    private OrderTypeDecision marketOrder(OrderContext ctx, String reason) {
        logger.info("{} {}: Order type -> MARKET ({})", ctx.symbol(), ctx.side(), reason);
        return new OrderTypeDecision("market", null, "day", reason);
    }

    private OrderTypeDecision limitOrder(OrderContext ctx, String reason) {
        double limitPrice = calculateLimitPrice(ctx.currentPrice(), ctx.side());
        String tif = "day"; // limit orders expire at end of day if not filled
        logger.info("{} {}: Order type -> LIMIT @ ${} ({})",
            ctx.symbol(), ctx.side(), String.format("%.2f", limitPrice), reason);
        return new OrderTypeDecision("limit", limitPrice, tif, reason);
    }

    /**
     * Calculate limit price close to current market to maximize fill probability
     * while capturing a small price improvement vs market order.
     */
    private double calculateLimitPrice(double currentPrice, String side) {
        if ("buy".equalsIgnoreCase(side)) {
            // Buy limit: slightly above current price (willing to pay a tiny premium for the fill)
            return currentPrice * (1.0 + BUY_LIMIT_OFFSET_PERCENT / 100.0);
        } else {
            // Sell limit: slightly below current price (accept a tiny discount for the fill)
            return currentPrice * (1.0 - SELL_LIMIT_OFFSET_PERCENT / 100.0);
        }
    }
}
