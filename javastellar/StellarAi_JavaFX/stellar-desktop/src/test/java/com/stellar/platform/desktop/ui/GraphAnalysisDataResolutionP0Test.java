package com.stellar.platform.desktop.ui;

import com.stellar.platform.desktop.presentation.AnalysisMeasurementContext;
import com.stellar.platform.desktop.presentation.AnalysisMeasurementResolver;
import com.stellar.platform.desktop.presentation.DesktopAppService;
import com.stellar.platform.desktop.presentation.MeasurementCatalogScope;
import com.stellar.platform.desktop.presentation.NavigationPage;
import com.stellar.platform.desktop.presentation.WorkspaceRoute;
import com.stellar.platform.desktop.project.FileKind;
import com.stellar.platform.desktop.project.WorkspaceFile;
import com.stellar.platform.desktop.session.DesktopSession;
import com.stellar.platform.desktop.ui.signals.ActiveSignalWorkspacePane;
import com.stellar.platform.domain.CanonicalSignal;
import com.stellar.platform.domain.SourceReference;
import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSamplePoint;
import com.stellar.platform.processing.ParserRegistry;
import com.stellar.platform.processing.catalog.InMemorySignalCatalogStore;
import com.stellar.platform.processing.catalog.ProjectMergedSignalStore;
import com.stellar.platform.processing.graph.GraphRenderSeries;
import com.stellar.platform.processing.graph.GraphSeriesQuery;
import com.stellar.platform.storage.metadata.SqliteProjectMetadataStore;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Production-path Graph data resolution: ProjectMergedSignalStore + Analyze → Graph.
 */
class GraphAnalysisDataResolutionP0Test {

    @TempDir Path tempDir;
    private DesktopAppService service;
    private MainShellController shell;
    private Stage stage;
    private ProjectMergedSignalStore retainedStore;

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
    void fourSameMeasurementSignalsReachGraphs() throws Exception {
        onFx(() -> {
            openTwoFileProject();
            showSignals();
            var browser = signalsController().browserPaneForTest();
            assertEquals("All Measurements (2)", browser.measurementOptionsForTest().getFirst());
            assertTrue(browser.measurementOptionsForTest().contains("MF4_A.mf4"));
            assertTrue(browser.measurementOptionsForTest().contains("MF4_B.mf4"));
            assertFalse(browser.measurementOptionsForTest().contains("2 measurements"));
            browser.selectMeasurementForTest("MF4_A.mf4");
            layout();
            List<SignalCatalogKey> projectKeys = browser.catalogTable().getItems().stream()
                    .limit(4)
                    .map(SignalCatalogEntry::key)
                    .toList();
            assertEquals(4, projectKeys.size());
            assertTrue(projectKeys.stream().allMatch(key -> "src-A".equals(key.sourceFile())));
            service.signalsModel().addToWorkspace(projectKeys);
            signalsController().refresh();
            layout();

            ActiveSignalWorkspacePane workspace = signalsController().workspacePaneForTest();
            assertTrue(workspace.analyzeEnabledForTest(), workspace.analyzeTooltipForTest());
            workspace.analyzeButtonForTest().fire();
            layout();

            assertEquals(WorkspaceRoute.GRAPHS, shell.visibleRoute());
            GraphController graph = graphController();
            assertEquals(4, graph.selectedSignalCountForTest());
            assertEquals(4, service.signalsModel().workspaceKeys().size());
            assertTrue(service.signalsModel().workspaceKeys().stream()
                    .allMatch(key -> "src-A".equals(key.sourceFile())));
            assertFalse(graph.explorerCountCaptionForTest().contains("/ 0 signals"));
            assertTrue(graph.explorerCountCaptionForTest().startsWith("4 selected"));
            assertEquals(4, graph.renderAnalysisTracksForTest().size());
        });
    }

