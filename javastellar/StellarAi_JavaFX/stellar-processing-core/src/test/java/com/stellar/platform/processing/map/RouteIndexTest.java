package com.stellar.platform.processing.map;

import com.stellar.platform.domain.catalog.SignalSamplePoint;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RouteIndexTest {

    private static final long SECOND = 1_000_000_000L;

    private static MapRouteModel route(int count, double latitudeStep, double longitudeStep) {
        List<SignalSamplePoint> latitude = new ArrayList<>(count);
        List<SignalSamplePoint> longitude = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            latitude.add(new SignalSamplePoint(i * SECOND, 18.5 + i * latitudeStep, null, null));
            longitude.add(new SignalSamplePoint(i * SECOND, 73.7 + i * longitudeStep, null, null));
        }
        return MapRouteBuilder.build(latitude, longitude, null);
    }

    @Test
    void lodKeepsEndpointsAndReducesAStraightLine() {
        MapRouteModel model = route(500, 0.0, 0.00001);
        RouteLodIndex lod = new RouteLodIndex(model);

        int[] coarse = lod.indices(0.001);

        assertEquals(0, coarse[0]);
        assertEquals(model.size() - 1, coarse[coarse.length - 1]);
        assertTrue(coarse.length < model.size() / 10, "a straight line must decimate heavily");
    }

    @Test
    void aPerfectlyStraightRouteCollapsesToItsEndpoints() {
        MapRouteModel model = route(200, 0.00001, 0.00001);

        assertEquals(2, new RouteLodIndex(model).indices(0.0).length,
                "a straight line carries no shape information beyond its endpoints");
    }

    @Test
    void smallCurvyRouteKeepsFullDetailAtTheFinestLevel() {
        MapRouteModel model = curvyRoute(200);

        assertEquals(model.size(), new RouteLodIndex(model).indices(0.0).length);
    }

    @Test
    void lodDetailIncreasesAsToleranceShrinks() {
        MapRouteModel model = curvyRoute(2_000);
        RouteLodIndex lod = new RouteLodIndex(model);

        int coarse = lod.indices(0.01).length;
        int fine = lod.indices(0.00001).length;

        assertTrue(fine > coarse, "zooming in must render more detail");
        assertTrue(fine <= model.size());
    }

    @Test
    void levelLadderIsBoundedAndZoomingReusesTheSameArrays() {
        MapRouteModel model = curvyRoute(500);
        RouteLodIndex lod = new RouteLodIndex(model);

        assertSame(lod.indices(0.001), lod.indices(0.001));
        for (int i = 0; i < 200; i++) {
            lod.indices(0.00001 * (i + 1));
        }

        assertTrue(lod.preparedLevelCountForTest() <= 7, "the LOD ladder must stay bounded");
    }

    @Test
    void everyLevelStaysWithinTheRenderBudget() {
        MapRouteModel model = curvyRoute(200_000);
        RouteLodIndex lod = new RouteLodIndex(model);
        lod.prepareLevels();

        for (double tolerance : new double[]{1e-1, 1e-2, 1e-3, 1e-4, 1e-6, 0}) {
            assertTrue(lod.indices(tolerance).length <= 40_000,
                    "no zoom level may ask the canvas to draw an unbounded number of points");
        }
    }

    @Test
    void indicesAreAPureLookupOnceLevelsArePrepared() {
        MapRouteModel model = curvyRoute(200_000);
        RouteLodIndex lod = new RouteLodIndex(model);
        lod.prepareLevels();

        long start = System.nanoTime();
        for (int i = 0; i < 2_000; i++) {
            lod.indices(1e-5 * (1 + i % 50));
        }
        long averageNanos = (System.nanoTime() - start) / 2_000;

        assertTrue(lod.prepared());
        assertTrue(averageNanos < 50_000,
                "zoom must not recompute decimation on the render thread, was " + averageNanos + "ns");
    }

    @Test
    void lodNeverBridgesAGap() {
        List<SignalSamplePoint> latitude = new ArrayList<>();
        List<SignalSamplePoint> longitude = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            latitude.add(new SignalSamplePoint(i * SECOND, 18.5 + i * 0.00001, null, null));
            longitude.add(new SignalSamplePoint(i * SECOND, 73.7, null, null));
        }
        for (int i = 50; i < 100; i++) {
            latitude.add(new SignalSamplePoint(i * SECOND, 30.0 + i * 0.00001, null, null));
            longitude.add(new SignalSamplePoint(i * SECOND, 80.0, null, null));
        }
        MapRouteModel model = MapRouteBuilder.build(latitude, longitude, null);
        int[] indices = new RouteLodIndex(model).indices(0.01);

        assertTrue(contains(indices, 49), "run before the gap must keep its last point");
        assertTrue(contains(indices, 50), "run after the gap must keep its first point");
    }

    @Test
    void spatialIndexFindsTheSameSampleAsABruteForceScan() {
        MapRouteModel model = curvyRoute(5_000);
        RouteSpatialIndex index = new RouteSpatialIndex(model);
        Random random = new Random(42);

        for (int trial = 0; trial < 200; trial++) {
            int expectedSource = random.nextInt(model.size());
            double latitude = model.latitude(expectedSource) + (random.nextDouble() - 0.5) * 1e-5;
            double longitude = model.longitude(expectedSource) + (random.nextDouble() - 0.5) * 1e-5;

            int found = index.nearest(latitude, longitude);
            int expected = bruteForceNearest(model, latitude, longitude);

            assertEquals(distance(model, expected, latitude, longitude),
                    distance(model, found, latitude, longitude), 1e-12,
                    "spatial lookup must match the brute-force nearest distance");
        }
    }

    @Test
    void spatialIndexHandlesClicksOutsideTheRoute() {
        MapRouteModel model = curvyRoute(500);
        RouteSpatialIndex index = new RouteSpatialIndex(model);

        int found = index.nearest(0.0, 0.0);

        assertTrue(found >= 0 && found < model.size());
    }

    @Test
    void emptyRouteIndexesAreSafe() {
        MapRouteModel empty = MapRouteModel.empty();

        assertEquals(0, new RouteLodIndex(empty).indices(0.001).length);
        assertEquals(-1, new RouteSpatialIndex(empty).nearest(18.5, 73.7));
    }

    private static MapRouteModel curvyRoute(int count) {
        List<SignalSamplePoint> latitude = new ArrayList<>(count);
        List<SignalSamplePoint> longitude = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            double angle = i * 0.01;
            latitude.add(new SignalSamplePoint(i * SECOND, 18.5 + 0.02 * Math.sin(angle), null, null));
            longitude.add(new SignalSamplePoint(i * SECOND, 73.7 + 0.02 * Math.cos(angle), null, null));
        }
        return MapRouteBuilder.build(latitude, longitude, null);
    }

    private static boolean contains(int[] values, int target) {
        for (int value : values) {
            if (value == target) {
                return true;
            }
        }
        return false;
    }

    private static int bruteForceNearest(MapRouteModel model, double latitude, double longitude) {
        int best = -1;
        double bestDistance = Double.MAX_VALUE;
        for (int i = 0; i < model.size(); i++) {
            double d = distance(model, i, latitude, longitude);
            if (d < bestDistance) {
                bestDistance = d;
                best = i;
            }
        }
        return best;
    }

    private static double distance(MapRouteModel model, int index, double latitude, double longitude) {
        double scale = Math.cos(Math.toRadians(latitude));
        double dx = (model.longitude(index) - longitude) * scale;
        double dy = model.latitude(index) - latitude;
        return dx * dx + dy * dy;
    }
}
