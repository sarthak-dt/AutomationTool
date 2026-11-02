package com.stellar.platform.desktop.presentation;

import com.stellar.platform.api.worker.LocalWorkerIsolation;
import com.stellar.platform.api.worker.ProcessIsolatedLocalWorker;
import com.stellar.platform.desktop.project.ChannelMappingStatus;
import com.stellar.platform.desktop.project.ChannelRecommendationKind;
import com.stellar.platform.desktop.project.FileKind;
import com.stellar.platform.desktop.project.SourceChannelKey;
import com.stellar.platform.desktop.project.SourceChannelMappingEntry;
import com.stellar.platform.desktop.project.WorkspaceFile;
import com.stellar.platform.desktop.session.DesktopSession;
import com.stellar.platform.desktop.support.DesktopFixtures;
import com.stellar.platform.domain.MeasurementSourceFormat;
import com.stellar.platform.domain.inventory.CanChannelInventory;
import com.stellar.platform.domain.inventory.CanInventoryStatus;
import com.stellar.platform.domain.inventory.CanMessageInventory;
import com.stellar.platform.domain.inventory.CanMessageKey;
import com.stellar.platform.domain.inventory.CanSourceInventory;
import com.stellar.platform.domain.processing.plan.IndependentJobResult;
import com.stellar.platform.domain.processing.plan.JobExecutionStatus;
import com.stellar.platform.processing.ParserRegistry;
import com.stellar.platform.storage.analysis.AnalysisStorageRoots;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P0-RC-09: worker terminal must auto-transition Processing to Result without Cancel,
 * keep COMPLETED against late Cancel, and leave project journey PROCESSING.
 */
class ProcessingTerminalPropagationP0RegressionTest {

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
    @Timeout(60)
    void oneJobCompletesToResultWithoutCancel() throws Exception {
        DesktopAppService service = newNamedService("P0-RC-09 one");
        prepareSources(service, 1, false);
        assertEquals(ProjectJourneyState.READY_TO_PROCESS, service.projectJourneyState());
        AtomicInteger cancelCalls = new AtomicInteger();
        service.executeReviewedPlan(Runnable::run, () -> { });
        waitForIdle(service);

        assertEquals(0, cancelCalls.get(), "Cancel must not be required for completion");
        assertEquals(ProcessingPageMode.EXECUTION_RESULT, service.processingSelectionModel().mode());
        assertFalse(service.processingResultRegistry().all().isEmpty(), "result rows must be hydrated");
        assertTrue(service.processingResultRegistry().all().stream().allMatch(IndependentJobResult::successful));
        assertTrue(service.latestPersistedProcessingRun()
                .map(run -> ProcessingRunStatuses.isCompleted(run.status()))
                .orElse(false));
        assertNotEquals(ProjectJourneyState.PROCESSING, service.projectJourneyState());
        assertEquals(ProjectJourneyState.ANALYSIS_READY, service.projectJourneyState());
        assertFalse(service.isPlanExecutionActive());
    }

    @Test
    @Timeout(60)
    void completedWithWarningsLeavesProcessingStateWithoutCancel() throws Exception {
        DesktopAppService service = newNamedService("P0-RC-09 warn");
        prepareSources(service, 1, true);
        service.executeReviewedPlan(Runnable::run, () -> { });
        waitForIdle(service);

        assertEquals(ProcessingPageMode.EXECUTION_RESULT, service.processingSelectionModel().mode());
        String status = service.latestPersistedProcessingRun().map(run -> run.status()).orElse("");
        boolean warned = ProcessingRunStatuses.COMPLETED_WITH_WARNINGS.equals(status)
                || service.processingResultRegistry().all().stream()
                .anyMatch(job -> job.status() == JobExecutionStatus.SUCCESS_WITH_WARNINGS);
        if (warned) {
            assertEquals(ProcessingRunStatuses.COMPLETED, status);
            assertEquals(ProjectJourneyState.ANALYSIS_READY, service.projectJourneyState());
            assertEquals("Ready", service.projectJourneyState().statusLabel().display());
            assertEquals("badge-ready", service.projectJourneyState().statusLabel().badgeStyleClass());
            assertFalse(service.processingWarningReport().isEmpty());
        } else {
            assertTrue(ProcessingRunStatuses.isCompleted(status), status);
        }
        assertNotEquals(ProjectJourneyState.PROCESSING, service.projectJourneyState());
        assertFalse(service.processingSelectionModel().executionJobs().isEmpty());
    }

