package com.stellar.platform.storage.metadata;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchemaV20AnalysisScopeMigrationTest {

    @TempDir
    Path temp;

    @Test
    void schemaAdvancesToTwentyAndPersistsAnalysisScope() {
        Path db = temp.resolve("v20.db");
        Instant now = Instant.now();
        try (SqliteProjectMetadataStore store = new SqliteProjectMetadataStore(db)) {
            assertEquals(21, SqliteProjectMetadataStore.currentSchemaVersion());
            assertEquals(SqliteProjectMetadataStore.currentSchemaVersion(), store.schemaVersion());
            store.upsertProject(new ProjectMetadata("p1", "Drive", now, now));
            store.upsertAnalysisScope(new AnalysisScopeRecord("p1", "ALL", "", "", now));
            AnalysisScopeRecord all = store.findAnalysisScope("p1").orElseThrow();
            assertTrue(all.allMeasurements());
            assertEquals("", all.sourceId());

            store.upsertAnalysisScope(new AnalysisScopeRecord("p1", "SINGLE", "src-B", "B.mf4", now));
            AnalysisScopeRecord single = store.findAnalysisScope("p1").orElseThrow();
            assertFalse(single.allMeasurements());
            assertEquals("src-B", single.sourceId());
            assertEquals("B.mf4", single.fileName());
        }
        try (SqliteProjectMetadataStore reopened = new SqliteProjectMetadataStore(db)) {
            assertEquals(SqliteProjectMetadataStore.currentSchemaVersion(), reopened.schemaVersion());
            AnalysisScopeRecord restored = reopened.findAnalysisScope("p1").orElseThrow();
            assertEquals("SINGLE", restored.scopeKind());
            assertEquals("src-B", restored.sourceId());
        }
    }

    @Test
    void analysisScopeIsProjectIsolated() {
        Path db = temp.resolve("iso.db");
        Instant now = Instant.now();
        try (SqliteProjectMetadataStore store = new SqliteProjectMetadataStore(db)) {
            store.upsertProject(new ProjectMetadata("p1", "One", now, now));
            store.upsertProject(new ProjectMetadata("p2", "Two", now, now));
            store.upsertAnalysisScope(new AnalysisScopeRecord("p1", "ALL", "", "", now));
            store.upsertAnalysisScope(new AnalysisScopeRecord("p2", "SINGLE", "src-Z", "Z.mf4", now));
            assertTrue(store.findAnalysisScope("p1").orElseThrow().allMeasurements());
            assertEquals("src-Z", store.findAnalysisScope("p2").orElseThrow().sourceId());
            assertTrue(store.findAnalysisScope("missing").isEmpty());
        }
    }
}
