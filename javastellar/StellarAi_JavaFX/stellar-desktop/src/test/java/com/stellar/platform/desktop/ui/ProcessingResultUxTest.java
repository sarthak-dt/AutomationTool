package com.stellar.platform.desktop.ui;

import com.stellar.platform.desktop.presentation.DecisionWorkspaceMode;
import com.stellar.platform.desktop.presentation.DesktopAppService;
import com.stellar.platform.desktop.presentation.ProcessingSelectionWorkspaceModel;
import com.stellar.platform.desktop.presentation.ProcessingWorkspacePhase;
import com.stellar.platform.desktop.project.ChannelMappingStatus;
import com.stellar.platform.desktop.project.ChannelRecommendationKind;
import com.stellar.platform.desktop.project.DefinitionSourceId;
import com.stellar.platform.desktop.project.FileKind;
import com.stellar.platform.desktop.project.SourceChannelKey;
import com.stellar.platform.desktop.project.SourceChannelMappingEntry;
import com.stellar.platform.desktop.project.WorkspaceFile;
import com.stellar.platform.desktop.session.DesktopSession;
import com.stellar.platform.domain.CanonicalSignal;
import com.stellar.platform.domain.MeasurementSourceFormat;
import com.stellar.platform.domain.SourceReference;
import com.stellar.platform.domain.inventory.CanChannelInventory;
import com.stellar.platform.domain.inventory.CanInventoryStatus;
import com.stellar.platform.domain.inventory.CanMessageInventory;
import com.stellar.platform.domain.inventory.CanMessageKey;
import com.stellar.platform.domain.inventory.CanSourceInventory;
import com.stellar.platform.domain.processing.plan.IndependentJobResult;
import com.stellar.platform.domain.processing.plan.JobExecutionStatus;
import com.stellar.platform.domain.processing.plan.MappingLifecycle;
import com.stellar.platform.domain.processing.plan.MultiSourceProcessingResult;
import com.stellar.platform.domain.processing.plan.ProcessingJob;
import com.stellar.platform.domain.processing.plan.ProcessingJobKey;
import com.stellar.platform.domain.processing.plan.ProjectExecutionStatus;
import com.stellar.platform.domain.processing.plan.ProjectProcessingPlan;
import com.stellar.platform.domain.processing.plan.SourceChannelRef;
import com.stellar.platform.processing.ParserRegistry;
import com.stellar.platform.processing.catalog.InMemorySignalCatalogStore;
import com.stellar.platform.desktop.presentation.ProcessingPlanInputAssembler;
import com.stellar.platform.storage.metadata.ProcessingJobMetadata;
import com.stellar.platform.storage.metadata.ProcessingRunMetadata;
import com.stellar.platform.storage.metadata.ProjectMetadata;
import com.stellar.platform.storage.metadata.SqliteProjectMetadataStore;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 3 — the active, partially-failed and successful processing states as the engineer sees
 * them: user-facing statuses, preserved successes and explicit onward actions.
 */
class ProcessingResultUxTest {

