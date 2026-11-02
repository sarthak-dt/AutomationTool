package com.stellar.platform.desktop.ui;

import com.stellar.platform.desktop.DesktopVersion;
import com.stellar.platform.desktop.presentation.DesktopAppService;
import com.stellar.platform.desktop.presentation.GraphInvestigationCodec;
import com.stellar.platform.desktop.presentation.NavigationPage;
import com.stellar.platform.desktop.session.DesktopSession;
import com.stellar.platform.desktop.support.JavaFxTestSupport;
import com.stellar.platform.desktop.ui.graph.AutomotiveSignalCanvas;
import com.stellar.platform.desktop.ui.graph.map.MapRoutePane;
import com.stellar.platform.desktop.ui.graph.map.RouteCanvas;
import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.processing.graph.SharedTimelineHit;
import com.stellar.platform.processing.time.MeasurementCursor;
import com.stellar.platform.storage.metadata.GraphInvestigationMetadata;
import com.stellar.platform.storage.metadata.SqliteProjectMetadataStore;
import javafx.embed.swing.SwingFXUtils;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.image.WritableImage;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import javax.imageio.ImageIO;
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
 * Live desktop acceptance on the isolated 73-file Xenon project.
 * Verified UTC is not present in that project; the fixture test covers that clock.
 */
@Timeout(value = 20, unit = TimeUnit.MINUTES)
class Milestone3LiveAcceptanceTest {

    private static final Path EVIDENCE = Path.of(
            System.getProperty("java.io.tmpdir"), "stellar-accuracy", "m2-evidence");
    private static DesktopAppService service;
    private static Scene scene;
    private static Stage stage;
    private static GraphController graph;
    private static final StringBuilder NOTES = new StringBuilder();

    @AfterAll
    static void shutdown() {
        try {
            Files.createDirectories(EVIDENCE);
            Files.writeString(EVIDENCE.resolve("milestone3-live-notes.txt"), NOTES.toString());
        } catch (Exception ignored) {
            // The assertion report still carries the same notes.
        }
        if (service != null) {
            service.shutdown();
            service = null;
        }
    }

