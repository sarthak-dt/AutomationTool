package com.stellar.platform.desktop.ui;

import com.stellar.platform.desktop.presentation.DesktopAppService;
import com.stellar.platform.desktop.session.DesktopSession;
import com.stellar.platform.desktop.support.JavaFxTestSupport;
import com.stellar.platform.desktop.ui.graph.AutomotiveSignalCanvas;
import com.stellar.platform.desktop.ui.graph.GraphAxisScale;
import com.stellar.platform.desktop.ui.graph.GraphSeriesPalette;
import com.stellar.platform.desktop.ui.graph.map.MapRoutePane;
import com.stellar.platform.desktop.ui.graph.map.MapRouteRequest;
import com.stellar.platform.desktop.ui.graph.map.MapTimeFormat;
import com.stellar.platform.domain.CanonicalMeasurementSample;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSamplePoint;
import com.stellar.platform.domain.catalog.SignalSourceType;
import com.stellar.platform.parser.mf4.decode.MdfDynamicRecordDecoder;
import com.stellar.platform.parser.mf4.support.SyntheticMdf4Builder;
import com.stellar.platform.parser.spi.ReaderContext;
import com.stellar.platform.processing.graph.GraphBucket;
import com.stellar.platform.processing.graph.GraphLogicalTrack;
import com.stellar.platform.processing.graph.GraphRenderSeries;
import com.stellar.platform.processing.graph.GraphSeriesQuery;
import com.stellar.platform.processing.graph.GraphTrackSegment;
import com.stellar.platform.processing.graph.SharedDisplayTimeline;
import com.stellar.platform.processing.map.MapRoute;
import com.stellar.platform.processing.map.MapRouteBuilder;
import com.stellar.platform.processing.map.MapRouteModel;
import com.stellar.platform.processing.map.ProjectRoute;
import com.stellar.platform.processing.map.ProjectRouteAssembler;
import com.stellar.platform.processing.map.RouteSegment;
import com.stellar.platform.processing.map.time.RecordingClock;
import com.stellar.platform.processing.map.time.RecordingClockReader;
import com.stellar.platform.processing.time.MeasurementCursor;
import com.stellar.platform.storage.metadata.SqliteProjectMetadataStore;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TabPane;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.BorderPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JavaFX integration of the shared UTC cursor. Mouse events are dispatched on the waveform
 * canvas, and route clicks use the handler the map canvas calls. This is not a live desktop session.
 */
class SharedUtcInteractionTest {

    private static final long S = 1_000_000_000L;
    private static final long ORIGIN = 1_674_000_000_000_000_000L;
    private static final double LEFT = 82;
    private static final double BOTTOM = 30;

    @TempDir
    Path temp;

    private DesktopAppService service;
    private GraphController graph;
    private Stage stage;

    @BeforeAll
    static void startFx() throws Exception {
        JavaFxTestSupport.startToolkit();
    }

    @AfterEach
    void tearDown() {
        if (service != null) {
            service.shutdown();
        }
    }

