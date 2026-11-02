package com.stellar.platform.desktop.ui;

import com.stellar.platform.desktop.presentation.DesktopAppService;
import com.stellar.platform.desktop.session.DesktopSession;
import com.stellar.platform.desktop.support.JavaFxTestSupport;
import com.stellar.platform.desktop.ui.graph.AutomotiveSignalCanvas;
import com.stellar.platform.domain.CanonicalSignal;
import com.stellar.platform.domain.SourceReference;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSourceType;
import com.stellar.platform.processing.graph.GraphBucket;
import com.stellar.platform.processing.graph.GraphRenderSeries;
import com.stellar.platform.processing.graph.GraphSignalSessionStore;
import com.stellar.platform.processing.graph.GraphViewportEngine;
import com.stellar.platform.processing.graph.GraphViewportRequest;
import com.stellar.platform.storage.metadata.SqliteProjectMetadataStore;
import javafx.scene.layout.VBox;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Close vs Hide/Show, legend visibility, Full Screen Inspector, timestamp X-axis.
 */
class GraphInteractionStateTest {

    @TempDir Path temp;
    private DesktopAppService service;
    private GraphController graph;
    private GraphSignalSessionStore store;

    @BeforeAll
    static void startFx() throws Exception {
        JavaFxTestSupport.startToolkit();
    }

    @AfterEach
    void tearDown() {
        if (graph != null) {
            graph.dispose();
        }
        if (store != null) {
            store.close();
        }
        if (service != null) {
            service.shutdown();
        }
    }

    @Test
    void closingChipRemovesMembershipAndUnchecksSelection() {
        bindAbc();
        selectAll();
        assertTrue(graph.isSelectedForTest(keyB()));
        assertEquals(3, graph.selectedSignalCountForTest());

        List<SignalCatalogKey> explorerOrder = List.of(keyA(), keyB(), keyC());
        graph.closeChipForTest(keyB());

        assertFalse(graph.isSelectedForTest(keyB()), "close must clear analysis membership");
        assertTrue(graph.isSelectedForTest(keyA()));
        assertTrue(graph.isSelectedForTest(keyC()));
        assertEquals(2, graph.selectedSignalCountForTest());
        assertFalse(service.signalsModel().workspaceKeys().contains(keyB()));
        // Catalog order unchanged (workspace membership ≠ explorer catalog order)
        assertTrue(service.signalsModel().graphCatalog().stream()
                .map(e -> e.key())
                .toList()
                .containsAll(explorerOrder));
    }

    @Test
    void deselectReselectSameSignalRendersAgain() {
        bindAbc();
        service.signalsModel().addToWorkspace(List.of(keyA()));
        graph.applyMembershipFromWorkspaceForTest();
        graph.tryFitRangeForTest();
        List<GraphRenderSeries> first = renderAll(400);
        assertEquals(1, first.size());
        assertFalse(first.getFirst().buckets().isEmpty(), "initial select must have samples");
        long start = graph.viewportStartForTest();
        long end = graph.viewportEndForTest();

        for (int i = 0; i < 10; i++) {
            graph.toggleMembershipForTest(keyA(), false);
            assertFalse(graph.isSelectedForTest(keyA()));
            assertEquals(0, graph.selectedSignalCountForTest());
            // Catalog/store must still resolve the signal
            assertTrue(service.signalsModel().findEntry(keyA()).isPresent());

            graph.toggleMembershipForTest(keyA(), true);
            assertTrue(graph.isSelectedForTest(keyA()));
            assertTrue(graph.isVisibleForTest(keyA()), "reselect must restore visible=true");
            List<GraphRenderSeries> again = renderAll(400);
            assertEquals(1, again.size());
            assertFalse(again.getFirst().buckets().isEmpty(),
                    "cycle " + i + ": reselect must not yield empty viewport series");
            assertEquals(keyA(), again.getFirst().key());
        }
        // Viewport should remain a usable positive interval
        assertTrue(graph.viewportEndForTest() > graph.viewportStartForTest());
        assertTrue(end > start);
    }

