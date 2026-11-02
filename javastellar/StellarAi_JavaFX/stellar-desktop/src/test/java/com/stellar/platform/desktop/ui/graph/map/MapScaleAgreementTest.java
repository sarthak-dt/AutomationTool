package com.stellar.platform.desktop.ui.graph.map;

import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSamplePoint;
import com.stellar.platform.domain.catalog.SignalSourceType;
import com.stellar.platform.processing.graph.GraphSeriesQuery;
import com.stellar.platform.processing.map.GpsSignalRole;
import com.stellar.platform.processing.map.MapRouteCacheKey;
import com.stellar.platform.processing.map.MapRouteModel;
import com.stellar.platform.processing.map.MapRouteService;
import com.stellar.platform.processing.map.RouteMovement;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The route overlay and the basemap have to be drawn at one scale.
 *
 * <p>JavaFX WebView Leaflet uses an integer zoom (no CSS {@code scale()}). Production Fit Route
 * floors the desired continuous zoom so the route stays fully visible, then both {@link RouteCanvas}
 * and Leaflet consume that same integer via {@link MapCamera}. The tolerance is a fraction of a
 * percent: canvas pixels-per-degree must match the scale of the actual Leaflet zoom.
 */
class MapScaleAgreementTest {

    private static final long SECOND = 1_000_000_000L;
    private static final double METERS_PER_DEGREE = 111_320.0;
    private static final double CENTER_LATITUDE = 18.52;
    private static final double CENTER_LONGITUDE = 73.85;

    /** Well inside a rounding step, so a canvas/Leaflet scale split cannot slip through. */
    private static final double TOLERANCE = 0.002;

    private static final double VIEW_WIDTH = 960;
    private static final double VIEW_HEIGHT = 620;
    private static final double MARGIN = 24;

    private static final class Query implements GraphSeriesQuery {
        final Map<SignalCatalogKey, List<SignalSamplePoint>> data = new HashMap<>();

        @Override
        public List<SignalSamplePoint> queryRange(SignalCatalogKey key, long startNanos, long endNanos) {
            return data.getOrDefault(key, List.of());
        }

        @Override
        public String unit(SignalCatalogKey key) {
            return "deg";
        }

        @Override
        public long sampleCount(SignalCatalogKey key) {
            return data.getOrDefault(key, List.of()).size();
        }
    }

    private static SignalCatalogKey key(String name) {
        return new SignalCatalogKey(name, SignalSourceType.MF4_NATIVE, "GPS", 0);
    }

    /** A straight north-east run of the requested extent, built through the real route pipeline. */
    private static MapRouteModel routeSpanning(double meters) {
        int samples = 200;
        double latitudeSpan = meters / METERS_PER_DEGREE / Math.sqrt(2);
        double cosine = Math.cos(Math.toRadians(CENTER_LATITUDE));
        double longitudeSpan = latitudeSpan / cosine;
        Query query = new Query();
        List<SignalSamplePoint> latitude = new ArrayList<>(samples);
        List<SignalSamplePoint> longitude = new ArrayList<>(samples);
        for (int i = 0; i < samples; i++) {
            double t = samples == 1 ? 0 : i / (double) (samples - 1);
            latitude.add(new SignalSamplePoint(i * SECOND, CENTER_LATITUDE + t * latitudeSpan, null, null));
            longitude.add(new SignalSamplePoint(i * SECOND, CENTER_LONGITUDE + t * longitudeSpan, null, null));
        }
        query.data.put(key("Latitude"), latitude);
        query.data.put(key("Longitude"), longitude);
        Map<GpsSignalRole, SignalCatalogKey> roles = new EnumMap<>(GpsSignalRole.class);
        roles.put(GpsSignalRole.LATITUDE, key("Latitude"));
        roles.put(GpsSignalRole.LONGITUDE, key("Longitude"));
        MapRouteCacheKey cacheKey = new MapRouteCacheKey("project-1", "file-a", 1L, roles);
        return new MapRouteService(query).prepare(cacheKey, 0, samples * SECOND).model();
    }

    private static MapViewport fitted(MapRouteModel route) {
        MapViewport viewport = new MapViewport();
        viewport.resize(VIEW_WIDTH, VIEW_HEIGHT);
        viewport.fit(route, MARGIN);
        return viewport;
    }