    @Test
    void graphAndGpsCursorsFollowTheSharedUtcTimeline() throws Exception {
        Path home = temp.resolve("home");
        System.setProperty("stellarai.home", home.toAbsolutePath().toString());
        assertFalse(System.getProperty("stellarai.home").startsWith("D:\\config"),
                "interaction test must not use the operator analysis storage");

        Recording a = write("utc-a", ORIGIN, 110, 18.111111, 73.222222);
        Recording c = write("utc-c", ORIGIN + 10 * S, 330, Double.NaN, Double.NaN);
        Recording b = write("utc-b", ORIGIN + 40 * S, 220, 19.333333, 74.444444);
        SharedDisplayTimeline timeline = SharedDisplayTimeline.of(List.of(member(a), member(c), member(b)));
        assertEquals(60 * S, timeline.displayEnd() - timeline.displayStart());
        ProjectRoute project = routeOfDecoded(List.of(c, b, a));

        JavaFxTestSupport.runOnFx("shared utc interaction", () -> {
            BorderPane root = loadGraph();
            graph = (GraphController) root.getProperties().get("controller");
            service = new DesktopAppService(new DesktopSession(), new SqliteProjectMetadataStore(temp.resolve("meta.db")));
            service.newProject("UTC interaction");
            graph.setAppService(service, null);
            stage = new Stage();
            stage.setScene(new Scene(root, 1100, 700));
            stage.show();

            AutomotiveSignalCanvas canvas = findCanvas(root);
            MapRoutePane map = graph.mapRoutePaneForTest();
            GraphLogicalTrack full = track(timeline, List.of(c, b, a), 0, 60 * S);
            install(canvas, map, timeline, project, full, 0, 60 * S);
            assertEquals(1, canvas.logicalTrackCountForTest());
            assertEquals(GraphSeriesPalette.at(0), canvas.logicalTrackColorForTest(0));
            assertEquals(2, route(map).routeSegmentCount());

            canvas.setLayoutMode(AutomotiveSignalCanvas.LayoutMode.OVERLAY_MULTI_AXIS);
            click(canvas, xAt(canvas, 15 * S), yFor(canvas, a, 15 * S));
            assertOwned("utc-a", 15 * S, ORIGIN + 15 * S);
            assertGps(map, "utc-a", 18.111411, 73.222522);
            long afterA = service.analysisSelection().measurementCursor().token();

            click(canvas, xAt(canvas, 15 * S), yFor(canvas, c, 5 * S));
            assertOwned("utc-c", 5 * S, ORIGIN + 15 * S);
            assertGpsCleared(map);
            assertNotEquals("utc-a", measurementId());
            assertEquals(afterA + 1, service.analysisSelection().measurementCursor().token());

            double x20 = xAt(canvas, 20 * S);
            double x40 = xAt(canvas, 40 * S);
            click(canvas, x20 + (x40 - x20) * 0.75, 200);
            assertGap(map);
            assertEquals(2, route(map).routeSegmentCount());
            assertEquals(3, route(map).segments().size());

            click(canvas, xAt(canvas, 45 * S), yFor(canvas, b, 5 * S));
            assertOwned("utc-b", 5 * S, ORIGIN + 45 * S);
            assertGps(map, "utc-b", 19.333433, 74.444544);

            pick(map, segment(map, "utc-a"), indexAt(segment(map, "utc-a"), 10 * S));
            assertFromMap("utc-a", 10 * S, 10 * S, ORIGIN + 10 * S);
            pick(map, segment(map, "utc-b"), indexAt(segment(map, "utc-b"), 5 * S));
            assertFromMap("utc-b", 5 * S, 45 * S, ORIGIN + 45 * S);

            canvas.setLayoutMode(AutomotiveSignalCanvas.LayoutMode.STACKED);
            double xOverlap = xAt(canvas, 15 * S);
            click(canvas, xOverlap, stackedY(canvas, List.of(c, b, a), 113));
            assertOwned("utc-a", 15 * S, ORIGIN + 15 * S);
            assertGps(map, "utc-a", 18.111411, 73.222522);
            click(canvas, xOverlap, stackedY(canvas, List.of(c, b, a), 331));
            assertOwned("utc-c", 5 * S, ORIGIN + 15 * S);
            assertGpsCleared(map);

            GraphLogicalTrack coincident = track(timeline, List.of(c, b, a), 10 * S, 20 * S);
            install(canvas, map, timeline, project, coincident, 10 * S, 20 * S);
            canvas.setLayoutMode(AutomotiveSignalCanvas.LayoutMode.OVERLAY_MULTI_AXIS);
            double xCoincident = xAt(canvas, 15 * S);
            double yCoincident = yWithChoices(canvas, xCoincident, 2);
            var coincidentChoices = canvas.choicesForTest(xCoincident, yCoincident);
            assertEquals(2, coincidentChoices.size());
            assertTrue(coincidentChoices.stream().anyMatch(choice -> "utc-a".equals(choice.hit().measurementId())
                    && Math.abs(choice.physicalValue() - 113) < 1));
            assertTrue(coincidentChoices.stream().anyMatch(choice -> "utc-c".equals(choice.hit().measurementId())
                    && Math.abs(choice.physicalValue() - 331) < 1));
            assertEquals(1, canvas.logicalTrackCountForTest());
            assertEquals(GraphSeriesPalette.at(0), canvas.logicalTrackColorForTest(0));
            long beforeChoice = service.analysisSelection().measurementCursor().token();
            click(canvas, xCoincident, yCoincident);
            assertTrue(graph.overlapChooserShowingForTest());
            assertEquals(beforeChoice, service.analysisSelection().measurementCursor().token());
            assertTrue(graph.overlapChoiceLabelsForTest().stream().anyMatch(label -> label.contains("utc-c.dat")));
            graph.chooseOverlapForTest("utc-c");
            assertOwned("utc-c", 5 * S, ORIGIN + 15 * S);
            assertGpsCleared(map);
            long afterRear = service.analysisSelection().measurementCursor().token();
            assertTrue(afterRear >= beforeChoice);
            click(canvas, xCoincident, yCoincident);
            graph.chooseOverlapForTest("utc-a");
            assertOwned("utc-a", 15 * S, ORIGIN + 15 * S);
            assertEquals(afterRear + 1, service.analysisSelection().measurementCursor().token());
            assertGps(map, "utc-a", 18.111411, 73.222522);
            assertEquals(1, canvas.logicalTrackCountForTest());
            assertEquals(GraphSeriesPalette.at(0), canvas.logicalTrackColorForTest(0));
            assertEquals(1, canvas.childCountForTest());
            click(canvas, xCoincident, yCoincident);
            assertTrue(graph.overlapChooserShowingForTest());
            move(canvas, xCoincident + 40, yCoincident);
            assertFalse(graph.overlapChooserShowingForTest());
            assertOwned("utc-a", 15 * S, ORIGIN + 15 * S);

            GraphLogicalTrack zoomed = track(timeline, List.of(c, b, a), 0, 20 * S);
            install(canvas, map, timeline, project, zoomed, 0, 20 * S);
            canvas.setLayoutMode(AutomotiveSignalCanvas.LayoutMode.OVERLAY_MULTI_AXIS);
            double xZoom = xAt(canvas, 15 * S);
            click(canvas, xZoom, yOwned(canvas, xZoom, "utc-c"));
            if (graph.overlapChooserShowingForTest()) {
                graph.chooseOverlapForTest("utc-c");
            }
            assertOwned("utc-c", 5 * S, ORIGIN + 15 * S);
            assertGpsCleared(map);
            click(canvas, xZoom, yOwned(canvas, xZoom, "utc-a"));
            if (graph.overlapChooserShowingForTest()) {
                graph.chooseOverlapForTest("utc-a");
            }
            assertOwned("utc-a", 15 * S, ORIGIN + 15 * S);

            install(canvas, map, timeline, project, full, 0, 60 * S);
            canvas.setLayoutMode(AutomotiveSignalCanvas.LayoutMode.OVERLAY_MULTI_AXIS);
            click(canvas, xAt(canvas, 45 * S), yFor(canvas, b, 5 * S));
            assertOwned("utc-b", 5 * S, ORIGIN + 45 * S);
            stage.close();
            graph.dispose();
        });
    }

