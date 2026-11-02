package com.stellar.platform.desktop.ui;

import com.stellar.platform.desktop.DesktopVersion;
import com.stellar.platform.desktop.presentation.NavigationPage;
import com.stellar.platform.desktop.presentation.DesktopAppService;
import com.stellar.platform.desktop.session.DesktopSession;
import com.stellar.platform.desktop.support.JavaFxTestSupport;
import com.stellar.platform.desktop.ui.canvas.CanvasRenderSupport;
import com.stellar.platform.desktop.ui.graph.AutomotiveSignalCanvas;
import com.stellar.platform.desktop.ui.graph.map.RouteCanvas;
import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.processing.graph.SharedTimelineHit;
import com.stellar.platform.storage.metadata.SqliteProjectMetadataStore;
import javafx.embed.swing.SwingFXUtils;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.image.WritableImage;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import javax.imageio.ImageIO;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Live Desktop timings on the isolated 73-file Xenon project.
 * The 100- and 300-recording workloads are a separate synthetic test.
 */
@Timeout(value = 20, unit = TimeUnit.MINUTES)
class Milestone42ScaleLiveTest {

    private static final Path EVIDENCE = Path.of(
            System.getProperty("java.io.tmpdir"), "stellar-accuracy", "m2-evidence");
    private static DesktopAppService service;
    private static Scene scene;
    private static Stage stage;
    private static GraphController graph;
    private static final StringBuilder NOTES = new StringBuilder();
    private static String lastClick = "";

    @AfterAll
    static void shutdown() {
        try {
            Files.createDirectories(EVIDENCE);
            String pipeline = System.getProperty("prism.order", "default");
            Files.writeString(EVIDENCE.resolve("m42-live-notes-" + pipeline + ".txt"), NOTES.toString());
        } catch (Exception ignored) {
            // The assertion text still carries the same notes.
        }
        if (service != null) {
            service.shutdown();
            service = null;
        }
    }

