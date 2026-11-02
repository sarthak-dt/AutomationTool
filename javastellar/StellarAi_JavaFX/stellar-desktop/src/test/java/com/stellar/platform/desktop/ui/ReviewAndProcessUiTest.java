package com.stellar.platform.desktop.ui;

import com.stellar.platform.desktop.presentation.DecisionWorkspaceMode;
import com.stellar.platform.desktop.presentation.DesktopAppService;
import com.stellar.platform.desktop.presentation.ProcessingReviewSummary;
import com.stellar.platform.desktop.presentation.ProcessingWorkspacePhase;
import com.stellar.platform.desktop.presentation.ReviewIssueSeverity;
import com.stellar.platform.desktop.presentation.WorkspaceRoute;
import com.stellar.platform.desktop.project.ChannelMappingStatus;
import com.stellar.platform.desktop.project.ChannelRecommendationKind;
import com.stellar.platform.desktop.project.DefinitionSourceId;
import com.stellar.platform.desktop.project.FileKind;
import com.stellar.platform.desktop.project.FileValidationStatus;
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
import com.stellar.platform.processing.ParserRegistry;
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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 3 — Review &amp; Process is a read-only checkpoint whose content and readiness come from
 * existing application state.
 */
class ReviewAndProcessUiTest {

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
    void reviewRouteInitializesReviewPhaseAndShowsActualSourceScope() throws Exception {
        runOnFx(() -> {
            Path temp = Files.createTempDirectory("review-scope");
            DesktopAppService service = newService();
            WorkspaceFile a = addReadySource(service, temp, "run01.mf4", 1, 100L);
            WorkspaceFile b = addReadySource(service, temp, "run02.mf4", 1, 200L);
            WorkspaceFile dbc = addDefinition(service, temp, "chassis.dbc");
            confirm(service, a, 1, dbc.definitionSourceId());
            confirm(service, b, 1, dbc.definitionSourceId());
            service.enterProcessingWorkspaceFromDecision();

            ProcessingController controller = loadController(service);
            controller.prepareForRoute(WorkspaceRoute.REVIEW_AND_PROCESS);
            ProcessingWorkspacePane pane = controller.workspacePaneForTest();

            assertEquals(ProcessingWorkspacePhase.REVIEW, pane.phaseForTest());
            assertEquals("Review & Process", pane.titleForTest());

            ProcessingReviewSummaryPane review = pane.reviewSummaryPaneForTest();
            assertTrue(review.isVisible(), "review summary must be shown at the checkpoint");
            assertTrue(review.sourcesTextForTest().contains("2 measurement files"),
                    review.sourcesTextForTest());
            assertTrue(review.scopeTextForTest().contains("2 files"), review.scopeTextForTest());
            assertTrue(review.scopeTextForTest().contains("2 jobs"), review.scopeTextForTest());
            assertEquals("Ready to process", review.readinessTextForTest());
        });
    }

    @Test
    @Timeout(60)
    void autoDecodeNoDbcReviewDoesNotClaimDbcConfigured() throws Exception {
        runOnFx(() -> {
            Path temp = Files.createTempDirectory("review-no-dbc");
            DesktopAppService service = newService();
            service.setDecisionWorkspaceMode(DecisionWorkspaceMode.QUICK_DECODE);
            WorkspaceFile source = addReadySource(service, temp, "drive.mf4", 1, 128_788L);
            service.definitionMatchingModel().putMapping(new SourceChannelMappingEntry(
                    SourceChannelKey.of(source.projectSourceId(), 1),
                    ChannelMappingStatus.UNMAPPED, ChannelRecommendationKind.NO_MATCH,
                    null, "", null, "", 3, 0, 0.0));
            service.enterProcessingWorkspaceFromDecision();

            ProcessingController controller = loadController(service);
            controller.prepareForRoute(WorkspaceRoute.REVIEW_AND_PROCESS);
            ProcessingReviewSummaryPane review =
                    controller.workspacePaneForTest().reviewSummaryPaneForTest();
            ProcessingReviewSummary summary = review.summaryForTest();

            assertTrue(summary.readyToProcess(), summary.issues().toString());
            assertEquals("Ready with warnings", review.readinessTextForTest());
            String decode = review.decodeTextForTest();
            assertTrue(decode.contains("DBC: none"), decode);
            assertTrue(decode.contains("native/raw outputs only"), decode);
            assertFalse(decode.toLowerCase().contains("dbc configured"), decode);
            assertTrue(review.issueTextsForTest().stream().anyMatch(text ->
                    text.contains("No matching DBC") && text.startsWith("Warning")));
            assertFalse(review.hasBlockingIssueForTest());
            assertFalse(controller.workspacePaneForTest().runButtonForTest().isDisable());
        });
    }

