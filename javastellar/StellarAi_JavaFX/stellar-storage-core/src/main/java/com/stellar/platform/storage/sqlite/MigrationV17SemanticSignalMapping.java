package com.stellar.platform.storage.sqlite;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Schema v17 — user-confirmed semantic signal mappings (GPS Latitude/Longitude/Altitude first).
 *
 * <p>Role is TEXT so Vehicle Speed / Heading / Acceleration can reuse the same table later.
 * Mappings are scoped per source; schema_fingerprint allows reuse only on compatible catalogs.
 */
public final class MigrationV17SemanticSignalMapping implements SchemaMigration {
    @Override
    public int targetVersion() {
        return 17;
    }

    @Override
    public void apply(Connection connection) throws SQLException {
        try (Statement s = connection.createStatement()) {
            s.execute("""
                    CREATE TABLE IF NOT EXISTS semantic_signal_mapping(
                      project_id TEXT NOT NULL,
                      source_id TEXT NOT NULL,
                      schema_fingerprint TEXT NOT NULL,
                      role TEXT NOT NULL,
                      signal_name TEXT NOT NULL,
                      source_type TEXT NOT NULL,
                      source_file TEXT NOT NULL,
                      message_name TEXT NOT NULL,
                      channel INTEGER NOT NULL,
                      user_confirmed INTEGER NOT NULL,
                      updated_at TEXT NOT NULL,
                      PRIMARY KEY(project_id, source_id, role),
                      FOREIGN KEY(project_id) REFERENCES projects(project_id) ON DELETE CASCADE)
                    """);
        }
    }
}
