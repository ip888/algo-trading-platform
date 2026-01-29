//! Adaptive capital tier management for Coinbase trading
//!
//! Automatically adjusts risk parameters based on portfolio size.
//! Small accounts require more conservative settings due to:
//! - Higher relative impact of fees
//! - Less room for diversification
//! - Need for capital preservation to grow

/// Capital tier classification
#[derive(Debug, Clone, Copy, PartialEq)]
pub enum CapitalTier {
    /// $0 - $100: Insufficient capital, trading disabled
    Micro,
    /// $100 - $500: Ultra conservative, 1 position max
    Tiny,
    /// $500 - $2,000: Conservative, 2 positions max
    Small,
    /// $2,000 - $5,000: Balanced approach
    Medium,
    /// $5,000 - $25,000: Standard parameters
    Standard,
    /// $25,000+: Full trading capabilities
    Large,
}

impl CapitalTier {
    /// Determine tier from portfolio value
    pub fn from_portfolio(value: f64) -> Self {
        if value < 100.0 {
            CapitalTier::Micro
        } else if value < 500.0 {
            CapitalTier::Tiny
        } else if value < 2000.0 {
            CapitalTier::Small
        } else if value < 5000.0 {
            CapitalTier::Medium
        } else if value < 25000.0 {
            CapitalTier::Standard
        } else {
            CapitalTier::Large
        }
    }

    /// Maximum positions allowed for this tier
    pub fn max_positions(&self) -> usize {
        match self {
            CapitalTier::Micro => 0,    // Trading disabled
            CapitalTier::Tiny => 1,     // Single position
            CapitalTier::Small => 2,    // Limited diversification
            CapitalTier::Medium => 3,   // Moderate diversification
            CapitalTier::Standard => 4, // Good diversification
            CapitalTier::Large => 5,    // Full diversification
        }
    }

    /// Maximum % of portfolio in a single position
    pub fn max_position_percent(&self) -> f64 {
        match self {
            CapitalTier::Micro => 0.0,   // No trading
            CapitalTier::Tiny => 80.0,   // Concentrated (1 position)
            CapitalTier::Small => 50.0,  // Semi-concentrated
            CapitalTier::Medium => 35.0, // Balanced
            CapitalTier::Standard => 25.0, // Standard
            CapitalTier::Large => 20.0,  // Well diversified
        }
    }

    /// Risk % per trade (scales with capital)
    pub fn risk_per_trade_percent(&self) -> f64 {
        match self {
            CapitalTier::Micro => 0.0,   // No trading
            CapitalTier::Tiny => 0.5,    // Ultra conservative
            CapitalTier::Small => 1.0,   // Conservative
            CapitalTier::Medium => 1.5,  // Moderate
            CapitalTier::Standard => 2.0, // Standard Kelly
            CapitalTier::Large => 2.0,   // Standard Kelly
        }
    }

    /// Whether trading is allowed at this tier
    pub fn can_trade(&self) -> bool {
        !matches!(self, CapitalTier::Micro)
    }

    /// Entry threshold multiplier (higher = more selective)
    pub fn entry_threshold_multiplier(&self) -> f64 {
        match self {
            CapitalTier::Micro => 1.5,   // Very selective (if enabled)
            CapitalTier::Tiny => 1.3,    // More selective
            CapitalTier::Small => 1.15,  // Slightly selective
            CapitalTier::Medium => 1.0,  // Normal
            CapitalTier::Standard => 1.0, // Normal
            CapitalTier::Large => 0.95,  // Slightly less selective (more opportunities)
        }
    }

    /// Get tier recommendation message
    pub fn recommendation(&self) -> &'static str {
        match self {
            CapitalTier::Micro =>
                "MICRO (<$100): Insufficient capital. Add funds to begin trading.",
            CapitalTier::Tiny =>
                "TINY ($100-$500): Ultra-conservative mode. Single position, tight risk controls.",
            CapitalTier::Small =>
                "SMALL ($500-$2K): Conservative mode. Focus on capital preservation.",
            CapitalTier::Medium =>
                "MEDIUM ($2K-$5K): Balanced mode. Good risk/reward with moderate diversification.",
            CapitalTier::Standard =>
                "STANDARD ($5K-$25K): Full trading capabilities. Standard risk parameters.",
            CapitalTier::Large =>
                "LARGE ($25K+): Full capabilities with enhanced diversification.",
        }
    }

    /// Get tier name as string
    pub fn name(&self) -> &'static str {
        match self {
            CapitalTier::Micro => "MICRO",
            CapitalTier::Tiny => "TINY",
            CapitalTier::Small => "SMALL",
            CapitalTier::Medium => "MEDIUM",
            CapitalTier::Standard => "STANDARD",
            CapitalTier::Large => "LARGE",
        }
    }
}

/// Tier-adjusted parameters for trading
#[derive(Debug, Clone)]
pub struct TierParameters {
    pub tier: CapitalTier,
    pub max_positions: usize,
    pub max_position_percent: f64,
    pub risk_per_trade_percent: f64,
    pub can_trade: bool,
    pub entry_threshold_multiplier: f64,
    pub recommendation: String,
}

impl TierParameters {
    /// Calculate tier parameters for a given portfolio value
    pub fn for_portfolio(value: f64) -> Self {
        let tier = CapitalTier::from_portfolio(value);
        Self {
            tier,
            max_positions: tier.max_positions(),
            max_position_percent: tier.max_position_percent(),
            risk_per_trade_percent: tier.risk_per_trade_percent(),
            can_trade: tier.can_trade(),
            entry_threshold_multiplier: tier.entry_threshold_multiplier(),
            recommendation: tier.recommendation().to_string(),
        }
    }
}

