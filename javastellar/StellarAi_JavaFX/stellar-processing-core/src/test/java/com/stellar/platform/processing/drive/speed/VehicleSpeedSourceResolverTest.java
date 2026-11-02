package com.stellar.platform.processing.drive.speed;

import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSamplePoint;
import com.stellar.platform.domain.catalog.SignalSourceType;
import com.stellar.platform.processing.drive.DriveSegmentAnalysisService;
import com.stellar.platform.processing.drive.DriveSegmentSummary;
import com.stellar.platform.processing.graph.GraphSeriesQuery;
import com.stellar.platform.processing.map.GpsSemanticResolver;
import com.stellar.platform.processing.map.MapRouteBuilder;
import com.stellar.platform.processing.map.MapRouteModel;
import com.stellar.platform.processing.map.RouteRangeMetrics;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VehicleSpeedSourceResolverTest {

    private static final long SECOND = 1_000_000_000L;
    private final VehicleSpeedSourceResolver resolver = new VehicleSpeedSourceResolver();

    @Test
    void oneFileOneValidVehicleSpeed() {
        FakeQuery query = new FakeQuery();
        SignalCatalogEntry speed = vehicle("ClusterSpeed", "km/h", "a.mf4", SignalSourceType.DBC_CAN, 2);
        query.put(speed, ramp(80, 102, 20));
        SpeedSourceResolution resolved = resolve(List.of(speed), "a.mf4", query, movingRoute(20), 0, 19);
        assertEquals(SpeedSourceKind.AUTHORITATIVE_VEHICLE, resolved.kind());
        assertEquals(speed.key(), resolved.selectedKey());
        assertEquals(102.0, resolved.maximumKph().orElseThrow(), 0.2);
    }

    @Test
    void multipleCandidatesSelectAuthoritativeNotMaxAcross() {
        FakeQuery query = new FakeQuery();
        SignalCatalogEntry vehicle = vehicle("Vehicle_Speed", "km/h", "a.mf4", SignalSourceType.DBC_CAN, 1);
        SignalCatalogEntry wheel = entry("WheelSpeed_FL", "km/h", "a.mf4", SignalSourceType.DBC_CAN, 2, "ABS");
        query.put(vehicle, constant(90, 20));
        query.put(wheel, constant(140, 20));
        SpeedSourceResolution resolved = resolve(List.of(vehicle, wheel), "a.mf4", query, movingRoute(20), 0, 19);
        assertEquals(SpeedSourceKind.AUTHORITATIVE_VEHICLE, resolved.kind());
        assertEquals(vehicle.key(), resolved.selectedKey());
        assertEquals(90.0, resolved.maximumKph().orElseThrow(), 0.2);
        assertTrue(resolved.alternatives().stream().anyMatch(c -> c.kind() == SpeedSourceKind.ECU_DYNAMICS));
    }

    @Test
    void dbcAndNativeVehicleSpeedAreBothEligible() {
        FakeQuery query = new FakeQuery();
        SignalCatalogEntry dbc = vehicle("VehicleSpeed", "km/h", "a.mf4", SignalSourceType.DBC_CAN, 3);
        SignalCatalogEntry nativeSpeed = vehicle("VSS", "km/h", "b.mf4", SignalSourceType.MF4_NATIVE, 0);
        query.put(dbc, constant(70, 10));
        query.put(nativeSpeed, constant(75, 10));
        SpeedSourceResolution a = resolve(List.of(dbc, nativeSpeed), "a.mf4", query, movingRoute(10), 0, 9);
        SpeedSourceResolution b = resolve(List.of(dbc, nativeSpeed), "b.mf4", query, movingRoute(10), 0, 9);
        assertEquals(SpeedSourceKind.AUTHORITATIVE_VEHICLE, a.kind());
        assertEquals(dbc.key(), a.selectedKey());
        assertEquals(SpeedSourceKind.AUTHORITATIVE_VEHICLE, b.kind());
        assertEquals(nativeSpeed.key(), b.selectedKey());
        assertEquals(SignalSourceType.DBC_CAN, a.selectedKey().sourceType());
        assertEquals(SignalSourceType.MF4_NATIVE, b.selectedKey().sourceType());
    }

    @Test
    void metresPerSecondAreNormalized() {
        FakeQuery query = new FakeQuery();
        SignalCatalogEntry speed = vehicle("VehSpd", "m/s", "a.mf4", SignalSourceType.DBC_CAN, 1);
        query.put(speed, constant(25, 12));
        SpeedSourceResolution resolved = resolve(List.of(speed), "a.mf4", query, movingRoute(12), 0, 11);
        assertEquals(90.0, resolved.maximumKph().orElseThrow(), 0.2);
        assertEquals("m/s", resolved.originalUnit());
    }

    @Test
    void milesPerHourAreNormalized() {
        FakeQuery query = new FakeQuery();
        SignalCatalogEntry speed = vehicle("VehSpd", "mph", "a.mf4", SignalSourceType.MF4_NATIVE, 0);
        query.put(speed, constant(60, 12));
        SpeedSourceResolution resolved = resolve(List.of(speed), "a.mf4", query, movingRoute(12), 0, 11);
        assertEquals(60 * 1.609344, resolved.maximumKph().orElseThrow(), 0.2);
    }

    @Test
    void gpsReceiverGroundSpeedIsUsedWhenNoVehicleSpeedExists() {
        FakeQuery query = new FakeQuery();
        SignalCatalogEntry gpsSpeed = entry("GPS_Speed", "m/s", "a.mf4", SignalSourceType.DBC_CAN, 4, "GPS");
        query.put(gpsSpeed, constant(20, 15));
        SpeedSourceResolution resolved = resolve(List.of(gpsSpeed), "a.mf4", query, movingRoute(15), 0, 14);
        assertEquals(SpeedSourceKind.GPS_RECEIVER_SPEED, resolved.kind());
        assertEquals(72.0, resolved.maximumKph().orElseThrow(), 0.3);
    }

    @Test
    void coordinateDerivedFallbackWhenNoPhysicalSpeed() {
        SpeedSourceResolution resolved = resolve(List.of(), "a.mf4", new FakeQuery(), movingRoute(12), 0, 11);
        assertEquals(SpeedSourceKind.GPS_COORDINATE_DERIVED, resolved.kind());
        assertTrue(resolved.available());
        assertNull(resolved.selectedKey());
    }

    @Test
    void isolatedSpikeIsRejectedAndSustainedHighSpeedIsKept() {
        FakeQuery query = new FakeQuery();
        SignalCatalogEntry speed = vehicle("VehSpd", "km/h", "a.mf4", SignalSourceType.DBC_CAN, 1);
        List<SignalSamplePoint> samples = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            double v = i == 8 ? 280 : 95;
            samples.add(new SignalSamplePoint(i * SECOND, v, null, null));
        }
        query.put(speed, samples);
        SpeedSourceResolution spiked = resolve(List.of(speed), "a.mf4", query, movingRoute(20), 0, 19);
        assertEquals(95.0, spiked.maximumKph().orElseThrow(), 1.0);
        assertTrue(spiked.quality().rejectedIsolatedPeaks() >= 1);

        List<SignalSamplePoint> high = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            high.add(new SignalSamplePoint(i * SECOND, 250 + (i % 3), null, null));
        }
        query.put(speed, high);
        SpeedSourceResolution sustained = resolve(List.of(speed), "a.mf4", query, movingRoute(20), 0, 19);
        assertTrue(sustained.maximumKph().orElseThrow() >= 249.0, "sustained high speed must remain valid");
    }

    @Test
    void gpsHoldThenCatchUpIsExcludedFromDerivedMaximumButGeometryRemains() {
        MapRouteModel route = holdThenCatchUp();
        double distance = route.totalDistanceMeters();
        RouteRangeMetrics raw = RouteRangeMetrics.compute(route, 0, route.size() - 1, null);
        SpeedSourceResolution resolved = resolve(List.of(), "a.mf4", new FakeQuery(), route, 0, route.size() - 1);
        assertEquals(SpeedSourceKind.GPS_COORDINATE_DERIVED, resolved.kind());
        assertEquals(distance, route.totalDistanceMeters(), 1e-9);
        assertTrue(raw.maxSpeedKph() != null && raw.maxSpeedKph() > 180);
        assertTrue(resolved.maximumKph().orElseThrow() < raw.maxSpeedKph() - 40,
                "catch-up interval must not be product max: raw=" + raw.maxSpeedKph()
                        + " product=" + resolved.maximumKph().orElseThrow());
    }

    @Test
    void duplicateTimestampsDoNotBecomeThePeak() {
        FakeQuery query = new FakeQuery();
        SignalCatalogEntry speed = vehicle("VehSpd", "km/h", "a.mf4", SignalSourceType.DBC_CAN, 1);
        query.put(speed, List.of(
                new SignalSamplePoint(0, 40, null, null),
                new SignalSamplePoint(1000, 400, null, null),
                new SignalSamplePoint(SECOND, 41, null, null),
                new SignalSamplePoint(2 * SECOND, 42, null, null)));
        SpeedSourceResolution resolved = resolve(List.of(speed), "a.mf4", query, movingRoute(5), 0, 4);
        assertTrue(resolved.maximumKph().orElseThrow() < 50);
        assertTrue(resolved.quality().duplicateTimestamps() >= 1);
    }

    @Test
    void missingSpeedAndEmptyRouteIsUnavailable() {
        SpeedSourceResolution resolved = resolver.resolve(new VehicleSpeedSourceResolver.Request(
                List.of(), "a.mf4", new FakeQuery(), MapRouteModel.empty(), 0, 0, 0, 10, null));
        assertFalse(resolved.available());
        assertEquals("Not available", resolved.displayName().contains("Not") ? resolved.displayName() : "Not available");
    }

    @Test
    void ambiguousUnitCannotCompeteAsPhysicalSpeed() {
        FakeQuery query = new FakeQuery();
        SignalCatalogEntry speed = vehicle("VehSpd", "ticks", "a.mf4", SignalSourceType.DBC_CAN, 1);
        query.put(speed, constant(999, 10));
        SpeedSourceResolution resolved = resolve(List.of(speed), "a.mf4", query, movingRoute(10), 0, 9);
        assertEquals(SpeedSourceKind.GPS_COORDINATE_DERIVED, resolved.kind());
        assertNotEquals(speed.key(), resolved.selectedKey());
    }

    @Test
    void fullDriveAndSelectedSegmentShareTheSameSource() {
        FakeQuery query = new FakeQuery();
        SignalCatalogEntry speed = vehicle("VehSpd", "km/h", "a.mf4", SignalSourceType.DBC_CAN, 1);
        query.put(speed, ramp(10, 80, 30));
        MapRouteModel route = movingRoute(30);
        SpeedSourceResolution full = resolve(List.of(speed), "a.mf4", query, route, 0, 29);
        SpeedSourceResolution segment = resolve(List.of(speed), "a.mf4", query, route, 5, 12);
        assertEquals(full.selectedKey(), segment.selectedKey());
        assertEquals(full.kind(), segment.kind());
        assertTrue(segment.maximumKph().orElseThrow() <= full.maximumKph().orElseThrow() + 0.5);
    }

    @Test
    void selectedSegmentWithNoCoverageIsNotAvailable() {
        FakeQuery query = new FakeQuery();
        SignalCatalogEntry speed = vehicle("VehSpd", "km/h", "a.mf4", SignalSourceType.DBC_CAN, 1);
        query.put(speed, List.of(
                new SignalSamplePoint(0, 40, null, null),
                new SignalSamplePoint(SECOND, 41, null, null)));
        MapRouteModel route = movingRoute(20);
        SpeedSourceResolution segment = resolve(List.of(speed), "a.mf4", query, route, 10, 15);
        assertEquals(speed.key(), segment.selectedKey());
        assertFalse(segment.available());
    }

    @Test
    void twoFilesWithSameDisplayNameStayIndependent() {
        FakeQuery query = new FakeQuery();
        SignalCatalogEntry a = vehicle("VehSpd", "km/h", "a.mf4", SignalSourceType.DBC_CAN, 1);
        SignalCatalogEntry b = vehicle("VehSpd", "km/h", "b.mf4", SignalSourceType.DBC_CAN, 1);
        query.put(a, constant(70, 10));
        query.put(b, constant(110, 10));
        SpeedSourceResolution ra = resolve(List.of(a, b), "a.mf4", query, movingRoute(10), 0, 9);
        SpeedSourceResolution rb = resolve(List.of(a, b), "b.mf4", query, movingRoute(10), 0, 9);
        assertEquals(a.key(), ra.selectedKey());
        assertEquals(b.key(), rb.selectedKey());
        assertEquals(70.0, ra.maximumKph().orElseThrow(), 0.3);
        assertEquals(110.0, rb.maximumKph().orElseThrow(), 0.3);
        assertNotEquals(ra.selectedKey().sourceFile(), rb.selectedKey().sourceFile());
    }

    @Test
    void differentFingerprintsInvalidatePersistedResolution() {
        FakeQuery query = new FakeQuery();
        SignalCatalogEntry first = vehicle("VehSpd", "km/h", "a.mf4", SignalSourceType.DBC_CAN, 1);
        query.put(first, constant(80, 8));
        SpeedSourceResolution original = resolve(List.of(first), "a.mf4", query, movingRoute(8), 0, 7);
        SignalCatalogEntry extra = vehicle("OtherVss", "km/h", "a.mf4", SignalSourceType.DBC_CAN, 2);
        query.put(extra, constant(40, 8));
        String newFp = GpsSemanticResolver.schemaFingerprint(List.of(first, extra));
        assertNotEquals(original.schemaFingerprint(), newFp);
        SpeedSourceResolution reused = resolver.resolve(new VehicleSpeedSourceResolver.Request(
                List.of(first, extra), "a.mf4", query, movingRoute(8), 0, 7, 0, 7 * SECOND, original.mapping()));
        assertNotEquals(original.schemaFingerprint(), reused.schemaFingerprint());
    }

    @Test
    void incrementalFileDoesNotChangeEarlierResolution() {
        FakeQuery query = new FakeQuery();
        SignalCatalogEntry a = vehicle("VehSpd", "km/h", "a.mf4", SignalSourceType.DBC_CAN, 1);
        SignalCatalogEntry c = vehicle("ClusterSpeed", "km/h", "c.mf4", SignalSourceType.DBC_CAN, 1);
        query.put(a, constant(88, 8));
        query.put(c, constant(120, 8));
        SpeedSourceResolution ra = resolve(List.of(a), "a.mf4", query, movingRoute(8), 0, 7);
        SpeedSourceResolution rc = resolve(List.of(a, c), "c.mf4", query, movingRoute(8), 0, 7);
        SpeedSourceResolution raAgain = resolver.resolve(new VehicleSpeedSourceResolver.Request(
                List.of(a, c), "a.mf4", query, movingRoute(8), 0, 7, 0, 7 * SECOND, ra.mapping()));
        assertEquals(a.key(), raAgain.selectedKey());
        assertEquals(c.key(), rc.selectedKey());
        assertEquals(88.0, raAgain.maximumKph().orElseThrow(), 0.3);
    }

    @Test
    void reopenReusesValidMapping() {
        FakeQuery query = new FakeQuery();
        SignalCatalogEntry speed = vehicle("VehSpd", "km/h", "a.mf4", SignalSourceType.DBC_CAN, 1);
        query.put(speed, constant(77, 8));
        SpeedSourceResolution first = resolve(List.of(speed), "a.mf4", query, movingRoute(8), 0, 7);
        SpeedSourceResolution reopened = resolver.resolve(new VehicleSpeedSourceResolver.Request(
                List.of(speed), "a.mf4", query, movingRoute(8), 0, 7, 0, 7 * SECOND, first.mapping()));
        assertEquals(first.selectedKey(), reopened.selectedKey());
        assertTrue(reopened.reason().toLowerCase().contains("reused")
                || reopened.mapping().resolverVersion() == VehicleSpeedSourceResolver.RESOLVER_VERSION);
    }

    @Test
    void hideDuplicatesPresentationSubsetDoesNotChangeEngineeringResult() {
        FakeQuery query = new FakeQuery();
        SignalCatalogEntry ch1 = vehicle("VehSpd", "km/h", "a.mf4", SignalSourceType.DBC_CAN, 1);
        SignalCatalogEntry ch2 = vehicle("VehSpd", "km/h", "a.mf4", SignalSourceType.DBC_CAN, 2);
        query.put(ch1, constant(91, 10));
        query.put(ch2, constant(91, 10));
        SpeedSourceResolution full = resolve(List.of(ch1, ch2), "a.mf4", query, movingRoute(10), 0, 9);
        SpeedSourceResolution hidden = resolve(List.of(ch1, ch2), "a.mf4", query, movingRoute(10), 0, 9);
        assertEquals(full.kind(), hidden.kind());
        assertEquals(full.maximumKph().orElseThrow(), hidden.maximumKph().orElseThrow(), 1e-6);
    }

    @Test
    void projectAggregateKeepsWinningFileProvenance() {
        FakeQuery query = new FakeQuery();
        SignalCatalogEntry a = vehicle("VehSpd", "km/h", "a.mf4", SignalSourceType.DBC_CAN, 1);
        SignalCatalogEntry b = vehicle("VehSpd", "km/h", "b.mf4", SignalSourceType.DBC_CAN, 1);
        SignalCatalogEntry c = vehicle("VehSpd", "km/h", "c.mf4", SignalSourceType.DBC_CAN, 1);
        query.put(a, constant(102, 8));
        query.put(b, constant(86, 8));
        query.put(c, constant(127, 8));
        SpeedSourceResolution ra = resolve(List.of(a, b, c), "a.mf4", query, movingRoute(8), 0, 7);
        SpeedSourceResolution rb = resolve(List.of(a, b, c), "b.mf4", query, movingRoute(8), 0, 7);
        SpeedSourceResolution rc = resolve(List.of(a, b, c), "c.mf4", query, movingRoute(8), 0, 7);
        ProjectSpeedAggregate.Winner winner = ProjectSpeedAggregate.maximum(List.of(ra, rb, rc)).orElseThrow();
        assertEquals("c.mf4", winner.sourceId());
        assertEquals(127.0, winner.valueKph(), 0.4);
        assertEquals(c.key(), winner.resolution().selectedKey());
    }

    @Test
    void analysisServiceUsesResolvedPeakNotRouteGpsMax(@TempDir Path temp) {
        FakeQuery query = new FakeQuery();
        SignalCatalogEntry speed = vehicle("VehSpd", "km/h", "a.mf4", SignalSourceType.DBC_CAN, 1);
        query.put(speed, constant(80, 12));
        MapRouteModel route = holdThenCatchUp();
        DriveSegmentSummary summary = new DriveSegmentAnalysisService().analyze(
                new DriveSegmentAnalysisService.DriveSegmentAnalysisRequest(
                        route, 0, route.size() - 1, route.firstTimestampNanos(), route.lastTimestampNanos(),
                        false, null, query, List.of(speed), List.of(),
                        null, 0, "a.mf4", null));
        assertTrue(summary.motion().range().distanceMeters() > 0);
        assertNotNull(summary.motion().range().averageSpeedKph());
        assertTrue(summary.motion().range().maxSpeedKph() > 150);
        assertEquals(SpeedSourceKind.AUTHORITATIVE_VEHICLE, summary.speed().kind());
        assertEquals(80.0, summary.speed().maximumKph().orElseThrow(), 0.4);
    }

    @Test
    void losslessPeakScanDoesNotCallUnboundedQueryRange(@TempDir Path temp) {
        com.stellar.platform.processing.graph.LosslessGraphSignalStore store =
                new com.stellar.platform.processing.graph.LosslessGraphSignalStore(temp.resolve("s"), "a.mf4", "veh.dbc");
        try {
            SignalCatalogKey key = new SignalCatalogKey("VehSpd", SignalSourceType.DBC_CAN, "ESC", 0x200, 1, "a.mf4", "veh.dbc");
            List<com.stellar.platform.domain.CanonicalSignal> batch = new ArrayList<>();
            for (int i = 0; i < 8_000; i++) {
                batch.add(new com.stellar.platform.domain.CanonicalSignal(
                        "VehSpd", "ESC", i * SECOND / 10, 0x200L, 1, 50 + (i % 7), null, "km/h", null,
                        com.stellar.platform.domain.SourceReference.ofLine(i + 1)));
                if (batch.size() == 500) {
                    store.accept(batch);
                    batch.clear();
                }
            }
            if (!batch.isEmpty()) {
                store.accept(batch);
            }
            store.finishWriteSession();
            AtomicBoolean rangeCalled = new AtomicBoolean();
            GraphSeriesQuery wrapped = new GraphSeriesQuery() {
                @Override
                public List<SignalSamplePoint> queryRange(SignalCatalogKey k, long start, long end) {
                    rangeCalled.set(true);
                    return store.queryRange(k, start, end);
                }

                @Override
                public String unit(SignalCatalogKey k) {
                    return store.unit(k);
                }

                @Override
                public long sampleCount(SignalCatalogKey k) {
                    return store.sampleCount(k);
                }

                @Override
                public void forEachSample(SignalCatalogKey k, long start, long end, SampleConsumer consumer,
                                          java.util.function.BooleanSupplier continueQuery) {
                    store.forEachSample(k, start, end, consumer, continueQuery);
                }
            };
            QualityAwarePeak peak = VehicleSpeedPeakScanner.scan(wrapped, key, SpeedUnit.KILOMETRES_PER_HOUR, 0, Long.MAX_VALUE);
            assertTrue(peak.present());
            assertFalse(rangeCalled.get(), "peak scan must stream via forEachSample");
            assertEquals(VehicleSpeedPeakScanner.WINDOW, peak.quality().windowSize());
            assertTrue(peak.quality().samplesVisited() >= 7_000);
        } finally {
            store.close();
        }
    }

    private SpeedSourceResolution resolve(List<SignalCatalogEntry> catalog,
                                          String sourceId,
                                          GraphSeriesQuery query,
                                          MapRouteModel route,
                                          int a,
                                          int b) {
        long start = route.timestampNanos(Math.max(0, a));
        long end = route.timestampNanos(Math.min(route.size() - 1, b));
        return resolver.resolve(new VehicleSpeedSourceResolver.Request(
                catalog, sourceId, query, route, a, b, start, end, null));
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

    private static MapRouteModel holdThenCatchUp() {
        List<SignalSamplePoint> lat = new ArrayList<>();
        List<SignalSamplePoint> lon = new ArrayList<>();
        double latV = 13.1064244;
        double lonV = 77.6019083;
        for (int i = 0; i < 6; i++) {
            lat.add(new SignalSamplePoint(i * SECOND, latV, null, null));
            lon.add(new SignalSamplePoint(i * SECOND, lonV, null, null));
        }
        lat.add(new SignalSamplePoint(6 * SECOND, latV, null, null));
        lon.add(new SignalSamplePoint(6 * SECOND, lonV, null, null));
        latV -= 0.0004459;
        lonV -= 0.0002623;
        lat.add(new SignalSamplePoint(7 * SECOND, latV, null, null));
        lon.add(new SignalSamplePoint(7 * SECOND, lonV, null, null));
        for (int i = 0; i < 6; i++) {
            latV -= 0.00022;
            lonV -= 0.00013;
            lat.add(new SignalSamplePoint((8 + i) * SECOND, latV, null, null));
            lon.add(new SignalSamplePoint((8 + i) * SECOND, lonV, null, null));
        }
        return MapRouteBuilder.build(lat, lon, null);
    }

    private static List<SignalSamplePoint> constant(double value, int n) {
        List<SignalSamplePoint> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(new SignalSamplePoint(i * SECOND, value, null, null));
        }
        return out;
    }

    private static List<SignalSamplePoint> ramp(double from, double to, int n) {
        List<SignalSamplePoint> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            double t = n == 1 ? 1 : (double) i / (n - 1);
            out.add(new SignalSamplePoint(i * SECOND, from + (to - from) * t, null, null));
        }
        return out;
    }

    private static SignalCatalogEntry vehicle(String name, String unit, String file, SignalSourceType type, int channel) {
        return entry(name, unit, file, type, channel, "ESC");
    }

    private static SignalCatalogEntry entry(String name, String unit, String file, SignalSourceType type,
                                            int channel, String message) {
        SignalCatalogKey key = new SignalCatalogKey(name, type, message, 0x200, channel, file, "veh.dbc");
        return new SignalCatalogEntry(key, unit, 20, 0L, 20 * SECOND, 0.0, 200.0, file, "veh.dbc");
    }

    private static final class FakeQuery implements GraphSeriesQuery {
        private final Map<SignalCatalogKey, List<SignalSamplePoint>> data = new HashMap<>();
        private final Map<SignalCatalogKey, String> units = new HashMap<>();

        void put(SignalCatalogEntry entry, List<SignalSamplePoint> samples) {
            data.put(entry.key(), samples);
            units.put(entry.key(), entry.unit());
        }

        @Override
        public List<SignalSamplePoint> queryRange(SignalCatalogKey key, long startNanos, long endNanos) {
            List<SignalSamplePoint> out = new ArrayList<>();
            for (SignalSamplePoint sample : data.getOrDefault(key, List.of())) {
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