    @Test
    void realSeventyThreeRecordingsStayIntactUnderGraphAndMapLoad() throws Exception {
        Path home = Path.of(System.getProperty("java.io.tmpdir"), "stellar-accuracy", "m2-73-home");
        Path db = home.resolve("meta.db");
        Assumptions.assumeTrue(Files.isRegularFile(db), "isolated M2-73 project is not present");
        System.setProperty("stellarai.test.isolate", "true");
        System.setProperty("stellarai.home", home.toString());
        note("source=real pipelineProperty=" + System.getProperty("prism.order", "unset")
                + " pipeline=" + CanvasRenderSupport.pipelineName()
                + " project=M2-73 recordings=73");
        open(home, db);
        fx("open graph", () -> {
            shell().openEngineeringAnalysis();
            graph = (GraphController) shell().controllerForTest(NavigationPage.GRAPHS);
        });
        await("catalog", () -> graph.explorerRowsForTest() != null && !graph.explorerRowsForTest().isEmpty(), 90);
        fx("clear restored selection", () -> graph.deselectAllFilteredForTest());
        await("cleared", () -> graph.selectedSignalCountForTest() == 0
                && !graph.graphStatusForTest().startsWith("Loading"), 90);
        int explorerRows = fxRef("explorer", () -> graph.explorerRowsForTest().size());
        note("explorerRows=" + explorerRows);

        SignalCatalogEntry speed = exact("IN_Vehicle_speed");
        long coldStarted = System.nanoTime();
        fx("select one", () -> {
            if (!graph.visibleKeysForTest().contains(speed.key())) {
                graph.fireExplorerCheckboxForTest(speed.key());
            }
        });
        await("first waveform", () -> graph.logicalTrackCountForTest() >= 1
                && graph.segmentCountForTest(0) >= 1
                && !graph.graphStatusForTest().startsWith("Loading"), 180);
        note("firstWaveformMs=" + elapsed(coldStarted)
                + " group=" + text(() -> graph.timelineGroupForTest())
                + " " + segmentSummary()
                + " fxQueueMs=" + value(() -> graph.lastViewportFxQueueMsForTest())
                + " cache=" + text(() -> graph.graphCacheStatsForTest()));
        if (flag(() -> graph.fileFocusActiveForTest())) {
            fx("leave restored file focus", () -> graph.disableFileFocusForTest());
        }
        await("one signal overview", () -> "UNKNOWN_ORDER".equals(graph.timelineGroupForTest())
                && graph.logicalTrackCountForTest() == 1
                && graph.segmentCountForTest(0) >= 73
                && !graph.graphStatusForTest().startsWith("Loading"), 180);
        note("oneSignal coldMs=" + elapsed(coldStarted)
                + " " + segmentSummary()
                + " fxQueueMs=" + value(() -> graph.lastViewportFxQueueMsForTest())
                + " cache=" + text(() -> graph.graphCacheStatsForTest())
                + " canvas=" + text(() -> graph.graphCanvasForTest().canvasMetricsForTest())
                + " range=" + oneLine(() -> graph.rangeTextForTest()));
        assertEquals(73, value(() -> graph.segmentCountForTest(0)));

        long warmStarted = System.nanoTime();
        fx("warm reload", () -> graph.reloadViewportForTest());
        await("warm", () -> graph.logicalTrackCountForTest() == 1
                && !graph.graphStatusForTest().startsWith("Loading"), 90);
        note("oneSignal warmMs=" + elapsed(warmStarted)
                + " fxQueueMs=" + value(() -> graph.lastViewportFxQueueMsForTest())
                + " cache=" + text(() -> graph.graphCacheStatsForTest()));

        long fullStart = value(() -> graph.viewportStartForTest());
        long fullEnd = value(() -> graph.viewportEndForTest());
        long span = Math.max(2L, fullEnd - fullStart);
        fx("zoom pan", () -> {
            graph.zoomInForTest();
            graph.panLeftForTest();
        });
        await("zoomed", () -> !graph.graphStatusForTest().startsWith("Loading")
                && graph.viewportEndForTest() - graph.viewportStartForTest() < span, 90);
        note("zoomPanMs fxQueueMs=" + value(() -> graph.lastViewportFxQueueMsForTest())
                + " canvas=" + text(() -> graph.graphCanvasForTest().canvasMetricsForTest()));
        long fitStarted = System.nanoTime();
        fx("fit", () -> graph.resetViewForTest());
        await("fit", () -> graph.viewportStartForTest() == fullStart
                && graph.viewportEndForTest() == fullEnd
                && !graph.graphStatusForTest().startsWith("Loading"), 120);
        note("fitAllMs=" + elapsed(fitStarted)
                + " fxQueueMs=" + value(() -> graph.lastViewportFxQueueMsForTest()));

        long rejectedBefore = value(() -> graph.viewportRejectedStaleForTest());
        fx("rapid", () -> {
            long start = graph.viewportStartForTest();
            long end = graph.viewportEndForTest();
            long width = Math.max(2L, end - start);
            for (int step = 1; step <= 6; step++) {
                graph.setViewportForTest(start + width / 20 * step, end - width / 40);
                graph.reloadViewportForTest();
            }
        });
        await("rapid settled", () -> !graph.graphStatusForTest().startsWith("Loading"), 90);
        note("rapidStaleRejected=" + (value(() -> graph.viewportRejectedStaleForTest()) - rejectedBefore)
                + " cache=" + text(() -> graph.graphCacheStatsForTest()));
        fx("fit after rapid", () -> graph.resetViewForTest());
        await("fit after rapid", () -> graph.viewportStartForTest() == fullStart
                && !graph.graphStatusForTest().startsWith("Loading"), 120);

        fx("search", () -> graph.searchExplorerForTest("IN_Vehicle_speed"));
        int searched = fxRef("searched", () -> graph.explorerRowsForTest().size());
        fx("clear search", () -> graph.searchExplorerForTest(""));
        int restored = fxRef("restored", () -> graph.explorerRowsForTest().size());
        note("signalSearch rows=" + searched + " restoredRows=" + restored + " tracks=" + value(() -> graph.logicalTrackCountForTest()));
        assertTrue(searched >= 1 && searched < explorerRows, "search did not narrow the explorer: " + searched);
        assertEquals(explorerRows, restored);
        assertEquals(1, value(() -> graph.logicalTrackCountForTest()));

        fx("file focus", () -> {
            if (!graph.fileFocusActiveForTest()) {
                graph.enableFileFocusForTest();
            }
        });
        await("file focus", () -> graph.fileFocusActiveForTest()
                && "FILE_FOCUS".equals(graph.timelineGroupForTest())
                && !graph.graphStatusForTest().startsWith("Loading"), 120);
        long focusStart = value(() -> graph.viewportStartForTest());
        long focusEnd = value(() -> graph.viewportEndForTest());
        long narrowEnd = focusStart + Math.max(1_000_000L, (focusEnd - focusStart) / 20_000L);
        fx("deep zoom", () -> {
            graph.setViewportForTest(focusStart, narrowEnd);
            graph.reloadViewportForTest();
        });
        await("deep zoom", () -> graph.viewportStartForTest() == focusStart
                && graph.viewportEndForTest() == narrowEnd
                && !graph.graphStatusForTest().startsWith("Loading"), 90);
        note("deepZoom fullResolutionSegments=" + value(() -> graph.fullResolutionSegmentCountForTest())
                + " window=" + focusStart + ".." + narrowEnd
                + " focus=" + text(() -> graph.focusedMeasurementIdForTest())
                + " fxQueueMs=" + value(() -> graph.lastViewportFxQueueMsForTest())
                + " cache=" + text(() -> graph.graphCacheStatsForTest()));
        fx("leave file focus", () -> graph.disableFileFocusForTest());
        await("overview restored", () -> "UNKNOWN_ORDER".equals(graph.timelineGroupForTest())
                && graph.logicalTrackCountForTest() == 1
                && graph.segmentCountForTest(0) >= 73
                && !graph.graphStatusForTest().startsWith("Loading"), 120);

        measureMap();
        measureMoreSignals(explorerRows);
        System.gc();
        note("heapAfterGcMb=" + (usedHeap() / (1024 * 1024)) + " " + gcSummary() + " " + threadSummary());
        assertTrue(NOTES.length() > 0);
    }

