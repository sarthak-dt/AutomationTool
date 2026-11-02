package com.stellar.platform.storage.metadata;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Schema v6 Map Route view state: lightweight selection only, scoped per measurement,
 * and never carrying route geometry into SQLite.
 */
class MapRouteStatePersistenceTest {

    @TempDir Path tempDir;

    private SqliteProjectMetadataStore openStore(String name) {
        Path db = tempDir.resolve(name);
        SqliteProjectMetadataStore store = new SqliteProjectMetadataStore(db);
        Instant now = Instant.now();
        store.upsertProject(new ProjectMetadata("p1", "Road Test", now, now));
        return store;
    }

    @Test
    void rangeSelectionSurvivesReopeningTheProject() {
        Instant now = Instant.now();
        try (SqliteProjectMetadataStore store = openStore("range.db")) {
            store.upsertMapRouteState(new MapRouteStateMetadata(
                    "p1", "src1", "A_B_RANGE", null, 1_000L, 9_000L, true, now));
        }
        try (SqliteProjectMetadataStore store = new SqliteProjectMetadataStore(tempDir.resolve("range.db"))) {
            MapRouteStateMetadata restored = store.findMapRouteState("p1", "src1").orElseThrow();
            assertEquals("A_B_RANGE", restored.mode());
            assertNull(restored.pointTimestampNanos());
            assertEquals(1_000L, restored.rangeStartNanos());
            assertEquals(9_000L, restored.rangeEndNanos());
            assertTrue(restored.autoSync());
        }
    }

    @Test
    void singlePointSelectionRoundTripsWithoutRangeBounds() {
        try (SqliteProjectMetadataStore store = openStore("point.db")) {
            store.upsertMapRouteState(new MapRouteStateMetadata(
                    "p1", "src1", "SINGLE_POINT", 4_242L, null, null, false, Instant.now()));
            MapRouteStateMetadata restored = store.findMapRouteState("p1", "src1").orElseThrow();
            assertEquals(4_242L, restored.pointTimestampNanos());
            assertNull(restored.rangeStartNanos());
            assertNull(restored.rangeEndNanos());
            assertFalse(restored.autoSync());
        }
    }

    @Test
    void stateIsScopedPerMeasurementSoANewFileNeverInheritsTheOldSelection() {
        try (SqliteProjectMetadataStore store = openStore("scoped.db")) {
            store.upsertMapRouteState(new MapRouteStateMetadata(
                    "p1", "src1", "SINGLE_POINT", 100L, null, null, true, Instant.now()));
            assertTrue(store.findMapRouteState("p1", "src2").isEmpty());
            assertTrue(store.findMapRouteState("other-project", "src1").isEmpty());
        }
    }

    @Test
    void reselectingOverwritesRatherThanAccumulatingRows() throws Exception {
        Path db = tempDir.resolve("upsert.db");
        try (SqliteProjectMetadataStore store = new SqliteProjectMetadataStore(db)) {
            Instant now = Instant.now();
            store.upsertProject(new ProjectMetadata("p1", "Road Test", now, now));
            for (int i = 1; i <= 25; i++) {
                store.upsertMapRouteState(new MapRouteStateMetadata(
                        "p1", "src1", "SINGLE_POINT", (long) i, null, null, true, now));
            }
            assertEquals(25L, store.findMapRouteState("p1", "src1").orElseThrow().pointTimestampNanos());
        }
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db);
             var rs = connection.createStatement().executeQuery("SELECT COUNT(*) FROM map_route_state")) {
            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1));
        }
    }

    @Test
    void savingASelectionDoesNotAgeTheProjectRecord() {
        try (SqliteProjectMetadataStore store = openStore("touch.db")) {
            Instant before = store.loadProject("p1").orElseThrow().project().updatedAt();
            store.upsertMapRouteState(new MapRouteStateMetadata(
                    "p1", "src1", "SINGLE_POINT", 7L, null, null, true, Instant.now()));
            assertEquals(before, store.loadProject("p1").orElseThrow().project().updatedAt());
        }
    }

    @Test
    void deletingTheProjectRemovesItsMapRouteState() throws Exception {
        Path db = tempDir.resolve("cascade.db");
        System.setProperty("stellarai.home", tempDir.resolve("home").toString());
        try (SqliteProjectMetadataStore store = new SqliteProjectMetadataStore(db)) {
            Instant now = Instant.now();
            store.upsertProject(new ProjectMetadata("p1", "Road Test", now, now));
            store.upsertMapRouteState(new MapRouteStateMetadata(
                    "p1", "src1", "SINGLE_POINT", 7L, null, null, true, now));
            store.deleteProject("p1");
            Optional<MapRouteStateMetadata> remaining = store.findMapRouteState("p1", "src1");
            assertTrue(remaining.isEmpty());
        }
    }
}
