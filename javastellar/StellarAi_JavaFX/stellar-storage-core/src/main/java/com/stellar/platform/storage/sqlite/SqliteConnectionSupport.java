package com.stellar.platform.storage.sqlite;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * SQLite-only connection setup. Keep JDBC URLs, PRAGMAs and WAL here —
 * not in JavaFX, domain, Graph or GPS code.
 */
public final class SqliteConnectionSupport {

    private SqliteConnectionSupport() {
    }

    public static void configure(Connection connection) throws SQLException {
        try (Statement s = connection.createStatement()) {
            s.execute("PRAGMA foreign_keys=ON");
            s.execute("PRAGMA journal_mode=WAL");
            s.execute("PRAGMA synchronous=NORMAL");
            s.execute("PRAGMA busy_timeout=5000");
            s.execute("PRAGMA cache_size=-8192");
        }
        verifyForeignKeysEnabled(connection);
    }

    public static void verifyForeignKeysEnabled(Connection connection) throws SQLException {
        try (Statement s = connection.createStatement();
             ResultSet rs = s.executeQuery("PRAGMA foreign_keys")) {
            if (!rs.next() || rs.getInt(1) != 1) {
                throw new SQLException("PRAGMA foreign_keys is not ON");
            }
        }
    }

    public static void walCheckpointPassive(Connection connection) throws SQLException {
        try (Statement s = connection.createStatement()) {
            s.execute("PRAGMA wal_checkpoint(PASSIVE)");
        }
    }
}