    /** Relative disagreement between the canvas scale and the scale Leaflet adopts at its zoom. */
    private static double scaleError(MapViewport viewport) {
        double canvas = viewport.pixelsPerDegreeLongitude();
        double leaflet = MapScale.pixelsPerDegreeLongitudeAt(viewport.leafletZoom());
        return Math.abs(leaflet - canvas) / canvas;
    }

    private static void assertRouteInsideViewport(MapViewport viewport, MapRouteModel route) {
        double minX = viewport.x(route.minLongitude());
        double maxX = viewport.x(route.maxLongitude());
        double topY = viewport.y(route.maxLatitude());
        double bottomY = viewport.y(route.minLatitude());
        assertTrue(minX >= -1 && maxX <= VIEW_WIDTH + 1,
                "route longitude left the viewport: " + minX + ".." + maxX);
        assertTrue(topY >= -1 && bottomY <= VIEW_HEIGHT + 1,
                "route latitude left the viewport: " + topY + ".." + bottomY);
    }

    @Test
    void canvasAndLeafletAgreeOnScaleAcrossRepresentativeRouteLengths() {
        for (double meters : new double[] {40_000, 8_000, 2_000, 300, 60}) {
            MapRouteModel route = routeSpanning(meters);
            MapViewport viewport = fitted(route);
            assertTrue(scaleError(viewport) < TOLERANCE,
                    () -> "scale disagreement over " + meters + " m: canvas "
                            + viewport.pixelsPerDegreeLongitude() + " px/deg vs Leaflet "
                            + MapScale.pixelsPerDegreeLongitudeAt(viewport.leafletZoom()) + " px/deg");
            assertRouteInsideViewport(viewport, route);
        }
    }

    @Test
    void canvasAndLeafletAgreeOnTheVisibleGeographicBounds() {
        MapViewport viewport = fitted(routeSpanning(8_000));
        double zoom = viewport.leafletZoom();

        double canvasLongitudeSpan = viewport.longitudeAt(VIEW_WIDTH) - viewport.longitudeAt(0);
        double leafletLongitudeSpan = VIEW_WIDTH / MapScale.pixelsPerDegreeLongitudeAt(zoom);

        assertEquals(canvasLongitudeSpan, leafletLongitudeSpan, canvasLongitudeSpan * TOLERANCE,
                "Leaflet must show exactly the longitude extent the canvas draws");
    }

    @Test
    void productionFitUsesTheIntegerZoomLeafletWillPaint() {
        MapViewport viewport = fitted(routeSpanning(8_000));
        MapCamera camera = viewport.camera();
        assertEquals(viewport.leafletZoom(), camera.zoom());
        assertEquals(viewport.centerLatitude(), camera.centerLatitude(), 1e-12);
        assertEquals(viewport.centerLongitude(), camera.centerLongitude(), 1e-12);
        assertTrue(scaleError(viewport) < TOLERANCE);
    }

    @Test
    void desiredFractionalZoomConvertsToASafeIntegerThatStillShowsTheRoute() {
        assertEquals(10, MapScale.webViewSafeZoom(10.884, 19));
        assertEquals(11, MapScale.webViewSafeZoom(11.0, 19));
        assertEquals(1, MapScale.webViewSafeZoom(0.4, 19));
        assertEquals(17, MapScale.webViewSafeZoom(18.7, 17));

        MapRouteModel route = routeSpanning(8_000);
        MapViewport viewport = fitted(route);
        assertRouteInsideViewport(viewport, route);
    }

    @Test
    void canvasScaleEqualsTheActualIntegerLeafletZoomNotAPrivateContinuousScale() {
        MapViewport viewport = fitted(routeSpanning(8_000));
        double continuousWouldHaveBeen = MapScale.zoomFor(viewport.pixelsPerDegreeLongitude());
        assertEquals(viewport.leafletZoom(), (int) Math.floor(continuousWouldHaveBeen + 1e-9));
        assertEquals(0.0, Math.abs(continuousWouldHaveBeen - viewport.leafletZoom()), 1e-6,
                "after adopt, canvas scale must be exactly the integer zoom, not a leftover fractional fit");
    }

