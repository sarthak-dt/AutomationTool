package com.stellar.platform.storage.sqlite;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;

/**
 * Schema v19 — central numeric identities (1001+) while retaining TEXT stable
 * domain identities used by artifacts, SignalCatalogKey and crash/resume.
 */
public final class MigrationV19CentralIdentity implements SchemaMigration {

    @Override
    public int targetVersion() {
        return 19;
    }

    @Override
    public void apply(Connection connection) throws SQLException {
        SqliteIdentitySequences.bootstrap(connection);
        try (Statement s = connection.createStatement()) {
            addColumnIgnoreDuplicate(s, "ALTER TABLE projects ADD COLUMN numeric_id INTEGER");
            addColumnIgnoreDuplicate(s, "ALTER TABLE project_files ADD COLUMN file_pk INTEGER");
            addColumnIgnoreDuplicate(s, "ALTER TABLE source_inventory ADD COLUMN measurement_pk INTEGER");
            addColumnIgnoreDuplicate(s, "ALTER TABLE signal_catalog ADD COLUMN signal_pk INTEGER");
            addColumnIgnoreDuplicate(s, "ALTER TABLE processing_runs ADD COLUMN run_pk INTEGER");
            s.execute("""
                    CREATE TABLE IF NOT EXISTS legacy_project_import(
                      import_id INTEGER PRIMARY KEY AUTOINCREMENT,
                      legacy_db_path TEXT NOT NULL,
                      legacy_project_id TEXT NOT NULL,
                      numeric_project_id INTEGER NOT NULL,
                      imported_at TEXT NOT NULL,
                      UNIQUE(legacy_db_path, legacy_project_id))
                    """);
            s.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_projects_numeric_id ON projects(numeric_id)");
            s.execute("CREATE INDEX IF NOT EXISTS idx_project_files_pk ON project_files(file_pk)");
            s.execute("CREATE INDEX IF NOT EXISTS idx_source_inventory_measurement_pk ON source_inventory(measurement_pk)");
            s.execute("CREATE INDEX IF NOT EXISTS idx_signal_catalog_signal_pk ON signal_catalog(signal_pk)");
            s.execute("CREATE INDEX IF NOT EXISTS idx_processing_runs_run_pk ON processing_runs(run_pk)");
        }
        backfillNumericIds(connection);
    }

    private static void backfillNumericIds(Connection connection) throws SQLException {
        try (Statement s = connection.createStatement();
             ResultSet rs = s.executeQuery("SELECT project_id FROM projects WHERE numeric_id IS NULL")) {
            while (rs.next()) {
                long numeric = SqliteIdentitySequences.next(connection, SqliteIdentitySequences.PROJECTS);
                try (PreparedStatement upd = connection.prepareStatement(
                        "UPDATE projects SET numeric_id=? WHERE project_id=?")) {
                    upd.setLong(1, numeric);
                    upd.setString(2, rs.getString(1));
                    upd.executeUpdate();
                }
            }
        }
    }

    private static void addColumnIgnoreDuplicate(Statement s, String sql) throws SQLException {
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
