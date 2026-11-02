package com.stellar.platform.desktop.ui.graph.map;

import com.stellar.platform.domain.catalog.SignalSamplePoint;
import com.stellar.platform.processing.map.MapRouteBuilder;
import com.stellar.platform.processing.map.MapRouteModel;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class MapViewportCalculatorTest {

    private static final long SECOND = 1_000_000_000L;

    @Test
    void viewportComesFromRouteCoordinatesNotAWorldDefault() {
        MapRouteModel route = line(18.50, 73.80, 0.01, 6);
        MapViewport viewport = new MapViewport();
        viewport.resize(1600, 900);
        double beforeLat = viewport.centerLatitude();
        double beforeLon = viewport.centerLongitude();
        viewport.fitReport(List.of(route), 0);

        MapViewportCalculator.Bounds driven = MapViewportCalculator.of(route);
        assertTrue(Math.abs(viewport.centerLatitude() - driven.centerLatitude()) < 1e-9);
        assertTrue(Math.abs(viewport.centerLongitude() - driven.centerLongitude()) < 1e-9);
        assertTrue(Math.abs(viewport.centerLatitude() - beforeLat) > 1.0
                || Math.abs(viewport.centerLongitude() - beforeLon) > 1.0);
        double longitudeSpan = viewport.longitudeAt(1600) - viewport.longitudeAt(0);
        double latitudeSpan = viewport.latitudeAt(0) - viewport.latitudeAt(900);
        assertTrue(longitudeSpan < 1.0, "longitude span " + longitudeSpan);
        assertTrue(latitudeSpan < 1.0, "latitude span " + latitudeSpan);
        assertTrue(longitudeSpan < 180);
        assertTrue(viewport.centerLatitude() > 18 && viewport.centerLatitude() < 19);
        assertTrue(viewport.centerLongitude() > 73 && viewport.centerLongitude() < 74);
    }

    @Test
    void drivenRouteFillsMostOfThePaddedFrameAndStaysInsideIt() {
        MapRouteModel route = line(18.50, 73.80, 0.02, 8);
        MapViewport viewport = new MapViewport();
        viewport.resize(1600, 900);
        viewport.fitReport(List.of(route), 0);
        MapViewportCalculator.Bounds driven = MapViewportCalculator.of(route);

        assertTrue(viewport.x(driven.minLongitude()) >= 0);
        assertTrue(viewport.x(driven.maxLongitude()) <= 1600);
        assertTrue(viewport.y(driven.maxLatitude()) >= 0);
        assertTrue(viewport.y(driven.minLatitude()) <= 900);

        double widthFraction = (viewport.x(driven.maxLongitude()) - viewport.x(driven.minLongitude())) / 1600.0;
        double heightFraction = (viewport.y(driven.minLatitude()) - viewport.y(driven.maxLatitude())) / 900.0;
        double occupancy = Math.max(widthFraction, heightFraction);
        assertTrue(occupancy >= 0.70 && occupancy <= 0.92, "occupancy " + occupancy);
    }

    @Test
    void anIsolatedGpsGapDoesNotExpandTheFrameToTheWorld() {
        List<SignalSamplePoint> latitude = new ArrayList<>();
        List<SignalSamplePoint> longitude = new ArrayList<>();
        double[] latitudes = {18.50, 18.51, 18.52, 0.0, 18.53, 18.54};
        double[] longitudes = {73.80, 73.81, 73.82, 0.0, 73.83, 73.84};
        for (int i = 0; i < latitudes.length; i++) {
            latitude.add(new SignalSamplePoint(i * SECOND, latitudes[i], null, null));
            longitude.add(new SignalSamplePoint(i * SECOND, longitudes[i], null, null));
        }
        MapRouteModel route = MapRouteBuilder.validateAndBuild(latitude, longitude, null).model();
        MapViewportCalculator.Bounds driven = MapViewportCalculator.of(route);
        assertTrue(driven.minLatitude() > 18.0, "minLat " + driven.minLatitude());
        assertTrue(driven.maxLatitude() < 19.0, "maxLat " + driven.maxLatitude());
        assertTrue(driven.minLongitude() > 73.0);
        assertTrue(driven.maxLongitude() < 74.0);

        MapViewport viewport = new MapViewport();
        viewport.resize(1600, 900);
        viewport.fitReport(List.of(route), 0);
        double outlierY = viewport.y(0.0);
        assertTrue(outlierY < 0 || outlierY > 900);
        assertTrue(viewport.x(driven.minLongitude()) >= 0);
        assertTrue(viewport.x(driven.maxLongitude()) < 1600);
        assertTrue(viewport.longitudeAt(1600) - viewport.longitudeAt(0) < 1.0);
    }

    @Test
    void aDistantGapClusterDoesNotPullTheFrameOffTheDrive() {
        List<SignalSamplePoint> latitude = new ArrayList<>();
        List<SignalSamplePoint> longitude = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            latitude.add(new SignalSamplePoint(i * SECOND, 18.52 + i * 0.004, null, null));
            longitude.add(new SignalSamplePoint(i * SECOND, 73.85 + i * 0.004, null, null));
        }
        latitude.add(new SignalSamplePoint(20 * SECOND, 0.01, null, null));
        longitude.add(new SignalSamplePoint(20 * SECOND, 0.80, null, null));
        latitude.add(new SignalSamplePoint(21 * SECOND, 0.012, null, null));
        longitude.add(new SignalSamplePoint(21 * SECOND, 0.802, null, null));
        MapRouteModel route = MapRouteBuilder.validateAndBuild(latitude, longitude, null).model();
        MapViewportCalculator.Bounds driven = MapViewportCalculator.of(route);
        assertTrue(driven.minLatitude() > 18.0, "minLat " + driven.minLatitude());
        assertTrue(driven.maxLongitude() > 73.0 && driven.maxLongitude() < 75.0);
        assertTrue(MapViewportCalculator.firstIncluded(route) >= 0);
        assertTrue(MapViewportCalculator.lastIncluded(route) < 8);
    }

    @Test
    void combinedBoundsCoverEveryDrivenSegmentAndNotADefaultWorld() {
        MapRouteModel west = line(18.60, 73.70, 0.004, 5);
        MapRouteModel east = line(18.90, 74.20, 0.004, 5);
        MapViewportCalculator.Bounds combined = MapViewportCalculator.of(List.of(west, east));
        assertTrue(combined.minLatitude() <= 18.60);
        assertTrue(combined.maxLatitude() >= 18.91);
        assertTrue(combined.minLongitude() <= 73.70);
        assertTrue(combined.maxLongitude() >= 74.21);
        MapViewport viewport = new MapViewport();
        viewport.resize(1600, 900);
        viewport.fitReport(List.of(west, east), 0);
        assertTrue(viewport.x(west.minLongitude()) >= 0);
        assertTrue(viewport.x(east.maxLongitude()) <= 1600);
        assertTrue(viewport.longitudeAt(1600) - viewport.longitudeAt(0) < 2.0);
    }

    private static MapRouteModel line(double latitude, double longitude, double step, int count) {
        List<SignalSamplePoint> latitudes = new ArrayList<>();
        List<SignalSamplePoint> longitudes = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            latitudes.add(new SignalSamplePoint(i * SECOND, latitude + i * step, null, null));
            longitudes.add(new SignalSamplePoint(i * SECOND, longitude + i * step, null, null));
        }
        return MapRouteBuilder.validateAndBuild(latitudes, longitudes, null).model();
    }
}