    @Test
    void graphGpsSyncAndGiv2ReopenOnTheIsolatedProject() throws Exception {
        Path home = Path.of(System.getProperty("java.io.tmpdir"), "stellar-accuracy", "m2-73-home");
        Path db = home.resolve("meta.db");
        Assumptions.assumeTrue(Files.isRegularFile(db), "isolated M2-73 project is not present");
        System.setProperty("stellarai.test.isolate", "true");
        System.setProperty("stellarai.home", home.toString());
        open(home, db);
        fx("open graph", () -> {
            shell().openEngineeringAnalysis();
            graph = (GraphController) shell().controllerForTest(NavigationPage.GRAPHS);
        });
        await("catalog", () -> graph.explorerRowsForTest() != null && !graph.explorerRowsForTest().isEmpty(), 90);
        SignalCatalogEntry speed = exact("IN_Vehicle_speed");
        SignalCatalogEntry coolant = exact("IN_Coolant_temperature");
        fx("clear restored focus", () -> graph.disableFileFocusForTest());
        fx("select", () -> {
            if (!graph.visibleKeysForTest().contains(speed.key())) {
                graph.fireExplorerCheckboxForTest(speed.key());
            }
            if (!graph.visibleKeysForTest().contains(coolant.key())) {
                graph.fireExplorerCheckboxForTest(coolant.key());
            }
        });
        await("stacked fit", () -> "UNKNOWN_ORDER".equals(graph.timelineGroupForTest())
                && graph.logicalTrackCountForTest() >= 2
                && segmentTotal() >= 140
                && !graph.graphStatusForTest().startsWith("Loading"), 300);
        note("stackedSegments=" + segmentSummary()
                + " fxQueueMs=" + value(() -> graph.lastViewportFxQueueMsForTest()));
        long mapStarted = System.nanoTime();
        fx("map", () -> graph.selectMapRouteTab());
        await("routes", () -> graph.mapRoutePaneForTest().routeStatusForTest().contains("Recording order unknown"), 180);
        long firstMapMs = (System.nanoTime() - mapStarted) / 1_000_000L;
        long heapAfterMap = usedHeap();
        note("firstMapMs=" + firstMapMs + " heapMb=" + (heapAfterMap / (1024 * 1024))
                + " status=" + text(() -> graph.mapRoutePaneForTest().routeStatusForTest()).replace('\n', ' '));
        assertTrue(text(() -> graph.mapRoutePaneForTest().routeStatusForTest()).contains("73 GPS-capable"));

        fx("waveforms", () -> graph.selectWaveformTabForTest());
        Sync stackedGraph = graphToGps("stacked");
        Sync stackedMap = gpsToGraph("stacked");
        rapidAlternation();
        fx("stacked map shot", () -> graph.selectMapRouteTab());
        shot("m3-sync-stacked-map.png");

        long overlayFx = fxMillis("overlay", () -> graph.setLayoutModeForTest(false));
        await("overlay", () -> "OVERLAY".equals(graph.layoutModeForTest())
                && graph.segmentCountForTest(0) == 73
                && !graph.graphStatusForTest().startsWith("Loading"), 120);
        note("overlaySwitchFxMs=" + overlayFx + " fxQueueMs=" + value(() -> graph.lastViewportFxQueueMsForTest()));
        fx("map overlay", () -> graph.selectMapRouteTab());
        fx("waveforms overlay", () -> graph.selectWaveformTabForTest());
        Sync overlayGraph = graphToGps("overlay");
        Sync overlayMap = gpsToGraph("overlay");
        fx("overlay map shot", () -> graph.selectMapRouteTab());
        shot("m3-sync-overlay-map.png");
        fx("stacked again", () -> graph.setLayoutModeForTest(true));
        await("stacked again", () -> "STACKED".equals(graph.layoutModeForTest())
                && !graph.graphStatusForTest().startsWith("Loading"), 120);

        assertEquals(stackedGraph.graphMeasurementId, stackedGraph.gpsMeasurementId, stackedGraph.toString());
        assertEquals(stackedMap.graphMeasurementId, stackedMap.gpsMeasurementId, stackedMap.toString());
        assertEquals(overlayGraph.graphMeasurementId, overlayGraph.gpsMeasurementId, overlayGraph.toString());
        assertEquals(overlayMap.graphMeasurementId, overlayMap.gpsMeasurementId, overlayMap.toString());
        assertFalse(stackedGraph.graphMeasurementId.isBlank());
        assertTrue(stackedGraph.graphStoredNanos > 0);
        assertTrue(stackedMap.gpsStoredNanos > 0);
        assertEquals(stackedMap.gpsStoredNanos, stackedMap.graphStoredNanos);

        fx("focus", () -> {
            graph.selectWaveformTabForTest();
            graph.enableFileFocusForTest();
        });
        await("file focus", () -> graph.fileFocusActiveForTest()
                && graph.rangeTextForTest().contains(" s ")
                && !graph.graphStatusForTest().startsWith("Loading"), 120);
        String focusId = text(() -> graph.focusedMeasurementIdForTest());
        assertFalse(focusId.isBlank(), "file focus measurement");
        long[] window = narrowViewport();
        var saved = service.saveProject();
        assertTrue(saved.saved(), String.valueOf(saved));
        GraphInvestigationMetadata giv2 = service.metadataStore().findGraphInvestigation(service.currentProjectId()).orElseThrow();
        assertTrue(giv2.tracesBlob().startsWith("GIV2"), giv2.tracesBlob().substring(0, Math.min(12, giv2.tracesBlob().length())));
        GraphInvestigationCodec.Domain domain = GraphInvestigationCodec.decodeDomain(giv2.tracesBlob());
        note("giv2Before group=" + domain.timelineGroup() + " domain=" + domain.viewportDomain()
                + " focus=" + domain.fileFocusMeasurementId()
                + " viewport=" + giv2.viewportStartNanos() + ".." + giv2.viewportEndNanos()
                + " colors=" + colorSummary(giv2.tracesBlob()));
        assertEquals("UNKNOWN_ORDER", domain.timelineGroup());
        assertEquals("FILE_FOCUS", domain.viewportDomain());
        assertEquals(focusId, domain.fileFocusMeasurementId());
        assertEquals(window[0], giv2.viewportStartNanos());
        assertEquals(window[1], giv2.viewportEndNanos());
        int speedColor = colorOf(giv2.tracesBlob(), "IN_Vehicle_speed");
        int coolantColor = colorOf(giv2.tracesBlob(), "IN_Coolant_temperature");

        closeStage();
        service.shutdown();
        open(home, db);
        fx("reopen graph", () -> {
            shell().openEngineeringAnalysis();
            graph = (GraphController) shell().controllerForTest(NavigationPage.GRAPHS);
        });
        await("restored focus", () -> graph.fileFocusActiveForTest()
                && focusId.equals(graph.focusedMeasurementIdForTest())
                && !graph.graphStatusForTest().startsWith("Loading"), 180);
        GraphInvestigationCodec.Domain restored = GraphInvestigationCodec.decodeDomain(
                service.graphInvestigation().timelineGroup() == null ? "" : giv2.tracesBlob());
        note("giv2After group=" + service.graphInvestigation().timelineGroup()
                + " domain=" + service.graphInvestigation().viewportDomain()
                + " focus=" + service.graphInvestigation().fileFocusMeasurementId()
                + " active=" + text(() -> graph.timelineGroupForTest())
                + " range=" + text(() -> graph.rangeTextForTest())
                + " viewport=" + value(() -> graph.viewportStartForTest()) + ".." + value(() -> graph.viewportEndForTest())
                + " fxQueueMs=" + value(() -> graph.lastViewportFxQueueMsForTest()));
        assertEquals("UNKNOWN_ORDER", service.graphInvestigation().timelineGroup());
        assertEquals("FILE_FOCUS", service.graphInvestigation().viewportDomain());
        assertEquals(focusId, service.graphInvestigation().fileFocusMeasurementId());
        assertEquals(focusId, text(() -> graph.focusedMeasurementIdForTest()));
        assertEquals(window[0], value(() -> graph.viewportStartForTest()));
        assertEquals(window[1], value(() -> graph.viewportEndForTest()));
        GraphInvestigationMetadata reopenedBlob = service.metadataStore()
                .findGraphInvestigation(service.currentProjectId()).orElseThrow();
        assertEquals(speedColor, colorOf(reopenedBlob.tracesBlob(), "IN_Vehicle_speed"));
        assertEquals(coolantColor, colorOf(reopenedBlob.tracesBlob(), "IN_Coolant_temperature"));
        assertFalse(text(() -> graph.rangeTextForTest()).contains("UTC"));
        shot("m3-giv2-reopen.png");

        fx("map after reopen", () -> graph.selectMapRouteTab());
        await("map after reopen", () -> {
            String status = graph.mapRoutePaneForTest().routeStatusForTest();
            return status.contains("Recording order unknown") || status.contains("GPS samples");
        }, 180);
        fx("wave after reopen", () -> graph.selectWaveformTabForTest());
        Sync afterReopen = graphToGps("reopen");
        assertEquals(focusId, afterReopen.graphMeasurementId, afterReopen.toString());
        assertEquals(focusId, afterReopen.gpsMeasurementId, afterReopen.toString());

        GraphInvestigationMetadata legacy = new GraphInvestigationMetadata(
                giv2.projectId(), giv2.layoutMode(), 37L * 1_000_000_000L, 38L * 1_000_000_000L,
                giv2.defaultStartNanos(), giv2.defaultEndNanos(), giv2.cursorANanos(), giv2.cursorBNanos(),
                giv2.notes(), GraphInvestigationCodec.encodeTraces(GraphInvestigationCodec.decodeTraces(giv2.tracesBlob())),
                giv2.updatedAt(), giv2.focusKeyBlob(), giv2.analysisTab(), giv2.inspectorVisible(),
                giv2.plotSettingsVisible(), giv2.signalsDisplayMode(), giv2.signalsPrecision(),
                giv2.presentationVersion());
        service.metadataStore().upsertGraphInvestigation(legacy);
        closeStage();
        service.shutdown();
        open(home, db);
        fx("giv1 graph", () -> {
            shell().openEngineeringAnalysis();
            graph = (GraphController) shell().controllerForTest(NavigationPage.GRAPHS);
        });
        await("giv1 fit", () -> "UNKNOWN_ORDER".equals(graph.timelineGroupForTest())
                && !graph.fileFocusActiveForTest()
                && graph.rangeTextForTest().contains("not elapsed time")
                && graph.rangeTextForTest().contains("Slots 1")
                && !graph.graphStatusForTest().startsWith("Loading"), 180);
        long giv1Start = value(() -> graph.viewportStartForTest());
        long giv1End = value(() -> graph.viewportEndForTest());
        note("giv1After range=" + text(() -> graph.rangeTextForTest())
                + " viewport=" + giv1Start + ".." + giv1End
                + " domain=" + service.graphInvestigation().viewportDomain()
                + " group=" + service.graphInvestigation().timelineGroup());
        assertTrue(giv1Start < 37L * 1_000_000_000L, "legacy slot window was applied as the viewport");
        assertTrue(giv1End > 38L * 1_000_000_000L, "legacy slot window was applied as the viewport");
        assertFalse(text(() -> graph.rangeTextForTest()).contains("UTC"));
        service.metadataStore().upsertGraphInvestigation(giv2);
        note("heapMb=" + (usedHeap() / (1024 * 1024))
                + " restoredDomainCheck=" + restored.viewportDomain());
        assertTrue(NOTES.length() > 0);
    }