    @Test
    void deselectOneAmongManyPreservesViewportAndRestoresSeries() {
        bindAbc();
        selectAll();
        graph.tryFitRangeForTest();
        graph.setViewportForTest(0L, 2L);
        long start = graph.viewportStartForTest();
        long end = graph.viewportEndForTest();

        graph.toggleMembershipForTest(keyB(), false);
        assertEquals(2, graph.selectedSignalCountForTest());
        assertEquals(start, graph.viewportStartForTest());
        assertEquals(end, graph.viewportEndForTest());

        graph.toggleMembershipForTest(keyB(), true);
        assertTrue(graph.isVisibleForTest(keyB()));
        assertEquals(start, graph.viewportStartForTest());
        assertEquals(end, graph.viewportEndForTest());
        List<GraphRenderSeries> series = renderAll(400);
        assertEquals(3, series.size());
        assertTrue(series.stream().allMatch(s -> !s.buckets().isEmpty()));
    }

    @Test
    void hideThenUncheckThenRecheckForcesVisible() {
        bindAbc();
        selectAll();
        seedRenderedSeries();
        graph.hideTraceForTest(keyA());
        assertFalse(graph.isVisibleForTest(keyA()));
        assertTrue(graph.isSelectedForTest(keyA()));

        graph.toggleMembershipForTest(keyA(), false);
        assertFalse(graph.isSelectedForTest(keyA()));

        graph.toggleMembershipForTest(keyA(), true);
        assertTrue(graph.isSelectedForTest(keyA()));
        assertTrue(graph.isVisibleForTest(keyA()), "checkbox ON must not inherit stale hidden");
    }

    @Test
    void fastDeselectReselectRejectsStaleGeneration() {
        bindAbc();
        service.signalsModel().addToWorkspace(List.of(keyA()));
        graph.applyMembershipFromWorkspaceForTest();
        graph.tryFitRangeForTest();
        int session = graph.sessionGenerationForTest();

        // Simulate in-flight query started before rapid OFF→ON.
        long staleGen = graph.generationForTest();
        graph.toggleMembershipForTest(keyA(), false);
        assertFalse(graph.acceptViewportResult(staleGen, session),
                "deselect must invalidate in-flight generation");

        long midGen = graph.generationForTest();
        graph.toggleMembershipForTest(keyA(), true);
        assertTrue(graph.isSelectedForTest(keyA()));
        assertTrue(graph.isVisibleForTest(keyA()));
        // Mid-toggle generation must not overwrite final membership query.
        assertFalse(graph.acceptViewportResult(midGen, session));

        List<GraphRenderSeries> series = renderAll(400);
        assertEquals(1, series.size());
        assertFalse(series.getFirst().buckets().isEmpty());
        assertTrue(graph.acceptViewportResult(graph.generationForTest(), session));
    }

    @Test
    void legendToggleHidesWithoutUnchecking() {
        bindAbc();
        selectAll();
        graph.tryFitRangeForTest();
        // Seed rendered series so hide can compose without a live FX canvas query.
        seedRenderedSeries();

        int colorBefore = service.graphInvestigation().colorIndex(keyB());
        graph.legendToggleForTest(keyB());

        assertTrue(graph.isSelectedForTest(keyB()), "checkbox/membership stays selected");
        assertFalse(graph.isVisibleForTest(keyB()), "visibility only");
        assertEquals(3, graph.selectedSignalCountForTest());
        assertEquals(2, graph.visibleKeysForTest().size());
        assertTrue(service.graphInvestigation().isHidden(keyB()));
        assertEquals(colorBefore, service.graphInvestigation().colorIndex(keyB()), "colour assignment retained");

        graph.legendToggleForTest(keyB());
        assertTrue(graph.isVisibleForTest(keyB()));
        assertFalse(service.graphInvestigation().isHidden(keyB()));
        assertEquals(colorBefore, service.graphInvestigation().colorIndex(keyB()));
    }

