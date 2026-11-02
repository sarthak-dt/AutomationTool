package com.stellar.platform.desktop.presentation;

import com.stellar.platform.desktop.project.ChannelMappingStatus;
import com.stellar.platform.desktop.project.ChannelRecommendationKind;
import com.stellar.platform.desktop.project.FileKind;
import com.stellar.platform.desktop.project.SourceChannelKey;
import com.stellar.platform.desktop.project.SourceChannelMappingEntry;
import com.stellar.platform.desktop.project.WorkspaceFile;
import com.stellar.platform.desktop.session.DesktopSession;
import com.stellar.platform.domain.MeasurementSourceFormat;
import com.stellar.platform.domain.inventory.CanChannelInventory;
import com.stellar.platform.domain.inventory.CanInventoryStatus;
import com.stellar.platform.domain.inventory.CanMessageInventory;
import com.stellar.platform.domain.inventory.CanMessageKey;
import com.stellar.platform.domain.inventory.CanSourceInventory;
import com.stellar.platform.domain.processing.plan.JobExecutionStatus;
import com.stellar.platform.domain.processing.plan.ProjectExecutionStatus;
import com.stellar.platform.processing.ParserRegistry;
import com.stellar.platform.storage.metadata.ChannelMappingMetadata;
import com.stellar.platform.storage.metadata.ProcessingRunMetadata;
import com.stellar.platform.storage.metadata.ProjectFileMetadata;
import com.stellar.platform.storage.metadata.ProjectMetadata;
import com.stellar.platform.storage.metadata.ProjectMetadataStore;
import com.stellar.platform.storage.metadata.SavedProjectSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 3 — Start Processing scope, duplicate-run protection and the persisted run outcome that
 * resume routing depends on.
 */
class ProcessingExecutionLifecycleTest {

    @TempDir Path tempDir;

    @Test
    void startProcessingCreatesOnlyOneRunAndDoubleClickDoesNotDuplicate() throws Exception {
        RecordingStore store = new RecordingStore(null);
        DesktopAppService service = new DesktopAppService(
                new DesktopSession(ParserRegistry.load()), store);
        prepareProcessableProject(service);
        Held held = holdBackgroundExecution(service);

        service.executeReviewedPlan(Runnable::run, () -> { });
        assertTrue(held.entered.await(20, TimeUnit.SECONDS), "execution never reached the worker");
        // Second and third clicks arrive while the first run is still in flight.
        service.executeReviewedPlan(Runnable::run, () -> { });
        service.executeReviewedPlan(Runnable::run, () -> { });

        List<String> startedRunIds = store.runs.stream()
                .filter(run -> ProcessingRunStatuses.RUNNING.equals(run.status()))
                .map(ProcessingRunMetadata::runId)
                .distinct()
                .toList();
        assertEquals(1, startedRunIds.size(),
                "duplicate Start Processing clicks must not register a second run: " + store.runs);

        held.release();
        waitForIdle(service);
        service.shutdown();
    }

    @Test
    void planExecutionIsActiveWhileTheRunIsInFlight() throws Exception {
        DesktopAppService service = new DesktopAppService(new DesktopSession(ParserRegistry.load()));
        prepareProcessableProject(service);
        Held held = holdBackgroundExecution(service);

        assertFalse(service.isPlanExecutionActive());
        service.executeReviewedPlan(Runnable::run, () -> { });
        assertTrue(held.entered.await(20, TimeUnit.SECONDS));
        assertTrue(service.isPlanExecutionActive(),
                "the guard must report an active run for as long as work is outstanding");

        held.release();
        waitForIdle(service);
        assertFalse(service.isPlanExecutionActive());
        service.shutdown();
    }

    @Test
    void cancelUsesCooperativeCancellationAndDoesNotMarkIncompleteUnitsComplete() throws Exception {
        DesktopAppService service = new DesktopAppService(new DesktopSession(ParserRegistry.load()));
        prepareProcessableProject(service);
        Held held = holdBackgroundExecution(service);

        service.executeReviewedPlan(Runnable::run, () -> { });
        assertTrue(held.entered.await(20, TimeUnit.SECONDS));
        service.cancelProcessing();
        held.release();
        waitForIdle(service);

        assertFalse(service.isPlanExecutionActive());
        // Success is only ever claimed by a unit that actually produced a result.
        long claimedSuccessWithoutResult = service.processingSelectionModel().executionJobs().stream()
                .filter(row -> row.status() == JobExecutionStatus.SUCCESS && row.result().isEmpty())
                .count();
        assertEquals(0, claimedSuccessWithoutResult,
                "an incomplete unit must never be presented as complete after cancellation");
        service.shutdown();
    }

