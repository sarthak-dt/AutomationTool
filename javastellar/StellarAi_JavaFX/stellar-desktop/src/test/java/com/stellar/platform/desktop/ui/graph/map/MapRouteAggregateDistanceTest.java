package com.stellar.platform.desktop.ui.graph.map;

import com.stellar.platform.domain.catalog.SignalSamplePoint;
import com.stellar.platform.processing.graph.GraphSeriesQuery;
import com.stellar.platform.processing.map.GeoDistance;
import com.stellar.platform.processing.map.MapRoute;
import com.stellar.platform.processing.map.MapRouteBuilder;
import com.stellar.platform.processing.map.MapRouteModel;
import com.stellar.platform.processing.map.ProjectRoute;
import com.stellar.platform.processing.map.ProjectRouteAssembler;
import javafx.application.Platform;
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
 * Segment Analysis distance for All Measurements is the existing independent-segment total.
 */
class MapRouteAggregateDistanceTest {

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
    void oneMeasurementKeepsItsOwnDistance() throws Exception {
        MapRoute only = routeNear(12.97, 77.59, 98);
        String shown = String.format("%.1f km", only.model().totalDistanceMeters() / 1000.0);
        assertEquals("19.6 km", shown, "fixture must stay on the existing one-decimal policy");

        MapRoutePane pane = newPane();
        runOnFx(() -> pane.presentRoutesForTest(singleRequest(), ProjectRoute.empty(), only));

        assertEquals("Full Drive", pane.segmentBadgeForTest());
        assertTrue(pane.segmentBodyTextForTest().contains("Distance"), pane.segmentBodyTextForTest());
        assertTrue(pane.segmentBodyTextForTest().contains("19.6 km"), pane.segmentBodyTextForTest());
        assertFalse(pane.segmentBodyTextForTest().contains("19.60 km"), pane.segmentBodyTextForTest());
        runOnFx(pane::dispose);
    }

    @Test
    void allMeasurementsShowsTheIndependentSumAndOmitsTheGap() throws Exception {
        MapRoute fileA = routeNear(12.9716, 77.5946, 40);
        MapRoute fileB = routeNear(19.0760, 72.8777, 20);
        double gap = GeoDistance.haversineMeters(
                fileA.model().latitude(fileA.model().size() - 1),
                fileA.model().longitude(fileA.model().size() - 1),
                fileB.model().latitude(0),
                fileB.model().longitude(0));
        assertTrue(gap > 100_000);

        ProjectRoute project = ProjectRouteAssembler.assemble(List.of(
                new ProjectRouteAssembler.PreparedMeasurement("src-A", "A-far.dat", fileA),
                new ProjectRouteAssembler.PreparedMeasurement("src-B", "B-far.dat", fileB)
        ), 2);
        assertEquals(
                fileA.model().totalDistanceMeters() + fileB.model().totalDistanceMeters(),
                project.totalDistanceMeters(),
                1e-6);
        assertTrue(project.totalDistanceMeters() + 1
                < fileA.model().totalDistanceMeters() + fileB.model().totalDistanceMeters() + gap);

        String aggregate = MapRoutePane.formatAggregateKilometers(project.totalDistanceMeters());
        MapRoutePane pane = newPane();
        runOnFx(() -> pane.presentRoutesForTest(projectRequest(2), project, null));

        assertEquals("Full Drive", pane.segmentBadgeForTest());
        assertEquals("Independent segments", pane.segmentHeadlineForTest());
        assertFalse(pane.segmentHeadlineForTest().contains("Duration"));
        String body = pane.segmentBodyTextForTest();
        assertTrue(body.contains("Distance"), body);
        assertTrue(body.contains(aggregate), body);
        assertFalse(body.contains("A-far.dat"), body);
        assertFalse(body.contains("B-far.dat"), body);
        assertTrue(pane.mappingAltitudeForTest().contains(aggregate), pane.mappingAltitudeForTest());
        runOnFx(pane::dispose);
    }

    @Test
    void seventyThreeSegmentsUseTheSameDistanceAsTheGpsSummary() throws Exception {
        List<ProjectRouteAssembler.PreparedMeasurement> prepared = new ArrayList<>();
        for (int i = 0; i < 73; i++) {
            prepared.add(new ProjectRouteAssembler.PreparedMeasurement(
                    "src-" + i,
                    "measurement-" + i + ".dat",
                    routeNear(10.0 + i * 0.05, 76.0, 3)));
        }
        ProjectRoute project = ProjectRouteAssembler.assemble(prepared, 73);
        assertEquals(73, project.gpsCapableCount());
        String aggregate = MapRoutePane.formatAggregateKilometers(project.totalDistanceMeters());

        MapRoutePane pane = newPane();
        runOnFx(() -> pane.presentRoutesForTest(projectRequest(73), project, null));

        assertEquals("Full Drive", pane.segmentBadgeForTest());
        assertTrue(pane.segmentBodyTextForTest().contains(aggregate), pane.segmentBodyTextForTest());
        assertTrue(pane.mappingAltitudeForTest().contains(aggregate), pane.mappingAltitudeForTest());
        assertFalse(pane.segmentBodyTextForTest().contains(".dat"), pane.segmentBodyTextForTest());
        runOnFx(pane::dispose);
    }