    private void assertOwned(String id, long stored, long epoch) {
        MeasurementCursor cursor = service.analysisSelection().measurementCursor();
        assertEquals(id, cursor.measurementId());
        assertTrue(Math.abs(cursor.relativeTimestampNanos() - stored) < 100_000_000L,
                "stored " + cursor.relativeTimestampNanos() + " expected " + stored);
        assertTrue(Math.abs(cursor.absoluteTimestampNanos() - epoch) < 100_000_000L,
                "epoch " + cursor.absoluteTimestampNanos() + " expected " + epoch);
        assertTrue(cursor.placeMarker());
        assertEquals(cursor.relativeTimestampNanos(), graph.cursorAForTest());
        String inspector = graph.inspectorTextForTest();
        assertTrue(inspector.contains(id + ".dat"), inspector);
        assertTrue(inspector.contains(MapTimeFormat.withMillis(cursor.relativeTimestampNanos())), inspector);
        assertTrue(inspector.contains(MapTimeFormat.withMillis(cursor.absoluteTimestampNanos())), inspector);
    }

    private void assertFromMap(String id, long stored, long display, long epoch) throws Exception {
        MeasurementCursor cursor = service.analysisSelection().measurementCursor();
        assertEquals(id, cursor.measurementId());
        assertEquals(stored, cursor.relativeTimestampNanos());
        assertNull(cursor.absoluteTimestampNanos());
        assertTrue(cursor.placeMarker());
        assertEquals(stored, graph.cursorAForTest());
        assertEquals(display, displayCursor());
        assertEquals(epoch, field(graph, "cursorAAbsolute"));
        String inspector = graph.inspectorTextForTest();
        assertTrue(inspector.contains(id + ".dat"), inspector);
        assertTrue(inspector.contains(MapTimeFormat.withMillis(epoch)), inspector);
    }

