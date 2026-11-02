package com.stellar.platform.storage.sqlite;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Schema v7 — graph investigation state, one row per project.
 *
 * <p>Stores only reconstructable logical fields: membership identities, visibility, layout,
 * viewport, cursors and notes. Rendered output, LOD arrays and JavaFX objects stay out.</p>
 */
public final class MigrationV7GraphInvestigation implements SchemaMigration {
    @Override
    public int targetVersion() {
        return 7;
    }

    @Override
    public void apply(Connection connection) throws SQLException {
        try (Statement s = connection.createStatement()) {
            s.execute("""
                    CREATE TABLE IF NOT EXISTS graph_investigation(
                      project_id TEXT NOT NULL,
                      layout_mode TEXT NOT NULL,
                      viewport_start_nanos INTEGER,
                      viewport_end_nanos INTEGER,
                      default_start_nanos INTEGER,
                      default_end_nanos INTEGER,
                      cursor_a_nanos INTEGER,
                      cursor_b_nanos INTEGER,
                      notes TEXT,
                      traces_blob TEXT NOT NULL,
                      updated_at TEXT NOT NULL,
                      PRIMARY KEY(project_id),
                      FOREIGN KEY(project_id) REFERENCES projects(project_id) ON DELETE CASCADE)
                    """);
        }
    }
}
