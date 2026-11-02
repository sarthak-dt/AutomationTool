package com.stellar.platform.storage.sqlite;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;

/**
 * Schema v10 — persist per-artifact processing-plan (job intent) fingerprint so reopen can
 * reject artifacts when mapping/definition intent no longer matches.
 */
public final class MigrationV10PlanFingerprint implements SchemaMigration {
    @Override
    public int targetVersion() {
        return 10;
    }

    @Override
    public void apply(Connection connection) throws SQLException {
        try (Statement s = connection.createStatement()) {
            addColumn(s, "ALTER TABLE processing_artifacts ADD COLUMN plan_fingerprint TEXT");
        }
    }

    private static void addColumn(Statement s, String sql) throws SQLException {
        try {
            s.execute(sql);
        } catch (SQLException ex) {
            String message = ex.getMessage() == null ? "" : ex.getMessage().toLowerCase(Locale.ROOT);
            if (message.contains("duplicate column") || message.contains("already exists")) {
                return;
            }
            throw ex;
        }
    }
}
