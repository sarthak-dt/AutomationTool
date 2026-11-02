package com.stellar.platform.desktop.presentation;

import com.stellar.platform.api.support.ProcessingPlanDtoMapper;
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
import com.stellar.platform.domain.processing.plan.MappingLifecycle;
import com.stellar.platform.domain.processing.plan.ProcessingJob;
import com.stellar.platform.domain.processing.plan.ProcessingPlanReadiness;
import com.stellar.platform.domain.processing.plan.ProjectProcessingPlan;
import com.stellar.platform.processing.ParserRegistry;
import com.stellar.platform.processing.execution.ProjectPlanExecutor;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Production Start Processing path for Auto Decode + NO_MATCH + no DBC.
 * Exercises Review → executeReviewedPlan → isolated worker reconstruction → executor,
 * not only {@link com.stellar.platform.processing.plan.ProcessingPlanBuilder}
 * or {@link ProjectPlanExecutor} directly.
 */
class AutoDecodeNoDbcExecutionP0RegressionTest {

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
    @Timeout(90)
    void untitledWorkspaceAutoDecodeNoDbcStartProcessingSucceeds() throws Exception {
        if (previousHome == null) {
            previousHome = System.getProperty("stellarai.home");
            System.setProperty("stellarai.home", tempDir.resolve("home").toAbsolutePath().toString());
        }
        DesktopAppService service = DesktopAppService.forLocalProduction(new DesktopSession(ParserRegistry.load()));
        services.add(service);
        assertTrue(service.isolateProductionProcessing());
        assertTrue(service.activeProjectRoot().isEmpty(), "CAT starts from transient untitled work");

        addNativeOnlyMf4(service, unique("untitled-lavasa.mf4"), 1, 3, 71_352L);
        service.setDecisionWorkspaceMode(DecisionWorkspaceMode.QUICK_DECODE);
        service.enterProcessingWorkspaceFromDecision();
        assertNativeOnlyReviewPlan(service.processingSelectionModel().reviewedPlan(),
                service.workspace().measurementFiles().getFirst().id());

        service.executeReviewedPlan(Runnable::run, () -> { });
        waitForIdle(service, 75);

        assertTrue(service.activeProjectRoot().isPresent(), "isolated Start must persist a project ledger");
        assertEquals(ProcessingPageMode.EXECUTION_RESULT, service.processingSelectionModel().mode());
        assertTrue(service.processingResultRegistry().all().stream().allMatch(IndependentJobResult::successful));
        assertEquals(ProcessingRunStatuses.COMPLETED,
                service.latestPersistedProcessingRun().map(run -> run.status()).orElse(""));
    }

    @Test
    @Timeout(90)
    void autoDecodeNoDbcStartProcessingReachesSuccessWithWarnings() throws Exception {
        DesktopAppService service = newProductionService("no-dbc-exec");
        WorkspaceFile source = addNativeOnlyMf4(service, unique("lavasa.mf4"), 1, 3, 71_352L);
        service.setDecisionWorkspaceMode(DecisionWorkspaceMode.QUICK_DECODE);

        DesktopAppService.DecisionProcessingReadiness readiness = service.decisionProcessingReadiness();
        assertTrue(readiness.canProceed());
        assertEquals(1, readiness.nativeOnlyChannels());
        assertEquals(0, readiness.dbcConfiguredChannels());

        AtomicBoolean enteredExecuting = new AtomicBoolean();
        service.planJobStartingProbeForTest = () -> enteredExecuting.set(true);

        service.enterProcessingWorkspaceFromDecision();
        ProjectProcessingPlan reviewPlan = service.processingSelectionModel().reviewedPlan();
        assertNativeOnlyReviewPlan(reviewPlan, source.id());
        assertTrue(executeEnabled(reviewPlan));
        assertEquals("Ready with warnings", ProcessingReviewSummaryFactory.build(service).readinessText());

        ProjectProcessingPlan reconstructed = ProcessingPlanDtoMapper.toDomain(
                ProcessingPlanDtoMapper.toDto(reviewPlan));
        assertNativeOnlyReviewPlan(reconstructed, source.id());
        assertTrue(ProjectPlanExecutor.emptyDefinitionDatabase().messages().isEmpty());

        service.executeReviewedPlan(Runnable::run, () -> { });
        waitForIdle(service, 75);

        assertTrue(service.localProcessingWorker() instanceof ProcessIsolatedLocalWorker);
        assertEquals(LocalWorkerIsolation.PROCESS_ISOLATED, service.localProcessingWorker().isolation());
        assertTrue(enteredExecuting.get(), "mode must transition through EXECUTING / job start");
        assertEquals(ProcessingPageMode.EXECUTION_RESULT, service.processingSelectionModel().mode());
        assertFalse(service.processingSelectionModel().planRequiresReviewMessage() != null
                && service.processingSelectionModel().planRequiresReviewMessage().contains("execution failed"),
                service.processingSelectionModel().planRequiresReviewMessage());
        assertFalse(service.processingResultRegistry().all().isEmpty(), "native outputs/catalog must be committed");
        assertTrue(service.processingResultRegistry().all().stream().allMatch(IndependentJobResult::successful));
        assertTrue(service.processingResultRegistry().all().stream()
                .anyMatch(job -> job.status() == JobExecutionStatus.SUCCESS_WITH_WARNINGS));
        String status = service.latestPersistedProcessingRun().map(run -> run.status()).orElse("");
        assertEquals(ProcessingRunStatuses.COMPLETED, status, status);
        assertTrue(service.processingResultRegistry().all().stream()
                .allMatch(job -> job.definitionSourceId().isEmpty()));
        assertNotEquals(ProjectJourneyState.PROCESSING, service.projectJourneyState());
        assertFalse(service.isPlanExecutionActive());
    }

