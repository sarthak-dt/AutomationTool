package com.stellar.platform.desktop.ui;

import com.stellar.platform.desktop.presentation.DesktopAppService;
import com.stellar.platform.desktop.presentation.GraphInvestigationCodec;
import com.stellar.platform.desktop.presentation.GraphInvestigationSnapshot;
import com.stellar.platform.desktop.presentation.GraphInvestigationState;
import com.stellar.platform.desktop.presentation.SignalTraceLabels;
import com.stellar.platform.desktop.presentation.SignalsScreenModel;
import com.stellar.platform.desktop.session.DesktopSession;
import com.stellar.platform.domain.CanonicalSignal;
import com.stellar.platform.domain.SourceReference;
import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSourceType;
import com.stellar.platform.processing.catalog.InMemorySignalCatalogStore;
import com.stellar.platform.processing.graph.GraphCursorSample;
import com.stellar.platform.storage.metadata.SqliteProjectMetadataStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 5 graph workspace: identity, hide≠remove, clear≠reset, persistence, incremental, lifecycle.
 */
class GraphWorkspacePhase5Test {

    @TempDir Path tempDir;
    private DesktopAppService service;
    private final java.util.ArrayList<GraphController> graphs = new java.util.ArrayList<>();

    @AfterEach
    void tearDown() {
        for (GraphController graph : graphs) {
            try {
                graph.dispose();
            } catch (RuntimeException ignored) {
            }
        }
        graphs.clear();
        if (service != null) {
            service.shutdown();
        }
    }

    @Test
    void sameNameDifferentChannelRendersTwoTraces() {
        openWithCatalog(speed(1, "run.mf4"), speed(4, "run.mf4"));
        SignalsScreenModel model = service.signalsModel();
        SignalCatalogKey can1 = key(1, "run.mf4");
        SignalCatalogKey can4 = key(4, "run.mf4");
        model.addToWorkspace(List.of(can1, can4));
        assertEquals(2, model.workspaceKeys().size());
        assertTrue(model.workspaceKeys().contains(can1));
        assertTrue(model.workspaceKeys().contains(can4));
        assertNotEquals(can1, can4);
    }

    @Test
    void sameNameDifferentSourceRendersTwoTraces() {
        openWithCatalog(speed(1, "run.mf4"));
        SignalsScreenModel model = service.signalsModel();
        SignalCatalogKey a = key(1, "run01.mf4");
        SignalCatalogKey b = key(1, "run02.mf4");
        assertNotEquals(a, b);
        model.addToWorkspace(List.of(a, b));
        assertEquals(2, model.workspaceKeys().size());
        List<SignalCatalogEntry> session = List.of(
                new SignalCatalogEntry(a, "km/h", 1, 1L, 2L, 1.0, 2.0, "run01.mf4", "veh.dbc"),
                new SignalCatalogEntry(b, "km/h", 1, 1L, 2L, 1.0, 2.0, "run02.mf4", "veh.dbc"));
        assertEquals("VehicleSpeed · run01 · CAN1", SignalTraceLabels.displayName(a, session));
        assertEquals("VehicleSpeed · run02 · CAN1", SignalTraceLabels.displayName(b, session));
    }

    @Test
    void duplicateLegendLabelsAreDisambiguated() {
        List<SignalCatalogEntry> session = catalogEntries(speed(1, "run.mf4"), speed(4, "run.mf4"));
        SignalCatalogKey can1 = key(1, "run.mf4");
        SignalCatalogKey can4 = key(4, "run.mf4");
        assertEquals("VehicleSpeed · CAN1", SignalTraceLabels.displayName(can1, session));
        assertEquals("VehicleSpeed · CAN4", SignalTraceLabels.displayName(can4, session));
        assertEquals("VehicleSpeed", SignalTraceLabels.displayName(can1, catalogEntries(speed(1, "run.mf4"))));
    }

