package com.stellar.platform.storage.metadata;

import com.stellar.platform.storage.sqlite.MigrationV3Baseline;
import com.stellar.platform.storage.sqlite.MigrationV4EnterpriseKnowledge;
import com.stellar.platform.storage.sqlite.MigrationV5UniqueProjectNames;
import com.stellar.platform.storage.sqlite.MigrationV6MapRouteState;
import com.stellar.platform.storage.sqlite.MigrationV7GraphInvestigation;
import com.stellar.platform.storage.sqlite.MigrationV8LastWorkspace;
import com.stellar.platform.storage.sqlite.MigrationV9ArtifactJobOutcome;
import com.stellar.platform.storage.sqlite.SchemaMigrator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchemaV10MigrationTest {

    @TempDir Path tempDir;

    @Test
    void currentSchemaVersionIs10AndPlanFingerprintPersists() throws Exception {
        Path db = tempDir.resolve("v10.db");
        try (SqliteProjectMetadataStore store = new SqliteProjectMetadataStore(db)) {
            assertEquals(SqliteProjectMetadataStore.currentSchemaVersion(), store.schemaVersion());
            Instant now = Instant.now();
            store.upsertProject(new ProjectMetadata("p1", "Workspace", now, now, "SIGNALS"));
            String planFp = ProcessingPlanFingerprint.forJob("srcA", 1, "dbc", "CONFIRMED", "ASC");
            store.upsertProcessingArtifact(new ProcessingArtifactMetadata(
                    "a1", "p1", "srcA", 1, "dbc", "run1",
                    ProcessingArtifactMetadata.TYPE_LOSSLESS_SIGNAL_STORE,
                    "",
                    "ref://a1",
                    ProcessingArtifactMetadata.ARTIFACT_VERSION_V1,
                    "fp-src", "fp-dbc", planFp,
                    3, 100, 1L, 2L,
                    ProcessingArtifactMetadata.STATUS_VALID,
                    now,
                    "CONFIRMED",
                    "SUCCESS_WITH_WARNINGS",
                    "PIPELINE_DIAGNOSTIC|srcA|1|coverage warning",
                    "tech"
            ));
            ProcessingArtifactMetadata loaded = store.findValidArtifact("p1", "srcA", 1, "dbc").orElseThrow();
            assertEquals(planFp, loaded.planFingerprint());
            assertEquals("SUCCESS_WITH_WARNINGS", loaded.jobExecutionStatus());
            assertTrue(loaded.diagnosticsSummary().contains("coverage warning"));
            assertEquals("a1", loaded.jobId());
        }
    }

    @Test
    void restartPreservesDiagnosticsAndRejectsStaleLookup() throws Exception {
        Path db = tempDir.resolve("restart.db");
        Instant now = Instant.now();
        String planFp = ProcessingPlanFingerprint.forJob("srcA", 0, "dbc", "CONFIRMED", "ASC");
        try (SqliteProjectMetadataStore store = new SqliteProjectMetadataStore(db)) {
            store.upsertProject(new ProjectMetadata("p1", "P", now, now, "SIGNALS"));
            store.upsertProcessingRun(new ProcessingRunMetadata(
                    "run1", "p1", "COMPLETED_WITH_WARNINGS", false, 1, now, now, "ok"));
            store.upsertProcessingArtifact(new ProcessingArtifactMetadata(
                    "a1", "p1", "srcA", 0, "dbc", "run1",
                    ProcessingArtifactMetadata.TYPE_LOSSLESS_SIGNAL_STORE,
                    "", "ref://a1",
                    ProcessingArtifactMetadata.ARTIFACT_VERSION_V1,
                    "fp-src", "fp-dbc", planFp,
                    2, 50, 1L, 2L,
                    ProcessingArtifactMetadata.STATUS_VALID,
                    now, "CONFIRMED",
                    "SUCCESS_WITH_WARNINGS",
                    "PIPELINE_DIAGNOSTIC|srcA|0|warn detail",
                    "tech-detail"
            ));
        }

        try (SqliteProjectMetadataStore reopened = new SqliteProjectMetadataStore(db)) {
            ProcessingArtifactMetadata loaded = reopened.findValidArtifact("p1", "srcA", 0, "dbc").orElseThrow();
            assertEquals("SUCCESS_WITH_WARNINGS", loaded.jobExecutionStatus());
            assertTrue(loaded.diagnosticsSummary().contains("warn detail"));
            assertEquals("tech-detail", loaded.technicalDetail());
            assertEquals(planFp, loaded.planFingerprint());

            reopened.upsertProcessingArtifact(loaded.withStatus(ProcessingArtifactMetadata.STATUS_STALE));
            assertTrue(reopened.findValidArtifact("p1", "srcA", 0, "dbc").isEmpty(),
                    "STALE artifacts must not be reusable");
        }
    }

    @Test
    void twoProjectsRemainIsolated() throws Exception {
        Path db = tempDir.resolve("iso.db");
        Instant now = Instant.now();
        try (SqliteProjectMetadataStore store = new SqliteProjectMetadataStore(db)) {
            store.upsertProject(new ProjectMetadata("pA", "A", now, now, "SIGNALS"));
            store.upsertProject(new ProjectMetadata("pB", "B", now, now, "SIGNALS"));
            store.upsertProcessingArtifact(new ProcessingArtifactMetadata(
                    "pA|src|0|d", "pA", "src", 0, "d", "r1",
                    ProcessingArtifactMetadata.TYPE_LOSSLESS_SIGNAL_STORE,
                    "", "ref://a", "1", "s", "d", "plan-a",
                    1, 1, null, null, ProcessingArtifactMetadata.STATUS_VALID, now, "CONFIRMED",
                    "SUCCESS", "", ""));
            store.upsertProcessingArtifact(new ProcessingArtifactMetadata(
                    "pB|src|0|d", "pB", "src", 0, "d", "r1",
                    ProcessingArtifactMetadata.TYPE_LOSSLESS_SIGNAL_STORE,
                    "", "ref://b", "1", "s", "d", "plan-b",
                    9, 9, null, null, ProcessingArtifactMetadata.STATUS_VALID, now, "CONFIRMED",
                    "SUCCESS", "", ""));
            assertEquals(1, store.findValidArtifact("pA", "src", 0, "d").orElseThrow().signalCount());
            assertEquals(9, store.findValidArtifact("pB", "src", 0, "d").orElseThrow().signalCount());
            assertFalse(store.findValidArtifact("pA", "src", 0, "d").orElseThrow().planFingerprint()
                    .equals(store.findValidArtifact("pB", "src", 0, "d").orElseThrow().planFingerprint()));
        }
    }

    @Test
    void supportedOldSchemaMigratesTo10() throws Exception {
        Path db = tempDir.resolve("legacy.db");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db)) {
            new SchemaMigrator(java.util.List.of(
                    new MigrationV3Baseline(),
                    new MigrationV4EnterpriseKnowledge(),
                    new MigrationV5UniqueProjectNames(),
                    new MigrationV6MapRouteState(),
                    new MigrationV7GraphInvestigation(),
                    new MigrationV8LastWorkspace(),
                    new MigrationV9ArtifactJobOutcome()
            )).migrate(connection);
            try (Statement s = connection.createStatement()) {
                s.execute("INSERT INTO projects(project_id, name, normalized_name, created_at, updated_at) "
                        + "VALUES('p-old','Old','old','2026-01-01T00:00:00Z','2026-01-01T00:00:00Z')");
            }
        }

        try (SqliteProjectMetadataStore store = new SqliteProjectMetadataStore(db)) {
            assertEquals(SqliteProjectMetadataStore.currentSchemaVersion(), store.schemaVersion());
            assertEquals("Old", store.loadProject("p-old").orElseThrow().project().name());
        }
    }
}