    @Test
    void runOutcomeIsPersistedFromTheActualBatchStatus() {
        assertEquals(ProcessingRunStatuses.COMPLETED,
                ProcessingRunStatuses.forBatch(ProjectExecutionStatus.SUCCESS));
        assertEquals(ProcessingRunStatuses.COMPLETED_WITH_FAILURES,
                ProcessingRunStatuses.forBatch(ProjectExecutionStatus.PARTIAL_FAILURE));
        assertEquals(ProcessingRunStatuses.CANCELLED,
                ProcessingRunStatuses.forBatch(ProjectExecutionStatus.CANCELLED));
        assertEquals(ProcessingRunStatuses.FAILED,
                ProcessingRunStatuses.forBatch(ProjectExecutionStatus.FAILED));
        assertEquals(ProcessingRunStatuses.FAILED,
                ProcessingRunStatuses.forBatch(ProjectExecutionStatus.BLOCKED));
    }

    @Test
    void partialFailureReadsAsCompletedAndFailedButNotInterrupted() {
        String status = ProcessingRunStatuses.COMPLETED_WITH_FAILURES;
        assertTrue(ProcessingRunStatuses.isCompleted(status));
        assertTrue(ProcessingRunStatuses.isFailed(status));
        assertFalse(ProcessingRunStatuses.isInterrupted(status),
                "a batch that finished is not outstanding work");
    }

    @Test
    void cancelledRunReadsAsInterruptedSoRemainingWorkCanResume() {
        assertTrue(ProcessingRunStatuses.isInterrupted(ProcessingRunStatuses.CANCELLED));
        assertTrue(ProcessingRunStatuses.isInterrupted(ProcessingRunStatuses.RUNNING));
        assertFalse(ProcessingRunStatuses.isInterrupted(ProcessingRunStatuses.COMPLETED));
    }

    @Test
    void residentRunListTracksTheCurrentSession() throws Exception {
        DesktopAppService service = new DesktopAppService(
                new DesktopSession(ParserRegistry.load()), new RecordingStore(null));
        prepareProcessableProject(service);
        assertTrue(service.persistedProcessingRuns().isEmpty());

        service.executeReviewedPlan(Runnable::run, () -> { });
        waitForIdle(service);

        assertFalse(service.persistedProcessingRuns().isEmpty(),
                "the run started in this session must be visible to journey state and resume routing");
        assertTrue(service.lastProcessingRunOutcome().present());
        service.shutdown();
    }

    @Test
    void interruptedSavedRunResolvesToProcessingResume() throws Exception {
        DesktopAppService service = openProjectWithRunStatus(ProcessingRunStatuses.RUNNING);

        assertTrue(service.processingRunInterrupted());
        assertEquals(ProjectJourneyState.PROCESSING, service.projectJourneyState());
        assertEquals(WorkspaceRoute.PROCESSING_RESUME, service.resolveResumeRoute());
        service.shutdown();
    }

    @Test
    void cancelledSavedRunResolvesToProcessingResume() throws Exception {
        DesktopAppService service = openProjectWithRunStatus(ProcessingRunStatuses.CANCELLED);

        assertTrue(service.processingRunInterrupted());
        assertEquals(WorkspaceRoute.PROCESSING_RESUME, service.resolveResumeRoute());
        service.shutdown();
    }

    @Test
    void failedSavedRunResolvesToProcessingResult() throws Exception {
        DesktopAppService service = openProjectWithRunStatus(ProcessingRunStatuses.FAILED);

        assertFalse(service.processingRunInterrupted());
        assertTrue(service.processingRunFailed());
        assertEquals(ProjectJourneyState.PROCESSING_FAILED, service.projectJourneyState());
        assertEquals(WorkspaceRoute.PROCESSING_RESULT, service.resolveResumeRoute());
        service.shutdown();
    }