    @Test
    @Timeout(60)
    void analyzeSelectedReachesGraphsWithResolvedCatalogAndTracks() throws Exception {
        onFx(() -> {
            openTwoFileProject();
            showSignals();
            List<SignalCatalogKey> projectKeys = projectKeys("UniqueA1", "UniqueA2", "UniqueA3");
            assertTrue(projectKeys.stream().allMatch(k ->
                    ProjectMergedSignalStore.PROJECT_SCOPE_SOURCE.equals(k.sourceFile())));
            service.signalsModel().addToWorkspace(projectKeys);
            signalsController().refresh();
            layout();

            ActiveSignalWorkspacePane workspace = signalsController().workspacePaneForTest();
            assertTrue(workspace.analyzeEnabledForTest());
            workspace.analyzeButtonForTest().fire();
            layout();

            assertEquals(WorkspaceRoute.GRAPHS, shell.visibleRoute());
            assertEquals(NavigationPage.GRAPHS, shell.visiblePage());
            assertEquals("src-A", service.activeAnalysisMeasurement().sourceId());
            assertEquals("MF4_A.mf4", service.activeAnalysisMeasurement().fileName());
            assertNotEquals(service.activeAnalysisMeasurement().sourceId(),
                    service.activeAnalysisMeasurement().fileName());

            List<SignalCatalogEntry> catalog = service.engineeringCatalog();
            assertFalse(catalog.isEmpty());
            assertTrue(catalog.stream().allMatch(e -> "src-A".equals(e.key().sourceFile())));
            assertTrue(catalog.stream().noneMatch(e -> "src-B".equals(e.key().sourceFile())));

            GraphController graph = graphController();
            assertTrue(graph.graphCatalogForTest().size() > 0);
            assertEquals(3, graph.selectedSignalCountForTest());
            assertEquals(3, AnalysisMeasurementResolver.remapToCatalog(
                    service.signalsModel().workspaceKeys(), catalog).size());
            assertFalse(graph.explorerCountCaptionForTest().contains("/ 0 signals"));
            assertTrue(graph.explorerCountCaptionForTest().startsWith("3 selected · 3 visible / "));

            List<GraphRenderSeries> tracks = graph.renderAnalysisTracksForTest();
            assertEquals(3, tracks.size());
            assertTrue(tracks.stream().allMatch(t -> !t.buckets().isEmpty()));
            assertEquals(0, graph.analysisTabIndexForTest());
        });
    }

    @Test
    void graphCatalogMatchesEngineeringCatalogAndMustNotCollapseToEmpty() throws Exception {
        openTwoFileProject();
        bindGraph("src-A", "VehicleSpeed", "EngineRPM", "CoolantTemp");
        List<SignalCatalogEntry> engineering = service.engineeringCatalog();
        int catalogSize = service.signalsModel().catalog() == null ? 0 : service.signalsModel().catalog().size();
        GraphController graph = new GraphController();
        graph.bindMembershipForTest(service);
        int graphAllSignals = graph.graphCatalogForTest().size();
        assertEquals(engineering.size(), graphAllSignals,
                "graphAllSignals must equal engineeringCatalog of the active measurement");
        assertTrue(engineering.size() > 0, "empty engineering catalog is a 3/3/0-class regression");
        assertTrue(catalogSize > 0);
        assertFalse(graph.explorerCountCaptionForTest().contains("/ 0 signals"));
        assertEquals(3, AnalysisMeasurementResolver.remapToCatalog(
                service.signalsModel().workspaceKeys(), engineering).size());
        assertFalse(graph.renderAnalysisTracksForTest().isEmpty());
    }

    @Test
    void engineeringCatalogAndSamplesStayIsolatedAcrossAThenBThenA() throws Exception {
        openTwoFileProject();
        bindGraph("src-A", "VehicleSpeed", "EngineRPM", "CoolantTemp");
        assertActiveIsolated("src-A", "MF4_A.mf4", 1_000L);

        bindGraph("src-B", "VehicleSpeed", "EngineRPM", "CoolantTemp");
        assertActiveIsolated("src-B", "MF4_B.mf4", 10_000L);

        bindGraph("src-A", "VehicleSpeed", "EngineRPM", "CoolantTemp");
        assertActiveIsolated("src-A", "MF4_A.mf4", 1_000L);
    }