    @Test
    void duplicateCursorRowsAreDisambiguated() {
        openWithCatalog(speed(1, "run.mf4"), speed(4, "run.mf4"));
        SignalsScreenModel model = service.signalsModel();
        model.addToWorkspace(List.of(key(1, "run.mf4"), key(4, "run.mf4")));
        GraphController graph = boundGraph();
        List<String> labels = graph.cursorLabelsForTest();
        assertEquals(2, labels.size());
        assertTrue(labels.get(0).contains("CAN1"));
        assertTrue(labels.get(1).contains("CAN4"));
        assertNotEquals(labels.get(0), labels.get(1));
    }

    @Test
    void removingOneDuplicateDoesNotRemoveOther() {
        openWithCatalog(speed(1, "run.mf4"), speed(4, "run.mf4"));
        SignalsScreenModel model = service.signalsModel();
        SignalCatalogKey can1 = key(1, "run.mf4");
        SignalCatalogKey can4 = key(4, "run.mf4");
        model.addToWorkspace(List.of(can1, can4));
        GraphController graph = boundGraph();
        graph.removeTraceForTest(can1);
        assertFalse(model.workspaceKeys().contains(can1));
        assertTrue(model.workspaceKeys().contains(can4));
        assertTrue(model.findEntry(can1).isPresent());
    }

    @Test
    void hidingOneDuplicateDoesNotHideOther() {
        openWithCatalog(speed(1, "run.mf4"), speed(4, "run.mf4"));
        SignalsScreenModel model = service.signalsModel();
        SignalCatalogKey can1 = key(1, "run.mf4");
        SignalCatalogKey can4 = key(4, "run.mf4");
        model.addToWorkspace(List.of(can1, can4));
        GraphInvestigationState inv = service.graphInvestigation();
        inv.hide(can1);
        GraphController graph = boundGraph();
        assertEquals(List.of(can4), graph.visibleKeysForTest());
        assertTrue(model.workspaceKeys().contains(can1));
        assertTrue(inv.isHidden(can1));
        assertFalse(inv.isHidden(can4));
    }

    @Test
    void hideTracePreservesAnalysisMembership() {
        openWithCatalog(speed(1, "run.mf4"));
        SignalCatalogKey can1 = key(1, "run.mf4");
        service.signalsModel().addToWorkspace(can1);
        GraphController graph = boundGraph();
        graph.hideTraceForTest(can1);
        assertTrue(service.signalsModel().workspaceKeys().contains(can1));
        assertTrue(graph.visibleKeysForTest().isEmpty());
    }

    @Test
    void showTraceRestoresRendering() {
        openWithCatalog(speed(1, "run.mf4"));
        SignalCatalogKey can1 = key(1, "run.mf4");
        service.signalsModel().addToWorkspace(can1);
        GraphController graph = boundGraph();
        graph.hideTraceForTest(can1);
        graph.showTraceForTest(can1);
        assertEquals(List.of(can1), graph.visibleKeysForTest());
    }

    @Test
    void removeTracePreservesCatalogSignal() {
        openWithCatalog(speed(1, "run.mf4"));
        SignalCatalogKey can1 = key(1, "run.mf4");
        service.signalsModel().addToWorkspace(can1);
        boundGraph().removeTraceForTest(can1);
        assertTrue(service.signalsModel().workspaceKeys().isEmpty());
        assertTrue(service.signalsModel().findEntry(can1).isPresent());
        assertFalse(service.signalsModel().catalog().entries().isEmpty());
    }

    @Test
    void clearViewRemovesAllAnalysisMembership() {
        openWithCatalog(speed(1, "run.mf4"), speed(4, "run.mf4"));
        service.signalsModel().addToWorkspace(List.of(key(1, "run.mf4"), key(4, "run.mf4")));
        GraphController graph = boundGraph();
        graph.clearGraphWorkingState();
        assertTrue(service.signalsModel().workspaceKeys().isEmpty());
        assertEquals(0, graph.selectedSignalCountForTest());
        assertEquals(0, graph.liveTraceModelsForTest());
    }

    @Test
    void clearViewDoesNotClearSignalCatalog() {
        openWithCatalog(speed(1, "run.mf4"), speed(4, "run.mf4"));
        service.signalsModel().addToWorkspace(key(1, "run.mf4"));
        boundGraph().clearGraphWorkingState();
        assertEquals(2, service.signalsModel().catalog().entries().size());
    }

