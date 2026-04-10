package com.trading.persistence;

import com.trading.config.TradingConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.time.Instant;
import java.util.concurrent.locks.StampedLock;

/**
 * SQLite database for persisting trade history.
 * Modernized with Java 23 concurrency primitives (StampedLock) for production-grade performance.
 * 
 * Thread-Safety: Uses StampedLock with optimistic reads for high-throughput read operations
 * and write locks for mutations. This is significantly faster than synchronized methods.
 */
public class TradeDatabase {
    private static final Logger logger = LoggerFactory.getLogger(TradeDatabase.class);

    private final Connection connection;
    private final StampedLock lock = new StampedLock();

    /** Resolves the DB path: uses DATA_DIR env var if set, otherwise current directory. */
    private static String resolveDbPath() {
        String dataDir = System.getenv("DATA_DIR");
        if (dataDir != null && !dataDir.isBlank()) {
            java.io.File dir = new java.io.File(dataDir);
            if (!dir.exists()) dir.mkdirs();
            return dataDir + "/trades.db";
        }
        return "trades.db";
    }

    public TradeDatabase() {
        this(resolveDbPath());
    }
    
    public TradeDatabase(String dbPath) {
        String dbUrl = "jdbc:sqlite:" + dbPath;
        try {
            connection = DriverManager.getConnection(dbUrl);
            createTables();
            logger.info("Trade database initialized: {} with StampedLock concurrency", dbPath);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to initialize database", e);
        }
    }
    
    private void createTables() throws SQLException {
        String createSql = """
            CREATE TABLE IF NOT EXISTS trades (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                symbol TEXT NOT NULL,
                strategy TEXT,
                profile TEXT,
                broker TEXT DEFAULT 'alpaca',
                entry_time TEXT NOT NULL,
                exit_time TEXT,
                entry_price REAL NOT NULL,
                exit_price REAL,
                quantity REAL NOT NULL,
                pnl REAL,
                status TEXT NOT NULL,
                stop_loss REAL,
                take_profit REAL,
                created_at TEXT DEFAULT CURRENT_TIMESTAMP
            )
            """;

        // Add index for common queries
        String createIndexSql = """
            CREATE INDEX IF NOT EXISTS idx_symbol_status
            ON trades(symbol, status)
            """;

        String createBrokerIndexSql = """
            CREATE INDEX IF NOT EXISTS idx_broker_status
            ON trades(broker, status)
            """;

        long stamp = lock.writeLock();
        try (var stmt = connection.createStatement()) {
            stmt.execute(createSql);
            stmt.execute(createIndexSql);
            stmt.execute(createBrokerIndexSql);
        } finally {
            lock.unlockWrite(stamp);
        }

        runMigration("ALTER TABLE trades ADD COLUMN profile TEXT",
            "Schema migration: Added 'profile' column");
        runMigration("ALTER TABLE trades ADD COLUMN broker TEXT DEFAULT 'alpaca'",
            "Schema migration: Added 'broker' column (existing rows default to 'alpaca')");
    }

    /**
     * Execute a DDL migration statement safely, ignoring "duplicate column name" errors
     * (which mean the column already exists) and logging warnings for other failures.
     */
    private void runMigration(String ddl, String description) {
        long stamp = lock.writeLock();
        try (var stmt = connection.createStatement()) {
            stmt.execute(ddl);
            logger.info("{}", description);
        } catch (SQLException e) {
            if (e.getMessage() != null && e.getMessage().contains("duplicate column name")) {
                // Expected: column already exists — silently ignore
            } else {
                logger.warn("Migration '{}' failed (non-fatal): {}", description, e.getMessage());
            }
        } finally {
            lock.unlockWrite(stamp);
        }
    }
    
