package com.stellar.platform.processing.map;

import com.stellar.platform.domain.catalog.SignalSamplePoint;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RouteRangeMetricsTest {

    private static final long SECOND = 1_000_000_000L;

    private static MapRouteModel route(int count, double[] altitudes) {
        List<SignalSamplePoint> latitude = new ArrayList<>(count);
        List<SignalSamplePoint> longitude = new ArrayList<>(count);
        List<SignalSamplePoint> altitude = altitudes == null ? null : new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            latitude.add(new SignalSamplePoint(i * SECOND, 18.5, null, null));
            longitude.add(new SignalSamplePoint(i * SECOND, 73.7 + i * 0.0001, null, null));
            if (altitude != null) {
                altitude.add(new SignalSamplePoint(i * SECOND, altitudes[i], null, null));
            }
        }
        return MapRouteBuilder.build(latitude, longitude, altitude);
    }

    @Test
    void durationDistanceAndSampleCountFollowTheSelectedRange() {
        MapRouteModel model = route(101, null);

        RouteRangeMetrics metrics = RouteRangeMetrics.compute(model, 10, 60, MapRouteBuildOptions.defaults());

        assertEquals(10 * SECOND, metrics.startTimestampNanos());
        assertEquals(60 * SECOND, metrics.endTimestampNanos());
        assertEquals(50.0, metrics.durationSeconds(), 1e-9);
        assertEquals(51, metrics.sampleCount());
        assertEquals(model.distanceMetersBetween(10, 60), metrics.distanceMeters(), 1e-6);
    }

    @Test
    void reversedSelectionProducesTheSameMetrics() {
        MapRouteModel model = route(50, null);

        RouteRangeMetrics forward = RouteRangeMetrics.compute(model, 5, 40, null);
        RouteRangeMetrics reversed = RouteRangeMetrics.compute(model, 40, 5, null);

        assertEquals(forward, reversed);
    }

    @Test
    void averageSpeedMatchesDistanceOverDuration() {
        MapRouteModel model = route(61, null);

        RouteRangeMetrics metrics = RouteRangeMetrics.compute(model, 0, 60, null);

        assertNotNull(metrics.averageSpeedKph());
        double expected = (metrics.distanceMeters() / metrics.durationSeconds()) * 3.6;
        assertEquals(expected, metrics.averageSpeedKph(), 1e-6);
        assertTrue(metrics.maxSpeedKph() >= metrics.averageSpeedKph() - 1e-6);
    }

    @Test
    void elevationGainIgnoresNoiseBelowTheThreshold() {
        double[] jitter = new double[101];
        for (int i = 0; i < jitter.length; i++) {
            jitter[i] = 500.0 + (i % 2 == 0 ? 0.3 : -0.3);
        }
        MapRouteModel model = route(101, jitter);

        RouteRangeMetrics metrics = RouteRangeMetrics.compute(model, 0, 100, MapRouteBuildOptions.defaults());

        assertNotNull(metrics.elevationGainMeters());
        assertEquals(0.0, metrics.elevationGainMeters(), 1e-9);
    }

    @Test
    void elevationGainCountsRealClimbs() {
        double[] climb = new double[101];
        for (int i = 0; i < climb.length; i++) {
            climb[i] = 500.0 + i * 2.0;
        }
        MapRouteModel model = route(101, climb);

        RouteRangeMetrics metrics = RouteRangeMetrics.compute(model, 0, 100, MapRouteBuildOptions.defaults());

        assertEquals(200.0, metrics.elevationGainMeters(), 1e-6);
    }

    @Test
    void elevationGainIsAbsentWithoutAltitude() {
        MapRouteModel model = route(10, null);

        assertNull(RouteRangeMetrics.compute(model, 0, 9, null).elevationGainMeters());
    }

    @Test
    void emptyRouteYieldsNeutralMetrics() {
        RouteRangeMetrics metrics = RouteRangeMetrics.compute(MapRouteModel.empty(), 0, 0, null);

        assertEquals(0, metrics.sampleCount());
        assertEquals(0.0, metrics.distanceMeters(), 1e-9);
        assertNull(metrics.averageSpeedKph());
    }
}
