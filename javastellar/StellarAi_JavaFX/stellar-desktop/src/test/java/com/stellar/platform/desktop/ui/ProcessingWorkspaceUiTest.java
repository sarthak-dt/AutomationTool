package com.stellar.platform.desktop.ui;

import com.stellar.platform.desktop.presentation.DecisionWorkspaceMode;
import com.stellar.platform.desktop.presentation.DesktopAppService;
import com.stellar.platform.desktop.presentation.ProcessingPageMode;
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
import com.stellar.platform.domain.SourceReference;
import com.stellar.platform.domain.MeasurementSourceFormat;
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
import com.stellar.platform.domain.processing.plan.ProcessingPlanDiagnostic;
import com.stellar.platform.domain.processing.plan.ProcessingPlanDiagnosticCode;
import com.stellar.platform.domain.processing.plan.ProjectExecutionStatus;
import com.stellar.platform.domain.processing.plan.ProjectProcessingPlan;
import com.stellar.platform.domain.processing.plan.SourceChannelRef;
import com.stellar.platform.processing.ParserRegistry;
import com.stellar.platform.processing.catalog.InMemorySignalCatalogStore;
import com.stellar.platform.processing.plan.ProcessingPlanInputs;
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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessingWorkspaceUiTest {

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
    void reviewModeShowsPlanJobsAndBlocksUnresolvedRun() throws Exception {
        runOnFx(() -> {
            Path temp = Files.createTempDirectory("proc-ws-review");
            DesktopAppService service = new DesktopAppService(new DesktopSession(ParserRegistry.load()));
            WorkspaceFile sourceA = addReadySource(service, temp, "alpha-164651.mf4", 3, 536_827L);
            WorkspaceFile sourceB = addReadySource(service, temp, "beta-171438.mf4", 3, 113_886L);
            WorkspaceFile definition = addDefinition(service, temp, "3.dbc");
            addConfirmedMapping(service, sourceA, 3, definition.definitionSourceId(), "3.dbc", 31, 31, 1.0);
            addConfirmedMapping(service, sourceB, 3, definition.definitionSourceId(), "3.dbc", 31, 31, 1.0);

            service.processingSelectionModel().select(SourceChannelRef.of(sourceA.id(), 3));
            service.processingSelectionModel().select(SourceChannelRef.of(sourceB.id(), 3));
            service.processingSelectionModel().captureDecisionOrigin(DecisionWorkspaceMode.DETAILED_MAPPING);
            ProcessingPlanInputs inputs = new com.stellar.platform.desktop.presentation.ProcessingPlanInputAssembler()
                    .assemble(service);
            service.processingSelectionModel().reviewPlan(inputs);

            ProcessingController controller = loadController(service);
            ProcessingWorkspacePane workspace = controller.workspacePaneForTest();
            assertSame(workspace, controller.processingHostForTest().getChildren().getFirst());
            assertEquals(ProcessingWorkspacePhase.REVIEW, workspace.phaseForTest());
            assertEquals("Review & Process", workspace.titleForTest());

            List<ProcessingWorkspacePane.JobView> rows = workspace.jobsTableForTest().getItems();
            assertEquals(2, rows.size());
            ProcessingWorkspacePane.JobView a = rows.get(0);
            ProcessingWorkspacePane.JobView b = rows.get(1);
            assertEquals(sourceA.id(), a.jobKey().projectSourceId());
            assertEquals(sourceB.id(), b.jobKey().projectSourceId());
            assertEquals("Ch3", a.channelLabel());
            assertEquals("Ch3", b.channelLabel());
            assertEquals("3.dbc", a.dbcLabel());
            assertEquals("3.dbc", b.dbcLabel());
            assertEquals("Confirmed", a.mappingLabel());
            assertEquals("Confirmed", b.mappingLabel());
            assertFalse(ProcessingWorkspacePane.looksLikeSourceId(a.sourceAlias()));
            assertFalse(ProcessingWorkspacePane.looksLikeSourceId(a.sourceFile()));
            assertFalse(ProcessingWorkspacePane.looksLikeSourceId(b.sourceFile()));
            assertTrue(a.sourceFile().contains("164651") || a.sourceFile().endsWith(".mf4"));
            assertTrue(b.sourceFile().contains("171438") || b.sourceFile().endsWith(".mf4"));
            assertEquals("31 / 31", a.matchedLabel());
            assertEquals("100%", a.coverageLabel());
            assertEquals("536,827", a.framesLabel());
            assertEquals("113,886", b.framesLabel());
            assertEquals("Ready", a.statusLabel());
            assertEquals("Ready", b.statusLabel());
            assertTrue(workspace.summaryForTest().contains("2 Jobs"));
            assertTrue(workspace.summaryForTest().contains("650,713"));
            assertFalse(workspace.runButtonForTest().isDisable());
            assertTrue(workspace.jobsTableForTest().getPrefHeight() < 280,
                    "few jobs must not create a giant empty table");

            WorkspaceFile blocked = addReadySource(service, temp, "blocked.mf4", 1, 10L);
            service.definitionMatchingModel().putMapping(new SourceChannelMappingEntry(
                    SourceChannelKey.of(blocked.projectSourceId(), 1),
                    ChannelMappingStatus.AMBIGUOUS,
                    ChannelRecommendationKind.AMBIGUOUS,
                    null, "", null, "", 4, 0, 0.0));
            service.processingSelectionModel().clearSelection();
            service.processingSelectionModel().select(SourceChannelRef.of(blocked.id(), 1));
            service.processingSelectionModel().reviewPlan(
                    new com.stellar.platform.desktop.presentation.ProcessingPlanInputAssembler().assemble(service));
            controller.refresh();
            ProcessingWorkspacePane.JobView blockedRow = workspace.jobsTableForTest().getItems().getFirst();
            assertEquals("Needs Attention", blockedRow.statusLabel());
            assertTrue(workspace.runButtonForTest().isDisable());
            assertTrue(blockedRow.needsAttention());
        });
    }

    @Test
    @Timeout(60)
    void runStaysOnSameWorkspaceAndPreservesOrderOffFxThread() throws Exception {
        Path temp = Files.createTempDirectory("proc-ws-run");
        DesktopAppService service = new DesktopAppService(new DesktopSession(ParserRegistry.load()));
        WorkspaceFile sourceA = addReadySource(service, temp, "a.mf4", 1, 100L);
        WorkspaceFile sourceB = addReadySource(service, temp, "b.mf4", 1, 50L);
        WorkspaceFile definition = addDefinition(service, temp, "map.dbc");
        addConfirmedMapping(service, sourceA, 1, definition.definitionSourceId(), "map.dbc", 1, 1, 1.0);
        addConfirmedMapping(service, sourceB, 1, definition.definitionSourceId(), "map.dbc", 1, 1, 1.0);
        service.processingSelectionModel().select(SourceChannelRef.of(sourceA.id(), 1));
        service.processingSelectionModel().select(SourceChannelRef.of(sourceB.id(), 1));
        service.processingSelectionModel().reviewPlan(
                new com.stellar.platform.desktop.presentation.ProcessingPlanInputAssembler().assemble(service));

        runOnFx(() -> {
            ProcessingController controller = loadController(service);
            ProcessingWorkspacePane workspace = controller.workspacePaneForTest();
            assertEquals(ProcessingWorkspacePhase.REVIEW, workspace.phaseForTest());
            List<ProcessingJob> planJobs = service.processingSelectionModel().reviewedPlan().jobs();
            assertEquals(sourceA.id(), planJobs.get(0).projectSourceId());
            assertEquals(sourceB.id(), planJobs.get(1).projectSourceId());
            workspace.runButtonForTest().fire();
            assertEquals(ProcessingPageMode.EXECUTING, service.processingSelectionModel().mode());
            assertEquals(ProcessingWorkspacePhase.RUNNING, workspace.phaseForTest());
            assertSame(workspace, controller.processingHostForTest().getChildren().getFirst());
            assertEquals(1, controller.processingHostForTest().getChildren().size());
            List<ProcessingWorkspacePane.JobView> rows = workspace.jobsTableForTest().getItems();
            assertEquals(2, rows.size());
            assertEquals(sourceA.id(), rows.get(0).jobKey().projectSourceId());
            assertEquals(sourceB.id(), rows.get(1).jobKey().projectSourceId());
            assertEquals("PROCESSING", rows.get(0).statusLabel());
            assertEquals("PROCESSING", rows.get(1).statusLabel());
            assertTrue(workspace.backButtonForTest().isDisable());
            assertEquals("Processing is currently running.", workspace.backButtonForTest().getTooltip().getText());
            service.cancelProcessing();
        });
    }

    @Test
    @Timeout(60)
    void resultModeDistinguishesWarningsAndIsolatesViewSignals() throws Exception {
        runOnFx(() -> {
            Path temp = Files.createTempDirectory("proc-ws-result");
            DesktopAppService service = new DesktopAppService(new DesktopSession(ParserRegistry.load()));
            WorkspaceFile sourceA = addReadySource(service, temp, "srcA.mf4", 1, 100L);
            WorkspaceFile sourceB = addReadySource(service, temp, "srcB.mf4", 1, 50L);
            WorkspaceFile definition = addDefinition(service, temp, "map.dbc");
            addConfirmedMapping(service, sourceA, 1, definition.definitionSourceId(), "map.dbc", 1, 1, 1.0);
            addConfirmedMapping(service, sourceB, 1, definition.definitionSourceId(), "map.dbc", 1, 1, 1.0);
            service.processingSelectionModel().select(SourceChannelRef.of(sourceA.id(), 1));
            service.processingSelectionModel().select(SourceChannelRef.of(sourceB.id(), 1));
            service.processingSelectionModel().captureDecisionOrigin(DecisionWorkspaceMode.DETAILED_MAPPING);
            ProjectProcessingPlan plan = service.processingSelectionModel().reviewPlan(
                    new com.stellar.platform.desktop.presentation.ProcessingPlanInputAssembler().assemble(service));

            ProcessingController controller = loadController(service);
            ProcessingWorkspacePane workspace = controller.workspacePaneForTest();
            ProcessingSelectionWorkspaceModel model = service.processingSelectionModel();
            model.beginExecution(plan);

            ProcessingJob jobA = plan.jobs().get(0);
            ProcessingJob jobB = plan.jobs().get(1);
            model.updateJobStatus(jobA.key(), JobExecutionStatus.RUNNING);
            model.updateJobStatus(jobA.key(), successResult(jobA.key(), JobExecutionStatus.SUCCESS,
                    536_827L, 536_827L, 2_076_610L, Duration.ofMillis(14_200), List.of(), catalog("A", "A_Only")));
            model.updateJobStatus(jobB.key(), JobExecutionStatus.RUNNING);
            model.updateJobStatus(jobB.key(), successResult(jobB.key(), JobExecutionStatus.SUCCESS_WITH_WARNINGS,
                    113_886L, 113_886L, 400_000L, Duration.ofMillis(3200),
                    List.of(ProcessingPlanDiagnostic.of(
                            ProcessingPlanDiagnosticCode.FRAME_COUNT_MISMATCH,
                            jobB.channel(),
                            "inventory differed")),
                    catalog("B", "B_Only")));

            MultiSourceProcessingResult batch = new MultiSourceProcessingResult(
                    plan.planId(), Instant.now(), ProjectExecutionStatus.SUCCESS,
                    List.of(model.executionJobs().get(0).result().orElseThrow(),
                            model.executionJobs().get(1).result().orElseThrow()),
                    List.of(), Duration.ofMillis(17_400));
            model.completeExecution(batch);

            assertEquals(ProcessingWorkspacePhase.COMPLETED, workspace.phaseForTest());
            assertSame(workspace, controller.processingHostForTest().getChildren().getFirst());
            assertEquals("Processing Complete", workspace.titleForTest());
            assertTrue(workspace.summaryForTest().contains("2 Sources"), workspace.summaryForTest());
            assertTrue(workspace.summaryForTest().contains("2 Channels"), workspace.summaryForTest());
            assertTrue(workspace.statusStripForTest().contains("Errors: 0"));

            List<ProcessingWorkspacePane.JobView> rows = workspace.jobsTableForTest().getItems();
            assertEquals("COMPLETED", rows.get(0).statusLabel());
            assertEquals("COMPLETED", rows.get(1).statusLabel());
            assertEquals("536,827", rows.get(0).selectedFramesLabel());
            assertEquals("536,827", rows.get(0).dbcMatchedLabel());
            assertEquals("2,076,610", rows.get(0).signalsLabel());
            assertEquals("14.2 s", rows.get(0).elapsedLabel());
            assertTrue(rows.get(0).canViewSignals());
            assertTrue(rows.get(1).canViewSignals());

            workspace.fireViewSignalsForTest(0);
            assertEquals(1, service.signalsModel().catalog().entries().size());
            assertTrue(service.signalsModel().catalog().entries().stream()
                    .allMatch(e -> e.signalName().equals("A_Only")));
            assertEquals("srcA.mf4", service.signalsModel().processingContext().sourceFile());

            workspace.fireViewSignalsForTest(1);
            assertEquals(1, service.signalsModel().catalog().entries().size());
            assertTrue(service.signalsModel().catalog().entries().stream()
                    .allMatch(e -> e.signalName().equals("B_Only")));
            assertTrue(service.signalsModel().catalog().entries().stream()
                    .noneMatch(e -> e.signalName().equals("A_Only")));
            assertEquals("srcB.mf4", service.signalsModel().processingContext().sourceFile());

            assertFalse(workspace.backButtonForTest().isDisable());
            assertFalse(workspace.openAnalysisButtonForTest().isDisable());
            assertTrue(workspace.jobsTableForTest().getPrefHeight() < 280);
        });
    }

    @Test
    @Timeout(30)
    void decisionEnterBuildsReviewedPlanWithoutSelectionPage() throws Exception {
        runOnFx(() -> {
            Path temp = Files.createTempDirectory("proc-ws-enter");
            DesktopAppService service = new DesktopAppService(new DesktopSession(ParserRegistry.load()));
            WorkspaceFile source = addReadySource(service, temp, "ready.mf4", 1, 20L);
            WorkspaceFile definition = addDefinition(service, temp, "map.dbc");
            addConfirmedMapping(service, source, 1, definition.definitionSourceId(), "map.dbc", 1, 1, 1.0);
            service.setDecisionWorkspaceMode(DecisionWorkspaceMode.DETAILED_MAPPING);
            service.enterProcessingWorkspaceFromDecision();
            assertEquals(ProcessingPageMode.PLAN_REVIEW, service.processingSelectionModel().mode());
            assertEquals(1, service.processingSelectionModel().reviewedPlan().jobs().size());
            ProcessingController controller = loadController(service);
            assertSame(controller.workspacePaneForTest(),
                    controller.processingHostForTest().getChildren().getFirst());
            assertEquals(ProcessingWorkspacePhase.REVIEW, controller.workspacePaneForTest().phaseForTest());
            assertTrue(controller.workspacePaneForTest().lookup("#processing-selection-root") == null);
        });
    }

    @Test
    @Timeout(30)
    void sourceDisplayUsesFilenameNotInternalId() throws Exception {
        runOnFx(() -> {
            Path temp = Files.createTempDirectory("proc-ws-name");
            DesktopAppService service = new DesktopAppService(new DesktopSession(ParserRegistry.load()));
            WorkspaceFile source = addReadySource(service, temp, "Vehicle_Test_001.mf4", 1, 40L);
            WorkspaceFile definition = addDefinition(service, temp, "map.dbc");
            addConfirmedMapping(service, source, 1, definition.definitionSourceId(), "map.dbc", 1, 1, 1.0);
            service.setDecisionWorkspaceMode(DecisionWorkspaceMode.DETAILED_MAPPING);
            service.enterProcessingWorkspaceFromDecision();
            ProcessingController controller = loadController(service);
            ProcessingWorkspacePane.JobView row = controller.workspacePaneForTest().jobsTableForTest().getItems().getFirst();
            assertEquals(source.id(), row.jobKey().projectSourceId());
            assertTrue(ProcessingWorkspacePane.looksLikeSourceId(source.id()));
            assertFalse(ProcessingWorkspacePane.looksLikeSourceId(row.sourceAlias()));
            assertFalse(ProcessingWorkspacePane.looksLikeSourceId(row.sourceFile()));
            assertEquals("Vehicle_Test_001.mf4", row.sourceAlias());
            assertEquals("Vehicle_Test_001.mf4", row.sourceFile());
            assertTrue(row.tooltip().contains("Vehicle_Test_001.mf4"));
        });
    }

    @Test
    @Timeout(30)
    void duplicateFilenamesStayIndependentInSourceColumn() throws Exception {
        runOnFx(() -> {
            Path temp = Files.createTempDirectory("proc-ws-dup");
            DesktopAppService service = new DesktopAppService(new DesktopSession(ParserRegistry.load()));
            WorkspaceFile a = addReadySourceIn(service, temp.resolve("left"), "same.mf4", 1, 10L);
            WorkspaceFile b = addReadySourceIn(service, temp.resolve("right"), "same.mf4", 1, 20L);
            WorkspaceFile definition = addDefinition(service, temp, "map.dbc");
            addConfirmedMapping(service, a, 1, definition.definitionSourceId(), "map.dbc", 1, 1, 1.0);
            addConfirmedMapping(service, b, 1, definition.definitionSourceId(), "map.dbc", 1, 1, 1.0);
            service.setDecisionWorkspaceMode(DecisionWorkspaceMode.DETAILED_MAPPING);
            service.enterProcessingWorkspaceFromDecision();
            ProcessingController controller = loadController(service);
            List<ProcessingWorkspacePane.JobView> rows = controller.workspacePaneForTest().jobsTableForTest().getItems();
            assertEquals(2, rows.size());
            assertEquals(a.id(), rows.get(0).jobKey().projectSourceId());
            assertEquals(b.id(), rows.get(1).jobKey().projectSourceId());
            assertEquals("same.mf4", rows.get(0).sourceFile());
            assertEquals("same.mf4", rows.get(1).sourceFile());
            assertEquals("same.mf4", rows.get(0).sourceAlias());
            assertEquals("same.mf4", rows.get(1).sourceAlias());
            assertTrue(rows.get(0).tooltip().contains("left") || rows.get(0).tooltip().contains("same.mf4"));
            assertTrue(rows.get(1).tooltip().contains("right") || rows.get(1).tooltip().contains("same.mf4"));
        });
    }

    @Test
    @Timeout(30)
    void recommendedOnlyShowsEmptyStateAndDisablesRun() throws Exception {
        runOnFx(() -> {
            Path temp = Files.createTempDirectory("proc-ws-empty");
            DesktopAppService service = new DesktopAppService(new DesktopSession(ParserRegistry.load()));
            WorkspaceFile source = addReadySource(service, temp, "rec-only.mf4", 1, 20L);
            WorkspaceFile definition = addDefinition(service, temp, "map.dbc");
            addRecommendedMapping(service, source, 1, definition.definitionSourceId(), "map.dbc", 1, 1, 1.0);
            service.setDecisionWorkspaceMode(DecisionWorkspaceMode.DETAILED_MAPPING);
            service.enterProcessingWorkspaceFromDecision();
            assertTrue(service.processingSelectionModel().reviewedPlan().jobs().isEmpty());
            ProcessingController controller = loadController(service);
            ProcessingWorkspacePane workspace = controller.workspacePaneForTest();
            assertTrue(workspace.jobsTableForTest().getItems().isEmpty());
            assertTrue(workspace.runButtonForTest().isDisable());
            assertTrue(workspace.emptyHintForTest().contains("No channels are ready for processing."));
            assertTrue(workspace.emptyHintForTest().contains("Confirm or choose a DBC"));
            assertTrue(workspace.summaryForTest().startsWith("0 Jobs"));
        });
    }

    @Test
    @Timeout(30)
    void revisitAfterSuccessRestoresResultsWithoutFreshRun() throws Exception {
        runOnFx(() -> {
            Path temp = Files.createTempDirectory("proc-ws-revisit");
            DesktopAppService service = new DesktopAppService(new DesktopSession(ParserRegistry.load()));
            WorkspaceFile sourceA = addReadySource(service, temp, "srcA.mf4", 1, 100L);
            WorkspaceFile sourceB = addReadySource(service, temp, "srcB.mf4", 1, 50L);
            WorkspaceFile definition = addDefinition(service, temp, "map.dbc");
            addConfirmedMapping(service, sourceA, 1, definition.definitionSourceId(), "map.dbc", 1, 1, 1.0);
            addConfirmedMapping(service, sourceB, 1, definition.definitionSourceId(), "map.dbc", 1, 1, 1.0);
            service.setDecisionWorkspaceMode(DecisionWorkspaceMode.DETAILED_MAPPING);
            service.enterProcessingWorkspaceFromDecision();
            ProjectProcessingPlan plan = service.processingSelectionModel().reviewedPlan();
            IndependentJobResult a = storedResult(plan.jobs().get(0), JobExecutionStatus.SUCCESS,
                    536_827L, 536_827L, 100L, Duration.ofMillis(1400), catalog("A", "A_Only"));
            IndependentJobResult b = storedResult(plan.jobs().get(1), JobExecutionStatus.SUCCESS_WITH_WARNINGS,
                    113_886L, 113_886L, 40L, Duration.ofMillis(800), catalog("B", "B_Only"));
            service.processingResultRegistry().publishJob(a);
            service.processingResultRegistry().publishJob(b);

            service.setDecisionWorkspaceMode(DecisionWorkspaceMode.DETAILED_MAPPING);
            service.enterProcessingWorkspaceFromDecision();
            ProcessingController first = loadController(service);
            ProcessingWorkspacePane workspace = first.workspacePaneForTest();
            assertEquals(ProcessingWorkspacePhase.COMPLETED, workspace.phaseForTest());
            assertEquals("Processing Complete", workspace.titleForTest());
            assertFalse(workspace.runButtonForTest().isVisible());
            assertTrue(workspace.rerunButtonForTest().isVisible());
            List<ProcessingWorkspacePane.JobView> rows = workspace.jobsTableForTest().getItems();
            assertEquals(2, rows.size());
            assertEquals("COMPLETED", rows.get(0).statusLabel());
            assertEquals("COMPLETED", rows.get(1).statusLabel());
            assertTrue(rows.get(0).canViewSignals());
            assertTrue(rows.get(1).canViewSignals());
            assertEquals("536,827", rows.get(0).selectedFramesLabel());
            assertEquals("113,886", rows.get(1).selectedFramesLabel());

            ProcessingController recreated = loadController(service);
            assertTrue(first != recreated);
            ProcessingWorkspacePane restored = recreated.workspacePaneForTest();
            assertEquals(ProcessingWorkspacePhase.COMPLETED, restored.phaseForTest());
            assertFalse(restored.runButtonForTest().isVisible());
            assertTrue(restored.rerunButtonForTest().isVisible());
            assertTrue(restored.jobsTableForTest().getItems().getFirst().canViewSignals());
            restored.fireViewSignalsForTest(0);
            assertTrue(service.signalsModel().catalog().entries().stream()
                    .allMatch(e -> e.signalName().equals("A_Only")));
        });
    }

    @Test
    @Timeout(30)
    void runningRevisitKeepsExecutionAndBlocksDuplicateRun() throws Exception {
        runOnFx(() -> {
            Path temp = Files.createTempDirectory("proc-ws-running");
            DesktopAppService service = new DesktopAppService(new DesktopSession(ParserRegistry.load()));
            WorkspaceFile source = addReadySource(service, temp, "run.mf4", 1, 20L);
            WorkspaceFile definition = addDefinition(service, temp, "map.dbc");
            addConfirmedMapping(service, source, 1, definition.definitionSourceId(), "map.dbc", 1, 1, 1.0);
            service.setDecisionWorkspaceMode(DecisionWorkspaceMode.DETAILED_MAPPING);
            service.enterProcessingWorkspaceFromDecision();
            service.processingSelectionModel().beginExecution(service.processingSelectionModel().reviewedPlan());
            ProcessingController controller = loadController(service);
            ProcessingWorkspacePane workspace = controller.workspacePaneForTest();
            assertEquals(ProcessingWorkspacePhase.RUNNING, workspace.phaseForTest());
            assertFalse(workspace.runButtonForTest().isVisible());
            assertTrue(workspace.backButtonForTest().isDisable());
            service.executeReviewedPlan(r -> {
            }, () -> {
            });
            assertEquals(ProcessingPageMode.EXECUTING, service.processingSelectionModel().mode());
            assertEquals(JobExecutionStatus.QUEUED,
                    service.processingSelectionModel().executionJobs().getFirst().status());
        });
    }

    @Test
    @Timeout(30)
    void oneChangedJobStaysReadyAndDoesNotStealOtherResult() throws Exception {
        runOnFx(() -> {
            Path temp = Files.createTempDirectory("proc-ws-changed");
            DesktopAppService service = new DesktopAppService(new DesktopSession(ParserRegistry.load()));
            WorkspaceFile sourceA = addReadySource(service, temp, "srcA.mf4", 1, 100L);
            WorkspaceFile sourceB = addReadySource(service, temp, "srcB.mf4", 1, 50L);
            WorkspaceFile def1 = addDefinition(service, temp, "one.dbc");
            WorkspaceFile def2 = addDefinition(service, temp, "two.dbc");
            addConfirmedMapping(service, sourceA, 1, def1.definitionSourceId(), "one.dbc", 1, 1, 1.0);
            addConfirmedMapping(service, sourceB, 1, def1.definitionSourceId(), "one.dbc", 1, 1, 1.0);
            service.setDecisionWorkspaceMode(DecisionWorkspaceMode.DETAILED_MAPPING);
            service.enterProcessingWorkspaceFromDecision();
            ProjectProcessingPlan plan = service.processingSelectionModel().reviewedPlan();
            service.processingResultRegistry().publishJob(
                    storedResult(plan.jobs().get(0), JobExecutionStatus.SUCCESS,
                            100L, 100L, 2L, Duration.ofMillis(10), catalog("A", "Keep_A")));
            service.processingResultRegistry().publishJob(
                    storedResult(plan.jobs().get(1), JobExecutionStatus.SUCCESS,
                            50L, 50L, 2L, Duration.ofMillis(10), catalog("B", "Old_B")));

            addConfirmedMapping(service, sourceB, 1, def2.definitionSourceId(), "two.dbc", 1, 1, 1.0);
            service.setDecisionWorkspaceMode(DecisionWorkspaceMode.DETAILED_MAPPING);
            service.enterProcessingWorkspaceFromDecision();
            ProcessingController controller = loadController(service);
            ProcessingWorkspacePane workspace = controller.workspacePaneForTest();
            assertEquals(ProcessingWorkspacePhase.PARTIAL, workspace.phaseForTest());
            assertTrue(workspace.runButtonForTest().isVisible());
            assertFalse(workspace.rerunButtonForTest().isVisible());
            List<ProcessingWorkspacePane.JobView> rows = workspace.jobsTableForTest().getItems();
            assertEquals(2, rows.size());
            ProcessingWorkspacePane.JobView rowA = rows.stream()
                    .filter(row -> sourceA.id().equals(row.jobKey().projectSourceId()))
                    .findFirst()
                    .orElseThrow();
            ProcessingWorkspacePane.JobView rowB = rows.stream()
                    .filter(row -> sourceB.id().equals(row.jobKey().projectSourceId()))
                    .findFirst()
                    .orElseThrow();
            assertEquals("COMPLETED", rowA.statusLabel());
            assertTrue(rowA.canViewSignals());
            assertEquals("Ready", rowB.statusLabel());
            assertFalse(rowB.canViewSignals());
            assertEquals("two.dbc", rowB.dbcLabel());
        });
    }

    @Test
    @Timeout(30)
    void quickCompletedResultIsSourceCentricAndConsolidatesMultipleDbcs() throws Exception {
        runOnFx(() -> {
            Path temp = Files.createTempDirectory("proc-ws-quick-source-result");
            DesktopAppService service = new DesktopAppService(new DesktopSession(ParserRegistry.load()));

            Path sourcePath = temp.resolve("multi-channel.mf4");
            Files.writeString(sourcePath, "stub");
            WorkspaceFile source = new WorkspaceFile(sourcePath, FileKind.MEASUREMENT_MF4, 10);
            service.workspace().addFile(source);
            List<CanChannelInventory> channels = List.of(
                    new CanChannelInventory(1, "Channel 1", 100L, 1, 1, 0, 0,
                            List.of(new CanMessageInventory(new CanMessageKey(1, 0x101L, false), 100, 8, 8, false))),
                    new CanChannelInventory(2, "Channel 2", 200L, 1, 1, 0, 0,
                            List.of(new CanMessageInventory(new CanMessageKey(2, 0x102L, false), 200, 8, 8, false)))
            );
            service.sourceInventoryModel().put(source.id(), new CanSourceInventory(
                    sourcePath, source.fileName(), MeasurementSourceFormat.MF4, CanInventoryStatus.INVENTORY_READY,
                    2, 300L, 2, true, channels, List.of()));

            WorkspaceFile body = addDefinition(service, temp, "body.dbc");
            WorkspaceFile powertrain = addDefinition(service, temp, "powertrain.dbc");
            addConfirmedMapping(service, source, 1, body.definitionSourceId(), "body.dbc", 1, 1, 1.0);
            addConfirmedMapping(service, source, 2, powertrain.definitionSourceId(), "powertrain.dbc", 1, 1, 1.0);

            ProcessingSelectionWorkspaceModel model = service.processingSelectionModel();
            model.captureDecisionOrigin(DecisionWorkspaceMode.QUICK_DECODE);
            model.select(SourceChannelRef.of(source.id(), 1));
            model.select(SourceChannelRef.of(source.id(), 2));
            model.reviewPlan(new com.stellar.platform.desktop.presentation.ProcessingPlanInputAssembler().assemble(service));
            ProjectProcessingPlan plan = model.reviewedPlan();
            assertEquals(2, plan.jobs().size());
            model.beginExecution(plan);

            IndependentJobResult ch1 = storedResult(plan.jobs().get(0), JobExecutionStatus.SUCCESS,
                    100L, 90L, 10L, Duration.ofMillis(10), catalog("A", "Speed"));
            IndependentJobResult ch2 = storedResult(plan.jobs().get(1), JobExecutionStatus.SUCCESS_WITH_WARNINGS,
                    200L, 150L, 20L, Duration.ofMillis(12), catalog("A", "Torque"));
            model.completeExecution(new MultiSourceProcessingResult(
                    plan.planId(), Instant.now(), ProjectExecutionStatus.SUCCESS,
                    List.of(ch1, ch2), List.of(), Duration.ofMillis(20)));

            ProcessingController controller = loadController(service);
            ProcessingWorkspacePane workspace = controller.workspacePaneForTest();
            assertTrue(workspace.usesQuickSourceResultPresentationForTest());
            assertFalse(workspace.jobsTableForTest().isVisible());
            assertTrue(workspace.quickSourceTableForTest().isVisible());
            assertEquals(1, workspace.quickSourceTableForTest().getItems().size());

            ProcessingWorkspacePane.QuickSourceView row = workspace.quickSourceTableForTest().getItems().getFirst();
            assertEquals("2", row.channelsLabel());
            assertTrue(row.definitionsLabel().contains("body.dbc"));
            assertTrue(row.definitionsLabel().contains("powertrain.dbc"));
            assertEquals("300", row.selectedFramesLabel());
            assertEquals("240", row.dbcMatchedLabel());
            assertEquals("30", row.decodedSamplesLabel());
            assertEquals("COMPLETED", row.resultLabel());
            assertTrue(row.canViewSignals());

            workspace.fireViewQuickSourceSignalsForTest(0);
            assertEquals(2, service.signalsModel().catalog().size());
            assertTrue(service.signalsModel().processingContext().mode()
                    .equals(com.stellar.platform.desktop.presentation.SignalsProcessingContext.MODE_QUICK_DECODE));
        });
    }

    private static IndependentJobResult storedResult(
            ProcessingJob job,
            JobExecutionStatus status,
            long frames,
            long matched,
            long signals,
            Duration elapsed,
            InMemorySignalCatalogStore catalog
    ) {
        return new IndependentJobResult(
                job.key(),
                job.definitionSourceId().orElse(null),
                job.definitionDisplayName(),
                job.mappingLifecycle(),
                status,
                frames, frames, frames, matched, 0, signals,
                null, null, null, elapsed, List.of(), "", catalog);
    }

    private static IndependentJobResult successResult(
            ProcessingJobKey key,
            JobExecutionStatus status,
            long frames,
            long matched,
            long signals,
            Duration elapsed,
            List<ProcessingPlanDiagnostic> diagnostics,
            InMemorySignalCatalogStore catalog
    ) {
        return new IndependentJobResult(
                key, "d", "map.dbc", MappingLifecycle.CONFIRMED, status,
                frames, frames, frames, matched, 0, signals,
                1L, 2L, null, elapsed, diagnostics, "", catalog);
    }

    private static InMemorySignalCatalogStore catalog(String source, String signalName) {
        InMemorySignalCatalogStore store = new InMemorySignalCatalogStore(source, "map.dbc");
        store.accept(List.of(new CanonicalSignal(
                signalName, "Msg", 10L, 0x10, 1, 1.0, 1L, "u", null, SourceReference.ofLine(1))));
        return store;
    }

    private static ProcessingController loadController(DesktopAppService service) throws Exception {
        FXMLLoader loader = new FXMLLoader(ProcessingWorkspaceUiTest.class.getResource("/fxml/processing-view.fxml"));
        Parent root = loader.load();
        ProcessingController controller = loader.getController();
        controller.setAppService(service, null);
        Scene scene = new Scene(root, 1600, 900);
        var css = ProcessingWorkspaceUiTest.class.getResource("/css/stellar-desktop.css");
        if (css != null) {
            scene.getStylesheets().add(css.toExternalForm());
        }
        Stage stage = new Stage();
        stage.setScene(scene);
        stage.setWidth(1600);
        stage.setHeight(900);
        stage.show();
        root.applyCss();
        root.layout();
        return controller;
    }

    private static WorkspaceFile addReadySource(
            DesktopAppService service, Path temp, String name, int channelId, long frames
    ) throws Exception {
        return addReadySourceIn(service, temp, name, channelId, frames);
    }

    private static WorkspaceFile addReadySourceIn(
            DesktopAppService service, Path dir, String name, int channelId, long frames
    ) throws Exception {
        Files.createDirectories(dir);
        Path path = dir.resolve(name);
        Files.writeString(path, "stub");
        WorkspaceFile file = new WorkspaceFile(path, FileKind.MEASUREMENT_MF4, 10);
        service.workspace().addFile(file);
        List<CanChannelInventory> channelList = new ArrayList<>();
        channelList.add(new CanChannelInventory(
                channelId, "Channel " + channelId, frames, 31, 31, 0, 0,
                List.of(new CanMessageInventory(new CanMessageKey(channelId, 0x100L, false), 10, 8, 8, false))));
        CanSourceInventory inventory = new CanSourceInventory(
                path, name, MeasurementSourceFormat.MF4, CanInventoryStatus.INVENTORY_READY,
                1, frames, 31, true, channelList, List.of());
        service.sourceInventoryModel().put(file.id(), inventory);
        return file;
    }

    private static WorkspaceFile addDefinition(DesktopAppService service, Path temp, String name) throws Exception {
        Path path = temp.resolve(name);
        Files.writeString(path, "VERSION \"\"\n");
        WorkspaceFile file = new WorkspaceFile(path, FileKind.DEFINITION_DBC, 10);
        service.workspace().addFile(file);
        return file;
    }

    private static void addConfirmedMapping(
            DesktopAppService service,
            WorkspaceFile measurement,
            int channelId,
            DefinitionSourceId defId,
            String dbcName,
            long observed,
            long matched,
            double coverage
    ) {
        service.definitionMatchingModel().putMapping(new SourceChannelMappingEntry(
                SourceChannelKey.of(measurement.projectSourceId(), channelId),
                ChannelMappingStatus.CONFIRMED,
                ChannelRecommendationKind.CLEAR_BEST_MATCH,
                defId, dbcName, defId, dbcName, observed, matched, coverage));
    }

    private static void addRecommendedMapping(
            DesktopAppService service,
            WorkspaceFile measurement,
            int channelId,
            DefinitionSourceId defId,
            String dbcName,
            long observed,
            long matched,
            double coverage
    ) {
        service.definitionMatchingModel().putMapping(new SourceChannelMappingEntry(
                SourceChannelKey.of(measurement.projectSourceId(), channelId),
                ChannelMappingStatus.RECOMMENDED,
                ChannelRecommendationKind.CLEAR_BEST_MATCH,
                defId, dbcName, null, "", observed, matched, coverage));
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
