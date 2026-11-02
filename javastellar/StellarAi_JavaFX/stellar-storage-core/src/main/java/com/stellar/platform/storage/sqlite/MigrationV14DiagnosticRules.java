package com.stellar.platform.storage.sqlite;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Schema v14 — diagnostic rule packs, runs, findings, bindings and workspace.
 * Evaluation streams are not stored. Waveform samples stay out of SQLite.
 */
public final class MigrationV14DiagnosticRules implements SchemaMigration {

    @Override
    public int targetVersion() {
        return 14;
    }

    @Override
    public void apply(Connection connection) throws SQLException {
        try (Statement s = connection.createStatement()) {
            s.execute("""
                    CREATE TABLE IF NOT EXISTS diagnostic_rule_packs(
                      pack_id TEXT NOT NULL,
                      pack_version TEXT NOT NULL,
                      project_id TEXT NOT NULL,
                      name TEXT NOT NULL,
                      description TEXT NOT NULL DEFAULT '',
                      origin TEXT NOT NULL DEFAULT 'PROJECT_CUSTOM',
                      engine_min_version TEXT NOT NULL DEFAULT '1',
                      json_blob TEXT NOT NULL,
                      created_at TEXT NOT NULL,
                      PRIMARY KEY(project_id, pack_id, pack_version),
                      FOREIGN KEY(project_id) REFERENCES projects(project_id) ON DELETE CASCADE)
                    """);
            s.execute("""
                    CREATE TABLE IF NOT EXISTS diagnostic_runs(
                      run_id TEXT PRIMARY KEY,
                      project_id TEXT NOT NULL,
                      pack_id TEXT NOT NULL,
                      pack_version TEXT NOT NULL,
                      status TEXT NOT NULL,
                      range_source TEXT NOT NULL DEFAULT 'FULL',
                      start_timestamp_nanos INTEGER,
                      end_timestamp_nanos INTEGER,
                      rules_requested INTEGER NOT NULL DEFAULT 0,
                      rules_executed INTEGER NOT NULL DEFAULT 0,
                      rules_skipped INTEGER NOT NULL DEFAULT 0,
                      invalidation_key TEXT NOT NULL DEFAULT '',
                      artifact_generation INTEGER NOT NULL DEFAULT 0,
                      engine_version TEXT NOT NULL DEFAULT '1',
                      message TEXT NOT NULL DEFAULT '',
                      created_at TEXT NOT NULL,
                      completed_at TEXT,
                      FOREIGN KEY(project_id) REFERENCES projects(project_id) ON DELETE CASCADE)
                    """);
            s.execute("CREATE INDEX IF NOT EXISTS idx_diagnostic_runs_project "
                    + "ON diagnostic_runs(project_id, created_at)");
            s.execute("""
                    CREATE TABLE IF NOT EXISTS diagnostic_findings(
                      finding_id TEXT PRIMARY KEY,
                      run_id TEXT NOT NULL,
                      project_id TEXT NOT NULL,
                      rule_id TEXT NOT NULL,
                      rule_version TEXT NOT NULL,
                      severity TEXT NOT NULL,
                      category TEXT NOT NULL,
                      start_timestamp_nanos INTEGER NOT NULL,
                      end_timestamp_nanos INTEGER,
                      summary TEXT NOT NULL DEFAULT '',
                      details TEXT NOT NULL DEFAULT '',
                      evidence_blob TEXT NOT NULL DEFAULT '',
                      created_at TEXT NOT NULL,
                      FOREIGN KEY(project_id) REFERENCES projects(project_id) ON DELETE CASCADE,
                      FOREIGN KEY(run_id) REFERENCES diagnostic_runs(run_id) ON DELETE CASCADE)
                    """);
            s.execute("CREATE INDEX IF NOT EXISTS idx_diagnostic_findings_run "
                    + "ON diagnostic_findings(run_id)");
            s.execute("""
                    CREATE TABLE IF NOT EXISTS diagnostic_bindings(
                      project_id TEXT NOT NULL,
                      pack_id TEXT NOT NULL,
                      role TEXT NOT NULL,
                      signal_key_blob TEXT NOT NULL,
                      updated_at TEXT NOT NULL,
                      PRIMARY KEY(project_id, pack_id, role),
                      FOREIGN KEY(project_id) REFERENCES projects(project_id) ON DELETE CASCADE)
                    """);
            s.execute("""
                    CREATE TABLE IF NOT EXISTS diagnostic_workspace(
                      project_id TEXT PRIMARY KEY,
                      last_pack_id TEXT NOT NULL DEFAULT '',
                      last_run_id TEXT NOT NULL DEFAULT '',
                      enabled_rules_blob TEXT NOT NULL DEFAULT '',
                      findings_visible INTEGER NOT NULL DEFAULT 1,
                      selected_finding_id TEXT NOT NULL DEFAULT '',
                      updated_at TEXT NOT NULL,
                      FOREIGN KEY(project_id) REFERENCES projects(project_id) ON DELETE CASCADE)
                    """);
        }
    }
}
