package com.stellar.platform.desktop.ui.graph;

import com.stellar.platform.desktop.support.JavaFxTestSupport;
import com.stellar.platform.desktop.ui.canvas.CanvasRenderSupport;
import com.stellar.platform.desktop.ui.graph.map.RouteCanvas;
import com.stellar.platform.domain.CanonicalSignal;
import com.stellar.platform.domain.SourceReference;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSamplePoint;
import com.stellar.platform.processing.catalog.InMemorySignalCatalogStore;
import com.stellar.platform.processing.graph.GraphBucket;
import com.stellar.platform.processing.graph.GraphLogicalTrack;
import com.stellar.platform.processing.graph.GraphRenderSeries;
import com.stellar.platform.processing.graph.GraphTrackSegment;
import com.stellar.platform.processing.graph.LosslessGraphSignalStore;
import com.stellar.platform.processing.graph.timeline.UnknownOrderLayout;
import com.stellar.platform.processing.map.MapRoute;
import com.stellar.platform.processing.map.MapRouteBuilder;
import com.stellar.platform.processing.map.RouteSegment;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Synthetic Graph and GPS canvas timings. The samples are generated and queried
 * from the lossless store, then drawn by the product canvases. This is not the
 * live 73-file Desktop project and it is not 300 independent customer recordings.
 */
@Timeout(value = 10, unit = TimeUnit.MINUTES)
class ScalabilityRenderTest {

    private static final int RECORDINGS = 300;
    private static final int SAMPLES = 200;
    private static final int GPS_SAMPLES = 20;
    private static final int SIGNALS = 120;
    private static final long SLOT = UnknownOrderLayout.EQUAL_SLOT_SPAN;
    private static final Path EVIDENCE = Path.of(
            System.getProperty("java.io.tmpdir"), "stellar-accuracy", "m2-evidence");

    private static final StringBuilder NOTES = new StringBuilder();

    @TempDir
    Path temp;

    @BeforeAll
    static void startFx() throws Exception {
        JavaFxTestSupport.startToolkit();
    }

    @AfterAll
    static void writeNotes() throws Exception {
        Files.createDirectories(EVIDENCE);
        String pipeline = System.getProperty("prism.order", "default");
        Files.writeString(EVIDENCE.resolve("m42-render-notes-" + pipeline + ".txt"), NOTES.toString());
    }

    @Test
    void syntheticQueriesRenderOnTheGraphAndMapCanvases() throws Exception {
        note("source=synthetic pipelineProperty=" + System.getProperty("prism.order", "unset")
                + " pipeline=" + CanvasRenderSupport.pipelineName());
        note("methodology=generated series with measurement ids synthetic-render-000..299; "
                + "queried through LosslessGraphSignalStore; painted by AutomotiveSignalCanvas and RouteCanvas");
        List<QueriedRecording> recordings = writeRecordings();
        List<GraphLogicalTrack> signalTracks = writeSignalTracks();
        paintGraphs(recordings, signalTracks);
        paintRoutes(recordings);
        long beforeGc = usedHeap();
        System.gc();
        note("heapBeforeGcMb=" + mb(beforeGc) + " heapAfterGcMb=" + mb(usedHeap()));
    }