    private static void measureMap() throws Exception {
        long mapStarted = System.nanoTime();
        fx("map", () -> graph.selectMapRouteTab());
        await("routes", () -> readyMap()
                && graph.mapRoutePaneForTest().routeCanvasForTest().overlayCountForTest() >= 73, 180);
        note("gpsFirstMs=" + elapsed(mapStarted)
                + " status=" + oneLine(() -> graph.mapRoutePaneForTest().routeStatusForTest())
                + " overlays=" + value(() -> graph.mapRoutePaneForTest().routeCanvasForTest().overlayCountForTest())
                + " canvas=" + text(() -> graph.mapRoutePaneForTest().routeCanvasForTest().canvasMetricsForTest())
                + " stale=" + value(() -> graph.mapRoutePaneForTest().staleDiscardsForTest()));
        fx("paint all", () -> graph.mapRoutePaneForTest().routeCanvasForTest().paintForTest());
        assertEquals(RouteCanvas.EndpointPolicy.GLOBAL,
                fxRef("policy", () -> graph.mapRoutePaneForTest().routeCanvasForTest().endpointPolicyForTest()));
        assertEquals(2, fxRef("all marks", () -> graph.mapRoutePaneForTest().routeCanvasForTest().endpointMarksForTest().size()));
        assertTrue(text(() -> graph.mapRoutePaneForTest().routeCanvasForTest().endpointMarksForTest().toString()).contains("START"));
        long shotMs = shot("m42-all-routes-" + pipelineTag() + ".png");
        note("allRoutesSnapshotMs=" + shotMs);

        var segment = fxRef("first route", () -> graph.mapRoutePaneForTest().gpsSegmentsForTest().getFirst());
        long selectedStarted = System.nanoTime();
        AtomicReference<String> marks = new AtomicReference<>();
        fx("selected route", () -> {
            graph.selectMapRouteTab();
            graph.mapRoutePaneForTest().showOnly(segment);
            graph.mapRoutePaneForTest().routeCanvasForTest().paintForTest();
            marks.set(graph.mapRoutePaneForTest().routeCanvasForTest().endpointMarksForTest().toString()
                    + " policy=" + graph.mapRoutePaneForTest().routeCanvasForTest().endpointPolicyForTest());
        });
        note("gpsSelectedMs=" + elapsed(selectedStarted) + " marks=" + marks.get()
                + " canvas=" + text(() -> graph.mapRoutePaneForTest().routeCanvasForTest().canvasMetricsForTest()));
        assertTrue(marks.get().contains("START"), marks.get());
        assertTrue(marks.get().contains("END"), marks.get());
        assertFalse(marks.get().contains("POINT"), marks.get());
        note("selectedRouteSnapshotMs=" + shot("m42-selected-route-" + pipelineTag() + ".png"));

        Sync graphToGps = graphToGps();
        Sync gpsToGraph = gpsToGraph();
        note("sync " + graphToGps);
        note("sync " + gpsToGraph);
        assertFalse(graphToGps.graphId.isBlank());
        assertEquals(graphToGps.graphId, graphToGps.gpsId, graphToGps.toString());
        assertEquals(gpsToGraph.graphId, gpsToGraph.gpsId, gpsToGraph.toString());
        fx("all routes again", () -> graph.mapRoutePaneForTest().showAllRoutes());
        await("all routes restored", () -> mapSettled()
                && graph.mapRoutePaneForTest().routeCanvasForTest().overlayCountForTest() >= 73, 30);

        fx("waveforms", () -> graph.selectWaveformTabForTest());
        long start = value(() -> graph.viewportStartForTest());
        long end = value(() -> graph.viewportEndForTest());
        long width = Math.max(2L, end - start);
        fx("nudge", () -> {
            graph.setViewportForTest(start + width / 10, end - width / 10);
            graph.reloadViewportForTest();
        });
        await("nudged", () -> !graph.graphStatusForTest().startsWith("Loading"), 90);
        long revisitStarted = System.nanoTime();
        fx("map revisit", () -> graph.selectMapRouteTab());
        await("map reused", () -> mapSettled()
                && graph.mapRoutePaneForTest().routeCanvasForTest().overlayCountForTest() >= 73, 20);
        long revisitMs = elapsed(revisitStarted);
        note("gpsCachedAfterGraphZoomMs=" + revisitMs
                + " loading=" + flag(() -> graph.mapRoutePaneForTest().loadingVisibleForTest())
                + " stale=" + value(() -> graph.mapRoutePaneForTest().staleDiscardsForTest()));
        assertTrue(revisitMs < 3_000L, "map rebuilt after a graph zoom, revisitMs=" + revisitMs);
        assertFalse(flag(() -> graph.mapRoutePaneForTest().loadingVisibleForTest()));
    }