    private static Sync graphToGps(String layout) throws Exception {
        fx("wave " + layout, () -> graph.selectWaveformTabForTest());
        await("drawn " + layout, () -> graph.segmentCountForTest(0) >= 1
                && !graph.graphStatusForTest().startsWith("Loading"), 60);
        double[] point = hitPoint();
        assertTrue(point != null, "no visible graph sample in " + layout);
        long started = System.nanoTime();
        fx("graph click " + layout, () -> clickGraph(point[0], point[1]));
        long latency = (System.nanoTime() - started) / 1_000_000L;
        String graphId = text(() -> graph.cursorMeasurementIdForTest());
        long graphTime = value(() -> graph.cursorAForTest() == null ? 0L : graph.cursorAForTest());
        String sample = text(() -> graph.mapRoutePaneForTest().selectedSampleForTest());
        String mapStatus = text(() -> graph.mapRoutePaneForTest().routeStatusForTest());
        String[] parts = sample.split("\t", -1);
        assertEquals(4, parts.length, "map sample [" + sample + "] graphId=" + graphId
                + " graphStored=" + graphTime + " mapStatus=" + mapStatus.replace('\n', ' ')
                + " click=" + lastClick);
        Sync sync = new Sync(layout, "graph-to-gps", graphId, graphTime, parts[0], Long.parseLong(parts[1]), latency,
                value(() -> graph.lastViewportFxQueueMsForTest()));
        note(sync.toString());
        if (latency > 2_000L) {
            dumpThreads(layout + "-graph-click");
        }
        return sync;
    }

