package com.stellar.platform.desktop.ui;

import com.stellar.platform.desktop.presentation.DesktopAppService;
import com.stellar.platform.desktop.presentation.GraphInvestigationState;
import com.stellar.platform.desktop.session.DesktopSession;
import com.stellar.platform.domain.CanonicalSignal;
import com.stellar.platform.domain.SourceReference;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSourceType;
import com.stellar.platform.processing.graph.GraphRenderSeries;
import com.stellar.platform.processing.graph.GraphSignalSessionStore;
import com.stellar.platform.processing.graph.GraphViewportEngine;
import com.stellar.platform.processing.graph.GraphViewportRequest;
import com.stellar.platform.storage.metadata.SqliteProjectMetadataStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Multi-signal Overlay/Stacked correctness: focus ≠ visibility, navigation preserves session,
 * shared X viewport, per-signal Y metadata, no membership collapse on focus.
 */
class GraphMultiSignalCorrectnessTest {

    @TempDir Path temp;
    private DesktopAppService service;
    private GraphController graph;
    private GraphSignalSessionStore store;

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
    void focusDoesNotFilterVisibleKeys_threeSignalsStayVisible() {
        bindAbc();
        selectAll();
        assertEquals(3, graph.visibleKeysForTest().size());

        graph.focusSignalForTest(keyB());
        assertEquals(keyB(), graph.focusedExplorerKeyForTest());
        assertEquals(keyB(), graph.investigationFocusForTest());
        assertEquals(3, graph.visibleKeysForTest().size(), "focus must not collapse render membership");
        assertTrue(graph.visibleKeysForTest().containsAll(List.of(keyA(), keyB(), keyC())));

        graph.focusSignalForTest(keyC());
        assertEquals(keyC(), graph.focusedExplorerKeyForTest());
        assertEquals(3, graph.visibleKeysForTest().size());
    }

    @Test
    void investigationStateVisibleKeysIgnoresFocus() {
        GraphInvestigationState inv = new GraphInvestigationState();
        inv.beginSession();
        List<SignalCatalogKey> membership = List.of(keyA(), keyB(), keyC());
        inv.setFocus(keyB());
        assertEquals(3, inv.visibleKeys(membership).size());
        inv.hide(keyA());
        assertEquals(List.of(keyB(), keyC()), inv.visibleKeys(membership));
    }

    @Test
    void overlayEngineProducesThreeIndependentSeries() {
        bindAbc();
        selectAll();
        graph.tryFitRangeForTest();
        List<GraphRenderSeries> series = renderAllVisible(800);
        assertEquals(3, series.size());
        Set<SignalCatalogKey> keys = series.stream().map(GraphRenderSeries::key).collect(Collectors.toSet());
        assertEquals(Set.of(keyA(), keyB(), keyC()), keys);
        assertTrue(series.stream().anyMatch(s -> "rpm".equals(s.unit())));
        assertTrue(series.stream().anyMatch(s -> "km/h".equals(s.unit())));
        assertTrue(series.stream().anyMatch(s -> "degC".equals(s.unit())));
        // Shared X request range
        long start = graph.viewportStartForTest();
        long end = graph.viewportEndForTest();
        assertTrue(end > start);
        assertEquals(0L, start);
        assertEquals(2L, end);
    }

    @Test
    void stackedAndOverlayModeSwitchPreservesSelectionAndSession() {
        bindAbc();
        selectAll();
        int gen = graph.sessionGenerationForTest();
        assertEquals(GraphInvestigationState.STACKED, graph.layoutModeForTest());

        graph.setLayoutModeForTest(false);
        assertEquals(GraphInvestigationState.OVERLAY, graph.layoutModeForTest());
        assertEquals(List.of(keyA(), keyB(), keyC()), service.signalsModel().workspaceKeys());
        assertEquals(3, graph.visibleKeysForTest().size());
        assertEquals(gen, graph.sessionGenerationForTest(), "layout switch must not begin a new session");

        graph.setLayoutModeForTest(true);
        assertEquals(GraphInvestigationState.STACKED, graph.layoutModeForTest());
        assertEquals(3, graph.visibleKeysForTest().size());
        assertEquals(gen, graph.sessionGenerationForTest());
    }

