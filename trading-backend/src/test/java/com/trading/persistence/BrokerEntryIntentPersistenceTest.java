package com.trading.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class BrokerEntryIntentPersistenceTest {
    @Test
    void persistsIntentAcrossDatabaseReopen(@TempDir Path dir) {
        String path = dir.resolve("order-intents.db").toString();
        try (var db = new TestDb(path)) {
            assertTrue(db.database.claimBrokerEntryIntent("alpaca", "SPY", "atp_unique1", 4.0, 485.25, 522.75));
            assertFalse(db.database.claimBrokerEntryIntent("alpaca", "SPY", "atp_unique2"));
            assertEquals("atp_unique1",
                db.database.getBrokerEntryIntent("alpaca", "SPY").clientOrderId());
            db.database.updateBrokerEntryIntent("alpaca", "SPY", "atp_unique1", "ACKNOWLEDGED");
        }
        try (var db = new TestDb(path)) {
            assertEquals("ACKNOWLEDGED",
                db.database.getBrokerEntryIntent("alpaca", "SPY").state());
            assertEquals(4.0, db.database.getBrokerEntryIntent("alpaca", "SPY").intendedQty());
            assertEquals(485.25, db.database.getBrokerEntryIntent("alpaca", "SPY").stopLoss());
            assertEquals(522.75, db.database.getBrokerEntryIntent("alpaca", "SPY").takeProfit());
            assertFalse(db.database.claimBrokerEntryIntent("alpaca", "SPY", "atp_unique3"));
            db.database.releaseBrokerEntryIntent("alpaca", "SPY", "atp_unique1");
            assertTrue(db.database.claimBrokerEntryIntent("alpaca", "SPY", "atp_unique4"));
        }
    }

    @Test
    void separateSymbolsHaveIndependentClaims(@TempDir Path dir) {
        try (var db = new TestDb(dir.resolve("separate.db").toString())) {
            assertTrue(db.database.claimBrokerEntryIntent("alpaca", "SPY", "atp_spy"));
            assertTrue(db.database.claimBrokerEntryIntent("alpaca", "QQQ", "atp_qqq"));
            assertThrows(IllegalStateException.class,
                () -> db.database.releaseBrokerEntryIntent("alpaca", "SPY", "wrong_order"));
        }
    }

    private static final class TestDb implements AutoCloseable {
        final TradeDatabase database;
        TestDb(String path) { database = new TradeDatabase(path); }
        @Override public void close() { database.close(); }
    }
}
