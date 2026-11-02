package com.stellar.platform.storage.sqlite;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Schema v12 — production local runtime foundations.
 *
 * <p>Adds the durable state that lets processing survive termination of the worker or the
 * application: a job ledger, committed batches, resume checkpoints and classified errors.
 * Adds the append-only audit log, graph annotations and export job history.</p>
 *
 * <p>Deliberately stores no samples, no LOD buffers and no rendered geometry. {@code processing_runs}
 * remains the run-level summary; {@code processing_jobs} is the durable unit-of-work lifecycle.</p>
 */
public final class MigrationV12ProductionRuntime implements SchemaMigration {

    @Override
    public int targetVersion() {
        return 12;
    }

    @Override
    public void apply(Connection connection) throws SQLException {
        try (Statement s = connection.createStatement()) {
            createProcessingJobs(s);
            createProcessingBatches(s);
            createProcessingCheckpoints(s);
            createProcessingErrors(s);
            createAuditLog(s);
            createGraphAnnotations(s);
            createExportJobs(s);
        }
    }

    /**
     * Durable unit-of-work lifecycle. One row per source/channel/definition job in a run, updated
     * in place across attempts so an interrupted job is still discoverable after a restart.
     */
    private static void createProcessingJobs(Statement s) throws SQLException {
        s.execute("""
                CREATE TABLE IF NOT EXISTS processing_jobs(
                  job_id TEXT PRIMARY KEY,
                  project_id TEXT NOT NULL,
                  run_id TEXT NOT NULL,
                  source_id TEXT NOT NULL,
                  channel_id INTEGER NOT NULL,
                  definition_id TEXT NOT NULL,
                  status TEXT NOT NULL,
                  attempt INTEGER NOT NULL DEFAULT 0,
                  max_attempts INTEGER NOT NULL DEFAULT 3,
                  progress REAL NOT NULL DEFAULT 0,
                  stage TEXT NOT NULL DEFAULT '',
                  worker_id TEXT NOT NULL DEFAULT '',
                  created_at TEXT NOT NULL,
                  started_at TEXT,
                  finished_at TEXT,
                  failure_class TEXT,
                  message TEXT NOT NULL DEFAULT '',
                  FOREIGN KEY(project_id) REFERENCES projects(project_id) ON DELETE CASCADE)
                """);
        s.execute("CREATE INDEX IF NOT EXISTS idx_processing_jobs_project_status "
                + "ON processing_jobs(project_id, status)");
        s.execute("CREATE INDEX IF NOT EXISTS idx_processing_jobs_run ON processing_jobs(run_id)");
        s.execute("""
                CREATE UNIQUE INDEX IF NOT EXISTS idx_processing_jobs_identity
                ON processing_jobs(project_id, run_id, source_id, channel_id, definition_id)
                """);
    }

    /**
     * Committed processing units. A batch row reaching COMMITTED means its artifact segment is
     * durable, which is what makes resume safe.
     */
    private static void createProcessingBatches(Statement s) throws SQLException {
        s.execute("""
                CREATE TABLE IF NOT EXISTS processing_batches(
                  batch_id TEXT PRIMARY KEY,
                  project_id TEXT NOT NULL,
                  job_id TEXT NOT NULL,
                  sequence_no INTEGER NOT NULL,
                  input_start INTEGER NOT NULL,
                  input_end INTEGER NOT NULL,
                  records_processed INTEGER NOT NULL DEFAULT 0,
                  samples_produced INTEGER NOT NULL DEFAULT 0,
                  status TEXT NOT NULL,
                  artifact_ref TEXT NOT NULL DEFAULT '',
                  started_at TEXT NOT NULL,
                  committed_at TEXT,
                  FOREIGN KEY(project_id) REFERENCES projects(project_id) ON DELETE CASCADE,
                  FOREIGN KEY(job_id) REFERENCES processing_jobs(job_id) ON DELETE CASCADE)
                """);
        s.execute("""
                CREATE UNIQUE INDEX IF NOT EXISTS idx_processing_batches_sequence
                ON processing_batches(job_id, sequence_no)
                """);
        s.execute("CREATE INDEX IF NOT EXISTS idx_processing_batches_project "
                + "ON processing_batches(project_id, status)");
    }

    /**
     * Latest resume point per job. Identity columns exist so a checkpoint is only ever reused when
     * the source, definition, plan and artifact format all still match.
     */
    private static void createProcessingCheckpoints(Statement s) throws SQLException {
        s.execute("""
                CREATE TABLE IF NOT EXISTS processing_checkpoints(
                  checkpoint_id TEXT PRIMARY KEY,
                  project_id TEXT NOT NULL,
                  job_id TEXT NOT NULL,
                  source_id TEXT NOT NULL,
                  source_fingerprint TEXT NOT NULL,
                  dbc_fingerprint TEXT NOT NULL,
                  plan_fingerprint TEXT NOT NULL,
                  artifact_version TEXT NOT NULL DEFAULT '1',
                  input_position INTEGER NOT NULL,
                  batch_sequence INTEGER NOT NULL,
                  last_timestamp_nanos INTEGER,
                  cursor_payload TEXT NOT NULL DEFAULT '',
                  committed_artifact_ref TEXT NOT NULL DEFAULT '',
                  state TEXT NOT NULL,
                  created_at TEXT NOT NULL,
                  FOREIGN KEY(project_id) REFERENCES projects(project_id) ON DELETE CASCADE,
                  FOREIGN KEY(job_id) REFERENCES processing_jobs(job_id) ON DELETE CASCADE)
                """);
        s.execute("""
                CREATE UNIQUE INDEX IF NOT EXISTS idx_processing_checkpoints_job
                ON processing_checkpoints(job_id)
                """);
        s.execute("CREATE INDEX IF NOT EXISTS idx_processing_checkpoints_project "
                + "ON processing_checkpoints(project_id, source_id)");
    }