    @Test
    @Timeout(60)
    void autoDecodeMatchingDbcStillProcessesOnProductionPath() throws Exception {
        DesktopAppService service = newProductionService("with-dbc-exec");
        WorkspaceFile dbc = addDefinition(service, unique("vehicle.dbc"));
        addMappedMf4(service, unique("drive.mf4"), dbc);
        service.setDecisionWorkspaceMode(DecisionWorkspaceMode.QUICK_DECODE);
        service.enterProcessingWorkspaceFromDecision();
        assertTrue(service.processingSelectionModel().reviewedPlan().jobs().getFirst().definitionSourceId().isPresent());

        service.executeReviewedPlan(Runnable::run, () -> { });
        waitForIdle(service, 75);

        assertEquals(ProcessingPageMode.EXECUTION_RESULT, service.processingSelectionModel().mode());
        assertTrue(service.processingResultRegistry().all().stream().allMatch(IndependentJobResult::successful));
        assertTrue(service.latestPersistedProcessingRun()
                .map(run -> ProcessingRunStatuses.isCompleted(run.status()))
                .orElse(false));
    }

    @Test
    @Timeout(90)
    void autoDecodeDbcPresentButNoMatchStartsNativeOnly() throws Exception {
        DesktopAppService service = newProductionService("dbc-no-overlap");
        addDefinition(service, unique("other.dbc"));
        WorkspaceFile source = addNativeOnlyMf4(service, unique("drive.mf4"), 1, 3, 100L);
        service.setDecisionWorkspaceMode(DecisionWorkspaceMode.QUICK_DECODE);
        assertTrue(service.canProceedFromDecisionWorkspace());
        assertEquals(1, service.decisionProcessingReadiness().nativeOnlyChannels());

        service.enterProcessingWorkspaceFromDecision();
        assertNativeOnlyReviewPlan(service.processingSelectionModel().reviewedPlan(), source.id());

        service.executeReviewedPlan(Runnable::run, () -> { });
        waitForIdle(service, 75);

        assertEquals(ProcessingPageMode.EXECUTION_RESULT, service.processingSelectionModel().mode());
        assertTrue(service.processingResultRegistry().all().stream().allMatch(IndependentJobResult::successful));
        assertTrue(service.processingResultRegistry().all().stream()
                .allMatch(job -> job.definitionSourceId().isEmpty()));
        assertEquals(ProcessingRunStatuses.COMPLETED,
                service.latestPersistedProcessingRun().map(run -> run.status()).orElse(""));
    }