    @Test
    @Timeout(60)
    void reviewIsReadOnlyAndOffersOnlyEditNavigation() throws Exception {
        runOnFx(() -> {
            Path temp = Files.createTempDirectory("review-readonly");
            DesktopAppService service = newService();
            WorkspaceFile a = addReadySource(service, temp, "run01.mf4", 1, 100L);
            WorkspaceFile dbc = addDefinition(service, temp, "chassis.dbc");
            confirm(service, a, 1, dbc.definitionSourceId());
            service.enterProcessingWorkspaceFromDecision();

            ProcessingController controller = loadController(service);
            controller.prepareForRoute(WorkspaceRoute.REVIEW_AND_PROCESS);
            ProcessingWorkspacePane pane = controller.workspacePaneForTest();
            ProcessingReviewSummaryPane review = pane.reviewSummaryPaneForTest();

            String mappingsBefore = service.definitionMatchingModel().allMappings().toString();
            DecisionWorkspaceMode modeBefore = service.decisionWorkspaceMode();
            int jobsBefore = service.processingSelectionModel().reviewedPlan().jobs().size();

            // Opening the checkpoint repeatedly must not change any configuration.
            controller.prepareForRoute(WorkspaceRoute.REVIEW_AND_PROCESS);
            controller.prepareForRoute(WorkspaceRoute.REVIEW_AND_PROCESS);

            assertEquals(mappingsBefore, service.definitionMatchingModel().allMappings().toString());
            assertEquals(modeBefore, service.decisionWorkspaceMode());
            assertEquals(jobsBefore, service.processingSelectionModel().reviewedPlan().jobs().size());

            assertNotNull(review.editSourcesButtonForTest());
            assertNotNull(review.editDecodeButtonForTest());
            assertEquals("Edit Sources", review.editSourcesButtonForTest().getText());
            assertEquals("Edit Decode", review.editDecodeButtonForTest().getText());
        });
    }

    @Test
    @Timeout(60)
    void reviewShowsAutoDecodeSummaryWithIgnoredCount() throws Exception {
        runOnFx(() -> {
            Path temp = Files.createTempDirectory("review-auto");
            DesktopAppService service = newService();
            service.setDecisionWorkspaceMode(DecisionWorkspaceMode.QUICK_DECODE);
            WorkspaceFile a = addReadySource(service, temp, "run01.mf4", 1, 100L);
            WorkspaceFile b = addReadySource(service, temp, "run02.mf4", 2, 200L);
            WorkspaceFile dbc = addDefinition(service, temp, "chassis.dbc");
            confirm(service, a, 1, dbc.definitionSourceId());
            confirm(service, b, 2, dbc.definitionSourceId());
            service.quickDecodeSelectionModel().setIncluded(
                    SourceChannelKey.of(b.projectSourceId(), 2), false);
            service.enterProcessingWorkspaceFromDecision();

            ProcessingController controller = loadController(service);
            controller.prepareForRoute(WorkspaceRoute.REVIEW_AND_PROCESS);
            ProcessingReviewSummaryPane review =
                    controller.workspacePaneForTest().reviewSummaryPaneForTest();

            String decode = review.decodeTextForTest();
            assertTrue(decode.startsWith("Auto Decode"), decode);
            assertTrue(decode.contains("1 DBC definition"), decode);
            assertTrue(decode.contains("1 ignored"), decode);
        });
    }