    private static void measureMoreSignals(int explorerRows) throws Exception {
        fx("waveforms for signals", () -> graph.selectWaveformTabForTest());
        fx("fit before more signals", () -> graph.resetViewForTest());
        await("full range before more signals", () -> "UNKNOWN_ORDER".equals(graph.timelineGroupForTest())
                && graph.segmentCountForTest(0) >= 73
                && !graph.graphStatusForTest().startsWith("Loading"), 120);
        long tenStarted = System.nanoTime();
        fx("select ten", () -> {
            for (SignalCatalogEntry entry : graph.explorerRowsForTest()) {
                if (graph.selectedSignalCountForTest() >= 10) {
                    return;
                }
                if (!graph.visibleKeysForTest().contains(entry.key())) {
                    graph.fireExplorerCheckboxForTest(entry.key());
                }
            }
        });
        await("ten signals", () -> graph.selectedSignalCountForTest() >= 10
                && graph.logicalTrackCountForTest() == graph.selectedSignalCountForTest()
                && graph.segmentCountForTest(0) >= 73
                && !graph.graphStatusForTest().startsWith("Loading"), 300);
        note("tenSignalsMs=" + elapsed(tenStarted)
                + " " + segmentSummary()
                + " fxQueueMs=" + value(() -> graph.lastViewportFxQueueMsForTest())
                + " cache=" + text(() -> graph.graphCacheStatsForTest())
                + " canvas=" + text(() -> graph.graphCanvasForTest().canvasMetricsForTest()));

        long allStarted = System.nanoTime();
        fx("select all", () -> graph.selectAllFilteredForTest());
        long firstUseful = awaitFirst("select all", () -> graph.logicalTrackCountForTest() > 10
                && !graph.graphStatusForTest().startsWith("Loading"), () -> graph.logicalTrackCountForTest() == graph.selectedSignalCountForTest()
                && graph.selectedSignalCountForTest() == explorerRows
                && graph.segmentCountForTest(0) >= 73
                && !graph.graphStatusForTest().startsWith("Loading"), 600);
        int tracks = (int) value(() -> graph.logicalTrackCountForTest());
        int selected = (int) value(() -> graph.selectedSignalCountForTest());
        int shortTracks = 0;
        for (int i = 0; i < tracks; i++) {
            int index = i;
            int segments = (int) value(() -> graph.segmentCountForTest(index));
            if (segments < 73) {
                shortTracks++;
            }
        }
        note("selectAll firstUsefulMs=" + firstUseful
                + " fullMs=" + elapsed(allStarted)
                + " selected=" + selected
                + " tracks=" + tracks
                + " explorerRows=" + explorerRows
                + " residentTracksUnder73=" + shortTracks
                + " fxQueueMs=" + value(() -> graph.lastViewportFxQueueMsForTest())
                + " cache=" + text(() -> graph.graphCacheStatsForTest())
                + " canvas=" + text(() -> graph.graphCanvasForTest().canvasMetricsForTest())
                + " prefHeight=" + fxRef("height", () -> graph.graphCanvasForTest().getPrefHeight()));
        assertEquals(explorerRows, selected, "Select All dropped explorer rows");
        assertEquals(selected, tracks, "the plot truncated the selection");
        assertTrue(tracks - shortTracks >= 4, "no resident track kept all 73 recordings");
        long scrollLoadStarted = System.nanoTime();
        fx("scroll to last tracks", () -> graph.scrollWaveformForTest(1));
        int last = tracks - 1;
        await("last track loaded", () -> graph.segmentCountForTest(last) > 0
                && !graph.graphStatusForTest().startsWith("Loading"), 180);
        int longTracks = 0;
        for (int i = 0; i < tracks; i++) {
            int index = i;
            if (value(() -> graph.segmentCountForTest(index)) >= 73) {
                longTracks++;
            }
        }
        assertTrue(longTracks >= 4, "scrolled plot lost the 73-recording signals, longTracks=" + longTracks);
        note("scrollToEndMs=" + elapsed(scrollLoadStarted)
                + " lastSegments=" + value(() -> graph.segmentCountForTest(last))
                + " tracks=" + value(() -> graph.logicalTrackCountForTest())
                + " fxQueueMs=" + value(() -> graph.lastViewportFxQueueMsForTest())
                + " cache=" + text(() -> graph.graphCacheStatsForTest()));

        long overlayStarted = System.nanoTime();
        fx("overlay", () -> graph.setLayoutModeForTest(false));
        await("overlay", () -> "OVERLAY".equals(graph.layoutModeForTest())
                && !graph.graphStatusForTest().startsWith("Loading"), 120);
        String overlayStatus = text(() -> graph.graphStatusForTest());
        note("overlayMs=" + elapsed(overlayStarted)
                + " mode=" + text(() -> graph.layoutModeForTest())
                + " tracks=" + value(() -> graph.logicalTrackCountForTest())
                + " selected=" + value(() -> graph.selectedSignalCountForTest())
                + " status=" + overlayStatus
                + " canvas=" + text(() -> graph.graphCanvasForTest().canvasMetricsForTest()));
        assertEquals(tracks, value(() -> graph.selectedSignalCountForTest()), overlayStatus);
        assertTrue(overlayStatus.contains("Showing " + Math.min(24, tracks) + " of " + tracks + " selected signals"),
                overlayStatus);
        assertTrue(overlayStatus.contains("Switch to Stacked to inspect the rest"), overlayStatus);
        fx("stacked", () -> graph.setLayoutModeForTest(true));
        await("stacked again", () -> graph.layoutModeForTest().contains("STACK")
                && graph.logicalTrackCountForTest() == tracks
                && !graph.graphStatusForTest().startsWith("Loading"), 180);

        javafx.scene.paint.Color first = fxRef("colour", () -> graph.graphCanvasForTest().logicalTrackColorForTest(0));
        long scrollStarted = System.nanoTime();
        fx("scroll", () -> {
            AutomotiveSignalCanvas canvas = graph.graphCanvasForTest();
            canvas.setVisibleWindow(480, 0);
            canvas.layout();
            canvas.setVisibleWindow(480, Math.min(1_500, Math.max(0, canvas.getPrefHeight() - 480)));
            canvas.layout();
        });
        note("verticalScrollMs=" + elapsed(scrollStarted)
                + " redrawMs=" + value(() -> graph.graphCanvasForTest().lastRedrawMillis())
                + " canvas=" + text(() -> graph.graphCanvasForTest().canvasMetricsForTest())
                + " prefHeight=" + fxRef("pref", () -> graph.graphCanvasForTest().getPrefHeight()));
        assertEquals(first, fxRef("colour again", () -> graph.graphCanvasForTest().logicalTrackColorForTest(0)));
        assertEquals(tracks, value(() -> graph.logicalTrackCountForTest()));
        note("heapBeforeGcMb=" + (usedHeap() / (1024 * 1024)));
    }

