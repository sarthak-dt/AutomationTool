package com.stellar.platform.processing.map;

import com.stellar.platform.domain.SignalValidity;
import com.stellar.platform.domain.catalog.SignalSamplePoint;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MapRouteBuilderTest {

    private static final long SECOND = 1_000_000_000L;

    private static SignalSamplePoint sample(long timestampNanos, double value) {
        return new SignalSamplePoint(timestampNanos, value, null, null);
    }

    private static List<SignalSamplePoint> series(double start, double step, int count, long period) {
        List<SignalSamplePoint> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            out.add(sample(i * period, start + i * step));
        }
        return out;
    }

    @Test
    void rejectsInvalidLatitudeInsideWgs84AndKeepsValidFixes() {
        List<SignalSamplePoint> latitude = List.of(
                sample(0, 18.52040),
                new SignalSamplePoint(SECOND, 18.52045, 1L, null, SignalValidity.INVALID_RAW_CODE),
                sample(2 * SECOND, 18.52050));
        List<SignalSamplePoint> longitude = List.of(
                sample(0, 73.85670),
                sample(SECOND, 73.85675),
                sample(2 * SECOND, 73.85680));
        MapRouteModel route = MapRouteBuilder.build(latitude, longitude, null);
        assertEquals(2, route.size());
        assertEquals(18.52040, route.latitude(0), 1e-9);
        assertEquals(73.85670, route.longitude(0), 1e-9);
        assertEquals(18.52050, route.latitude(1), 1e-9);
        assertEquals(73.85680, route.longitude(1), 1e-9);
        assertTrue(route.totalDistanceMeters() > 0);
    }

    @Test
    void buildsRouteFromPairedLatitudeAndLongitude() {
        MapRouteModel route = MapRouteBuilder.build(
                series(18.5, 0.001, 10, SECOND),
                series(73.7, 0.001, 10, SECOND),
                null);

        assertEquals(10, route.size());
        assertFalse(route.hasAltitude());
        assertEquals(18.5, route.latitude(0), 1e-9);
        assertEquals(73.7, route.longitude(0), 1e-9);
        assertEquals(0.0, route.cumulativeDistanceMeters(0), 1e-9);
        assertTrue(route.totalDistanceMeters() > 0);
    }

    @Test
    void carriesAltitudeWhenAvailable() {
        MapRouteModel route = MapRouteBuilder.build(
                series(18.5, 0.001, 5, SECOND),
                series(73.7, 0.001, 5, SECOND),
                series(560.0, 1.0, 5, SECOND));

        assertTrue(route.hasAltitude());
        assertEquals(564.0, route.altitude(4), 1e-9);
    }

    @Test
    void missingAltitudeSamplesBecomeNaNWithoutDroppingThePoint() {
        List<SignalSamplePoint> altitude = List.of(sample(0, 500.0), sample(4 * SECOND, 504.0));
        MapRouteModel route = MapRouteBuilder.build(
                series(18.5, 0.001, 5, SECOND),
                series(73.7, 0.001, 5, SECOND),
                altitude,
                new MapRouteBuildOptions(SECOND / 2, 1_000, 5 * SECOND, 1.0));

        assertEquals(5, route.size());
        assertEquals(500.0, route.altitude(0), 1e-9);
        assertTrue(Double.isNaN(route.altitude(2)));
    }

    @Test
    void skipsInvalidCoordinates() {
        List<SignalSamplePoint> latitude = List.of(
                sample(0, 18.5),
                sample(SECOND, Double.NaN),
                sample(2 * SECOND, 120.0),
                sample(3 * SECOND, 0.0),
                sample(4 * SECOND, 18.6));
        List<SignalSamplePoint> longitude = List.of(
                sample(0, 73.7),
                sample(SECOND, 73.7),
                sample(2 * SECOND, 73.7),
                sample(3 * SECOND, 0.0),
                sample(4 * SECOND, 73.8));

        MapRouteModel route = MapRouteBuilder.build(latitude, longitude, null);

        assertEquals(3, route.size());
        assertEquals(0L, route.timestampNanos(0));
        assertEquals(3 * SECOND, route.timestampNanos(1));
        assertEquals(4 * SECOND, route.timestampNanos(2));
        assertEquals(0.0, route.latitude(1), 1e-9);
        assertEquals(0.0, route.longitude(1), 1e-9);
    }

    @Test
    void allZeroZeroRouteIsTreatedAsMissingGps() {
        List<SignalSamplePoint> latitude = series(0.0, 0.0, 25, SECOND);
        List<SignalSamplePoint> longitude = series(0.0, 0.0, 25, SECOND);
        assertTrue(MapRouteBuilder.build(latitude, longitude, null).isEmpty());
    }

    @Test
    void rejectsInfiniteCoordinates() {
        List<SignalSamplePoint> latitude = List.of(
                sample(0, 18.5),
                sample(SECOND, Double.POSITIVE_INFINITY),
                sample(2 * SECOND, 18.6));
        List<SignalSamplePoint> longitude = List.of(
                sample(0, 73.7),
                sample(SECOND, Double.NEGATIVE_INFINITY),
                sample(2 * SECOND, 73.8));

        MapRouteModel route = MapRouteBuilder.build(latitude, longitude, null);
        assertEquals(2, route.size());
    }

    @Test
    void pairsByNearestTimestampWithinTolerance() {
        List<SignalSamplePoint> latitude = List.of(
                sample((long) (1.0 * SECOND), 51.0),
                sample((long) (2.0 * SECOND), 51.1));
        List<SignalSamplePoint> longitude = List.of(
                sample((long) (1.05 * SECOND), -0.1),
                sample((long) (2.05 * SECOND), -0.2));

        MapRouteModel route = MapRouteBuilder.build(
                latitude, longitude, null, MapRouteBuildOptions.defaults());

        assertEquals(2, route.size());
        assertEquals(51.0, route.latitude(0), 1e-9);
        assertEquals(-0.1, route.longitude(0), 1e-9);
        assertEquals(51.1, route.latitude(1), 1e-9);
        assertEquals(-0.2, route.longitude(1), 1e-9);
    }

    @Test
    void dropsDuplicateAndOutOfOrderTimestamps() {
        List<SignalSamplePoint> latitude = List.of(
                sample(0, 18.5), sample(0, 18.5), sample(SECOND, 18.6), sample(SECOND / 2, 18.7));
        List<SignalSamplePoint> longitude = List.of(
                sample(0, 73.7), sample(0, 73.7), sample(SECOND, 73.8), sample(SECOND / 2, 73.9));

        MapRouteModel route = MapRouteBuilder.build(latitude, longitude, null);

        assertEquals(2, route.size());
        assertTrue(route.timestampNanos(1) > route.timestampNanos(0));
    }

    @Test
    void gpsJumpBecomesAGapAndDoesNotAddDrivenDistance() {
        List<SignalSamplePoint> latitude = List.of(
                sample(0, 18.5000), sample(SECOND, 18.5001), sample(2 * SECOND, 25.0000), sample(3 * SECOND, 25.0001));
        List<SignalSamplePoint> longitude = List.of(
                sample(0, 73.7000), sample(SECOND, 73.7001), sample(2 * SECOND, 80.0000), sample(3 * SECOND, 80.0001));

        MapRouteModel route = MapRouteBuilder.build(latitude, longitude, null);

        assertEquals(4, route.size());
        assertTrue(route.segmentBreakBefore(2));
        assertFalse(route.segmentBreakBefore(1));
        assertTrue(route.totalDistanceMeters() < 100, "jump distance must not be counted as driven");
    }

    @Test
    void impossibleRoadSpeedIsAGapEvenWhenTheStepIsUnderOneKilometre() {
        long quarterSecond = 255_000_000L;
        List<SignalSamplePoint> latitude = List.of(sample(0, 18.6500000), sample(quarterSecond, 18.6526500));
        List<SignalSamplePoint> longitude = List.of(sample(0, 73.8100000), sample(quarterSecond, 73.8100000));

        MapRouteModel route = MapRouteBuilder.build(latitude, longitude, null);

        assertEquals(2, route.size());
        assertEquals(18.6526500, route.latitude(1), 1e-9);
        assertTrue(route.segmentBreakBefore(1));
        assertEquals(0.0, route.totalDistanceMeters(), 1e-6);
    }

    @Test
    void ordinaryUrbanStepStaysOneContinuousSegment() {
        List<SignalSamplePoint> latitude = List.of(sample(0, 18.6500000), sample(SECOND, 18.6500900));
        List<SignalSamplePoint> longitude = List.of(sample(0, 73.8100000), sample(SECOND, 73.8100000));

        MapRouteModel route = MapRouteBuilder.build(latitude, longitude, null);

        assertFalse(route.segmentBreakBefore(1));
        assertTrue(route.totalDistanceMeters() > 5);
        assertTrue(route.totalDistanceMeters() < 20);
    }

    @Test
    void longTimeGapIsTreatedAsARouteBreak() {
        List<SignalSamplePoint> latitude = List.of(sample(0, 18.5), sample(60 * SECOND, 18.5001));
        List<SignalSamplePoint> longitude = List.of(sample(0, 73.7), sample(60 * SECOND, 73.7001));

        MapRouteModel route = MapRouteBuilder.build(latitude, longitude, null,
                new MapRouteBuildOptions(60 * SECOND, 1_000, 5 * SECOND, 1.0));

        assertTrue(route.segmentBreakBefore(1));
    }

    @Test
    void emptyInputsProduceAnEmptyRoute() {
        assertTrue(MapRouteBuilder.build(List.of(), List.of(), null).isEmpty());
        assertTrue(MapRouteBuilder.build(null, null, null).isEmpty());
    }

    @Test
    void differentSamplingRatesStillPair() {
        List<SignalSamplePoint> latitude = series(18.5, 0.001, 5, SECOND);
        List<SignalSamplePoint> longitude = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            longitude.add(sample(i * (SECOND / 10), 73.7 + i * 0.0001));
        }

        MapRouteModel route = MapRouteBuilder.build(latitude, longitude, null);

        assertEquals(5, route.size());
        assertEquals(73.7, route.longitude(0), 1e-9);
    }
}
