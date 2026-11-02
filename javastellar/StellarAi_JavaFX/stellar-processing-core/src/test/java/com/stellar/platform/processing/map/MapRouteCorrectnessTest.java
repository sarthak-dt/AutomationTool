package com.stellar.platform.processing.map;

import com.stellar.platform.domain.catalog.SignalSamplePoint;
import com.stellar.platform.processing.drive.DriveSegmentAnalysisService;
import com.stellar.platform.processing.drive.DriveSegmentSummary;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MapRouteCorrectnessTest {

    private static final long SECOND = 1_000_000_000L;

    private static SignalSamplePoint sample(long timestampNanos, double value) {
        return new SignalSamplePoint(timestampNanos, value, null, null);
    }

    @Test
    void normalContinuousGpsRouteIsOneChronologicalPolyline() {
        List<SignalSamplePoint> lat = new ArrayList<>();
        List<SignalSamplePoint> lon = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            lat.add(sample(i * SECOND, 18.50 + i * 0.0001));
            lon.add(sample(i * SECOND, 73.80 + i * 0.0001));
        }
        ValidatedRoute route = MapRouteBuilder.validateAndBuild(lat, lon, null);
        MapRouteModel model = route.model();

        assertEquals(30, model.size());
        for (int i = 1; i < model.size(); i++) {
            assertTrue(model.timestampNanos(i) > model.timestampNanos(i - 1));
            assertFalse(model.segmentBreakBefore(i));
        }
        assertEquals(0, route.diagnostics().largeDiscontinuities());
        assertTrue(model.totalDistanceMeters() > 0);
        assertTrue(model.totalDistanceMeters() < 5_000);
    }

    @Test
    void unequalLatitudeAndLongitudeCountsPairByTimestampNotIndex() {
        List<SignalSamplePoint> lat = List.of(
                sample(0, 51.0),
                sample(2 * SECOND, 51.2),
                sample(4 * SECOND, 51.4));
        List<SignalSamplePoint> lon = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            lon.add(sample(i * (SECOND / 2), -0.10 - i * 0.01));
        }
        ValidatedRoute route = MapRouteBuilder.validateAndBuild(lat, lon, null);

        assertEquals(3, route.size());
        assertEquals(-0.10, route.point(0).longitude(), 1e-9);
        assertEquals(-0.14, route.point(1).longitude(), 1e-9);
        assertEquals(-0.18, route.point(2).longitude(), 1e-9);
        assertEquals(0L, route.point(0).longitudeTimestampNanos());
        assertEquals(2 * SECOND, route.point(1).longitudeTimestampNanos());
    }

    @Test
    void differentLatitudeAndLongitudeTimestampsStillPairSemantically() {
        List<SignalSamplePoint> lat = List.of(sample((long) (1.00 * SECOND), 13.04), sample((long) (2.00 * SECOND), 13.05));
        List<SignalSamplePoint> lon = List.of(sample((long) (1.04 * SECOND), 77.59), sample((long) (2.04 * SECOND), 77.60));

        ValidatedRoute route = MapRouteBuilder.validateAndBuild(lat, lon, null, MapRouteBuildOptions.defaults());

        assertEquals(2, route.size());
        assertEquals(13.04, route.point(0).latitude(), 1e-9);
        assertEquals(77.59, route.point(0).longitude(), 1e-9);
        assertEquals((long) (1.04 * SECOND), route.point(0).longitudeTimestampNanos());
        assertEquals(13.05, route.point(1).latitude(), 1e-9);
        assertEquals(77.60, route.point(1).longitude(), 1e-9);
    }

    @Test
    void duplicateTimestampKeepsTheFirstFixOnly() {
        List<SignalSamplePoint> lat = List.of(sample(0, 18.5), sample(0, 18.6), sample(SECOND, 18.7));
        List<SignalSamplePoint> lon = List.of(sample(0, 73.7), sample(0, 73.8), sample(SECOND, 73.9));

        ValidatedRoute route = MapRouteBuilder.validateAndBuild(lat, lon, null);

        assertEquals(2, route.size());
        assertEquals(18.5, route.model().latitude(0), 1e-9);
        assertEquals(1, route.diagnostics().duplicateOrOutOfOrderTimestamps());
    }

    @Test
    void zeroDtIsAGapAndDoesNotProduceAnInfiniteSpeed() {
        List<SignalSamplePoint> lat = List.of(
                sample(0, 18.5000),
                sample(0, 18.5010),
                sample(SECOND, 18.5020));
        List<SignalSamplePoint> lon = List.of(
                sample(0, 73.7000),
                sample(0, 73.7010),
                sample(SECOND, 73.7020));
        MapRouteModel model = MapRouteBuilder.build(lat, lon, null);
        RouteRangeMetrics metrics = RouteRangeMetrics.compute(model, 0, model.size() - 1, null);

        if (metrics.maxSpeedKph() != null) {
            assertTrue(metrics.maxSpeedKph() < 200_000, "zero-dt must not explode speed, got " + metrics.maxSpeedKph());
            assertTrue(Double.isFinite(metrics.maxSpeedKph()));
        }
    }

    @Test
    void outOfOrderTimestampIsDroppedNotSilentlySorted() {
        List<SignalSamplePoint> lat = List.of(
                sample(0, 18.50),
                sample(2 * SECOND, 18.52),
                sample(SECOND, 18.99),
                sample(3 * SECOND, 18.53));
        List<SignalSamplePoint> lon = List.of(
                sample(0, 73.70),
                sample(2 * SECOND, 73.72),
                sample(SECOND, 73.99),
                sample(3 * SECOND, 73.73));
        ValidatedRoute route = MapRouteBuilder.validateAndBuild(lat, lon, null);

        assertEquals(3, route.size());
        assertEquals(18.52, route.model().latitude(1), 1e-9);
        assertTrue(route.diagnostics().duplicateOrOutOfOrderTimestamps() >= 1);
        for (int i = 1; i < route.size(); i++) {
            assertTrue(route.model().timestampNanos(i) > route.model().timestampNanos(i - 1));
        }
    }

    @Test
    void invalidLatitudeIsRejected() {
        List<SignalSamplePoint> lat = List.of(sample(0, 18.5), sample(SECOND, 120.0), sample(2 * SECOND, 18.6));
        List<SignalSamplePoint> lon = List.of(sample(0, 73.7), sample(SECOND, 73.8), sample(2 * SECOND, 73.9));
        ValidatedRoute route = MapRouteBuilder.validateAndBuild(lat, lon, null);

        assertEquals(2, route.size());
        assertEquals(1, route.diagnostics().rejectedInvalidCoordinates());
    }

    @Test
    void invalidLongitudeIsRejected() {
        List<SignalSamplePoint> lat = List.of(sample(0, 18.5), sample(SECOND, 18.6));
        List<SignalSamplePoint> lon = List.of(sample(0, 73.7), sample(SECOND, 190.0));
        ValidatedRoute route = MapRouteBuilder.validateAndBuild(lat, lon, null);

        assertEquals(1, route.size());
        assertEquals(1, route.diagnostics().rejectedInvalidCoordinates());
    }

    @Test
    void nanAndInfinityCoordinatesAreRejected() {
        List<SignalSamplePoint> lat = List.of(
                sample(0, 18.5),
                sample(SECOND, Double.NaN),
                sample(2 * SECOND, Double.POSITIVE_INFINITY),
                sample(3 * SECOND, 18.6));
        List<SignalSamplePoint> lon = List.of(
                sample(0, 73.7),
                sample(SECOND, 73.7),
                sample(2 * SECOND, Double.NEGATIVE_INFINITY),
                sample(3 * SECOND, 73.8));
        ValidatedRoute route = MapRouteBuilder.validateAndBuild(lat, lon, null);

        assertEquals(2, route.size());
        assertEquals(18.5, route.model().latitude(0), 1e-9);
        assertEquals(18.6, route.model().latitude(1), 1e-9);
        assertTrue(route.diagnostics().rejectedInvalidCoordinates() >= 2);
    }

    @Test
    void isolatedGpsSpikeIsAGapNotDrivenDistance() {
        List<SignalSamplePoint> lat = List.of(
                sample(0, 18.5000),
                sample(SECOND, 18.5001),
                sample(2 * SECOND, 19.5000),
                sample(3 * SECOND, 18.5002));
        List<SignalSamplePoint> lon = List.of(
                sample(0, 73.7000),
                sample(SECOND, 73.7001),
                sample(2 * SECOND, 73.7002),
                sample(3 * SECOND, 73.7003));
        ValidatedRoute route = MapRouteBuilder.validateAndBuild(lat, lon, null);

        assertTrue(route.model().segmentBreakBefore(2));
        assertTrue(route.model().segmentBreakBefore(3));
        assertTrue(route.model().totalDistanceMeters() < 100);
        assertTrue(route.diagnostics().largeDiscontinuities() >= 2);
    }

    @Test
    void missingGpsIntervalIsPreservedAsABreakNotInventedRoad() {
        List<SignalSamplePoint> lat = List.of(sample(0, 18.50), sample(60 * SECOND, 18.5005));
        List<SignalSamplePoint> lon = List.of(sample(0, 73.70), sample(60 * SECOND, 73.7005));
        ValidatedRoute route = MapRouteBuilder.validateAndBuild(
                lat, lon, null, new MapRouteBuildOptions(60 * SECOND, 1_000, 5 * SECOND, 1.0));

        assertEquals(2, route.size());
        assertTrue(route.model().segmentBreakBefore(1));
        assertEquals(0.0, route.model().totalDistanceMeters(), 1e-9);
    }

    @Test
    void knownHaversineDistanceIsUsedForTheRoute() {
        double expected = GeoDistance.haversineMeters(12.9716, 77.5946, 12.9716, 77.6046);
        List<SignalSamplePoint> lat = List.of(sample(0, 12.9716), sample(10 * SECOND, 12.9716));
        List<SignalSamplePoint> lon = List.of(sample(0, 77.5946), sample(10 * SECOND, 77.6046));
        MapRouteModel model = MapRouteBuilder.build(
                lat, lon, null, new MapRouteBuildOptions(SECOND, 50_000, 60 * SECOND, 1.0));

        assertEquals(expected, model.totalDistanceMeters(), 1e-6);
        assertTrue(expected > 1_000 && expected < 1_200);
    }

    @Test
    void kilometrePerHourConversionIsDistanceOverDtTimes3Point6() {
        double meters = GeoDistance.haversineMeters(18.50, 73.80, 18.50, 73.801);
        List<SignalSamplePoint> lat = List.of(sample(0, 18.50), sample(SECOND, 18.50));
        List<SignalSamplePoint> lon = List.of(sample(0, 73.80), sample(SECOND, 73.801));
        MapRouteModel model = MapRouteBuilder.build(lat, lon, null);
        RouteRangeMetrics metrics = RouteRangeMetrics.compute(model, 0, 1, null);

        assertNotNull(metrics.maxSpeedKph());
        assertEquals((meters / 1.0) * 3.6, metrics.maxSpeedKph(), 1e-6);
        assertEquals((meters / 1.0) * 3.6, metrics.averageSpeedKph(), 1e-6);
    }

    @Test
    void averageSpeedIsTotalDistanceOverTotalDurationNotMeanOfInstantaneous() {
        List<SignalSamplePoint> lat = new ArrayList<>();
        List<SignalSamplePoint> lon = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            lat.add(sample(i * SECOND, 18.50));
            lon.add(sample(i * SECOND, 73.80 + i * 0.0002));
        }
        MapRouteModel model = MapRouteBuilder.build(lat, lon, null);
        RouteRangeMetrics metrics = RouteRangeMetrics.compute(model, 0, 10, null);
        double expected = (model.totalDistanceMeters() / 10.0) * 3.6;

        assertEquals(expected, metrics.averageSpeedKph(), 1e-6);
    }

    @Test
    void sparseTenSecondGpsStillAccumulatesDistanceUnderSamplingDerivedGap() {
        List<SignalSamplePoint> lat = new ArrayList<>();
        List<SignalSamplePoint> lon = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            lat.add(sample(i * 10 * SECOND, 13.0400 + i * 0.0008));
            lon.add(sample(i * 10 * SECOND, 77.5900 + i * 0.0008));
        }
        ValidatedRoute route = MapRouteBuilder.validateAndBuild(lat, lon, null);
        assertEquals(6, route.size());
        assertFalse(route.model().segmentBreakBefore(1));
        assertFalse(route.model().segmentBreakBefore(2));
        assertTrue(route.model().totalDistanceMeters() > 100);
        assertEquals(0, route.diagnostics().largeDiscontinuities());
    }

    @Test
    void oneHertzDropoutLongerThanFiveSecondsRemainsAGap() {
        List<SignalSamplePoint> lat = List.of(
                sample(0, 13.04),
                sample(SECOND, 13.0401),
                sample(31 * SECOND, 13.0402),
                sample(32 * SECOND, 13.0403));
        List<SignalSamplePoint> lon = List.of(
                sample(0, 77.59),
                sample(SECOND, 77.5901),
                sample(31 * SECOND, 77.5902),
                sample(32 * SECOND, 77.5903));
        ValidatedRoute route = MapRouteBuilder.validateAndBuild(lat, lon, null);
        assertTrue(route.model().segmentBreakBefore(2));
        assertFalse(route.model().segmentBreakBefore(1));
        assertFalse(route.model().segmentBreakBefore(3));
    }

    @Test
    void sparseLatitudePairsOntoDenserLongitudeByTimestamp() {
        List<SignalSamplePoint> lat = List.of(sample(0, 13.04), sample(2 * SECOND, 13.05));
        List<SignalSamplePoint> lon = List.of(
                sample(0, 77.59),
                sample(SECOND, 77.60),
                sample(2 * SECOND, 77.61));
        ValidatedRoute route = MapRouteBuilder.validateAndBuild(lat, lon, null);
        assertEquals(2, route.size());
        assertEquals(77.59, route.point(0).longitude(), 1e-9);
        assertEquals(77.61, route.point(1).longitude(), 1e-9);
    }

    @Test
    void onePointRouteHasNoDistance() {
        ValidatedRoute route = MapRouteBuilder.validateAndBuild(
                List.of(sample(0, 13.04)), List.of(sample(0, 77.59)), null);
        assertEquals(1, route.size());
        assertEquals(0.0, route.model().totalDistanceMeters(), 1e-9);
        RouteRangeMetrics metrics = RouteRangeMetrics.compute(route.model(), 0, 0, null);
        assertNull(metrics.averageSpeedKph());
    }

    @Test
    void noValidRouteWhenCoordinatesAreUnusable() {
        ValidatedRoute route = MapRouteBuilder.validateAndBuild(
                List.of(sample(0, 200.0)), List.of(sample(0, 400.0)), null);
        assertTrue(route.isEmpty());
        assertTrue(route.diagnostics().rejectedInvalidCoordinates() >= 1);
    }

    @Test
    void separateMasterTimelinesPairWithinSamplingTolerance() {
        List<SignalSamplePoint> lat = List.of(
                sample((long) (1.000 * SECOND), 18.5000),
                sample((long) (2.000 * SECOND), 18.5008));
        List<SignalSamplePoint> lon = List.of(
                sample((long) (1.040 * SECOND), 73.7000),
                sample((long) (2.040 * SECOND), 73.7008));
        ValidatedRoute route = MapRouteBuilder.validateAndBuild(lat, lon, null);
        assertEquals(2, route.size());
        assertEquals((long) (1.040 * SECOND), route.point(0).longitudeTimestampNanos());
        assertTrue(route.model().totalDistanceMeters() > 0);
    }

    @Test
    void rendererAndSegmentAnalysisConsumeTheSameValidatedModel() {
        List<SignalSamplePoint> lat = new ArrayList<>();
        List<SignalSamplePoint> lon = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            lat.add(sample(i * SECOND, 13.04 + i * 0.0001));
            lon.add(sample(i * SECOND, 77.59 + i * 0.0001));
        }
        ValidatedRoute validated = MapRouteBuilder.validateAndBuild(lat, lon, null);
        MapRouteModel renderer = validated.model();
        DriveSegmentSummary analysis = new DriveSegmentAnalysisService().analyze(
                new DriveSegmentAnalysisService.DriveSegmentAnalysisRequest(
                        renderer, 0, renderer.size() - 1, false, null, null, List.of(), List.of(),
                        null, 0));

        assertSame(renderer, validated.model());
        assertEquals(renderer.size(), analysis.motion().range().sampleCount());
        assertEquals(renderer.totalDistanceMeters(), analysis.motion().range().distanceMeters(), 1e-9);
        assertEquals(renderer.firstTimestampNanos(), analysis.startTimestampNanos());
        assertEquals(renderer.lastTimestampNanos(), analysis.endTimestampNanos());
    }

    @Test
    void tenMicrosecondCrossTrackHopIsAGapWhenDtIsNonPositiveAfterDedupe() {
        List<SignalSamplePoint> lat = List.of(
                sample(375_143_390_000L, 18.4438464),
                sample(375_143_400_000L, 18.5738437));
        List<SignalSamplePoint> lon = List.of(
                sample(375_143_390_000L, 73.5376964),
                sample(375_143_400_000L, 73.7639593));
        ValidatedRoute route = MapRouteBuilder.validateAndBuild(lat, lon, null);
        RouteRangeMetrics metrics = RouteRangeMetrics.compute(route.model(), 0, route.size() - 1, null);

        assertTrue(route.model().segmentBreakBefore(1), "27 km hop in 10 µs must not be driven distance");
        assertEquals(0.0, route.model().totalDistanceMeters(), 1e-9);
        assertTrue(metrics.maxSpeedKph() == null || metrics.maxSpeedKph() < 1_000);
    }

    @Test
    void subMillisecondHopUnderJumpLimitDoesNotExplodeSpeed() {
        // Reproduces the failing-dataset 336 m / 10 µs quotient (121 060 758 km/h) which was
        // counted because the step was below the 1 km geographic jump limit.
        List<SignalSamplePoint> lat = List.of(
                sample(375_143_390_000L, 18.4438464),
                sample(375_143_400_000L, 18.4465000));
        List<SignalSamplePoint> lon = List.of(
                sample(375_143_390_000L, 73.5376964),
                sample(375_143_400_000L, 73.5392000));
        ValidatedRoute route = MapRouteBuilder.validateAndBuild(lat, lon, null);
        RouteRangeMetrics metrics = RouteRangeMetrics.compute(route.model(), 0, route.size() - 1, null);
        double step = GeoDistance.haversineMeters(18.4438464, 73.5376964, 18.4465000, 73.5392000);

        assertTrue(step < 1_000.0, "fixture must stay under the geographic jump limit, got " + step);
        assertTrue(route.model().segmentBreakBefore(1));
        assertEquals(0.0, route.model().totalDistanceMeters(), 1e-9);
        assertTrue(metrics.maxSpeedKph() == null || metrics.maxSpeedKph() < 400);
        assertTrue(route.diagnostics().negativeOrZeroDt() >= 1);
    }
}
