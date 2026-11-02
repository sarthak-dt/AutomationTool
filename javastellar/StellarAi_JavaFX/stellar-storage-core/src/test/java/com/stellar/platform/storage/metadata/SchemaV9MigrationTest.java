package com.stellar.platform.storage.metadata;

import com.stellar.platform.storage.sqlite.MigrationV3Baseline;
import com.stellar.platform.storage.sqlite.MigrationV4EnterpriseKnowledge;
import com.stellar.platform.storage.sqlite.MigrationV5UniqueProjectNames;
import com.stellar.platform.storage.sqlite.MigrationV6MapRouteState;
import com.stellar.platform.storage.sqlite.MigrationV7GraphInvestigation;
import com.stellar.platform.storage.sqlite.MigrationV8LastWorkspace;
import com.stellar.platform.storage.sqlite.SchemaMigrator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchemaV9MigrationTest {

    @TempDir Path tempDir;

    @Test
    void currentSchemaVersionIs9AndJobOutcomePersists() throws Exception {
        Path db = tempDir.resolve("v9.db");
        try (SqliteProjectMetadataStore store = new SqliteProjectMetadataStore(db)) {
            assertEquals(SqliteProjectMetadataStore.currentSchemaVersion(), store.schemaVersion());
            Instant now = Instant.now();
            store.upsertProject(new ProjectMetadata("p1", "Workspace", now, now, "SIGNALS"));
            store.upsertProcessingArtifact(new ProcessingArtifactMetadata(
                    "a1", "p1", "srcA", 1, "dbc", "run1",
                    ProcessingArtifactMetadata.TYPE_LOSSLESS_SIGNAL_STORE,
                    "",
                    "ref://a1",
                    ProcessingArtifactMetadata.ARTIFACT_VERSION_V1,
                    "fp-src", "fp-dbc",
                    3, 100, 1L, 2L,
                    ProcessingArtifactMetadata.STATUS_VALID,
                    now,
                    "AUTO",
                    "SUCCESS_WITH_WARNINGS",
                    "PIPELINE_DIAGNOSTIC|srcA|1|coverage warning",
                    "tech"
            ));
            ProcessingArtifactMetadata loaded = store.findValidArtifact("p1", "srcA", 1, "dbc").orElseThrow();
            assertEquals("SUCCESS_WITH_WARNINGS", loaded.jobExecutionStatus());
            assertTrue(loaded.diagnosticsSummary().contains("coverage warning"));
            assertEquals("tech", loaded.technicalDetail());
        }
    }

    @Test
    void supportedOldSchemaMigratesTo9() throws Exception {
        Path db = tempDir.resolve("legacy.db");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db)) {
            new SchemaMigrator(java.util.List.of(
                    new MigrationV3Baseline(),
                    new MigrationV4EnterpriseKnowledge(),
                    new MigrationV5UniqueProjectNames(),
                    new MigrationV6MapRouteState(),
                    new MigrationV7GraphInvestigation(),
                    new MigrationV8LastWorkspace()
            )).migrate(connection);
            try (Statement s = connection.createStatement()) {
                s.execute("INSERT INTO projects(project_id, name, normalized_name, created_at, updated_at) "
                        + "VALUES('p-old','Old','old','2026-01-01T00:00:00Z','2026-01-01T00:00:00Z')");
            }
            try (var rs = connection.createStatement().executeQuery("SELECT version FROM schema_info")) {
                assertTrue(rs.next());
                assertEquals(8, rs.getInt(1));
            }
        }

        try (SqliteProjectMetadataStore store = new SqliteProjectMetadataStore(db)) {
            assertEquals(SqliteProjectMetadataStore.currentSchemaVersion(), store.schemaVersion());
            assertEquals("Old", store.loadProject("p-old").orElseThrow().project().name());
        }
    }
}