    private List<QueriedRecording> writeRecordings() throws Exception {
        long started = System.nanoTime();
        List<QueriedRecording> recordings = new ArrayList<>();
        for (int i = 0; i < RECORDINGS; i++) {
            String measurement = id(i);
            Path root = temp.resolve(measurement);
            try (LosslessGraphSignalStore store = new LosslessGraphSignalStore(root, measurement, "", 1_024)) {
                List<CanonicalSignal> batch = new ArrayList<>();
                for (int sample = 0; sample < SAMPLES; sample++) {
                    batch.add(numeric("SYN_Speed", 0x180, sample * 1_000_000L, 20.0 + (i % 50) + sample * 0.01, (long) sample));
                }
                for (int sample = 0; sample < GPS_SAMPLES; sample++) {
                    long timestamp = sample * 1_000_000L;
                    batch.add(numeric("SYN_Lat", 0x181, timestamp, 18.50 + (i % 20) * 0.01 + sample * 0.0001, null));
                    batch.add(numeric("SYN_Lon", 0x182, timestamp, 73.70 + (i / 20) * 0.01 + sample * 0.0001, null));
                }
                store.accept(batch);
            }
            try (LosslessGraphSignalStore store = LosslessGraphSignalStore.openExisting(root)) {
                SignalCatalogKey speed = key("SYN_Speed", 0x180, measurement);
                long last = (SAMPLES - 1L) * 1_000_000L;
                List<GraphBucket> buckets = store.queryBuckets(speed, 0L, last, 32);
                assertEquals(SAMPLES, buckets.stream().mapToLong(GraphBucket::sampleCount).sum());
                List<SignalSamplePoint> latitude = store.queryRange(key("SYN_Lat", 0x181, measurement), 0L, last);
                List<SignalSamplePoint> longitude = store.queryRange(key("SYN_Lon", 0x182, measurement), 0L, last);
                assertEquals(GPS_SAMPLES, latitude.size());
                assertEquals(GPS_SAMPLES, longitude.size());
                assertEquals(latitude.getFirst().timestampNanos(), longitude.getFirst().timestampNanos());
                assertEquals(latitude.getLast().timestampNanos(), longitude.getLast().timestampNanos());
                recordings.add(new QueriedRecording(measurement, speed, buckets, latitude, longitude));
            }
        }
        note("decodedRecordings=" + recordings.size()
                + " samplesPerRecording=" + SAMPLES
                + " gpsSamples=" + GPS_SAMPLES
                + " queryAndWriteMs=" + millis(started));
        return recordings;
    }

    private List<GraphLogicalTrack> writeSignalTracks() throws Exception {
        String measurement = "synthetic-render-signals";
        Path root = temp.resolve(measurement);
        long started = System.nanoTime();
        List<GraphLogicalTrack> tracks = new ArrayList<>();
        try (LosslessGraphSignalStore store = new LosslessGraphSignalStore(root, measurement, "", 4_096)) {
            for (int signal = 0; signal < SIGNALS; signal++) {
                List<CanonicalSignal> batch = new ArrayList<>();
                for (int sample = 0; sample < 80; sample++) {
                    batch.add(numeric("SYN_Signal_" + signal, 0x200 + signal, sample * 1_000_000L,
                            signal + sample * 0.05, (long) sample));
                }
                store.accept(batch);
            }
        }
        try (LosslessGraphSignalStore store = LosslessGraphSignalStore.openExisting(root)) {
            assertEquals(SIGNALS, store.keys().size());
            for (int signal = 0; signal < SIGNALS; signal++) {
                SignalCatalogKey physical = key("SYN_Signal_" + signal, 0x200 + signal, measurement);
                long last = 79L * 1_000_000L;
                List<GraphBucket> buckets = store.queryBuckets(physical, 0L, last, 20);
                assertEquals(80, buckets.stream().mapToLong(GraphBucket::sampleCount).sum());
                GraphRenderSeries series = new GraphRenderSeries(physical, "unit", 80, false, 0L, last, buckets);
                SignalCatalogKey logical = new SignalCatalogKey(
                        physical.signalName(), physical.sourceType(), physical.messageName(),
                        physical.canId(), physical.channel(), "PROJECT", "");
                tracks.add(new GraphLogicalTrack(logical,
                        List.of(new GraphTrackSegment(series, measurement + ".dat", null, 0L, SLOT)), false));
            }
        }
        note("logicalSignals=" + tracks.size() + " samplesPerSignal=80 queryMs=" + millis(started));
        return tracks;
    }

