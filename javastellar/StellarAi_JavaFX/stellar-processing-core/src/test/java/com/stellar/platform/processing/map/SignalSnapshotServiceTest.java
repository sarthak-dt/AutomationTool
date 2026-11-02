package com.stellar.platform.processing.map;

import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSamplePoint;
import com.stellar.platform.domain.catalog.SignalSourceType;
import com.stellar.platform.processing.graph.GraphSeriesQuery;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SignalSnapshotServiceTest {

    private static final long SECOND = 1_000_000_000L;

    /** Bounded-window store that records how much data each lookup touched. */
    private static final class RecordingQuery implements GraphSeriesQuery {
        private final Map<SignalCatalogKey, List<SignalSamplePoint>> data = new HashMap<>();
        private final Map<SignalCatalogKey, String> units = new HashMap<>();
        private final AtomicInteger scanned = new AtomicInteger();

        void put(SignalCatalogKey key, String unit, List<SignalSamplePoint> samples) {
            data.put(key, samples);
            units.put(key, unit);
        }

        @Override
        public List<SignalSamplePoint> queryRange(SignalCatalogKey key, long startNanos, long endNanos) {
            List<SignalSamplePoint> all = data.getOrDefault(key, List.of());
            List<SignalSamplePoint> window = new ArrayList<>();
            for (SignalSamplePoint point : all) {
                scanned.incrementAndGet();
                if (point.timestampNanos() >= startNanos && point.timestampNanos() <= endNanos) {
                    window.add(point);
                }
            }
            return window;
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

    private static SignalCatalogKey key(String name) {
        return new SignalCatalogKey(name, SignalSourceType.MF4_NATIVE, "MSG", 0);
    }

    @Test
    void exactSampleIsReturnedUnchanged() {
        RecordingQuery query = new RecordingQuery();
        SignalCatalogKey speed = key("VehicleSpeed");
        query.put(speed, "km/h", List.of(
                new SignalSamplePoint(0, 10.5, null, null),
                new SignalSamplePoint(SECOND, 20.5, null, null)));

        SignalSnapshotValue value = new SignalSnapshotService(query)
                .resolve(speed, SECOND, null, SECOND).orElseThrow();

        assertEquals(20.5, value.physicalValue(), 1e-9);
        assertTrue(value.exact());
        assertEquals(0L, value.alignmentDeltaNanos());
    }

    @Test
    void continuousSignalsInterpolateBetweenSamples() {
        RecordingQuery query = new RecordingQuery();
        SignalCatalogKey speed = key("VehicleSpeed");
        query.put(speed, "km/h", List.of(
                new SignalSamplePoint(0, 10.5, null, null),
                new SignalSamplePoint(2 * SECOND, 30.5, null, null)));

        SignalSnapshotValue value = new SignalSnapshotService(query)
                .resolve(speed, SECOND, null, 2 * SECOND).orElseThrow();

        assertEquals(SignalAlignmentPolicy.LINEAR, value.appliedPolicy());
        assertEquals(20.5, value.physicalValue(), 1e-9);
    }

    @Test
    void discreteStateSignalsHoldTheLastValueInsteadOfInterpolating() {
        RecordingQuery query = new RecordingQuery();
        SignalCatalogKey gear = key("GearState");
        query.put(gear, "", List.of(
                new SignalSamplePoint(0, 3, 3L, "D3"),
                new SignalSamplePoint(2 * SECOND, 4, 4L, "D4")));

        SignalSnapshotValue value = new SignalSnapshotService(query)
                .resolve(gear, SECOND, null, 2 * SECOND).orElseThrow();

        assertEquals(SignalAlignmentPolicy.HOLD_LAST, value.appliedPolicy());
        assertEquals(3.0, value.physicalValue(), 1e-9);
        assertEquals("D3", value.textualValue());
    }

    @Test
    void integralValuedSignalsAreNotInterpolated() {
        RecordingQuery query = new RecordingQuery();
        SignalCatalogKey satellites = key("Number_Satellites");
        query.put(satellites, "count", List.of(
                new SignalSamplePoint(0, 11, 11L, null),
                new SignalSamplePoint(2 * SECOND, 12, 12L, null)));

        SignalSnapshotValue value = new SignalSnapshotService(query)
                .resolve(satellites, SECOND, null, 2 * SECOND).orElseThrow();

        assertEquals(11.0, value.physicalValue(), 1e-9);
    }

    @Test
    void exactPolicyRejectsNonMatchingTimestamps() {
        RecordingQuery query = new RecordingQuery();
        SignalCatalogKey speed = key("VehicleSpeed");
        query.put(speed, "km/h", List.of(new SignalSamplePoint(0, 10.5, null, null)));

        Optional<SignalSnapshotValue> value = new SignalSnapshotService(query)
                .resolve(speed, SECOND, SignalAlignmentPolicy.EXACT, SECOND);

        assertTrue(value.isEmpty());
    }

    @Test
    void nearestPolicyPicksTheClosestNeighbour() {
        RecordingQuery query = new RecordingQuery();
        SignalCatalogKey speed = key("VehicleSpeed");
        query.put(speed, "km/h", List.of(
                new SignalSamplePoint(0, 10.5, null, null),
                new SignalSamplePoint(10 * SECOND, 90.5, null, null)));

        SignalSnapshotValue value = new SignalSnapshotService(query)
                .resolve(speed, 9 * SECOND, SignalAlignmentPolicy.NEAREST, 10 * SECOND).orElseThrow();

        assertEquals(90.5, value.physicalValue(), 1e-9);
        assertFalse(value.exact());
    }

    @Test
    void signalsWithNoSamplesInTheWindowAreOmittedRatherThanFaked() {
        RecordingQuery query = new RecordingQuery();
        SignalCatalogKey present = key("VehicleSpeed");
        SignalCatalogKey absent = key("BrakePressure");
        query.put(present, "km/h", List.of(new SignalSamplePoint(0, 10.5, null, null)));
        query.put(absent, "bar", List.of(new SignalSamplePoint(9_999 * SECOND, 1.0, null, null)));

        List<SignalSnapshotValue> values = new SignalSnapshotService(query)
                .snapshotAt(0, List.of(present, absent), null, SECOND);

        assertEquals(1, values.size());
        assertEquals(present, values.get(0).key());
    }

    @Test
    void mixedSamplingRatesResolveIndependentlyPerSignal() {
        RecordingQuery query = new RecordingQuery();
        SignalCatalogKey fast = key("RPM");
        SignalCatalogKey slow = key("Altitude");
        List<SignalSamplePoint> fastSamples = new ArrayList<>();
        for (int i = 0; i < 1_000; i++) {
            fastSamples.add(new SignalSamplePoint(i * (SECOND / 100), 1_000.5 + i, null, null));
        }
        query.put(fast, "rpm", fastSamples);
        query.put(slow, "m", List.of(
                new SignalSamplePoint(0, 500.5, null, null),
                new SignalSamplePoint(5 * SECOND, 550.5, null, null)));

        List<SignalSnapshotValue> values = new SignalSnapshotService(query)
                .snapshotAt(SECOND, List.of(fast, slow), null, 5 * SECOND);

        assertEquals(2, values.size());
        assertEquals(1_100.5, values.get(0).physicalValue(), 1e-6);
        assertEquals(510.5, values.get(1).physicalValue(), 1e-6);
    }

    @Test
    void emptyKeySetPerformsNoQueries() {
        RecordingQuery query = new RecordingQuery();

        assertTrue(new SignalSnapshotService(query).snapshotAt(SECOND, List.of()).isEmpty());
        assertEquals(0, query.scanned.get());
    }
}