    @Test
    void rowHideAndLegendShareOneVisibilityState() {
        bindAbc();
        selectAll();
        seedRenderedSeries();
        graph.hideTraceForTest(keyB());
        assertFalse(graph.isVisibleForTest(keyB()));
        assertTrue(graph.isSelectedForTest(keyB()));

        graph.legendToggleForTest(keyB()); // show via legend
        assertTrue(graph.isVisibleForTest(keyB()));
        assertFalse(service.graphInvestigation().isHidden(keyB()));
    }

    @Test
    void fullScreenAllowsShowInspectorWithoutClearingFocus() throws Exception {
        bindAbc();
        injectInspectorPanel();
        selectAll();
        graph.focusSignalForTest(keyA());
        JavaFxTestSupport.runOnFx("open inspector", () -> graph.setInspectorOpenForTest(true));
        JavaFxTestSupport.runOnFx("enter fullscreen", graph::enterFullScreenForTest);
        assertFalse(graph.inspectorManagedForTest());
        assertEquals(keyA(), graph.focusedExplorerKeyForTest());

        JavaFxTestSupport.runOnFx("show inspector in FS", graph::toggleInspectorForTest);
        assertTrue(graph.inspectorManagedForTest());
        assertTrue(graph.focusGraphForTest());
        assertEquals(keyA(), graph.focusedExplorerKeyForTest());
        assertTrue(graph.inspectorToggledDuringFullScreenForTest());

        JavaFxTestSupport.runOnFx("hide inspector in FS", graph::toggleInspectorForTest);
        assertFalse(graph.inspectorManagedForTest());
        assertEquals(keyA(), graph.focusedExplorerKeyForTest());
    }

    @Test
    void fullScreenHidesInspectorAndRestoresPreviousOpenState() throws Exception {
        bindAbc();
        injectInspectorPanel();
        JavaFxTestSupport.runOnFx("open inspector", () -> graph.setInspectorOpenForTest(true));
        assertTrue(graph.inspectorManagedForTest());

        long start = graph.viewportStartForTest();
        long end = graph.viewportEndForTest();
        selectAll();
        int selected = graph.selectedSignalCountForTest();

        JavaFxTestSupport.runOnFx("enter fullscreen", graph::enterFullScreenForTest);
        assertTrue(graph.focusGraphForTest());
        assertFalse(graph.inspectorManagedForTest());
        assertFalse(graph.inspectorVisibleForTest());
        assertEquals(selected, graph.selectedSignalCountForTest());
        assertEquals(start, graph.viewportStartForTest());
        assertEquals(end, graph.viewportEndForTest());

        JavaFxTestSupport.runOnFx("exit fullscreen", graph::exitFullScreenForTest);
        assertFalse(graph.focusGraphForTest());
        assertTrue(graph.inspectorManagedForTest());
        assertTrue(graph.inspectorVisibleForTest());
    }

    @Test
    void fullScreenKeepsManuallyCollapsedInspectorCollapsed() throws Exception {
        bindAbc();
        injectInspectorPanel();
        JavaFxTestSupport.runOnFx("collapse inspector", () -> graph.setInspectorOpenForTest(false));
        JavaFxTestSupport.runOnFx("enter fullscreen", graph::enterFullScreenForTest);
        assertFalse(graph.inspectorManagedForTest());
        JavaFxTestSupport.runOnFx("exit fullscreen", graph::exitFullScreenForTest);
        assertFalse(graph.inspectorManagedForTest(), "manually collapsed Inspector must stay collapsed");
    }