    private static Sync graphToGps() throws Exception {
        fx("wave sync", () -> graph.selectWaveformTabForTest());
        await("drawn", () -> graph.segmentCountForTest(0) >= 1
                && !graph.graphStatusForTest().startsWith("Loading"), 60);
        double[] point = hitPoint();
        assertTrue(point != null, "no visible graph sample");
        long started = System.nanoTime();
        fx("graph click", () -> clickGraph(point[0], point[1]));
        long latency = elapsed(started);
        String graphId = text(() -> graph.cursorMeasurementIdForTest());
        long graphTime = value(() -> graph.cursorAForTest() == null ? 0L : graph.cursorAForTest());
        fx("map after graph", () -> graph.selectMapRouteTab());
        await("map after graph", Milestone42ScaleLiveTest::mapSettled, 20);
        String sample = text(() -> graph.mapRoutePaneForTest().selectedSampleForTest());
        String[] parts = sample.split("\t", -1);
        assertEquals(4, parts.length, "map sample [" + sample + "] click=" + lastClick);
        return new Sync("graph-to-gps", graphId, graphTime, parts[0], Long.parseLong(parts[1]), latency,
                value(() -> graph.lastViewportFxQueueMsForTest()));
    }

    private static Sync gpsToGraph() throws Exception {
        await("map for click", () -> !graph.mapRoutePaneForTest().gpsSegmentsForTest().isEmpty(), 20);
        double[] point = routePoint();
        assertTrue(point != null, "no visible GPS point");
        long started = System.nanoTime();
        fx("route click", () -> clickRoute(point[0], point[1]));
        long latency = elapsed(started);
        String sample = text(() -> graph.mapRoutePaneForTest().selectedSampleForTest());
        String[] parts = sample.split("\t", -1);
        assertEquals(4, parts.length, sample);
        fx("see graph", () -> graph.selectWaveformTabForTest());
        await("graph cursor", () -> parts[0].equals(graph.cursorMeasurementIdForTest())
                && graph.cursorAForTest() != null
                && graph.cursorAForTest() == Long.parseLong(parts[1]), 60);
        return new Sync("gps-to-graph",
                text(() -> graph.cursorMeasurementIdForTest()),
                value(() -> graph.cursorAForTest() == null ? 0L : graph.cursorAForTest()),
                parts[0], Long.parseLong(parts[1]), latency,
                value(() -> graph.lastViewportFxQueueMsForTest()));
    }

