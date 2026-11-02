package com.stellar.platform.desktop.presentation;

import com.stellar.platform.desktop.session.DesktopSession;
import com.stellar.platform.processing.ParserRegistry;
import com.stellar.platform.storage.metadata.ProcessingRunMetadata;
import com.stellar.platform.storage.metadata.ProjectMetadata;
import com.stellar.platform.storage.metadata.SavedProjectSnapshot;
import com.stellar.platform.storage.metadata.SqliteProjectMetadataStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DEF-002: abandoned or replaced processing_runs must not remain RUNNING.
 */
class ProcessingRunFinalizationTest {

    private static final Instant T0 = Instant.parse("2026-10-12T07:30:00Z");

    @TempDir
    Path temp;

    @Test
    void cancelAndSupersedeLeaveNoOrphanRunningRows() throws Exception {
        Path db = temp.resolve("runs.db");
        try (SqliteProjectMetadataStore store = new SqliteProjectMetadataStore(db)) {
            DesktopAppService service = new DesktopAppService(new DesktopSession(ParserRegistry.load()), store);
            String projectId = service.currentProjectId();
            store.upsertProject(new ProjectMetadata(projectId, "runs", T0, T0, "PROCESSING", null));

            ProcessingRunMetadata orphan = new ProcessingRunMetadata(
                    "run-orphan", projectId, ProcessingRunStatuses.RUNNING, true, 16, T0, null, "");
            ProcessingRunMetadata current = new ProcessingRunMetadata(
                    "run-current", projectId, ProcessingRunStatuses.RUNNING, true, 8, T0.plusSeconds(10), null, "");
            store.upsertProcessingRun(orphan);
            store.upsertProcessingRun(current);
            service.rememberPersistedRunForTest(current);
            service.rememberPersistedRunForTest(orphan);

            service.finalizeOrphanRunningRuns("run-current", "Superseded by a newer processing run");

            List<ProcessingRunMetadata> runs = store.loadProject(projectId)
                    .map(SavedProjectSnapshot::processingRuns)
                    .orElse(List.of());
            ProcessingRunMetadata orphanAfter = runs.stream()
                    .filter(run -> "run-orphan".equals(run.runId()))
                    .findFirst()
                    .orElseThrow();
            ProcessingRunMetadata currentAfter = runs.stream()
                    .filter(run -> "run-current".equals(run.runId()))
                    .findFirst()
                    .orElseThrow();
            assertEquals(ProcessingRunStatuses.CANCELLED, orphanAfter.status());
            assertNotNull(orphanAfter.finishedAt());
            assertEquals(ProcessingRunStatuses.RUNNING, currentAfter.status());
            assertTrue(currentAfter.finishedAt() == null);

            service.finalizeOrphanRunningRuns(null, "Cancelled");
            runs = store.loadProject(projectId).map(SavedProjectSnapshot::processingRuns).orElse(List.of());
            assertTrue(runs.stream().noneMatch(run -> ProcessingRunStatuses.isRunning(run.status())),
                    "no RUNNING row may remain after cancel/recovery");
            assertTrue(runs.stream().allMatch(run -> run.finishedAt() != null));
            assertTrue(runs.stream().noneMatch(run -> run.runId() == null || run.runId().isBlank() || "-".equals(run.runId())));
            service.shutdown();
        }
    }

    @Test
    void reopenFinalizesLeftoverRunningRun() throws Exception {
        Path db = temp.resolve("reopen-runs.db");
        try (SqliteProjectMetadataStore store = new SqliteProjectMetadataStore(db)) {
            DesktopAppService first = new DesktopAppService(new DesktopSession(ParserRegistry.load()), store);
            String projectId = first.currentProjectId();
            store.upsertProject(new ProjectMetadata(projectId, "reopen-runs", T0, T0, "PROCESSING", null));
            store.upsertProcessingRun(new ProcessingRunMetadata(
                    "run-abandoned", projectId, ProcessingRunStatuses.RUNNING, true, 16, T0, null, ""));
            first.rememberPersistedRunForTest(new ProcessingRunMetadata(
                    "run-abandoned", projectId, ProcessingRunStatuses.RUNNING, true, 16, T0, null, ""));
            first.finalizeOrphanRunningRuns(null, "Processing interrupted");
            List<ProcessingRunMetadata> runs = store.loadProject(projectId)
                    .map(SavedProjectSnapshot::processingRuns)
                    .orElse(List.of());
            first.shutdown();
            assertEquals(1, runs.size());
            assertEquals(ProcessingRunStatuses.CANCELLED, runs.getFirst().status());
            assertNotNull(runs.getFirst().finishedAt());
            assertFalse("-".equals(runs.getFirst().runId()));
        }
    }

    @Test
    void invalidRunIdIsNotPersisted() throws Exception {
        Path db = temp.resolve("blank-run.db");
        try (SqliteProjectMetadataStore store = new SqliteProjectMetadataStore(db)) {
            DesktopAppService service = new DesktopAppService(new DesktopSession(ParserRegistry.load()), store);
            String projectId = service.currentProjectId();
            store.upsertProject(new ProjectMetadata(projectId, "blank-run", T0, T0, "PROCESSING", null));
            service.persistProcessingRunForTest("-", ProcessingRunStatuses.RUNNING, T0, null);
            service.persistProcessingRunForTest("", ProcessingRunStatuses.RUNNING, T0, null);
            service.persistProcessingRunForTest(null, ProcessingRunStatuses.RUNNING, T0, null);
            service.persistProcessingRunForTest("valid-run", ProcessingRunStatuses.COMPLETED, T0, T0);
            List<ProcessingRunMetadata> runs = store.loadProject(projectId)
                    .map(SavedProjectSnapshot::processingRuns)
                    .orElse(List.of());
            assertTrue(runs.stream().noneMatch(run -> run.runId() == null || run.runId().isBlank() || "-".equals(run.runId())));
            assertTrue(runs.stream().anyMatch(run -> "valid-run".equals(run.runId())));
            service.shutdown();
        }
    }
}