    @Test
    void layoutModeSwitchPreservesValidViewport() {
        bindAbcWide();
        selectAll();
        graph.tryFitRangeForTest();
        graph.setViewportForTest(60L, 80L);
        assertEquals(60L, graph.viewportStartForTest());
        assertEquals(80L, graph.viewportEndForTest());

        graph.setLayoutModeForTest(false);
        assertEquals(60L, graph.viewportStartForTest(), "Overlay must keep shared viewport");
        assertEquals(80L, graph.viewportEndForTest());
        assertEquals(GraphInvestigationState.OVERLAY, graph.layoutModeForTest());

        graph.setLayoutModeForTest(true);
        assertEquals(60L, graph.viewportStartForTest(), "Stacked must keep shared viewport");
        assertEquals(80L, graph.viewportEndForTest());
        assertEquals(GraphInvestigationState.STACKED, graph.layoutModeForTest());
    }

    @Test
    void invalidViewportRecoversToSelectedVisibleFullRange() {
        System.setProperty("stellarai.home", temp.resolve("home-recover").toAbsolutePath().toString());
        service = new DesktopAppService(
                new DesktopSession(), new SqliteProjectMetadataStore(temp.resolve("meta-recover.db")));
        service.newProject("Recover");
        store = GraphSignalSessionStore.create("run.mf4", "veh.dbc");
        long fullStart = 398_000_000L;
        long fullEnd = 600_124_000_000L;
        store.accept(List.of(
                sig("EngineRPM", "OBD2", "rpm", 1, fullStart, 10.0),
                sig("EngineRPM", "OBD2", "rpm", 1, fullEnd, 30.0),
                sig("VehicleSpeed", "OBD2", "km/h", 2, fullStart + 1, 100.0),
                sig("VehicleSpeed", "OBD2", "km/h", 2, fullEnd - 1, 300.0)
        ));
        service.signalsModel().replaceCatalog(store);
        graph = new GraphController();
        graph.bindMembershipForTest(service);
        service.signalsModel().addToWorkspace(List.of(keyA(), keyB()));
        graph.applyMembershipFromWorkspaceForTest();
        graph.tryFitRangeForTest();

        long collapsed = 338_960_000_000L;
        graph.setViewportForTest(collapsed, collapsed + 1L);

        graph.requestViewportForTest();

        assertEquals(fullStart, graph.viewportStartForTest());
        assertEquals(fullEnd, graph.viewportEndForTest());
    }

    @Test
    void overlayQueriesAllVisibleSignalsAgainstSharedViewport() {
        bindAbcWide();
        selectAll();
        graph.setViewportForTest(60L, 80L);
        graph.setLayoutModeForTest(false);
        List<GraphRenderSeries> series = renderAllVisible(800);
        assertEquals(3, series.size());
        for (GraphRenderSeries s : series) {
            assertEquals(60L, s.viewportStartTimestampNanos());
            assertEquals(80L, s.viewportEndTimestampNanos());
        }
    }

    @Test
    void stackedTracksShareXViewportWithIndependentSeries() {
        bindAbcWide();
        selectAll();
        graph.setViewportForTest(60L, 80L);
        graph.setLayoutModeForTest(true);
        List<GraphRenderSeries> series = renderAllVisible(800);
        assertEquals(3, series.size());
        assertEquals(3, series.stream().map(GraphRenderSeries::key).collect(Collectors.toSet()).size());
        assertTrue(series.stream().allMatch(s -> s.viewportStartTimestampNanos() == 60L));
        assertTrue(series.stream().allMatch(s -> s.viewportEndTimestampNanos() == 80L));
    }

    @Test
    void navigateAwayAndBackPreservesZoomedViewport() {
        bindAbcWide();
        selectAll();
        graph.setViewportForTest(100L, 150L);
        graph.simulateNavigateAwayAndBackForTest();
        assertEquals(100L, graph.viewportStartForTest());
        assertEquals(150L, graph.viewportEndForTest());
    }

    @Test
    void focusDoesNotCollapseSharedViewport() {
        bindAbcWide();
        selectAll();
        graph.setViewportForTest(100L, 150L);
        graph.focusSignalForTest(keyB());
        assertEquals(100L, graph.viewportStartForTest());
        assertEquals(150L, graph.viewportEndForTest());
        graph.focusSignalForTest(keyC());
        assertEquals(100L, graph.viewportStartForTest());
        assertEquals(150L, graph.viewportEndForTest());
    }

