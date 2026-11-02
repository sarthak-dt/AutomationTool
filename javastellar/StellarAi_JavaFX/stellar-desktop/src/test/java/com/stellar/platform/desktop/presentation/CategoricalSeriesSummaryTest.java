package com.stellar.platform.desktop.presentation;

import com.stellar.platform.analysis.investigation.EngineeringInvestigation;
import com.stellar.platform.domain.SignalValidity;
import com.stellar.platform.domain.catalog.SignalCatalog;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSamplePoint;
import com.stellar.platform.domain.catalog.SignalSourceType;
import com.stellar.platform.processing.catalog.SignalSeriesStore;
import com.stellar.platform.storage.metadata.SignalCatalogMetadata;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CategoricalSeriesSummaryTest {

    @Test
    void uniformFalseIsNamedWithItsSampleCountAndNotAsANumber() {
        SignalCatalogKey key = key("ETC_Fan2_output");
        List<SignalSamplePoint> samples = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            samples.add(new SignalSamplePoint(5_998_194L + i, Double.NaN, 0L, "FALSE", SignalValidity.VALID));
        }
        CategoricalSeriesSummary.Result result = CategoricalSeriesSummary.scan(new Pages(key, samples), key);
        assertEquals("FALSE (300 samples)", result.reportLabel());
        assertEquals("0", result.rawText());
        assertEquals("VALID", result.validityText());
        assertEquals(5_998_194L, result.firstTimestampNanos());
        assertFalse(result.changes());
        assertFalse(result.reportLabel().contains("0.0"));
    }

    @Test
    void changingLabelsAreNotCollapsedToTheFirstValue() {
        SignalCatalogKey key = key("ETC_Fan2_output");
        List<SignalSamplePoint> samples = List.of(
                new SignalSamplePoint(1L, Double.NaN, 0L, "FALSE", SignalValidity.VALID),
                new SignalSamplePoint(2L, Double.NaN, 1L, "TRUE", SignalValidity.VALID),
                new SignalSamplePoint(3L, Double.NaN, 0L, "FALSE", SignalValidity.VALID));
        CategoricalSeriesSummary.Result result = CategoricalSeriesSummary.scan(new Pages(key, samples), key);
        assertTrue(result.changes());
        assertTrue(result.reportLabel().contains("FALSE"));
        assertTrue(result.reportLabel().contains("TRUE"));
        assertTrue(result.reportLabel().contains("changes"));
        assertEquals("varies", result.rawText());
        assertFalse(result.reportLabel().equals("FALSE"));
    }

    @Test
    void numericInvalidAndFailedSamplesAreNotCategoricalLabels() {
        SignalCatalogKey key = key("IN_Vehicle_speed");
        List<SignalSamplePoint> samples = List.of(
                new SignalSamplePoint(99_013_388L, 72.96875, 9340L, null, SignalValidity.VALID),
                new SignalSamplePoint(100L, 255, 255L, "invalid", SignalValidity.INVALID_RAW_CODE),
                new SignalSamplePoint(200L, Double.NaN, 9L, null, SignalValidity.CONVERSION_FAILED));
        assertNull(CategoricalSeriesSummary.scan(new Pages(key, samples), key));
    }

    @Test
    void reportRowUsesTheStreamedLabelAndLeavesNumericRangeUnavailable() {
        SignalCatalogKey key = new SignalCatalogKey(
                "ETC_Fan2_output", SignalSourceType.MF4_NATIVE, "DIRECT_MEASUREMENT", 0, 0, "Xenon.dat", "");
        String blob = GraphInvestigationCodec.encodeKey(key);
        List<SignalSamplePoint> samples = List.of(
                new SignalSamplePoint(5_998_194L, Double.NaN, 0L, "FALSE", SignalValidity.VALID),
                new SignalSamplePoint(6_000_000L, Double.NaN, 0L, "FALSE", SignalValidity.VALID));
        SignalCatalogMetadata row = new SignalCatalogMetadata(
                "row", "p1", "src", 0, "", "art", blob, "ETC_Fan2_output", "", 0, 0,
                "MF4_NATIVE", "", 2, 5_998_194L, 6_000_000L, null, null, Instant.now(), Instant.now());
        EngineeringInvestigation investigation = EngineeringInvestigation.builder("p1")
                .name("Xenon")
                .signalKeyBlobs(List.of(blob))
                .build();
        var signals = DesktopInvestigationService.evidenceSignals(
                investigation, List.of(row), java.util.Map.of(), null, new Pages(key, samples));
        assertEquals(1, signals.size());
        assertEquals("n/a", signals.getFirst().min());
        assertEquals("n/a", signals.getFirst().max());
        assertTrue(signals.getFirst().categoricalLabel().contains("FALSE"));
        assertTrue(signals.getFirst().categoricalLabel().contains("2 samples"));
        assertFalse(signals.getFirst().categoricalLabel().contains("changes"));
    }

    private static SignalCatalogKey key(String name) {
        return new SignalCatalogKey(name, SignalSourceType.MF4_NATIVE, "MEAS", 0, 0, "Xenon.dat", "");
    }

    private static final class Pages implements SignalSeriesStore {
        private final SignalCatalogKey key;
        private final List<SignalSamplePoint> samples;

        private Pages(SignalCatalogKey key, List<SignalSamplePoint> samples) {
            this.key = key;
            this.samples = samples;
        }

        @Override
        public SignalCatalog snapshot() {
            return null;
        }

        @Override
        public List<SignalSamplePoint> querySamples(SignalCatalogKey requested, int offset, int limit) {
            if (!key.equals(requested) || offset >= samples.size() || limit <= 0) {
                return List.of();
            }
            return List.copyOf(samples.subList(offset, Math.min(samples.size(), offset + limit)));
        }

        @Override
        public int retainedSampleCount(SignalCatalogKey requested) {
            return key.equals(requested) ? samples.size() : 0;
        }

        @Override
        public long retainedWindowStartIndex(SignalCatalogKey requested) {
            return 0;
        }
    }
}
