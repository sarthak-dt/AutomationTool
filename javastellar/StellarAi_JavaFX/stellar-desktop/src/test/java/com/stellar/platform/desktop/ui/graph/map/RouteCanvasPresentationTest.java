package com.stellar.platform.desktop.ui.graph.map;

import com.stellar.platform.domain.catalog.SignalSamplePoint;
import com.stellar.platform.processing.map.MapRoute;
import com.stellar.platform.processing.map.MapRouteBuilder;
import com.stellar.platform.processing.map.MapRouteModel;
import com.stellar.platform.processing.map.RouteSegment;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * All Measurements draws independent polylines and one START plus one END for that display order.
 * One selected recording keeps its own START and END.
 */
class RouteCanvasPresentationTest {

    private static Scene scene;

    @BeforeAll
    static void startFx() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        try {
            Platform.startup(started::countDown);
        } catch (IllegalStateException alreadyStarted) {
            started.countDown();
        }
        assertTrue(started.await(10, TimeUnit.SECONDS));
    }

    @Test
    void allMeasurementsShowsOneCombinedStartAndEnd() throws Exception {
        MapRoute north = route(18.5, 73.8, 19.1, 74.2);
        MapRoute south = route(12.9, 77.5, 13.1, 77.7);
        RouteCanvas canvas = canvas();
        runOnFx(() -> {
            canvas.setSegments(List.of(
                    new RouteSegment("north", "North", north),
                    new RouteSegment("south", "South", south)));
            layout(canvas);
            canvas.fitRoute();
            canvas.paintForTest();
        });

        assertEquals(RouteCanvas.EndpointPolicy.GLOBAL, canvas.endpointPolicyForTest());
        List<RouteCanvas.EndpointMark> marks = canvas.endpointMarksForTest();
        assertEquals(2, marks.size());
        assertEquals(2, canvas.paintedMarkersForTest().size());
        assertMark(marks.get(0), 0, "START", north.model(), 0);
        assertMark(marks.get(1), 1, "END", south.model(), south.model().size() - 1);
        assertEquals(2, canvas.overlayCountForTest());
        double northY = canvas.viewport().y(north.model().latitude(0));
        double southY = canvas.viewport().y(south.model().latitude(0));
        assertTrue(northY >= 0 && northY <= canvas.viewport().height(), "north route left the frame y=" + northY);
        assertTrue(southY >= 0 && southY <= canvas.viewport().height(), "south route left the frame y=" + southY);
    }

    @Test
    void allMeasurementsKeepsStartAndEndLabels() throws Exception {
        RouteSegment first = segment("a", "Alpha", 18.5, 73.8, 18.6, 73.9);
        RouteSegment second = segment("b", "Beta", 12.9, 77.5, 13.0, 77.6);
        RouteCanvas canvas = show(List.of(first, second));
        assertEquals("START", canvas.endpointMarksForTest().get(0).role());
        assertEquals("END", canvas.endpointMarksForTest().get(1).role());
        assertEquals(first.route().model().latitude(0), canvas.endpointMarksForTest().get(0).latitude(), 1e-9);
        assertEquals(second.route().model().latitude(second.route().model().size() - 1),
                canvas.endpointMarksForTest().get(1).latitude(), 1e-9);
    }

    @Test
    void tooltipStaysInsideTheMapAndOnlyTracksTheRoute() throws Exception {
        double[] corner = RouteCanvas.placeTooltip(620, 8, 180, 72, 640, 480);
        assertTrue(corner[0] >= 6 && corner[0] + 180 <= 634);
        assertTrue(corner[1] >= 6 && corner[1] + 72 <= 474);

        MapRoute route = route(18.52, 73.85, 18.62, 73.95);
        RouteCanvas canvas = canvas();
        AtomicReference<Double> x = new AtomicReference<>();
        AtomicReference<Double> y = new AtomicReference<>();
        runOnFx(() -> {
            canvas.setRoute(route);
            layout(canvas);
            canvas.fitRoute();
            canvas.paintForTest();
            x.set(canvas.viewport().x(route.model().longitude(0)));
            y.set(canvas.viewport().y(route.model().latitude(0)));
            assertTrue(canvas.hoverNearRouteForTest(x.get(), y.get()));
            assertFalse(canvas.hoverNearRouteForTest(8, 8));
            canvas.showTooltip("12:00:00.000\nLatitude   18.5200000\nLongitude  73.8500000", 0);
        });
        assertTrue(canvas.tooltipVisibleForTest());
        runOnFx(() -> {
            Scene scene = new Scene(new StackPane(canvas), 640, 480);
            scene.getStylesheets().add(RouteCanvas.class.getResource("/css/stellar-desktop.css").toExternalForm());
            canvas.applyCss();
            canvas.tooltipForTest().applyCss();
            Color fill = (Color) canvas.tooltipForTest().getBackground().getFills().getFirst().getFill();
            assertTrue(fill.getBrightness() > 0.9, "tooltip background should be light, was " + fill);
            assertFalse(canvas.tooltipForTest().getTextFill().equals(Color.WHITE));
        });
    }

    @Test
    void oneFileKeepsOneRouteWithItsOwnStartAndEnd() throws Exception {
        MapRoute drive = route(18.52, 73.85, 18.62, 73.95);
        RouteCanvas canvas = canvas();
        runOnFx(() -> {
            canvas.setRoute(drive);
            layout(canvas);
            canvas.fitRoute();
            canvas.paintForTest();
        });
        assertEquals(5, drive.model().size());
        assertEquals(2, canvas.endpointMarksForTest().size());
        assertEquals(2, canvas.paintedMarkersForTest().size());
        assertMark(canvas.endpointMarksForTest().get(0), -1, "START", drive.model(), 0);
        assertMark(canvas.endpointMarksForTest().get(1), -1, "END", drive.model(), 4);
        double startX = canvas.paintedMarkersForTest().getFirst().x();
        double startLon = canvas.endpointMarksForTest().getFirst().longitude();
        runOnFx(() -> {
            canvas.viewport().panByPixels(48, -24);
            canvas.paintForTest();
        });
        assertEquals(startLon, canvas.endpointMarksForTest().getFirst().longitude(), 1e-12);
        assertTrue(Math.abs(canvas.paintedMarkersForTest().getFirst().x() - startX) > 1,
                "pan should move the start marker on screen");
        runOnFx(() -> {
            canvas.fitRoute();
            canvas.paintForTest();
        });
        double fittedX = canvas.viewport().x(canvas.endpointMarksForTest().getFirst().longitude());
        double fittedY = canvas.viewport().y(canvas.endpointMarksForTest().getFirst().latitude());
        assertEquals(fittedX, canvas.paintedMarkersForTest().getFirst().x(), 0.5);
        assertEquals(fittedY, canvas.paintedMarkersForTest().getFirst().y(), 0.5);
    }

    @Test
    void twoIndependentRoutesStaySeparate() throws Exception {
        RouteSegment north = segment("n", "North file", 18.5, 73.8, 19.4, 74.6);
        RouteSegment south = segment("s", "South file", 12.2, 77.2, 13.4, 78.4);
        RouteCanvas canvas = show(List.of(north, south));
        assertEquals(5, north.route().model().size());
        assertEquals(5, south.route().model().size());
        assertEquals(RouteCanvas.EndpointPolicy.GLOBAL, canvas.endpointPolicyForTest());
        assertEquals(2, canvas.overlayCountForTest());
        assertEquals(2, canvas.endpointMarksForTest().size());
        assertEquals(2, canvas.paintedMarkersForTest().size());
        assertEquals("START", canvas.endpointMarksForTest().get(0).role());
        assertEquals("END", canvas.endpointMarksForTest().get(1).role());
        assertEquals(north.route().model().latitude(0), canvas.endpointMarksForTest().get(0).latitude(), 1e-9);
        assertEquals(south.route().model().latitude(south.route().model().size() - 1),
                canvas.endpointMarksForTest().get(1).latitude(), 1e-9);
    }

    @Test
    void twoFilesSharingOneEndpointPaintOneJunction() throws Exception {
        RouteSegment first = segment("f1", "File One", 13.30, 77.60, 13.35, 77.64);
        RouteSegment second = segment("f2", "File Two", 13.35, 77.64, 13.20, 77.60);
        RouteCanvas canvas = show(List.of(first, second));
        assertEquals(RouteCanvas.EndpointPolicy.GLOBAL, canvas.endpointPolicyForTest());
        assertEquals(2, canvas.endpointMarksForTest().size());
        assertMark(canvas.endpointMarksForTest().get(0), 0, "START", first.route().model(), 0);
        assertMark(canvas.endpointMarksForTest().get(1), 1, "END", second.route().model(),
                second.route().model().size() - 1);
        assertTrue(canvas.endpointMarksForTest().stream().noneMatch(mark ->
                mark.latitude() == 13.35 && "END".equals(mark.role()) && "File One".equals(mark.measurementName())));
        String detail = RouteCanvas.endpointDetail(List.of(
                new RouteCanvas.EndpointMark(0, "File One", "END", 13.35, 77.64),
                new RouteCanvas.EndpointMark(1, "File Two", "START", 13.35, 77.64)));
        assertTrue(detail.contains("END  File One"));
        assertTrue(detail.contains("START  File Two"));
        assertFalse(detail.contains("f1"));
        assertFalse(detail.contains("f2"));
    }

    @Test
    void threeFilesSharingOneEndpointListEveryRole() throws Exception {
        RouteSegment first = segment("id-1", "File 1", 13.00, 77.00, 13.35, 77.64);
        RouteSegment second = segment("id-2", "File 2", 13.35, 77.64, 13.50, 77.80);
        RouteSegment third = segment("id-3", "File 3", 13.35, 77.64, 13.10, 77.40);
        RouteCanvas canvas = show(List.of(first, second, third));
        assertEquals(RouteCanvas.EndpointPolicy.GLOBAL, canvas.endpointPolicyForTest());
        assertEquals(2, canvas.endpointMarksForTest().size());
        assertMark(canvas.endpointMarksForTest().get(0), 0, "START", first.route().model(), 0);
        assertMark(canvas.endpointMarksForTest().get(1), 2, "END", third.route().model(),
                third.route().model().size() - 1);
        assertEquals(3, canvas.overlayCountForTest());
        String detail = RouteCanvas.endpointDetail(List.of(
                new RouteCanvas.EndpointMark(0, "File 1", "END", 13.35, 77.64),
                new RouteCanvas.EndpointMark(1, "File 2", "START", 13.35, 77.64),
                new RouteCanvas.EndpointMark(2, "File 3", "START", 13.35, 77.64)));
        assertTrue(detail.contains("Shared point"));
        assertTrue(detail.contains("END  File 1"));
        assertTrue(detail.contains("START  File 2"));
        assertTrue(detail.contains("START  File 3"));
        assertFalse(detail.contains("id-1"));
        runOnFx(() -> canvas.showTooltip(detail, 0));
        assertTrue(canvas.tooltipVisibleForTest());
        assertTrue(canvas.tooltipForTest().getText().contains("START  File 3"));
    }

    @Test
    void manyFilesWithNoSharedCoordinatesStayIndependent() throws Exception {
        List<RouteSegment> segments = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            double lat = 10 + i * 3.0;
            segments.add(segment("id-" + i, "Drive " + i, lat, 70, lat + 1.4, 71.4));
        }
        RouteCanvas canvas = show(segments);
        assertEquals(8, canvas.overlayCountForTest());
        assertEquals(RouteCanvas.EndpointPolicy.GLOBAL, canvas.endpointPolicyForTest());
        assertEquals(2, canvas.endpointMarksForTest().size());
        assertEquals(2, canvas.paintedMarkersForTest().size());
        assertEquals("START", canvas.endpointMarksForTest().get(0).role());
        assertEquals("END", canvas.endpointMarksForTest().get(1).role());
        assertEquals("Drive 0", canvas.endpointMarksForTest().get(0).measurementName());
        assertEquals("Drive 7", canvas.endpointMarksForTest().get(1).measurementName());
        assertEquals(segments.getFirst().route().model().latitude(0),
                canvas.endpointMarksForTest().get(0).latitude(), 1e-9);
        MapRouteModel last = segments.getLast().route().model();
        assertEquals(last.latitude(last.size() - 1), canvas.endpointMarksForTest().get(1).latitude(), 1e-9);
    }

    @Test
    void screenOverlapDependsOnZoomNotOnAFixedGpsDistance() throws Exception {
        RouteSegment arriving = segment("a", "Arriving", 10.0, 70.0, 11.0, 71.0);
        RouteSegment leaving = segment("b", "Leaving", 11.0, 71.02, 12.0, 72.0);
        RouteCanvas canvas = show(List.of(arriving, leaving));
        assertEquals(RouteCanvas.EndpointPolicy.GLOBAL, canvas.endpointPolicyForTest());
        assertEquals(2, canvas.endpointMarksForTest().size());
        assertEquals(10.0, canvas.endpointMarksForTest().get(0).latitude(), 1e-9);
        assertEquals(12.0, canvas.endpointMarksForTest().get(1).latitude(), 1e-9);
        runOnFx(() -> {
            canvas.setEndpointPolicy(RouteCanvas.EndpointPolicy.EACH_ROUTE);
            canvas.paintForTest();
        });
        double[] fitted = screenGap(canvas, 11.0, 71.0, 11.0, 71.02);
        assertTrue(fitted[0] <= 18, "close route ends should overlap when fitted, gap=" + fitted[0]);
        assertEquals(4, canvas.endpointMarksForTest().size());
        assertTrue(canvas.paintedMarkersForTest().stream().anyMatch(marker -> marker.endpoints().size() > 1),
                "overlapping endpoints keep both identities on one disc");

        runOnFx(() -> {
            canvas.viewport().zoomByLevels(6);
            canvas.paintForTest();
        });
        double[] zoomed = screenGap(canvas, 11.0, 71.0, 11.0, 71.02);
        assertTrue(zoomed[0] > 18, "the same coordinates should separate after zoom, gap=" + zoomed[0]);
        assertEquals(4, canvas.endpointMarksForTest().size());
        assertEquals(11.0, canvas.endpointMarksForTest().get(1).latitude(), 1e-9);
        assertEquals(71.0, canvas.endpointMarksForTest().get(1).longitude(), 1e-9);
        assertTrue(canvas.paintedMarkersForTest().stream().noneMatch(marker -> marker.endpoints().size() > 1));
    }

    @Test
    void playbackOnAnEndpointKeepsTheEndpointDetail() throws Exception {
        MapRoute drive = route(18.52, 73.85, 18.62, 73.95);
        RouteCanvas canvas = canvas();
        runOnFx(() -> {
            canvas.setRoute(drive);
            layout(canvas);
            canvas.fitRoute();
            canvas.setSelectedIndex(0);
            canvas.paintForTest();
            RouteCanvas.PaintedMarker start = canvas.paintedMarkersForTest().stream()
                    .filter(marker -> marker.endpoints().stream().anyMatch(mark -> "START".equals(mark.role())))
                    .findFirst()
                    .orElseThrow();
            canvas.showMarkerAtForTest(start.x(), start.y());
        });
        assertEquals(2, canvas.endpointMarksForTest().size());
        assertEquals(2, canvas.paintedMarkersForTest().size());
        assertTrue(canvas.tooltipForTest().getText().contains("START"));
        assertTrue(canvas.tooltipForTest().getText().contains("Latitude   18.5200000"));
        assertTrue(canvas.tooltipForTest().getText().contains("Longitude  73.8500000"));
        assertTrue(canvas.tooltipForTest().getText().contains("0.000 s"));
        assertFalse(canvas.tooltipForTest().getText().contains("END"));
    }

    @Test
    void singlePointRoutePaintsOneMarkerForBothRoles() throws Exception {
        MapRoute point = route(13.3511145, 77.6463410, 13.3511145, 77.6463410);
        RouteCanvas canvas = canvas();
        runOnFx(() -> {
            canvas.setSegments(List.of(new RouteSegment("only", "Stationary", point)));
            layout(canvas);
            canvas.fitRoute();
            canvas.paintForTest();
        });
        assertEquals(2, canvas.endpointMarksForTest().size());
        assertEquals(1, canvas.paintedMarkersForTest().size());
        String detail = RouteCanvas.endpointDetail(canvas.paintedMarkersForTest().getFirst().endpoints());
        assertTrue(detail.contains("START  Stationary"));
        assertTrue(detail.contains("END  Stationary"));
        assertFalse(detail.contains("Shared point"));
    }

    @Test
    void emptyRoutePaintsNoMarkers() throws Exception {
        RouteCanvas canvas = canvas();
        runOnFx(() -> {
            canvas.setSegments(List.of());
            layout(canvas);
            canvas.paintForTest();
            canvas.setRoute(MapRoute.emptyRoute());
            canvas.paintForTest();
        });
        assertEquals(0, canvas.endpointMarksForTest().size());
        assertEquals(0, canvas.paintedMarkersForTest().size());
        assertFalse(canvas.tooltipVisibleForTest());
    }

    @Test
    void oneStoredGpsSamplePaintsOnePoint() throws Exception {
        List<SignalSamplePoint> latitude = List.of(new SignalSamplePoint(1_000L, 18.927474149068196, null, null));
        List<SignalSamplePoint> longitude = List.of(new SignalSamplePoint(1_000L, 74.48571033477783, null, null));
        MapRoute point = new MapRoute(MapRouteBuilder.build(latitude, longitude, null));
        RouteCanvas canvas = canvas();
        runOnFx(() -> {
            canvas.setSegments(List.of(new RouteSegment("only", "Stationary", point)));
            layout(canvas);
            canvas.fitRoute();
            canvas.paintForTest();
        });
        assertEquals(1, point.model().size());
        assertEquals(1, canvas.endpointMarksForTest().size());
        assertEquals("POINT", canvas.endpointMarksForTest().getFirst().role());
        assertEquals(1, canvas.paintedMarkersForTest().size());
        assertEquals(18.927474149068196, canvas.endpointMarksForTest().getFirst().latitude(), 1e-12);
        assertEquals(74.48571033477783, canvas.endpointMarksForTest().getFirst().longitude(), 1e-12);
        runOnFx(() -> {
            RouteCanvas.PaintedMarker marker = canvas.paintedMarkersForTest().getFirst();
            canvas.showMarkerAtForTest(marker.x(), marker.y());
        });
        String tip = canvas.tooltipForTest().getText();
        assertTrue(tip.contains("POINT  Stationary"));
        assertTrue(tip.contains("Latitude   18.9274741"));
        assertTrue(tip.contains("Longitude  74.4857103"));
        assertFalse(tip.contains("START"));
        assertFalse(tip.contains("END"));
    }

    @Test
    void filteringToOneMeasurementDropsTheOtherMarkers() throws Exception {
        RouteSegment first = segment("f1", "File One", 13.30, 77.60, 13.35, 77.64);
        RouteSegment second = segment("f2", "File Two", 13.35, 77.64, 13.20, 77.60);
        RouteSegment third = segment("f3", "File Three", 12.00, 76.00, 12.20, 76.20);
        RouteCanvas canvas = show(List.of(first, second, third));
        assertEquals(2, canvas.endpointMarksForTest().size());
        runOnFx(() -> {
            canvas.setRoute(second.route());
            canvas.fitRoute();
            canvas.paintForTest();
        });
        assertEquals(2, canvas.endpointMarksForTest().size());
        assertEquals(-1, canvas.endpointMarksForTest().getFirst().segmentOrdinal());
        assertEquals(5, second.route().model().size());
        assertEquals(second.route().model().latitude(0), canvas.endpointMarksForTest().get(0).latitude(), 1e-9);
    }

    @Test
    void reloadingAProjectDoesNotKeepStaleMarkers() throws Exception {
        RouteCanvas canvas = show(List.of(
                segment("a", "Alpha", 10, 70, 11, 71),
                segment("b", "Beta", 11, 71, 12, 72),
                segment("c", "Gamma", 11, 71, 10, 69)));
        assertEquals(2, canvas.endpointMarksForTest().size());
        assertEquals(RouteCanvas.EndpointPolicy.GLOBAL, canvas.endpointPolicyForTest());
        runOnFx(() -> {
            canvas.setSegments(List.of(segment("b", "Beta", 11, 71, 12, 72)));
            canvas.fitRoute();
            canvas.paintForTest();
            canvas.setSegments(List.of(segment("b", "Beta", 11, 71, 12, 72)));
            canvas.fitRoute();
            canvas.paintForTest();
        });
        assertEquals(2, canvas.endpointMarksForTest().size());
        assertEquals(2, canvas.paintedMarkersForTest().size());
        assertTrue(canvas.endpointMarksForTest().stream().allMatch(mark -> "Beta".equals(mark.measurementName())));
    }

    @Test
    void longMeasurementNamesAreShortenedInTheDetail() {
        String name = "Xenon new script C910P41 2020-02-07 14-01-18-400240 Partition 0.dat";
        String compact = RouteCanvas.compactName(name);
        assertTrue(compact.endsWith("..."));
        assertTrue(compact.length() <= 42);
        assertFalse(compact.contains("00000000-0000-0000-0000-000000000000"));
    }

    private static void assertMark(RouteCanvas.EndpointMark mark, int segment, String role,
                                    MapRouteModel model, int index) {
        assertEquals(segment, mark.segmentOrdinal());
        assertEquals(role, mark.role());
        assertEquals(model.latitude(index), mark.latitude(), 1e-9);
        assertEquals(model.longitude(index), mark.longitude(), 1e-9);
    }

    private static RouteCanvas canvas() throws Exception {
        AtomicReference<RouteCanvas> ref = new AtomicReference<>();
        runOnFx(() -> ref.set(new RouteCanvas()));
        return ref.get();
    }

    private static void layout(RouteCanvas canvas) {
        StackPane root = new StackPane(canvas);
        root.setPrefSize(640, 480);
        scene = new Scene(root, 640, 480);
        scene.getStylesheets().add(RouteCanvas.class.getResource("/css/stellar-desktop.css").toExternalForm());
        root.applyCss();
        root.layout();
    }

    private static RouteCanvas show(List<RouteSegment> segments) throws Exception {
        RouteCanvas canvas = canvas();
        runOnFx(() -> {
            canvas.setSegments(segments);
            layout(canvas);
            canvas.fitRoute();
            canvas.paintForTest();
        });
        return canvas;
    }

    private static double[] screenGap(RouteCanvas canvas, double lat1, double lon1, double lat2, double lon2) {
        double x1 = canvas.viewport().x(lon1);
        double y1 = canvas.viewport().y(lat1);
        double x2 = canvas.viewport().x(lon2);
        double y2 = canvas.viewport().y(lat2);
        return new double[]{Math.hypot(x1 - x2, y1 - y2)};
    }

    private static RouteSegment segment(String id, String name, double startLat, double startLon,
                                        double endLat, double endLon) {
        return new RouteSegment(id, name, route(startLat, startLon, endLat, endLon));
    }

    private static MapRoute route(double startLat, double startLon, double endLat, double endLon) {
        List<SignalSamplePoint> latitude = new ArrayList<>();
        List<SignalSamplePoint> longitude = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            long timestamp = i * 1_000_000_000L;
            double t = i / 4.0;
            latitude.add(new SignalSamplePoint(timestamp, startLat + (endLat - startLat) * t, null, null));
            longitude.add(new SignalSamplePoint(timestamp, startLon + (endLon - startLon) * t, null, null));
        }
        return new MapRoute(MapRouteBuilder.build(latitude, longitude, null));
    }

    private static void runOnFx(Runnable action) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                action.run();
            } catch (Throwable thrown) {
                error.set(thrown);
            } finally {
                // Let a coalesced requestRedraw finish before the test reads marker state.
                Platform.runLater(done::countDown);
            }
        });
        assertTrue(done.await(20, TimeUnit.SECONDS));
        if (error.get() != null) {
            throw new AssertionError(error.get());
        }
    }
}