    private static Sync gpsToGraph(String layout) throws Exception {
        fx("map " + layout, () -> graph.selectMapRouteTab());
        await("map ready " + layout, () -> !graph.mapRoutePaneForTest().gpsSegmentsForTest().isEmpty(), 30);
        double[] point = routePoint();
        assertTrue(point != null, "no visible GPS point in " + layout);
        long started = System.nanoTime();
        fx("route click " + layout, () -> clickRoute(point[0], point[1]));
        long latency = (System.nanoTime() - started) / 1_000_000L;
        String sample = text(() -> graph.mapRoutePaneForTest().selectedSampleForTest());
        String[] parts = sample.split("\t");
        assertEquals(4, parts.length, sample);
        fx("see graph " + layout, () -> graph.selectWaveformTabForTest());
        await("graph cursor " + layout, () -> parts[0].equals(graph.cursorMeasurementIdForTest())
                && graph.cursorAForTest() != null
                && graph.cursorAForTest() == Long.parseLong(parts[1]), 60);
        Sync sync = new Sync(layout, "gps-to-graph",
                text(() -> graph.cursorMeasurementIdForTest()),
                value(() -> graph.cursorAForTest()),
                parts[0], Long.parseLong(parts[1]), latency,
                value(() -> graph.lastViewportFxQueueMsForTest()));
        note(sync + " range=" + text(() -> graph.rangeTextForTest()));
        if (latency > 2_000L) {
            dumpThreads(layout + "-gps-click");
        }
        return sync;
    }