    @Test
    void renderUsesTimestampNotSampleIndex() {
        bindTimestamps();
        graph.setViewportForTest(0L, 11_000_000_000L);
        List<GraphRenderSeries> series = renderAll(400);
        assertEquals(1, series.size());
        List<Long> times = new ArrayList<>();
        for (GraphBucket b : series.getFirst().buckets()) {
            times.add(b.firstTimestampNanos());
            times.add(b.lastTimestampNanos());
        }
        assertTrue(times.stream().anyMatch(t -> t == 100_000_000L));
        assertTrue(times.stream().anyMatch(t -> t == 2_500_000_000L));
        assertTrue(times.stream().anyMatch(t -> t == 10_000_000_000L));
        assertFalse(times.containsAll(List.of(0L, 1L, 2L)) && times.size() <= 6,
                "X domain must be real timestamps, not ordinal indices");
    }

    @Test
    void overlayPlacesMultiRateSignalsOnSharedTimestampAxis() {
        System.setProperty("stellarai.home", temp.resolve("home-multi").toAbsolutePath().toString());
        service = new DesktopAppService(
                new DesktopSession(), new SqliteProjectMetadataStore(temp.resolve("meta-multi.db")));
        service.newProject("MultiRate");
        store = GraphSignalSessionStore.create("run.mf4", "veh.dbc");
        store.accept(List.of(
                sig("EngineRPM", "OBD2", "rpm", 1, 1_000_000_000L, 10.0),
                sig("EngineRPM", "OBD2", "rpm", 1, 3_000_000_000L, 20.0),
                sig("VehicleSpeed", "OBD2", "km/h", 2, 2_000_000_000L, 100.0),
                sig("VehicleSpeed", "OBD2", "km/h", 2, 4_000_000_000L, 200.0)
        ));
        service.signalsModel().replaceCatalog(store);
        graph = new GraphController();
        graph.bindMembershipForTest(service);
        service.signalsModel().addToWorkspace(List.of(keyA(), keyB()));
        graph.applyMembershipFromWorkspaceForTest();
        graph.setViewportForTest(0L, 5_000_000_000L);
        graph.setLayoutModeForTest(false);

        List<GraphRenderSeries> series = renderAll(800);
        assertEquals(2, series.size());
        GraphRenderSeries a = series.stream().filter(s -> s.key().equals(keyA())).findFirst().orElseThrow();
        GraphRenderSeries b = series.stream().filter(s -> s.key().equals(keyB())).findFirst().orElseThrow();
        assertTrue(a.buckets().stream().anyMatch(x -> x.lastTimestampNanos() == 1_000_000_000L
                || x.firstTimestampNanos() == 1_000_000_000L));
        assertTrue(a.buckets().stream().anyMatch(x -> x.lastTimestampNanos() == 3_000_000_000L
                || x.firstTimestampNanos() == 3_000_000_000L));
        assertTrue(b.buckets().stream().anyMatch(x -> x.lastTimestampNanos() == 2_000_000_000L
                || x.firstTimestampNanos() == 2_000_000_000L));
        assertTrue(b.buckets().stream().anyMatch(x -> x.lastTimestampNanos() == 4_000_000_000L
                || x.firstTimestampNanos() == 4_000_000_000L));
        assertEquals(graph.viewportStartForTest(), a.viewportStartTimestampNanos());
        assertEquals(graph.viewportStartForTest(), b.viewportStartTimestampNanos());
    }

    private void bindAbc() {
        System.setProperty("stellarai.home", temp.resolve("home").toAbsolutePath().toString());
        service = new DesktopAppService(
                new DesktopSession(), new SqliteProjectMetadataStore(temp.resolve("meta.db")));
        service.newProject("Interaction");
        store = GraphSignalSessionStore.create("run.mf4", "veh.dbc");
        store.accept(List.of(
                sig("EngineRPM", "OBD2", "rpm", 1, 0L, 10.0),
                sig("EngineRPM", "OBD2", "rpm", 1, 1L, 20.0),
                sig("VehicleSpeed", "OBD2", "km/h", 2, 0L, 100.0),
                sig("VehicleSpeed", "OBD2", "km/h", 2, 1L, 200.0),
                sig("CoolantTemp", "OBD2", "degC", 3, 0L, 5.0),
                sig("CoolantTemp", "OBD2", "degC", 3, 1L, 7.0)
        ));
        service.signalsModel().replaceCatalog(store);
        graph = new GraphController();
        graph.bindMembershipForTest(service);
    }

