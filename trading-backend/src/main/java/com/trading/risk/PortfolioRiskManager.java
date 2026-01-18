package com.trading.risk;

import com.trading.api.AlpacaClient;
import com.trading.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Portfolio-level risk management.
 * Monitors overall portfolio health and triggers emergency actions.
 */
public class PortfolioRiskManager {
    private static final Logger logger = LoggerFactory.getLogger(PortfolioRiskManager.class);
    
    private final Config config;
    private final double initialCapital;
    
    public PortfolioRiskManager(Config config, double initialCapital) {
        this.config = config;
        this.initialCapital = initialCapital;
    }
    
    /**
     * Check if trading should be halted due to portfolio-level stop loss.
     * @param currentEquity Current total portfolio equity
     * @return true if stop loss hit
     */
    public boolean shouldHaltTrading(double currentEquity) {
        if (!config.isPortfolioStopLossEnabled()) {
            return false;
        }
        
        double portfolioLoss = ((currentEquity - initialCapital) / initialCapital) * 100;
        
        if (portfolioLoss < -config.getPortfolioStopLossPercent()) {
            logger.error("🛑 PORTFOLIO STOP LOSS HIT: {:.2f}% (limit: -{:.1f}%)", 
                portfolioLoss, config.getPortfolioStopLossPercent());
            logger.error("   Initial Capital: ${:.2f}", initialCapital);
            logger.error("   Current Equity: ${:.2f}", currentEquity);
            logger.error("   Total Loss: ${:.2f}", currentEquity - initialCapital);
            return true;
        }
        
        // Log warning if approaching stop loss
        if (portfolioLoss < -(config.getPortfolioStopLossPercent() * 0.8)) {
            logger.warn("⚠️ Approaching portfolio stop loss: {:.2f}% (limit: -{:.1f}%)", 
                portfolioLoss, config.getPortfolioStopLossPercent());
        }
        
        return false;
    }
    
    /**
     * Assess overall portfolio risk.
     */
    public PortfolioRisk assessRisk(AlpacaClient client, double currentEquity) {
        try {
            var positions = client.getPositions();
            
            double totalValue = 0;
            double totalUnrealizedPnL = 0;
            double largestLoss = 0;
            String largestLoser = "";
            
            for (var position : positions) {
                double marketValue = position.marketValue();
                double unrealizedPnL = position.unrealizedPL();
                String symbol = position.symbol();
                
                totalValue += marketValue;
                totalUnrealizedPnL += unrealizedPnL;
                
                if (unrealizedPnL < largestLoss) {
                    largestLoss = unrealizedPnL;
                    largestLoser = symbol;
                }
            }
            
            double pnlPercent = (totalUnrealizedPnL / initialCapital) * 100;
            
            return new PortfolioRisk(
                currentEquity,
                totalUnrealizedPnL,
                pnlPercent,
                positions.size(),
                largestLoss,
                largestLoser
            );
            
        } catch (Exception e) {
            logger.error("Failed to assess portfolio risk", e);
            return new PortfolioRisk(currentEquity, 0, 0, 0, 0, "");
        }
    }
    
    /**
     * Portfolio risk assessment result.
     */
    public record PortfolioRisk(
        double totalEquity,
        double totalPnL,
        double pnlPercent,
        int positionCount,
        double largestLoss,
        String largestLoser
    ) {
        public String getSummary() {
            return String.format(
                "Portfolio: $%.2f | P&L: $%.2f (%.2f%%) | Positions: %d | Largest Loss: %s ($%.2f)",
                totalEquity, totalPnL, pnlPercent, positionCount, largestLoser, largestLoss
            );
        }
    }
}
