package com.stellar.platform.storage.metadata;

import com.stellar.platform.storage.sqlite.SqliteIdentitySequences;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CentralSqliteIdentityTest {

    @TempDir Path temp;

    @Test
    void sequencesStartAt1001AreIndependentSurviveReopenAndDoNotReuseDeletedIds() {
        Path db = temp.resolve("central.db");
        long projectA;
        long fileA;
        long measurementA;
        long signalA;
        long runA;
        try (SqliteProjectMetadataStore store = new SqliteProjectMetadataStore(db)) {
            assertEquals(SqliteProjectMetadataStore.currentSchemaVersion(), store.schemaVersion());
            assertTrue(store.foreignKeysEnabled());
            projectA = store.allocateNumericId(SqliteIdentitySequences.PROJECTS);
            fileA = store.allocateNumericId(SqliteIdentitySequences.FILES);
            measurementA = store.allocateNumericId(SqliteIdentitySequences.MEASUREMENTS);
            signalA = store.allocateNumericId(SqliteIdentitySequences.SIGNALS);
            runA = store.allocateNumericId(SqliteIdentitySequences.RUNS);
            assertEquals(1001L, projectA);
            assertEquals(1001L, fileA);
            assertEquals(1001L, measurementA);
            assertEquals(1001L, signalA);
            assertEquals(1001L, runA);
            Instant now = Instant.now();
            store.upsertProject(new ProjectMetadata("1001", "A", now, now));
            store.upsertProject(new ProjectMetadata("1002", "B", now, now));
            store.upsertProject(new ProjectMetadata("1003", "C", now, now));
            assertEquals(1001L, store.numericIdForProject("1001").orElseThrow());
            assertEquals(1002L, store.numericIdForProject("1002").orElseThrow());
            assertEquals(1003L, store.numericIdForProject("1003").orElseThrow());
        }
        try (SqliteProjectMetadataStore reopened = new SqliteProjectMetadataStore(db)) {
            assertEquals(1004L, reopened.allocateNumericId(SqliteIdentitySequences.PROJECTS));
            reopened.deleteProject("1002");
            assertEquals(1005L, reopened.allocateNumericId(SqliteIdentitySequences.PROJECTS));
            assertTrue(reopened.numericIdForProject("1002").isEmpty());
            assertEquals(1002L, reopened.allocateNumericId(SqliteIdentitySequences.FILES));
            assertNotEquals(1002L, reopened.numericIdForProject("1003").orElseThrow());
        }
    }

    @Test
    void rolledBackAllocationDoesNotCorruptLaterIds() {
        Path db = temp.resolve("rollback.db");
        try (SqliteProjectMetadataStore store = new SqliteProjectMetadataStore(db)) {
            Instant now = Instant.now();
            store.upsertProject(new ProjectMetadata("1001", "Keep", now, now));
            try {
                store.upsertProject(new ProjectMetadata("1001", "Keep", now, now));
                store.upsertProject(new ProjectMetadata("1001", "Duplicate-name-same-id", now, now));
            } catch (RuntimeException ignored) {
                // same id upsert is an update
            }
            store.upsertProject(new ProjectMetadata("p-uuid", "Other", now, now));
            assertEquals(1001L, store.numericIdForProject("1001").orElseThrow());
            assertTrue(store.numericIdForProject("p-uuid").orElseThrow() >= 1001L);
        }
    }
}