    private static void rapidAlternation() throws Exception {
        fx("wave rapid", () -> graph.selectWaveformTabForTest());
        MeasurementCursor last = null;
        for (int i = 0; i < 4; i++) {
            double[] point = hitPointAt(0.2 + i * 0.15);
            if (point == null) {
                continue;
            }
            fx("rapid " + i, () -> clickGraph(point[0], point[1]));
            last = service.analysisSelection().measurementCursor();
        }
        assertTrue(last != null && last.hasMeasurement());
        double[] route = routePoint();
        fx("rapid map", () -> {
            graph.selectMapRouteTab();
        });
        if (route != null) {
            fx("rapid route", () -> clickRoute(route[0], route[1]));
        }
        double[] back = hitPointAt(0.62);
        fx("rapid back", () -> {
            graph.selectWaveformTabForTest();
            if (back != null) {
                clickGraph(back[0], back[1]);
            }
        });
        MeasurementCursor expected = service.analysisSelection().measurementCursor();
        Thread.sleep(400L);
        MeasurementCursor settled = service.analysisSelection().measurementCursor();
        note("rapid lastToken=" + expected.token() + " id=" + expected.measurementId()
                + " stored=" + expected.relativeTimestampNanos()
                + " settledToken=" + settled.token() + " settledId=" + settled.measurementId());
        assertEquals(expected.token(), settled.token());
        assertEquals(expected.measurementId(), settled.measurementId());
        assertEquals(expected.measurementId(), text(() -> graph.cursorMeasurementIdForTest()));
    }

    private static long[] narrowViewport() throws Exception {
        long start = value(() -> graph.viewportStartForTest());
        long end = value(() -> graph.viewportEndForTest());
        long span = Math.max(2L, end - start);
        long nextStart = start + span / 5;
        long nextEnd = start + span / 2;
        fx("narrow", () -> {
            graph.setViewportForTest(nextStart, nextEnd);
            graph.reloadViewportForTest();
        });
        await("narrowed", () -> graph.viewportStartForTest() == nextStart
                && graph.viewportEndForTest() == nextEnd
                && !graph.graphStatusForTest().startsWith("Loading"), 60);
        return new long[] {nextStart, nextEnd};
    }

    private static double[] hitPoint() throws Exception {
        return hitPointAt(0.35);
    }

