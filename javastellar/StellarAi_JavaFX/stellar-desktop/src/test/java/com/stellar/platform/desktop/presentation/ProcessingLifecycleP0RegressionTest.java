package com.stellar.platform.desktop.presentation;

import com.stellar.platform.desktop.project.ChannelMappingStatus;
import com.stellar.platform.desktop.project.ChannelRecommendationKind;
import com.stellar.platform.desktop.project.FileKind;
import com.stellar.platform.desktop.project.SourceChannelKey;
import com.stellar.platform.desktop.project.SourceChannelMappingEntry;
import com.stellar.platform.desktop.project.WorkspaceFile;
import com.stellar.platform.desktop.session.DesktopSession;
import com.stellar.platform.desktop.support.DesktopFixtures;
import com.stellar.platform.domain.MeasurementSourceFormat;
import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.inventory.CanChannelInventory;
import com.stellar.platform.domain.inventory.CanInventoryStatus;
import com.stellar.platform.domain.inventory.CanMessageInventory;
import com.stellar.platform.domain.inventory.CanMessageKey;
import com.stellar.platform.domain.inventory.CanSourceInventory;
import com.stellar.platform.domain.processing.plan.IndependentJobResult;
import com.stellar.platform.domain.processing.plan.JobExecutionStatus;
import com.stellar.platform.processing.ParserRegistry;
import com.stellar.platform.processing.catalog.SignalSeriesStore;
import com.stellar.platform.processing.graph.GraphSignalSessionStore;
import com.stellar.platform.processing.graph.LosslessGraphSignalStore;
import com.stellar.platform.storage.ArtifactStorageRefs;
import com.stellar.platform.storage.analysis.AnalysisStorageRoots;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P0-RC-06: Explore Signals must not reprocess; second runs must leave QUEUED;
 * cancel must terminate pending/running jobs; cancelled/failed replacement must keep G1.
 */
class ProcessingLifecycleP0RegressionTest {

    @TempDir Path tempDir;
    private final List<DesktopAppService> services = new ArrayList<>();
    private String previousHome;

    @AfterEach
    void tearDown() {
        for (DesktopAppService service : services) {
            service.shutdown();
        }
        services.clear();
        AnalysisStorageRoots.clear();
        if (previousHome == null) {
            System.clearProperty("stellarai.home");
        } else {
            System.setProperty("stellarai.home", previousHome);
        }
    }

    @Test
    void exploreSignalsDoesNotReprocessOrClearValidArtifacts() throws Exception {
        DesktopAppService service = newNamedService();
        prepareTwoRealSources(service);
        waitForIdle(service);
        service.executeReviewedPlan(Runnable::run, () -> { });
        waitForIdle(service);

        assertEquals(ProcessingPageMode.EXECUTION_RESULT, service.processingSelectionModel().mode());
        assertTrue(service.processingResultRegistry().all().stream().allMatch(IndependentJobResult::successful));
        int stores = service.signalStoreCreateCountForTest.get();
        int jobs = service.processingResultRegistry().all().size();
        int catalog = service.signalsModel().catalog().entries().size();
        assertTrue(catalog > 0, "first run must publish a catalog");
        double fingerprint = firstSampleFingerprint(service);
        List<Path> roots = committedRoots(service);
        assertFalse(roots.isEmpty());
        for (Path root : roots) {
            assertTrue(LosslessGraphSignalStore.hasValidIndex(root), root.toString());
        }

        assertTrue(service.openCompletedSignalsForExploration());
        service.hydrateProcessingWorkspace();
        assertTrue(service.openCompletedSignalsForExploration());
        service.hydrateProcessingWorkspace();
        assertTrue(service.openCompletedSignalsForExploration());

        assertEquals(stores, service.signalStoreCreateCountForTest.get(),
                "Explore Signals must not create a replacement staging store");
        assertEquals(jobs, service.processingResultRegistry().all().size());
        assertTrue(service.signalsModel().catalog().entries().size() >= catalog);
        assertEquals(fingerprint, firstSampleFingerprint(service), 1e-9);
        assertFalse(service.isPlanExecutionActive());
        assertEquals(1, service.persistedProcessingRuns().stream()
                .map(run -> run.runId())
                .distinct()
                .count(),
                "Explore Signals must not create a second processing run");
        assertTrue(service.latestPersistedProcessingRun()
                .map(run -> ProcessingRunStatuses.isCompleted(run.status()))
                .orElse(false));
        for (Path root : roots) {
            assertTrue(LosslessGraphSignalStore.hasValidIndex(root));
            assertTrue(LosslessGraphSignalStore.segmentsReadable(root));
        }
    }

