package com.stellar.platform.storage.sqlite;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Schema v13 — lightweight engineering analysis metadata.
 * Waveform samples stay out of SQLite.
 */
public final class MigrationV13AnalysisEngine implements SchemaMigration {

    @Override
    public int targetVersion() {
        return 13;
    }

    @Override
    public void apply(Connection connection) throws SQLException {
        try (Statement s = connection.createStatement()) {
            s.execute("""
                    CREATE TABLE IF NOT EXISTS analysis_runs(
                      analysis_id TEXT PRIMARY KEY,
                      project_id TEXT NOT NULL,
                      analysis_type TEXT NOT NULL,
                      status TEXT NOT NULL,
                      alignment_policy TEXT NOT NULL DEFAULT 'EXACT_TIMESTAMP',
                      analysis_version TEXT NOT NULL DEFAULT '1',
                      range_source TEXT NOT NULL DEFAULT 'FULL',
                      start_timestamp_nanos INTEGER,
                      end_timestamp_nanos INTEGER,
                      signal_keys_blob TEXT NOT NULL DEFAULT '',
                      parameters_blob TEXT NOT NULL DEFAULT '',
                      invalidation_key TEXT NOT NULL DEFAULT '',
                      artifact_ref TEXT NOT NULL DEFAULT '',
                      message TEXT NOT NULL DEFAULT '',
                      created_at TEXT NOT NULL,
                      completed_at TEXT,
                      FOREIGN KEY(project_id) REFERENCES projects(project_id) ON DELETE CASCADE)
                    """);
            s.execute("CREATE INDEX IF NOT EXISTS idx_analysis_runs_project "
                    + "ON analysis_runs(project_id, created_at)");
            s.execute("""
                    CREATE TABLE IF NOT EXISTS analysis_findings(
                      finding_id TEXT PRIMARY KEY,
                      analysis_id TEXT NOT NULL,
                      project_id TEXT NOT NULL,
                      finding_type TEXT NOT NULL,
                      severity TEXT NOT NULL,
                      start_timestamp_nanos INTEGER NOT NULL,
                      end_timestamp_nanos INTEGER,
                      summary TEXT NOT NULL DEFAULT '',
                      details TEXT NOT NULL DEFAULT '',
                      metrics_blob TEXT NOT NULL DEFAULT '',
                      created_at TEXT NOT NULL,
                      FOREIGN KEY(project_id) REFERENCES projects(project_id) ON DELETE CASCADE,
                      FOREIGN KEY(analysis_id) REFERENCES analysis_runs(analysis_id) ON DELETE CASCADE)
                    """);
            s.execute("CREATE INDEX IF NOT EXISTS idx_analysis_findings_run "
                    + "ON analysis_findings(analysis_id)");
            s.execute("""
                    CREATE TABLE IF NOT EXISTS derived_signal_definitions(
                      derived_id TEXT PRIMARY KEY,
                      project_id TEXT NOT NULL,
                      identity TEXT NOT NULL,
                      expression TEXT NOT NULL,
                      alignment_policy TEXT NOT NULL,
                      analysis_version TEXT NOT NULL DEFAULT '1',
                      created_at TEXT NOT NULL,
                      FOREIGN KEY(project_id) REFERENCES projects(project_id) ON DELETE CASCADE)
                    """);
            s.execute("""
                    CREATE UNIQUE INDEX IF NOT EXISTS idx_derived_signal_identity
                    ON derived_signal_definitions(project_id, identity)
                    """);
            s.execute("""
                    CREATE TABLE IF NOT EXISTS analysis_workspace(
                      project_id TEXT PRIMARY KEY,
                      last_analysis_type TEXT NOT NULL DEFAULT '',
                      last_analysis_id TEXT NOT NULL DEFAULT '',
                      result_visible INTEGER NOT NULL DEFAULT 1,
                      selected_finding_id TEXT NOT NULL DEFAULT '',
                      updated_at TEXT NOT NULL,
                      FOREIGN KEY(project_id) REFERENCES projects(project_id) ON DELETE CASCADE)
                    """);
        }
    }
}