    private static double[] hitPointAt(double fraction) throws Exception {
        AtomicReference<double[]> found = new AtomicReference<>();
        fx("hit", () -> {
            AutomotiveSignalCanvas canvas = graph.graphCanvasForTest();
            double[] size = canvas.innerCanvasSizeForTest();
            double width = size[0] > 1 ? size[0] : Math.max(200, canvas.getWidth());
            double trackHeight = size[1] > 1 ? size[1] : Math.max(80, canvas.getHeight() * 0.4);
            double trackTop = size[1] > 1 ? size[2] : 40;
            double[] fractions = {0.28, 0.72};
            for (double trackFraction : fractions) {
                double y = trackTop + trackHeight * trackFraction;
                double x = Math.max(90, width * fraction);
                SharedTimelineHit.Hit hit = canvas.hitForTest(x, y);
                if (recordingId(hit)) {
                    found.set(new double[]{x, y});
                    return;
                }
                for (double scan = 90; scan < width - 30; scan += 24) {
                    SharedTimelineHit.Hit next = canvas.hitForTest(scan, y);
                    if (recordingId(next)) {
                        found.set(new double[]{scan, y});
                        return;
                    }
                }
            }
            if (graph.fileFocusActiveForTest()) {
                double y = trackTop + trackHeight * 0.45;
                found.set(new double[]{Math.max(90, width * fraction), y});
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
            if (!graph.mapRoutePaneForTest().gpsSegmentsForTest().isEmpty()) {
                found.set(new double[]{width * 0.5, height * 0.5});
            }
        });
        return found.get();
    }

    private static String lastClick = "";

    private static void clickGraph(double x, double y) {
        AutomotiveSignalCanvas canvas = graph.graphCanvasForTest();
        javafx.geometry.Point2D scenePoint = canvas.localToScene(x, y);
        double sx = scenePoint == null ? x : scenePoint.getX();
        double sy = scenePoint == null ? y : scenePoint.getY();
        StringBuilder seen = new StringBuilder();
        javafx.event.EventHandler<MouseEvent> filter = event -> seen.append(event.getEventType().getName())
                .append(" x=").append(event.getX())
                .append(" y=").append(event.getY())
                .append(" button=").append(event.getButton())
                .append(';');
        canvas.addEventFilter(MouseEvent.ANY, filter);
        try {
            canvas.fireEvent(new MouseEvent(
                    canvas, canvas, MouseEvent.MOUSE_PRESSED,
                    sx, sy, sx, sy, MouseButton.PRIMARY, 1,
                    false, false, false, false, true, false, false, true, false, false, null));
            canvas.fireEvent(new MouseEvent(
                    canvas, canvas, MouseEvent.MOUSE_RELEASED,
                    sx, sy, sx, sy, MouseButton.PRIMARY, 1,
                    false, false, false, false, false, false, false, true, false, false, null));
        } finally {
            canvas.removeEventFilter(MouseEvent.ANY, filter);
        }
        SharedTimelineHit.Hit hit = canvas.hitForTest(x, y);
        lastClick = "local=" + x + "," + y + " scene=" + sx + "," + sy
                + " hit=" + (hit == null ? "none" : hit.measurementId())
                + " events=" + seen;
    }

    private static void clickRoute(double x, double y) {
        graph.mapRoutePaneForTest().routeCanvasForTest().clickAtForTest(x, y);
    }

    private static boolean recordingId(SharedTimelineHit.Hit hit) {
        if (hit == null || hit.measurementId() == null) {
            return false;
        }
        String id = hit.measurementId();
        return id.length() >= 32 && id.indexOf('-') > 0
                && !"PROJECT".equals(id) && !"_none_".equals(id);
    }

    private static int colorOf(String blob, String signal) {
        for (var trace : GraphInvestigationCodec.decodeTraces(blob)) {
            if (trace.key() != null && signal.equals(trace.key().signalName())) {
                return trace.colorIndex();
            }
        }
        return -1;
    }

    private static String colorSummary(String blob) {
        StringBuilder text = new StringBuilder();
        for (var trace : GraphInvestigationCodec.decodeTraces(blob)) {
            if (trace.key() == null) {
                continue;
            }
            if (text.length() > 0) {
                text.append(',');
            }
            text.append(trace.key().signalName()).append('=').append(trace.colorIndex());
        }
        return text.toString();
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
        Files.writeString(EVIDENCE.resolve("m3-stall-" + name + ".txt"), dump);
    }

    private static int segmentTotal() {
        int total = 0;
        int tracks = graph.logicalTrackCountForTest();
        for (int i = 0; i < tracks; i++) {
            total += graph.segmentCountForTest(i);
        }
        return total;
    }

    private static String segmentSummary() {
        int tracks = graph.logicalTrackCountForTest();
        StringBuilder text = new StringBuilder();
        text.append("tracks=").append(tracks);
        for (int i = 0; i < tracks; i++) {
            text.append(" t").append(i).append('=').append(graph.segmentCountForTest(i));
        }
        return text.toString();
    }

    private static long usedHeap() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    private static void open(Path home, Path db) throws Exception {
        System.setProperty("stellarai.test.isolate", "true");
        System.setProperty("stellarai.home", home.toString());
        service = new DesktopAppService(new DesktopSession(), new SqliteProjectMetadataStore(db));
        var project = service.metadataStore().listProjects().stream()
                .filter(row -> "M2-73".equals(row.name()))
                .findFirst()
                .orElseThrow();
        assertTrue(service.openSavedProject(project.projectId()).opened());
        JavaFxTestSupport.startToolkit();
    }

    private static void closeStage() throws Exception {
        if (stage == null) {
            return;
        }
        fx("close", () -> stage.close());
        stage = null;
        scene = null;
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
                    return;
                }
            }
        });
        assertTrue(found.get() != null, "missing " + name);
        return found.get();
    }

    private static void shot(String name) throws Exception {
        Files.createDirectories(EVIDENCE);
        AtomicReference<WritableImage> image = new AtomicReference<>();
        fx("shot " + name, () -> image.set(scene.snapshot(null)));
        if (image.get() != null) {
            ImageIO.write(SwingFXUtils.fromFXImage(image.get(), null), "png", EVIDENCE.resolve(name).toFile());
        }
    }

    private static long fxMillis(String label, JavaFxTestSupport.FxWork work) throws Exception {
        AtomicLong elapsed = new AtomicLong();
        fx(label, () -> {
            long started = System.nanoTime();
            work.run();
            elapsed.set((System.nanoTime() - started) / 1_000_000L);
        });
        return elapsed.get();
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

    private static void await(String label, java.util.function.BooleanSupplier condition, int seconds) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (System.nanoTime() < deadline) {
            AtomicBoolean ok = new AtomicBoolean();
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
            }, "m3-stall-watch");
            watch.setDaemon(true);
            watch.start();
            long started = System.nanoTime();
            fx(label, () -> ok.set(condition.getAsBoolean()));
            finished.set(true);
            watch.interrupt();
            long waited = (System.nanoTime() - started) / 1_000_000L;
            if (waited > 2_000L) {
                note(label + " fxWaitMs=" + waited);
            }
            if (ok.get()) {
                return;
            }
            Thread.sleep(250L);
        }
        throw new AssertionError("timed out waiting for " + label
                + " status=" + text(() -> graph.graphStatusForTest())
                + " range=" + text(() -> graph.rangeTextForTest())
                + " group=" + text(() -> graph.timelineGroupForTest())
                + " segments=" + text(() -> segmentSummary()));
    }

    private static String text(java.util.function.Supplier<String> read) throws Exception {
        AtomicReference<String> value = new AtomicReference<>();
        fx("text", () -> value.set(read.get()));
        return value.get() == null ? "" : value.get();
    }

    private static long value(java.util.function.Supplier<Long> read) throws Exception {
        AtomicReference<Long> value = new AtomicReference<>();
        fx("long", () -> value.set(read.get()));
        return value.get() == null ? 0L : value.get();
    }

    private static void note(String line) {
        NOTES.append(line).append('\n');
    }

    private record Sync(String layout, String direction, String graphMeasurementId, long graphStoredNanos,
                         String gpsMeasurementId, long gpsStoredNanos, long latencyMs, long fxQueueMs) {
        @Override
        public String toString() {
            return layout + " " + direction
                    + " graphId=" + graphMeasurementId + " graphStored=" + graphStoredNanos
                    + " gpsId=" + gpsMeasurementId + " gpsStored=" + gpsStoredNanos
                    + " latencyMs=" + latencyMs + " fxQueueMs=" + fxQueueMs;
        }
    }
}
