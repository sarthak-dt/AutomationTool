package com.stellar.platform.storage.metadata;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchemaV8MigrationTest {

    @TempDir Path tempDir;

    @Test
    void currentSchemaVersionIsAtLeast8AndLastWorkspacePersists() throws Exception {
        Path db = tempDir.resolve("v8.db");
        try (SqliteProjectMetadataStore store = new SqliteProjectMetadataStore(db)) {
            assertTrue(store.schemaVersion() >= 8);
            Instant now = Instant.now();
            store.upsertProject(new ProjectMetadata("p1", "Workspace", now, now, "SIGNALS"));
            assertEquals("SIGNALS", store.loadProject("p1").orElseThrow().project().lastWorkspace());
        }
    }

    @Test
    void supportedOldSchemaMigratesThroughV8() throws Exception {
        Path db = tempDir.resolve("legacy.db");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db)) {
            new com.stellar.platform.storage.sqlite.SchemaMigrator(java.util.List.of(
                    new com.stellar.platform.storage.sqlite.MigrationV3Baseline(),
                    new com.stellar.platform.storage.sqlite.MigrationV4EnterpriseKnowledge(),
                    new com.stellar.platform.storage.sqlite.MigrationV5UniqueProjectNames(),
                    new com.stellar.platform.storage.sqlite.MigrationV6MapRouteState(),
                    new com.stellar.platform.storage.sqlite.MigrationV7GraphInvestigation()
            )).migrate(connection);
            try (Statement s = connection.createStatement()) {
                s.execute("INSERT INTO projects(project_id, name, normalized_name, created_at, updated_at) "
                        + "VALUES('p-old','Old','old','2026-01-01T00:00:00Z','2026-01-01T00:00:00Z')");
            }
            try (var rs = connection.createStatement().executeQuery("SELECT version FROM schema_info")) {
                assertTrue(rs.next());
                assertEquals(7, rs.getInt(1));
            }
        }

        try (SqliteProjectMetadataStore store = new SqliteProjectMetadataStore(db)) {
            assertTrue(store.schemaVersion() >= 8);
            assertEquals("Old", store.loadProject("p-old").orElseThrow().project().name());
            assertEquals("", store.loadProject("p-old").orElseThrow().project().lastWorkspace());
        }
    }
}