    private static double[] hitPoint() throws Exception {
        AtomicReference<double[]> found = new AtomicReference<>();
        fx("hit", () -> {
            AutomotiveSignalCanvas canvas = graph.graphCanvasForTest();
            double[] size = canvas.innerCanvasSizeForTest();
            double width = size[0] > 1 ? size[0] : Math.max(200, canvas.getWidth());
            double trackHeight = size[1] > 1 ? size[1] : Math.max(80, canvas.getHeight() * 0.4);
            double trackTop = size[1] > 1 ? size[2] : 40;
            double y = trackTop + Math.min(trackHeight * 0.35, Math.max(8, trackHeight - 8));
            for (double scan = 90; scan < width - 30; scan += 24) {
                SharedTimelineHit.Hit hit = canvas.hitForTest(scan, y);
                if (hit != null && hit.measurementId() != null && hit.measurementId().indexOf('-') > 0) {
                    found.set(new double[]{scan, y});
                    return;
                }
            }
        });
        return found.get();
    }

    private static double[] routePoint() throws Exception {
        AtomicReference<double[]> found = new AtomicReference<>();
        fx("route point", () -> {
            RouteCanvas view = graph.mapRoutePaneForTest().routeCanvasForTest();
            view.paintForTest();
            double width = Math.max(200, view.getWidth());
            double height = Math.max(120, view.getHeight());
            for (double y = 20; y < height - 12; y += 8) {
                for (double x = 20; x < width - 12; x += 8) {
                    if (view.hoverNearRouteForTest(x, y)) {
                        found.set(new double[]{x, y});
                        return;
                    }
                }
            }
        });
        return found.get();
    }

    private static void clickGraph(double x, double y) {
        AutomotiveSignalCanvas canvas = graph.graphCanvasForTest();
        javafx.geometry.Point2D scenePoint = canvas.localToScene(x, y);
        double sx = scenePoint == null ? x : scenePoint.getX();
        double sy = scenePoint == null ? y : scenePoint.getY();
        canvas.fireEvent(new MouseEvent(
                canvas, canvas, MouseEvent.MOUSE_PRESSED,
                sx, sy, sx, sy, MouseButton.PRIMARY, 1,
                false, false, false, false, true, false, false, true, false, false, null));
        canvas.fireEvent(new MouseEvent(
                canvas, canvas, MouseEvent.MOUSE_RELEASED,
                sx, sy, sx, sy, MouseButton.PRIMARY, 1,
                false, false, false, false, false, false, false, true, false, false, null));
        lastClick = x + "," + y + " scene=" + sx + "," + sy;
    }

    private static void clickRoute(double x, double y) {
        graph.mapRoutePaneForTest().routeCanvasForTest().clickAtForTest(x, y);
    }