    @Test
    void leaveAndReturnSignalsDoesNotInvalidateOrReparse() throws Exception {
        DesktopAppService service = newNamedService();
        prepareTwoRealSources(service);
        service.executeReviewedPlan(Runnable::run, () -> { });
        waitForIdle(service);
        int stores = service.signalStoreCreateCountForTest.get();
        int catalog = service.signalsModel().catalog().entries().size();

        assertTrue(service.openCompletedSignalsForExploration());
        service.hydrateProcessingWorkspace();
        assertTrue(service.openCompletedSignalsForExploration());
        service.hydrateProcessingWorkspace();
        assertTrue(service.openCompletedSignalsForExploration());

        assertEquals(stores, service.signalStoreCreateCountForTest.get());
        assertEquals(catalog, service.signalsModel().catalog().entries().size());
        assertFalse(service.isPlanExecutionActive());
        assertTrue(service.processingResultRegistry().all().stream().allMatch(IndependentJobResult::successful));
    }

    @Test
    void secondExplicitRunLeavesQueuedAndReachesTerminal() throws Exception {
        DesktopAppService service = newNamedService();
        prepareTwoRealSources(service);
        service.executeReviewedPlan(Runnable::run, () -> { });
        waitForIdle(service);
        assertEquals(ProcessingPageMode.EXECUTION_RESULT, service.processingSelectionModel().mode());
        int firstStores = service.signalStoreCreateCountForTest.get();

        service.forceReprocessReviewedPlan(Runnable::run, () -> { });
        waitForIdle(service);

        assertFalse(service.isPlanExecutionActive());
        assertEquals(ProcessingPageMode.EXECUTION_RESULT, service.processingSelectionModel().mode());
        assertEquals(0, service.processingSelectionModel().pendingJobCount());
        assertTrue(service.processingSelectionModel().executionJobs().stream()
                .allMatch(row -> row.status().isTerminal()));
        assertTrue(service.signalStoreCreateCountForTest.get() > firstStores,
                "an explicit reprocess must create a new staging generation");
        assertTrue(service.openCompletedSignalsForExploration());
    }

    @Test
    void cancelWhilePendingMovesBothJobsToCancelled() throws Exception {
        DesktopAppService service = newNamedService();
        prepareTwoRealSources(service);
        Held held = holdBackground(service);

        service.executeReviewedPlan(Runnable::run, () -> { });
        assertTrue(held.entered.await(20, TimeUnit.SECONDS));
        assertEquals(2, service.processingSelectionModel().pendingJobCount()
                + service.processingSelectionModel().activeJobCount());
        assertEquals(ProcessingPageMode.EXECUTING, service.processingSelectionModel().mode());

        service.cancelProcessing();
        service.cancelProcessing();
        assertEquals(ProcessingPageMode.EXECUTION_RESULT, service.processingSelectionModel().mode());
        assertEquals(0, service.processingSelectionModel().pendingJobCount());
        assertEquals(0, service.processingSelectionModel().activeJobCount());
        assertTrue(service.processingSelectionModel().executionJobs().stream()
                .allMatch(row -> row.status() == JobExecutionStatus.CANCELLED));

        held.release();
        waitForIdle(service);
        assertFalse(service.isPlanExecutionActive());
        assertEquals(0, service.processingSelectionModel().pendingJobCount());
        assertTrue(service.lastProcessingRunOutcome().interrupted()
                || service.latestPersistedProcessingRun()
                .map(run -> ProcessingRunStatuses.CANCELLED.equals(run.status()))
                .orElse(false));
        assertEquals(0, service.signalStoreCreateCountForTest.get(),
                "pending cancel must not start a decode write");
    }

    @Test
    void cancelWhileFirstJobStartingCancelsRemainderAndDoesNotRetry() throws Exception {
        DesktopAppService service = newNamedService();
        prepareTwoRealSources(service);
        AtomicInteger started = new AtomicInteger();
        Held held = holdFirstJobStart(service, started);

        service.executeReviewedPlan(Runnable::run, () -> { });
        assertTrue(held.entered.await(20, TimeUnit.SECONDS));
        assertEquals(1, started.get());
        assertEquals(JobExecutionStatus.RUNNING,
                service.processingSelectionModel().executionJobs().getFirst().status());
        assertEquals(JobExecutionStatus.QUEUED,
                service.processingSelectionModel().executionJobs().get(1).status());

        service.cancelProcessing();
        held.release();
        waitForIdle(service);

        assertTrue(service.signalStoreCreateCountForTest.get() <= 1,
                "queued remainder must never open a replacement store");
        assertTrue(started.get() >= 1);
        assertEquals(0, service.processingSelectionModel().pendingJobCount());
        assertEquals(0, service.processingSelectionModel().activeJobCount());
        assertTrue(service.processingSelectionModel().executionJobs().stream()
                .allMatch(row -> row.status() == JobExecutionStatus.CANCELLED));
        assertFalse(service.isPlanExecutionActive());
    }

