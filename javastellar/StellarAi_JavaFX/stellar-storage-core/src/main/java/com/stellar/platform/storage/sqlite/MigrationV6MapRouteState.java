package com.stellar.platform.storage.sqlite;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Schema v6 — Map Route view state, one row per (project, measurement).
 * <p>
 * Stores only the selection mode, the resolved timestamps and the sync toggle. GPS coordinate
 * arrays are deliberately not persisted here: they are rebuilt from the already-processed signals
 * when the measurement is reopened, so saving a project does not duplicate route data.
 */
public final class MigrationV6MapRouteState implements SchemaMigration {
    @Override
    public int targetVersion() {
        return 6;
    }

    @Override
    public void apply(Connection connection) throws SQLException {
        try (Statement s = connection.createStatement()) {
            s.execute("""
                    CREATE TABLE IF NOT EXISTS map_route_state(
                      project_id TEXT NOT NULL,
                      source_id TEXT NOT NULL,
                      mode TEXT NOT NULL,
                      point_timestamp_nanos INTEGER,
                      range_start_nanos INTEGER,
                      range_end_nanos INTEGER,
                      auto_sync INTEGER NOT NULL,
                      updated_at TEXT NOT NULL,
                      PRIMARY KEY(project_id, source_id),
                      FOREIGN KEY(project_id) REFERENCES projects(project_id) ON DELETE CASCADE)
                    """);
        }
    }
}