    @Test
    void scaleStaysInAgreementThroughZoomPanAndResize() {
        MapViewport viewport = fitted(routeSpanning(2_000));

        for (int i = 0; i < 12; i++) {
            viewport.zoomByLevelsAt(1, VIEW_WIDTH * 0.25, VIEW_HEIGHT * 0.75);
            assertTrue(scaleError(viewport) < TOLERANCE, "drifted while zooming in at step " + i);
        }
        viewport.panByPixels(180, -140);
        assertTrue(scaleError(viewport) < TOLERANCE, "drifted while panning");

        viewport.resize(420, 1_100);
        assertTrue(scaleError(viewport) < TOLERANCE, "drifted after resize");

        for (int i = 0; i < 12; i++) {
            viewport.zoomByLevels(-1);
            assertTrue(scaleError(viewport) < TOLERANCE, "drifted while zooming out at step " + i);
        }
    }

    @Test
    void canvasNeverZoomsBeyondWhatTheActiveProviderCanRender() {
        MapViewport viewport = new MapViewport();
        viewport.resize(VIEW_WIDTH, VIEW_HEIGHT);
        viewport.setProviderMaxZoom(17);
        viewport.fit(routeSpanning(60), MARGIN);

        for (int i = 0; i < 30; i++) {
            viewport.zoomByLevels(1);
        }

        assertTrue(viewport.leafletZoom() <= 17,
                "canvas reached zoom " + viewport.leafletZoom() + " beyond the provider maximum 17");
        assertTrue(scaleError(viewport) < TOLERANCE, "clamping must not desynchronise the basemap");
    }

    @Test
    void fallingBackToACoarserProviderPullsTheCanvasScaleBackDown() {
        MapViewport viewport = new MapViewport();
        viewport.resize(VIEW_WIDTH, VIEW_HEIGHT);
        viewport.setProviderMaxZoom(19);
        viewport.fit(routeSpanning(60), MARGIN);
        for (int i = 0; i < 20; i++) {
            viewport.zoomByLevels(1);
        }

        viewport.setProviderMaxZoom(11);

        assertTrue(viewport.leafletZoom() <= 11,
                "provider fallback must clamp the existing scale, zoom was " + viewport.leafletZoom());
        assertTrue(scaleError(viewport) < TOLERANCE);
        assertEquals(viewport.leafletZoom(), viewport.camera().zoom());
    }

    @Test
    void stationaryRecordingIsFramedAtAReadableZoomOnTheGpsLocation() {
        MapRouteModel route = routeSpanning(0);
        assertEquals(RouteMovement.STATIONARY, RouteMovement.classify(route));

        MapViewport viewport = fitted(route);

        assertEquals(CENTER_LATITUDE, viewport.centerLatitude(), 1e-9);
        assertEquals(CENTER_LONGITUDE, viewport.centerLongitude(), 1e-9);
        int zoom = viewport.leafletZoom();
        assertTrue(zoom >= (int) MapScale.MIN_ZOOM && zoom <= MapScale.DEFAULT_MAX_ZOOM,
                "stationary recording framed at unusable zoom " + zoom);
        assertTrue(scaleError(viewport) < TOLERANCE);
        assertRouteInsideViewport(viewport, route);
    }

    @Test
    void nearStationaryRecordingIsFramedLikeAStationaryOneRatherThanMagnified() {
        MapRouteModel route = routeSpanning(6);
        MapViewport nearStationary = fitted(route);
        MapViewport stationary = fitted(routeSpanning(0));

        assertEquals(RouteMovement.STATIONARY, RouteMovement.classify(route));
        assertEquals(stationary.leafletZoom(), nearStationary.leafletZoom(),
                "receiver noise must not be magnified into a route");
    }

    @Test
    void shortButRealMovementIsReportedWithoutBeingTreatedAsStationary() {
        MapRouteModel route = routeSpanning(50);

        assertEquals(RouteMovement.MINIMAL, RouteMovement.classify(route));
        assertTrue(RouteMovement.classify(route).message().toLowerCase().contains("little movement")
                        || !RouteMovement.classify(route).message().isBlank(),
                "a minimal route needs a status the user can read");
        assertTrue(scaleError(fitted(route)) < TOLERANCE);
    }
}