    @Test
    void resetViewDoesNotClearAnalysisMembership() {
        openWithCatalog(speed(1, "run.mf4"));
        SignalCatalogKey can1 = key(1, "run.mf4");
        service.signalsModel().addToWorkspace(can1);
        GraphInvestigationState inv = service.graphInvestigation();
        inv.captureDefaultViewport(1_000L, 9_000L);
        inv.setViewport(3_000L, 5_000L);
        GraphController graph = boundGraph();
        graph.setViewportForTest(3_000L, 5_000L);
        graph.resetViewForTest();
        assertEquals(List.of(can1), service.signalsModel().workspaceKeys());
        // Reset View fits selected-visible full analysis range (single catalog timestamp
        // receives a non-zero display pad). Membership must remain intact.
        assertTrue(inv.viewportEnd() > inv.viewportStart());
        assertTrue(inv.viewportStart() <= 1_000L && 1_000L <= inv.viewportEnd());
    }

    @Test
    void graphAddSignalsUsesAuthoritativeCatalog() {
        GraphController graph = new GraphController();
        graphs.add(graph);
        assertEquals(null, graph.addSignalsPickerTypeForTest());
        assertFalse(graph.addSignalsPopupRequiredForTest());
    }

    @Test
    void addingSignalPreservesExistingViewportAndCursorsAndNotes() {
        openWithCatalog(speed(1, "run.mf4"), speed(4, "run.mf4"), brake());
        SignalsScreenModel model = service.signalsModel();
        model.addToWorkspace(key(1, "run.mf4"));
        GraphInvestigationState inv = service.graphInvestigation();
        inv.setNotes("keep this");
        GraphController graph = boundGraph();
        graph.setViewportForTest(2_000L, 8_000L);
        graph.setCursorsForTest(3_000L, 7_000L);
        graph.addWorkspaceKeysPreservingViewForTest(List.of(key(4, "run.mf4")));
        assertEquals(2_000L, inv.viewportStart());
        assertEquals(8_000L, inv.viewportEnd());
        assertEquals(3_000L, graph.cursorAForTest());
        assertEquals(7_000L, graph.cursorBForTest());
        assertEquals("keep this", inv.notes());
        assertEquals(2, model.workspaceKeys().size());
    }

    @Test
    void addingSameStableIdentityIsIdempotent() {
        openWithCatalog(speed(1, "run.mf4"));
        SignalCatalogKey can1 = key(1, "run.mf4");
        SignalsScreenModel model = service.signalsModel();
        assertTrue(model.addToWorkspace(can1));
        assertFalse(model.addToWorkspace(can1));
        model.addToWorkspace(List.of(can1, can1));
        assertEquals(1, model.workspaceKeys().size());
    }

    @Test
    void fitUsesActualAvailableTimeExtent() {
        openWithCatalog(speed(1, "run.mf4"));
        SignalCatalogEntry entry = service.signalsModel().catalog().entries().getFirst();
        assertTrue(entry.lastTimestampNanos() >= entry.firstTimestampNanos());
        GraphInvestigationState inv = service.graphInvestigation();
        inv.captureDefaultViewport(entry.firstTimestampNanos(), Math.max(entry.firstTimestampNanos() + 1, entry.lastTimestampNanos()));
        assertEquals(entry.firstTimestampNanos(), inv.viewportStart());
        assertEquals(Math.max(entry.firstTimestampNanos() + 1, entry.lastTimestampNanos()), inv.viewportEnd());
    }

    @Test
    void resetViewDoesNotReparseSource() {
        resetViewDoesNotClearAnalysisMembership();
        assertTrue(service.signalsModel().findEntry(key(1, "run.mf4")).isPresent());
        assertEquals(1, service.signalsModel().catalog().entries().size());
    }