    private void assertGap(MapRoutePane map) throws Exception {
        assertTrue(service.analysisSelection().explicitNoOwner());
        assertFalse(service.analysisSelection().measurementCursor().placeMarker());
        assertEquals("", service.analysisSelection().measurementCursor().measurementId());
        String inspector = graph.inspectorTextForTest();
        assertTrue(inspector.contains(MeasurementCursor.NO_MEASUREMENT_AT_TIME), inspector);
        assertEquals(-1, invoke(map, "selectedIndexForTest"));
        assertNull(invoke(map, "selectedSegmentForTest"));
        assertTrue(map.selectedGpsSummary().isEmpty());
        assertTrue(invoke(map, "statusTextForTest").toString().contains(MeasurementCursor.NO_MEASUREMENT_AT_TIME));
    }

    private void assertGps(MapRoutePane map, String id, double latitude, double longitude) throws Exception {
        RouteSegment selected = (RouteSegment) invoke(map, "selectedSegmentForTest");
        assertEquals(id, selected.measurementId());
        int index = (int) invoke(map, "selectedIndexForTest");
        assertTrue(index >= 0);
        MapRouteModel model = selected.route().model();
        assertEquals(latitude, model.latitude(index), 1e-6);
        assertEquals(longitude, model.longitude(index), 1e-6);
        assertTrue(map.selectedGpsSummary().isPresent());
    }

    private void assertGpsCleared(MapRoutePane map) throws Exception {
        assertEquals(-1, invoke(map, "selectedIndexForTest"));
        assertTrue(map.selectedGpsSummary().isEmpty());
        String status = invoke(map, "statusTextForTest").toString();
        assertTrue(status.contains("No GPS samples"), status);
        assertFalse(status.contains("utc-a"), status);
    }

    private String measurementId() {
        return service.analysisSelection().measurementCursor().measurementId();
    }

    private long displayCursor() throws Exception {
        return (long) field(graph, "cursorADisplay");
    }

    private void install(
            AutomotiveSignalCanvas canvas,
            MapRoutePane map,
            SharedDisplayTimeline timeline,
            ProjectRoute project,
            GraphLogicalTrack track,
            long viewStart,
            long viewEnd
    ) throws Exception {
        fieldSet(graph, "lastViewportQueryWidth", 960);
        canvas.resize(960, 520);
        canvas.layout();
        canvas.setSharedViewport(viewStart, viewEnd);
        canvas.setLogicalTracks(List.of(track));
        fieldSet(graph, "lastTracks", List.of(track));
        fieldSet(graph, "sharedDisplayDomain", true);
        fieldSet(graph, "sharedOriginUtc", timeline.originUtcNanos());
        fieldSet(graph, "sharedDisplayStart", timeline.displayStart());
        fieldSet(graph, "sharedDisplayEnd", timeline.displayEnd());
        present(map, project);
    }

    private void present(MapRoutePane map, ProjectRoute project) throws Exception {
        Method method = MapRoutePane.class.getDeclaredMethod(
                "presentRoutesForTest", MapRouteRequest.class, ProjectRoute.class, MapRoute.class);
        method.setAccessible(true);
        method.invoke(map, new MapRouteRequest(emptyQuery(), List.of(), "1001", "", "All Measurements", 0, 0, 0, 60 * S, true),
                project, MapRoute.emptyRoute());
    }

    private static void pick(MapRoutePane map, RouteSegment segment, int index) throws Exception {
        Method method = MapRoutePane.class.getDeclaredMethod("onSegmentPicked", RouteSegment.class, int.class);
        method.setAccessible(true);
        method.invoke(map, segment, index);
    }

    private static RouteSegment segment(MapRoutePane map, String id) throws Exception {
        ProjectRoute project = (ProjectRoute) invoke(map, "projectRouteForTest");
        return project.segments().stream()
                .filter(segment -> id.equals(segment.measurementId()))
                .findFirst()
                .orElseThrow();
    }

    private static ProjectRoute route(MapRoutePane map) throws Exception {
        return (ProjectRoute) invoke(map, "projectRouteForTest");
    }

    private static int indexAt(RouteSegment segment, long stored) {
        MapRouteModel model = segment.route().model();
        for (int i = 0; i < model.size(); i++) {
            if (model.timestampNanos(i) == stored) {
                return i;
            }
        }
        throw new AssertionError("no GPS sample at " + stored);
    }

