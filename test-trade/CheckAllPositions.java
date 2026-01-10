import com.trading.api.AlpacaClient;
import com.trading.config.Config;

public class CheckAllPositions {
    public static void main(String[] args) throws Exception {
        var config = new Config();
        var client = new AlpacaClient(config);
        
        // Get ALL positions
        var positions = client.getAllPositions();
        
        System.out.println("========================================");
        System.out.println("ALL POSITIONS IN YOUR ALPACA ACCOUNT:");
        System.out.println("========================================");
        
        if (positions.isEmpty()) {
            System.out.println("No positions found.");
        } else {
            for (var pos : positions) {
                System.out.println("Symbol: " + pos.symbol());
                System.out.println("  Quantity: " + pos.quantity());
                System.out.println("  Entry Price: $" + pos.avgEntryPrice());
                System.out.println("  Current Value: $" + pos.marketValue());
                System.out.println("  P/L: $" + pos.unrealizedPL());
                System.out.println("---");
            }
        }
        System.out.println("Total Positions: " + positions.size());
    }
}