    /**
     * Route readiness is the prepared model and the GPS panel. The route status line is replaced
     * by a measurement cursor or by basemap tile text after the 73 routes already exist.
     */
    private static boolean readyMap() {
        var pane = graph.mapRoutePaneForTest();
        if (pane == null || pane.loadingVisibleForTest() || pane.gpsSegmentsForTest().size() < 73) {
            return false;
        }
        if (pane.routeCanvasForTest().overlayCountForTest() < 73) {
            return false;
        }
        String mapping = pane.mappingStatusForTest();
        return mapping != null && mapping.contains("Recording order unknown");
    }

    private static boolean mapSettled() {
        String status = graph.mapRoutePaneForTest().routeStatusForTest();
        return !graph.mapRoutePaneForTest().loadingVisibleForTest()
                && status != null
                && !status.isBlank()
                && !status.startsWith("Loading");
    }

    private static long shot(String name) throws Exception {
        Files.createDirectories(EVIDENCE);
        AtomicReference<WritableImage> image = new AtomicReference<>();
        long started = System.nanoTime();
        fx("shot " + name, () -> {
            graph.selectMapRouteTab();
            image.set(scene.snapshot(null));
        });
        long elapsed = elapsed(started);
        if (image.get() != null) {
            ImageIO.write(SwingFXUtils.fromFXImage(image.get(), null), "png", EVIDENCE.resolve(name).toFile());
        }
        return elapsed;
    }

    private static void open(Path home, Path db) throws Exception {
        service = new DesktopAppService(new DesktopSession(), new SqliteProjectMetadataStore(db));
        var project = service.metadataStore().listProjects().stream()
                .filter(row -> "M2-73".equals(row.name()))
                .findFirst()
                .orElseThrow();
        assertTrue(service.openSavedProject(project.projectId()).opened());
        JavaFxTestSupport.startToolkit();
    }

    private static void launch() throws Exception {
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        javafx.application.Platform.runLater(() -> {
            try {
                FXMLLoader loader = new FXMLLoader(MainShellController.class.getResource("/fxml/main-shell.fxml"));
                Parent root = loader.load();
                MainShellController shell = loader.getController();
                shell.init(service);
                shell.setUnsavedLeaveDecisionForTest(
                        intent -> com.stellar.platform.desktop.presentation.UnsavedLeaveChoice.DISCARD);
                scene = new Scene(root, 1440, 900);
                scene.getProperties().put("shell", shell);
                scene.getStylesheets().add(
                        MainShellController.class.getResource("/css/stellar-desktop.css").toExternalForm());
                stage = new Stage();
                stage.setTitle(DesktopVersion.windowTitle());
                stage.setScene(scene);
                stage.setX(-3200);
                stage.setY(0);
                stage.show();
            } catch (Exception ex) {
                error.set(ex);
            } finally {
                latch.countDown();
            }
        });
        assertTrue(latch.await(30, TimeUnit.SECONDS), "shell did not start");
        if (error.get() != null) {
            throw new IllegalStateException("shell failed", error.get());
        }
    }

    private static MainShellController shell() {
        return (MainShellController) scene.getProperties().get("shell");
    }

    private static SignalCatalogEntry exact(String name) throws Exception {
        AtomicReference<SignalCatalogEntry> found = new AtomicReference<>();
        fx("find " + name, () -> {
            for (SignalCatalogEntry entry : graph.explorerRowsForTest()) {
                if (name.equals(entry.signalName())) {
                    found.set(entry);
                }
            }
        });
        assertTrue(found.get() != null, "missing " + name);
        return found.get();
    }

