package com.stellar.platform.desktop.ui;

import com.stellar.platform.desktop.presentation.AnalysisMeasurementContext;
import com.stellar.platform.desktop.presentation.DesktopAppService;
import com.stellar.platform.desktop.presentation.NavigationPage;
import com.stellar.platform.desktop.presentation.WorkspaceRoute;
import com.stellar.platform.desktop.project.FileKind;
import com.stellar.platform.desktop.project.WorkspaceFile;
import com.stellar.platform.desktop.session.DesktopSession;
import com.stellar.platform.desktop.ui.signals.ActiveSignalWorkspacePane;
import com.stellar.platform.desktop.ui.signals.WorkspaceFocusMode;
import com.stellar.platform.domain.CanonicalSignal;
import com.stellar.platform.domain.SourceReference;
import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.processing.ParserRegistry;
import com.stellar.platform.processing.catalog.CompositeSignalCatalogStore;
import com.stellar.platform.processing.catalog.InMemorySignalCatalogStore;
import com.stellar.platform.storage.metadata.SqliteProjectMetadataStore;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.layout.BorderPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P0 Signals → Analyze Signals handoff. Focus Table must stay on Signals;
 * Analyze Signals must open Graphs / Waveforms.
 */
class SignalsAnalyzeHandoffP0Test {

    @TempDir Path tempDir;
    private DesktopAppService service;
    private MainShellController shell;
    private Stage stage;

