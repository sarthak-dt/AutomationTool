package com.stellar.platform.processing.drive;

import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSamplePoint;
import com.stellar.platform.domain.catalog.SignalSourceType;
import com.stellar.platform.processing.graph.GraphSeriesQuery;
import com.stellar.platform.processing.map.MapRouteBuilder;
import com.stellar.platform.processing.map.MapRouteModel;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DriveSegmentAnalysisServiceTest {

    private static final long SECOND = 1_000_000_000L;

    @Test
    void defaultFullDriveUsesFirstAndLastGpsSample() {
        MapRouteModel route = movingRoute(21);
        DriveSegmentSummary summary = analyze(route, 0, 20, false, store(), List.of(), List.of());

        assertTrue(summary.fullDrive());
        assertEquals(route.firstTimestampNanos(), summary.startTimestampNanos());
        assertEquals(route.lastTimestampNanos(), summary.endTimestampNanos());
        assertEquals(20.0, summary.motion().range().durationSeconds(), 1e-9);
        assertTrue(summary.motion().range().distanceMeters() > 0);
        assertNotNull(summary.motion().range().averageSpeedKph());
        assertNotNull(summary.motion().range().maxSpeedKph());
    }

    @Test
    void averageSpeedIncludesStoppedTimeMovingAverageDoesNot() {
        MapRouteModel route = stopThenGo();
        DriveSegmentSummary summary = analyze(route, 0, route.size() - 1, false, store(), List.of(), List.of());

        assertTrue(summary.motion().stoppedDurationNanos() > 0);
        assertNotNull(summary.motion().range().averageSpeedKph());
        assertNotNull(summary.motion().movingAverageSpeedKph());
        assertTrue(summary.motion().movingAverageSpeedKph() > summary.motion().range().averageSpeedKph());
    }

    @Test
    void fuelRateWithKnownUnitsIsIntegrated() {
        MapRouteModel route = movingRoute(11);
        FakeQuery store = store();
        List<SignalSamplePoint> rate = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            rate.add(new SignalSamplePoint(i * SECOND, 3.6, null, null));
        }
        SignalCatalogEntry fuelRate = entry("Fuel_Rate", "L/h");
        store.put(fuelRate.key(), "L/h", rate);

        DriveSegmentSummary summary = analyze(route, 0, 10, true, store, List.of(fuelRate), List.of());

        assertTrue(summary.fuel().displayable());
        assertEquals(0.01, summary.fuel().fuelUsedLiters(), 1e-6);
        assertNotNull(summary.fuel().fuelEconomyKmPerLiter());
    }

    @Test
    void missingFuelSignalIsNotAvailableNotZero() {
        MapRouteModel route = movingRoute(5);
        DriveSegmentSummary summary = analyze(route, 0, 4, true, store(), List.of(), List.of());

        assertFalse(summary.fuel().displayable());
        assertNull(summary.fuel().fuelUsedLiters());
        assertEquals("Not available", summary.fuel().displayLabel());
        assertEquals(DriveFuelSummary.Availability.NOT_AVAILABLE, summary.fuel().availability());
    }

    @Test
    void unknownFuelUnitsDoNotFabricateAMetric() {
        MapRouteModel route = movingRoute(5);
        FakeQuery store = store();
        SignalCatalogEntry fuelRate = entry("Fuel_Rate", "ticks");
        store.put(fuelRate.key(), "ticks", List.of(
                new SignalSamplePoint(0, 10, null, null),
                new SignalSamplePoint(4 * SECOND, 10, null, null)));

        DriveSegmentSummary summary = analyze(route, 0, 4, true, store, List.of(fuelRate), List.of());

        assertEquals(DriveFuelSummary.Availability.UNKNOWN_UNITS, summary.fuel().availability());
        assertNull(summary.fuel().fuelUsedLiters());
        assertEquals("Not available", summary.fuel().displayLabel());
    }

    @Test
    void tankLevelIsNotAuthoritativeFuelUsed() {
        MapRouteModel route = movingRoute(5);
        FakeQuery store = store();
        SignalCatalogEntry level = entry("Fuel_Level", "%");
        store.put(level.key(), "%", List.of(
                new SignalSamplePoint(0, 80, null, null),
                new SignalSamplePoint(4 * SECOND, 70, null, null)));

        DriveSegmentSummary summary = analyze(route, 0, 4, true, store, List.of(level), List.of());

        assertEquals(DriveFuelSummary.Availability.TANK_LEVEL_UNRELIABLE, summary.fuel().availability());
        assertNull(summary.fuel().fuelUsedLiters());
    }

    @Test
    void committedEndpointTimestampsAreTheAnalysisInterval() {
        MapRouteModel route = movingRoute(601);
        long start = route.timestampNanos(100);
        long end = route.timestampNanos(500);
        DriveSegmentSummary summary = new DriveSegmentAnalysisService().analyze(
                new DriveSegmentAnalysisService.DriveSegmentAnalysisRequest(
                        route, 100, 500, start, end, true, null, store(), List.of(), List.of(),
                        DriveSegmentThresholds.defaults(), 0));

        assertEquals(start, summary.startTimestampNanos());
        assertEquals(end, summary.endTimestampNanos());
        assertEquals(end - start, summary.motion().range().durationNanos());
        assertTrue(summary.motion().range().durationNanos() > 0);
        assertTrue(summary.motion().range().distanceMeters() > 0);
        assertNotNull(summary.motion().range().averageSpeedKph());
        assertNotNull(summary.motion().range().maxSpeedKph());
        assertFalse(summary.fullDrive());
    }

    @Test
    void mappedBrakeSignalIsMeasuredNotInferred() {
        MapRouteModel route = movingRoute(11);
        FakeQuery store = store();
        SignalCatalogEntry brake = entry("Brake_Pedal", "%");
        List<SignalSamplePoint> samples = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            samples.add(new SignalSamplePoint(i * SECOND, i >= 3 && i <= 5 ? 40 : 0, null, null));
        }
        store.put(brake.key(), "%", samples);

        DriveSegmentSummary summary = analyze(route, 0, 10, true, store, List.of(brake), List.of());

        assertEquals(DriveBrakingSummary.Classification.MEASURED, summary.braking().classification());
        assertEquals(1, summary.braking().eventCount());
        assertEquals("Braking events", summary.braking().eventLabel());
        assertTrue(summary.braking().activeSeconds() > 0);
    }

    @Test
    void decelerationWithoutBrakeSignalIsInferred() {
        MapRouteModel route = hardDecelRoute();
        DriveSegmentSummary summary = analyze(route, 0, route.size() - 1, true, store(), List.of(), List.of());

        assertEquals(DriveBrakingSummary.Classification.INFERRED, summary.braking().classification());
        assertTrue(summary.braking().eventCount() >= 1);
        assertEquals("Inferred braking events", summary.braking().eventLabel());
    }

    @Test
    void missingSamplesAreReportedWithEvidence() {
        MapRouteModel route = movingRoute(11);
        FakeQuery store = store();
        SignalCatalogEntry wheel = entry("WheelSpeed_FL", "km/h");
        store.put(wheel.key(), "km/h", List.of(
                new SignalSamplePoint(0, 40, null, null),
                new SignalSamplePoint(SECOND, 41, null, null),
                new SignalSamplePoint(2 * SECOND, 42, null, null)));

        DriveSegmentSummary summary = analyze(route, 0, 10, true, store, List.of(wheel), List.of(wheel.key()));

        assertTrue(summary.health().count() >= 1);
        assertTrue(summary.health().issues().stream()
                .anyMatch(issue -> issue.kind() == DriveSignalIssueKind.MISSING_SAMPLES
                        && issue.evidence().contains("Missing data")));
    }

    @Test
    void invalidStatusIsReportedSeparatelyFromInference() {
        MapRouteModel route = movingRoute(5);
        FakeQuery store = store();
        SignalCatalogEntry steering = entry("Steering_Angle", "deg");
        store.put(steering.key(), "deg", List.of(
                new SignalSamplePoint(0, 1, null, "ok"),
                new SignalSamplePoint(2 * SECOND, Double.NaN, null, null, com.stellar.platform.domain.SignalValidity.CONVERSION_FAILED),
                new SignalSamplePoint(4 * SECOND, 2, null, "invalid", com.stellar.platform.domain.SignalValidity.INVALID_RAW_CODE)));

        DriveSegmentSummary summary = analyze(route, 0, 4, true, store, List.of(steering), List.of(steering.key()));

        assertTrue(summary.health().issues().stream()
                .anyMatch(issue -> issue.kind() == DriveSignalIssueKind.INVALID_SIGNAL && !issue.inferred()));
    }

    @Test
    void flatlineIsClassifiedWithDurationEvidence() {
        MapRouteModel route = movingRoute(11);
        FakeQuery store = store();
        SignalCatalogEntry pressure = entry("Fuel_Pressure", "kPa");
        List<SignalSamplePoint> samples = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            samples.add(new SignalSamplePoint(i * SECOND, 250.0, null, null));
        }
        store.put(pressure.key(), "kPa", samples);

        DriveSegmentSummary summary = analyze(route, 0, 10, true, store, List.of(pressure), List.of(pressure.key()));

        assertTrue(summary.health().issues().stream()
                .anyMatch(issue -> issue.kind() == DriveSignalIssueKind.STALE_FLATLINE
                        && issue.evidence().contains("Flatline")));
    }

    @Test
    void healthyVaryingSignalDoesNotReportSensorFailure() {
        MapRouteModel route = movingRoute(11);
        FakeQuery store = store();
        SignalCatalogEntry rpm = entry("Engine_RPM", "rpm");
        List<SignalSamplePoint> samples = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            samples.add(new SignalSamplePoint(i * SECOND, 800 + i * 50, null, null));
        }
        store.put(rpm.key(), "rpm", samples);

        DriveSegmentSummary summary = analyze(route, 0, 10, true, store, List.of(rpm), List.of(rpm.key()));

        assertEquals(0, summary.health().count());
        assertTrue(summary.health().issues().stream()
                .noneMatch(issue -> issue.kind() == DriveSignalIssueKind.CONFIRMED_FAULT));
    }

    @Test
    void analysisQueriesIndexedSeriesAndDoesNotReparseFiles() {
        MapRouteModel route = movingRoute(5);
        FakeQuery store = store();
        SignalCatalogEntry brake = entry("Brake_Switch", "");
        store.put(brake.key(), "", List.of(
                new SignalSamplePoint(0, 0, null, null),
                new SignalSamplePoint(4 * SECOND, 1, null, null)));

        analyze(route, 0, 4, true, store, List.of(brake), List.of(brake.key()));

        assertTrue(store.reads.get() > 0);
        assertEquals(0, store.fileParses.get());
        assertEquals(0, store.dbcParses.get());
    }

    @Test
    void optionalAnalyzerFailureDoesNotDropCanonicalRouteDistance() {
        MapRouteModel route = movingRoute(8);
        GraphSeriesQuery throwing = new GraphSeriesQuery() {
            @Override
            public List<SignalSamplePoint> queryRange(SignalCatalogKey key, long startNanos, long endNanos) {
                throw new java.util.concurrent.CancellationException("Obsolete graph query");
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
        DriveSegmentSummary summary = new DriveSegmentAnalysisService().analyze(
                new DriveSegmentAnalysisService.DriveSegmentAnalysisRequest(
                        route, 0, route.size() - 1, false, null, throwing,
                        List.of(entry("Brake_Switch", "")), List.of(),
                        DriveSegmentThresholds.defaults(), 0));
        assertTrue(summary.motion().range().distanceMeters() > 0);
        assertEquals(route.totalDistanceMeters(), summary.motion().range().distanceMeters(), 1e-9);
    }

    private static DriveSegmentSummary analyze(MapRouteModel route,
                                               int a,
                                               int b,
                                               boolean userDefined,
                                               FakeQuery query,
                                               List<SignalCatalogEntry> catalog,
                                               List<SignalCatalogKey> selected) {
        return new DriveSegmentAnalysisService().analyze(new DriveSegmentAnalysisService.DriveSegmentAnalysisRequest(
                route, a, b, userDefined, null, query, catalog, selected,
                DriveSegmentThresholds.defaults(), 0));
    }

    private static MapRouteModel movingRoute(int count) {
        List<SignalSamplePoint> lat = new ArrayList<>(count);
        List<SignalSamplePoint> lon = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            lat.add(new SignalSamplePoint(i * SECOND, 12.97, null, null));
            lon.add(new SignalSamplePoint(i * SECOND, 77.59 + i * 0.0002, null, null));
        }
        return MapRouteBuilder.build(lat, lon, null);
    }

    private static MapRouteModel stopThenGo() {
        List<SignalSamplePoint> lat = new ArrayList<>();
        List<SignalSamplePoint> lon = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            lat.add(new SignalSamplePoint(i * SECOND, 12.97, null, null));
            lon.add(new SignalSamplePoint(i * SECOND, 77.59, null, null));
        }
        for (int i = 6; i < 16; i++) {
            lat.add(new SignalSamplePoint(i * SECOND, 12.97, null, null));
            lon.add(new SignalSamplePoint(i * SECOND, 77.59 + (i - 5) * 0.0003, null, null));
        }
        return MapRouteBuilder.build(lat, lon, null);
    }

    private static MapRouteModel hardDecelRoute() {
        List<SignalSamplePoint> lat = new ArrayList<>();
        List<SignalSamplePoint> lon = new ArrayList<>();
        double speedKph = 80;
        double lonValue = 77.59;
        for (int i = 0; i < 12; i++) {
            lat.add(new SignalSamplePoint(i * SECOND, 12.97, null, null));
            lon.add(new SignalSamplePoint(i * SECOND, lonValue, null, null));
            double mps = speedKph / 3.6;
            lonValue += (mps * 1.0) / 111_320.0;
            speedKph = i < 4 ? 80 : Math.max(0, speedKph - 18);
        }
        return MapRouteBuilder.build(lat, lon, null);
    }

    private static SignalCatalogEntry entry(String name, String unit) {
        SignalCatalogKey key = new SignalCatalogKey(name, SignalSourceType.DBC_CAN, "Drive", 1);
        return new SignalCatalogEntry(key, unit, 10, 0L, 10 * SECOND, null, null, "drive.mf4", "veh.dbc");
    }

    private static FakeQuery store() {
        return new FakeQuery();
    }

    private static final class FakeQuery implements GraphSeriesQuery {
        private final Map<SignalCatalogKey, List<SignalSamplePoint>> data = new HashMap<>();
        private final Map<SignalCatalogKey, String> units = new HashMap<>();
        final AtomicInteger reads = new AtomicInteger();
        final AtomicInteger fileParses = new AtomicInteger();
        final AtomicInteger dbcParses = new AtomicInteger();

        void put(SignalCatalogKey key, String unit, List<SignalSamplePoint> samples) {
            data.put(key, samples);
            units.put(key, unit);
        }

        @Override
        public List<SignalSamplePoint> queryRange(SignalCatalogKey key, long startNanos, long endNanos) {
            reads.incrementAndGet();
            List<SignalSamplePoint> all = data.getOrDefault(key, List.of());
            List<SignalSamplePoint> out = new ArrayList<>();
            for (SignalSamplePoint sample : all) {
                if (sample.timestampNanos() >= startNanos && sample.timestampNanos() <= endNanos) {
                    out.add(sample);
                }
            }
            return out;
        }

        @Override
        public String unit(SignalCatalogKey key) {
            return units.getOrDefault(key, "");
        }

        @Override
        public long sampleCount(SignalCatalogKey key) {
            return data.getOrDefault(key, List.of()).size();
        }
    }
}