    /**
     * Record a new trade with write lock.
     * Modern approach: explicit lock management with try-finally.
     */
    public void recordTrade(String symbol, String strategy, String profile, String broker,
                           Instant entryTime, double entryPrice, double quantity,
                           double stopLoss, double takeProfit) {
        String sql = """
            INSERT INTO trades (symbol, strategy, profile, broker, entry_time, entry_price, quantity, status, stop_loss, take_profit)
            VALUES (?, ?, ?, ?, ?, ?, ?, 'OPEN', ?, ?)
            """;

        long stamp = lock.writeLock();
        try (var stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, symbol);
            stmt.setString(2, strategy);
            stmt.setString(3, profile);
            stmt.setString(4, broker);
            stmt.setString(5, entryTime.toString());
            stmt.setDouble(6, entryPrice);
            stmt.setDouble(7, quantity);
            stmt.setDouble(8, stopLoss);
            stmt.setDouble(9, takeProfit);
            stmt.executeUpdate();

            // Structured logging (Phase 4)
            logger.atInfo()
                .addKeyValue("symbol", symbol)
                .addKeyValue("profile", profile)
                .addKeyValue("broker", broker)
                .addKeyValue("price", entryPrice)
                .addKeyValue("quantity", quantity)
                .log("Trade recorded");

        } catch (SQLException e) {
            logger.error("Failed to record trade for {}", symbol, e);
            throw new RuntimeException("Database write failed", e);
        } finally {
            lock.unlockWrite(stamp);
        }
    }
    
    /**
     * Simplified recordTrade for testing - records a trade with minimal parameters.
     * Uses configured stop-loss and take-profit percentages.
     * @param symbol Stock symbol
     * @param quantity Number of shares
     * @param price Entry price
     * @param side "buy" or "sell"
     * @param date Trade date
     */
    public void recordTrade(String symbol, double quantity, double price, String side, java.time.LocalDate date) {
        Instant entryTime = date.atStartOfDay(java.time.ZoneId.of("America/New_York")).toInstant();
        
        // Use configured values from TradingConfig
        TradingConfig config = TradingConfig.getInstance();
        double stopLoss = price * (1.0 - config.getStopLossDecimal());
        double takeProfit = price * (1.0 + config.getTakeProfitDecimal());
        
        if ("sell".equalsIgnoreCase(side)) {
            // For sells, we need to get the entry price from the open trade to calculate actual PnL
            double entryPrice = getOpenTradeEntryPrice(symbol);
            double actualPnL = (price - entryPrice) * quantity; // Correct PnL calculation
            closeTrade(symbol, entryTime, price, actualPnL, "test");
        } else {
            // For buys, record new trade
            recordTrade(symbol, "TEST", "test", "test", entryTime, price, quantity, stopLoss, takeProfit);
        }
    }
    
    /**
     * Get entry price for the most recent open trade of a symbol.
     */
    private double getOpenTradeEntryPrice(String symbol) {
        String sql = "SELECT entry_price FROM trades WHERE symbol = ? AND status = 'OPEN' ORDER BY entry_time DESC LIMIT 1";
        long stamp = lock.tryOptimisticRead();
        
        try (var stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, symbol);
            var rs = stmt.executeQuery();
            if (rs.next()) {
                double price = rs.getDouble("entry_price");
                if (lock.validate(stamp)) {
                    return price;
                }
                // Retry with read lock if optimistic read failed
                stamp = lock.readLock();
                try {
                    rs = stmt.executeQuery();
                    if (rs.next()) {
                        return rs.getDouble("entry_price");
                    }
                } finally {
                    lock.unlockRead(stamp);
                }
            }
        } catch (SQLException e) {
            logger.error("Failed to get entry price for {}: {}", symbol, e.getMessage());
        }
        return 0.0; // Default if no open trade found
    }
    
    /**
     * Returns true if there is already an OPEN trade record for this symbol.
     * Used during startup reconciliation to avoid double-inserting existing positions.
     */
    public boolean hasOpenTrade(String symbol, String broker) {
        String sql = "SELECT COUNT(*) as cnt FROM trades WHERE symbol = ? AND broker = ? AND status = 'OPEN'";
        long stamp = lock.tryOptimisticRead();
        try (var stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, symbol);
            stmt.setString(2, broker);
            try (var rs = stmt.executeQuery()) {
                int count = rs.next() ? rs.getInt("cnt") : 0;
                if (lock.validate(stamp)) return count > 0;
            }
        } catch (SQLException e) {
            logger.debug("hasOpenTrade query failed for {}: {}", symbol, e.getMessage());
        }
        // Fallback with read lock
        stamp = lock.readLock();
        try (var stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, symbol);
            stmt.setString(2, broker);
            try (var rs = stmt.executeQuery()) {
                return rs.next() && rs.getInt("cnt") > 0;
            }
        } catch (SQLException e) {
            logger.error("hasOpenTrade fallback failed for {}: {}", symbol, e.getMessage());
            return false;
        } finally {
            lock.unlockRead(stamp);
        }
    }

    /**
     * Close a trade with write lock.
     */
    public void closeTrade(String symbol, Instant exitTime, double exitPrice, double pnl, String broker) {
        String sql = """
            UPDATE trades SET exit_time = ?, exit_price = ?, pnl = ?, status = 'CLOSED'
            WHERE symbol = ? AND broker = ? AND status = 'OPEN'
            AND entry_time = (SELECT MAX(entry_time) FROM trades WHERE symbol = ? AND broker = ? AND status = 'OPEN')
            """;

        long stamp = lock.writeLock();
        try (var stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, exitTime.toString());
            stmt.setDouble(2, exitPrice);
            stmt.setDouble(3, pnl);
            stmt.setString(4, symbol);
            stmt.setString(5, broker);
            stmt.setString(6, symbol); // For subquery
            stmt.setString(7, broker); // For subquery
            int updated = stmt.executeUpdate();
            
            if (updated > 0) {
                logger.atInfo()
                    .addKeyValue("symbol", symbol)
                    .addKeyValue("exitPrice", exitPrice)
                    .addKeyValue("pnl", pnl)
                    .log("Trade closed");
            } else {
                logger.warn("No open trade found to close for symbol: {}", symbol);
            }
        } catch (SQLException e) {
            logger.error("Failed to close trade for {}", symbol, e);
            throw new RuntimeException("Database write failed", e);
        } finally {
            lock.unlockWrite(stamp);
        }
    }
    
    /**
     * Get total P&L with optimistic read.
     * This is the key advantage of StampedLock - reads don't block each other or writers.
     */
    public double getTotalPnL() {
        String sql = "SELECT COALESCE(SUM(pnl), 0) as total FROM trades WHERE status = 'CLOSED'";
        
        // Try optimistic read first (no lock)
        long stamp = lock.tryOptimisticRead();
        double result = queryDouble(sql, "total");
        
        // Validate optimistic read
        if (!lock.validate(stamp)) {
            // Optimistic read failed, fall back to read lock
            stamp = lock.readLock();
            try {
                result = queryDouble(sql, "total");
            } finally {
                lock.unlockRead(stamp);
            }
        }
        
        return result;
    }
    
    /**
     * Get total trades count with optimistic read.
     */
    public int getTotalTrades() {
        String sql = "SELECT COUNT(*) as count FROM trades WHERE status = 'CLOSED'";
        
        long stamp = lock.tryOptimisticRead();
        int result = queryInt(sql, "count");
        
        if (!lock.validate(stamp)) {
            stamp = lock.readLock();
            try {
                result = queryInt(sql, "count");
            } finally {
                lock.unlockRead(stamp);
            }
        }
        
        return result;
    }
    
    /**
     * Trade statistics record for dashboard.
     */
    public record TradeStatistics(
        int totalTrades,
        double winRate,
        double totalPnL
    ) {}
    
    /**
     * Get aggregated trade statistics for dashboard.
     */
    public TradeStatistics getTradeStatistics() {
        int total = getTotalTrades();
        double pnl = getTotalPnL();
        
        // Calculate win rate
        String winSql = "SELECT COUNT(*) as count FROM trades WHERE status = 'CLOSED' AND pnl > 0";
        long stamp = lock.tryOptimisticRead();
        int wins = queryInt(winSql, "count");
        
        if (!lock.validate(stamp)) {
            stamp = lock.readLock();
            try {
                wins = queryInt(winSql, "count");
            } finally {
                lock.unlockRead(stamp);
            }
        }
        
        double winRate = total > 0 ? (double) wins / total : 0.0;
        
        return new TradeStatistics(total, winRate, pnl);
    }
    
    /**
     * Symbol-specific statistics for position sizing.
     */
    public record SymbolStatistics(
        int totalTrades,
        double winRate,
        double avgWin,
        double avgLoss
    ) {}
    
    /**
     * Get statistics for a specific symbol.
     */
    public SymbolStatistics getSymbolStatistics(String symbol) {
        String totalSql = "SELECT COUNT(*) as count FROM trades WHERE symbol = ? AND status = 'CLOSED'";
        String winSql = "SELECT COUNT(*) as count FROM trades WHERE symbol = ? AND status = 'CLOSED' AND pnl > 0";
        String avgWinSql = "SELECT AVG(pnl) as avg FROM trades WHERE symbol = ? AND status = 'CLOSED' AND pnl > 0";
        String avgLossSql = "SELECT AVG(ABS(pnl)) as avg FROM trades WHERE symbol = ? AND status = 'CLOSED' AND pnl < 0";
        
        long stamp = lock.readLock();
        try {
            // Get total trades
            int total = 0;
            try (var stmt = connection.prepareStatement(totalSql)) {
                stmt.setString(1, symbol);
                var rs = stmt.executeQuery();
                if (rs.next()) {
                    total = rs.getInt("count");
                }
            }
            
            if (total == 0) {
                return null; // No trades for this symbol
            }
            
            // Get wins
            int wins = 0;
            try (var stmt = connection.prepareStatement(winSql)) {
                stmt.setString(1, symbol);
                var rs = stmt.executeQuery();
                if (rs.next()) {
                    wins = rs.getInt("count");
                }
            }
            
            // Get average win
            double avgWin = 0.0;
            try (var stmt = connection.prepareStatement(avgWinSql)) {
                stmt.setString(1, symbol);
                var rs = stmt.executeQuery();
                if (rs.next()) {
                    avgWin = rs.getDouble("avg");
                }
            }
            
            // Get average loss
            double avgLoss = 0.0;
            try (var stmt = connection.prepareStatement(avgLossSql)) {
                stmt.setString(1, symbol);
                var rs = stmt.executeQuery();
                if (rs.next()) {
                    avgLoss = rs.getDouble("avg");
                }
            }
            
            double winRate = (double) wins / total;
            return new SymbolStatistics(total, winRate, avgWin, avgLoss);
            
        } catch (SQLException e) {
            logger.error("Failed to get symbol statistics for {}", symbol, e);
            return null;
        } finally {
            lock.unlockRead(stamp);
        }
    }
    
    /**
     * Check if there was a buy order for this symbol today (PDT detection).
     */
    public boolean hasBuyToday(String symbol, java.time.LocalDate date) {
        String sql = """
            SELECT COUNT(*) as count FROM trades 
            WHERE symbol = ? 
            AND DATE(entry_time) = ? 
            AND status IN ('OPEN', 'CLOSED')
            """;
        
        long stamp = lock.readLock();
        try (var stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, symbol);
            stmt.setString(2, date.toString());
            var rs = stmt.executeQuery();
            if (rs.next()) {
                return rs.getInt("count") > 0;
            }
        } catch (SQLException e) {
            logger.error("Failed to check buy today for {}", symbol, e);
        } finally {
            lock.unlockRead(stamp);
        }
        return false;
    }
    
    /**
     * Count day trades in the last N business days.
     */
    public int getDayTradesInLastNBusinessDays(int businessDays) {
        int calendarDays = (int)(businessDays * 1.5);
        
        String sql = """
            SELECT COUNT(*) as count FROM trades 
            WHERE status = 'CLOSED'
            AND DATE(entry_time) = DATE(exit_time)
            AND entry_time >= datetime('now', '-' || ? || ' days')
            """;
        
        long stamp = lock.readLock();
        try (var stmt = connection.prepareStatement(sql)) {
            stmt.setInt(1, calendarDays);
            var rs = stmt.executeQuery();
            if (rs.next()) {
                return rs.getInt("count");
            }
        } catch (SQLException e) {
            logger.error("Failed to count day trades", e);
        } finally {
            lock.unlockRead(stamp);
        }
        return 0;
    }
    
    // Helper methods for optimistic reads
    private double queryDouble(String sql, String columnName) {
        try (var stmt = connection.createStatement();
             var rs = stmt.executeQuery(sql)) {
            if (rs.next()) {
                return rs.getDouble(columnName);
            }
        } catch (SQLException e) {
            logger.error("Query failed: {}", sql, e);
        }
        return 0.0;
    }
    
    private int queryInt(String sql, String columnName) {
        try (var stmt = connection.createStatement();
             var rs = stmt.executeQuery(sql)) {
            if (rs.next()) {
                return rs.getInt(columnName);
            }
        } catch (SQLException e) {
            logger.error("Query failed: {}", sql, e);
        }
        return 0;
    }
    
    /**
     * Get recent trades for execution archive.
     * @param limit Maximum number of trades to return
     * @return List of recent trades as maps
     */
    public java.util.List<java.util.Map<String, Object>> getRecentTrades(int limit) {
        long stamp = lock.tryOptimisticRead();
        java.util.List<java.util.Map<String, Object>> trades = new java.util.ArrayList<>();
        
        try {
            String sql = "SELECT symbol, strategy, profile, broker, entry_time, entry_price, quantity, " +
                        "exit_time, exit_price, pnl, stop_loss, take_profit, status " +
                        "FROM trades ORDER BY entry_time DESC LIMIT ?";

            try (PreparedStatement stmt = connection.prepareStatement(sql)) {
                stmt.setInt(1, limit);
                ResultSet rs = stmt.executeQuery();

                while (rs.next()) {
                    java.util.Map<String, Object> trade = new java.util.HashMap<>();
                    trade.put("symbol", rs.getString("symbol"));
                    trade.put("strategy", rs.getString("strategy"));
                    trade.put("profile", rs.getString("profile"));
                    trade.put("broker", rs.getString("broker"));
                    trade.put("entryTime", rs.getString("entry_time"));
                    trade.put("entryPrice", rs.getDouble("entry_price"));
                    trade.put("quantity", rs.getDouble("quantity"));
                    trade.put("exitTime", rs.getString("exit_time"));
                    trade.put("exitPrice", rs.getDouble("exit_price"));
                    trade.put("status", rs.getString("status"));
                    // Only include pnl for closed trades (avoids 0.0 masquerading as closed)
                    double pnlVal = rs.getDouble("pnl");
                    if (!rs.wasNull()) trade.put("pnl", pnlVal);
                    trade.put("stopLoss", rs.getDouble("stop_loss"));
                    trade.put("takeProfit", rs.getDouble("take_profit"));
                    trades.add(trade);
                }
            }
            
            if (!lock.validate(stamp)) {
                stamp = lock.readLock();
                try {
                    // Re-read if validation failed
                    return getRecentTrades(limit);
                } finally {
                    lock.unlockRead(stamp);
                }
            }
        } catch (SQLException e) {
            logger.error("Failed to get recent trades", e);
        }
        
        return trades;
    }
    
    /**
     * Close any OPEN trade records for symbols no longer held on the broker.
     * Called during portfolio reconciliation to prevent ghost "OPEN" records accumulating
     * when a sell order fills between the reconcile recovery-insert and the next cycle.
     * Marks orphaned records as CANCELLED (null exit price/pnl) so they appear in history
     * but don't count toward P&L stats.
     * Only closes records older than minAgeMs to avoid race with just-inserted records.
     */
    public int closeOrphanedOpenTrades(String broker, java.util.Set<String> liveSymbols, long minAgeMs) {
        if (liveSymbols == null) liveSymbols = java.util.Collections.emptySet();
        // Build NOT IN clause — safe because symbols are validated ticker strings
        String placeholders = liveSymbols.isEmpty() ? "'__none__'" :
            liveSymbols.stream().map(s -> "?").collect(java.util.stream.Collectors.joining(","));
        String sql = "UPDATE trades SET status = 'CANCELLED', exit_time = ? " +
            "WHERE status = 'OPEN' AND broker = ? AND symbol NOT IN (" + placeholders + ") " +
            "AND created_at <= datetime('now', '-' || ? || ' seconds')";

        long stamp = lock.writeLock();
        try (var stmt = connection.prepareStatement(sql)) {
            int idx = 1;
            stmt.setString(idx++, java.time.Instant.now().toString());
            stmt.setString(idx++, broker);
            for (String sym : liveSymbols) stmt.setString(idx++, sym);
            stmt.setLong(idx, minAgeMs / 1000);
            int updated = stmt.executeUpdate();
            if (updated > 0) {
                logger.warn("Closed {} orphaned OPEN trade record(s) for symbols no longer on broker", updated);
            }
            return updated;
        } catch (SQLException e) {
            logger.error("Failed to close orphaned trades", e);
            return 0;
        } finally {
            lock.unlockWrite(stamp);
        }
    }

    /**
     * Close database connection.
     * Should be called on application shutdown.
     */
    public void close() {
        long stamp = lock.writeLock();
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
                logger.info("Database connection closed");
            }
        } catch (SQLException e) {
            logger.error("Error closing database", e);
        } finally {
            lock.unlockWrite(stamp);
        }
    }

    /**
     * Export all trades as a list of maps suitable for JSON serialization.
     * @param status Filter by status ("OPEN", "CLOSED") or null for all
     * @return List of trade records
     */
    public java.util.List<java.util.Map<String, Object>> exportTrades(String status) {
        var trades = new java.util.ArrayList<java.util.Map<String, Object>>();
        String sql = status != null
            ? "SELECT * FROM trades WHERE status = ? ORDER BY entry_time DESC"
            : "SELECT * FROM trades ORDER BY entry_time DESC";

        long stamp = lock.readLock();
        try (var stmt = status != null
                ? connection.prepareStatement(sql)
                : connection.prepareStatement(sql)) {
            if (status != null) {
                ((PreparedStatement) stmt).setString(1, status);
            }
            var rs = stmt.executeQuery();
            while (rs.next()) {
                var trade = new java.util.LinkedHashMap<String, Object>();
                trade.put("id", rs.getInt("id"));
                trade.put("symbol", rs.getString("symbol"));
                trade.put("strategy", rs.getString("strategy"));
                trade.put("profile", rs.getString("profile"));
                trade.put("broker", rs.getString("broker"));
                trade.put("entryTime", rs.getString("entry_time"));
                trade.put("exitTime", rs.getString("exit_time"));
                trade.put("entryPrice", rs.getDouble("entry_price"));
                trade.put("exitPrice", rs.getDouble("exit_price"));
                trade.put("quantity", rs.getDouble("quantity"));
                trade.put("pnl", rs.getDouble("pnl"));
                trade.put("status", rs.getString("status"));
                trade.put("stopLoss", rs.getDouble("stop_loss"));
                trade.put("takeProfit", rs.getDouble("take_profit"));
                trade.put("createdAt", rs.getString("created_at"));
                trades.add(trade);
            }
        } catch (SQLException e) {
            logger.error("Failed to export trades", e);
        } finally {
            lock.unlockRead(stamp);
        }
        return trades;
    }

    /**
     * Export all trades as CSV string.
     * @param status Filter by status ("OPEN", "CLOSED") or null for all
     * @return CSV string with header row
     */
    public String exportTradesAsCsv(String status) {
        var sb = new StringBuilder();
        sb.append("id,symbol,strategy,profile,broker,entry_time,exit_time,entry_price,exit_price,quantity,pnl,status,stop_loss,take_profit,created_at\n");

        var trades = exportTrades(status);
        for (var trade : trades) {
            sb.append(trade.get("id")).append(',');
            sb.append(csvEscape(trade.get("symbol"))).append(',');
            sb.append(csvEscape(trade.get("strategy"))).append(',');
            sb.append(csvEscape(trade.get("profile"))).append(',');
            sb.append(csvEscape(trade.get("broker"))).append(',');
            sb.append(csvEscape(trade.get("entryTime"))).append(',');
            sb.append(csvEscape(trade.get("exitTime"))).append(',');
            sb.append(trade.get("entryPrice")).append(',');
            sb.append(trade.get("exitPrice")).append(',');
            sb.append(trade.get("quantity")).append(',');
            sb.append(trade.get("pnl")).append(',');
            sb.append(csvEscape(trade.get("status"))).append(',');
            sb.append(trade.get("stopLoss")).append(',');
            sb.append(trade.get("takeProfit")).append(',');
            sb.append(csvEscape(trade.get("createdAt"))).append('\n');
        }
        return sb.toString();
    }

    private static String csvEscape(Object value) {
        if (value == null) return "";
        String str = value.toString();
        if (str.contains(",") || str.contains("\"") || str.contains("\n")) {
            return "\"" + str.replace("\"", "\"\"") + "\"";
        }
        return str;
    }
}