    @Test
    void allMeasurementsUniqueKeysResolveOneSourceAndCrossMeasurementStaysBlocked() throws Exception {
        openTwoFileProject();
        List<WorkspaceFile> files = service.workspace().measurementFiles();
        List<SignalCatalogKey> uniqueA = projectKeys("UniqueA1", "UniqueA2", "UniqueA3");
        AnalysisMeasurementResolver.Resolution fromAll = AnalysisMeasurementResolver.resolve(
                uniqueA,
                service.signalsModel().catalog().entries(),
                service.workspace().selectedMeasurement(),
                files,
                MeasurementCatalogScope.Ref.all(2),
                service.signalsModel().catalogStore());
        assertTrue(fromAll.canAnalyze());
        assertEquals("src-A", fromAll.measurementId());

        List<SignalCatalogKey> uniqueB = projectKeys("UniqueB1", "UniqueB2", "UniqueB3");
        AnalysisMeasurementResolver.Resolution fromB = AnalysisMeasurementResolver.resolve(
                uniqueB,
                service.signalsModel().catalog().entries(),
                OptionalFile.none(),
                files,
                MeasurementCatalogScope.Ref.all(2),
                service.signalsModel().catalogStore());
        assertTrue(fromB.canAnalyze());
        assertEquals("src-B", fromB.measurementId());

        AnalysisMeasurementResolver.Resolution mixed = AnalysisMeasurementResolver.resolve(
                List.of(uniqueA.getFirst(), uniqueB.getFirst()),
                service.signalsModel().catalog().entries(),
                service.workspace().selectedMeasurement(),
                files,
                MeasurementCatalogScope.Ref.all(2),
                service.signalsModel().catalogStore());
        assertTrue(mixed.ambiguous());
        assertFalse(mixed.canAnalyze());

        AnalysisMeasurementResolver.Resolution overlapping = AnalysisMeasurementResolver.resolve(
                projectKeys("VehicleSpeed", "EngineRPM", "CoolantTemp"),
                service.signalsModel().catalog().entries(),
                service.workspace().selectedMeasurement(),
                files,
                MeasurementCatalogScope.Ref.all(2),
                service.signalsModel().catalogStore());
        assertTrue(overlapping.canAnalyze(), overlapping.explanation());
        assertEquals("src-A", overlapping.measurementId());

        AnalysisMeasurementResolver.Resolution overlappingOrphan = AnalysisMeasurementResolver.resolve(
                projectKeys("VehicleSpeed", "EngineRPM", "CoolantTemp"),
                service.signalsModel().catalog().entries(),
                OptionalFile.none(),
                files,
                MeasurementCatalogScope.Ref.all(2),
                service.signalsModel().catalogStore());
        assertTrue(overlappingOrphan.ambiguous());
        assertFalse(overlappingOrphan.canAnalyze());
        assertTrue(overlappingOrphan.explanation().contains("canAnalyze=false"));
    }

    @Test
    @Timeout(60)
    void reopenRestoresAnalyzeWithoutReprocessing() throws Exception {
        onFx(() -> {
            openTwoFileProject();
            service.selectMeasurement("src-A");
            List<SignalCatalogKey> signal = projectKeys("VehicleSpeed");
            service.signalsModel().addToWorkspace(signal);
            service.persistGraphInvestigation();
            AnalysisMeasurementResolver.Resolution before = resolveRestored();
            assertTrue(before.canAnalyze(), before.explanation());
            assertEquals("src-A", before.measurementId());
            String projectId = service.currentProjectId();
            int catalogBefore = service.signalsModel().catalog().size();
            service.saveProject();
            service.closeProject();

            DesktopAppService.ProjectOpenResult opened = service.openSavedProject(projectId);
            assertTrue(opened.opened(), String.join(",", opened.warnings()));
            assertEquals("src-A", service.workspace().selectedMeasurement().map(WorkspaceFile::id).orElse(""));
            service.signalsModel().replaceCatalog(retainedStore);
            service.restoreGraphInvestigation();

            assertEquals("src-A", service.activeAnalysisMeasurement().sourceId());
            assertEquals("MF4_A.mf4", service.activeAnalysisMeasurement().fileName());
            assertFalse(service.signalsModel().workspaceKeys().isEmpty());
            assertEquals(catalogBefore, service.signalsModel().catalog().size());
            assertFalse(service.engineeringCatalog().isEmpty());
            assertFalse(service.compactProgress().running());
            assertFalse(service.isPlanExecutionActive());

            AnalysisMeasurementResolver.Resolution after = resolveRestored();
            assertTrue(after.canAnalyze(), after.explanation());
            assertTrue(after.explanation().contains("canAnalyze=true"));
            assertEquals("src-A", after.measurementId());

            showSignals();
            signalsController().refresh();
            layout();
            ActiveSignalWorkspacePane workspace = signalsController().workspacePaneForTest();
            assertTrue(workspace.analyzeEnabledForTest(), workspace.analyzeTooltipForTest());
            assertTrue(workspace.analyzeTooltipForTest().contains("Plot the signals"));
            assertFalse(workspace.analyzeTooltipForTest().contains("canAnalyze="),
                    workspace.analyzeTooltipForTest());
            workspace.analyzeButtonForTest().fire();
            layout();

            assertEquals(WorkspaceRoute.GRAPHS, shell.visibleRoute());
            GraphController graph = graphController();
            assertEquals(1, graph.selectedSignalCountForTest());
            assertFalse(graph.graphCatalogForTest().isEmpty());
            assertEquals(1, graph.renderAnalysisTracksForTest().size());
            assertFalse(graph.renderAnalysisTracksForTest().getFirst().buckets().isEmpty());
        });
    }