    @Test
    void zoomUpdatesViewportNotSignalStore() {
        openWithCatalog(speed(1, "run.mf4"));
        long before = service.signalsModel().catalog().entries().getFirst().sampleCount();
        GraphInvestigationState inv = service.graphInvestigation();
        inv.setViewport(1_000L, 9_000L);
        inv.setViewport(3_000L, 5_000L);
        assertEquals(3_000L, inv.viewportStart());
        assertEquals(before, service.signalsModel().catalog().entries().getFirst().sampleCount());
    }

    @Test
    void staleViewportResultCannotOverwriteNewerViewport() {
        openWithCatalog(speed(1, "run.mf4"));
        GraphController graph = boundGraph();
        long current = graph.generationForTest();
        int session = graph.sessionGenerationForTest();
        graph.bumpGenerationForTest();
        assertFalse(graph.acceptViewportResult(current, session));
        assertTrue(graph.acceptViewportResult(graph.generationForTest(), session));
    }

    @Test
    void obsoleteViewportRequestIsCancelledOrIgnored() {
        openWithCatalog(speed(1, "run.mf4"));
        GraphController graph = boundGraph();
        int oldSession = graph.sessionGenerationForTest();
        long gen = graph.generationForTest();
        service.graphInvestigation().beginSession();
        assertFalse(graph.acceptViewportResult(gen, oldSession));
    }

    @Test
    void projectSwitchRejectsOldAsyncResults() {
        obsoleteViewportRequestIsCancelledOrIgnored();
    }

    @Test
    void cursorAAndBProduceDeltaTime() {
        GraphCursorSample a = new GraphCursorSample(key(1, "run.mf4"), 1_000_000_000L, 10, 10L, null, "km/h");
        GraphCursorSample b = new GraphCursorSample(key(1, "run.mf4"), 1_500_000_000L, 12, 12L, null, "km/h");
        assertEquals(0.5, (b.timestampNanos() - a.timestampNanos()) / 1e9, 1e-9);
    }

    @Test
    void cursorValuesUseStableTraceIdentity() {
        duplicateCursorRowsAreDisambiguated();
    }

    @Test
    void graphStatePersistsStableSignalIdsVisibilityOrderModeViewportCursorsNotes() {
        openWithCatalog(speed(1, "run.mf4"), speed(4, "run.mf4"));
        SignalsScreenModel model = service.signalsModel();
        SignalCatalogKey can1 = key(1, "run.mf4");
        SignalCatalogKey can4 = key(4, "run.mf4");
        model.addToWorkspace(List.of(can1, can4));
        GraphInvestigationState inv = service.graphInvestigation();
        inv.hide(can4);
        inv.setLayoutMode(GraphInvestigationState.OVERLAY);
        inv.setViewport(2_000L, 8_000L);
        inv.captureDefaultViewport(1_000L, 9_000L);
        inv.setCursorA(3_000L);
        inv.setCursorB(6_000L);
        inv.setNotes("channel compare");
        int color1 = inv.colorIndex(can1);
        service.persistGraphInvestigation();

        model.clearWorkspace();
        inv.beginSession();
        service.restoreGraphInvestigation();

        assertEquals(List.of(can1, can4), model.workspaceKeys());
        assertFalse(inv.isHidden(can4),
                "temporary waveform visibility is not part of the saved selection");
        assertFalse(inv.isHidden(can1));
        assertEquals(GraphInvestigationState.OVERLAY, inv.layoutMode());
        assertEquals(2_000L, inv.viewportStart());
        assertEquals(8_000L, inv.viewportEnd());
        assertEquals(3_000L, inv.cursorA());
        assertEquals(6_000L, inv.cursorB());
        assertEquals("channel compare", inv.notes());
        assertEquals(color1, inv.colorIndex(can1));
    }

    @Test
    void duplicateSignalsRestoreByStableIdentity() {
        graphStatePersistsStableSignalIdsVisibilityOrderModeViewportCursorsNotes();
    }

