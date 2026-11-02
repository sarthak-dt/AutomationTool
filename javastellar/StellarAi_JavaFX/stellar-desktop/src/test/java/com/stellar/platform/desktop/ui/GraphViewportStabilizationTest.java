package com.stellar.platform.desktop.ui;

import com.stellar.platform.desktop.presentation.DesktopAppService;
import com.stellar.platform.desktop.session.DesktopSession;
import com.stellar.platform.desktop.support.JavaFxTestSupport;
import com.stellar.platform.domain.CanonicalSignal;
import com.stellar.platform.domain.SourceReference;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSourceType;
import com.stellar.platform.processing.graph.GraphRenderSeries;
import com.stellar.platform.processing.graph.GraphSignalSessionStore;
import com.stellar.platform.storage.metadata.SqliteProjectMetadataStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Zoom and pan change the time window only. Stored physical values stay the numbers from the signal store.
 */
class GraphViewportStabilizationTest {

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
    void zoomPanAndFitChangeTheWindowAndLeavePhysicalValues() {
        System.setProperty("stellarai.home", temp.resolve("home").toAbsolutePath().toString());
        service = new DesktopAppService(
                new DesktopSession(), new SqliteProjectMetadataStore(temp.resolve("meta.db")));
        service.newProject("Viewport");
        store = GraphSignalSessionStore.create("run.mf4", "veh.dbc");
        store.accept(List.of(
                signal(0L, 10.0),
                signal(10_000_000_000L, 90.0)));
        service.signalsModel().replaceCatalog(store);
        graph = new GraphController();
        graph.bindMembershipForTest(service);
        SignalCatalogKey speed = new SignalCatalogKey(
                "VehicleSpeed", SignalSourceType.DBC_CAN, "OBD2", 0x102L, 1, "run.mf4", "veh.dbc");
        service.signalsModel().addToWorkspace(List.of(speed));
        graph.applyMembershipFromWorkspaceForTest();
        assertTrue(graph.tryFitRangeForTest());

        long fittedStart = graph.viewportStartForTest();
        long fittedEnd = graph.viewportEndForTest();
        long fittedSpan = fittedEnd - fittedStart;
        assertEquals(0L, fittedStart);
        assertEquals(10_000_000_000L, fittedEnd);

        graph.zoomInForTest();
        long zoomedSpan = graph.viewportEndForTest() - graph.viewportStartForTest();
        assertTrue(zoomedSpan < fittedSpan);
        assertTrue(zoomedSpan > 1_000_000L);
        assertTrue(graph.viewportStartForTest() >= fittedStart);
        assertTrue(graph.viewportEndForTest() <= fittedEnd);

        graph.zoomOutForTest();
        long widened = graph.viewportEndForTest() - graph.viewportStartForTest();
        assertTrue(widened > zoomedSpan);

        graph.zoomInForTest();
        graph.panLeftForTest();
        assertTrue(graph.viewportStartForTest() >= 0L);
        long beforeEdge = graph.viewportStartForTest();
        for (int i = 0; i < 8; i++) {
            graph.panLeftForTest();
        }
        assertEquals(0L, graph.viewportStartForTest());
        assertTrue(graph.viewportStartForTest() <= beforeEdge);
        graph.panRightForTest();
        assertTrue(graph.viewportEndForTest() <= fittedEnd);

        graph.resetViewForTest();
        assertEquals(fittedStart, graph.viewportStartForTest());
        assertEquals(fittedEnd, graph.viewportEndForTest());

        List<GraphRenderSeries> series = graph.renderAnalysisTracksForTest();
        assertEquals(1, series.size());
        assertTrue(series.getFirst().buckets().stream().anyMatch(b -> b.minUsable() && b.minValue() == 10.0));
        assertTrue(series.getFirst().buckets().stream().anyMatch(b -> b.maxUsable() && b.maxValue() == 90.0));
        assertTrue(series.getFirst().buckets().stream().noneMatch(b -> b.maxUsable() && b.maxValue() == 100.0
                && b.minUsable() && b.minValue() == 0.0));
    }

    private static CanonicalSignal signal(long timestamp, double value) {
        return new CanonicalSignal(
                "VehicleSpeed", "OBD2", timestamp, 0x102L, 1, value, (long) value, "km/h", null,
                SourceReference.ofLine(1));
    }
}
