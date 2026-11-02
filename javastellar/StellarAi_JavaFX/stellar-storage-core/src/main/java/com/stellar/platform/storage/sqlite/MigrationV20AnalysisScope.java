package com.stellar.platform.storage.sqlite;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Schema v20 — lightweight persisted Graphs analysis scope (ALL vs SINGLE).
 * No samples. No artifact moves.
 */
public final class MigrationV20AnalysisScope implements SchemaMigration {

    @Override
    public int targetVersion() {
        return 20;
    }

    @Override
    public void apply(Connection connection) throws SQLException {
        try (Statement s = connection.createStatement()) {
            s.execute("""
                    CREATE TABLE IF NOT EXISTS analysis_scope(
                      project_id TEXT PRIMARY KEY,
                      scope_kind TEXT NOT NULL,
                      source_id TEXT NOT NULL DEFAULT '',
                      file_name TEXT NOT NULL DEFAULT '',
                      updated_at TEXT NOT NULL,
                      FOREIGN KEY(project_id) REFERENCES projects(project_id) ON DELETE CASCADE)
                    """);
        }
    }
}