    private void paintGraphs(List<QueriedRecording> recordings, List<GraphLogicalTrack> signalTracks) throws Exception {
        GraphLogicalTrack files73 = fileTrack(recordings, 73);
        GraphLogicalTrack files100 = fileTrack(recordings, 100);
        GraphLogicalTrack files300 = fileTrack(recordings, RECORDINGS);
        assertStoredClockSeparateFromSlots(files300);

        onFx("graph paint", () -> {
            timedGraph("stacked-73", List.of(files73), AutomotiveSignalCanvas.LayoutMode.STACKED, 73, 1);
            timedGraph("stacked-100", List.of(files100), AutomotiveSignalCanvas.LayoutMode.STACKED, 100, 1);
            timedGraph("stacked-300", List.of(files300), AutomotiveSignalCanvas.LayoutMode.STACKED, RECORDINGS, 1);
            timedGraph("overlay-300", List.of(files300), AutomotiveSignalCanvas.LayoutMode.OVERLAY_NORMALIZED, RECORDINGS, 1);
            timedGraph("signals-1", signalTracks.subList(0, 1), AutomotiveSignalCanvas.LayoutMode.STACKED, 1, 1);
            timedGraph("signals-10", signalTracks.subList(0, 10), AutomotiveSignalCanvas.LayoutMode.STACKED, 1, 10);
            timedGraph("signals-30", signalTracks.subList(0, 30), AutomotiveSignalCanvas.LayoutMode.STACKED, 1, 30);
            timedGraph("signals-120", signalTracks, AutomotiveSignalCanvas.LayoutMode.STACKED, 1, SIGNALS);
            timedGraph("overlay-120", signalTracks, AutomotiveSignalCanvas.LayoutMode.OVERLAY_NORMALIZED, 1, SIGNALS);
        });
    }

    private void timedGraph(
            String label, List<GraphLogicalTrack> tracks, AutomotiveSignalCanvas.LayoutMode mode,
            int expectedSegments, int expectedTracks
    ) {
        AutomotiveSignalCanvas canvas = new AutomotiveSignalCanvas();
        Stage stage = new Stage();
        try {
            stage.setScene(new Scene(canvas, 960, 520));
            stage.show();
            canvas.resize(960, 520);
            canvas.setViewportFloorHeight(480);
            canvas.setVisibleWindow(480, 0);
            present(canvas, tracks);
            canvas.setLayoutMode(mode);
            canvas.setUnknownOrderOverview(expectedSegments > 1);
            canvas.setSharedViewport(0L, Math.max(SLOT, expectedSegments * SLOT));
            long started = System.nanoTime();
            canvas.layout();
            long layoutMs = millis(started);
            assertEquals(expectedTracks, canvas.logicalTrackCountForTest());
            assertEquals(expectedSegments, canvas.segmentCountForTest(0));
            assertEquals(GraphSeriesPalette.at(0), canvas.logicalTrackColorForTest(0));
            if (expectedTracks > 1) {
                assertNotEquals(canvas.logicalTrackColorForTest(0), canvas.logicalTrackColorForTest(1));
            }
            if (expectedSegments > 1) {
                assertTrue(canvas.unknownOrderOverviewForTest());
                assertEquals("UNKNOWN ORDER · slot position is not elapsed time", canvas.overviewCaptionForTest());
                assertTrue(canvas.getPrefHeight() < 500, label + " height " + canvas.getPrefHeight());
            }
            if (expectedTracks >= 100 && mode == AutomotiveSignalCanvas.LayoutMode.STACKED) {
                assertTrue(canvas.getPrefHeight() > 8_000, label + " virtual height " + canvas.getPrefHeight());
                double[] inner = canvas.innerCanvasSizeForTest();
                assertTrue(inner[1] <= 520, label + " texture height " + inner[1]);
                ColorHold first = new ColorHold(canvas.logicalTrackColorForTest(0));
                canvas.setVisibleWindow(480, 4_000);
                canvas.layout();
                assertEquals(first.color, canvas.logicalTrackColorForTest(0));
                assertEquals(expectedTracks, canvas.logicalTrackCountForTest());
                assertEquals("#0f8a96", GraphSeriesPalette.cssAt(0));
                assertEquals("#a16207", GraphSeriesPalette.cssAt(7));
                assertTrue(GraphSeriesPalette.dashesAt(24) != null);
            }
            String metrics = canvas.canvasMetricsForTest();
            long snapshotStarted = System.nanoTime();
            canvas.snapshot(null, null);
            note(label + " layoutMs=" + layoutMs
                    + " redrawMs=" + canvas.lastRedrawMillis()
                    + " snapshotMs=" + millis(snapshotStarted)
                    + " " + metrics
                    + " prefHeight=" + Math.round(canvas.getPrefHeight()));
            assertTrue(metrics.contains("maxEdge="), metrics);
        } finally {
            canvas.release();
            stage.hide();
        }
    }

