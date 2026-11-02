package com.stellar.platform.storage.sqlite;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;

/**
 * Schema v18 — generic configuration mapping instead of GPS-only signal references.
 *
 * <p>{@code semantic_signal_mapping} keeps its identity and every existing row: the new columns
 * default to exactly what those rows already mean, a project-scoped MAP_ROUTE mapping that points at
 * a catalog signal. The additions let one table also carry a signal name typed by hand, which is the
 * only way to configure a product whose channel names are not present in the loaded measurement.
 */
public final class MigrationV18ConfigMappingScope implements SchemaMigration {

    @Override
    public int targetVersion() {
        return 18;
    }

    @Override
    public void apply(Connection connection) throws SQLException {
        try (Statement s = connection.createStatement()) {
            addColumnIgnoreDuplicate(s, """
                    ALTER TABLE semantic_signal_mapping
                    ADD COLUMN config_group TEXT NOT NULL DEFAULT 'MAP_ROUTE'""");
            addColumnIgnoreDuplicate(s, """
                    ALTER TABLE semantic_signal_mapping
                    ADD COLUMN scope TEXT NOT NULL DEFAULT 'PROJECT'""");
            addColumnIgnoreDuplicate(s, """
                    ALTER TABLE semantic_signal_mapping
                    ADD COLUMN mapping_mode TEXT NOT NULL DEFAULT 'SIGNAL_REFERENCE'""");
            addColumnIgnoreDuplicate(s, """
                    ALTER TABLE semantic_signal_mapping
                    ADD COLUMN manual_text TEXT NOT NULL DEFAULT ''""");
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