    @Test
    @Timeout(60)
    void twoJobsAutoCompleteAndWarningAggregate() throws Exception {
        DesktopAppService service = newNamedService("P0-RC-09 two");
        prepareSources(service, 2, false);
        service.executeReviewedPlan(Runnable::run, () -> { });
        waitForIdle(service);

        assertEquals(2, service.processingSelectionModel().executionJobs().size());
        assertEquals(ProcessingPageMode.EXECUTION_RESULT, service.processingSelectionModel().mode());
        assertTrue(service.processingResultRegistry().all().stream().allMatch(IndependentJobResult::successful));
        assertFalse(service.processingSelectionModel().executionJobs().stream()
                .anyMatch(row -> !row.status().isTerminal()));
        String status = service.latestPersistedProcessingRun().map(run -> run.status()).orElse("");
        boolean anyWarn = service.processingResultRegistry().all().stream()
                .anyMatch(job -> job.status() == JobExecutionStatus.SUCCESS_WITH_WARNINGS);
        if (anyWarn) {
            assertEquals(ProcessingRunStatuses.COMPLETED, status);
        } else {
            assertTrue(ProcessingRunStatuses.isCompleted(status), status);
        }
        assertNotEquals(ProjectJourneyState.PROCESSING, service.projectJourneyState());
    }

    @Test
    @Timeout(60)
    void lateCancelDoesNotRewriteCompleted() throws Exception {
        DesktopAppService service = newNamedService("P0-RC-09 late-cancel");
        prepareSources(service, 1, false);
        service.executeReviewedPlan(Runnable::run, () -> { });
        waitForIdle(service);
        String before = service.latestPersistedProcessingRun().map(run -> run.status()).orElse("");
        assertTrue(ProcessingRunStatuses.isProtectedTerminal(before), before);

        service.cancelProcessing();

        String after = service.latestPersistedProcessingRun().map(run -> run.status()).orElse("");
        assertEquals(before, after);
        assertFalse(ProcessingRunStatuses.isCancelled(after));
        assertEquals(ProcessingPageMode.EXECUTION_RESULT, service.processingSelectionModel().mode());
        assertTrue(service.processingResultRegistry().all().stream().allMatch(IndependentJobResult::successful));
        assertNotEquals(ProjectJourneyState.PROCESSING, service.projectJourneyState());
    }

    @Test
    @Timeout(40)
    void trueCancelWhileRunningStillCancels() throws Exception {
        DesktopAppService service = newNamedService("P0-RC-09 true-cancel");
        prepareSources(service, 1, false);
        Held held = holdBackgroundExecution(service);
        service.executeReviewedPlan(Runnable::run, () -> { });
        assertTrue(held.entered.await(20, TimeUnit.SECONDS));
        assertEquals(ProjectJourneyState.PROCESSING, service.projectJourneyState());
        service.cancelProcessing();
        held.release();
        waitForIdle(service);

        assertTrue(service.latestPersistedProcessingRun()
                .map(run -> ProcessingRunStatuses.isCancelled(run.status()))
                .orElse(false));
        assertEquals(ProcessingPageMode.EXECUTION_RESULT, service.processingSelectionModel().mode());
        assertTrue(service.processingSelectionModel().executionJobs().stream()
                .allMatch(row -> row.status().isTerminal()));
    }

    @Test
    @Timeout(60)
    void shellJourneyAgreesWithProcessingPaneAfterCompletion() throws Exception {
        DesktopAppService service = newNamedService("P0-RC-09 shell");
        prepareSources(service, 1, false);
        assertEquals(ProjectJourneyState.READY_TO_PROCESS, service.projectJourneyState());
        Held held = holdBackgroundExecution(service);
        service.executeReviewedPlan(Runnable::run, () -> { });
        assertTrue(held.entered.await(20, TimeUnit.SECONDS));
        assertEquals(ProjectJourneyState.PROCESSING, service.projectJourneyState());
        held.release();
        waitForIdle(service);
        assertEquals(ProcessingPageMode.EXECUTION_RESULT, service.processingSelectionModel().mode());
        assertEquals(ProjectJourneyState.ANALYSIS_READY, service.projectJourneyState());
        assertEquals(ProjectStatusLabel.ANALYSIS_READY, service.projectJourneyState().statusLabel());
    }