    private static void click(AutomotiveSignalCanvas canvas, double x, double y) {
        javafx.geometry.Point2D scene = canvas.localToScene(x, y);
        MouseEvent press = new MouseEvent(
                canvas, canvas, MouseEvent.MOUSE_PRESSED,
                scene.getX(), scene.getY(), scene.getX(), scene.getY(), MouseButton.PRIMARY, 1,
                false, false, false, false, true, false, false, true, false, false, null);
        MouseEvent release = new MouseEvent(
                canvas, canvas, MouseEvent.MOUSE_RELEASED,
                scene.getX(), scene.getY(), scene.getX(), scene.getY(), MouseButton.PRIMARY, 1,
                false, false, false, false, false, false, false, true, false, false, null);
        canvas.fireEvent(press);
        canvas.fireEvent(release);
    }

    private static void move(AutomotiveSignalCanvas canvas, double x, double y) {
        javafx.geometry.Point2D scene = canvas.localToScene(x, y);
        canvas.fireEvent(new MouseEvent(
                canvas, canvas, MouseEvent.MOUSE_MOVED,
                scene.getX(), scene.getY(), scene.getX(), scene.getY(), MouseButton.NONE, 0,
                false, false, false, false, false, false, false, true, false, false, null));
    }

    private static double yWithChoices(AutomotiveSignalCanvas canvas, double x, int count) {
        double height = Math.max(canvas.getHeight(), 520);
        for (double y = 0; y <= height; y += 1) {
            if (canvas.choicesForTest(x, y).size() >= count) {
                return y;
            }
        }
        throw new AssertionError("no pointer with " + count + " measurements at x=" + x);
    }

    private static double xAt(AutomotiveSignalCanvas canvas, long display) {
        double width = Math.max(canvas.getWidth(), 960);
        double chosen = LEFT;
        long best = Long.MAX_VALUE;
        for (double x = 0; x <= width; x += 1) {
            var hit = canvas.hitForTest(x, 160);
            if (hit == null || hit.absoluteTimestampNanos() == null) {
                continue;
            }
            long delta = Math.abs(hit.absoluteTimestampNanos() - display);
            if (delta < best) {
                best = delta;
                chosen = x;
            }
        }
        assertTrue(best < S, "no rendered hit near " + display + " bestDelta=" + best);
        return chosen;
    }

    private static double stackedY(AutomotiveSignalCanvas canvas, List<Recording> recordings, double value) {
        AutomotiveSignalCanvas.StackedTrackFrame frame = canvas.stackedFramesForTest().getFirst();
        List<GraphBucket> buckets = new ArrayList<>();
        for (Recording recording : recordings) {
            for (CanonicalMeasurementSample sample : recording.speed()) {
                long ts = sample.timestampNanos();
                buckets.add(new GraphBucket(ts, sample.physicalValue(), ts, sample.physicalValue(),
                        ts, sample.physicalValue(), ts, sample.physicalValue(), 1));
            }
        }
        GraphAxisScale.Domain domain = GraphAxisScale.fromBuckets(buckets);
        double span = Math.max(1e-9, frame.plotBottom() - frame.plotTop());
        double fraction = (value - domain.axisMin()) / (domain.axisMax() - domain.axisMin());
        return frame.plotBottom() - fraction * span;
    }

    private static double yOwned(AutomotiveSignalCanvas canvas, double x, String id) {
        double height = Math.max(canvas.getHeight(), 520);
        double shared = Double.NaN;
        for (double y = 0; y <= height; y += 1) {
            var choices = canvas.choicesForTest(x, y);
            boolean present = choices.stream().anyMatch(choice -> id.equals(choice.hit().measurementId()));
            if (!present) {
                continue;
            }
            if (choices.size() == 1) {
                return y;
            }
            if (Double.isNaN(shared)) {
                shared = y;
            }
        }
        if (!Double.isNaN(shared)) {
            return shared;
        }
        throw new AssertionError("rendered hit testing has no " + id + " at x=" + x);
    }

