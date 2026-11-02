package com.stellar.platform.storage.sqlite;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;

/**
 * Schema v11 — explicit Save checkpoint fields.
 *
 * <p>{@code projects.last_saved_at} is the wall-clock of the last successful explicit Save.
 * Graph investigation gains lightweight presentation fields (focus, analysis tab, inspector,
 * plot-settings, signals chrome) without storing samples.</p>
 */
public final class MigrationV11WorkspaceCheckpoint implements SchemaMigration {
    @Override
    public int targetVersion() {
        return 11;
    }

    @Override
    public void apply(Connection connection) throws SQLException {
        try (Statement s = connection.createStatement()) {
            addColumnIgnoreDuplicate(s, "ALTER TABLE projects ADD COLUMN last_saved_at TEXT");
            addColumnIgnoreDuplicate(s, "ALTER TABLE graph_investigation ADD COLUMN focus_key_blob TEXT");
            addColumnIgnoreDuplicate(s, "ALTER TABLE graph_investigation ADD COLUMN analysis_tab INTEGER NOT NULL DEFAULT 0");
            addColumnIgnoreDuplicate(s, "ALTER TABLE graph_investigation ADD COLUMN inspector_visible INTEGER NOT NULL DEFAULT 1");
            addColumnIgnoreDuplicate(s, "ALTER TABLE graph_investigation ADD COLUMN plot_settings_visible INTEGER NOT NULL DEFAULT 0");
            addColumnIgnoreDuplicate(s, "ALTER TABLE graph_investigation ADD COLUMN signals_display_mode TEXT");
            addColumnIgnoreDuplicate(s, "ALTER TABLE graph_investigation ADD COLUMN signals_precision TEXT");
            addColumnIgnoreDuplicate(s, "ALTER TABLE graph_investigation ADD COLUMN presentation_version INTEGER NOT NULL DEFAULT 1");
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