    @Test
    void reopenAndControllerRecreationResolveTheSamePhysicalCatalog() throws Exception {
        openTwoFileProject();
        bindGraph("src-B", "UniqueB1", "UniqueB2", "UniqueB3");
        assertEquals("src-B", service.activeAnalysisMeasurement().sourceId());
        String projectId = service.currentProjectId();
        List<SignalCatalogKey> before = List.copyOf(service.signalsModel().workspaceKeys());
        service.saveProject();
        service.closeProject();
        DesktopAppService.ProjectOpenResult opened = service.openSavedProject(projectId);
        assertTrue(opened.opened(), String.join(",", opened.warnings()));
        service.signalsModel().replaceCatalog(retainedStore);
        service.selectMeasurement("src-B");
        service.signalsModel().clearWorkspace();
        service.signalsModel().addToWorkspace(before);

        assertEquals("src-B", service.activeAnalysisMeasurement().sourceId());
        assertFalse(service.engineeringCatalog().isEmpty());
        assertFalse(service.compactProgress().running());
        assertFalse(service.isPlanExecutionActive());

        GraphController first = new GraphController();
        first.bindMembershipForTest(service);
        assertEquals(3, first.selectedSignalCountForTest());
        assertFalse(first.graphCatalogForTest().isEmpty());
        assertEquals(3, first.renderAnalysisTracksForTest().size());

        GraphController second = new GraphController();
        second.bindMembershipForTest(service);
        assertNotSame(first, second);
        assertEquals(3, second.selectedSignalCountForTest());
        assertEquals(3, second.renderAnalysisTracksForTest().size());
        assertTrue(second.graphCatalogForTest().stream().allMatch(e -> "src-B".equals(e.key().sourceFile())));
    }

    @Test
    void lateAsyncPreviousMeasurementCannotOverwriteCurrent() throws Exception {
        openTwoFileProject();
        GraphController graph = new GraphController();
        graph.bindMembershipForTest(service);
        service.selectMeasurement("src-A");
        graph.applyActiveMeasurementCatalogForTest();
        long generationA = graph.generationForTest();
        int session = graph.sessionGenerationForTest();
        service.selectMeasurement("src-B");
        graph.applyActiveMeasurementCatalogForTest();
        assertFalse(graph.acceptViewportResult(generationA, session));
        assertTrue(graph.graphCatalogForTest().stream().allMatch(e -> "src-B".equals(e.key().sourceFile())));
    }

    private AnalysisMeasurementResolver.Resolution resolveRestored() {
        return AnalysisMeasurementResolver.resolve(
                service.signalsModel().workspaceKeys(),
                service.signalsModel().catalog().entries(),
                service.workspace().selectedMeasurement(),
                service.workspace().measurementFiles(),
                MeasurementCatalogScope.Ref.all(2),
                service.signalsModel().catalogStore());
    }

    private void bindGraph(String sourceId, String... names) {
        service.selectMeasurement(sourceId);
        List<SignalCatalogKey> requested = projectKeys(names);
        AnalysisMeasurementResolver.Resolution resolved = AnalysisMeasurementResolver.resolve(
                requested,
                service.signalsModel().catalog().entries(),
                service.workspace().selectedMeasurement(),
                service.workspace().measurementFiles(),
                new MeasurementCatalogScope.Ref(sourceId, sourceId.equals("src-A") ? "MF4_A.mf4" : "MF4_B.mf4", false, 2),
                service.signalsModel().catalogStore());
        assertTrue(resolved.canAnalyze(), resolved.explanation());
        assertEquals(sourceId, resolved.measurementId());
        List<SignalCatalogKey> remapped = AnalysisMeasurementResolver.remapToCatalog(
                requested, service.engineeringCatalog(resolved.context()));
        assertEquals(3, remapped.size());
        service.signalsModel().clearWorkspace();
        service.signalsModel().addToWorkspace(remapped);
        GraphController graph = new GraphController();
        graph.bindMembershipForTest(service);
        assertEquals(3, graph.selectedSignalCountForTest());
        assertFalse(graph.explorerCountCaptionForTest().contains("/ 0 signals"));
        assertEquals(3, graph.renderAnalysisTracksForTest().size());
    }