    private void paintRoutes(List<QueriedRecording> recordings) throws Exception {
        List<RouteSegment> all = new ArrayList<>();
        long assemblyStarted = System.nanoTime();
        for (QueriedRecording recording : recordings) {
            MapRoute route = new MapRoute(MapRouteBuilder.build(
                    recording.latitude, recording.longitude, null));
            assertEquals(GPS_SAMPLES, route.model().size(), recording.measurement);
            assertEquals(recording.latitude.getFirst().timestampNanos(), route.model().timestampNanos(0));
            assertEquals(recording.latitude.getLast().timestampNanos(),
                    route.model().timestampNanos(route.model().size() - 1));
            route.lod();
            all.add(new RouteSegment(recording.measurement, recording.measurement + ".dat", route));
        }
        long assemblyMs = millis(assemblyStarted);
        assertNotEquals(all.getFirst().measurementId(), all.getLast().measurementId());
        onFx("routes", () -> {
            paintRouteSet("routes-73", all.subList(0, 73), assemblyMs);
            paintRouteSet("routes-100", all.subList(0, 100), assemblyMs);
            paintRouteSet("routes-300", all, assemblyMs);
            RouteCanvas selected = canvas();
            Stage stage = show(selected);
            try {
                long started = System.nanoTime();
                selected.setRoute(all.get(40).route());
                selected.fitRoute();
                selected.paintForTest();
                long paintMs = millis(started);
                String marks = selected.endpointMarksForTest().toString();
                assertEquals(RouteCanvas.EndpointPolicy.OWN_ROUTE, selected.endpointPolicyForTest());
                assertTrue(marks.contains("START"), marks);
                assertTrue(marks.contains("END"), marks);
                assertFalse(marks.contains("POINT"), marks);
                long again = System.nanoTime();
                selected.paintForTest();
                note("selected-route paintMs=" + paintMs
                        + " secondPaintMs=" + millis(again)
                        + " redrawMs=" + selected.lastRedrawMillis()
                        + " " + selected.canvasMetricsForTest()
                        + " marks=" + marks);
            } finally {
                stage.hide();
            }
        });
    }

    private static void paintRouteSet(String label, List<RouteSegment> segments, long assemblyMs) {
        RouteCanvas canvas = canvas();
        Stage stage = show(canvas);
        try {
            long started = System.nanoTime();
            canvas.setSegments(segments);
            canvas.fitRoute();
            canvas.paintForTest();
            long paintMs = millis(started);
            assertEquals(segments.size(), canvas.overlayCountForTest());
            assertEquals(RouteCanvas.EndpointPolicy.GLOBAL, canvas.endpointPolicyForTest());
            assertEquals(2, canvas.endpointMarksForTest().size());
            assertEquals("START", canvas.endpointMarksForTest().get(0).role());
            assertEquals("END", canvas.endpointMarksForTest().get(1).role());
            long again = System.nanoTime();
            canvas.paintForTest();
            long snapshotStarted = System.nanoTime();
            canvas.snapshot(null, null);
            note(label + " assemblyMs=" + assemblyMs
                    + " paintMs=" + paintMs
                    + " secondPaintMs=" + millis(again)
                    + " redrawMs=" + canvas.lastRedrawMillis()
                    + " snapshotMs=" + millis(snapshotStarted)
                    + " overlays=" + canvas.overlayCountForTest()
                    + " " + canvas.canvasMetricsForTest());
        } finally {
            stage.hide();
        }
    }

