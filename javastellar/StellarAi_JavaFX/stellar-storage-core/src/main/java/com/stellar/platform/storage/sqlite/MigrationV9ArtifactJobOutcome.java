package com.stellar.platform.storage.sqlite;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;

/**
 * Schema v9 — persist per-artifact job execution status and diagnostic summary so reopen
 * preserves Completed-with-warnings (and reasons) instead of collapsing to SUCCESS.
 */
public final class MigrationV9ArtifactJobOutcome implements SchemaMigration {
    @Override
    public int targetVersion() {
        return 9;
    }

    @Override
    public void apply(Connection connection) throws SQLException {
        try (Statement s = connection.createStatement()) {
            addColumn(s, "ALTER TABLE processing_artifacts ADD COLUMN job_execution_status TEXT");
            addColumn(s, "ALTER TABLE processing_artifacts ADD COLUMN diagnostics_summary TEXT");
            addColumn(s, "ALTER TABLE processing_artifacts ADD COLUMN technical_detail TEXT");
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