    private void assertActiveIsolated(String sourceId, String fileName, long expectedFirstTs) {
        AnalysisMeasurementContext ctx = service.activeAnalysisMeasurement();
        assertEquals(sourceId, ctx.sourceId());
        assertEquals(fileName, ctx.fileName());
        List<SignalCatalogEntry> catalog = service.engineeringCatalog();
        assertFalse(catalog.isEmpty());
        assertTrue(catalog.stream().allMatch(e -> sourceId.equals(e.key().sourceFile())));
        assertTrue(catalog.stream().noneMatch(e ->
                sourceId.equals("src-A") ? e.signalName().startsWith("UniqueB") : e.signalName().startsWith("UniqueA")));

        List<SignalCatalogKey> remapped = AnalysisMeasurementResolver.remapToCatalog(
                service.signalsModel().workspaceKeys(), catalog);
        assertEquals(3, remapped.size());
        assertTrue(remapped.stream().allMatch(k -> sourceId.equals(k.sourceFile())));
        GraphSeriesQuery query = ((ProjectMergedSignalStore) service.signalsModel().catalogStore()).graphQuery();
        for (SignalCatalogKey key : remapped) {
            List<SignalSamplePoint> samples = query.queryRange(key, 0, Long.MAX_VALUE / 4, sourceId);
            assertFalse(samples.isEmpty(), key.signalName() + " returned no samples");
            assertEquals(expectedFirstTs, samples.getFirst().timestampNanos());
        }
    }

    private void openTwoFileProject() throws Exception {
        System.setProperty("stellarai.home", tempDir.resolve("home").toAbsolutePath().toString());
        service = new DesktopAppService(
                new DesktopSession(ParserRegistry.load()),
                new SqliteProjectMetadataStore(tempDir.resolve("graph-resolve.db")));
        service.newProject("Lavasa Graph Resolve");
        Files.writeString(tempDir.resolve("MF4_A.mf4"), "stub");
        Files.writeString(tempDir.resolve("MF4_B.mf4"), "stub");
        service.workspace().addFile(WorkspaceFile.restore(
                "src-A", tempDir.resolve("MF4_A.mf4"), FileKind.MEASUREMENT_MF4, 1));
        service.workspace().addFile(WorkspaceFile.restore(
                "src-B", tempDir.resolve("MF4_B.mf4"), FileKind.MEASUREMENT_MF4, 1));
        service.selectMeasurement("src-A");
        retainedStore = twoFileMergedStore();
        service.signalsModel().replaceCatalog(retainedStore);
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

    private List<SignalCatalogKey> projectKeys(String... names) {
        List<SignalCatalogKey> keys = new ArrayList<>();
        for (String name : names) {
            keys.add(service.signalsModel().catalog().entries().stream()
                    .filter(e -> name.equals(e.signalName()))
                    .findFirst()
                    .orElseThrow()
                    .key());
        }
        return keys;
    }

    private static ProjectMergedSignalStore twoFileMergedStore() {
        return ProjectMergedSignalStore.fromMembers(List.of(
                member("src-A", "MF4_A.mf4", 1_000L, true),
                member("src-B", "MF4_B.mf4", 10_000L, false)
        ), "2 measurements", "veh.dbc");
    }

    private static ProjectMergedSignalStore.Member member(
            String identity, String display, long t0, boolean sourceA
    ) {
        InMemorySignalCatalogStore store = new InMemorySignalCatalogStore(display, "veh.dbc", 200);
        List<CanonicalSignal> rows = new ArrayList<>();
        rows.add(can("VehicleSpeed", t0, 40));
        rows.add(can("EngineRPM", t0, 900));
        rows.add(can("CoolantTemp", t0, 83));
        if (sourceA) {
            rows.add(can("UniqueA1", t0, 1));
            rows.add(can("UniqueA2", t0, 2));
            rows.add(can("UniqueA3", t0, 3));
        } else {
            rows.add(can("UniqueB1", t0, 1));
            rows.add(can("UniqueB2", t0, 2));
            rows.add(can("UniqueB3", t0, 3));
        }
        store.accept(rows);
        return new ProjectMergedSignalStore.Member(store, identity, "veh.dbc", display, "veh.dbc");
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

    private static final class OptionalFile {
        static java.util.Optional<WorkspaceFile> none() {
            return java.util.Optional.empty();
        }
    }
}