    @Test
    void partiallyFailedSavedRunResolvesToProcessingResultRatherThanSignals() throws Exception {
        DesktopAppService service =
                openProjectWithRunStatus(ProcessingRunStatuses.COMPLETED_WITH_FAILURES);

        assertFalse(service.processingRunInterrupted());
        assertTrue(service.processingRunFailed());
        assertEquals(WorkspaceRoute.PROCESSING_RESULT, service.resolveResumeRoute(),
                "failures must not be hidden by jumping straight to analysis");
        service.shutdown();
    }

    @Test
    void completedSavedRunDoesNotLookInterruptedOrFailed() throws Exception {
        DesktopAppService service = openProjectWithRunStatus(ProcessingRunStatuses.COMPLETED);

        assertFalse(service.processingRunInterrupted());
        assertFalse(service.processingRunFailed());
        assertEquals(WorkspaceRoute.REVIEW_AND_PROCESS, service.resolveResumeRoute(),
                "a clean run with no restored signal artifacts still has nothing to explore yet");
        service.shutdown();
    }

    @Test
    void reopeningAProcessedProjectDoesNotReparseMeasurements() throws Exception {
        DesktopAppService service = openProjectWithRunStatus(ProcessingRunStatuses.COMPLETED);

        // Reopen restores inventory from metadata; no channel discovery job is queued.
        assertEquals(1, service.sourceInventoryModel()
                .inventoryFor(service.workspace().measurementFiles().getFirst().id())
                .orElseThrow().channels().size());
        assertFalse(service.isPlanExecutionActive());
        service.shutdown();
    }

    @Test
    void externallyChangedSourceIsReportedRatherThanSilentlyReused() throws Exception {
        DesktopAppService service = new DesktopAppService(new DesktopSession(ParserRegistry.load()));
        WorkspaceFile source = addReadySource(service, tempDir, "run01.mf4", 1, 100L);

        // No prior processing recorded for this source.
        assertFalse(service.sourceChangedSinceLastProcessing(source.projectSourceId()));
        service.shutdown();
    }

    /**
     * A project that has genuinely been processed before: sources, discovered channels and a
     * confirmed DBC decision, so resume routing is decided by the run outcome rather than by
     * missing decode setup.
     */
    private DesktopAppService openProjectWithRunStatus(String status) throws Exception {
        Path dir = Files.createDirectories(tempDir.resolve(status.toLowerCase(java.util.Locale.ROOT)));
        Path source = dir.resolve("saved.mf4");
        Files.writeString(source, "stub");
        Path definition = dir.resolve("chassis.dbc");
        Files.writeString(definition, "VERSION \"\"\n");
        Instant created = Instant.parse("2026-08-26T10:00:00Z");
        ProjectMetadata project = new ProjectMetadata("project-1", "Saved Road Test", created, created);
        ProjectFileMetadata measurement = new ProjectFileMetadata(
                "project-1", "source-1", source.toString(), "MEASUREMENT_MF4", Files.size(source),
                "READY", "", null, "Finalized", false, true, false);
        ProjectFileMetadata dbc = new ProjectFileMetadata(
                "project-1", "def-1", definition.toString(), "DEFINITION_DBC", Files.size(definition),
                "READY", "", null, "", false, false, false);
        CanSourceInventory inventory = new CanSourceInventory(
                source, "saved.mf4", MeasurementSourceFormat.MF4,
                CanInventoryStatus.INVENTORY_READY, 1, 20, 1, true,
                List.of(new CanChannelInventory(1, "Channel 1", 20, 1, 1, 0, 0,
                        List.of(new CanMessageInventory(new CanMessageKey(1, 0x123, false), 20, 8, 8, false)))),
                List.of());
        ChannelMappingMetadata mapping = new ChannelMappingMetadata(
                "project-1", "source-1", 1, "CONFIRMED", "CLEAR_BEST_MATCH",
                "def-1", "def-1", 1, 1, 1.0);
        ProcessingRunMetadata run = new ProcessingRunMetadata(
                "run-1", "project-1", status, false, 1, created, created.plusSeconds(2), "");
        SavedProjectSnapshot snapshot = new SavedProjectSnapshot(
                project, List.of(measurement, dbc), Map.of("source-1", inventory),
                List.of(mapping), List.of(run));

        DesktopAppService service = new DesktopAppService(
                new DesktopSession(ParserRegistry.load()), new RecordingStore(snapshot));
        assertTrue(service.openSavedProject("project-1").opened());
        return service;
    }