    @Test
    void cancelledReprocessPreservesPreviousValidGeneration() throws Exception {
        DesktopAppService service = newNamedService();
        prepareTwoRealSources(service);
        service.executeReviewedPlan(Runnable::run, () -> { });
        waitForIdle(service);
        double fingerprint = firstSampleFingerprint(service);
        List<Path> roots = committedRoots(service);
        assertFalse(roots.isEmpty());
        for (Path root : roots) {
            assertTrue(LosslessGraphSignalStore.hasValidIndex(root));
        }

        Held held = holdBackground(service);
        service.forceReprocessReviewedPlan(Runnable::run, () -> { });
        assertTrue(held.entered.await(20, TimeUnit.SECONDS));
        service.cancelProcessing();
        held.release();
        waitForIdle(service);

        for (Path root : roots) {
            assertTrue(LosslessGraphSignalStore.hasValidIndex(root), "G1 must remain after cancelled G2");
            assertTrue(LosslessGraphSignalStore.segmentsReadable(root));
            Path staging = root.resolveSibling(root.getFileName().toString() + ".staging");
            assertTrue(!Files.exists(staging) || !LosslessGraphSignalStore.hasValidIndex(staging));
        }
        assertTrue(service.openCompletedSignalsForExploration());
        assertEquals(fingerprint, firstSampleFingerprint(service), 1e-9);
    }

    @Test
    void failedReprocessPreservesPreviousValidGeneration() throws Exception {
        DesktopAppService service = newNamedService();
        WorkspaceFile sourceA = prepareOneRealSource(service, "g1-a.mf4");
        service.executeReviewedPlan(Runnable::run, () -> { });
        waitForIdle(service);
        double fingerprint = firstSampleFingerprint(service);
        Path root = committedRoots(service).getFirst();
        assertTrue(LosslessGraphSignalStore.hasValidIndex(root));

        WorkspaceFile dbc = service.workspace().definitionFiles().getFirst();
        Files.writeString(dbc.path(), "this is not a valid dbc {{{");
        service.forceReprocessReviewedPlan(Runnable::run, () -> { });
        waitForIdle(service);

        assertTrue(LosslessGraphSignalStore.hasValidIndex(root), "failed replacement must keep G1");
        assertTrue(LosslessGraphSignalStore.segmentsReadable(root));
        GraphSignalSessionStore opened = GraphSignalSessionStore.openExisting(root);
        assertEquals(fingerprint, firstSample(opened), 1e-9);
        assertTrue(service.openCompletedSignalsForExploration()
                || service.processingResultRegistry().get(
                service.processingSelectionModel().executionPlanSnapshot().jobs().getFirst().key())
                .filter(IndependentJobResult::successful).isPresent()
                || Files.isDirectory(root));
        assertNotEquals(sourceA.id(), "");
    }

    @Test
    void reopenAfterCancelKeepsValidArtifactsAndDoesNotAutoStart() throws Exception {
        DesktopAppService service = newNamedService();
        prepareTwoRealSources(service);
        service.executeReviewedPlan(Runnable::run, () -> { });
        waitForIdle(service);
        double fingerprint = firstSampleFingerprint(service);
        String projectId = service.currentProjectId();
        assertTrue(service.saveProject().saved());

        Held held = holdBackground(service);
        service.forceReprocessReviewedPlan(Runnable::run, () -> { });
        assertTrue(held.entered.await(20, TimeUnit.SECONDS));
        service.cancelProcessing();
        held.release();
        waitForIdle(service);
        assertTrue(service.saveProject().saved());
        service.shutdown();
        services.remove(service);

        DesktopAppService reopened = new DesktopAppService(new DesktopSession(ParserRegistry.load()));
        services.add(reopened);
        assertTrue(reopened.openSavedProject(projectId).opened());
        assertFalse(reopened.isPlanExecutionActive());
        assertTrue(reopened.processingSelectionModel().executionJobs().stream()
                .noneMatch(row -> row.status() == JobExecutionStatus.QUEUED
                        || row.status() == JobExecutionStatus.RUNNING));
        assertTrue(reopened.openCompletedSignalsForExploration());
        assertEquals(fingerprint, firstSampleFingerprint(reopened), 1e-9);
        assertEquals(0, reopened.signalStoreCreateCountForTest.get());
    }

    private DesktopAppService newNamedService() {
        previousHome = System.getProperty("stellarai.home");
        System.setProperty("stellarai.home", tempDir.resolve("home").toAbsolutePath().toString());
        DesktopAppService service = new DesktopAppService(new DesktopSession(ParserRegistry.load()));
        services.add(service);
        service.newProject("P0-RC-06");
        return service;
    }

