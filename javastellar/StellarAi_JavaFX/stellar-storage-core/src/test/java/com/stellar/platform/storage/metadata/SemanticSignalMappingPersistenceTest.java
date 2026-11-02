package com.stellar.platform.storage.metadata;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SemanticSignalMappingPersistenceTest {

    @TempDir
    Path tempDir;

    @Test
    void userGpsMappingSurvivesReopenAndDoesNotLeakAcrossProjects() {
        Path db = tempDir.resolve("map.db");
        Instant now = Instant.now();
        try (SqliteProjectMetadataStore store = new SqliteProjectMetadataStore(db)) {
            assertEquals(SqliteProjectMetadataStore.currentSchemaVersion(), store.schemaVersion());
            assertTrue(store.tableExists("semantic_signal_mapping"));
            store.upsertProject(new ProjectMetadata("p1", "Drive", now, now));
            store.upsertProject(new ProjectMetadata("p2", "Other", now, now));
            store.upsertSemanticSignalMapping(SemanticSignalMappingMetadata.signalReference(
                    "p1", "a.mf4", "2:abcd", "LATITUDE", "Pos01", "MF4_NATIVE",
                    "a.mf4", "GPS", 0, true, now));
            store.upsertSemanticSignalMapping(SemanticSignalMappingMetadata.signalReference(
                    "p1", "a.mf4", "2:abcd", "LONGITUDE", "Pos02", "MF4_NATIVE",
                    "a.mf4", "GPS", 0, true, now));
        }
        try (SqliteProjectMetadataStore store = new SqliteProjectMetadataStore(db)) {
            List<SemanticSignalMappingMetadata> restored = store.listSemanticSignalMappings("p1", "a.mf4");
            assertEquals(2, restored.size());
            assertEquals("Pos01", restored.getFirst().signalName());
            assertTrue(store.listSemanticSignalMappings("p2").isEmpty());
            store.deleteProject("p1");
            assertTrue(store.listSemanticSignalMappings("p1").isEmpty());
        }
    }

    @Test
    void replacingARoleUpdatesTheSameRow() {
        Path db = tempDir.resolve("replace.db");
        Instant now = Instant.now();
        try (SqliteProjectMetadataStore store = new SqliteProjectMetadataStore(db)) {
            store.upsertProject(new ProjectMetadata("p1", "Drive", now, now));
            store.upsertSemanticSignalMapping(SemanticSignalMappingMetadata.signalReference(
                    "p1", "src", "fp", "LATITUDE", "OldLat", "MF4_NATIVE",
                    "src", "GPS", 0, true, now));
            store.upsertSemanticSignalMapping(SemanticSignalMappingMetadata.signalReference(
                    "p1", "src", "fp", "LATITUDE", "NewLat", "MF4_NATIVE",
                    "src", "GPS", 0, true, now.plusSeconds(1)));
            List<SemanticSignalMappingMetadata> rows = store.listSemanticSignalMappings("p1", "src");
            assertEquals(1, rows.size());
            assertEquals("NewLat", rows.getFirst().signalName());
        }
    }
}
