package com.stellar.platform.storage.sqlite;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;

/**
 * Schema v8 — persist the last meaningful workspace (SOURCES / DECODE / SIGNALS / GRAPHS).
 * Transient processing routes are never stored here.
 */
public final class MigrationV8LastWorkspace implements SchemaMigration {
    @Override
    public int targetVersion() {
        return 8;
    }

    @Override
    public void apply(Connection connection) throws SQLException {
        try (Statement s = connection.createStatement()) {
            try {
                s.execute("ALTER TABLE projects ADD COLUMN last_workspace TEXT");
            } catch (SQLException ex) {
                String message = ex.getMessage() == null ? "" : ex.getMessage().toLowerCase(Locale.ROOT);
                if (message.contains("duplicate column") || message.contains("already exists")) {
                    return;
                }
                throw ex;
            }
        }
    }
}