    private void prepareTwoRealSources(DesktopAppService service) throws Exception {
        WorkspaceFile dbc = addDefinition(service, "desktop_engine.dbc");
        addRealSource(service, "a.mf4", dbc);
        addRealSource(service, "b.mf4", dbc);
        service.setDecisionWorkspaceMode(DecisionWorkspaceMode.DETAILED_MAPPING);
        service.enterProcessingWorkspaceFromDecision();
        assertEquals(2, service.processingSelectionModel().reviewedPlan().jobs().size());
    }

    private WorkspaceFile prepareOneRealSource(DesktopAppService service, String name) throws Exception {
        WorkspaceFile dbc = addDefinition(service, "one-" + name + ".dbc");
        WorkspaceFile source = addRealSource(service, name, dbc);
        service.setDecisionWorkspaceMode(DecisionWorkspaceMode.DETAILED_MAPPING);
        service.enterProcessingWorkspaceFromDecision();
        return source;
    }

    private WorkspaceFile addDefinition(DesktopAppService service, String name) throws Exception {
        Path dbcPath = DesktopFixtures.copyResource("dbc/desktop_engine.dbc", tempDir.resolve(name));
        WorkspaceFile dbc = new WorkspaceFile(dbcPath, FileKind.DEFINITION_DBC, Files.size(dbcPath));
        service.workspace().addFile(dbc);
        return dbc;
    }

    private WorkspaceFile addRealSource(DesktopAppService service, String name, WorkspaceFile dbc) throws Exception {
        Path mf4 = DesktopFixtures.writeFinalizedMf4(tempDir.resolve(name));
        WorkspaceFile source = new WorkspaceFile(mf4, FileKind.MEASUREMENT_MF4, Files.size(mf4));
        service.workspace().addFile(source);
        service.sourceInventoryModel().put(source.id(), new CanSourceInventory(
                mf4, name, MeasurementSourceFormat.MF4, CanInventoryStatus.INVENTORY_READY,
                1, 1, 1, true,
                List.of(new CanChannelInventory(
                        0, "Channel 0", 1, 1, 1, 0, 0,
                        List.of(new CanMessageInventory(new CanMessageKey(0, 0x100L, false), 1, 8, 8, false)))),
                List.of()));
        service.definitionMatchingModel().putMapping(new SourceChannelMappingEntry(
                SourceChannelKey.of(source.projectSourceId(), 0),
                ChannelMappingStatus.CONFIRMED,
                ChannelRecommendationKind.CLEAR_BEST_MATCH,
                dbc.definitionSourceId(), dbc.fileName(),
                dbc.definitionSourceId(), dbc.fileName(), 1, 1, 1.0));
        return source;
    }

    private static List<Path> committedRoots(DesktopAppService service) {
        List<Path> roots = new ArrayList<>();
        String projectId = service.currentProjectId();
        for (IndependentJobResult result : service.processingResultRegistry().all()) {
            Path root = ArtifactStorageRefs.jobRoot(
                    projectId, result.projectSourceId(), result.channelId(), result.definitionSourceId().orElse(""));
            if (Files.isDirectory(root)) {
                roots.add(root);
            }
        }
        return roots;
    }

    private static double firstSampleFingerprint(DesktopAppService service) {
        SignalSeriesStore store = service.signalsModel().catalogStore();
        if (store == null) {
            IndependentJobResult result = service.processingResultRegistry().all().stream()
                    .filter(IndependentJobResult::successful)
                    .findFirst()
                    .orElseThrow();
            store = service.resolveExplorationStore(result);
        }
        assertNotNull(store);
        return firstSample(store);
    }

    private static double firstSample(SignalSeriesStore store) {
        SignalCatalogEntry entry = store.snapshot().entries().getFirst();
        return store.querySamples(entry.key(), 0, 1).getFirst().physicalValue();
    }

    private static void waitForIdle(DesktopAppService service) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(40);
        while (service.isPlanExecutionActive() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertFalse(service.isPlanExecutionActive(), "processing run did not reach a terminal state");
    }

    private static Held holdBackground(DesktopAppService service) {
        Held held = new Held();
        service.planExecutionThreadProbeForTest = () -> {
            held.entered.countDown();
            await(held.release);
        };
        return held;
    }

    private static Held holdFirstJobStart(DesktopAppService service, AtomicInteger started) {
        Held held = new Held();
        service.planJobStartingProbeForTest = () -> {
            started.incrementAndGet();
            held.entered.countDown();
            await(held.release);
        };
        return held;
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(20, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class Held {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        private void release() {
            release.countDown();
        }
    }
}