    @BeforeAll
    static void initJavaFx() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        try {
            Platform.startup(started::countDown);
        } catch (IllegalStateException alreadyStarted) {
            started.countDown();
        }
        assertTrue(started.await(10, TimeUnit.SECONDS));
        Platform.runLater(() -> Platform.setImplicitExit(false));
    }

    @AfterEach
    void tearDown() throws Exception {
        onFx(() -> {
            if (stage != null) {
                stage.close();
                stage = null;
            }
            if (shell != null) {
                shell.dispose();
                shell = null;
            }
            if (service != null) {
                service.shutdown();
                service = null;
            }
        });
    }

    @Test
    @Timeout(60)
    void analyzeSelectedOpensGraphsWaveformsWithFourKeys() throws Exception {
        onFx(() -> {
            openLavasaLikeProject();
            showSignals();
            List<SignalCatalogKey> keys = keysOn("src-A", 4);
            service.signalsModel().addToWorkspace(keys);
            signalsController().refresh();
            layout();

            ActiveSignalWorkspacePane workspace = signalsController().workspacePaneForTest();
            assertEquals(WorkspaceFocusMode.NORMAL, workspace.focusMode());
            assertTrue(workspace.analyzeEnabledForTest());
            workspace.analyzeButtonForTest().fire();
            layout();

            assertEquals(WorkspaceRoute.GRAPHS, shell.visibleRoute());
            assertEquals(NavigationPage.GRAPHS, shell.visiblePage());
            GraphController graph = graphController();
            assertEquals(0, graph.analysisTabIndexForTest(), "Waveforms must be the landing tab");
            assertEquals("Waveforms", graph.analysisTabTextForTest(0));
            assertEquals("Analytics", graph.analysisTabTextForTest(1));
            assertEquals("Engineering", graph.analysisTabTextForTest(2));
            assertEquals("Map Route", graph.analysisTabTextForTest(3));
            assertEquals(4, graph.analysisTabCountForTest());
            assertTrue(graph.signalPanelManagedForTest(), "left Signals explorer must stay visible");
            assertEquals(4, graph.selectedSignalCountForTest());
            assertTrue(graph.graphCatalogForTest().stream().allMatch(e -> "src-A".equals(e.key().sourceFile())));
            assertEquals("src-A", service.activeAnalysisMeasurement().sourceId());
            assertEquals(keys, service.signalsModel().workspaceKeys());
        });
    }

    @Test
    @Timeout(45)
    void focusTableStaysOnSignalsAndExitRestoresPanes() throws Exception {
        onFx(() -> {
            openLavasaLikeProject();
            showSignals();
            service.signalsModel().addToWorkspace(keysOn("src-A", 4));
            signalsController().refresh();
            layout();

            SignalsController controller = signalsController();
            ActiveSignalWorkspacePane workspace = controller.workspacePaneForTest();
            findButton(workspace, "Focus Table").fire();
            layout();

            assertEquals(WorkspaceRoute.SIGNALS, shell.visibleRoute());
            assertEquals(WorkspaceFocusMode.FOCUS, workspace.focusMode());
            assertEquals(WorkspaceFocusMode.FOCUS, controller.focusMode());
            assertTrue(findButton(workspace, "Exit Focus").isVisible());
            assertFalse(workspace.analyzeButtonForTest().getText().isBlank());

            findButton(workspace, "Exit Focus").fire();
            layout();
            assertEquals(WorkspaceFocusMode.NORMAL, workspace.focusMode());
            assertEquals(3, workbenchChildCount());
            assertTrue(controller.browserPaneForTest().isManaged());
            assertTrue(controller.inspectorPaneForTest().isManaged());
        });
    }

    @Test
    @Timeout(60)
    void focusThenExitThenAnalyzeOpensGraphs() throws Exception {
        onFx(() -> {
            openLavasaLikeProject();
            showSignals();
            service.signalsModel().addToWorkspace(keysOn("src-A", 4));
            signalsController().refresh();
            layout();

            ActiveSignalWorkspacePane workspace = signalsController().workspacePaneForTest();
            findButton(workspace, "Focus Table").fire();
            layout();
            findButton(workspace, "Exit Focus").fire();
            layout();
            workspace.analyzeButtonForTest().fire();
            layout();

            assertEquals(WorkspaceRoute.GRAPHS, shell.visibleRoute());
            assertEquals(0, graphController().analysisTabIndexForTest());
        });
    }

    @Test
    @Timeout(60)
    void analyzeThenBackToSignalsKeepsWorkspaceKeys() throws Exception {
        onFx(() -> {
            openLavasaLikeProject();
            showSignals();
            List<SignalCatalogKey> keys = keysOn("src-A", 4);
            service.signalsModel().addToWorkspace(keys);
            signalsController().refresh();
            layout();
            signalsController().workspacePaneForTest().analyzeButtonForTest().fire();
            layout();
            assertEquals(WorkspaceRoute.GRAPHS, shell.visibleRoute());

            shell.show(NavigationPage.SIGNALS);
            layout();
            assertEquals(WorkspaceRoute.SIGNALS, shell.visibleRoute());
            assertEquals(keys, service.signalsModel().workspaceKeys());
            ActiveSignalWorkspacePane workspace = signalsController().workspacePaneForTest();
            assertEquals(WorkspaceFocusMode.NORMAL, workspace.focusMode());
            assertTrue(workspace.analyzeEnabledForTest());
        });
    }

    @Test
    @Timeout(45)
    void largeSelectionKeepsBoundedPreviewAndReachableToolbar() throws Exception {
        onFx(() -> {
            openLavasaLikeProject();
            showSignals();
            service.signalsModel().setMaxWorkspaceSignals(128);
            assertSelectionSummary(1);
            assertSelectionSummary(4);
            assertSelectionSummary(10);
            assertSelectionSummary(12);
        });
    }

    @Test
    @Timeout(45)
    void mixedMeasurementsCannotSilentlyAnalyze() throws Exception {
        onFx(() -> {
            openLavasaLikeProject();
            showSignals();
            service.signalsModel().addToWorkspace(List.of(
                    keyOn("src-A", "VehicleSpeed"),
                    keyOn("src-B", "VehicleSpeed")));
            signalsController().refresh();
            layout();

            ActiveSignalWorkspacePane workspace = signalsController().workspacePaneForTest();
            assertFalse(workspace.analyzeEnabledForTest());
            assertTrue(workspace.analyzeTooltipForTest().contains("more than one measurement"));
            workspace.analyzeButtonForTest().fire();
            layout();
            assertEquals(WorkspaceRoute.SIGNALS, shell.visibleRoute());
            assertEquals(WorkspaceFocusMode.NORMAL, workspace.focusMode());
        });
    }

    @Test
    @Timeout(60)
    void specificMeasurementAnalyzeResolvesSameContext() throws Exception {
        onFx(() -> {
            openLavasaLikeProject();
            service.selectMeasurement("src-B");
            showSignals();
            service.signalsModel().addToWorkspace(List.of(keyOn("src-B", "VehicleSpeed")));
            signalsController().refresh();
            layout();

            AnalysisMeasurementContext before = service.activeAnalysisMeasurement();
            signalsController().workspacePaneForTest().analyzeButtonForTest().fire();
            layout();

            assertEquals(WorkspaceRoute.GRAPHS, shell.visibleRoute());
            assertEquals(before.sourceId(), service.activeAnalysisMeasurement().sourceId());
            assertEquals("src-B", service.activeAnalysisMeasurement().sourceId());
            assertEquals("MF4_B.mf4", service.activeAnalysisMeasurement().fileName());
        });
    }

    @Test
    @Timeout(60)
    void controllerRecreationStillNavigatesAnalyzeToGraphs() throws Exception {
        onFx(() -> {
            openLavasaLikeProject();
            showSignals();
            service.signalsModel().addToWorkspace(keysOn("src-A", 4));
            signalsController().refresh();
            layout();
            SignalsController first = signalsController();

            shell.show(NavigationPage.PROJECTS_HOME);
            layout();
            showSignals();
            signalsController().refresh();
            layout();
            SignalsController second = signalsController();
            assertNotSame(first, second);
            assertTrue(first.disposedForTest());

            second.workspacePaneForTest().analyzeButtonForTest().fire();
            layout();
            assertEquals(WorkspaceRoute.GRAPHS, shell.visibleRoute());
            assertEquals(4, service.signalsModel().workspaceKeys().size());
        });
    }

    @Test
    @Timeout(60)
    void mapRouteFollowsMeasurementContextWithoutSelectingCoordinates() throws Exception {
        onFx(() -> {
            openLavasaLikeProject();
            service.signalsModel().replaceCatalog(gpsStore());
            showSignals();
            signalsController().refresh();
            layout();

            ActiveSignalWorkspacePane workspace = signalsController().workspacePaneForTest();
            assertEquals("Map Route", workspace.mapRouteButtonForTest().getText());
            assertTrue(workspace.mapRouteEnabledForTest(), workspace.mapRouteTooltipForTest());
            assertFalse(workspace.analyzeEnabledForTest());

            service.signalsModel().addToWorkspace(List.of(keyOn("src-A", "EngineSpeed")));
            signalsController().refresh();
            layout();
            assertTrue(workspace.analyzeEnabledForTest());
            assertTrue(workspace.mapRouteEnabledForTest());
            assertTrue(service.signalsModel().workspaceKeys().stream()
                    .noneMatch(key -> key.signalName().contains("Lat") || key.signalName().contains("Lon")));

            workspace.mapRouteButtonForTest().fire();
            layout();
            assertEquals(WorkspaceRoute.GRAPHS, shell.visibleRoute());
            GraphController graph = graphController();
            assertEquals(3, graph.analysisTabIndexForTest());
            assertEquals("Map Route", graph.analysisTabTextForTest(3));
            assertTrue(service.isProjectWideAnalysis(), service.analysisScope().comboLabel());
            assertTrue(service.analysisScope().comboLabel().startsWith("All Measurements"));
            assertEquals(1, service.signalsModel().workspaceKeys().size());

            shell.show(NavigationPage.SIGNALS);
            layout();
            assertEquals(List.of(keyOn("src-A", "EngineSpeed")), service.signalsModel().workspaceKeys());
            shell.show(NavigationPage.PROJECTS_HOME);
            layout();
            showSignals();
            signalsController().refresh();
            layout();
            assertEquals(1, service.signalsModel().workspaceKeys().size());
            assertEquals("EngineSpeed", service.signalsModel().workspaceKeys().getFirst().signalName());

            String single = signalsController().browserPaneForTest().measurementOptionsForTest().stream()
                    .filter(option -> option != null && !option.startsWith("All"))
                    .findFirst()
                    .orElseThrow();
            signalsController().browserPaneForTest().selectMeasurementForTest(single);
            layout();
            signalsController().workspacePaneForTest().mapRouteButtonForTest().fire();
            layout();
            assertEquals(3, graphController().analysisTabIndexForTest());
            assertFalse(service.isProjectWideAnalysis(), service.analysisScope().comboLabel());
            assertEquals("src-A", service.activeAnalysisMeasurement().sourceId());
            assertEquals("MF4_A.mf4", service.activeAnalysisMeasurement().fileName());
        });
    }

    @Test
    @Timeout(45)
    void mapRouteStaysDisabledWithoutGpsOrAResolvedCatalog() throws Exception {
        onFx(() -> {
            openLavasaLikeProject();
            showSignals();
            signalsController().refresh();
            layout();
            ActiveSignalWorkspacePane workspace = signalsController().workspacePaneForTest();
            assertFalse(workspace.mapRouteEnabledForTest());
            assertEquals(SignalsController.MAP_ROUTE_DISABLED_TIP, workspace.mapRouteTooltipForTest());
            workspace.mapRouteButtonForTest().fire();
            layout();
            assertEquals(WorkspaceRoute.SIGNALS, shell.visibleRoute());

            service.signalsModel().replaceCatalog(new InMemorySignalCatalogStore("pending", "none", 1));
            signalsController().refresh();
            layout();
            assertFalse(workspace.mapRouteEnabledForTest());
            assertEquals(SignalsController.MAP_ROUTE_DISABLED_TIP, workspace.mapRouteTooltipForTest());
        });
    }

    private void assertSelectionSummary(int count) {
        service.signalsModel().clearWorkspace();
        service.signalsModel().addToWorkspace(keysOn("src-A", count));
        signalsController().refresh();
        layout();
        ActiveSignalWorkspacePane workspace = signalsController().workspacePaneForTest();
        workspace.resize(1200, 700);
        workspace.layout();
        assertEquals(count, service.signalsModel().workspaceKeys().size());
        assertEquals(count, workspace.previewChipCountForTest(),
                "selected signals stay on one scrolling chip strip");
        assertFalse(workspace.moreSignalsVisibleForTest());
        assertFalse(workspace.analyzeInWorkspaceHeaderForTest());
        assertFalse(workspace.clearAllInWorkspaceHeaderForTest());
        Button analyze = workspace.analyzeButtonForTest();
        Button clear = workspace.clearAllButtonForTest();
        assertTrue(analyze.getParent().getStyleClass().contains("signals-context-actions"));
        assertSame(analyze.getParent(), clear.getParent());
        Button focus = findButton(workspace, "Focus Table");
        Bounds ws = workspace.localToScene(workspace.getBoundsInLocal());
        Bounds focusBounds = focus.localToScene(focus.getBoundsInLocal());
        assertTrue(focusBounds.getMinX() + 0.5 >= ws.getMinX(), "Focus Table left of workspace");
        assertTrue(focusBounds.getMaxX() <= ws.getMaxX() + 1.0, "Focus Table overflowed workspace");
        for (Button button : List.of(analyze, clear, focus)) {
            assertTrue(button.isVisible() && button.isManaged(), button.getText() + " must stay reachable");
            assertTrue(button.getStyle() == null || button.getStyle().isBlank(), button.getText());
        }
        assertEquals(WorkspaceRoute.SIGNALS, shell.visibleRoute());
    }

    @Test
    @Timeout(60)
    void analyzeFollowsActiveWorkspaceNotCatalogSelection() throws Exception {
        onFx(() -> {
            openLavasaLikeProject();
            showSignals();
            SignalsController signals = signalsController();
            signals.refresh();
            layout();
            ActiveSignalWorkspacePane workspace = signals.workspacePaneForTest();
            assertEquals(0, service.signalsModel().workspaceKeys().size());
            assertFalse(workspace.analyzeEnabledForTest(), workspace.analyzeTooltipForTest());
            assertTrue(workspace.analyzeTooltipForTest().contains("active workspace"));

            var catalog = signals.browserPaneForTest().catalogTable();
            if (!catalog.getItems().isEmpty()) {
                catalog.getSelectionModel().select(0);
            }
            signals.refresh();
            layout();
            assertEquals(0, service.signalsModel().workspaceKeys().size());
            assertFalse(workspace.analyzeEnabledForTest(),
                    "catalog selection must not enable Analyze: " + workspace.analyzeTooltipForTest());

            workspace.addKeys(keysOn("src-A", 1));
            layout();
            assertEquals(1, service.signalsModel().workspaceKeys().size());
            assertTrue(workspace.analyzeEnabledForTest(), workspace.analyzeTooltipForTest());

            workspace.addKeys(keysOn("src-A", 4));
            layout();
            assertEquals(4, service.signalsModel().workspaceKeys().size());
            assertTrue(workspace.analyzeEnabledForTest(), workspace.analyzeTooltipForTest());
            assertEquals("Analyze Signals", workspace.analyzeButtonForTest().getText());

            workspace.clearAllButtonForTest().fire();
            layout();
            assertEquals(0, service.signalsModel().workspaceKeys().size());
            assertFalse(workspace.analyzeEnabledForTest(), workspace.analyzeTooltipForTest());
            assertTrue(workspace.analyzeTooltipForTest().contains("active workspace"));
        });
    }

    private void openLavasaLikeProject() throws Exception {
        System.setProperty("stellarai.home", tempDir.resolve("home").toAbsolutePath().toString());
        service = new DesktopAppService(
                new DesktopSession(ParserRegistry.load()),
                new SqliteProjectMetadataStore(tempDir.resolve("handoff.db")));
        service.newProject("Lavasa Handoff");
        Files.writeString(tempDir.resolve("MF4_A.mf4"), "stub");
        Files.writeString(tempDir.resolve("MF4_B.mf4"), "stub");
        service.workspace().addFile(WorkspaceFile.restore(
                "src-A", tempDir.resolve("MF4_A.mf4"), FileKind.MEASUREMENT_MF4, 1));
        service.workspace().addFile(WorkspaceFile.restore(
                "src-B", tempDir.resolve("MF4_B.mf4"), FileKind.MEASUREMENT_MF4, 1));
        service.selectMeasurement("src-A");
        service.signalsModel().replaceCatalog(twoFileStore());
    }

    private void showSignals() {
        if (shell == null) {
            try {
                FXMLLoader loader = new FXMLLoader(MainShellController.class.getResource("/fxml/main-shell.fxml"));
                BorderPane root = loader.load();
                shell = loader.getController();
                shell.init(service);
                stage = new Stage();
                stage.setScene(new Scene(root, 1600, 900));
                stage.show();
                root.applyCss();
                root.layout();
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
        }
        shell.show(NavigationPage.SIGNALS);
        layout();
    }

    private void layout() {
        if (stage != null && stage.getScene() != null && stage.getScene().getRoot() != null) {
            stage.getScene().getRoot().applyCss();
            stage.getScene().getRoot().layout();
        }
    }

    private SignalsController signalsController() {
        Object controller = shell.controllerForTest(NavigationPage.SIGNALS);
        assertTrue(controller instanceof SignalsController);
        return (SignalsController) controller;
    }

    private GraphController graphController() {
        Object controller = shell.controllerForTest(NavigationPage.GRAPHS);
        assertTrue(controller instanceof GraphController);
        return (GraphController) controller;
    }

    private int workbenchChildCount() {
        return signalsController().browserPaneForTest().getParent().getChildrenUnmodifiable().size();
    }

    private List<SignalCatalogKey> keysOn(String source, int count) {
        List<SignalCatalogKey> keys = new ArrayList<>();
        for (SignalCatalogEntry entry : service.signalsModel().catalog().entries()) {
            if (source.equals(entry.key().sourceFile())) {
                keys.add(entry.key());
                if (keys.size() >= count) {
                    break;
                }
            }
        }
        assertEquals(count, keys.size(), "catalog did not contain " + count + " keys for " + source);
        return keys;
    }

    private SignalCatalogKey keyOn(String source, String name) {
        return service.signalsModel().catalog().entries().stream()
                .filter(e -> name.equals(e.signalName()) && source.equals(e.key().sourceFile()))
                .findFirst()
                .orElseThrow()
                .key();
    }

    private static CompositeSignalCatalogStore gpsStore() {
        return CompositeSignalCatalogStore.fromMembers(List.of(
                gpsMember("src-A", "MF4_A.mf4"),
                gpsMember("src-B", "MF4_B.mf4")
        ), "2 measurements", "none");
    }

    private static CompositeSignalCatalogStore.Member gpsMember(String identity, String display) {
        InMemorySignalCatalogStore store = new InMemorySignalCatalogStore(display, "veh.dbc", 16);
        long t0 = 1_000L;
        List<CanonicalSignal> rows = new ArrayList<>();
        rows.add(can("EngineSpeed", t0, 1_200));
        rows.add(geo("Latitude", t0, 18.50));
        rows.add(geo("Latitude", t0 + 1_000_000_000L, 18.55));
        rows.add(geo("Longitude", t0, 73.80));
        rows.add(geo("Longitude", t0 + 1_000_000_000L, 73.90));
        store.accept(rows);
        return new CompositeSignalCatalogStore.Member(store, identity, "_none_", display, "none");
    }

    private static CanonicalSignal geo(String name, long ts, double value) {
        return new CanonicalSignal(
                name, "GPS", ts, 0x300, 1, value, 1L, "deg", null, SourceReference.ofLine(1));
    }

    private static CompositeSignalCatalogStore twoFileStore() {
        return CompositeSignalCatalogStore.fromMembers(List.of(
                member("src-A", "MF4_A.mf4", 100),
                member("src-B", "MF4_B.mf4", 8)
        ), "2 measurements", "none");
    }

    private static CompositeSignalCatalogStore.Member member(String identity, String display, int signals) {
        InMemorySignalCatalogStore store = new InMemorySignalCatalogStore(display, "veh.dbc", 200);
        List<CanonicalSignal> rows = new ArrayList<>();
        long t0 = identity.hashCode() & 0xffff;
        rows.add(can("VehicleSpeed", t0, 40));
        rows.add(can("EngineRPM", t0 + 1, 900));
        rows.add(can("CoolantTemp", t0 + 2, 83));
        rows.add(can("MonitorStatus", t0 + 3, 1));
        for (int i = rows.size(); i < signals; i++) {
            rows.add(can("Sig" + i, t0 + i, i));
        }
        store.accept(rows);
        return new CompositeSignalCatalogStore.Member(store, identity, "_none_", display, "none");
    }

    private static CanonicalSignal can(String name, long ts, double value) {
        return new CanonicalSignal(
                name, "Chassis", ts, 0x200, 1, value, 1L, "km/h", null, SourceReference.ofLine(1));
    }

    private static Button findButton(Parent root, String text) {
        Deque<Node> q = new ArrayDeque<>();
        q.add(root);
        while (!q.isEmpty()) {
            Node n = q.removeFirst();
            if (n instanceof Button button && text.equals(button.getText())) {
                return button;
            }
            if (n instanceof Parent parent) {
                q.addAll(parent.getChildrenUnmodifiable());
            }
        }
        throw new AssertionError("Button not found: " + text);
    }

    private void onFx(FxWork work) throws Exception {
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                work.run();
            } catch (Throwable t) {
                error.set(t);
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(50, TimeUnit.SECONDS), "JavaFX work timed out");
        if (error.get() != null) {
            throw new AssertionError(error.get());
        }
    }

    @FunctionalInterface
    private interface FxWork {
        void run() throws Exception;
    }
}