    @Test
    void measurementWithoutGpsIsLeftOutOfTheDisplayedDistance() throws Exception {
        MapRoute gps = routeNear(12.97, 77.59, 8);
        ProjectRoute project = ProjectRouteAssembler.assemble(List.of(
                new ProjectRouteAssembler.PreparedMeasurement("src-A", "with-gps.dat", gps),
                new ProjectRouteAssembler.PreparedMeasurement("src-B", "no-gps.dat", MapRoute.emptyRoute())
        ), 2);
        assertEquals(1, project.gpsCapableCount());
        assertEquals(gps.model().totalDistanceMeters(), project.totalDistanceMeters(), 1e-6);
        String aggregate = MapRoutePane.formatAggregateKilometers(project.totalDistanceMeters());

        MapRoutePane pane = newPane();
        runOnFx(() -> pane.presentRoutesForTest(projectRequest(2), project, null));
        assertTrue(pane.segmentBodyTextForTest().contains(aggregate), pane.segmentBodyTextForTest());
        assertFalse(pane.segmentBodyTextForTest().contains("no-gps.dat"), pane.segmentBodyTextForTest());
        runOnFx(pane::dispose);
    }

    @Test
    void switchingBetweenAllMeasurementsAndOneFileUpdatesDistance() throws Exception {
        MapRoute fileA = routeNear(12.9716, 77.5946, 98);
        MapRoute fileB = routeNear(19.0760, 72.8777, 30);
        assertEquals("19.6 km", String.format("%.1f km", fileA.model().totalDistanceMeters() / 1000.0));
        ProjectRoute project = ProjectRouteAssembler.assemble(List.of(
                new ProjectRouteAssembler.PreparedMeasurement("src-A", "A.dat", fileA),
                new ProjectRouteAssembler.PreparedMeasurement("src-B", "B.dat", fileB)
        ), 2);
        String aggregate = MapRoutePane.formatAggregateKilometers(project.totalDistanceMeters());
        assertFalse(aggregate.equals("19.6 km"));

        MapRoutePane pane = newPane();
        runOnFx(() -> pane.presentRoutesForTest(projectRequest(2), project, null));
        assertTrue(pane.segmentBodyTextForTest().contains(aggregate), pane.segmentBodyTextForTest());

        runOnFx(() -> pane.presentRoutesForTest(singleRequest(), ProjectRoute.empty(), fileA));
        assertTrue(pane.segmentBodyTextForTest().contains("19.6 km"), pane.segmentBodyTextForTest());
        assertFalse(pane.segmentBodyTextForTest().contains(aggregate), pane.segmentBodyTextForTest());

        runOnFx(() -> pane.presentRoutesForTest(projectRequest(2), project, null));
        assertTrue(pane.segmentBodyTextForTest().contains(aggregate), pane.segmentBodyTextForTest());
        assertEquals("Full Drive", pane.segmentBadgeForTest());
        runOnFx(pane::dispose);
    }

    private static MapRoute routeNear(double lat, double lon, int steps) {
        double stepDegrees = 0.00179863;
        List<SignalSamplePoint> latitude = new ArrayList<>();
        List<SignalSamplePoint> longitude = new ArrayList<>();
        for (int i = 0; i <= steps; i++) {
            long timestamp = i * 1_000_000_000L;
            latitude.add(new SignalSamplePoint(timestamp, lat + i * stepDegrees, null, null));
            longitude.add(new SignalSamplePoint(timestamp, lon, null, null));
        }
        MapRouteModel model = MapRouteBuilder.build(latitude, longitude, null);
        return new MapRoute(model);
    }

    private static MapRouteRequest projectRequest(int measurements) {
        return MapRouteRequest.projectWide(emptyQuery(), List.of(), "dat", measurements, 1, 0, 0, 1);
    }

    private static MapRouteRequest singleRequest() {
        return new MapRouteRequest(emptyQuery(), List.of(), "dat", "src-A", "A.dat", 1, 0, 0, 1, false);
    }

    private static GraphSeriesQuery emptyQuery() {
        return new GraphSeriesQuery() {
            @Override
            public List<com.stellar.platform.domain.catalog.SignalSamplePoint> queryRange(
                    com.stellar.platform.domain.catalog.SignalCatalogKey key, long startNanos, long endNanos) {
                return List.of();
            }

            @Override
            public String unit(com.stellar.platform.domain.catalog.SignalCatalogKey key) {
                return "";
            }

            @Override
            public long sampleCount(com.stellar.platform.domain.catalog.SignalCatalogKey key) {
                return 0;
            }
        };
    }

    private static MapRoutePane newPane() throws Exception {
        AtomicReference<MapRoutePane> ref = new AtomicReference<>();
        runOnFx(() -> ref.set(new MapRoutePane()));
        return ref.get();
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
                done.countDown();
            }
        });
        assertTrue(done.await(20, TimeUnit.SECONDS));
        if (error.get() != null) {
            throw new AssertionError(error.get());
        }
    }
}