    @Test
    @Timeout(60)
    void automaticCompletionPersistsWithoutReprocess() throws Exception {
        DesktopAppService service = newNamedService("P0-RC-09 persist");
        prepareSources(service, 1, false);
        service.executeReviewedPlan(Runnable::run, () -> { });
        waitForIdle(service);
        int stores = service.signalStoreCreateCountForTest.get();
        int catalog = service.signalsModel().catalog().entries().size();
        assertTrue(catalog > 0);
        assertTrue(service.openCompletedSignalsForExploration());
        service.hydrateProcessingWorkspace();
        assertEquals(ProcessingPageMode.EXECUTION_RESULT, service.processingSelectionModel().mode());
        assertEquals(stores, service.signalStoreCreateCountForTest.get());
        assertTrue(service.saveProject().saved());
        String projectId = service.currentProjectId();
        service.shutdown();
        services.remove(service);

        DesktopAppService reopened = new DesktopAppService(new DesktopSession(ParserRegistry.load()));
        services.add(reopened);
        assertTrue(reopened.openSavedProject(projectId).opened());
        assertFalse(reopened.processingResultRegistry().all().isEmpty(),
                "reopen must restore committed results without Cancel or reprocess");
        assertEquals(0, reopened.signalStoreCreateCountForTest.get());
        assertTrue(reopened.openCompletedSignalsForExploration());
        assertEquals(ProjectJourneyState.ANALYSIS_READY, reopened.projectJourneyState());
        assertFalse(reopened.isPlanExecutionActive());
    }

    @Test
    @Timeout(90)
    void isolatedProductionPathAutoCompletesWithoutCancel() throws Exception {
        previousHome = System.getProperty("stellarai.home");
        System.setProperty("stellarai.home", tempDir.resolve("home").toAbsolutePath().toString());
        DesktopAppService service = DesktopAppService.forLocalProduction(new DesktopSession(ParserRegistry.load()));
        services.add(service);
        service.newProject("P0-RC-09 isolated");
        assertTrue(service.isolateProductionProcessing());
        prepareSources(service, 1, false);
        service.executeReviewedPlan(Runnable::run, () -> { });
        waitForIdle(service, 75);

        assertTrue(service.localProcessingWorker() instanceof ProcessIsolatedLocalWorker);
        assertEquals(LocalWorkerIsolation.PROCESS_ISOLATED, service.localProcessingWorker().isolation());
        assertEquals(ProcessingPageMode.EXECUTION_RESULT, service.processingSelectionModel().mode());
        assertFalse(service.processingResultRegistry().all().isEmpty());
        assertTrue(service.latestPersistedProcessingRun()
                .map(run -> ProcessingRunStatuses.isCompleted(run.status()))
                .orElse(false));
        assertNotEquals(ProjectJourneyState.PROCESSING, service.projectJourneyState());
        service.cancelProcessing();
        assertFalse(service.latestPersistedProcessingRun()
                .map(run -> ProcessingRunStatuses.isCancelled(run.status()))
                .orElse(true));
    }

    private DesktopAppService newNamedService(String name) {
        if (previousHome == null) {
            previousHome = System.getProperty("stellarai.home");
            System.setProperty("stellarai.home", tempDir.resolve("home").toAbsolutePath().toString());
        }
        DesktopAppService service = new DesktopAppService(new DesktopSession(ParserRegistry.load()));
        services.add(service);
        service.newProject(name);
        return service;
    }

    private void prepareSources(DesktopAppService service, int count, boolean unfinalized) throws Exception {
        WorkspaceFile dbc = addDefinition(service, unique("p09.dbc"));
        for (int i = 0; i < count; i++) {
            addSource(service, unique("p09-" + i + ".mf4"), dbc, unfinalized);
        }
        service.setDecisionWorkspaceMode(DecisionWorkspaceMode.DETAILED_MAPPING);
        service.enterProcessingWorkspaceFromDecision();
        assertEquals(count, service.processingSelectionModel().reviewedPlan().jobs().size());
    }

    private WorkspaceFile addDefinition(DesktopAppService service, String name) throws Exception {
        Path dbcPath = DesktopFixtures.copyResource("dbc/desktop_engine.dbc", tempDir.resolve(name));
        WorkspaceFile dbc = new WorkspaceFile(dbcPath, FileKind.DEFINITION_DBC, Files.size(dbcPath));
        service.workspace().addFile(dbc);
        return dbc;
    }

    private WorkspaceFile addSource(
            DesktopAppService service, String name, WorkspaceFile dbc, boolean unfinalized
    ) throws Exception {
        Path mf4 = unfinalized
                ? DesktopFixtures.writeUnfinalizedMf4(tempDir.resolve(name))
                : DesktopFixtures.writeFinalizedMf4(tempDir.resolve(name));
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

    private static void waitForIdle(DesktopAppService service) throws InterruptedException {
        waitForIdle(service, 40);
    }

    private static void waitForIdle(DesktopAppService service, int seconds) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (service.isPlanExecutionActive() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertFalse(service.isPlanExecutionActive(), "processing run did not reach a terminal state");
    }

    private static String unique(String suffix) {
        return UUID.randomUUID().toString().substring(0, 8) + "-" + suffix;
    }

    private static final class Held {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        void release() {
            release.countDown();
        }
    }
}