    private static void fx(String label, JavaFxTestSupport.FxWork work) throws Exception {
        if (scene == null) {
            launch();
        }
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        javafx.application.Platform.runLater(() -> {
            try {
                work.run();
            } catch (Throwable thrown) {
                error.set(thrown);
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(180, TimeUnit.SECONDS), "JavaFX scenario timed out (" + label + ")");
        if (error.get() != null) {
            throw new AssertionError(label, error.get());
        }
    }

    private static <T> T fxRef(String label, java.util.function.Supplier<T> read) throws Exception {
        AtomicReference<T> value = new AtomicReference<>();
        fx(label, () -> value.set(read.get()));
        return value.get();
    }

    private static void await(String label, java.util.function.BooleanSupplier condition, int seconds) throws Exception {
        awaitFirst(label, condition, condition, seconds);
    }

    private static long awaitFirst(
            String label,
            java.util.function.BooleanSupplier first,
            java.util.function.BooleanSupplier done,
            int seconds
    ) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        long origin = System.nanoTime();
        long firstMs = -1L;
        while (System.nanoTime() < deadline) {
            AtomicBoolean firstOk = new AtomicBoolean();
            AtomicBoolean doneOk = new AtomicBoolean();
            AtomicBoolean finished = new AtomicBoolean();
            Thread watch = new Thread(() -> {
                try {
                    Thread.sleep(2_000L);
                } catch (InterruptedException interrupted) {
                    return;
                }
                if (!finished.get()) {
                    try {
                        dumpThreads(label.replace(' ', '-'));
                    } catch (Exception ignored) {
                        // The latency note still records the stall.
                    }
                }
            }, "m42-stall-watch");
            watch.setDaemon(true);
            watch.start();
            long started = System.nanoTime();
            fx(label, () -> {
                firstOk.set(first.getAsBoolean());
                doneOk.set(done.getAsBoolean());
            });
            finished.set(true);
            watch.interrupt();
            long waited = elapsed(started);
            if (waited > 2_000L) {
                note(label + " fxWaitMs=" + waited);
            }
            if (firstOk.get() && firstMs < 0L) {
                firstMs = elapsed(origin);
            }
            if (doneOk.get()) {
                return firstMs < 0L ? elapsed(origin) : firstMs;
            }
            Thread.sleep(200L);
        }
        throw new AssertionError("timed out waiting for " + label
                + " graph=" + text(() -> graph.graphStatusForTest())
                + " map=" + oneLine(() -> graph.mapRoutePaneForTest().routeStatusForTest())
                + " tracks=" + value(() -> graph.logicalTrackCountForTest())
                + " selected=" + value(() -> graph.selectedSignalCountForTest()));
    }

    private static void dumpThreads(String name) throws Exception {
        StringBuilder dump = new StringBuilder();
        for (Map.Entry<Thread, StackTraceElement[]> entry : Thread.getAllStackTraces().entrySet()) {
            dump.append(entry.getKey().getName()).append('\n');
            for (StackTraceElement frame : entry.getValue()) {
                dump.append("  ").append(frame).append('\n');
            }
        }
        Files.createDirectories(EVIDENCE);
        Files.writeString(EVIDENCE.resolve("m42-stall-" + pipelineTag() + "-" + name + ".txt"), dump);
    }

    private static String text(java.util.function.Supplier<String> read) throws Exception {
        String value = fxRef("text", read);
        return value == null ? "" : value;
    }

    private static String oneLine(java.util.function.Supplier<String> read) throws Exception {
        return text(read).replace('\n', ' ');
    }

    private static long value(java.util.function.LongSupplier read) throws Exception {
        AtomicLong value = new AtomicLong();
        fx("long", () -> value.set(read.getAsLong()));
        return value.get();
    }

    private static boolean flag(java.util.function.BooleanSupplier read) throws Exception {
        AtomicBoolean value = new AtomicBoolean();
        fx("flag", () -> value.set(read.getAsBoolean()));
        return value.get();
    }

    private static String segmentSummary() {
        int tracks = graph.logicalTrackCountForTest();
        StringBuilder text = new StringBuilder("tracks=").append(tracks);
        int limit = Math.min(tracks, 4);
        for (int i = 0; i < limit; i++) {
            text.append(" t").append(i).append('=').append(graph.segmentCountForTest(i));
        }
        if (tracks > limit) {
            text.append(" ...");
        }
        return text.toString();
    }

    private static long usedHeap() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    private static String gcSummary() {
        long collections = 0;
        long millis = 0;
        for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
            collections += Math.max(0, bean.getCollectionCount());
            millis += Math.max(0, bean.getCollectionTime());
        }
        return "gcCount=" + collections + " gcMs=" + millis;
    }

    private static String threadSummary() {
        int workers = 0;
        int fx = 0;
        int quantum = 0;
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            String name = thread.getName();
            if (name.startsWith("stellar-")) {
                workers++;
            } else if (name.contains("JavaFX Application")) {
                fx++;
            } else if (name.startsWith("QuantumRenderer")) {
                quantum++;
            }
        }
        return "stellarWorkers=" + workers + " fxThreads=" + fx + " quantum=" + quantum;
    }

    private static void note(String line) {
        NOTES.append(line).append('\n');
    }

    private static String pipelineTag() {
        return System.getProperty("prism.order", "default");
    }

    private static long elapsed(long started) {
        return (System.nanoTime() - started) / 1_000_000L;
    }

    private record Sync(String direction, String graphId, long graphStored, String gpsId, long gpsStored,
                         long latencyMs, long fxQueueMs) {
        @Override
        public String toString() {
            return direction + " graphId=" + graphId + " graphStored=" + graphStored
                    + " gpsId=" + gpsId + " gpsStored=" + gpsStored
                    + " latencyMs=" + latencyMs + " fxQueueMs=" + fxQueueMs;
        }
    }
}
