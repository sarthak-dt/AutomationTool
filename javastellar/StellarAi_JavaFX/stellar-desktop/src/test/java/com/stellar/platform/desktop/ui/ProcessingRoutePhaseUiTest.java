package com.stellar.platform.desktop.ui;

import com.stellar.platform.desktop.presentation.DesktopAppService;
import com.stellar.platform.desktop.presentation.ProcessingPageMode;
import com.stellar.platform.desktop.presentation.ProcessingRoutePhases;
import com.stellar.platform.desktop.presentation.ProcessingRunStatuses;
import com.stellar.platform.desktop.presentation.ProcessingWorkspacePhase;
import com.stellar.platform.desktop.presentation.WorkspaceRoute;
import com.stellar.platform.desktop.session.DesktopSession;
import com.stellar.platform.domain.MeasurementSourceFormat;
import com.stellar.platform.domain.inventory.CanChannelInventory;
import com.stellar.platform.domain.inventory.CanInventoryStatus;
import com.stellar.platform.domain.inventory.CanMessageInventory;
import com.stellar.platform.domain.inventory.CanMessageKey;
import com.stellar.platform.domain.inventory.CanSourceInventory;
import com.stellar.platform.processing.ParserRegistry;
import com.stellar.platform.storage.metadata.ChannelMappingMetadata;
import com.stellar.platform.storage.metadata.ProcessingRunMetadata;
import com.stellar.platform.storage.metadata.ProjectFileMetadata;
import com.stellar.platform.storage.metadata.ProjectMetadata;
import com.stellar.platform.storage.metadata.ProjectMetadataStore;
import com.stellar.platform.storage.metadata.SavedProjectSnapshot;
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
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 3 — the three transient processing routes must open visibly different states rather than
 * whichever phase the model happened to be left in.
 */
class ProcessingRoutePhaseUiTest {

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
    void routeIntentIsAnExplicitMappingNotStringMatching() {
        assertEquals(Optional.of(ProcessingWorkspacePhase.REVIEW),
                ProcessingRoutePhases.intentOf(WorkspaceRoute.REVIEW_AND_PROCESS));
        assertEquals(Optional.of(ProcessingWorkspacePhase.INTERRUPTED),
                ProcessingRoutePhases.intentOf(WorkspaceRoute.PROCESSING_RESUME));
        assertEquals(Optional.of(ProcessingWorkspacePhase.COMPLETED),
                ProcessingRoutePhases.intentOf(WorkspaceRoute.PROCESSING_RESULT));
        assertTrue(ProcessingRoutePhases.intentOf(WorkspaceRoute.SOURCES).isEmpty());
        assertTrue(ProcessingRoutePhases.intentOf(null).isEmpty());
    }

    @Test
    void liveExecutionAlwaysWinsOverRouteIntent() {
        assertEquals(ProcessingWorkspacePhase.RUNNING, ProcessingRoutePhases.resolve(
                WorkspaceRoute.PROCESSING_RESULT, ProcessingPageMode.EXECUTING, false));
        assertEquals(ProcessingWorkspacePhase.RUNNING, ProcessingRoutePhases.resolve(
                WorkspaceRoute.PROCESSING_RESUME, ProcessingPageMode.EXECUTING, true));
    }

    @Test
    void outstandingWorkResolvesToInterruptedButFinishedWorkDoesNot() {
        assertEquals(ProcessingWorkspacePhase.INTERRUPTED,
                ProcessingWorkspacePhase.of(ProcessingPageMode.PLAN_REVIEW, true));
        assertEquals(ProcessingWorkspacePhase.INTERRUPTED,
                ProcessingWorkspacePhase.of(ProcessingPageMode.PARTIAL, true));
        assertEquals(ProcessingWorkspacePhase.COMPLETED,
                ProcessingWorkspacePhase.of(ProcessingPageMode.EXECUTION_RESULT, true));
        assertEquals(ProcessingWorkspacePhase.REVIEW,
                ProcessingWorkspacePhase.of(ProcessingPageMode.PLAN_REVIEW, false));
    }

    @Test
    @Timeout(60)
    void interruptedRunOpensResumePhaseAndDoesNotAutoStart() throws Exception {
        runOnFx(() -> {
            DesktopAppService service = openProjectWithRun(ProcessingRunStatuses.RUNNING);
            ProcessingController controller = loadController(service);

            controller.prepareForRoute(WorkspaceRoute.PROCESSING_RESUME);
            ProcessingWorkspacePane pane = controller.workspacePaneForTest();

            assertEquals(ProcessingWorkspacePhase.INTERRUPTED, pane.phaseForTest());
            assertEquals("Processing Interrupted", pane.titleForTest());
            assertEquals("Resume Processing", pane.runButtonForTest().getText());
            assertEquals("Review Setup", pane.backButtonForTest().getText());
            assertFalse(service.isPlanExecutionActive(),
                    "opening the resume page must not start execution by itself");
            service.shutdown();
        });
    }

