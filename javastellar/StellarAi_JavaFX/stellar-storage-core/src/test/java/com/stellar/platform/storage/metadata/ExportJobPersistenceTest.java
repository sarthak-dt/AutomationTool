package com.stellar.platform.storage.metadata;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/** Export Center history and status transitions. */
class ExportJobPersistenceTest {

    private static final String PROJECT = "proj-export";

    @TempDir
    Path temp;

    private Path db;

    @BeforeEach
    void createProject() {
        db = temp.resolve("exports.db");
        try (SqliteProjectMetadataStore store = open()) {
            store.upsertProject(new ProjectMetadata(
                    PROJECT, "Exporter", Instant.now(), Instant.now(), "SIGNALS", null));
        }
    }

    private SqliteProjectMetadataStore open() {
        return new SqliteProjectMetadataStore(db);
    }

    @Test
    void exportProgressesThroughItsLifecycleAndSurvivesReopen() {
        ExportJobMetadata queued = ExportJobMetadata.queued(
                PROJECT, "SIGNALS_CSV", "3 signals", "C:/exports/signals.csv");
        try (SqliteProjectMetadataStore store = open()) {
            store.upsertExportJob(queued);
            store.upsertExportJob(queued.withStatus(ExportJobStatus.EXPORTING, ""));
            store.upsertExportJob(queued.withProgress(2_048L).withStatus(ExportJobStatus.COMPLETED, "Done"));
        }
        try (SqliteProjectMetadataStore reopened = open()) {
            ExportJobMetadata found = reopened.findExportJob(queued.exportId()).orElseThrow();
            assertSame(ExportJobStatus.COMPLETED, found.status());
            assertEquals(2_048L, found.bytesWritten());
            assertEquals("Done", found.message());
            assertEquals("C:/exports/signals.csv", found.destination());
            assertEquals("SIGNALS_CSV", found.exportType());
            assertEquals("3 signals", found.scope());
            assertNotNull(found.finishedAt(), "a terminal export must record when it finished");

            assertEquals(1, reopened.listExportJobs(PROJECT, 10).size());
        }
    }

    @Test
    void failedExportKeepsItsReason() {
        ExportJobMetadata job = ExportJobMetadata.queued(PROJECT, "GRAPH_PNG", "viewport", "C:/exports/g.png");
        try (SqliteProjectMetadataStore store = open()) {
            store.upsertExportJob(job.withStatus(ExportJobStatus.FAILED, "Destination not writable"));

            ExportJobMetadata found = store.findExportJob(job.exportId()).orElseThrow();
            assertSame(ExportJobStatus.FAILED, found.status());
            assertEquals("Destination not writable", found.message());
        }
    }

    @Test
    void cancelledExportIsTerminalButRetainsBytesAlreadyWritten() {
        ExportJobMetadata job = ExportJobMetadata.queued(PROJECT, "SIGNALS_CSV", "all", "C:/exports/all.csv");
        try (SqliteProjectMetadataStore store = open()) {
            store.upsertExportJob(job.withProgress(512L).withStatus(ExportJobStatus.CANCELLED, "Cancelled"));

            ExportJobMetadata found = store.findExportJob(job.exportId()).orElseThrow();
            assertSame(ExportJobStatus.CANCELLED, found.status());
            assertEquals(512L, found.bytesWritten());
            assertNotNull(found.finishedAt());
        }
    }

    @Test
    void historyIsNewestFirstAndBounded() {
        try (SqliteProjectMetadataStore store = open()) {
            for (int i = 0; i < 5; i++) {
                store.upsertExportJob(new ExportJobMetadata(
                        "e" + i, PROJECT, "SIGNALS_CSV", "scope", ExportJobStatus.COMPLETED,
                        "dest" + i, 10L, "", Instant.parse("2026-09-0" + (i + 1) + "T10:00:00Z"), null));
            }
            List<ExportJobMetadata> recent = store.listExportJobs(PROJECT, 3);
            assertEquals(3, recent.size());
            assertEquals(List.of("e4", "e3", "e2"),
                    recent.stream().map(ExportJobMetadata::exportId).toList());
        }
    }

    @Test
    void queuedExportHasNoFinishTime() {
        ExportJobMetadata job = ExportJobMetadata.queued(PROJECT, "SIGNALS_CSV", "scope", "dest");
        assertNull(job.finishedAt());
        assertSame(ExportJobStatus.QUEUED, job.status());
    }
}
