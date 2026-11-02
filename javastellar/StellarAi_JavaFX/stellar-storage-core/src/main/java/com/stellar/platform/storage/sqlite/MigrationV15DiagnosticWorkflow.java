package com.stellar.platform.storage.sqlite;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Schema v15 — diagnostic investigation workflow.
 * Review state and engineer notes are local investigation metadata, not engine evidence.
 * Waveform samples stay out of SQLite.
 */
public final class MigrationV15DiagnosticWorkflow implements SchemaMigration {

    @Override
    public int targetVersion() {
        return 15;
    }

    @Override
    public void apply(Connection connection) throws SQLException {
        try (Statement s = connection.createStatement()) {
            s.execute("""
                    CREATE TABLE IF NOT EXISTS diagnostic_finding_reviews(
                      finding_id TEXT PRIMARY KEY,
                      project_id TEXT NOT NULL,
                      review_state TEXT NOT NULL DEFAULT 'NEW',
                      engineer_note TEXT NOT NULL DEFAULT '',
                      updated_at TEXT NOT NULL,
                      FOREIGN KEY(project_id) REFERENCES projects(project_id) ON DELETE CASCADE)
                    """);
            s.execute("CREATE INDEX IF NOT EXISTS idx_diagnostic_reviews_project "
                    + "ON diagnostic_finding_reviews(project_id)");
            s.execute("""
                    CREATE TABLE IF NOT EXISTS diagnostic_rule_preferences(
                      project_id TEXT NOT NULL,
                      pack_id TEXT NOT NULL,
                      rule_id TEXT NOT NULL,
                      enabled INTEGER NOT NULL DEFAULT 1,
                      updated_at TEXT NOT NULL,
                      PRIMARY KEY(project_id, pack_id, rule_id),
                      FOREIGN KEY(project_id) REFERENCES projects(project_id) ON DELETE CASCADE)
                    """);
        }
    }
}