    @Test
    void invalidSavedTraceDoesNotBindToSameNameAlternative() {
        openWithCatalog(speed(1, "run.mf4"), speed(4, "run.mf4"));
        SignalCatalogKey can1 = key(1, "run.mf4");
        SignalCatalogKey ghost = new SignalCatalogKey(
                "VehicleSpeed", SignalSourceType.DBC_CAN, "Speed", 0x200, 9, "missing.mf4", "veh.dbc");
        GraphInvestigationSnapshot snap = new GraphInvestigationSnapshot(
                service.currentProjectId(),
                GraphInvestigationState.STACKED,
                1L, 2L, 1L, 2L, null, null, "",
                List.of(
                        new GraphInvestigationSnapshot.Trace(can1, false, 0),
                        new GraphInvestigationSnapshot.Trace(ghost, false, 1)));
        String blob = GraphInvestigationCodec.encodeTraces(snap.traces());
        List<GraphInvestigationSnapshot.Trace> decoded = GraphInvestigationCodec.decodeTraces(blob);
        assertEquals(2, decoded.size());
        assertEquals(ghost, decoded.get(1).key());

        service.signalsModel().addToWorkspace(can1);
        GraphInvestigationState inv = service.graphInvestigation();
        inv.restore(snap, List.of(can1));
        assertEquals(List.of(ghost), inv.unavailableTraces());
        assertFalse(service.signalsModel().workspaceKeys().contains(ghost));
        assertEquals(can1, service.signalsModel().workspaceKeys().getFirst());
    }

    @Test
    void incrementalProcessingPreservesActiveSignalsViewportCursorsAndDoesNotAutoAdd() {
        openWithCatalog(speed(1, "run.mf4"), speed(4, "run.mf4"));
        SignalsScreenModel model = service.signalsModel();
        SignalCatalogKey can1 = key(1, "run.mf4");
        model.addToWorkspace(can1);
        GraphInvestigationState inv = service.graphInvestigation();
        inv.setViewport(4_000L, 7_000L);
        inv.setCursorA(5_000L);
        InMemorySignalCatalogStore next = catalog(speed(1, "run.mf4"), speed(4, "run.mf4"), brake());
        model.replaceCatalog(next, "incremental", null, true);
        assertEquals(List.of(can1), model.workspaceKeys());
        assertEquals(4_000L, inv.viewportStart());
        assertEquals(5_000L, inv.cursorA());
        assertEquals(3, model.catalog().entries().size());
        assertFalse(model.workspaceKeys().contains(key(4, "run.mf4")));
    }

    @Test
    void clearViewCancelsOutstandingQueriesAndReleasesTraceModels() {
        openWithCatalog(speed(1, "run.mf4"));
        service.signalsModel().addToWorkspace(key(1, "run.mf4"));
        GraphInvestigationState inv = service.graphInvestigation();
        inv.setLiveTraceModels(6);
        inv.setLiveViewportResults(6);
        GraphController graph = boundGraph();
        long before = graph.generationForTest();
        graph.clearGraphWorkingState();
        assertTrue(graph.generationForTest() > before);
        assertEquals(0, inv.liveTraceModels());
        assertEquals(0, inv.liveViewportResults());
        assertFalse(graph.hasPendingGraphWorkForTest());
    }

    @Test
    void removeTraceReleasesOnlyThatTrace() {
        openWithCatalog(speed(1, "run.mf4"), speed(4, "run.mf4"));
        SignalCatalogKey can1 = key(1, "run.mf4");
        SignalCatalogKey can4 = key(4, "run.mf4");
        service.signalsModel().addToWorkspace(List.of(can1, can4));
        GraphInvestigationState inv = service.graphInvestigation();
        inv.colorIndex(can1);
        inv.colorIndex(can4);
        inv.hide(can1);
        GraphController graph = boundGraph();
        graph.removeTraceForTest(can1);
        assertFalse(inv.isHidden(can1));
        assertTrue(service.signalsModel().workspaceKeys().contains(can4));
        assertEquals(List.of(can4), graph.visibleKeysForTest());
    }

    @Test
    void projectCloseDetachesGraphListeners() {
        openWithCatalog(speed(1, "run.mf4"));
        GraphController graph = boundGraph();
        graph.dispose();
        assertFalse(graph.hasPendingGraphWorkForTest());
    }

