package com.stellar.platform.storage.metadata;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchemaV18ConfigMappingMigrationTest {

    @TempDir
    Path temp;

    @Test
    void schemaAdvancesToEighteenWithTheGenericConfigurationColumns() throws SQLException {
        Path db = temp.resolve("v18.db");
        Instant now = Instant.now();
        try (SqliteProjectMetadataStore store = new SqliteProjectMetadataStore(db)) {
            assertEquals(SqliteProjectMetadataStore.currentSchemaVersion(), store.schemaVersion());
            assertTrue(store.tableExists("semantic_signal_mapping"));
            store.upsertProject(new ProjectMetadata("p1", "Drive", now, now));
            store.upsertSemanticSignalMapping(SemanticSignalMappingMetadata.signalReference(
                    "p1", "src", "fp", "LATITUDE", "Pos01", "MF4_NATIVE",
                    "src", "GPS", 0, true, now));
            assertEquals("Pos01", store.listSemanticSignalMappings("p1").getFirst().signalName());
        }
        assertTrue(columns(db).containsAll(
                Set.of("config_group", "scope", "mapping_mode", "manual_text")));
        try (SqliteProjectMetadataStore reopened = new SqliteProjectMetadataStore(db)) {
            assertEquals(SqliteProjectMetadataStore.currentSchemaVersion(), reopened.schemaVersion());
            assertEquals(1, reopened.listSemanticSignalMappings("p1").size());
        }
    }

    /** A database written before v18 keeps every mapping and reads back as a project signal reference. */
    @Test
    void mappingsSavedBeforeTheMigrationStillReadBackUnchanged() throws SQLException {
        Path db = temp.resolve("upgrade.db");
        Instant now = Instant.now();
        try (SqliteProjectMetadataStore store = new SqliteProjectMetadataStore(db)) {
            store.upsertProject(new ProjectMetadata("p1", "Drive", now, now));
        }
        // Recreate exactly what a v17 installation left behind: the row without the v18 columns.
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement s = c.createStatement()) {
            s.execute("DROP TABLE semantic_signal_mapping");
            s.execute("""
                    CREATE TABLE semantic_signal_mapping(
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
                      PRIMARY KEY(project_id, source_id, role))
                    """);
            s.execute("INSERT INTO semantic_signal_mapping VALUES("
                    + "'p1','a.mf4','2:abcd','LATITUDE','LegacyLat','MF4_NATIVE','a.mf4','GPS',0,1,'"
                    + now + "')");
            s.execute("DELETE FROM schema_info");
            s.execute("INSERT INTO schema_info(version) VALUES(17)");
        }

        try (SqliteProjectMetadataStore store = new SqliteProjectMetadataStore(db)) {
            assertEquals(SqliteProjectMetadataStore.currentSchemaVersion(), store.schemaVersion());
            List<SemanticSignalMappingMetadata> rows = store.listSemanticSignalMappings("p1");
            assertEquals(1, rows.size());
            SemanticSignalMappingMetadata restored = rows.getFirst();
            assertEquals("LegacyLat", restored.signalName());
            assertEquals(SemanticSignalMappingMetadata.GROUP_MAP_ROUTE, restored.configGroup());
            assertEquals(SemanticSignalMappingMetadata.SCOPE_PROJECT, restored.scope());
            assertEquals(SemanticSignalMappingMetadata.MODE_SIGNAL_REFERENCE, restored.mappingMode());
            assertFalse(restored.manual());
        }
    }

    @Test
    void aManuallyTypedSignalNameIsStoredAndRestored() {
        Path db = temp.resolve("manual.db");
        Instant now = Instant.now();
        try (SqliteProjectMetadataStore store = new SqliteProjectMetadataStore(db)) {
            store.upsertProject(new ProjectMetadata("p1", "Drive", now, now));
            store.upsertSemanticSignalMapping(SemanticSignalMappingMetadata.manualText(
                    "p1", "a.mf4", "2:abcd", "LATITUDE", "Vehicle.GNSS.Lat", true, now));
        }
        try (SqliteProjectMetadataStore store = new SqliteProjectMetadataStore(db)) {
            SemanticSignalMappingMetadata restored = store.listSemanticSignalMappings("p1").getFirst();
            assertTrue(restored.manual());
            assertEquals("Vehicle.GNSS.Lat", restored.manualText());
            assertEquals("Vehicle.GNSS.Lat", restored.effectiveSignalName());
        }
    }

    private static Set<String> columns(Path db) throws SQLException {
        Set<String> names = new LinkedHashSet<>();
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("PRAGMA table_info(semantic_signal_mapping)")) {
            while (rs.next()) {
                names.add(rs.getString("name"));
            }
        }
        return names;
    }
}
