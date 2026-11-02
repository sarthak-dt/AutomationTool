package com.stellar.platform.storage.sqlite;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Schema v4 — enterprise knowledge layer foundations:
 * portable artifact refs, persistent signal catalog (metadata only),
 * engineering bookmarks, data-quality findings.
 * <p>
 * Does NOT add waveform/sample tables. Does NOT add baseline/verification tables
 * until those workflows have real UI write paths.
 */
public final class MigrationV4EnterpriseKnowledge implements SchemaMigration {
    @Override
    public int targetVersion() {
        return 4;
    }

    @Override
    public void apply(Connection connection) throws SQLException {
        try (Statement s = connection.createStatement()) {
            // Portable storage reference alongside legacy absolute artifact_path.
            try {
                s.execute("ALTER TABLE processing_artifacts ADD COLUMN artifact_storage_ref TEXT NOT NULL DEFAULT ''");
            } catch (SQLException ignored) {
                // Column already present on re-run / partial upgrade.
            }

            s.execute("""
                    CREATE TABLE IF NOT EXISTS signal_catalog(
                      signal_catalog_id TEXT PRIMARY KEY,
                      project_id TEXT NOT NULL,
                      source_id TEXT NOT NULL,
                      channel_id INTEGER NOT NULL,
                      definition_id TEXT NOT NULL,
                      artifact_id TEXT NOT NULL,
                      signal_key TEXT NOT NULL,
                      signal_name TEXT NOT NULL,
                      message_name TEXT NOT NULL,
                      can_id INTEGER NOT NULL,
                      signal_channel INTEGER NOT NULL,
                      source_type TEXT NOT NULL,
                      unit TEXT NOT NULL,
                      sample_count INTEGER NOT NULL,
                      start_timestamp_nanos INTEGER,
                      end_timestamp_nanos INTEGER,
                      min_value REAL,
                      max_value REAL,
                      created_at TEXT NOT NULL,
                      updated_at TEXT NOT NULL,
                      FOREIGN KEY(project_id) REFERENCES projects(project_id) ON DELETE CASCADE)
                    """);
            s.execute("CREATE INDEX IF NOT EXISTS idx_signal_catalog_project ON signal_catalog(project_id)");
            s.execute("CREATE INDEX IF NOT EXISTS idx_signal_catalog_name ON signal_catalog(project_id, signal_name)");
            s.execute("CREATE INDEX IF NOT EXISTS idx_signal_catalog_can ON signal_catalog(project_id, can_id)");
            s.execute("CREATE INDEX IF NOT EXISTS idx_signal_catalog_source ON signal_catalog(project_id, source_id, channel_id)");
            s.execute("CREATE INDEX IF NOT EXISTS idx_signal_catalog_artifact ON signal_catalog(artifact_id)");
            s.execute("""
                    CREATE UNIQUE INDEX IF NOT EXISTS idx_signal_catalog_identity
                    ON signal_catalog(project_id, artifact_id, signal_key)
                    """);

            s.execute("""
                    CREATE TABLE IF NOT EXISTS engineering_bookmarks(
                      bookmark_id TEXT PRIMARY KEY,
                      project_id TEXT NOT NULL,
                      source_id TEXT NOT NULL,
                      artifact_id TEXT NOT NULL,
                      signal_name TEXT NOT NULL,
                      signal_key TEXT NOT NULL,
                      timestamp_nanos INTEGER NOT NULL,
                      event_type TEXT NOT NULL,
                      observation TEXT NOT NULL,
                      created_at TEXT NOT NULL,
                      updated_at TEXT NOT NULL,
                      FOREIGN KEY(project_id) REFERENCES projects(project_id) ON DELETE CASCADE)
                    """);
            s.execute("CREATE INDEX IF NOT EXISTS idx_engineering_bookmarks_project ON engineering_bookmarks(project_id, timestamp_nanos)");

            s.execute("""
                    CREATE TABLE IF NOT EXISTS data_quality_findings(
                      finding_id TEXT PRIMARY KEY,
                      project_id TEXT NOT NULL,
                      source_id TEXT NOT NULL,
                      channel_id INTEGER,
                      finding_type TEXT NOT NULL,
                      severity TEXT NOT NULL,
                      start_timestamp_nanos INTEGER,
                      end_timestamp_nanos INTEGER,
                      summary TEXT NOT NULL,
                      technical_detail TEXT NOT NULL,
                      processing_run_id TEXT NOT NULL,
                      created_at TEXT NOT NULL,
                      FOREIGN KEY(project_id) REFERENCES projects(project_id) ON DELETE CASCADE)
                    """);
            s.execute("CREATE INDEX IF NOT EXISTS idx_dq_findings_project ON data_quality_findings(project_id, source_id)");
            s.execute("CREATE INDEX IF NOT EXISTS idx_dq_findings_type ON data_quality_findings(project_id, finding_type)");
        }
    }
}