    private static double yFor(AutomotiveSignalCanvas canvas, Recording recording, long stored) {
        double top = 68;
        double bottom = Math.max(top + 1, canvas.getHeight() > 1 ? canvas.getHeight() - BOTTOM : 520 - BOTTOM);
        double min = recording.speed().stream().mapToDouble(CanonicalMeasurementSample::physicalValue).min().orElseThrow();
        double max = recording.speed().stream().mapToDouble(CanonicalMeasurementSample::physicalValue).max().orElseThrow();
        double value = recording.speed().stream()
                .filter(sample -> sample.timestampNanos() == stored)
                .findFirst()
                .orElseThrow()
                .physicalValue();
        double span = Math.max(1e-12, max - min);
        return bottom - ((value - min) / span) * (bottom - top);
    }

    private GraphLogicalTrack track(SharedDisplayTimeline timeline, List<Recording> order, long viewStart, long viewEnd) {
        SignalCatalogKey logical = key("VehicleSpeed", "PROJECT");
        List<GraphTrackSegment> segments = new ArrayList<>();
        for (Recording recording : order) {
            SharedDisplayTimeline.Member member = timeline.member(recording.id());
            long[] window = timeline.storedQuery(member, viewStart, viewEnd);
            List<GraphBucket> buckets = new ArrayList<>();
            if (window != null) {
                for (CanonicalMeasurementSample sample : recording.speed()) {
                    long ts = sample.timestampNanos();
                    if (ts < window[0] || ts > window[1]) {
                        continue;
                    }
                    buckets.add(new GraphBucket(ts, sample.physicalValue(), ts, sample.physicalValue(),
                            ts, sample.physicalValue(), ts, sample.physicalValue(), 1));
                }
            }
            long seriesStart = buckets.isEmpty() ? member.storedStartNanos() : buckets.getFirst().firstTimestampNanos();
            long seriesEnd = buckets.isEmpty() ? member.storedEndNanos() : buckets.getLast().lastTimestampNanos();
            GraphRenderSeries series = new GraphRenderSeries(
                    key("VehicleSpeed", recording.id()), "km/h", recording.speed().size(), true,
                    seriesStart, seriesEnd, buckets);
            segments.add(new GraphTrackSegment(series, recording.id() + ".dat", timeline.displayOffsetNanos(member)));
        }
        return new GraphLogicalTrack(logical, segments, true);
    }

    private static SignalCatalogKey key(String name, String file) {
        return new SignalCatalogKey(name, SignalSourceType.MF4_NATIVE, "DIRECT_MEASUREMENT", 0L, file, "");
    }

    private static SharedDisplayTimeline.Member member(Recording recording) {
        long first = recording.speed().getFirst().timestampNanos();
        long last = recording.speed().getLast().timestampNanos();
        return new SharedDisplayTimeline.Member(recording.id(), recording.clock().utcStartNanos(), first, last);
    }

    private static ProjectRoute routeOfDecoded(List<Recording> recordings) {
        List<ProjectRouteAssembler.PreparedMeasurement> rows = new ArrayList<>();
        for (Recording recording : recordings) {
            rows.add(new ProjectRouteAssembler.PreparedMeasurement(
                    recording.id(), recording.id() + ".dat", gpsRoute(recording)));
        }
        return ProjectRouteAssembler.assemble(rows, recordings.size());
    }

    private static MapRoute gpsRoute(Recording recording) {
        if (recording.latitude().isEmpty()) {
            return MapRoute.emptyRoute();
        }
        return new MapRoute(MapRouteBuilder.build(points(recording.latitude()), points(recording.longitude()), null));
    }

    private static List<SignalSamplePoint> points(List<CanonicalMeasurementSample> samples) {
        List<SignalSamplePoint> points = new ArrayList<>();
        for (CanonicalMeasurementSample sample : samples) {
            points.add(new SignalSamplePoint(sample.timestampNanos(), sample.physicalValue(), null, ""));
        }
        return points;
    }

    private BorderPane loadGraph() throws Exception {
        FXMLLoader loader = new FXMLLoader(GraphController.class.getResource("/fxml/graph-view.fxml"));
        BorderPane root = loader.load();
        root.getProperties().put("controller", loader.getController());
        return root;
    }