/// Fee tier based on 30-day trading volume
#[derive(Debug, Clone, Copy)]
pub struct FeeTier {
    pub taker_fee_percent: f64,
    pub maker_fee_percent: f64,
}

impl FeeTier {
    /// Get fee tier from 30-day volume
    pub fn from_volume(volume_30d: f64) -> Self {
        if volume_30d < 1_000.0 {
            FeeTier { taker_fee_percent: 0.60, maker_fee_percent: 0.40 }
        } else if volume_30d < 10_000.0 {
            FeeTier { taker_fee_percent: 0.40, maker_fee_percent: 0.25 }
        } else if volume_30d < 50_000.0 {
            FeeTier { taker_fee_percent: 0.25, maker_fee_percent: 0.15 }
        } else {
            FeeTier { taker_fee_percent: 0.20, maker_fee_percent: 0.10 }
        }
    }

    /// Calculate round-trip fee (buy + sell) as percentage
    pub fn round_trip_percent(&self) -> f64 {
        self.taker_fee_percent * 2.0
    }

    /// Calculate minimum profitable TP given current fees
    /// Returns the TP % needed to make target_net_profit after fees
    pub fn min_profitable_tp(&self, target_net_profit_percent: f64) -> f64 {
        self.round_trip_percent() + target_net_profit_percent
    }

    /// Calculate minimum position size for trade to be worthwhile
    /// Ensures expected profit exceeds a minimum dollar threshold
    pub fn min_position_for_profit(&self, expected_move_percent: f64, min_profit_usd: f64) -> f64 {
        let net_profit_percent = expected_move_percent - self.round_trip_percent();
        if net_profit_percent <= 0.0 {
            f64::MAX // Position would need to be infinite (not profitable)
        } else {
            min_profit_usd / (net_profit_percent / 100.0)
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_tier_classification() {
        assert_eq!(CapitalTier::from_portfolio(50.0), CapitalTier::Micro);
        assert_eq!(CapitalTier::from_portfolio(100.0), CapitalTier::Tiny);
        assert_eq!(CapitalTier::from_portfolio(500.0), CapitalTier::Small);
        assert_eq!(CapitalTier::from_portfolio(2000.0), CapitalTier::Medium);
        assert_eq!(CapitalTier::from_portfolio(5000.0), CapitalTier::Standard);
        assert_eq!(CapitalTier::from_portfolio(25000.0), CapitalTier::Large);
        assert_eq!(CapitalTier::from_portfolio(100000.0), CapitalTier::Large);
    }

    #[test]
    fn test_tier_parameters() {
        // Micro tier cannot trade
        let micro = TierParameters::for_portfolio(50.0);
        assert!(!micro.can_trade);
        assert_eq!(micro.max_positions, 0);

        // Tiny tier is ultra conservative
        let tiny = TierParameters::for_portfolio(300.0);
        assert!(tiny.can_trade);
        assert_eq!(tiny.max_positions, 1);
        assert_eq!(tiny.risk_per_trade_percent, 0.5);

        // Standard tier has normal parameters
        let standard = TierParameters::for_portfolio(10000.0);
        assert!(standard.can_trade);
        assert_eq!(standard.max_positions, 4);
        assert_eq!(standard.risk_per_trade_percent, 2.0);
    }

    #[test]
    fn test_fee_tiers() {
        let low_volume = FeeTier::from_volume(500.0);
        assert_eq!(low_volume.taker_fee_percent, 0.60);
        assert_eq!(low_volume.round_trip_percent(), 1.2);

        let high_volume = FeeTier::from_volume(100_000.0);
        assert_eq!(high_volume.taker_fee_percent, 0.20);
        assert_eq!(high_volume.round_trip_percent(), 0.4);
    }

    #[test]
    fn test_min_profitable_tp() {
        let fees = FeeTier::from_volume(500.0); // 0.6% taker

        // To make 1% net profit with 1.2% round-trip fees
        let min_tp = fees.min_profitable_tp(1.0);
        assert!((min_tp - 2.2).abs() < 0.01); // 1.2% fees + 1% profit = 2.2% TP needed
    }

    #[test]
    fn test_min_position_for_profit() {
        let fees = FeeTier::from_volume(500.0); // 1.2% round-trip

        // If expected move is 3%, fees are 1.2%, net profit is 1.8%
        // To make $1 profit: $1 / 0.018 = $55.56 minimum position
        let min_pos = fees.min_position_for_profit(3.0, 1.0);
        assert!((min_pos - 55.56).abs() < 1.0);

        // If expected move equals fees, no profit possible
        let impossible = fees.min_position_for_profit(1.2, 1.0);
        assert_eq!(impossible, f64::MAX);
    }

    #[test]
    fn test_tier_progression() {
        // Verify tiers get progressively more aggressive
        let tiers = [
            CapitalTier::Micro,
            CapitalTier::Tiny,
            CapitalTier::Small,
            CapitalTier::Medium,
            CapitalTier::Standard,
            CapitalTier::Large,
        ];

        let mut prev_positions = 0;
        for tier in tiers.iter().skip(1) {
            let positions = tier.max_positions();
            assert!(positions >= prev_positions,
                "Tier {:?} should have >= positions than previous", tier);
            prev_positions = positions;
        }
    }
}
