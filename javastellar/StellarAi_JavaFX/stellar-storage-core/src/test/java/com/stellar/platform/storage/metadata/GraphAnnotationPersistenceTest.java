package com.stellar.platform.storage.metadata;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Graph annotations persist as timestamps and stable signal keys, never as rendered geometry. */
class GraphAnnotationPersistenceTest {

    private static final String PROJECT = "proj-annot";

    @TempDir
    Path temp;

    private Path db;

    @BeforeEach
    void createProject() {
        db = temp.resolve("annotations.db");
        try (SqliteProjectMetadataStore store = open()) {
            store.upsertProject(new ProjectMetadata(
                    PROJECT, "Annotated", Instant.now(), Instant.now(), "GRAPHS", null));
        }
    }

    private SqliteProjectMetadataStore open() {
        return new SqliteProjectMetadataStore(db);
    }

    @Test
    void allThreeAnnotationKindsSurviveReopen() {
        try (SqliteProjectMetadataStore store = open()) {
            store.upsertGraphAnnotation(new GraphAnnotationMetadata(
                    "a-point", PROJECT, "EngineRPM", GraphAnnotationType.POINT_MARKER,
                    320_000_000_000L, null, 6400.5d, "Redline", "", null, null));
            store.upsertGraphAnnotation(new GraphAnnotationMetadata(
                    "a-range", PROJECT, "VehicleSpeed", GraphAnnotationType.TIME_RANGE,
                    100_000_000_000L, 180_000_000_000L, null, "Coast down", "no throttle", null, null));
            store.upsertGraphAnnotation(new GraphAnnotationMetadata(
                    "a-note", PROJECT, "", GraphAnnotationType.TEXT_NOTE,
                    50_000_000_000L, null, null, "", "Sensor recalibrated here", null, null));
        }
        try (SqliteProjectMetadataStore reopened = open()) {
            List<GraphAnnotationMetadata> all = reopened.listGraphAnnotations(PROJECT);
            assertEquals(3, all.size());
            // Ordered by start timestamp: note (50s), range (100s), point (320s).
            assertEquals(List.of("a-note", "a-range", "a-point"),
                    all.stream().map(GraphAnnotationMetadata::annotationId).toList());

            GraphAnnotationMetadata point = all.get(2);
            assertSame(GraphAnnotationType.POINT_MARKER, point.annotationType());
            assertEquals("EngineRPM", point.signalKey());
            assertEquals(6400.5d, point.value());
            assertNull(point.endTimestampNanos());

            GraphAnnotationMetadata range = all.get(1);
            assertSame(GraphAnnotationType.TIME_RANGE, range.annotationType());
            assertEquals(180_000_000_000L, range.endTimestampNanos());
            assertEquals("no throttle", range.note());

            GraphAnnotationMetadata note = all.getFirst();
            assertEquals("", note.signalKey(), "a project-wide note has no signal key");
            assertEquals("Sensor recalibrated here", note.note());
        }
    }

    @Test
    void editingAnAnnotationUpdatesItInPlace() {
        try (SqliteProjectMetadataStore store = open()) {
            Instant created = Instant.parse("2026-09-08T09:00:00Z");
            store.upsertGraphAnnotation(new GraphAnnotationMetadata(
                    "a1", PROJECT, "EngineRPM", GraphAnnotationType.POINT_MARKER,
                    1000L, null, null, "Original", "", created, created));
            store.upsertGraphAnnotation(new GraphAnnotationMetadata(
                    "a1", PROJECT, "EngineRPM", GraphAnnotationType.POINT_MARKER,
                    1000L, null, null, "Corrected", "with detail", created,
                    Instant.parse("2026-09-08T09:30:00Z")));

            List<GraphAnnotationMetadata> all = store.listGraphAnnotations(PROJECT);
            assertEquals(1, all.size());
            assertEquals("Corrected", all.getFirst().label());
            assertEquals("with detail", all.getFirst().note());
            assertTrue(all.getFirst().updatedAt().isAfter(all.getFirst().createdAt()));
        }
    }

    @Test
    void deleteRemovesOnlyTheTargetedAnnotation() {
        try (SqliteProjectMetadataStore store = open()) {
            store.upsertGraphAnnotation(new GraphAnnotationMetadata(
                    "a1", PROJECT, "S", GraphAnnotationType.POINT_MARKER, 1L, null, null, "one", "", null, null));
            store.upsertGraphAnnotation(new GraphAnnotationMetadata(
                    "a2", PROJECT, "S", GraphAnnotationType.POINT_MARKER, 2L, null, null, "two", "", null, null));

            store.deleteGraphAnnotation(PROJECT, "a1");

            assertEquals(List.of("a2"), store.listGraphAnnotations(PROJECT).stream()
                    .map(GraphAnnotationMetadata::annotationId).toList());
        }
    }

    @Test
    void rangeAnnotationRequiresAValidEnd() {
        assertThrows(IllegalArgumentException.class, () -> new GraphAnnotationMetadata(
                "bad", PROJECT, "S", GraphAnnotationType.TIME_RANGE,
                1000L, null, null, "", "", null, null));
        assertThrows(IllegalArgumentException.class, () -> new GraphAnnotationMetadata(
                "bad", PROJECT, "S", GraphAnnotationType.TIME_RANGE,
                1000L, 500L, null, "", "", null, null));
    }
}