    @Test
    @Timeout(40)
    void failedIsolatedStartCannotLeaveGlobalFailedAndJobReady() throws Exception {
        DesktopAppService service = newProductionService("failed-exec-state");
        addNativeOnlyMf4(service, unique("orphan.mf4"), 1, 3, 100L);
        service.enterProcessingWorkspaceFromDecision();
        assertTrue(executeEnabled(service.processingSelectionModel().reviewedPlan()));

        service.isolatedExecutionFailureForTest = "test-injected isolated execution failure";
        service.executeReviewedPlan(Runnable::run, () -> { });
        waitForIdle(service, 20);

        assertTrue(service.lastProcessingRunOutcome().failed());
        assertEquals(ProcessingPageMode.PLAN_REVIEW, service.processingSelectionModel().mode());
        assertFalse(service.processingSelectionModel().executionJobs().isEmpty());
        assertTrue(service.processingSelectionModel().executionJobs().stream()
                .allMatch(row -> row.status() == JobExecutionStatus.FAILED));
        assertEquals(1, service.processingSelectionModel().failedJobCount());
        assertTrue(executeEnabled(service.processingSelectionModel().reviewedPlan()),
                "retry must follow existing Start Processing enablement");
    }

    @Test
    void customDecodeWithoutDbcPlansNativeOnlyWithoutStartingExecution() throws Exception {
        DesktopAppService service = newProductionService("custom-native");
        addNativeOnlyMf4(service, unique("custom.mf4"), 1, 3, 100L);
        service.setDecisionWorkspaceMode(DecisionWorkspaceMode.DETAILED_MAPPING);
        assertTrue(service.canProceedFromDecisionWorkspace());
        service.enterProcessingWorkspaceFromDecision();
        ProjectProcessingPlan plan = service.processingSelectionModel().reviewedPlan();
        assertEquals(1, plan.jobs().size());
        assertEquals(MappingLifecycle.NO_MATCH, plan.jobs().getFirst().mappingLifecycle());
        assertTrue(plan.jobs().getFirst().definitionSourceId().isEmpty());
        assertTrue(plan.jobs().getFirst().executable());
        assertFalse(service.isPlanExecutionActive());
    }

    @Test
    void ambiguousMappingRemainsBlockedBeforeExecution() throws Exception {
        DesktopAppService service = newProductionService("ambiguous-blocked");
        WorkspaceFile source = addReadyMf4(service, unique("amb.mf4"), 1, 3, 100L);
        service.definitionMatchingModel().putMapping(new SourceChannelMappingEntry(
                SourceChannelKey.of(source.projectSourceId(), 1),
                ChannelMappingStatus.AMBIGUOUS, ChannelRecommendationKind.AMBIGUOUS,
                null, "", null, "", 3, 0, 0.0));
        service.setDecisionWorkspaceMode(DecisionWorkspaceMode.QUICK_DECODE);
        assertFalse(service.canProceedFromDecisionWorkspace());
        service.enterProcessingWorkspaceFromDecision();
        ProjectProcessingPlan plan = service.processingSelectionModel().reviewedPlan();
        assertFalse(executeEnabled(plan), "ambiguous mapping must not become an executable Start Processing plan");
        assertFalse(service.isPlanExecutionActive());
    }

    @Test
    void noSelectedChannelRemainsBlocked() throws Exception {
        DesktopAppService service = newProductionService("none-selected");
        WorkspaceFile source = addNativeOnlyMf4(service, unique("none.mf4"), 1, 3, 100L);
        service.quickDecodeSelectionModel().setIncluded(
                SourceChannelKey.of(source.projectSourceId(), 1), false);
        assertFalse(service.canProceedFromDecisionWorkspace());
        service.enterProcessingWorkspaceFromDecision();
        assertTrue(service.processingSelectionModel().reviewedPlan().jobs().isEmpty());
    }

    @Test
    void changingDecodeStrategyDoesNotStartProcessing() throws Exception {
        DesktopAppService service = newProductionService("mode-toggle");
        addNativeOnlyMf4(service, unique("toggle.mf4"), 1, 3, 100L);
        assertFalse(service.isPlanExecutionActive());
        service.setDecisionWorkspaceMode(DecisionWorkspaceMode.DETAILED_MAPPING);
        assertFalse(service.isPlanExecutionActive());
        service.setDecisionWorkspaceMode(DecisionWorkspaceMode.QUICK_DECODE);
        assertFalse(service.isPlanExecutionActive());
        assertTrue(service.processingResultRegistry().all().isEmpty());
    }