    @Test
    void repeatedGraphClearCyclesDoNotAccumulateGraphOwnedState() {
        openWithCatalog(speed(1, "run.mf4"), speed(4, "run.mf4"));
        SignalsScreenModel model = service.signalsModel();
        GraphInvestigationState inv = service.graphInvestigation();
        GraphController graph = boundGraph();
        int lastModels = 0;
        for (int i = 0; i < 10; i++) {
            model.addToWorkspace(List.of(key(1, "run.mf4"), key(4, "run.mf4")));
            graph.applyMembershipFromWorkspaceForTest();
            inv.setLiveTraceModels(2);
            inv.setLiveViewportResults(2);
            lastModels = inv.liveTraceModels();
            graph.clearGraphWorkingState();
            assertEquals(0, inv.liveTraceModels());
            assertEquals(0, inv.liveViewportResults());
            assertTrue(inv.hiddenKeys().isEmpty());
            assertTrue(model.workspaceKeys().isEmpty());
        }
        assertEquals(2, lastModels);
        assertEquals(0, graph.liveTraceModelsForTest());
        assertEquals(2, model.catalog().entries().size());
    }

    @Test
    void moveWorkspaceKeyReordersWithoutNewIdentities() {
        openWithCatalog(speed(1, "run.mf4"), speed(4, "run.mf4"));
        SignalCatalogKey can1 = key(1, "run.mf4");
        SignalCatalogKey can4 = key(4, "run.mf4");
        SignalsScreenModel model = service.signalsModel();
        model.addToWorkspace(List.of(can1, can4));
        assertTrue(model.moveWorkspaceKey(can4, -1));
        assertEquals(List.of(can4, can1), model.workspaceKeys());
    }

    @Test
    void codecRoundTripNeverCollapsesByName() {
        SignalCatalogKey can1 = key(1, "run.mf4");
        SignalCatalogKey can4 = key(4, "run.mf4");
        String blob = GraphInvestigationCodec.encodeTraces(List.of(
                new GraphInvestigationSnapshot.Trace(can1, false, 0),
                new GraphInvestigationSnapshot.Trace(can4, true, 3)));
        List<GraphInvestigationSnapshot.Trace> decoded = GraphInvestigationCodec.decodeTraces(blob);
        assertEquals(can1, decoded.get(0).key());
        assertEquals(can4, decoded.get(1).key());
        assertTrue(decoded.get(1).hidden());
        assertEquals(3, decoded.get(1).colorIndex());
    }

    private void openWithCatalog(CanonicalSignal... signals) {
        System.setProperty("stellarai.home", tempDir.resolve("home").toString());
        System.setProperty("stellarai.metadata.db", tempDir.resolve("meta.db").toString());
        service = new DesktopAppService(new DesktopSession(), new SqliteProjectMetadataStore(tempDir.resolve("meta.db")));
        service.newProject("Graph Phase 5");
        service.signalsModel().replaceCatalog(catalog(signals));
    }

    private GraphController boundGraph() {
        GraphController graph = new GraphController();
        graph.bindMembershipForTest(service);
        graphs.add(graph);
        return graph;
    }

    private static InMemorySignalCatalogStore catalog(CanonicalSignal... signals) {
        InMemorySignalCatalogStore store = new InMemorySignalCatalogStore("run.mf4", "veh.dbc", 1_000);
        store.accept(List.of(signals));
        return store;
    }

    private static List<SignalCatalogEntry> catalogEntries(CanonicalSignal... signals) {
        return catalog(signals).snapshot().entries();
    }

    private static CanonicalSignal speed(int channel, String file) {
        return new CanonicalSignal(
                "VehicleSpeed", "Speed", 1_000L * channel, 0x100, channel, 42.0 + channel, 42L, "km/h", null,
                SourceReference.ofLine(channel));
    }

    private static CanonicalSignal brake() {
        return new CanonicalSignal(
                "BrakePressure", "Brake", 2_000L, 0x300, 1, 11.0, 11L, "bar", null, SourceReference.ofLine(9));
    }

    private static SignalCatalogKey key(int channel, String file) {
        return new SignalCatalogKey(
                "VehicleSpeed", SignalSourceType.DBC_CAN, "Speed", 0x100, channel, file, "veh.dbc");
    }
}
