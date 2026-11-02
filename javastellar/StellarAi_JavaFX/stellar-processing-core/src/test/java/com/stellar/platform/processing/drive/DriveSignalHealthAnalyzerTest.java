package com.stellar.platform.processing.drive;

import com.stellar.platform.domain.SignalValidity;
import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSamplePoint;
import com.stellar.platform.domain.catalog.SignalSourceType;
import com.stellar.platform.processing.graph.GraphSeriesQuery;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DriveSignalHealthAnalyzerTest {

    private static final long S = 1_000_000_000L;
    private final DriveSignalHealthAnalyzer analyzer = new DriveSignalHealthAnalyzer();

    @Test
    void invalidFiniteSamplesDoNotCreateFlatlineOutOfRangeOrDiagnosticActivity() {
        SignalCatalogKey pressure = key("Fuel_Pressure");
        List<SignalSamplePoint> flat = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            flat.add(point(i * S, 250, null, null, SignalValidity.VALID));
        }
        for (int i = 3; i < 8; i++) {
            flat.add(point(i * S, 250, 255L, "invalid", SignalValidity.INVALID_RAW_CODE));
        }
        DriveSignalHealthSummary summary = analyzer.analyze(
                new ListQuery(pressure, flat),
                List.of(entry(pressure, "kPa", 0.0, 300.0, 8)),
                List.of(pressure), 0L, 7 * S);

        assertTrue(summary.issues().stream().noneMatch(issue -> issue.kind() == DriveSignalIssueKind.STALE_FLATLINE));
        assertTrue(summary.issues().stream().anyMatch(issue -> issue.kind() == DriveSignalIssueKind.INVALID_SIGNAL
                && issue.evidence().contains("5 events")));
        assertEquals(255L, flat.get(3).rawValue());
        assertEquals("invalid", flat.get(3).textualValue());

        SignalCatalogKey ranged = key("Coolant");
        List<SignalSamplePoint> range = List.of(
                point(0, 150, null, null, SignalValidity.VALID),
                point(S, 999, 999L, "invalid", SignalValidity.INVALID_RAW_CODE),
                point(2 * S, 200, 200L, null, SignalValidity.UNRECORDED),
                point(3 * S, Double.NaN, 0L, "FALSE", SignalValidity.VALID),
                point(4 * S, 40, null, null, SignalValidity.VALID));
        DriveSignalHealthSummary rangedSummary = analyzer.analyze(
                new ListQuery(ranged, range),
                List.of(entry(ranged, "degC", 0.0, 100.0, 5)),
                List.of(ranged), 0L, 4 * S);
        assertTrue(rangedSummary.issues().stream().anyMatch(issue ->
                issue.kind() == DriveSignalIssueKind.OUT_OF_RANGE && issue.evidence().startsWith("1 sample")));
        assertEquals(999L, range.get(1).rawValue());
        assertEquals("FALSE", range.get(3).textualValue());
    }

    @Test
    void recordingGapDoesNotExtendFlatlineDuration() {
        SignalCatalogKey pressure = key("Fuel_Pressure");
        List<SignalSamplePoint> samples = new ArrayList<>();
        for (int second : new int[] {0, 1, 2, 3, 4, 5, 15, 16, 17, 18, 19}) {
            samples.add(point(second * S, 250, null, null, SignalValidity.VALID));
        }
        DriveSignalHealthSummary summary = analyzer.analyze(
                new ListQuery(pressure, samples),
                List.of(entry(pressure, "kPa", null, null, samples.size())),
                List.of(pressure), 0L, 19 * S);
        assertTrue(summary.issues().stream().anyMatch(issue ->
                issue.kind() == DriveSignalIssueKind.STALE_FLATLINE
                        && issue.evidence().contains("5.00 s")
                        && !issue.evidence().contains("19")));
    }

    @Test
    void diagnosticActivityUsesValidNumbersAndKnownCategoricalLabels() {
        SignalCatalogKey fault = key("Diag_Fault_Status");
        List<SignalSamplePoint> samples = List.of(
                point(0, 0, null, null, SignalValidity.VALID),
                point(S, 1, null, null, SignalValidity.VALID),
                point(2 * S, 8, 8L, "invalid", SignalValidity.INVALID_RAW_CODE),
                point(3 * S, 9, 9L, null, SignalValidity.UNRECORDED),
                point(4 * S, 3, 3L, "inactive", SignalValidity.INACTIVE_RAW_CODE),
                point(5 * S, Double.NaN, 0L, "FALSE", SignalValidity.VALID),
                point(6 * S, Double.NaN, 1L, "TRUE", SignalValidity.VALID),
                point(7 * S, Double.NaN, null, "note", SignalValidity.VALID));
        DriveSignalHealthSummary summary = analyzer.analyze(
                new ListQuery(fault, samples),
                List.of(entry(fault, "", null, null, samples.size())),
                List.of(fault), 0L, 7 * S);

        assertTrue(summary.issues().stream().anyMatch(issue ->
                issue.kind() == DriveSignalIssueKind.DIAGNOSTIC_FAULT
                        && issue.evidence().equals("Diagnostic active 2 samples")));
        assertTrue(summary.issues().stream().anyMatch(issue ->
                issue.kind() == DriveSignalIssueKind.CONFIRMED_FAULT
                        && issue.evidence().equals("Diagnostic fault signal was non-zero")));
        assertEquals(SignalValidity.UNRECORDED, samples.get(3).validity());
        assertEquals("FALSE", samples.get(5).textualValue());
        assertEquals("TRUE", samples.get(6).textualValue());
    }

    private static SignalSamplePoint point(long ts, double physical, Long raw, String text, SignalValidity validity) {
        return new SignalSamplePoint(ts, physical, raw, text, validity);
    }

    private static SignalCatalogKey key(String name) {
        return new SignalCatalogKey(name, SignalSourceType.MF4_NATIVE, "DIRECT_MEASUREMENT", 0L);
    }

    private static SignalCatalogEntry entry(SignalCatalogKey key, String unit, Double min, Double max, long count) {
        return new SignalCatalogEntry(key, unit, count, 0L, 20 * S, min, max, "audit.dat", "");
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
            return "";
        }

        @Override
        public long sampleCount(SignalCatalogKey key) {
            return samples.size();
        }
    }
}
