package com.stellar.platform.storage.metadata;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchemaV13MigrationTest {

    @TempDir
    Path temp;

    @Test
    void schemaAdvancesToThirteenAndStoresLightweightAnalysis() {
        try (SqliteProjectMetadataStore store = new SqliteProjectMetadataStore(temp.resolve("v13.db"))) {
            assertEquals(SqliteProjectMetadataStore.currentSchemaVersion(), store.schemaVersion());
            assertTrue(store.tableExists("analysis_runs"));
            assertTrue(store.tableExists("analysis_findings"));
            assertTrue(store.tableExists("derived_signal_definitions"));
            assertTrue(store.tableExists("analysis_workspace"));
            store.upsertProject(new ProjectMetadata("p1", "A", Instant.now(), Instant.now(), "GRAPHS", null));
            store.upsertAnalysisRun(new AnalysisRunMetadata(
                    "an1", "p1", "STATISTICS", "COMPLETED", "EXACT_TIMESTAMP", "1",
                    "VIEWPORT", 1L, 2L, "Speed", "", "inv", "ref", "ok", Instant.now(), Instant.now()));
            store.replaceAnalysisFindings("an1", List.of(new AnalysisFindingRow(
                    "f1", "an1", "p1", "THRESHOLD_EVENT", "OBSERVATION", 1L, 2L, "hot", "", "", Instant.now())));
            store.upsertAnalysisWorkspace(new AnalysisWorkspaceMetadata(
                    "p1", "STATISTICS", "an1", true, "f1", Instant.now()));
            store.upsertDerivedSignalDefinition(new DerivedSignalDefinitionMetadata(
                    "d1", "p1", "ident-1", "sub", "EXACT_TIMESTAMP", "1", Instant.now()));
        }
        try (SqliteProjectMetadataStore reopened = new SqliteProjectMetadataStore(temp.resolve("v13.db"))) {
            assertEquals("STATISTICS", reopened.findAnalysisRun("an1").orElseThrow().analysisType());
            assertEquals(1, reopened.listAnalysisFindings("an1").size());
            assertEquals("an1", reopened.findAnalysisWorkspace("p1").orElseThrow().lastAnalysisId());
            assertEquals("sub", reopened.findDerivedSignalDefinition("p1", "ident-1").orElseThrow().expression());
        }
    }
}
