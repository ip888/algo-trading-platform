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

        // Create table and base index (symbol+status always exists)
        long stamp = lock.writeLock();
        try (var stmt = connection.createStatement()) {
            stmt.execute(createSql);
            stmt.execute("""
                CREATE INDEX IF NOT EXISTS idx_symbol_status
                ON trades(symbol, status)
                """);
        } finally {
            lock.unlockWrite(stamp);
        }

        // Additive migrations — safe to re-run on existing DBs (duplicate-column errors ignored)
        runMigration("ALTER TABLE trades ADD COLUMN profile TEXT",
            "Schema migration: Added 'profile' column");
        runMigration("ALTER TABLE trades ADD COLUMN broker TEXT DEFAULT 'alpaca'",
            "Schema migration: Added 'broker' column (existing rows default to 'alpaca')");

        // Broker index must be created AFTER the broker column migration above
        runMigration("CREATE INDEX IF NOT EXISTS idx_broker_status ON trades(broker, status)",
            "Schema migration: Added idx_broker_status index");

        runMigration("ALTER TABLE trades ADD COLUMN partial_exits_executed INTEGER DEFAULT 0",
            "Schema migration: Added 'partial_exits_executed' column (bitmask of partial-exit levels fired)");

        // Market-context columns recorded at entry — enables regime-conditioned win-rate analysis.
        runMigration("ALTER TABLE trades ADD COLUMN regime TEXT",
            "Schema migration: Added 'regime' column (market regime at entry time)");
        runMigration("ALTER TABLE trades ADD COLUMN vix_at_entry REAL",
            "Schema migration: Added 'vix_at_entry' column");
        runMigration("ALTER TABLE trades ADD COLUMN breadth_at_entry REAL",
            "Schema migration: Added 'breadth_at_entry' column (% sectors advancing at entry)");
        runMigration("ALTER TABLE trades ADD COLUMN entry_reason TEXT",
            "Schema migration: Added 'entry_reason' column (signal description at entry)");
        runMigration("ALTER TABLE trades ADD COLUMN exit_reason TEXT",
            "Schema migration: Added 'exit_reason' column (stop_loss|take_profit|eod_cleanup|etc)");

        // blocked_entries: persisted log of every rejected entry with the filter reason.
        // 30-day TTL — pruned at startup. Answers "what did the bot NOT trade and why?"
        runMigration("""
            CREATE TABLE IF NOT EXISTS blocked_entries (
                id       INTEGER PRIMARY KEY AUTOINCREMENT,
                ts       TEXT    NOT NULL DEFAULT (datetime('now')),
                symbol   TEXT    NOT NULL,
                profile  TEXT,
                reason   TEXT    NOT NULL,
                price    REAL,
                regime   TEXT,
                vix      REAL
            )""",
            "Schema migration: Added blocked_entries table");
        runMigration("CREATE INDEX IF NOT EXISTS idx_blocked_ts ON blocked_entries(ts)",
            "Schema migration: Added idx_blocked_ts index");

        // daily_summary: one row per trading day — permanent record, never pruned.
        runMigration("""
            CREATE TABLE IF NOT EXISTS daily_summary (
                date           TEXT PRIMARY KEY,
                trades         INTEGER DEFAULT 0,
                wins           INTEGER DEFAULT 0,
                losses         INTEGER DEFAULT 0,
                net_pnl        REAL    DEFAULT 0,
                regime         TEXT,
                vix_open       REAL,
                blocked_count  INTEGER DEFAULT 0,
                notes          TEXT
            )""",
            "Schema migration: Added daily_summary table");

        // regime_log: timestamp of every regime change with confidence and context.
        // 60-day TTL — pruned at startup.
        runMigration("""
            CREATE TABLE IF NOT EXISTS regime_log (
                id             INTEGER PRIMARY KEY AUTOINCREMENT,
                ts             TEXT    NOT NULL DEFAULT (datetime('now')),
                regime         TEXT    NOT NULL,
                confidence     REAL,
                breadth        REAL,
                trend          TEXT,
                volume_profile TEXT
            )""",
            "Schema migration: Added regime_log table");
        runMigration("CREATE INDEX IF NOT EXISTS idx_regime_ts ON regime_log(ts)",
            "Schema migration: Added idx_regime_ts index");

        // Prune old observability data on startup so the volume doesn't grow unbounded.
        pruneOldObservabilityData();

        // bot_state: lightweight key-value store for in-memory state that must survive restarts.
        // Covers: post-loss cooldowns, consecutive-loss counts, circuit-breaker drawdown.
        // Keys use namespaced prefixes: "cooldown:{symbol}", "consec_sl:{symbol}", "circuit:{broker}"
        runMigration("""
            CREATE TABLE IF NOT EXISTS bot_state (
                key   TEXT PRIMARY KEY,
                value TEXT NOT NULL,
                updated_at TEXT DEFAULT (datetime('now'))
            )""",
            "Schema migration: Added bot_state table for restart-safe in-memory state");
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
     * Record a trade with full market context — regime, VIX, breadth — for adaptive learning.
     * Call this from handleBuy() instead of the base recordTrade() so the bot can
     * query win rates conditioned on the market environment that was present at entry.
     */
    public void recordTradeWithContext(String symbol, String strategy, String profile, String broker,
                                       Instant entryTime, double entryPrice, double quantity,
                                       double stopLoss, double takeProfit,
                                       String regime, double vixAtEntry, double breadthAtEntry) {
        String sql = """
            INSERT INTO trades (symbol, strategy, profile, broker, entry_time, entry_price, quantity,
                                status, stop_loss, take_profit, regime, vix_at_entry, breadth_at_entry)
            VALUES (?, ?, ?, ?, ?, ?, ?, 'OPEN', ?, ?, ?, ?, ?)
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
            stmt.setString(10, regime);
            stmt.setDouble(11, vixAtEntry);
            stmt.setDouble(12, breadthAtEntry);
            stmt.executeUpdate();
            logger.atInfo()
                .addKeyValue("symbol", symbol)
                .addKeyValue("regime", regime)
                .addKeyValue("vix", vixAtEntry)
                .addKeyValue("breadth", breadthAtEntry)
                .log("Trade recorded with market context");
        } catch (SQLException e) {
            logger.error("Failed to record trade with context for {}", symbol, e);
            // Fall back to basic recordTrade so the trade isn't lost
            recordTrade(symbol, strategy, profile, broker, entryTime, entryPrice, quantity, stopLoss, takeProfit);
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
     * Returns true if a CLOSED trade for this symbol/broker exists with an exit_time
     * within {@code withinMillis} milliseconds of now.
     * Used to suppress orphan registration during broker settlement lag (T+0 to T+1 window):
     * when a position was just closed the broker may still report the shares as held.
     */
    public boolean wasRecentlyClosed(String symbol, String broker, long withinMillis) {
        String sql = "SELECT COUNT(*) as cnt FROM trades WHERE symbol = ? AND broker = ? " +
                     "AND status = 'CLOSED' AND exit_time >= ?";
        java.time.Instant cutoff = java.time.Instant.now().minusMillis(withinMillis);
        long stamp = lock.tryOptimisticRead();
        try (var stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, symbol);
            stmt.setString(2, broker);
            stmt.setString(3, cutoff.toString());
            try (var rs = stmt.executeQuery()) {
                int count = rs.next() ? rs.getInt("cnt") : 0;
                if (lock.validate(stamp)) return count > 0;
            }
        } catch (SQLException e) {
            logger.debug("wasRecentlyClosed optimistic query failed for {}: {}", symbol, e.getMessage());
        }
        // Fallback with read lock
        stamp = lock.readLock();
        try (var stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, symbol);
            stmt.setString(2, broker);
            stmt.setString(3, cutoff.toString());
            try (var rs = stmt.executeQuery()) {
                return rs.next() && rs.getInt("cnt") > 0;
            }
        } catch (SQLException e) {
            logger.error("wasRecentlyClosed fallback failed for {}: {}", symbol, e.getMessage());
            return false;
        } finally {
            lock.unlockRead(stamp);
        }
    }

    /**
     * Count OPEN trades for a symbol/broker combination.
     * Used to enforce the per-symbol entry cap.
     */
    public int countOpenTrades(String symbol, String broker) {
        String sql = "SELECT COUNT(*) as cnt FROM trades WHERE symbol = ? AND broker = ? AND status = 'OPEN'";
        long stamp = lock.tryOptimisticRead();
        try (var stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, symbol);
            stmt.setString(2, broker);
            try (var rs = stmt.executeQuery()) {
                int count = rs.next() ? rs.getInt("cnt") : 0;
                if (lock.validate(stamp)) return count;
            }
        } catch (SQLException e) {
            logger.debug("countOpenTrades failed for {}: {}", symbol, e.getMessage());
        }
        stamp = lock.readLock();
        try (var stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, symbol);
            stmt.setString(2, broker);
            try (var rs = stmt.executeQuery()) {
                return rs.next() ? rs.getInt("cnt") : 0;
            }
        } catch (SQLException e) {
            logger.error("countOpenTrades fallback failed: {}", e.getMessage());
            return 0;
        } finally {
            lock.unlockRead(stamp);
        }
    }

    /** A minimal record for restoring in-memory portfolio from the database on restart. */
    public record OpenTradeRecord(String symbol, double entryPrice, double quantity,
                                   double stopLoss, double takeProfit, java.time.Instant entryTime,
                                   int partialExitsExecuted) {}

    /**
     * Update the stop-loss on the most recent OPEN trade for (symbol, broker).
     * Used to persist trailing-stop trail-ups so they survive a restart — without this,
     * a restart silently resets the position to the original entry-time stop, undoing every
     * trail-up that locked profit.
     */
    public void updateStop(String symbol, String broker, double newStopLoss) {
        String sql = """
            UPDATE trades SET stop_loss = ?
            WHERE id = (SELECT id FROM trades WHERE symbol = ? AND broker = ? AND status = 'OPEN'
                        ORDER BY entry_time DESC LIMIT 1)
            """;
        long stamp = lock.writeLock();
        try (var stmt = connection.prepareStatement(sql)) {
            stmt.setDouble(1, newStopLoss);
            stmt.setString(2, symbol);
            stmt.setString(3, broker);
            stmt.executeUpdate();
        } catch (SQLException e) {
            logger.error("updateStop failed for {} ({}): {}", symbol, broker, e.getMessage());
        } finally {
            lock.unlockWrite(stamp);
        }
    }

    /**
     * Persist the partial-exit bitmask so a restart doesn't re-fire already-executed scale-outs.
     */
    public void updatePartialExits(String symbol, String broker, int partialExitsExecuted) {
        String sql = """
            UPDATE trades SET partial_exits_executed = ?
            WHERE id = (SELECT id FROM trades WHERE symbol = ? AND broker = ? AND status = 'OPEN'
                        ORDER BY entry_time DESC LIMIT 1)
            """;
        long stamp = lock.writeLock();
        try (var stmt = connection.prepareStatement(sql)) {
            stmt.setInt(1, partialExitsExecuted);
            stmt.setString(2, symbol);
            stmt.setString(3, broker);
            stmt.executeUpdate();
        } catch (SQLException e) {
            logger.error("updatePartialExits failed for {} ({}): {}", symbol, broker, e.getMessage());
        } finally {
            lock.unlockWrite(stamp);
        }
    }

    /**
     * Return all OPEN trades for a broker so the in-memory portfolio can be restored on startup.
     * Deduplicated: if the same symbol has multiple OPEN records (shouldn't happen after fixes),
     * only the most recent entry is returned.
     */
    public java.util.List<OpenTradeRecord> getOpenTradeRecords(String broker) {
        // Use the EARLIEST OPEN record's entry_price/stop_loss/take_profit per symbol so that
        // restart recovery reconstructs the real fill price (AVG produces a fictional price
        // when multiple OPEN rows exist for the same symbol, placing stops at wrong levels).
        String sql = """
            SELECT t.symbol,
                   agg.total_qty,
                   t.entry_price                    AS avg_entry,
                   t.stop_loss                      AS min_sl,
                   t.take_profit                    AS max_tp,
                   t.entry_time                     AS last_entry,
                   COALESCE(t.partial_exits_executed, 0) AS partial_exits
            FROM trades t
            JOIN (
                SELECT symbol,
                       MIN(entry_time) AS first_entry,
                       SUM(quantity)   AS total_qty
                FROM trades
                WHERE broker = ? AND status = 'OPEN'
                GROUP BY symbol
            ) agg ON t.symbol = agg.symbol AND t.entry_time = agg.first_entry
            WHERE t.broker = ? AND t.status = 'OPEN'
            """;
        var result = new java.util.ArrayList<OpenTradeRecord>();
        long stamp = lock.readLock();
        try (var stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, broker);
            stmt.setString(2, broker);
            try (var rs = stmt.executeQuery()) {
                while (rs.next()) {
                    var entryStr = rs.getString("last_entry");
                    java.time.Instant entryTime = entryStr != null
                        ? java.time.Instant.parse(entryStr.replace(" ", "T") + (entryStr.contains("Z") ? "" : "Z"))
                        : java.time.Instant.now();
                    result.add(new OpenTradeRecord(
                        rs.getString("symbol"),
                        rs.getDouble("avg_entry"),
                        rs.getDouble("total_qty"),
                        rs.getDouble("min_sl"),
                        rs.getDouble("max_tp"),
                        entryTime,
                        rs.getInt("partial_exits")
                    ));
                }
            }
        } catch (SQLException e) {
            logger.error("getOpenTradeRecords failed for broker {}: {}", broker, e.getMessage());
        } finally {
            lock.unlockRead(stamp);
        }
        return result;
    }

    /**
     * Returns the N most recent CLOSED trades (ordered by exit_time DESC).
     * Used by the bot behavior win-rate calculation — avoids including OPEN trades
     * that swamp the top-10 list and make win-rate appear 0%.
     */
    public java.util.List<java.util.Map<String, Object>> getRecentClosedTrades(int limit) {
        String sql = "SELECT symbol, strategy, profile, broker, entry_time, entry_price, quantity, " +
                     "exit_time, exit_price, pnl, stop_loss, take_profit, status " +
                     "FROM trades WHERE status = 'CLOSED' ORDER BY exit_time DESC LIMIT ?";
        var trades = new java.util.ArrayList<java.util.Map<String, Object>>();
        long stamp = lock.readLock();
        try (var stmt = connection.prepareStatement(sql)) {
            stmt.setInt(1, limit);
            try (var rs = stmt.executeQuery()) {
                while (rs.next()) {
                    var trade = new java.util.HashMap<String, Object>();
                    trade.put("symbol",     rs.getString("symbol"));
                    trade.put("strategy",   rs.getString("strategy"));
                    trade.put("broker",     rs.getString("broker"));
                    trade.put("entryPrice", rs.getDouble("entry_price"));
                    trade.put("exitPrice",  rs.getDouble("exit_price"));
                    trade.put("status",     rs.getString("status"));
                    double pnlVal = rs.getDouble("pnl");
                    if (!rs.wasNull()) trade.put("pnl", pnlVal);
                    trades.add(trade);
                }
            }
        } catch (SQLException e) {
            logger.error("getRecentClosedTrades failed: {}", e.getMessage());
        } finally {
            lock.unlockRead(stamp);
        }
        return trades;
    }

    /**
     * Close a trade with write lock.
     *
     * Closes ALL OPEN rows for (symbol, broker) in one call, not just the most
     * recent. Multi-entry positions accumulate multiple OPEN rows; a single
     * full-exit sell must record P&L for each lot, otherwise the orphan-cleanup
     * sweep marks the un-closed lots as CANCELLED with $0 P&L — hiding realized
     * results from reporting.
     *
     * Per-lot P&L is recomputed from each row's own entry_price and quantity
     * against the single exitPrice; the sum equals the aggregate P&L the caller
     * would have computed from the averaged position. The {@code pnl} parameter
     * is therefore redundant but kept for signature stability.
     */
    public void closeTrade(String symbol, Instant exitTime, double exitPrice, double pnl, String broker) {
        record Lot(int id, double entryPrice, double quantity) {}

        String selectSql = "SELECT id, entry_price, quantity FROM trades " +
                           "WHERE symbol = ? AND broker = ? AND status = 'OPEN'";
        String updateSql = "UPDATE trades SET exit_time = ?, exit_price = ?, pnl = ?, status = 'CLOSED' WHERE id = ?";

        long stamp = lock.writeLock();
        try {
            var lots = new java.util.ArrayList<Lot>();
            try (var sel = connection.prepareStatement(selectSql)) {
                sel.setString(1, symbol);
                sel.setString(2, broker);
                try (var rs = sel.executeQuery()) {
                    while (rs.next()) {
                        lots.add(new Lot(rs.getInt("id"), rs.getDouble("entry_price"), rs.getDouble("quantity")));
                    }
                }
            }

            if (lots.isEmpty()) {
                logger.warn("No open trade found to close for symbol: {}", symbol);
                return;
            }

            double totalPnl = 0.0;
            try (var upd = connection.prepareStatement(updateSql)) {
                for (Lot lot : lots) {
                    double lotPnl = (exitPrice - lot.entryPrice()) * lot.quantity();
                    totalPnl += lotPnl;
                    upd.setString(1, exitTime.toString());
                    upd.setDouble(2, exitPrice);
                    upd.setDouble(3, lotPnl);
                    upd.setInt(4, lot.id());
                    upd.addBatch();
                }
                upd.executeBatch();
            }

            logger.atInfo()
                .addKeyValue("symbol", symbol)
                .addKeyValue("exitPrice", exitPrice)
                .addKeyValue("pnl", totalPnl)
                .addKeyValue("lots", lots.size())
                .log("Trade closed");
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
    public double getTodayPnL() {
        String sql = "SELECT COALESCE(SUM(pnl), 0) as total FROM trades " +
                     "WHERE status = 'CLOSED' AND DATE(exit_time) = DATE('now')";
        long stamp = lock.tryOptimisticRead();
        double result = queryDouble(sql, "total");
        if (!lock.validate(stamp)) {
            stamp = lock.readLock();
            try { result = queryDouble(sql, "total"); } finally { lock.unlockRead(stamp); }
        }
        return result;
    }

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
     * Regime-conditioned symbol statistics — narrows the win-rate to the specific
     * market environment (WEAK_BEAR, STRONG_BULL, etc.) so adaptive gates can
     * compare apples-to-apples instead of averaging across all regimes.
     * Returns null if fewer than {@code minSamples} trades exist for this symbol+regime pair.
     */
    public SymbolStatistics getSymbolStatistics(String symbol, String regime) {
        return getSymbolStatistics(symbol, regime, 0);
    }

    public SymbolStatistics getSymbolStatistics(String symbol, String regime, int minSamples) {
        String totalSql = "SELECT COUNT(*) as count FROM trades WHERE symbol=? AND regime=? AND status='CLOSED'";
        String winSql   = "SELECT COUNT(*) as count FROM trades WHERE symbol=? AND regime=? AND status='CLOSED' AND pnl>0";
        String avgWinSql  = "SELECT AVG(pnl) FROM trades WHERE symbol=? AND regime=? AND status='CLOSED' AND pnl>0";
        String avgLossSql = "SELECT AVG(ABS(pnl)) FROM trades WHERE symbol=? AND regime=? AND status='CLOSED' AND pnl<0";

        long stamp = lock.readLock();
        try {
            int total = 0;
            try (var stmt = connection.prepareStatement(totalSql)) {
                stmt.setString(1, symbol); stmt.setString(2, regime);
                var rs = stmt.executeQuery();
                if (rs.next()) total = rs.getInt("count");
            }
            if (total < minSamples || total == 0) return null;

            int wins = 0;
            try (var stmt = connection.prepareStatement(winSql)) {
                stmt.setString(1, symbol); stmt.setString(2, regime);
                var rs = stmt.executeQuery();
                if (rs.next()) wins = rs.getInt("count");
            }
            double avgWin = 0.0;
            try (var stmt = connection.prepareStatement(avgWinSql)) {
                stmt.setString(1, symbol); stmt.setString(2, regime);
                var rs = stmt.executeQuery();
                if (rs.next()) avgWin = rs.getDouble(1);
            }
            double avgLoss = 0.0;
            try (var stmt = connection.prepareStatement(avgLossSql)) {
                stmt.setString(1, symbol); stmt.setString(2, regime);
                var rs = stmt.executeQuery();
                if (rs.next()) avgLoss = rs.getDouble(1);
            }
            return new SymbolStatistics(total, (double) wins / total, avgWin, avgLoss);
        } catch (SQLException e) {
            logger.error("Failed to get regime-conditioned statistics for {} in {}", symbol, regime, e);
            return null;
        } finally {
            lock.unlockRead(stamp);
        }
    }

    /**
     * Rolling regime-conditioned statistics over the last {@code maxTrades} closed trades.
     * Used for Kelly fraction sizing: recent sample is more predictive than lifetime average.
     */
    public SymbolStatistics getRollingSymbolStatistics(String symbol, String regime, int maxTrades) {
        String sql = """
            SELECT pnl FROM trades
            WHERE symbol = ? AND regime = ? AND status = 'CLOSED'
            ORDER BY exit_time DESC
            LIMIT ?
            """;
        long stamp = lock.readLock();
        try (var stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, symbol);
            stmt.setString(2, regime);
            stmt.setInt(3, maxTrades);
            var rs = stmt.executeQuery();
            int total = 0, wins = 0;
            double sumWin = 0.0, sumLoss = 0.0;
            int winCount = 0, lossCount = 0;
            while (rs.next()) {
                double pnl = rs.getDouble(1);
                total++;
                if (pnl > 0) { wins++; sumWin += pnl; winCount++; }
                else if (pnl < 0) { sumLoss += Math.abs(pnl); lossCount++; }
            }
            if (total == 0) return null;
            double avgWin  = winCount  > 0 ? sumWin  / winCount  : 0.0;
            double avgLoss = lossCount > 0 ? sumLoss / lossCount : 0.0;
            return new SymbolStatistics(total, (double) wins / total, avgWin, avgLoss);
        } catch (SQLException e) {
            logger.error("Failed to get rolling statistics for {} in {}", symbol, regime, e);
            return null;
        } finally {
            lock.unlockRead(stamp);
        }
    }

    /**
     * Persist the current regime and increment its consecutive-day counter.
     * Idempotent within a calendar day — multiple calls on the same ET date only
     * count as one day, so the counter tracks actual trading days not cycle counts.
     * Key layout in bot_state:
     *   "regime_persist:current"  → e.g. "WEAK_BEAR"
     *   "regime_persist:days"     → e.g. "3"
     *   "regime_persist:date"     → e.g. "2026-07-27"  (ET date of last increment)
     */
    public void updateRegimePersistence(String newRegime) {
        String today = java.time.LocalDate.now(java.time.ZoneId.of("America/New_York")).toString();
        String lastDate = loadBotState("regime_persist:date");
        if (today.equals(lastDate)) {
            // Already updated today — nothing to do
            return;
        }

        String currentRegime = loadBotState("regime_persist:current");
        int days = 1;
        if (newRegime.equals(currentRegime)) {
            String daysStr = loadBotState("regime_persist:days");
            if (daysStr != null) {
                try { days = Integer.parseInt(daysStr) + 1; } catch (NumberFormatException ignored) {}
            }
        }
        saveBotState("regime_persist:current", newRegime);
        saveBotState("regime_persist:days", String.valueOf(days));
        saveBotState("regime_persist:date", today);
        logger.info("Regime persistence: {} for {} consecutive day(s)", newRegime, days);
    }

    /**
     * Returns how many consecutive trading days the given regime has been active.
     * Returns 0 if the current persisted regime does not match, or no data exists.
     */
    public int getRegimePersistenceDays(String regime) {
        String current = loadBotState("regime_persist:current");
        if (!regime.equals(current)) return 0;
        String daysStr = loadBotState("regime_persist:days");
        if (daysStr == null) return 0;
        try { return Integer.parseInt(daysStr); } catch (NumberFormatException e) { return 0; }
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

    // ── bot_state persistence ────────────────────────────────────────────────

    /** Upsert a single key into bot_state. Value is JSON or a plain number string. */
    public void saveBotState(String key, String value) {
        String sql = """
            INSERT INTO bot_state (key, value, updated_at)
            VALUES (?, ?, datetime('now'))
            ON CONFLICT(key) DO UPDATE SET value = excluded.value, updated_at = excluded.updated_at
            """;
        long stamp = lock.writeLock();
        try (var ps = connection.prepareStatement(sql)) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.warn("saveBotState failed for key '{}': {}", key, e.getMessage());
        } finally {
            lock.unlockWrite(stamp);
        }
    }

    /** Load a single key. Returns null if missing. */
    public String loadBotState(String key) {
        String sql = "SELECT value FROM bot_state WHERE key = ?";
        long stamp = lock.tryOptimisticRead();
        try (var ps = connection.prepareStatement(sql)) {
            ps.setString(1, key);
            try (var rs = ps.executeQuery()) {
                String result = rs.next() ? rs.getString("value") : null;
                if (lock.validate(stamp)) return result;
            }
        } catch (SQLException e) {
            logger.warn("loadBotState failed for key '{}': {}", key, e.getMessage());
        }
        // Fallback to read lock
        stamp = lock.readLock();
        try (var ps = connection.prepareStatement(sql)) {
            ps.setString(1, key);
            try (var rs = ps.executeQuery()) {
                return rs.next() ? rs.getString("value") : null;
            }
        } catch (SQLException e) {
            logger.warn("loadBotState (read-lock) failed for key '{}': {}", key, e.getMessage());
            return null;
        } finally {
            lock.unlockRead(stamp);
        }
    }

    /** Load all keys matching a prefix. Returns a map of key → value. */
    public java.util.Map<String, String> loadBotStateWithPrefix(String prefix) {
        String sql = "SELECT key, value FROM bot_state WHERE key LIKE ?";
        var result = new java.util.HashMap<String, String>();
        long stamp = lock.readLock();
        try (var ps = connection.prepareStatement(sql)) {
            ps.setString(1, prefix + "%");
            try (var rs = ps.executeQuery()) {
                while (rs.next()) result.put(rs.getString("key"), rs.getString("value"));
            }
        } catch (SQLException e) {
            logger.warn("loadBotStateWithPrefix failed for prefix '{}': {}", prefix, e.getMessage());
        } finally {
            lock.unlockRead(stamp);
        }
        return result;
    }

    /** Delete a single key (e.g., when cooldown expires naturally). */
    public void deleteBotState(String key) {
        String sql = "DELETE FROM bot_state WHERE key = ?";
        long stamp = lock.writeLock();
        try (var ps = connection.prepareStatement(sql)) {
            ps.setString(1, key);
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.warn("deleteBotState failed for key '{}': {}", key, e.getMessage());
        } finally {
            lock.unlockWrite(stamp);
        }
    }

    // ── exit_reason / entry_reason ───────────────────────────────────────────

    /**
     * Overload of closeTrade that also stamps the exit_reason on every closed lot.
     * Call this instead of the 5-arg version whenever the reason is known at the call site.
     */
    public void closeTrade(String symbol, Instant exitTime, double exitPrice, double pnl,
                           String broker, String exitReason) {
        record Lot(int id, double entryPrice, double quantity) {}
        String selectSql = "SELECT id, entry_price, quantity FROM trades " +
                           "WHERE symbol = ? AND broker = ? AND status = 'OPEN'";
        String updateSql = "UPDATE trades SET exit_time = ?, exit_price = ?, pnl = ?, " +
                           "status = 'CLOSED', exit_reason = ? WHERE id = ?";
        long stamp = lock.writeLock();
        try {
            var lots = new java.util.ArrayList<Lot>();
            try (var sel = connection.prepareStatement(selectSql)) {
                sel.setString(1, symbol);
                sel.setString(2, broker);
                try (var rs = sel.executeQuery()) {
                    while (rs.next()) {
                        lots.add(new Lot(rs.getInt("id"), rs.getDouble("entry_price"), rs.getDouble("quantity")));
                    }
                }
            }
            if (lots.isEmpty()) {
                logger.warn("closeTrade(reason): no open trade found for {}", symbol);
                return;
            }
            try (var upd = connection.prepareStatement(updateSql)) {
                for (Lot lot : lots) {
                    double lotPnl = (exitPrice - lot.entryPrice()) * lot.quantity();
                    upd.setString(1, exitTime.toString());
                    upd.setDouble(2, exitPrice);
                    upd.setDouble(3, lotPnl);
                    upd.setString(4, exitReason);
                    upd.setInt(5, lot.id());
                    upd.addBatch();
                }
                upd.executeBatch();
            }
            logger.atInfo()
                .addKeyValue("symbol", symbol)
                .addKeyValue("exitPrice", exitPrice)
                .addKeyValue("exitReason", exitReason)
                .log("[TRADE_CLOSE] Trade closed");
        } catch (SQLException e) {
            logger.error("closeTrade(reason) failed for {}", symbol, e);
            closeTrade(symbol, exitTime, exitPrice, pnl, broker); // fallback
        } finally {
            lock.unlockWrite(stamp);
        }
    }

    /** Update entry_reason on the most recent OPEN trade (called immediately after recordTrade). */
    public void setEntryReason(String symbol, String broker, String reason) {
        String sql = "UPDATE trades SET entry_reason = ? " +
                     "WHERE id = (SELECT id FROM trades WHERE symbol = ? AND broker = ? " +
                     "            AND status = 'OPEN' ORDER BY entry_time DESC LIMIT 1)";
        long stamp = lock.writeLock();
        try (var ps = connection.prepareStatement(sql)) {
            ps.setString(1, reason);
            ps.setString(2, symbol);
            ps.setString(3, broker);
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.warn("setEntryReason failed for {}: {}", symbol, e.getMessage());
        } finally {
            lock.unlockWrite(stamp);
        }
    }

    // ── blocked_entries ──────────────────────────────────────────────────────

    /** Persist a blocked entry decision for post-session analysis. Non-fatal — never throws. */
    public void saveBlockedEntry(String symbol, String profile, String reason,
                                 double price, String regime, double vix) {
        String sql = "INSERT INTO blocked_entries (symbol, profile, reason, price, regime, vix) " +
                     "VALUES (?, ?, ?, ?, ?, ?)";
        long stamp = lock.writeLock();
        try (var ps = connection.prepareStatement(sql)) {
            ps.setString(1, symbol);
            ps.setString(2, profile);
            ps.setString(3, reason);
            ps.setDouble(4, price);
            ps.setString(5, regime);
            ps.setDouble(6, vix);
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.warn("saveBlockedEntry failed for {}: {}", symbol, e.getMessage());
        } finally {
            lock.unlockWrite(stamp);
        }
    }

    // ── daily_summary ────────────────────────────────────────────────────────

    /** Upsert today's trading summary. Called once at EOD cleanup completion. */
    public void saveDailySummary(String date, int trades, int wins, int losses,
                                 double netPnl, String regime, double vixOpen, int blockedCount) {
        String sql = """
            INSERT INTO daily_summary (date, trades, wins, losses, net_pnl, regime, vix_open, blocked_count)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(date) DO UPDATE SET
                trades = excluded.trades, wins = excluded.wins, losses = excluded.losses,
                net_pnl = excluded.net_pnl, regime = excluded.regime,
                vix_open = excluded.vix_open, blocked_count = excluded.blocked_count
            """;
        long stamp = lock.writeLock();
        try (var ps = connection.prepareStatement(sql)) {
            ps.setString(1, date);
            ps.setInt(2, trades);
            ps.setInt(3, wins);
            ps.setInt(4, losses);
            ps.setDouble(5, netPnl);
            ps.setString(6, regime);
            ps.setDouble(7, vixOpen);
            ps.setInt(8, blockedCount);
            ps.executeUpdate();
            logger.info("[DAILY_SUMMARY] {} trades={} wins={} losses={} pnl=${} regime={} blocked={}",
                date, trades, wins, losses, String.format("%.2f", netPnl), regime, blockedCount);
        } catch (SQLException e) {
            logger.warn("saveDailySummary failed: {}", e.getMessage());
        } finally {
            lock.unlockWrite(stamp);
        }
    }

    /** Count today's closed trades (wins and losses separately). Returns int[]{total, wins, losses}. */
    public int[] getTodayTradeCounts() {
        String sql = "SELECT COUNT(*) as total, " +
                     "SUM(CASE WHEN pnl > 0 THEN 1 ELSE 0 END) as wins, " +
                     "SUM(CASE WHEN pnl <= 0 THEN 1 ELSE 0 END) as losses " +
                     "FROM trades WHERE status = 'CLOSED' AND DATE(exit_time) = DATE('now')";
        long stamp = lock.readLock();
        try (var ps = connection.prepareStatement(sql);
             var rs = ps.executeQuery()) {
            if (rs.next()) {
                return new int[]{rs.getInt("total"), rs.getInt("wins"), rs.getInt("losses")};
            }
        } catch (SQLException e) {
            logger.warn("getTodayTradeCounts failed: {}", e.getMessage());
        } finally {
            lock.unlockRead(stamp);
        }
        return new int[]{0, 0, 0};
    }

    /** Count today's blocked entries. */
    public int getTodayBlockedCount() {
        String sql = "SELECT COUNT(*) as cnt FROM blocked_entries WHERE DATE(ts) = DATE('now')";
        long stamp = lock.readLock();
        try (var ps = connection.prepareStatement(sql);
             var rs = ps.executeQuery()) {
            if (rs.next()) return rs.getInt("cnt");
        } catch (SQLException e) {
            logger.warn("getTodayBlockedCount failed: {}", e.getMessage());
        } finally {
            lock.unlockRead(stamp);
        }
        return 0;
    }

    // ── regime_log ───────────────────────────────────────────────────────────

    /** Record a regime change. Non-fatal — never throws. */
    public void saveRegimeLog(String regime, double confidence, double breadth,
                              String trend, String volumeProfile) {
        String sql = "INSERT INTO regime_log (regime, confidence, breadth, trend, volume_profile) " +
                     "VALUES (?, ?, ?, ?, ?)";
        long stamp = lock.writeLock();
        try (var ps = connection.prepareStatement(sql)) {
            ps.setString(1, regime);
            ps.setDouble(2, confidence);
            ps.setDouble(3, breadth);
            ps.setString(4, trend);
            ps.setString(5, volumeProfile);
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.warn("saveRegimeLog failed: {}", e.getMessage());
        } finally {
            lock.unlockWrite(stamp);
        }
    }

    // ── TTL pruning ──────────────────────────────────────────────────────────

    /** Remove stale observability data on startup. Called once from createTables(). */
    private void pruneOldObservabilityData() {
        long stamp = lock.writeLock();
        try (var stmt = connection.createStatement()) {
            int blockedDeleted = stmt.executeUpdate(
                "DELETE FROM blocked_entries WHERE ts < datetime('now', '-30 days')");
            int regimeDeleted = stmt.executeUpdate(
                "DELETE FROM regime_log WHERE ts < datetime('now', '-60 days')");
            if (blockedDeleted > 0 || regimeDeleted > 0) {
                logger.info("Pruned {} blocked_entries and {} regime_log rows older than TTL",
                    blockedDeleted, regimeDeleted);
            }
        } catch (SQLException e) {
            logger.warn("pruneOldObservabilityData failed (non-fatal): {}", e.getMessage());
        } finally {
            lock.unlockWrite(stamp);
        }
    }
}