    private void bindTimestamps() {
        System.setProperty("stellarai.home", temp.resolve("home-ts").toAbsolutePath().toString());
        service = new DesktopAppService(
                new DesktopSession(), new SqliteProjectMetadataStore(temp.resolve("meta-ts.db")));
        service.newProject("Timestamps");
        store = GraphSignalSessionStore.create("run.mf4", "veh.dbc");
        store.accept(List.of(
                sig("EngineRPM", "OBD2", "rpm", 1, 100_000_000L, 5.0),
                sig("EngineRPM", "OBD2", "rpm", 1, 2_500_000_000L, 7.0),
                sig("EngineRPM", "OBD2", "rpm", 1, 10_000_000_000L, 9.0)
        ));
        service.signalsModel().replaceCatalog(store);
        graph = new GraphController();
        graph.bindMembershipForTest(service);
        service.signalsModel().addToWorkspace(List.of(keyA()));
        graph.applyMembershipFromWorkspaceForTest();
    }

    private void selectAll() {
        service.signalsModel().addToWorkspace(List.of(keyA(), keyB(), keyC()));
        graph.applyMembershipFromWorkspaceForTest();
    }

    private void seedRenderedSeries() {
        List<GraphRenderSeries> series = renderAll(400);
        try {
            Field f = GraphController.class.getDeclaredField("lastRendered");
            f.setAccessible(true);
            f.set(graph, series);
            Field canvas = GraphController.class.getDeclaredField("graphCanvas");
            canvas.setAccessible(true);
            AutomotiveSignalCanvas c = new AutomotiveSignalCanvas();
            canvas.set(graph, c);
            c.setSeries(series);
            c.setHiddenKeys(java.util.Set.of());
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private void injectInspectorPanel() throws Exception {
        JavaFxTestSupport.runOnFx("inject inspector", () -> {
            Field f = GraphController.class.getDeclaredField("inspectorPanel");
            f.setAccessible(true);
            f.set(graph, new VBox());
            graph.setInspectorOpenForTest(true);
        });
    }

    private List<GraphRenderSeries> renderAll(int width) {
        GraphViewportEngine engine = new GraphViewportEngine(store.graphQuery());
        List<GraphRenderSeries> out = new ArrayList<>();
        long start = graph.viewportStartForTest();
        long end = Math.max(start + 1, graph.viewportEndForTest());
        for (SignalCatalogKey key : service.signalsModel().workspaceKeys()) {
            out.addAll(engine.render(new GraphViewportRequest(List.of(key), start, end, width)));
        }
        return out;
    }

    private static CanonicalSignal sig(
            String name, String message, String unit, int channel, long ts, double value
    ) {
        return new CanonicalSignal(
                name, message, ts, 0x100L + channel, channel, value, (long) value, unit, null,
                SourceReference.ofLine(channel));
    }

    private static SignalCatalogKey keyA() {
        return new SignalCatalogKey("EngineRPM", SignalSourceType.DBC_CAN, "OBD2", 0x101L, 1, "run.mf4", "veh.dbc");
    }

    private static SignalCatalogKey keyB() {
        return new SignalCatalogKey("VehicleSpeed", SignalSourceType.DBC_CAN, "OBD2", 0x102L, 2, "run.mf4", "veh.dbc");
    }

    private static SignalCatalogKey keyC() {
        return new SignalCatalogKey("CoolantTemp", SignalSourceType.DBC_CAN, "OBD2", 0x103L, 3, "run.mf4", "veh.dbc");
    }
}
