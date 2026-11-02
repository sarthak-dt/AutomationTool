package com.stellar.platform.storage.metadata;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Schema v12 introduces the production runtime tables without disturbing v11 data. */
class SchemaV12MigrationTest {

    @TempDir
    Path temp;

    @Test
    void schemaAdvancesToTwelve() {
        try (SqliteProjectMetadataStore store = new SqliteProjectMetadataStore(temp.resolve("v12.db"))) {
            assertEquals(SqliteProjectMetadataStore.currentSchemaVersion(), store.schemaVersion());
        }
    }

    @Test
    void productionRuntimeTablesExist() {
        try (SqliteProjectMetadataStore store = new SqliteProjectMetadataStore(temp.resolve("tables.db"))) {
            assertTrue(store.tableExists("processing_jobs"));
            assertTrue(store.tableExists("processing_batches"));
            assertTrue(store.tableExists("processing_checkpoints"));
            assertTrue(store.tableExists("processing_errors"));
            assertTrue(store.tableExists("audit_log"));
            assertTrue(store.tableExists("graph_annotations"));
            assertTrue(store.tableExists("export_jobs"));
        }
    }

    @Test
    void noSampleTableIsIntroduced() {
        try (SqliteProjectMetadataStore store = new SqliteProjectMetadataStore(temp.resolve("nosamples.db"))) {
            assertFalse(store.tableExists("signal_samples"));
            assertFalse(store.tableExists("samples"));
            assertFalse(store.tableExists("waveforms"));
            assertFalse(store.tableExists("graph_lod"));
        }
    }

    @Test
    void existingV11DataSurvivesUpgrade() {
        Path db = temp.resolve("upgrade.db");
        Instant saved = Instant.parse("2026-09-07T17:27:43Z");
        try (SqliteProjectMetadataStore store = new SqliteProjectMetadataStore(db)) {
            store.upsertProject(new ProjectMetadata("p1", "Legacy", Instant.now(), Instant.now(), "GRAPHS", saved));
        }
        // Reopening runs the migrator again; v11 rows must be untouched and v12 tables added.
        try (SqliteProjectMetadataStore reopened = new SqliteProjectMetadataStore(db)) {
            ProjectMetadata project = reopened.loadProject("p1").orElseThrow().project();
            assertEquals(saved, project.lastSavedAt());
            assertEquals("GRAPHS", project.lastWorkspace());
            assertTrue(reopened.tableExists("processing_jobs"));
        }
    }

    @Test
    void migrationIsIdempotentAcrossRepeatedOpens() {
        Path db = temp.resolve("repeat.db");
        for (int i = 0; i < 3; i++) {
            try (SqliteProjectMetadataStore store = new SqliteProjectMetadataStore(db)) {
                assertEquals(SqliteProjectMetadataStore.currentSchemaVersion(), store.schemaVersion());
            }
        }
    }
}
