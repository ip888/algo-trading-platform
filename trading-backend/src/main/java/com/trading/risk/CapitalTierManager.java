package com.trading.risk;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages adaptive risk parameters based on account capital size.
 *
 * Small accounts require more conservative settings due to:
 * 1. Fractional shares limiting bracket order protection
 * 2. Higher relative impact of fees and slippage
 * 3. Less room for diversification
 * 4. PDT restrictions requiring longer hold times
 *
 * As capital grows, the bot can be more aggressive with better diversification.
 *
 * Capital Tiers:
 * - MICRO:    $0 - $500      (Ultra-conservative, limited trading)
 * - SMALL:    $500 - $2,000  (Conservative, focus on safety)
 * - MEDIUM:   $2,000 - $5,000 (Balanced approach)
 * - STANDARD: $5,000 - $25,000 (Normal parameters)
 * - PDT:      $25,000+       (Full trading capabilities)
 */
public final class CapitalTierManager {
    private static final Logger logger = LoggerFactory.getLogger(CapitalTierManager.class);

    public enum CapitalTier {
        MICRO,      // $0 - $500
        SMALL,      // $500 - $2,000
        MEDIUM,     // $2,000 - $5,000
        STANDARD,   // $5,000 - $25,000
        PDT         // $25,000+
    }

    /**
     * Tier-specific risk parameters.
     */
    public record TierParameters(
        CapitalTier tier,
        double maxPositionPercent,      // Max % of portfolio per position
        double riskPerTradePercent,     // Max % risk per trade
        int maxPositions,               // Maximum concurrent positions
        double minPositionValue,        // Minimum position size in $
        double takeProfitMultiplier,    // Multiplier for TP (1.0 = config value)
        double stopLossMultiplier,      // Multiplier for SL (1.0 = config value)
        boolean preferWholeShares,      // Prefer whole shares for bracket protection
        String recommendation           // Human-readable recommendation
    ) {

        public String getSummary() {
            return String.format(
                "Tier: %s | Max Position: %.0f%% | Risk/Trade: %.1f%% | Max Positions: %d | Whole Shares: %s",
                tier, maxPositionPercent * 100, riskPerTradePercent * 100, maxPositions, preferWholeShares
            );
        }
    }

    /**
     * Determine the capital tier for a given equity amount.
     */
    public static CapitalTier getTier(double equity) {
        if (equity < 500) {
            return CapitalTier.MICRO;
        } else if (equity < 2000) {
            return CapitalTier.SMALL;
        } else if (equity < 5000) {
            return CapitalTier.MEDIUM;
        } else if (equity < 25000) {
            return CapitalTier.STANDARD;
        } else {
            return CapitalTier.PDT;
        }
    }

    /**
     * Get tier-specific parameters for the given equity.
     */
    public static TierParameters getParameters(double equity) {
        CapitalTier tier = getTier(equity);

        return switch (tier) {
            case MICRO -> new TierParameters(
                tier,
                0.50,           // 50% max per position (limited diversification)
                0.005,          // 0.5% risk per trade (ultra-conservative)
                2,              // Max 2 positions
                5.0,            // Min $5 position
                0.5,            // Tighter TP (50% of config)
                1.5,            // Wider SL (150% of config) - more breathing room
                true,           // Prefer whole shares
                "⚠️ MICRO account (<$500): Very limited trading. Consider adding capital for better protection."
            );

            case SMALL -> new TierParameters(
                tier,
                0.35,           // 35% max per position
                0.01,           // 1% risk per trade
                3,              // Max 3 positions
                10.0,           // Min $10 position
                0.75,           // 75% of config TP
                1.25,           // 125% of config SL
                true,           // Prefer whole shares for bracket protection
                "📊 SMALL account ($500-$2K): Conservative mode. " +
                "Fractional shares may not have bracket protection."
            );

            case MEDIUM -> new TierParameters(
                tier,
                0.30,           // 30% max per position
                0.015,          // 1.5% risk per trade
                4,              // Max 4 positions
                15.0,           // Min $15 position
                0.9,            // 90% of config TP
                1.1,            // 110% of config SL
                true,           // Still prefer whole shares
                "📈 MEDIUM account ($2K-$5K): Balanced approach with good diversification."
            );

            case STANDARD -> new TierParameters(
                tier,
                0.25,           // 25% max per position
                0.02,           // 2% risk per trade (standard Kelly)
                5,              // Max 5 positions
                25.0,           // Min $25 position
                1.0,            // Use config TP as-is
                1.0,            // Use config SL as-is
                false,          // Fractional shares OK
                "💪 STANDARD account ($5K-$25K): Full trading capabilities. PDT restriction still applies."
            );

            case PDT -> new TierParameters(
                tier,
                0.20,           // 20% max per position (better diversification)
                0.02,           // 2% risk per trade
                8,              // Max 8 positions
                50.0,           // Min $50 position
                1.0,            // Use config TP as-is
                1.0,            // Use config SL as-is
                false,          // Fractional shares OK
                "🚀 PDT account ($25K+): Full day-trading capabilities unlocked!"
            );
        };
    }

    /**
     * Calculate position size adjusted for capital tier.
     *
     * @param equity Current account equity
     * @param baseSize Position size calculated by normal risk management
     * @param stockPrice Current stock price
     * @return Adjusted position size (in shares)
     */
    public static double adjustPositionSize(double equity, double baseSize, double stockPrice) {
        TierParameters params = getParameters(equity);

        // Apply max position percent limit
        double maxPositionValue = equity * params.maxPositionPercent();
        double maxShares = maxPositionValue / stockPrice;

        // If preferring whole shares for bracket protection, round down
        if (params.preferWholeShares() && baseSize < maxShares) {
            double wholeShares = Math.floor(baseSize);

            // Only use whole shares if it's at least the minimum position value
            if (wholeShares * stockPrice >= params.minPositionValue()) {
                logger.debug("Capital tier {} recommends whole shares: {} -> {} shares",
                    params.tier(), baseSize, wholeShares);
                return wholeShares;
            }
        }

        // Cap at max position size
        double adjustedSize = Math.min(baseSize, maxShares);

        // Ensure minimum position value
        if (adjustedSize * stockPrice < params.minPositionValue()) {
            logger.debug("Position too small for tier {}: ${} < ${}",
                params.tier(), adjustedSize * stockPrice, params.minPositionValue());
            return 0;
        }

        return adjustedSize;
    }

    /**
     * Check if the account is too small for effective trading.
     */
    public static boolean isAccountTooSmall(double equity) {
        return equity < 100;
    }

    /**
     * Get a human-readable status message for the current capital tier.
     */
    public static String getTierStatus(double equity) {
        TierParameters params = getParameters(equity);
        return String.format(
            "%s | Equity: $%.2f | %s",
            params.tier(),
            equity,
            params.recommendation()
        );
    }

    /**
     * Log the current tier parameters.
     */
    public static void logTierInfo(double equity) {
        TierParameters params = getParameters(equity);
        logger.info("═══════════════════════════════════════════════════════");
        logger.info("💰 Capital Tier Analysis for ${}", String.format("%.2f", equity));
        logger.info("   {}", params.getSummary());
        logger.info("   {}", params.recommendation());
        logger.info("═══════════════════════════════════════════════════════");
    }
}