    @Test
    @Timeout(60)
    void failedRunOpensResultStateWithoutHidingTheFailure() throws Exception {
        runOnFx(() -> {
            DesktopAppService service = openProjectWithRun(ProcessingRunStatuses.COMPLETED_WITH_FAILURES);
            ProcessingController controller = loadController(service);

            controller.prepareForRoute(WorkspaceRoute.PROCESSING_RESULT);
            ProcessingWorkspacePane pane = controller.workspacePaneForTest();

            assertTrue(service.lastProcessingRunOutcome().failed());
            assertFalse(pane.phaseForTest().active(),
                    "a finished run must not present itself as still running");
            assertFalse(service.isPlanExecutionActive());
            service.shutdown();
        });
    }

    @Test
    @Timeout(60)
    void reviewRouteReturnsToTheCheckpointFromAMaterializedResult() throws Exception {
        runOnFx(() -> {
            DesktopAppService service = openProjectWithRun(ProcessingRunStatuses.COMPLETED);
            ProcessingController controller = loadController(service);

            controller.prepareForRoute(WorkspaceRoute.REVIEW_AND_PROCESS);
            ProcessingWorkspacePane pane = controller.workspacePaneForTest();

            assertFalse(pane.phaseForTest().active());
            assertEquals("Review & Process", pane.titleForTest());
            assertTrue(pane.reviewSummaryPaneForTest().isVisible());
            service.shutdown();
        });
    }

    private static DesktopAppService openProjectWithRun(String status) throws Exception {
        Path dir = Files.createTempDirectory("route-phase");
        Path source = dir.resolve("saved.mf4");
        Files.writeString(source, "stub");
        Path definition = dir.resolve("chassis.dbc");
        Files.writeString(definition, "VERSION \"\"\n");
        Instant created = Instant.parse("2026-08-26T10:00:00Z");
        ProjectMetadata project = new ProjectMetadata("project-1", "Saved Road Test", created, created);
        ProjectFileMetadata file = new ProjectFileMetadata(
                "project-1", "source-1", source.toString(), "MEASUREMENT_MF4", Files.size(source),
                "READY", "", null, "Finalized", false, true, false);
        ProjectFileMetadata dbc = new ProjectFileMetadata(
                "project-1", "def-1", definition.toString(), "DEFINITION_DBC", Files.size(definition),
                "READY", "", null, "", false, false, false);
        CanSourceInventory inventory = new CanSourceInventory(
                source, "saved.mf4", MeasurementSourceFormat.MF4, CanInventoryStatus.INVENTORY_READY,
                1, 20, 1, true,
                List.of(new CanChannelInventory(1, "Channel 1", 20, 1, 1, 0, 0,
                        List.of(new CanMessageInventory(new CanMessageKey(1, 0x123, false), 20, 8, 8, false)))),
                List.of());
        ChannelMappingMetadata mapping = new ChannelMappingMetadata(
                "project-1", "source-1", 1, "CONFIRMED", "CLEAR_BEST_MATCH",
                "def-1", "def-1", 1, 1, 1.0);
        ProcessingRunMetadata run = new ProcessingRunMetadata(
                "run-1", "project-1", status, false, 1, created, created.plusSeconds(2), "");
        SavedProjectSnapshot snapshot = new SavedProjectSnapshot(
                project, List.of(file, dbc), Map.of("source-1", inventory),
                List.of(mapping), List.of(run));

        DesktopAppService service = new DesktopAppService(
                new DesktopSession(ParserRegistry.load()), new SnapshotStore(snapshot));
        assertTrue(service.openSavedProject("project-1").opened());
        return service;
    }

    private static ProcessingController loadController(DesktopAppService service) throws Exception {
        FXMLLoader loader = new FXMLLoader(
                ProcessingRoutePhaseUiTest.class.getResource("/fxml/processing-view.fxml"));
        Parent root = loader.load();
        ProcessingController controller = loader.getController();
        controller.setAppService(service, null);
        Scene scene = new Scene(root, 1600, 900);
        var css = ProcessingRoutePhaseUiTest.class.getResource("/css/stellar-desktop.css");
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

    private record SnapshotStore(SavedProjectSnapshot snapshot) implements ProjectMetadataStore {
        @Override public List<ProjectMetadata> listProjects() { return List.of(snapshot.project()); }
        @Override public Optional<SavedProjectSnapshot> loadProject(String projectId) {
            return snapshot.project().projectId().equals(projectId) ? Optional.of(snapshot) : Optional.empty();
        }
        @Override public void upsertProject(ProjectMetadata project) { }
        @Override public void upsertFile(ProjectFileMetadata file) { }
        @Override public void deleteFile(String projectId, String fileId) { }
        @Override public void saveInventory(String projectId, String sourceId, CanSourceInventory inventory) { }
        @Override public void upsertChannelMapping(ChannelMappingMetadata mapping) { }
        @Override public void deleteChannelMapping(String projectId, String sourceId, int channelId) { }
        @Override public void upsertProcessingRun(ProcessingRunMetadata run) { }
    }

    @FunctionalInterface
    private interface FxAction {
        void run() throws Exception;
    }
}