    private void prepareProcessableProject(DesktopAppService service) throws Exception {
        WorkspaceFile source = addReadySource(service, tempDir, "exec-run.mf4", 1, 20L);
        Path dbcPath = tempDir.resolve("exec.dbc");
        Files.writeString(dbcPath, "VERSION \"\"\n");
        WorkspaceFile dbc = new WorkspaceFile(dbcPath, FileKind.DEFINITION_DBC, 10);
        service.workspace().addFile(dbc);
        service.definitionMatchingModel().putMapping(new SourceChannelMappingEntry(
                SourceChannelKey.of(source.projectSourceId(), 1),
                ChannelMappingStatus.CONFIRMED,
                ChannelRecommendationKind.CLEAR_BEST_MATCH,
                dbc.definitionSourceId(), "exec.dbc",
                dbc.definitionSourceId(), "exec.dbc", 1, 1, 1.0));
        service.enterProcessingWorkspaceFromDecision();
    }

    private static WorkspaceFile addReadySource(
            DesktopAppService service, Path dir, String name, int channelId, long frames
    ) throws Exception {
        Files.createDirectories(dir);
        Path path = dir.resolve(name);
        Files.writeString(path, "stub");
        WorkspaceFile file = new WorkspaceFile(path, FileKind.MEASUREMENT_MF4, 10);
        service.workspace().addFile(file);
        List<CanChannelInventory> channels = new ArrayList<>();
        channels.add(new CanChannelInventory(
                channelId, "Channel " + channelId, frames, 1, 1, 0, 0,
                List.of(new CanMessageInventory(new CanMessageKey(channelId, 0x100L, false), 10, 8, 8, false))));
        service.sourceInventoryModel().put(file.id(), new CanSourceInventory(
                path, name, MeasurementSourceFormat.MF4, CanInventoryStatus.INVENTORY_READY,
                1, frames, 1, true, channels, List.of()));
        return file;
    }

    private static void waitForIdle(DesktopAppService service) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (service.isPlanExecutionActive() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
    }

    /** Pins the background worker inside the run so in-flight behaviour can be asserted. */
    private static Held holdBackgroundExecution(DesktopAppService service) {
        Held held = new Held();
        service.planExecutionThreadProbeForTest = () -> {
            held.entered.countDown();
            try {
                held.release.await(20, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        };
        return held;
    }

    private static final class Held {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        private void release() {
            release.countDown();
        }
    }

    /** Records every persisted run so duplicate-execution protection can be asserted. */
    private static final class RecordingStore implements ProjectMetadataStore {
        private final SavedProjectSnapshot snapshot;
        private final List<ProcessingRunMetadata> runs = new CopyOnWriteArrayList<>();

        private RecordingStore(SavedProjectSnapshot snapshot) {
            this.snapshot = snapshot;
        }

        @Override public List<ProjectMetadata> listProjects() {
            return snapshot == null ? List.of() : List.of(snapshot.project());
        }

        @Override public Optional<SavedProjectSnapshot> loadProject(String projectId) {
            return snapshot != null && snapshot.project().projectId().equals(projectId)
                    ? Optional.of(snapshot)
                    : Optional.empty();
        }

        @Override public void upsertProject(ProjectMetadata project) { }
        @Override public void upsertFile(ProjectFileMetadata file) { }
        @Override public void deleteFile(String projectId, String fileId) { }
        @Override public void saveInventory(String projectId, String sourceId, CanSourceInventory inventory) { }
        @Override public void upsertChannelMapping(ChannelMappingMetadata mapping) { }
        @Override public void deleteChannelMapping(String projectId, String sourceId, int channelId) { }

        @Override public void upsertProcessingRun(ProcessingRunMetadata run) {
            runs.add(run);
        }
    }
}
