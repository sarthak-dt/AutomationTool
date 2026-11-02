package com.stellar.platform.storage.sqlite;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Baseline through schema v3 (projects … processing_artifacts).
 * Idempotent CREATE IF NOT EXISTS — upgrades empty or partial databases safely.
 */
public final class MigrationV3Baseline implements SchemaMigration {
    @Override
    public int targetVersion() {
        return 3;
    }

    @Override
    public void apply(Connection connection) throws SQLException {
        try (Statement s = connection.createStatement()) {
            s.execute("""
                    CREATE TABLE IF NOT EXISTS projects(
                      project_id TEXT PRIMARY KEY,
                      name TEXT NOT NULL,
                      created_at TEXT NOT NULL,
                      updated_at TEXT NOT NULL)
                    """);
            s.execute("""
                    CREATE TABLE IF NOT EXISTS project_files(
                      project_id TEXT NOT NULL,
                      file_id TEXT NOT NULL,
                      path TEXT NOT NULL,
                      kind TEXT NOT NULL,
                      size_bytes INTEGER NOT NULL,
                      validation_status TEXT NOT NULL,
                      validation_message TEXT NOT NULL,
                      associated_definition_id TEXT,
                      finalization_label TEXT NOT NULL,
                      recovery_candidate INTEGER NOT NULL,
                      selected_measurement INTEGER NOT NULL,
                      selected_definition INTEGER NOT NULL,
                      updated_at TEXT NOT NULL,
                      PRIMARY KEY(project_id, file_id),
                      FOREIGN KEY(project_id) REFERENCES projects(project_id) ON DELETE CASCADE)
                    """);
            s.execute("CREATE INDEX IF NOT EXISTS idx_project_files_path ON project_files(project_id, path)");
            s.execute("""
                    CREATE TABLE IF NOT EXISTS source_inventory(
                      project_id TEXT NOT NULL,
                      source_id TEXT NOT NULL,
                      source_path TEXT NOT NULL,
                      source_name TEXT NOT NULL,
                      source_format TEXT NOT NULL,
                      status TEXT NOT NULL,
                      total_can_channels INTEGER NOT NULL,
                      total_can_frames INTEGER NOT NULL,
                      total_unique_message_ids INTEGER NOT NULL,
                      channel_discovery_complete INTEGER NOT NULL,
                      diagnostic_count INTEGER NOT NULL,
                      updated_at TEXT NOT NULL,
                      PRIMARY KEY(project_id, source_id),
                      FOREIGN KEY(project_id) REFERENCES projects(project_id) ON DELETE CASCADE)
                    """);
            s.execute("""
                    CREATE TABLE IF NOT EXISTS channel_inventory(
                      project_id TEXT NOT NULL,
                      source_id TEXT NOT NULL,
                      channel_id INTEGER NOT NULL,
                      display_name TEXT NOT NULL,
                      frame_count INTEGER NOT NULL,
                      unique_message_count INTEGER NOT NULL,
                      standard_message_count INTEGER NOT NULL,
                      extended_message_count INTEGER NOT NULL,
                      can_fd_frame_count INTEGER NOT NULL,
                      PRIMARY KEY(project_id, source_id, channel_id),
                      FOREIGN KEY(project_id, source_id) REFERENCES source_inventory(project_id, source_id) ON DELETE CASCADE)
                    """);
            s.execute("""
                    CREATE TABLE IF NOT EXISTS channel_messages(
                      project_id TEXT NOT NULL,
                      source_id TEXT NOT NULL,
                      channel_id INTEGER NOT NULL,
                      can_id INTEGER NOT NULL,
                      extended INTEGER NOT NULL,
                      frame_count INTEGER NOT NULL,
                      min_observed_dlc INTEGER NOT NULL,
                      max_observed_dlc INTEGER NOT NULL,
                      can_fd_observed INTEGER NOT NULL,
                      PRIMARY KEY(project_id, source_id, channel_id, can_id, extended),
                      FOREIGN KEY(project_id, source_id, channel_id) REFERENCES channel_inventory(project_id, source_id, channel_id) ON DELETE CASCADE)
                    """);
            s.execute("CREATE INDEX IF NOT EXISTS idx_channel_messages_source ON channel_messages(project_id, source_id, channel_id)");
            s.execute("""
                    CREATE TABLE IF NOT EXISTS channel_mappings(
                      project_id TEXT NOT NULL,
                      source_id TEXT NOT NULL,
                      channel_id INTEGER NOT NULL,
                      status TEXT NOT NULL,
                      recommendation_kind TEXT NOT NULL,
                      recommended_definition_id TEXT,
                      mapped_definition_id TEXT,
                      observed_unique_message_count INTEGER NOT NULL,
                      matched_unique_message_count INTEGER NOT NULL,
                      coverage_ratio REAL NOT NULL,
                      updated_at TEXT NOT NULL,
                      PRIMARY KEY(project_id, source_id, channel_id),
                      FOREIGN KEY(project_id) REFERENCES projects(project_id) ON DELETE CASCADE)
                    """);
            s.execute("""
                    CREATE TABLE IF NOT EXISTS processing_runs(
                      run_id TEXT PRIMARY KEY,
                      project_id TEXT NOT NULL,
                      status TEXT NOT NULL,
                      force_reprocess INTEGER NOT NULL,
                      job_count INTEGER NOT NULL,
                      started_at TEXT NOT NULL,
                      finished_at TEXT,
                      message TEXT NOT NULL,
                      FOREIGN KEY(project_id) REFERENCES projects(project_id) ON DELETE CASCADE)
                    """);
            s.execute("CREATE INDEX IF NOT EXISTS idx_processing_runs_project ON processing_runs(project_id, started_at)");
            s.execute("""
                    CREATE TABLE IF NOT EXISTS processing_artifacts(
                      artifact_id TEXT PRIMARY KEY,
                      project_id TEXT NOT NULL,
                      source_id TEXT NOT NULL,
                      channel_id INTEGER NOT NULL,
                      definition_id TEXT NOT NULL,
                      processing_run_id TEXT NOT NULL,
                      artifact_type TEXT NOT NULL,
                      artifact_path TEXT NOT NULL,
                      artifact_version TEXT NOT NULL,
                      source_fingerprint TEXT NOT NULL,
                      dbc_fingerprint TEXT NOT NULL,
                      signal_count INTEGER NOT NULL,
                      sample_count INTEGER NOT NULL,
                      start_timestamp_nanos INTEGER,
                      end_timestamp_nanos INTEGER,
                      status TEXT NOT NULL,
                      created_at TEXT NOT NULL,
                      mapping_lifecycle TEXT NOT NULL,
                      FOREIGN KEY(project_id) REFERENCES projects(project_id) ON DELETE CASCADE)
                    """);
            s.execute("""
                    CREATE UNIQUE INDEX IF NOT EXISTS idx_processing_artifacts_job
                    ON processing_artifacts(project_id, source_id, channel_id, definition_id)
                    """);
            s.execute("CREATE INDEX IF NOT EXISTS idx_processing_artifacts_project ON processing_artifacts(project_id, status)");
        }
    }
}
