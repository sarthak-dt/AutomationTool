package com.stellar.platform.desktop.ui;

import com.stellar.platform.desktop.DesktopVersion;
import com.stellar.platform.desktop.presentation.DesktopAppService;
import com.stellar.platform.desktop.presentation.NavigationPage;
import com.stellar.platform.desktop.session.DesktopSession;
import com.stellar.platform.desktop.support.JavaFxTestSupport;
import com.stellar.platform.desktop.ui.canvas.CanvasRenderSupport;
import com.stellar.platform.desktop.ui.graph.AutomotiveSignalCanvas;
import com.stellar.platform.desktop.ui.graph.map.RouteCanvas;
import com.stellar.platform.domain.catalog.SignalCatalogEntry;
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
import java.awt.image.BufferedImage;
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
 * Live render probe on the isolated 73-file Xenon project.
 * Times visible Graph and GPS Map interaction, including the JavaFX queue wait.
 */
@Timeout(value = 15, unit = TimeUnit.MINUTES)
class Milestone31RenderLiveTest {

    private static final Path EVIDENCE = Path.of(
            System.getProperty("java.io.tmpdir"), "stellar-accuracy", "m2-evidence");
    private static final int[][] ROUTE_COLORS = {
            {0x25, 0x63, 0xEB},
            {0x05, 0x96, 0x69},
            {0xD9, 0x77, 0x06},
            {0x7C, 0x3A, 0xED},
            {0xDB, 0x27, 0x77},
            {0x0F, 0x76, 0x6E},
            {0x16, 0x77, 0xFF}
    };
    private static final int[] START = {0x22, 0xA0, 0x6B};
    private static final int[] END = {0xE5, 0x48, 0x4D};

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
            Files.writeString(EVIDENCE.resolve("m31-render-notes-" + pipeline + ".txt"), NOTES.toString());
        } catch (Exception ignored) {
            // The assertion report still carries the same notes.
        }
        if (service != null) {
            service.shutdown();
            service = null;
        }
    }

    @Test
    void graphAndMapStayResponsiveAfterViewportChanges() throws Exception {
        Path home = Path.of(System.getProperty("java.io.tmpdir"), "stellar-accuracy", "m2-73-home");
        Path db = home.resolve("meta.db");
        Assumptions.assumeTrue(Files.isRegularFile(db), "isolated M2-73 project is not present");
        System.setProperty("stellarai.test.isolate", "true");
        System.setProperty("stellarai.home", home.toString());
        note("pipelineProperty=" + System.getProperty("prism.order", "unset")
                + " pipeline=" + CanvasRenderSupport.pipelineName());
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
        note("stacked " + segmentSummary()
                + " fxQueueMs=" + value(() -> graph.lastViewportFxQueueMsForTest())
                + " graphCanvas=" + text(() -> graph.graphCanvasForTest().canvasMetricsForTest())
                + " cache=" + text(() -> graph.graphCacheStatsForTest())
                + " heapMb=" + (usedHeap() / (1024 * 1024)));

        long mapStarted = System.nanoTime();
        fx("map", () -> graph.selectMapRouteTab());
        await("routes", () -> readyMap() && graph.mapRoutePaneForTest().routeCanvasForTest().overlayCountForTest() >= 73, 180);
        long firstMapMs = (System.nanoTime() - mapStarted) / 1_000_000L;
        note("firstMapMs=" + firstMapMs
                + " loading=" + flag(() -> graph.mapRoutePaneForTest().loadingVisibleForTest())
                + " status=" + oneLine(() -> graph.mapRoutePaneForTest().routeStatusForTest())
                + " routeCanvas=" + text(() -> graph.mapRoutePaneForTest().routeCanvasForTest().canvasMetricsForTest())
                + " stale=" + value(() -> graph.mapRoutePaneForTest().staleDiscardsForTest())
                + " heapMb=" + (usedHeap() / (1024 * 1024)));
        assertFalse(flag(() -> graph.mapRoutePaneForTest().loadingVisibleForTest()), "loading overlay still covering routes");
        fx("paint all", () -> graph.mapRoutePaneForTest().routeCanvasForTest().paintForTest());
        assertEquals(RouteCanvas.EndpointPolicy.GLOBAL,
                valuePolicy(() -> graph.mapRoutePaneForTest().routeCanvasForTest().endpointPolicyForTest()));
        assertEquals(2, fxRef("all marks", () -> graph.mapRoutePaneForTest().routeCanvasForTest().endpointMarksForTest().size()));
        assertTrue(text(() -> graph.mapRoutePaneForTest().routeCanvasForTest().endpointMarksForTest().toString()).contains("START"));
        long shotMs = shot("m31-all-routes-" + pipelineTag() + ".png");
        int allHits = paletteHits(EVIDENCE.resolve("m31-all-routes-" + pipelineTag() + ".png"));
        note("allRoutesShotMs=" + shotMs + " routePixels=" + allHits);
        assertTrue(allHits >= 40, "all-routes strokes were not visible in the plot, pixels=" + allHits);

        var segment = fxRef("first route", () -> graph.mapRoutePaneForTest().gpsSegmentsForTest().getFirst());
        AtomicReference<String> marksRef = new AtomicReference<>();
        long selectedStarted = System.nanoTime();
        fx("selected", () -> {
            graph.selectMapRouteTab();
            graph.mapRoutePaneForTest().showOnly(segment);
            graph.mapRoutePaneForTest().routeCanvasForTest().paintForTest();
            marksRef.set(graph.mapRoutePaneForTest().routeCanvasForTest().endpointMarksForTest().toString()
                    + " policy=" + graph.mapRoutePaneForTest().routeCanvasForTest().endpointPolicyForTest()
                    + " overlays=" + graph.mapRoutePaneForTest().routeCanvasForTest().overlayCountForTest()
                    + " tab=" + graph.analysisTabIndexForTest());
        });
        long selectedMs = (System.nanoTime() - selectedStarted) / 1_000_000L;
        String marks = marksRef.get() == null ? "" : marksRef.get();
        note("selectedRouteMs=" + selectedMs + " marks=" + marks
                + " routeCanvas=" + text(() -> graph.mapRoutePaneForTest().routeCanvasForTest().canvasMetricsForTest()));
        assertTrue(marks.contains("START"), marks);
        assertTrue(marks.contains("END"), marks);
        assertFalse(marks.contains("POINT"), marks);
        long selectedShot = shot("m31-selected-route-" + pipelineTag() + ".png");
        int selectedHits = paletteHits(EVIDENCE.resolve("m31-selected-route-" + pipelineTag() + ".png"));
        int startHits = colorHits(EVIDENCE.resolve("m31-selected-route-" + pipelineTag() + ".png"), START);
        int endHits = colorHits(EVIDENCE.resolve("m31-selected-route-" + pipelineTag() + ".png"), END);
        note("selectedShotMs=" + selectedShot + " routePixels=" + selectedHits
                + " startPixels=" + startHits + " endPixels=" + endHits);
        assertTrue(selectedHits >= 20, "selected route stroke was not visible, pixels=" + selectedHits);
        assertTrue(startHits >= 4, "green START was not visible, pixels=" + startHits);
        assertTrue(endHits >= 4, "red END was not visible, pixels=" + endHits);

        fx("all again", () -> graph.mapRoutePaneForTest().showAllRoutes());
        fx("waveforms", () -> graph.selectWaveformTabForTest());
        long start = value(() -> graph.viewportStartForTest());
        long end = value(() -> graph.viewportEndForTest());
        long span = Math.max(2L, end - start);
        long nextStart = start + span / 10;
        long nextEnd = end - span / 10;
        fx("nudge", () -> {
            graph.setViewportForTest(nextStart, nextEnd);
            graph.reloadViewportForTest();
        });
        await("nudged", () -> graph.viewportStartForTest() == nextStart
                && !graph.graphStatusForTest().startsWith("Loading"), 90);
        note("nudged fxQueueMs=" + value(() -> graph.lastViewportFxQueueMsForTest())
                + " graphCanvas=" + text(() -> graph.graphCanvasForTest().canvasMetricsForTest())
                + " graphPaints=" + value(() -> (long) graph.canvasPaintCountForTest())
                + " mapPaints=" + value(() -> (long) graph.mapRoutePaneForTest().routeCanvasForTest().paintCountForTest()));
        fx("viewport cache", () -> assertEquals(Boolean.FALSE, graph.waveformViewportCachedForTest(),
                "waveform scroll viewport cache"));
        long graphPaintsBefore = value(() -> (long) graph.canvasPaintCountForTest());
        long mapPaintsBefore = value(() -> (long) graph.mapRoutePaneForTest().routeCanvasForTest().paintCountForTest());
        long revisitStarted = System.nanoTime();
        fx("map revisit", () -> graph.selectMapRouteTab());
        long tabSwitchMs = (System.nanoTime() - revisitStarted) / 1_000_000L;
        await("map reused", () -> readyMap()
                && !graph.mapRoutePaneForTest().loadingVisibleForTest()
                && graph.mapRoutePaneForTest().routeCanvasForTest().overlayCountForTest() >= 73, 20);
        long revisitMs = (System.nanoTime() - revisitStarted) / 1_000_000L;
        note("revisitMs=" + revisitMs
                + " tabSwitchMs=" + tabSwitchMs
                + " afterTabMs=" + (revisitMs - tabSwitchMs)
                + " loading=" + flag(() -> graph.mapRoutePaneForTest().loadingVisibleForTest())
                + " overlays=" + value(() -> (long) graph.mapRoutePaneForTest().routeCanvasForTest().overlayCountForTest())
                + " stale=" + value(() -> graph.mapRoutePaneForTest().staleDiscardsForTest())
                + " graphPaints=" + graphPaintsBefore + "->" + value(() -> (long) graph.canvasPaintCountForTest())
                + " mapPaints=" + mapPaintsBefore + "->" + value(() -> (long) graph.mapRoutePaneForTest().routeCanvasForTest().paintCountForTest())
                + " heapMb=" + (usedHeap() / (1024 * 1024))
                + " routeCanvas=" + text(() -> graph.mapRoutePaneForTest().routeCanvasForTest().canvasMetricsForTest()));
        assertTrue(revisitMs < 3_000L, "map rebuilt after a graph viewport change, revisitMs=" + revisitMs);
        assertFalse(flag(() -> graph.mapRoutePaneForTest().loadingVisibleForTest()));

        Sync graphToGps = graphToGps();
        Sync gpsToGraph = gpsToGraph();
        assertEquals(graphToGps.graphId, graphToGps.gpsId, graphToGps.toString());
        assertEquals(gpsToGraph.graphId, gpsToGraph.gpsId, gpsToGraph.toString());
        assertFalse(graphToGps.graphId.isBlank());
        assertEquals(gpsToGraph.gpsStored, gpsToGraph.graphStored);

        long beforeGc = usedHeap();
        System.gc();
        Thread.sleep(400L);
        long afterGc = usedHeap();
        note("heapBeforeGcMb=" + (beforeGc / (1024 * 1024))
                + " heapAfterGcMb=" + (afterGc / (1024 * 1024))
                + " " + gcSummary()
                + " threads=" + threadSummary()
                + " cache=" + text(() -> graph.graphCacheStatsForTest()));
        note(graphToGps.toString());
        note(gpsToGraph.toString());
    }

    private static String pipelineTag() {
        String order = System.getProperty("prism.order", "default");
        return order == null || order.isBlank() ? "default" : order;
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

    /** A selected recording replaces the project status with the file name. It is still ready. */
    private static boolean mapSettled() {
        String status = graph.mapRoutePaneForTest().routeStatusForTest();
        return !graph.mapRoutePaneForTest().loadingVisibleForTest()
                && status != null
                && !status.isBlank()
                && !status.startsWith("Loading");
    }

    private static Sync graphToGps() throws Exception {
        fx("wave sync", () -> graph.selectWaveformTabForTest());
        await("drawn", () -> graph.segmentCountForTest(0) >= 1
                && !graph.graphStatusForTest().startsWith("Loading"), 60);
        double[] point = hitPoint();
        assertTrue(point != null, "no visible graph sample");
        long started = System.nanoTime();
        fx("graph click", () -> clickGraph(point[0], point[1]));
        long latency = (System.nanoTime() - started) / 1_000_000L;
        String graphId = text(() -> graph.cursorMeasurementIdForTest());
        long graphTime = value(() -> graph.cursorAForTest() == null ? 0L : graph.cursorAForTest());
        fx("map after graph", () -> graph.selectMapRouteTab());
        await("map after graph", Milestone31RenderLiveTest::mapSettled, 20);
        String sample = text(() -> graph.mapRoutePaneForTest().selectedSampleForTest());
        String[] parts = sample.split("\t", -1);
        assertEquals(4, parts.length, "map sample [" + sample + "] click=" + lastClick);
        if (latency > 2_000L) {
            dumpThreads("graph-click");
        }
        return new Sync("graph-to-gps", graphId, graphTime, parts[0], Long.parseLong(parts[1]), latency,
                value(() -> graph.lastViewportFxQueueMsForTest()));
    }

    private static Sync gpsToGraph() throws Exception {
        await("map for click", () -> !graph.mapRoutePaneForTest().gpsSegmentsForTest().isEmpty(), 20);
        double[] point = routePoint();
        assertTrue(point != null, "no visible GPS point");
        long started = System.nanoTime();
        fx("route click", () -> clickRoute(point[0], point[1]));
        long latency = (System.nanoTime() - started) / 1_000_000L;
        String sample = text(() -> graph.mapRoutePaneForTest().selectedSampleForTest());
        String[] parts = sample.split("\t", -1);
        assertEquals(4, parts.length, sample);
        fx("see graph", () -> graph.selectWaveformTabForTest());
        await("graph cursor", () -> parts[0].equals(graph.cursorMeasurementIdForTest())
                && graph.cursorAForTest() != null
                && graph.cursorAForTest() == Long.parseLong(parts[1]), 60);
        if (latency > 2_000L) {
            dumpThreads("gps-click");
        }
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
            double y = trackTop + trackHeight * 0.35;
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
            if (!graph.mapRoutePaneForTest().gpsSegmentsForTest().isEmpty()) {
                found.set(new double[]{width * 0.5, height * 0.5});
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

    private static int paletteHits(Path png) throws Exception {
        int total = 0;
        for (int[] color : ROUTE_COLORS) {
            total += colorHits(png, color);
        }
        return total;
    }

    private static int colorHits(Path png, int[] rgb) throws Exception {
        BufferedImage image = ImageIO.read(png.toFile());
        int hits = 0;
        int x0 = Math.min(280, image.getWidth() / 5);
        int x1 = image.getWidth() - 24;
        int y0 = Math.min(320, image.getHeight() / 3);
        int y1 = image.getHeight() - 24;
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                int pixel = image.getRGB(x, y);
                int distance = Math.abs(((pixel >> 16) & 255) - rgb[0])
                        + Math.abs(((pixel >> 8) & 255) - rgb[1])
                        + Math.abs((pixel & 255) - rgb[2]);
                if (distance <= 70) {
                    hits++;
                }
            }
        }
        return hits;
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
        return "stellarWorkers=" + workers + " fxThreads=" + fx + " quantum=" + quantum
                + " active=" + Thread.activeCount();
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
        StringBuilder text = new StringBuilder("tracks=").append(tracks);
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

    private static long shot(String name) throws Exception {
        Files.createDirectories(EVIDENCE);
        AtomicReference<WritableImage> image = new AtomicReference<>();
        long started = System.nanoTime();
        fx("shot " + name, () -> {
            graph.selectMapRouteTab();
            image.set(scene.snapshot(null));
        });
        long elapsed = (System.nanoTime() - started) / 1_000_000L;
        if (image.get() != null) {
            ImageIO.write(SwingFXUtils.fromFXImage(image.get(), null), "png", EVIDENCE.resolve(name).toFile());
        }
        return elapsed;
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
            }, "m31-stall-watch");
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
            Thread.sleep(200L);
        }
        throw new AssertionError("timed out waiting for " + label
                + " graph=" + text(() -> graph.graphStatusForTest())
                + " map=" + oneLine(() -> graph.mapRoutePaneForTest().routeStatusForTest()));
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
        Files.writeString(EVIDENCE.resolve("m31-stall-" + name + ".txt"), dump);
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

    private static RouteCanvas.EndpointPolicy valuePolicy(java.util.function.Supplier<RouteCanvas.EndpointPolicy> read) throws Exception {
        return fxRef("policy", read);
    }

    private static void note(String line) {
        NOTES.append(line).append('\n');
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