    @Test
    @Timeout(60)
    void customReviewDoesNotInventIgnoredCount() throws Exception {
        runOnFx(() -> {
            Path temp = Files.createTempDirectory("review-custom");
            DesktopAppService service = newService();
            service.setDecisionWorkspaceMode(DecisionWorkspaceMode.DETAILED_MAPPING);
            WorkspaceFile a = addReadySource(service, temp, "run01.mf4", 1, 100L);
            WorkspaceFile dbc = addDefinition(service, temp, "chassis.dbc");
            confirm(service, a, 1, dbc.definitionSourceId());
            // An excluded Auto selection must not leak into the Custom summary: Custom Decode has
            // no exclusion semantics in the backend.
            service.quickDecodeSelectionModel().setIncluded(
                    SourceChannelKey.of(a.projectSourceId(), 1), false);
            service.enterProcessingWorkspaceFromDecision();

            ProcessingController controller = loadController(service);
            controller.prepareForRoute(WorkspaceRoute.REVIEW_AND_PROCESS);
            ProcessingReviewSummaryPane review =
                    controller.workspacePaneForTest().reviewSummaryPaneForTest();

            String decode = review.decodeTextForTest();
            assertTrue(decode.startsWith("Custom Decode"), decode);
            assertFalse(decode.contains("ignored"), decode);
            assertFalse(review.summaryForTest().reportsIgnoredChannels());
        });
    }

    @Test
    @Timeout(60)
    void nativeOnlyNoMatchAllowsStartProcessing() throws Exception {
        runOnFx(() -> {
            Path temp = Files.createTempDirectory("review-native");
            DesktopAppService service = newService();
            service.setDecisionWorkspaceMode(DecisionWorkspaceMode.DETAILED_MAPPING);
            WorkspaceFile a = addReadySource(service, temp, "run01.mf4", 1, 100L);
            service.definitionMatchingModel().putMapping(new SourceChannelMappingEntry(
                    SourceChannelKey.of(a.projectSourceId(), 1),
                    ChannelMappingStatus.NO_MATCH,
                    ChannelRecommendationKind.NO_MATCH,
                    null, "", null, "", 4, 0, 0.0));

            ProcessingController controller = loadController(service);
            controller.prepareForRoute(WorkspaceRoute.REVIEW_AND_PROCESS);
            ProcessingWorkspacePane pane = controller.workspacePaneForTest();
            ProcessingReviewSummaryPane review = pane.reviewSummaryPaneForTest();

            assertFalse(review.hasBlockingIssueForTest(),
                    "native-only No Match is processable: " + review.issueTextsForTest());
            assertFalse(pane.runButtonForTest().isDisable(),
                    "Start Processing stays enabled for a valid native-only channel");
        });
    }

    @Test
    @Timeout(60)
    void unresolvedAmbiguousDecodeDisablesStartProcessing() throws Exception {
        runOnFx(() -> {
            Path temp = Files.createTempDirectory("review-blocking");
            DesktopAppService service = newService();
            service.setDecisionWorkspaceMode(DecisionWorkspaceMode.DETAILED_MAPPING);
            WorkspaceFile a = addReadySource(service, temp, "run01.mf4", 1, 100L);
            service.definitionMatchingModel().putMapping(new SourceChannelMappingEntry(
                    SourceChannelKey.of(a.projectSourceId(), 1),
                    ChannelMappingStatus.AMBIGUOUS,
                    ChannelRecommendationKind.AMBIGUOUS,
                    null, "", null, "", 4, 0, 0.0));

            ProcessingController controller = loadController(service);
            controller.prepareForRoute(WorkspaceRoute.REVIEW_AND_PROCESS);
            ProcessingWorkspacePane pane = controller.workspacePaneForTest();
            ProcessingReviewSummaryPane review = pane.reviewSummaryPaneForTest();

            assertTrue(review.hasBlockingIssueForTest(),
                    "ambiguous unresolved decode must block: " + review.issueTextsForTest());
            assertTrue(pane.runButtonForTest().isDisable(),
                    "Start Processing must be disabled while a blocking issue exists");
        });
    }

