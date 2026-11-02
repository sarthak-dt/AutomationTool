package com.stellar.platform.processing;

import com.stellar.platform.domain.CanonicalSignal;
import com.stellar.platform.domain.SignalValidity;
import com.stellar.platform.domain.SourceReference;
import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSamplePoint;
import com.stellar.platform.domain.catalog.SignalSourceType;
import com.stellar.platform.processing.analysis.AutomotiveAnalysisEngine;
import com.stellar.platform.processing.drive.DriveBrakingAnalyzer;
import com.stellar.platform.processing.drive.DriveFuelAnalyzer;
import com.stellar.platform.processing.drive.DriveSegmentThresholds;
import com.stellar.platform.processing.drive.speed.SpeedUnit;
import com.stellar.platform.processing.drive.speed.VehicleSpeedPeakScanner;
import com.stellar.platform.processing.graph.GraphBucket;
import com.stellar.platform.processing.graph.GraphRenderSeries;
import com.stellar.platform.processing.graph.GraphSeriesQuery;
import com.stellar.platform.processing.graph.LosslessGraphSignalStore;
import com.stellar.platform.processing.graph.MinMaxLodReducer;
import com.stellar.platform.processing.map.MapRouteBuilder;
import com.stellar.platform.processing.map.MapRouteModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NumericEligibilityRemediationTest {

    private static final long S = 1_000_000_000L;

    @TempDir
    Path temp;

    @Test
    void featuresSpeedFuelAndBrakingUseOnlyEligibleSamples() {
        SignalCatalogKey key = key("A");
        List<SignalSamplePoint> mixed = mixed();
        List<GraphBucket> buckets = MinMaxLodReducer.reduce(mixed, 16);
        GraphRenderSeries series = new GraphRenderSeries(key, "km/h", mixed.size(), true, 0L, 20 * S, buckets);
        var feature = AutomotiveAnalysisEngine.extractFeatures(List.of(series)).getFirst();
        assertEquals(10.0, feature.min(), 0.0);
        assertEquals(30.0, feature.max(), 0.0);
        assertEquals(20.0, feature.mean(), 1e-12);
        assertEquals(Math.sqrt(500.0), feature.rms(), 1e-9);
        assertTrue(AutomotiveAnalysisEngine.evaluateRules(List.of(series), List.of(
                new AutomotiveAnalysisEngine.DiagnosticRule(
                        "high", "1", "Above 100", AutomotiveAnalysisEngine.Severity.HIGH,
                        List.of(new AutomotiveAnalysisEngine.RuleCondition(
                                "A", AutomotiveAnalysisEngine.Operator.GT, 100)),
                        true, 1))).isEmpty());

        assertEquals(30.0, VehicleSpeedPeakScanner.scan(
                new ListQuery(key, mixed), key, SpeedUnit.KILOMETRES_PER_HOUR, 0L, 20 * S).valueKph(), 0.0);
        SignalSamplePoint invalid = mixed.get(2);
        assertEquals(255L, invalid.rawValue());
        assertEquals(SignalValidity.INVALID_RAW_CODE, invalid.validity());
        assertEquals("FALSE", mixed.get(1).textualValue());

        var blocked = new DriveFuelAnalyzer().analyze(
                new ListQuery(key, List.of(
                        point(S, 10, null, null, SignalValidity.VALID),
                        point(2 * S, 255, 255L, "invalid", SignalValidity.INVALID_RAW_CODE),
                        point(3 * S, 10, null, null, SignalValidity.VALID))),
                entry(key, "L/h"), null, null, 0L, 20 * S, 1000);
        assertNull(blocked.fuelUsedLiters());

        var open = new DriveFuelAnalyzer().analyze(
                new ListQuery(key, List.of(
                        point(0L, 10, null, null, SignalValidity.VALID),
                        point(S, 10, null, null, SignalValidity.VALID))),
                entry(key, "L/h"), null, null, 0L, 20 * S, 1000);
        assertEquals(10.0 / 3600.0, open.fuelUsedLiters(), 1e-12);

        MapRouteModel route = MapRouteBuilder.build(
                List.of(point(0L, 18.5, null, null, SignalValidity.VALID),
                        point(2 * S, 18.6, null, null, SignalValidity.VALID)),
                List.of(point(0L, 73.8, null, null, SignalValidity.VALID),
                        point(2 * S, 73.81, null, null, SignalValidity.VALID)),
                null);
        var braking = new DriveBrakingAnalyzer().analyze(
                new ListQuery(key, List.of(
                        point(0L, 255, 255L, "invalid", SignalValidity.INVALID_RAW_CODE),
                        point(S, 7, 1L, "inactive", SignalValidity.INACTIVE_RAW_CODE))),
                entry(key, ""), route, 0, route.size() - 1, DriveSegmentThresholds.defaults());
        assertEquals(0, braking.eventCount());
    }

    @Test
    void reopenedArtifactKeepsRawValuesAndDoesNotWidenCatalogExtrema() throws Exception {
        SignalCatalogKey key = key("A");
        Path root = temp.resolve("store");
        try (LosslessGraphSignalStore store = new LosslessGraphSignalStore(root, "audit.dat", "", 32)) {
            store.accept(mixed().stream().map(sample -> new CanonicalSignal(
                    "A", "DIRECT_MEASUREMENT", sample.timestampNanos(), 0L, 1,
                    sample.physicalValue(), sample.rawValue(), "km/h", sample.textualValue(),
                    SourceReference.ofLine(1), sample.validity())).toList());
            store.persistIndex();
        }
        try (LosslessGraphSignalStore read = LosslessGraphSignalStore.openExisting(root)) {
            var entry = read.catalogEntries().getFirst();
            assertEquals(10.0, entry.minPhysical(), 0.0);
            assertEquals(30.0, entry.maxPhysical(), 0.0);
            SignalSamplePoint invalid = read.querySamples(entry.key(), 0, 10).get(2);
            assertEquals(255L, invalid.rawValue());
            assertEquals("invalid", invalid.textualValue());
            assertEquals(SignalValidity.INVALID_RAW_CODE, invalid.validity());
            assertEquals("FALSE", read.querySamples(entry.key(), 0, 10).get(1).textualValue());
        }
    }

    private static List<SignalSamplePoint> mixed() {
        return List.of(
                point(S, 10, null, null, SignalValidity.VALID),
                point(2 * S, Double.NaN, 0L, "FALSE", SignalValidity.VALID),
                point(3 * S, 255, 255L, "invalid", SignalValidity.INVALID_RAW_CODE),
                point(4 * S, 7, 1L, "inactive", SignalValidity.INACTIVE_RAW_CODE),
                point(5 * S, Double.NaN, 9L, null, SignalValidity.CONVERSION_FAILED),
                point(7 * S, 42, 42L, null, SignalValidity.UNRECORDED),
                point(8 * S, 30, null, null, SignalValidity.VALID));
    }

    private static SignalSamplePoint point(long ts, double physical, Long raw, String text, SignalValidity validity) {
        return new SignalSamplePoint(ts, physical, raw, text, validity);
    }

    private static SignalCatalogKey key(String name) {
        return new SignalCatalogKey(name, SignalSourceType.MF4_NATIVE, "DIRECT_MEASUREMENT", 0L);
    }

    private static SignalCatalogEntry entry(SignalCatalogKey key, String unit) {
        return new SignalCatalogEntry(key, unit, 2, 0L, S, null, null, "audit.dat", "");
    }

    private record ListQuery(SignalCatalogKey key, List<SignalSamplePoint> samples) implements GraphSeriesQuery {
        @Override
        public List<SignalSamplePoint> queryRange(SignalCatalogKey key, long startNanos, long endNanos) {
            if (!this.key.equals(key)) {
                return List.of();
            }
            return samples.stream()
                    .filter(sample -> sample.timestampNanos() >= startNanos && sample.timestampNanos() <= endNanos)
                    .toList();
        }

        @Override
        public String unit(SignalCatalogKey key) {
            return "L/h";
        }

        @Override
        public long sampleCount(SignalCatalogKey key) {
            return samples.size();
        }
    }
}