    /**
     * Durable classified errors. Distinct from {@code data_quality_findings}, which records
     * observations about the measurement content rather than failures of the processing run.
     */
    private static void createProcessingErrors(Statement s) throws SQLException {
        s.execute("""
                CREATE TABLE IF NOT EXISTS processing_errors(
                  error_id TEXT PRIMARY KEY,
                  project_id TEXT NOT NULL,
                  run_id TEXT NOT NULL DEFAULT '',
                  job_id TEXT NOT NULL DEFAULT '',
                  source_id TEXT NOT NULL DEFAULT '',
                  classification TEXT NOT NULL,
                  severity TEXT NOT NULL,
                  retryable INTEGER NOT NULL DEFAULT 0,
                  summary TEXT NOT NULL,
                  technical_detail TEXT NOT NULL DEFAULT '',
                  created_at TEXT NOT NULL,
                  FOREIGN KEY(project_id) REFERENCES projects(project_id) ON DELETE CASCADE)
                """);
        s.execute("CREATE INDEX IF NOT EXISTS idx_processing_errors_project "
                + "ON processing_errors(project_id, created_at)");
        s.execute("CREATE INDEX IF NOT EXISTS idx_processing_errors_job ON processing_errors(job_id)");
    }

    /**
     * Append-only activity trail. The autoincrement key gives a durable total ordering that does
     * not depend on timestamp resolution. No foreign key: an audit row must never be rejected or
     * silently removed because of write ordering.
     */
    private static void createAuditLog(Statement s) throws SQLException {
        s.execute("""
                CREATE TABLE IF NOT EXISTS audit_log(
                  audit_id INTEGER PRIMARY KEY AUTOINCREMENT,
                  project_id TEXT NOT NULL,
                  event_type TEXT NOT NULL,
                  entity_id TEXT NOT NULL DEFAULT '',
                  summary TEXT NOT NULL DEFAULT '',
                  detail TEXT NOT NULL DEFAULT '',
                  created_at TEXT NOT NULL)
                """);
        s.execute("CREATE INDEX IF NOT EXISTS idx_audit_log_project ON audit_log(project_id, audit_id)");
        s.execute("CREATE INDEX IF NOT EXISTS idx_audit_log_type ON audit_log(project_id, event_type)");
    }

    /**
     * User annotations against the signal timeline. Timestamps only — never Canvas geometry,
     * pixel positions or rendered output.
     */
    private static void createGraphAnnotations(Statement s) throws SQLException {
        s.execute("""
                CREATE TABLE IF NOT EXISTS graph_annotations(
                  annotation_id TEXT PRIMARY KEY,
                  project_id TEXT NOT NULL,
                  signal_key TEXT NOT NULL DEFAULT '',
                  annotation_type TEXT NOT NULL,
                  start_timestamp_nanos INTEGER NOT NULL,
                  end_timestamp_nanos INTEGER,
                  annotation_value REAL,
                  label TEXT NOT NULL DEFAULT '',
                  note TEXT NOT NULL DEFAULT '',
                  created_at TEXT NOT NULL,
                  updated_at TEXT NOT NULL,
                  FOREIGN KEY(project_id) REFERENCES projects(project_id) ON DELETE CASCADE)
                """);
        s.execute("CREATE INDEX IF NOT EXISTS idx_graph_annotations_project "
                + "ON graph_annotations(project_id, start_timestamp_nanos)");
        s.execute("CREATE INDEX IF NOT EXISTS idx_graph_annotations_signal "
                + "ON graph_annotations(project_id, signal_key)");
    }

    /** Export Center history and live status. */
    private static void createExportJobs(Statement s) throws SQLException {
        s.execute("""
                CREATE TABLE IF NOT EXISTS export_jobs(
                  export_id TEXT PRIMARY KEY,
                  project_id TEXT NOT NULL,
                  export_type TEXT NOT NULL,
                  scope TEXT NOT NULL DEFAULT '',
                  status TEXT NOT NULL,
                  destination TEXT NOT NULL DEFAULT '',
                  bytes_written INTEGER NOT NULL DEFAULT 0,
                  message TEXT NOT NULL DEFAULT '',
                  created_at TEXT NOT NULL,
                  finished_at TEXT,
                  FOREIGN KEY(project_id) REFERENCES projects(project_id) ON DELETE CASCADE)
                """);
        s.execute("CREATE INDEX IF NOT EXISTS idx_export_jobs_project "
                + "ON export_jobs(project_id, created_at)");
    }
}