    @Test
    void trueSingleTimestampSignalGetsNonZeroDisplayViewportWithoutSyntheticSamples() {
        System.setProperty("stellarai.home", temp.resolve("home-single").toAbsolutePath().toString());
        service = new DesktopAppService(
                new DesktopSession(), new SqliteProjectMetadataStore(temp.resolve("meta-single.db")));
        service.newProject("SingleTs");
        store = GraphSignalSessionStore.create("run.mf4", "veh.dbc");
        long t = 338_960_000_000L;
        store.accept(List.of(sig("EngineRPM", "OBD2", "rpm", 1, t, 10.0)));
        service.signalsModel().replaceCatalog(store);
        graph = new GraphController();
        graph.bindMembershipForTest(service);
        service.signalsModel().addToWorkspace(List.of(keyA()));
        graph.applyMembershipFromWorkspaceForTest();
        graph.tryFitRangeForTest();

        assertTrue(graph.viewportEndForTest() > graph.viewportStartForTest());
        assertTrue(graph.viewportStartForTest() <= t && t <= graph.viewportEndForTest());

        List<GraphRenderSeries> series = renderAllVisible(400);
        assertEquals(1, series.size());
        assertFalse(series.getFirst().buckets().isEmpty(), "real sample must remain queryable");
        assertEquals(t, series.getFirst().buckets().getFirst().lastTimestampNanos());
    }

    @Test
    void navigateSignalsAndBackPreservesMembershipLayoutAndScope() {
        bindAbc();
        selectAll();
        graph.setLayoutModeForTest(false);
        graph.focusSignalForTest(keyB());
        int gen = graph.sessionGenerationForTest();
        List<SignalCatalogKey> before = List.copyOf(service.signalsModel().workspaceKeys());

        graph.simulateNavigateAwayAndBackForTest();

        assertEquals(before, service.signalsModel().workspaceKeys());
        assertEquals(3, graph.visibleKeysForTest().size());
        assertEquals(GraphInvestigationState.OVERLAY, graph.layoutModeForTest());
        assertEquals(gen, graph.sessionGenerationForTest());
        assertNotEquals(0, graph.selectedSignalCountForTest());
        // Catalog store (scope) unchanged — still the same GraphQueryProvider session
        assertTrue(service.signalsModel().catalogStore() instanceof com.stellar.platform.processing.graph.GraphQueryProvider);
    }

    @Test
    void singleClickFocusDoesNotOpenMembershipOrReplaceOthers() {
        bindAbc();
        service.signalsModel().addToWorkspace(List.of(keyA(), keyB()));
        graph.applyMembershipFromWorkspaceForTest();
        assertEquals(2, graph.visibleKeysForTest().size());

        graph.singleClickExplorerForTest(keyC());
        assertEquals(keyC(), graph.focusedExplorerKeyForTest());
        assertEquals(2, graph.visibleKeysForTest().size(), "single-click must not add membership");
        assertFalse(service.signalsModel().workspaceKeys().contains(keyC()));

        graph.doubleClickExplorerForTest(keyC());
        assertEquals(3, service.signalsModel().workspaceKeys().size());
        assertEquals(3, graph.visibleKeysForTest().size());
    }

    @Test
    void resetViewFitsUnionOfSelectedRanges() {
        bindAbc();
        // A: 0-100, B: 50-200 → union 0-200
        store.accept(List.of(
                sig("EngineRPM", "OBD2", "rpm", 1, 0L, 10.0),
                sig("EngineRPM", "OBD2", "rpm", 1, 100L, 30.0),
                sig("VehicleSpeed", "OBD2", "km/h", 2, 50L, 100.0),
                sig("VehicleSpeed", "OBD2", "km/h", 2, 200L, 300.0)
        ));
        // refresh catalog metadata after extra samples
        service.signalsModel().replaceCatalog(store);
        graph.bindMembershipForTest(service);
        service.signalsModel().addToWorkspace(List.of(keyA(), keyB()));
        graph.applyMembershipFromWorkspaceForTest();
        graph.setViewportForTest(60L, 80L);
        graph.resetViewForTest();
        // Default viewport was captured on first fit; reset restores it.
        // Ensure a fit produces the union when defaults were from selected set.
        graph.tryFitRangeForTest();
        assertEquals(0L, graph.viewportStartForTest());
        assertEquals(200L, graph.viewportEndForTest());
    }