    @Test
    void multiSourceNativeOnlyJobsStayIsolatedInReviewPlan() throws Exception {
        DesktopAppService service = newProductionService("multi-native");
        WorkspaceFile a = addNativeOnlyMf4(service, unique("left.mf4"), 1, 1, 10L);
        WorkspaceFile b = addNativeOnlyMf4(service, unique("right.mf4"), 1, 1, 20L);
        service.enterProcessingWorkspaceFromDecision();
        ProjectProcessingPlan plan = service.processingSelectionModel().reviewedPlan();
        assertEquals(2, plan.jobs().size());
        assertEquals(List.of(a.id(), b.id()), plan.jobs().stream().map(ProcessingJob::projectSourceId).toList());
        assertTrue(plan.jobs().stream().allMatch(job ->
                job.executable() && job.definitionSourceId().isEmpty()));
    }

    private DesktopAppService newProductionService(String name) {
        if (previousHome == null) {
            previousHome = System.getProperty("stellarai.home");
            System.setProperty("stellarai.home", tempDir.resolve("home").toAbsolutePath().toString());
        }
        DesktopAppService service = DesktopAppService.forLocalProduction(new DesktopSession(ParserRegistry.load()));
        services.add(service);
        service.newProject(name);
        assertTrue(service.isolateProductionProcessing());
        return service;
    }

    private static boolean executeEnabled(ProjectProcessingPlan plan) {
        return plan != null
                && !plan.jobs().isEmpty()
                && plan.readiness() == ProcessingPlanReadiness.READY
                && plan.jobs().stream().allMatch(ProcessingJob::executable);
    }

    private static void assertNativeOnlyReviewPlan(ProjectProcessingPlan plan, String sourceId) {
        assertEquals(ProcessingPlanReadiness.READY, plan.readiness());
        assertEquals(1, plan.jobs().size());
        ProcessingJob job = plan.jobs().getFirst();
        assertEquals(sourceId, job.projectSourceId());
        assertTrue(job.executable());
        assertEquals(MappingLifecycle.NO_MATCH, job.mappingLifecycle());
        assertTrue(job.definitionSourceId().isEmpty());
    }

    private WorkspaceFile addNativeOnlyMf4(
            DesktopAppService service, String name, int channelId, long observed, long frames
    ) throws Exception {
        WorkspaceFile source = addReadyMf4(service, name, channelId, observed, frames);
        service.definitionMatchingModel().putMapping(new SourceChannelMappingEntry(
                SourceChannelKey.of(source.projectSourceId(), channelId),
                ChannelMappingStatus.UNMAPPED, ChannelRecommendationKind.NO_MATCH,
                null, "", null, "", observed, 0L, 0.0));
        return source;
    }

    private WorkspaceFile addMappedMf4(DesktopAppService service, String name, WorkspaceFile dbc) throws Exception {
        WorkspaceFile source = addReadyMf4(service, name, 0, 1, 1L);
        service.definitionMatchingModel().putMapping(new SourceChannelMappingEntry(
                SourceChannelKey.of(source.projectSourceId(), 0),
                ChannelMappingStatus.RECOMMENDED, ChannelRecommendationKind.CLEAR_BEST_MATCH,
                dbc.definitionSourceId(), dbc.fileName(), null, "", 1, 1, 1.0));
        return source;
    }

    private WorkspaceFile addReadyMf4(
            DesktopAppService service, String name, int channelId, long observed, long frames
    ) throws Exception {
        Path mf4 = DesktopFixtures.writeFinalizedMf4(tempDir.resolve(name));
        WorkspaceFile source = new WorkspaceFile(mf4, FileKind.MEASUREMENT_MF4, Files.size(mf4));
        service.workspace().addFile(source);
        service.sourceInventoryModel().put(source.id(), new CanSourceInventory(
                mf4, name, MeasurementSourceFormat.MF4, CanInventoryStatus.INVENTORY_READY,
                1, frames, observed, true,
                List.of(new CanChannelInventory(
                        channelId, "Channel " + channelId, frames, observed, observed, 0, 0,
                        List.of(new CanMessageInventory(
                                new CanMessageKey(channelId, 0x100L, false), frames, 8, 8, false)))),
                List.of()));
        return source;
    }

    private WorkspaceFile addDefinition(DesktopAppService service, String name) throws Exception {
        Path dbcPath = DesktopFixtures.copyResource("dbc/desktop_engine.dbc", tempDir.resolve(name));
        WorkspaceFile dbc = new WorkspaceFile(dbcPath, FileKind.DEFINITION_DBC, Files.size(dbcPath));
        service.workspace().addFile(dbc);
        return dbc;
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
}