    private static GraphLogicalTrack fileTrack(List<QueriedRecording> recordings, int count) {
        List<GraphTrackSegment> segments = new ArrayList<>();
        SignalCatalogKey logical = new SignalCatalogKey(
                "SYN_Speed", recordings.getFirst().speed.sourceType(), "Synthetic", 0x180, "PROJECT", "");
        for (int i = 0; i < count; i++) {
            QueriedRecording recording = recordings.get(i);
            long last = (SAMPLES - 1L) * 1_000_000L;
            GraphRenderSeries series = new GraphRenderSeries(
                    recording.speed, "unit", SAMPLES, false, 0L, last, recording.buckets);
            assertEquals(recording.measurement, series.key().sourceFile());
            segments.add(new GraphTrackSegment(
                    series, recording.measurement + ".dat", null, i * SLOT, (i + 1) * SLOT));
        }
        return new GraphLogicalTrack(logical, segments, false);
    }

    private static void assertStoredClockSeparateFromSlots(GraphLogicalTrack track) {
        GraphTrackSegment first = track.segments().getFirst();
        GraphTrackSegment last = track.segments().getLast();
        assertEquals(0L, first.series().viewportStartTimestampNanos());
        assertTrue(first.layoutStartNanos() == 0L);
        assertTrue(last.layoutStartNanos() > last.series().viewportEndTimestampNanos());
        assertNotEquals(first.series().key().sourceFile(), last.series().key().sourceFile());
    }

    private static void present(AutomotiveSignalCanvas canvas, List<GraphLogicalTrack> tracks) {
        Map<SignalCatalogKey, Integer> colors = new LinkedHashMap<>();
        Map<SignalCatalogKey, String> labels = new LinkedHashMap<>();
        for (int i = 0; i < tracks.size(); i++) {
            colors.put(tracks.get(i).logicalKey(), i);
            labels.put(tracks.get(i).logicalKey(), tracks.get(i).logicalKey().signalName());
        }
        canvas.setTracePresentation(labels, colors);
        canvas.setLogicalTracks(tracks);
    }

    private static RouteCanvas canvas() {
        return new RouteCanvas();
    }

    private static Stage show(RouteCanvas canvas) {
        StackPane root = new StackPane(canvas);
        root.setPrefSize(640, 480);
        Stage stage = new Stage();
        stage.setScene(new Scene(root, 640, 480));
        stage.show();
        root.applyCss();
        root.layout();
        return stage;
    }

    private static SignalCatalogKey key(String name, int canId, String measurement) {
        return InMemorySignalCatalogStore.keyOf(numeric(name, canId, 0L, 0, 0L), measurement, "");
    }

    private static CanonicalSignal numeric(String name, int canId, long timestamp, double value, Long raw) {
        return new CanonicalSignal(name, "Synthetic", timestamp, canId, 1, value, raw, "unit", null,
                SourceReference.ofLine(1));
    }

    private static String id(int recording) {
        return String.format("synthetic-render-%03d", recording);
    }

    private static void onFx(String label, Runnable action) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<Throwable> error = new java.util.concurrent.atomic.AtomicReference<>();
        Platform.runLater(() -> {
            try {
                action.run();
            } catch (Throwable thrown) {
                error.set(thrown);
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(180, TimeUnit.SECONDS), label);
        if (error.get() != null) {
            throw new AssertionError(label, error.get());
        }
    }

    private static long millis(long started) {
        return (System.nanoTime() - started) / 1_000_000L;
    }

    private static long usedHeap() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    private static long mb(long bytes) {
        return bytes / (1024 * 1024);
    }

    private static void note(String line) {
        NOTES.append(line).append('\n');
    }

    private record QueriedRecording(
            String measurement,
            SignalCatalogKey speed,
            List<GraphBucket> buckets,
            List<SignalSamplePoint> latitude,
            List<SignalSamplePoint> longitude
    ) {
    }

    private record ColorHold(javafx.scene.paint.Color color) {
    }
}