    @Test
    void addingSignalWhileZoomedPreservesViewportWhenStillUsable() {
        bindAbc();
        selectAllExceptC();
        graph.tryFitRangeForTest();
        graph.setViewportForTest(0L, 2L); // still overlaps all ABC data at 0,1,2
        long s = graph.viewportStartForTest();
        long e = graph.viewportEndForTest();
        graph.toggleMembershipForTest(keyC(), true);
        assertEquals(s, graph.viewportStartForTest());
        assertEquals(e, graph.viewportEndForTest());
        assertEquals(3, graph.visibleKeysForTest().size());
    }

    private void bindAbc() {
        System.setProperty("stellarai.home", temp.resolve("home").toAbsolutePath().toString());
        service = new DesktopAppService(
                new DesktopSession(), new SqliteProjectMetadataStore(temp.resolve("meta.db")));
        service.newProject("MultiSignal");
        store = GraphSignalSessionStore.create("run.mf4", "veh.dbc");
        store.accept(List.of(
                sig("EngineRPM", "OBD2", "rpm", 1, 0L, 10.0),
                sig("EngineRPM", "OBD2", "rpm", 1, 1L, 20.0),
                sig("EngineRPM", "OBD2", "rpm", 1, 2L, 30.0),
                sig("VehicleSpeed", "OBD2", "km/h", 2, 0L, 100.0),
                sig("VehicleSpeed", "OBD2", "km/h", 2, 1L, 200.0),
                sig("VehicleSpeed", "OBD2", "km/h", 2, 2L, 300.0),
                sig("CoolantTemp", "OBD2", "degC", 3, 0L, 5.0),
                sig("CoolantTemp", "OBD2", "degC", 3, 1L, 7.0),
                sig("CoolantTemp", "OBD2", "degC", 3, 2L, 9.0)
        ));
        service.signalsModel().replaceCatalog(store);
        graph = new GraphController();
        graph.bindMembershipForTest(service);
    }

    /** A: 0-100, B: 20-200, C: 50-150 → union full range 0-200 (TEST 1 ranges). */
    private void bindAbcWide() {
        System.setProperty("stellarai.home", temp.resolve("home-wide").toAbsolutePath().toString());
        service = new DesktopAppService(
                new DesktopSession(), new SqliteProjectMetadataStore(temp.resolve("meta-wide.db")));
        service.newProject("MultiSignalWide");
        store = GraphSignalSessionStore.create("run.mf4", "veh.dbc");
        store.accept(List.of(
                sig("EngineRPM", "OBD2", "rpm", 1, 0L, 10.0),
                sig("EngineRPM", "OBD2", "rpm", 1, 100L, 30.0),
                sig("VehicleSpeed", "OBD2", "km/h", 2, 20L, 100.0),
                sig("VehicleSpeed", "OBD2", "km/h", 2, 200L, 300.0),
                sig("CoolantTemp", "OBD2", "degC", 3, 50L, 5.0),
                sig("CoolantTemp", "OBD2", "degC", 3, 150L, 9.0)
        ));
        service.signalsModel().replaceCatalog(store);
        graph = new GraphController();
        graph.bindMembershipForTest(service);
    }

    private void selectAll() {
        service.signalsModel().addToWorkspace(List.of(keyA(), keyB(), keyC()));
        graph.applyMembershipFromWorkspaceForTest();
    }

    private void selectAllExceptC() {
        service.signalsModel().addToWorkspace(List.of(keyA(), keyB()));
        graph.applyMembershipFromWorkspaceForTest();
    }

    private List<GraphRenderSeries> renderAllVisible(int width) {
        GraphViewportEngine engine = new GraphViewportEngine(store.graphQuery());
        List<GraphRenderSeries> out = new ArrayList<>();
        long start = graph.viewportStartForTest();
        long end = Math.max(start + 1, graph.viewportEndForTest());
        for (SignalCatalogKey key : graph.visibleKeysForTest()) {
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