    @Test
    @Timeout(60)
    void warningDoesNotDisableStartProcessing() throws Exception {
        runOnFx(() -> {
            Path temp = Files.createTempDirectory("review-warning");
            DesktopAppService service = newService();
            WorkspaceFile good = addReadySource(service, temp, "run01.mf4", 1, 100L);
            WorkspaceFile dbc = addDefinition(service, temp, "chassis.dbc");
            confirm(service, good, 1, dbc.definitionSourceId());

            // An unreadable extra source is a warning: it will be skipped, but the usable source
            // still carries the run.
            WorkspaceFile broken = addReadySource(service, temp, "run02.mf4", 1, 50L);
            broken.setStatus(FileValidationStatus.INVALID, "unreadable");
            service.definitionMatchingModel().putMapping(new SourceChannelMappingEntry(
                    SourceChannelKey.of(broken.projectSourceId(), 1),
                    ChannelMappingStatus.NOT_APPLICABLE,
                    ChannelRecommendationKind.NO_MATCH,
                    null, "", null, "", 4, 0, 0.0));
            service.enterProcessingWorkspaceFromDecision();

            ProcessingController controller = loadController(service);
            controller.prepareForRoute(WorkspaceRoute.REVIEW_AND_PROCESS);
            ProcessingWorkspacePane pane = controller.workspacePaneForTest();
            ProcessingReviewSummary summary = pane.reviewSummaryPaneForTest().summaryForTest();

            assertTrue(summary.issues().stream()
                            .anyMatch(issue -> issue.severity() == ReviewIssueSeverity.WARNING),
                    "expected a non-blocking finding: " + summary.issues());
            assertTrue(summary.blockingIssues().isEmpty(), summary.issues().toString());
            assertTrue(summary.readyToProcess());
            assertFalse(pane.runButtonForTest().isDisable());
        });
    }

    @Test
    @Timeout(60)
    void startProcessingIsTheSinglePrimaryAction() throws Exception {
        runOnFx(() -> {
            Path temp = Files.createTempDirectory("review-cta");
            DesktopAppService service = newService();
            WorkspaceFile a = addReadySource(service, temp, "run01.mf4", 1, 100L);
            WorkspaceFile dbc = addDefinition(service, temp, "chassis.dbc");
            confirm(service, a, 1, dbc.definitionSourceId());
            service.enterProcessingWorkspaceFromDecision();

            ProcessingController controller = loadController(service);
            controller.prepareForRoute(WorkspaceRoute.REVIEW_AND_PROCESS);
            ProcessingWorkspacePane pane = controller.workspacePaneForTest();

            assertEquals("Start Processing", pane.runButtonForTest().getText());
            assertTrue(pane.runButtonForTest().isVisible());
            // No competing primary verbs on the checkpoint.
            assertFalse(pane.rerunButtonForTest().isVisible());
            assertFalse(pane.cancelButtonForTest().isVisible());
            assertFalse(pane.retryFailedButtonForTest().isVisible());
        });
    }

    @Test
    @Timeout(60)
    void severityClassificationIsPresentationOnly() {
        assertTrue(ReviewIssueSeverity.BLOCKING.blocking());
        assertFalse(ReviewIssueSeverity.WARNING.blocking());
        assertFalse(ReviewIssueSeverity.INFORMATION.blocking());
    }

    private static DesktopAppService newService() {
        return new DesktopAppService(new DesktopSession(ParserRegistry.load()));
    }

    private static ProcessingController loadController(DesktopAppService service) throws Exception {
        FXMLLoader loader = new FXMLLoader(ReviewAndProcessUiTest.class.getResource("/fxml/processing-view.fxml"));
        Parent root = loader.load();
        ProcessingController controller = loader.getController();
        controller.setAppService(service, null);
        Scene scene = new Scene(root, 1600, 900);
        var css = ReviewAndProcessUiTest.class.getResource("/css/stellar-desktop.css");
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
        List<CanChannelInventory> channels = new ArrayList<>();
        channels.add(new CanChannelInventory(
                channelId, "Channel " + channelId, frames, 31, 31, 0, 0,
                List.of(new CanMessageInventory(new CanMessageKey(channelId, 0x100L, false), 10, 8, 8, false))));
        service.sourceInventoryModel().put(file.id(), new CanSourceInventory(
                path, name, MeasurementSourceFormat.MF4, CanInventoryStatus.INVENTORY_READY,
                1, frames, 31, true, channels, List.of()));
        return file;
    }

    private static WorkspaceFile addDefinition(DesktopAppService service, Path dir, String name) throws Exception {
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
                ChannelMappingStatus.CONFIRMED,
                ChannelRecommendationKind.CLEAR_BEST_MATCH,
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
