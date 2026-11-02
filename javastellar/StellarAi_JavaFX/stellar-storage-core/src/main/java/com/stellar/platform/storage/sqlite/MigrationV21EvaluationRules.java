package com.stellar.platform.storage.sqlite;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Schema v21 — immutable evaluation-rule versions and frozen evaluation results.
 * No samples are stored.
 */
public final class MigrationV21EvaluationRules implements SchemaMigration {

    @Override
    public int targetVersion() {
        return 21;
    }

    @Override
    public void apply(Connection connection) throws SQLException {
        try (Statement s = connection.createStatement()) {
            s.execute("""
                    CREATE TABLE IF NOT EXISTS evaluation_rules(
                      project_id TEXT NOT NULL,
                      rule_id TEXT NOT NULL,
                      version INTEGER NOT NULL,
                      document TEXT NOT NULL,
                      created_at TEXT NOT NULL,
                      PRIMARY KEY(project_id, rule_id, version),
                      FOREIGN KEY(project_id) REFERENCES projects(project_id) ON DELETE CASCADE)
                    """);
            s.execute("""
                    CREATE TABLE IF NOT EXISTS evaluation_results(
                      project_id TEXT NOT NULL,
                      result_id TEXT NOT NULL,
                      event_id TEXT NOT NULL,
                      rule_id TEXT NOT NULL,
                      rule_version INTEGER NOT NULL,
                      included INTEGER NOT NULL DEFAULT 0,
                      document TEXT NOT NULL,
                      created_at TEXT NOT NULL,
                      PRIMARY KEY(project_id, result_id),
                      FOREIGN KEY(project_id) REFERENCES projects(project_id) ON DELETE CASCADE)
                    """);
            s.execute("CREATE INDEX IF NOT EXISTS idx_evaluation_results_event "
                    + "ON evaluation_results(project_id, event_id, rule_version)");
        }
    }
}
