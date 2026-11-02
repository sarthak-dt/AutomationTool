package com.stellar.platform.storage.sqlite;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Schema v16 — engineering investigations. Metadata and references only; no waveform blobs.
 */
public final class MigrationV16Investigations implements SchemaMigration {

    @Override
    public int targetVersion() {
        return 16;
    }

    @Override
    public void apply(Connection connection) throws SQLException {
        try (Statement s = connection.createStatement()) {
            s.execute("""
                    CREATE TABLE IF NOT EXISTS engineering_investigations(
                      investigation_id TEXT PRIMARY KEY,
                      project_id TEXT NOT NULL,
                      name TEXT NOT NULL,
                      description TEXT NOT NULL DEFAULT '',
                      status TEXT NOT NULL DEFAULT 'DRAFT',
                      range_start_nanos INTEGER,
                      range_end_nanos INTEGER,
                      graph_traces_blob TEXT NOT NULL DEFAULT '',
                      layout_mode TEXT NOT NULL DEFAULT 'STACKED',
                      viewport_start_nanos INTEGER,
                      viewport_end_nanos INTEGER,
                      cursor_a_nanos INTEGER,
                      cursor_b_nanos INTEGER,
                      focus_key_blob TEXT NOT NULL DEFAULT '',
                      conclusion TEXT NOT NULL DEFAULT '',
                      report_config TEXT NOT NULL DEFAULT '',
                      created_at TEXT NOT NULL,
                      updated_at TEXT NOT NULL,
                      FOREIGN KEY(project_id) REFERENCES projects(project_id) ON DELETE CASCADE)
                    """);
            s.execute("CREATE INDEX IF NOT EXISTS idx_investigations_project "
                    + "ON engineering_investigations(project_id, updated_at)");
            s.execute("""
                    CREATE TABLE IF NOT EXISTS investigation_signals(
                      investigation_id TEXT NOT NULL,
                      sort_order INTEGER NOT NULL,
                      signal_key_blob TEXT NOT NULL,
                      PRIMARY KEY(investigation_id, sort_order),
                      FOREIGN KEY(investigation_id) REFERENCES engineering_investigations(investigation_id)
                        ON DELETE CASCADE)
                    """);
            s.execute("""
                    CREATE TABLE IF NOT EXISTS investigation_evidence(
                      evidence_id TEXT PRIMARY KEY,
                      investigation_id TEXT NOT NULL,
                      kind TEXT NOT NULL,
                      reference_id TEXT NOT NULL DEFAULT '',
                      sort_order INTEGER NOT NULL,
                      stale INTEGER NOT NULL DEFAULT 0,
                      stale_reason TEXT NOT NULL DEFAULT '',
                      source_fingerprint TEXT NOT NULL DEFAULT '',
                      artifact_generation INTEGER NOT NULL DEFAULT 0,
                      caption TEXT NOT NULL DEFAULT '',
                      summary TEXT NOT NULL DEFAULT '',
                      created_at TEXT NOT NULL,
                      FOREIGN KEY(investigation_id) REFERENCES engineering_investigations(investigation_id)
                        ON DELETE CASCADE)
                    """);
            s.execute("CREATE INDEX IF NOT EXISTS idx_investigation_evidence_inv "
                    + "ON investigation_evidence(investigation_id, sort_order)");
            s.execute("""
                    CREATE TABLE IF NOT EXISTS investigation_observations(
                      observation_id TEXT PRIMARY KEY,
                      investigation_id TEXT NOT NULL,
                      start_nanos INTEGER,
                      end_nanos INTEGER,
                      title TEXT NOT NULL DEFAULT '',
                      note TEXT NOT NULL DEFAULT '',
                      created_at TEXT NOT NULL,
                      updated_at TEXT NOT NULL,
                      FOREIGN KEY(investigation_id) REFERENCES engineering_investigations(investigation_id)
                        ON DELETE CASCADE)
                    """);
            s.execute("""
                    CREATE TABLE IF NOT EXISTS investigation_reports(
                      report_id TEXT PRIMARY KEY,
                      investigation_id TEXT NOT NULL,
                      project_id TEXT NOT NULL,
                      name TEXT NOT NULL,
                      format TEXT NOT NULL DEFAULT 'HTML',
                      status TEXT NOT NULL,
                      destination TEXT NOT NULL DEFAULT '',
                      message TEXT NOT NULL DEFAULT '',
                      created_at TEXT NOT NULL,
                      finished_at TEXT,
                      FOREIGN KEY(investigation_id) REFERENCES engineering_investigations(investigation_id)
                        ON DELETE CASCADE,
                      FOREIGN KEY(project_id) REFERENCES projects(project_id) ON DELETE CASCADE)
                    """);
            s.execute("CREATE INDEX IF NOT EXISTS idx_investigation_reports_inv "
                    + "ON investigation_reports(investigation_id, created_at)");
            s.execute("""
                    CREATE TABLE IF NOT EXISTS investigation_workspace(
                      project_id TEXT PRIMARY KEY,
                      last_investigation_id TEXT NOT NULL DEFAULT '',
                      updated_at TEXT NOT NULL,
                      FOREIGN KEY(project_id) REFERENCES projects(project_id) ON DELETE CASCADE)
                    """);
        }
    }
}
