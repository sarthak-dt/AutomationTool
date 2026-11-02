package com.stellar.platform.desktop.ui;

import com.stellar.platform.desktop.presentation.DesktopAppService;
import com.stellar.platform.desktop.presentation.GraphInvestigationState;
import com.stellar.platform.desktop.presentation.ProjectSaveState;
import com.stellar.platform.desktop.session.DesktopSession;
import com.stellar.platform.desktop.ui.graph.EChartsAnalyticsPane;
import com.stellar.platform.domain.CanonicalSignal;
import com.stellar.platform.domain.SourceReference;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSamplePoint;
import com.stellar.platform.domain.catalog.SignalSourceType;
import com.stellar.platform.processing.catalog.InMemorySignalCatalogStore;
import com.stellar.platform.processing.graph.GraphRenderSeries;
import com.stellar.platform.processing.graph.GraphViewportEngine;
import com.stellar.platform.processing.graph.GraphViewportRequest;
import com.stellar.platform.processing.graph.LodGraphSeriesQuery;
import com.stellar.platform.processing.graph.MinMaxLodReducer;
import com.stellar.platform.storage.metadata.SqliteProjectMetadataStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Phase7GraphLodAndLifecycleTest {

    @TempDir Path temp;
    private DesktopAppService service;
    private GraphController graph;

    @AfterEach
    void tearDown() {
        if (graph != null) {
            graph.dispose();
        }
        if (service != null) {
            service.shutdown();
        }
    }

    @Test
    void millionSamplesDoNotBecomeMillionBucketsOn1500pxPlot() {
        List<SignalSamplePoint> samples = new ArrayList<>(1_000_000);
        for (int i = 0; i < 1_000_000; i++) {
            samples.add(new SignalSamplePoint(i, i % 50, null, null));
        }
        int budget = 1500 * 2;
        assertTrue(MinMaxLodReducer.reduce(samples, budget).size() <= budget + 1);
    }

    @Test
    void viewportEngineBoundsBucketsByPixelBudgetNotSourceLength() {
        SignalCatalogKey key = new SignalCatalogKey(
                "Speed", SignalSourceType.DBC_CAN, "Msg", 0x100, 1, "run.mf4", "veh.dbc");
        CountingQuery query = new CountingQuery(key, 5_000_000);
        GraphViewportEngine bounded = new GraphViewportEngine(query);
        List<GraphRenderSeries> rendered = bounded.render(new GraphViewportRequest(List.of(key), 0, 5_000_000, 1500));
        assertEquals(1, rendered.size());
        assertTrue(rendered.getFirst().buckets().size() <= 1500 * 2);
        assertEquals(1, query.bucketQueries.get());
        assertEquals(0, query.rangeQueries.get(), "LOD path must not pull the raw million-sample range");
    }

    @Test
    void twentyMembersFifteenHiddenQueryOnlyFiveVisible() {
        openWithSignals(20);
        GraphInvestigationState inv = service.graphInvestigation();
        List<SignalCatalogKey> keys = service.signalsModel().workspaceKeys();
        assertEquals(20, keys.size());
        for (int i = 5; i < 20; i++) {
            inv.hide(keys.get(i));
        }
        graph = boundGraph();
        assertEquals(5, graph.visibleKeysForTest().size());
        assertEquals(15, inv.hiddenKeys().size());
        assertEquals(20, service.signalsModel().workspaceKeys().size());
    }

    @Test
    void multiSignalLayoutsKeepMembershipAndOnlyVisibleKeys() {
        openWithSignals(20);
        GraphInvestigationState inv = service.graphInvestigation();
        graph = boundGraph();
        for (int count : List.of(1, 5, 10, 20)) {
            List<SignalCatalogKey> slice = service.signalsModel().workspaceKeys().subList(0, count);
            assertEquals(count, inv.visibleKeys(slice).size());
        }
        inv.setLayoutMode(GraphInvestigationState.OVERLAY);
        assertEquals(20, inv.visibleKeys(service.signalsModel().workspaceKeys()).size());
        inv.setLayoutMode(GraphInvestigationState.STACKED);
        assertEquals(20, inv.visibleKeys(service.signalsModel().workspaceKeys()).size());
    }

    @Test
    void staleViewportIsRejectedAndLatestGenerationWins() {
        openWithSignals(1);
        graph = boundGraph();
        long current = graph.generationForTest();
        int session = graph.sessionGenerationForTest();
        graph.bumpGenerationForTest();
        assertFalse(graph.acceptViewportResult(current, session));
        assertTrue(graph.viewportRejectedStaleForTest() >= 1);
        assertTrue(graph.acceptViewportResult(graph.generationForTest(), session));
        assertTrue(graph.viewportAcceptedForTest() >= 1);
    }

    @Test
    void tenClearViewCyclesReturnGraphOwnedStateToBaseline() {
        openWithSignals(8);
        graph = boundGraph();
        GraphInvestigationState inv = service.graphInvestigation();
        EChartsAnalyticsPane echarts = new EChartsAnalyticsPane();
        int catalog = service.signalsModel().catalog().entries().size();
        for (int i = 0; i < 10; i++) {
            inv.setLiveTraceModels(8);
            inv.setLiveViewportResults(8);
            graph.clearGraphWorkingState();
            echarts.clearChartData();
            assertEquals(0, inv.liveTraceModels());
            assertEquals(0, inv.liveViewportResults());
            assertTrue(inv.hiddenKeys().isEmpty());
            assertTrue(service.signalsModel().workspaceKeys().isEmpty());
            service.signalsModel().addToWorkspace(service.signalsModel().catalog().entries().stream()
                    .map(e -> e.key()).toList());
        }
        graph.clearGraphWorkingState();
        assertEquals(0, inv.liveTraceModels());
        assertEquals(catalog, service.signalsModel().catalog().entries().size());
        assertFalse(graph.hasPendingGraphWorkForTest());
    }

    @Test
    void addZoomRemoveCyclesDoNotAccumulateRemovedTraceState() {
        openWithSignals(2);
        graph = boundGraph();
        GraphInvestigationState inv = service.graphInvestigation();
        List<SignalCatalogKey> keys = service.signalsModel().workspaceKeys();
        SignalCatalogKey a = keys.get(0);
        SignalCatalogKey b = keys.get(1);
        for (int i = 0; i < 8; i++) {
            inv.setViewport(1_000L * i, 2_000L * (i + 1));
            inv.hide(a);
            graph.removeTraceForTest(a);
            assertFalse(service.signalsModel().workspaceKeys().contains(a));
            assertTrue(service.signalsModel().workspaceKeys().contains(b));
            assertFalse(inv.isHidden(a));
            service.signalsModel().addToWorkspace(a);
        }
        assertEquals(2, service.signalsModel().workspaceKeys().size());
        assertEquals(2, service.signalsModel().catalog().entries().size());
    }

    @Test
    void fiveOpenCloseCyclesDoNotAccumulateGraphExecutorsOrMembership() {
        int lastStellar;
        for (int i = 0; i < 5; i++) {
            openWithSignals(3);
            graph = boundGraph();
            graph.dispose();
            graph = null;
            service.closeProject();
            service.shutdown();
            lastStellar = com.stellar.platform.desktop.diagnostics.JvmMemorySnapshot.capture().stellarThreadCount();
            service = null;
            assertTrue(lastStellar <= 2, "stellar threads after close cycle " + i + "=" + lastStellar);
        }
    }

    @Test
    void sustainedZoomAndCursorNotesCoalesceAutosave() throws Exception {
        openWithSignals(1);
        AtomicInteger writes = new AtomicInteger();
        service.saveCoordinatorForTest().addListener(writes::incrementAndGet);
        GraphInvestigationState inv = service.graphInvestigation();
        for (int i = 0; i < 40; i++) {
            inv.setViewport(1_000L + i, 50_000L + i);
            service.noteGraphLogicalChange();
            inv.setCursorA(2_000L + i);
            service.noteTransientGraphHover();
        }
        inv.setNotes("note");
        service.noteGraphLogicalChange();
        Thread.sleep(900);
        assertTrue(service.saveCoordinatorForTest().hasPendingAutosave()
                || service.projectSaveState() != ProjectSaveState.DIRTY
                || writes.get() < 40);
        assertTrue(writes.get() < 40, "must not write once per pointer event, writes=" + writes.get());
    }

    private void openWithSignals(int count) {
        System.setProperty("stellarai.home", temp.resolve("home").toAbsolutePath().toString());
        service = new DesktopAppService(
                new DesktopSession(), new SqliteProjectMetadataStore(temp.resolve("meta-" + System.nanoTime() + ".db")));
        service.newProject("Phase7 Graph");
        InMemorySignalCatalogStore store = new InMemorySignalCatalogStore("run.mf4", "veh.dbc", 8);
        List<CanonicalSignal> signals = new ArrayList<>(count);
        for (int i = 1; i <= count; i++) {
            signals.add(new CanonicalSignal(
                    "Speed", "Msg", 1_000L * i, 0x100, i, 10.0 + i, 10L, "km/h", null,
                    SourceReference.ofLine(i)));
        }
        store.accept(signals);
        service.signalsModel().replaceCatalog(store);
        service.signalsModel().addToWorkspace(service.signalsModel().catalog().entries().stream()
                .map(e -> e.key()).toList());
    }

    private GraphController boundGraph() {
        GraphController next = new GraphController();
        next.bindMembershipForTest(service);
        return next;
    }

    private static final class CountingQuery implements LodGraphSeriesQuery {
        private final SignalCatalogKey key;
        private final long samples;
        private final AtomicInteger bucketQueries = new AtomicInteger();
        private final AtomicInteger rangeQueries = new AtomicInteger();

        private CountingQuery(SignalCatalogKey key, long samples) {
            this.key = key;
            this.samples = samples;
        }

        @Override
        public List<com.stellar.platform.processing.graph.GraphBucket> queryBuckets(
                SignalCatalogKey k, long start, long end, int budget) {
            bucketQueries.incrementAndGet();
            List<SignalSamplePoint> fake = new ArrayList<>(budget);
            for (int i = 0; i < budget; i++) {
                fake.add(new SignalSamplePoint(start + i, i, null, null));
            }
            return MinMaxLodReducer.reduce(fake, budget);
        }

        @Override
        public long sampleCount(SignalCatalogKey k) {
            return key.equals(k) ? samples : 0;
        }

        @Override
        public String unit(SignalCatalogKey k) {
            return "km/h";
        }

        @Override
        public List<SignalSamplePoint> queryRange(SignalCatalogKey k, long start, long end) {
            rangeQueries.incrementAndGet();
            return List.of();
        }
    }
}