    @BeforeAll
    static void initJavaFx() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        try {
            Platform.startup(started::countDown);
        } catch (IllegalStateException alreadyStarted) {
            started.countDown();
        }
        assertTrue(started.await(10, TimeUnit.SECONDS));
        CountDownLatch configured = new CountDownLatch(1);
        Platform.runLater(() -> {
            Platform.setImplicitExit(false);
            configured.countDown();
        });
        assertTrue(configured.await(5, TimeUnit.SECONDS));
    }

    @Test
    @Timeout(60)
    void activeRunCountsFilesAndUsesUserFacingRowStatuses() throws Exception {
        runOnFx(() -> {
            Fixture fixture = fixture("proc-active");
            ProcessingSelectionWorkspaceModel model = fixture.service.processingSelectionModel();
            model.beginExecution(fixture.plan);
            model.updateJobStatus(fixture.plan.jobs().get(0).key(), JobExecutionStatus.RUNNING);

            ProcessingWorkspacePane pane = fixture.controller.workspacePaneForTest();
            assertEquals(ProcessingWorkspacePhase.RUNNING, pane.phaseForTest());
            assertEquals("Processing", pane.titleForTest());

            // Counts, not an invented percentage.
            assertTrue(pane.summaryForTest().contains(" of 2 "), pane.summaryForTest());
            assertFalse(pane.summaryForTest().contains("%"), pane.summaryForTest());

            List<ProcessingWorkspacePane.JobView> rows = pane.jobsTableForTest().getItems();
            assertEquals("Processing", rows.get(0).statusLabel());
            assertEquals("Pending", rows.get(1).statusLabel());
            assertTrue(rows.stream().noneMatch(row -> row.statusLabel().equals(row.statusLabel().toUpperCase(
                    java.util.Locale.ROOT))), "internal enum names must not reach the table");

            // Cancel is the only exit while work is in flight.
            assertTrue(pane.cancelButtonForTest().isVisible());
            assertFalse(pane.runButtonForTest().isVisible());
            assertTrue(pane.backButtonForTest().isDisable());
            fixture.service.shutdown();
        });
    }

    @Test
    @Timeout(60)
    void oneFailureLeavesTheSuccessfulUnitIntactAndOffersRetryFailed() throws Exception {
        runOnFx(() -> {
            Fixture fixture = fixture("proc-partial");
            ProcessingSelectionWorkspaceModel model = fixture.service.processingSelectionModel();
            model.beginExecution(fixture.plan);
            ProcessingJob good = fixture.plan.jobs().get(0);
            ProcessingJob bad = fixture.plan.jobs().get(1);

            IndependentJobResult goodResult = success(good.key(), 100L, 100L, 400L);
            model.updateJobStatus(good.key(), goodResult);
            IndependentJobResult badResult = failure(bad.key(), "Source unreadable");
            model.updateJobStatus(bad.key(), badResult);
            model.completeExecution(new MultiSourceProcessingResult(
                    fixture.plan.planId(), Instant.now(), ProjectExecutionStatus.PARTIAL_FAILURE,
                    List.of(goodResult, badResult), List.of(), Duration.ofSeconds(3)));

            ProcessingWorkspacePane pane = fixture.controller.workspacePaneForTest();
            assertEquals("Processing completed with issues", pane.titleForTest());

            List<ProcessingWorkspacePane.JobView> rows = pane.jobsTableForTest().getItems();
            assertEquals("Completed", rows.get(0).statusLabel());
            assertEquals("Failed", rows.get(1).statusLabel());
            assertTrue(rows.get(0).canViewSignals(),
                    "a failure elsewhere must not invalidate a successful unit");
            assertFalse(rows.get(1).canViewSignals());

            assertTrue(pane.retryFailedButtonForTest().isVisible());
            assertTrue(pane.openAnalysisButtonForTest().isVisible(),
                    "results that did succeed must remain explorable");
            fixture.service.shutdown();
        });
    }

    @Test
    @Timeout(60)
    void successStaysVisibleAndExploringSignalsIsExplicit() throws Exception {
        runOnFx(() -> {
            Fixture fixture = fixture("proc-success");
            ProcessingSelectionWorkspaceModel model = fixture.service.processingSelectionModel();
            model.beginExecution(fixture.plan);
            IndependentJobResult a = success(fixture.plan.jobs().get(0).key(), 100L, 100L, 400L);
            IndependentJobResult b = success(fixture.plan.jobs().get(1).key(), 50L, 50L, 200L);
            model.updateJobStatus(a.jobKey(), a);
            model.updateJobStatus(b.jobKey(), b);
            model.completeExecution(new MultiSourceProcessingResult(
                    fixture.plan.planId(), Instant.now(), ProjectExecutionStatus.SUCCESS,
                    List.of(a, b), List.of(), Duration.ofSeconds(2)));

            ProcessingWorkspacePane pane = fixture.controller.workspacePaneForTest();
            assertEquals(ProcessingWorkspacePhase.COMPLETED, pane.phaseForTest());
            assertEquals("Processing Complete", pane.titleForTest());
            assertEquals("Explore All Signals", pane.openAnalysisButtonForTest().getText());
            assertEquals("Total Processing Time 00:02", pane.totalProcessingTimeForTest());
            assertTrue(pane.quickSourceTableForTest().getColumns().stream()
                    .anyMatch(column -> "Elapsed".equals(column.getText())));
            assertTrue(pane.quickSourceTableForTest().getItems().stream()
                    .allMatch(row -> "00:01".equals(row.elapsedLabel())));
            assertFalse(pane.retryFailedButtonForTest().isVisible());
            assertFalse(pane.cancelButtonForTest().isVisible());
            assertTrue(fixture.service.signalsModel().catalog().entries().isEmpty(),
                    "navigating to Signals is an explicit user action");
            fixture.service.shutdown();
        });
    }

    @Test
    @Timeout(60)
    void reopenedZeroElapsedUsesPersistedWindowAndLegacyStaysBlank() throws Exception {
        runOnFx(() -> {
            Fixture fixture = fixture("proc-reopen-elapsed");
            ProcessingSelectionWorkspaceModel model = fixture.service.processingSelectionModel();
            model.beginExecution(fixture.plan);
            IndependentJobResult a = success(fixture.plan.jobs().get(0).key(), 100L, 100L, 400L);
            IndependentJobResult b = success(fixture.plan.jobs().get(1).key(), 50L, 50L, 200L);
            IndependentJobResult aZero = new IndependentJobResult(
                    a.jobKey(), a.definitionSourceId().orElse(null), a.definitionDisplayName(),
                    a.mappingLifecycle(), a.status(), a.expectedSelectedChannelFrames(),
                    a.sourceFramesRead(), a.selectedChannelFrames(), a.dbcMatchedFrames(),
                    a.dbcUnmatchedFrames(), a.signalsProduced(),
                    a.firstSampleTimestampNanos().orElse(null), a.lastSampleTimestampNanos().orElse(null),
                    a.observedSourceTiming().orElse(null), Duration.ZERO, a.diagnostics(),
                    a.technicalDetail(), a.catalogHandle().orElse(null));
            IndependentJobResult bZero = new IndependentJobResult(
                    b.jobKey(), b.definitionSourceId().orElse(null), b.definitionDisplayName(),
                    b.mappingLifecycle(), b.status(), b.expectedSelectedChannelFrames(),
                    b.sourceFramesRead(), b.selectedChannelFrames(), b.dbcMatchedFrames(),
                    b.dbcUnmatchedFrames(), b.signalsProduced(),
                    b.firstSampleTimestampNanos().orElse(null), b.lastSampleTimestampNanos().orElse(null),
                    b.observedSourceTiming().orElse(null), Duration.ZERO, b.diagnostics(),
                    b.technicalDetail(), b.catalogHandle().orElse(null));
            model.updateJobStatus(aZero.jobKey(), aZero);
            model.updateJobStatus(bZero.jobKey(), bZero);
            model.completeExecution(new MultiSourceProcessingResult(
                    fixture.plan.planId(), Instant.now(), ProjectExecutionStatus.SUCCESS,
                    List.of(aZero, bZero), List.of(), Duration.ofSeconds(26)));

            ProcessingWorkspacePane pane = fixture.controller.workspacePaneForTest();
            assertEquals("Total Processing Time 00:26", pane.totalProcessingTimeForTest());
            assertTrue(pane.quickSourceTableForTest().getItems().stream()
                    .allMatch(row -> "N/A".equals(row.elapsedLabel())),
                    "legacy reopen without ledger windows must stay blank");
            fixture.service.shutdown();
        });
    }

    @Test
    @Timeout(60)
    void reopenedZeroElapsedRestoresFromPersistedJobWindows() throws Exception {
        runOnFx(() -> {
            Path temp = Files.createTempDirectory("proc-reopen-restore");
            SqliteProjectMetadataStore store = new SqliteProjectMetadataStore(temp.resolve("meta.db"));
            DesktopAppService service = new DesktopAppService(new DesktopSession(ParserRegistry.load()), store);
            try {
                WorkspaceFile a = addReadySource(service, temp, "run01.mf4", 1, 100L);
                WorkspaceFile b = addReadySource(service, temp, "run02.mf4", 1, 50L);
                WorkspaceFile dbc = addDefinition(service, temp, "chassis.dbc");
                confirm(service, a, 1, dbc.definitionSourceId());
                confirm(service, b, 1, dbc.definitionSourceId());
                service.processingSelectionModel().select(SourceChannelRef.of(a.id(), 1));
                service.processingSelectionModel().select(SourceChannelRef.of(b.id(), 1));
                service.processingSelectionModel().captureDecisionOrigin(DecisionWorkspaceMode.DETAILED_MAPPING);
                ProjectProcessingPlan plan = service.processingSelectionModel()
                        .reviewPlan(new ProcessingPlanInputAssembler().assemble(service));

                Instant t0 = Instant.parse("2026-10-11T10:00:00Z");
                String projectId = service.currentProjectId();
                String runId = "run-reopen";
                ProcessingJob first = plan.jobs().get(0);
                ProcessingJob second = plan.jobs().get(1);
                store.upsertProject(new ProjectMetadata(projectId, "reopen-elapsed", t0, t0, "PROCESSING_RESULT", null));
                ProcessingRunMetadata run = new ProcessingRunMetadata(
                        runId, projectId, "COMPLETED", false, 2, t0, t0.plusSeconds(26), "ok");
                store.upsertProcessingRun(run);
                store.upsertProcessingJob(new ProcessingJobMetadata(
                        first.key().toString(), projectId, runId, first.projectSourceId(), first.channelId(),
                        "d", JobExecutionStatus.SUCCESS, 1, 3, 1d, "SUCCESS", "isolated-worker",
                        t0, t0, t0.plusSeconds(9), null, ""));
                store.upsertProcessingJob(new ProcessingJobMetadata(
                        second.key().toString(), projectId, runId, second.projectSourceId(), second.channelId(),
                        "d", JobExecutionStatus.SUCCESS, 1, 3, 1d, "SUCCESS", "isolated-worker",
                        t0, t0.plusSeconds(9), t0.plusSeconds(26), null, ""));
                service.rememberPersistedRunForTest(run);

                IndependentJobResult aZero = withZeroElapsed(success(first.key(), 100L, 100L, 400L));
                IndependentJobResult bZero = withZeroElapsed(success(second.key(), 50L, 50L, 200L));
                ProcessingSelectionWorkspaceModel model = service.processingSelectionModel();
                model.beginExecution(plan);
                model.updateJobStatus(aZero.jobKey(), aZero);
                model.updateJobStatus(bZero.jobKey(), bZero);
                model.completeExecution(new MultiSourceProcessingResult(
                        plan.planId(), Instant.now(), ProjectExecutionStatus.SUCCESS,
                        List.of(aZero, bZero), List.of(), Duration.ofSeconds(26)));

                ProcessingController controller = loadController(service);
                ProcessingWorkspacePane pane = controller.workspacePaneForTest();
                assertEquals("Total Processing Time 00:26", pane.totalProcessingTimeForTest());
                List<String> elapsed = pane.quickSourceTableForTest().getItems().stream()
                        .map(ProcessingWorkspacePane.QuickSourceView::elapsedLabel)
                        .toList();
                assertTrue(elapsed.contains("00:09"), "first source window missing: " + elapsed);
                assertTrue(elapsed.contains("00:17"), "second source window missing: " + elapsed);
            } finally {
                service.shutdown();
                store.close();
            }
        });
    }

    @Test
    @Timeout(60)
    void fullReprocessShowsFreshElapsedAndCurrentRunTotal() throws Exception {
        runOnFx(() -> {
            Path temp = Files.createTempDirectory("proc-reprocess-elapsed");
            SqliteProjectMetadataStore store = new SqliteProjectMetadataStore(temp.resolve("meta.db"));
            DesktopAppService service = new DesktopAppService(new DesktopSession(ParserRegistry.load()), store);
            try {
                WorkspaceFile a = addReadySource(service, temp, "run01.mf4", 1, 100L);
                WorkspaceFile b = addReadySource(service, temp, "run02.mf4", 1, 50L);
                WorkspaceFile dbc = addDefinition(service, temp, "chassis.dbc");
                confirm(service, a, 1, dbc.definitionSourceId());
                confirm(service, b, 1, dbc.definitionSourceId());
                service.processingSelectionModel().select(SourceChannelRef.of(a.id(), 1));
                service.processingSelectionModel().select(SourceChannelRef.of(b.id(), 1));
                service.processingSelectionModel().captureDecisionOrigin(DecisionWorkspaceMode.DETAILED_MAPPING);
                ProjectProcessingPlan plan = service.processingSelectionModel()
                        .reviewPlan(new ProcessingPlanInputAssembler().assemble(service));
                Instant t0 = Instant.parse("2026-10-11T10:00:00Z");
                String projectId = service.currentProjectId();
                store.upsertProject(new ProjectMetadata(projectId, "reprocess", t0, t0, "PROCESSING_RESULT", null));
                ProcessingJob first = plan.jobs().get(0);
                ProcessingJob second = plan.jobs().get(1);
                ProcessingRunMetadata original = new ProcessingRunMetadata(
                        "run-old", projectId, "COMPLETED", false, 2, t0, t0.plusSeconds(26), "ok");
                store.upsertProcessingRun(original);
                store.upsertProcessingJob(new ProcessingJobMetadata(
                        first.key().toString(), projectId, "run-old", first.projectSourceId(), first.channelId(),
                        "d", JobExecutionStatus.SUCCESS, 1, 3, 1d, "SUCCESS", "in-process",
                        t0, t0, t0.plusSeconds(9), null, ""));
                store.upsertProcessingJob(new ProcessingJobMetadata(
                        second.key().toString(), projectId, "run-old", second.projectSourceId(), second.channelId(),
                        "d", JobExecutionStatus.SUCCESS, 1, 3, 1d, "SUCCESS", "in-process",
                        t0, t0.plusSeconds(9), t0.plusSeconds(26), null, ""));
                ProcessingRunMetadata reprocess = new ProcessingRunMetadata(
                        "run-new", projectId, "COMPLETED", true, 2, t0.plusSeconds(60), t0.plusSeconds(68), "ok");
                store.upsertProcessingRun(reprocess);
                store.upsertProcessingJob(new ProcessingJobMetadata(
                        first.key().toString(), projectId, "run-new", first.projectSourceId(), first.channelId(),
                        "d", JobExecutionStatus.SUCCESS, 1, 3, 1d, "SUCCESS", "in-process",
                        t0, t0.plusSeconds(60), t0.plusSeconds(64), null, ""));
                store.upsertProcessingJob(new ProcessingJobMetadata(
                        second.key().toString(), projectId, "run-new", second.projectSourceId(), second.channelId(),
                        "d", JobExecutionStatus.SUCCESS, 1, 3, 1d, "SUCCESS", "in-process",
                        t0, t0.plusSeconds(64), t0.plusSeconds(68), null, ""));
                service.rememberPersistedRunForTest(original);
                service.rememberPersistedRunForTest(reprocess);

                IndependentJobResult aZero = withZeroElapsed(success(first.key(), 100L, 100L, 400L));
                IndependentJobResult bZero = withZeroElapsed(success(second.key(), 50L, 50L, 200L));
                ProcessingSelectionWorkspaceModel model = service.processingSelectionModel();
                model.beginExecution(plan);
                model.updateJobStatus(aZero.jobKey(), aZero);
                model.updateJobStatus(bZero.jobKey(), bZero);
                model.completeExecution(new MultiSourceProcessingResult(
                        plan.planId(), Instant.now(), ProjectExecutionStatus.SUCCESS,
                        List.of(aZero, bZero), List.of(), Duration.ofSeconds(8)));

                ProcessingWorkspacePane pane = loadController(service).workspacePaneForTest();
                assertEquals("Total Processing Time 00:08", pane.totalProcessingTimeForTest());
                List<String> elapsed = pane.quickSourceTableForTest().getItems().stream()
                        .map(ProcessingWorkspacePane.QuickSourceView::elapsedLabel)
                        .toList();
                assertTrue(elapsed.contains("00:04"), "reprocess window missing: " + elapsed);
                assertFalse(elapsed.contains("00:09"), "old elapsed leaked: " + elapsed);
                assertFalse(elapsed.contains("00:17"), "old elapsed leaked: " + elapsed);
            } finally {
                service.shutdown();
                store.close();
            }
        });
    }

    @Test
    @Timeout(60)
    void addMoreMeasurementKeepsOldElapsedAndShowsNewFileElapsed() throws Exception {
        runOnFx(() -> {
            Path temp = Files.createTempDirectory("proc-add-files-elapsed");
            SqliteProjectMetadataStore store = new SqliteProjectMetadataStore(temp.resolve("meta.db"));
            DesktopAppService service = new DesktopAppService(new DesktopSession(ParserRegistry.load()), store);
            try {
                WorkspaceFile a = addReadySource(service, temp, "run01.mf4", 1, 100L);
                WorkspaceFile b = addReadySource(service, temp, "run02.mf4", 1, 50L);
                WorkspaceFile added = addReadySource(service, temp, "run03.mf4", 1, 40L);
                WorkspaceFile dbc = addDefinition(service, temp, "chassis.dbc");
                confirm(service, a, 1, dbc.definitionSourceId());
                confirm(service, b, 1, dbc.definitionSourceId());
                confirm(service, added, 1, dbc.definitionSourceId());
                service.processingSelectionModel().select(SourceChannelRef.of(a.id(), 1));
                service.processingSelectionModel().select(SourceChannelRef.of(b.id(), 1));
                service.processingSelectionModel().select(SourceChannelRef.of(added.id(), 1));
                service.processingSelectionModel().captureDecisionOrigin(DecisionWorkspaceMode.DETAILED_MAPPING);
                ProjectProcessingPlan plan = service.processingSelectionModel()
                        .reviewPlan(new ProcessingPlanInputAssembler().assemble(service));
                Instant t0 = Instant.parse("2026-10-11T10:00:00Z");
                String projectId = service.currentProjectId();
                store.upsertProject(new ProjectMetadata(projectId, "add-files", t0, t0, "PROCESSING_RESULT", null));
                ProcessingJob first = plan.jobs().get(0);
                ProcessingJob second = plan.jobs().get(1);
                ProcessingJob third = plan.jobs().get(2);
                ProcessingRunMetadata original = new ProcessingRunMetadata(
                        "run-old", projectId, "COMPLETED", false, 2, t0, t0.plusSeconds(26), "ok");
                ProcessingRunMetadata incremental = new ProcessingRunMetadata(
                        "run-add", projectId, "COMPLETED", false, 1, t0.plusSeconds(40), t0.plusSeconds(43), "ok");
                store.upsertProcessingRun(original);
                store.upsertProcessingRun(incremental);
                store.upsertProcessingJob(new ProcessingJobMetadata(
                        first.key().toString(), projectId, "run-old", first.projectSourceId(), first.channelId(),
                        "d", JobExecutionStatus.SUCCESS, 1, 3, 1d, "SUCCESS", "in-process",
                        t0, t0, t0.plusSeconds(9), null, ""));
                store.upsertProcessingJob(new ProcessingJobMetadata(
                        second.key().toString(), projectId, "run-old", second.projectSourceId(), second.channelId(),
                        "d", JobExecutionStatus.SUCCESS, 1, 3, 1d, "SUCCESS", "in-process",
                        t0, t0.plusSeconds(9), t0.plusSeconds(26), null, ""));
                store.upsertProcessingJob(new ProcessingJobMetadata(
                        third.key().toString(), projectId, "run-add", third.projectSourceId(), third.channelId(),
                        "d", JobExecutionStatus.SUCCESS, 1, 3, 1d, "SUCCESS", "in-process",
                        t0, t0.plusSeconds(40), t0.plusSeconds(43), null, ""));
                service.rememberPersistedRunForTest(original);
                service.rememberPersistedRunForTest(incremental);

                IndependentJobResult aZero = withZeroElapsed(success(first.key(), 100L, 100L, 400L));
                IndependentJobResult bZero = withZeroElapsed(success(second.key(), 50L, 50L, 200L));
                IndependentJobResult dZero = withZeroElapsed(success(third.key(), 40L, 40L, 160L));
                ProcessingSelectionWorkspaceModel model = service.processingSelectionModel();
                model.beginIncrementalExecution(plan, service.processingResultRegistry());
                model.updateJobStatus(aZero.jobKey(), aZero);
                model.updateJobStatus(bZero.jobKey(), bZero);
                model.updateJobStatus(dZero.jobKey(), dZero);
                model.completeExecution(new MultiSourceProcessingResult(
                        plan.planId(), Instant.now(), ProjectExecutionStatus.SUCCESS,
                        List.of(aZero, bZero, dZero), List.of(), Duration.ofSeconds(3)));

                ProcessingWorkspacePane pane = loadController(service).workspacePaneForTest();
                assertEquals("Total Processing Time 00:03", pane.totalProcessingTimeForTest());
                Map<String, String> bySource = new java.util.LinkedHashMap<>();
                for (ProcessingWorkspacePane.QuickSourceView row : pane.quickSourceTableForTest().getItems()) {
                    bySource.put(row.projectSourceId(), row.elapsedLabel());
                }
                assertEquals("00:09", bySource.get(first.projectSourceId()));
                assertEquals("00:17", bySource.get(second.projectSourceId()));
                assertEquals("00:03", bySource.get(third.projectSourceId()));
            } finally {
                service.shutdown();
                store.close();
            }
        });
    }

    @Test
    @Timeout(60)
    void doubleClickingStartProcessingDoesNotStartTwoRuns() throws Exception {
        runOnFx(() -> {
            Fixture fixture = fixture("proc-double");
            ProcessingWorkspacePane pane = fixture.controller.workspacePaneForTest();
            assertEquals("Start Processing", pane.runButtonForTest().getText());

            pane.fireStartProcessingForTest();
            // The button is unusable the instant the first click is accepted.
            assertTrue(pane.runButtonForTest().isDisable() || !pane.runButtonForTest().isVisible(),
                    "the primary action must not stay clickable after being fired");
            pane.fireStartProcessingForTest();
            pane.fireStartProcessingForTest();

            assertFalse(fixture.service.processingSelectionModel().executionJobs().isEmpty(),
                    "the first click must actually have started a run");
            fixture.service.shutdown();
        });
    }

    @Test
    @Timeout(40)
    void elapsedColumnKeepsActionsInside1366Content() throws Exception {
        runOnFx(() -> {
            FXMLLoader loader = new FXMLLoader(
                    ProcessingResultUxTest.class.getResource("/fxml/processing-view.fxml"));
            Parent root = loader.load();
            ProcessingController controller = loader.getController();
            Scene scene = new Scene(root, 1366 - 250, 768);
            Stage stage = new Stage();
            stage.setScene(scene);
            stage.show();
            root.applyCss();
            root.layout();
            var table = controller.workspacePaneForTest().quickSourceTableForTest();
            double mins = 0;
            boolean elapsed = false;
            for (var column : table.getColumns()) {
                mins += column.getMinWidth();
                if ("Elapsed".equals(column.getText())) {
                    elapsed = true;
                }
            }
            assertTrue(elapsed);
            assertTrue(mins <= 1366 - 250, "column minimums " + mins);
            stage.close();
        });
    }

    private record Fixture(
            DesktopAppService service,
            ProcessingController controller,
            ProjectProcessingPlan plan
    ) {
    }

    private static Fixture fixture(String slug) throws Exception {
        Path temp = Files.createTempDirectory(slug);
        DesktopAppService service = new DesktopAppService(new DesktopSession(ParserRegistry.load()));
        WorkspaceFile a = addReadySource(service, temp, "run01.mf4", 1, 100L);
        WorkspaceFile b = addReadySource(service, temp, "run02.mf4", 1, 50L);
        WorkspaceFile dbc = addDefinition(service, temp, "chassis.dbc");
        confirm(service, a, 1, dbc.definitionSourceId());
        confirm(service, b, 1, dbc.definitionSourceId());
        service.processingSelectionModel().select(SourceChannelRef.of(a.id(), 1));
        service.processingSelectionModel().select(SourceChannelRef.of(b.id(), 1));
        service.processingSelectionModel().captureDecisionOrigin(DecisionWorkspaceMode.DETAILED_MAPPING);
        ProjectProcessingPlan plan = service.processingSelectionModel()
                .reviewPlan(new ProcessingPlanInputAssembler().assemble(service));
        return new Fixture(service, loadController(service), plan);
    }

    private static IndependentJobResult success(
            ProcessingJobKey key, long frames, long matched, long signals
    ) {
        InMemorySignalCatalogStore catalog = new InMemorySignalCatalogStore("src", "chassis.dbc");
                catalog.accept(List.of(new CanonicalSignal(
                "VehicleSpeed", "Msg", 10L, 0x10, 1, 1.0, 1L, "km/h", null, SourceReference.ofLine(1))));
        return new IndependentJobResult(
                key, "d", "chassis.dbc", MappingLifecycle.CONFIRMED, JobExecutionStatus.SUCCESS,
                frames, frames, frames, matched, 0, signals,
                1L, 2L, null, Duration.ofSeconds(1), List.of(), "", catalog);
    }

    private static IndependentJobResult withZeroElapsed(IndependentJobResult result) {
        return new IndependentJobResult(
                result.jobKey(), result.definitionSourceId().orElse(null), result.definitionDisplayName(),
                result.mappingLifecycle(), result.status(), result.expectedSelectedChannelFrames(),
                result.sourceFramesRead(), result.selectedChannelFrames(), result.dbcMatchedFrames(),
                result.dbcUnmatchedFrames(), result.signalsProduced(),
                result.firstSampleTimestampNanos().orElse(null), result.lastSampleTimestampNanos().orElse(null),
                result.observedSourceTiming().orElse(null), Duration.ZERO, result.diagnostics(),
                result.technicalDetail(), result.catalogHandle().orElse(null));
    }

    private static IndependentJobResult failure(ProcessingJobKey key, String message) {
        return new IndependentJobResult(
                key, "d", "chassis.dbc", MappingLifecycle.CONFIRMED, JobExecutionStatus.FAILED,
                0, 0, 0, 0, 0, 0,
                null, null, null, Duration.ofMillis(120), List.of(), message, null);
    }

    private static ProcessingController loadController(DesktopAppService service) throws Exception {
        FXMLLoader loader = new FXMLLoader(
                ProcessingResultUxTest.class.getResource("/fxml/processing-view.fxml"));
        Parent root = loader.load();
        ProcessingController controller = loader.getController();
        controller.setAppService(service, null);
        Scene scene = new Scene(root, 1600, 900);
        var css = ProcessingResultUxTest.class.getResource("/css/stellar-desktop.css");
        if (css != null) {
            scene.getStylesheets().add(css.toExternalForm());
        }
        Stage stage = new Stage();
        stage.setScene(scene);
        stage.show();
        root.applyCss();
        root.layout();
        return controller;
    }

    private static WorkspaceFile addReadySource(
            DesktopAppService service, Path dir, String name, int channelId, long frames
    ) throws Exception {
        Files.createDirectories(dir);
        Path path = dir.resolve(name);
        Files.writeString(path, "stub");
        WorkspaceFile file = new WorkspaceFile(path, FileKind.MEASUREMENT_MF4, 10);
        service.workspace().addFile(file);
        service.sourceInventoryModel().put(file.id(), new CanSourceInventory(
                path, name, MeasurementSourceFormat.MF4, CanInventoryStatus.INVENTORY_READY,
                1, frames, 31, true,
                List.of(new CanChannelInventory(channelId, "Channel " + channelId, frames, 31, 31, 0, 0,
                        List.of(new CanMessageInventory(
                                new CanMessageKey(channelId, 0x100L, false), 10, 8, 8, false)))),
                List.of()));
        return file;
    }

    private static WorkspaceFile addDefinition(DesktopAppService service, Path dir, String name)
            throws Exception {
        Path path = dir.resolve(name);
        Files.writeString(path, "VERSION \"\"\n");
        WorkspaceFile file = new WorkspaceFile(path, FileKind.DEFINITION_DBC, 10);
        service.workspace().addFile(file);
        return file;
    }

    private static void confirm(
            DesktopAppService service, WorkspaceFile measurement, int channelId, DefinitionSourceId defId
    ) {
        service.definitionMatchingModel().putMapping(new SourceChannelMappingEntry(
                SourceChannelKey.of(measurement.projectSourceId(), channelId),
                ChannelMappingStatus.CONFIRMED, ChannelRecommendationKind.CLEAR_BEST_MATCH,
                defId, "chassis.dbc", defId, "chassis.dbc", 1, 1, 1.0));
    }

    private static void runOnFx(FxAction action) throws Exception {
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                action.run();
            } catch (Throwable t) {
                error.set(t);
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(50, TimeUnit.SECONDS), "FX action timed out");
        if (error.get() != null) {
            if (error.get() instanceof RuntimeException re) {
                throw re;
            }
            if (error.get() instanceof Error err) {
                throw err;
            }
            throw new RuntimeException(error.get());
        }
    }

    @FunctionalInterface
    private interface FxAction {
        void run() throws Exception;
    }
}