    private static AutomotiveSignalCanvas findCanvas(Node node) {
        if (node instanceof AutomotiveSignalCanvas canvas) {
            return canvas;
        }
        if (node instanceof SplitPane split) {
            for (Node child : split.getItems()) {
                AutomotiveSignalCanvas found = findCanvas(child);
                if (found != null) {
                    return found;
                }
            }
        }
        if (node instanceof TabPane tabs) {
            for (javafx.scene.control.Tab tab : tabs.getTabs()) {
                if (tab.getContent() != null) {
                    AutomotiveSignalCanvas found = findCanvas(tab.getContent());
                    if (found != null) {
                        return found;
                    }
                }
            }
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                AutomotiveSignalCanvas found = findCanvas(child);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private Recording write(String id, long utcStart, int firstSpeed, double lat0, double lon0) throws Exception {
        boolean gps = Double.isFinite(lat0);
        Path file = temp.resolve(id + ".mf4");
        try (SyntheticMdf4Builder builder = SyntheticMdf4Builder.create(file)) {
            builder.writeIdBlock("4.10", 410, 0);
            long hd = builder.writeHd(0, 0, 0);
            builder.patchLong(hd + 24 + 6L * 8L, utcStart);
            long unitS = builder.writeTx("s");
            long timeCc = builder.writeCcLinear(0.0, 1.0e-9, unitS);
            long unit = builder.writeTx("km/h");
            long valueCc = builder.writeCcLinear(0.0, 1.0, unit);
            long deg = builder.writeTx("deg");
            long identity = builder.writeCcLinear(0.0, 1.0, deg);
            long next = 0L;
            int bytes = 16;
            if (gps) {
                next = builder.writeCn(0L, 0L, builder.writeTx("GpsLongitude"), deg, 0L, identity, 0, 0, 4, 24, 0, 64, 0, 0);
                next = builder.writeCn(next, 0L, builder.writeTx("GpsLatitude"), deg, 0L, identity, 0, 0, 4, 16, 0, 64, 0, 0);
                bytes = 32;
            }
            long speed = builder.writeCn(next, 0L, builder.writeTx("VehicleSpeed"), unit, 0L, valueCc, 0, 0, 4, 8, 0, 64, 0, 0);
            long time = builder.writeCn(speed, 0L, builder.writeTx("time"), unitS, 0L, timeCc, 2, 1, 4, 0, 0, 64, 0, 0);
            long group = builder.writeCg(0, time, 1, 5, 0, bytes, 0);
            ByteBuffer records = ByteBuffer.allocate(bytes * 5).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < 5; i++) {
                records.putDouble(i * 5.0e9);
                records.putDouble(firstSpeed + i);
                if (gps) {
                    records.putDouble(lat0 + i * 0.0001);
                    records.putDouble(lon0 + i * 0.0001);
                }
            }
            long data = builder.writeDt(records.array());
            long dg = builder.writeDg(0, group, data, 0);
            builder.patchLink(hd, 0, dg);
        }
        List<CanonicalMeasurementSample> signals = new MdfDynamicRecordDecoder()
                .decodeAll(file, ReaderContext.ofBatchSize(16)).nativeSignals();
        RecordingClock clock = RecordingClockReader.read(file, id);
        return new Recording(id, clock, named(signals, "VehicleSpeed"), named(signals, "GpsLatitude"), named(signals, "GpsLongitude"));
    }

    private static List<CanonicalMeasurementSample> named(List<CanonicalMeasurementSample> signals, String name) {
        return signals.stream().filter(sample -> sample.signalName().equals(name)).toList();
    }

    private static GraphSeriesQuery emptyQuery() {
        return new GraphSeriesQuery() {
            @Override
            public List<com.stellar.platform.domain.catalog.SignalSamplePoint> queryRange(SignalCatalogKey key, long startNanos, long endNanos) {
                return List.of();
            }

            @Override
            public String unit(SignalCatalogKey key) {
                return "";
            }

            @Override
            public long sampleCount(SignalCatalogKey key) {
                return 0;
            }
        };
    }

    private static Object invoke(Object target, String name) throws Exception {
        Method method = target.getClass().getDeclaredMethod(name);
        method.setAccessible(true);
        return method.invoke(target);
    }

    private static Object field(Object target, String name) throws Exception {
        Field found = target.getClass().getDeclaredField(name);
        found.setAccessible(true);
        return found.get(target);
    }

    private static void fieldSet(Object target, String name, Object value) throws Exception {
        Field found = target.getClass().getDeclaredField(name);
        found.setAccessible(true);
        found.set(target, value);
    }

    private record Recording(
            String id,
            RecordingClock clock,
            List<CanonicalMeasurementSample> speed,
            List<CanonicalMeasurementSample> latitude,
            List<CanonicalMeasurementSample> longitude
    ) {
    }
}
