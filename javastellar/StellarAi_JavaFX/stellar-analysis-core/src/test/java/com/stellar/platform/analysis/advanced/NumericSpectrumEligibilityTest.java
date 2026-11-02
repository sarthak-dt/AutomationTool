package com.stellar.platform.analysis.advanced;

import com.stellar.platform.analysis.align.SampleAligner;
import com.stellar.platform.analysis.model.AlignmentPolicy;
import com.stellar.platform.analysis.model.AnalysisStatus;
import com.stellar.platform.analysis.model.TimeWindow;
import com.stellar.platform.analysis.source.InMemoryAnalysisSampleSource;
import com.stellar.platform.domain.SignalValidity;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSamplePoint;
import com.stellar.platform.domain.catalog.SignalSourceType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NumericSpectrumEligibilityTest {

    private static final SignalCatalogKey A = key("Accel");
    private static final SignalCatalogKey B = key("AccelDelayed");
    private static final long SECOND = 1_000_000_000L;
    private final AdvancedSignalAnalytics engine = new AdvancedSignalAnalytics();

    @Test
    void regularSineHasAnFftAndIdenticalChannelsAreCoherent() {
        InMemoryAnalysisSampleSource src = sine(A, 10, 200, 1.0);
        sineOnto(src, B, 10, 200, 1.0);
        var fft = engine.execute(fft(A), src, null);
        assertEquals(AnalysisStatus.COMPLETED, fft.status(), fft.message());
        assertTrue(fft.peaks().stream().anyMatch(peak -> Math.abs(peak.frequencyHz() - 10.0) <= 1.0));

        var coherence = engine.execute(AdvancedAnalyticsRequest.builder(AdvancedAnalyticsType.COHERENCE)
                .signals(List.of(A, B))
                .window(new TimeWindow(0, SECOND))
                .welchSegmentLength(64)
                .welchOverlap(32)
                .build(), src, null);
        assertTrue(coherence.status().successful(), coherence.message());
        assertTrue(nearest(coherence.coherence(), 10.0) > 0.9);
    }

    @Test
    void invalidSampleMakesFrequencyAnalysisUnavailableUntilTheGapIsRejected() {
        List<SignalSamplePoint> samples = regular(200, 10);
        samples.set(100, new SignalSamplePoint(
                samples.get(100).timestampNanos(), 1_000_000d, 255L, "invalid", SignalValidity.INVALID_RAW_CODE));
        InMemoryAnalysisSampleSource src = new InMemoryAnalysisSampleSource();
        src.put(A, samples);

        var blocked = engine.execute(fft(A), src, null);
        assertEquals(AnalysisStatus.FAILED, blocked.status(), blocked.message());
        assertNull(blocked.spectrum());
        assertTrue(blocked.peaks().isEmpty());
        assertTrue(blocked.message().toLowerCase(Locale.ROOT).contains("discontinu"));
        assertTrue(blocked.warnings().stream().anyMatch(warning -> warning.contains("ineligible")));
        assertEquals(255L, src.querySamples(A, 100, 1).getFirst().rawValue());

        var rejectedGap = engine.execute(AdvancedAnalyticsRequest.builder(AdvancedAnalyticsType.FFT_SPECTRUM)
                .signals(List.of(A))
                .window(new TimeWindow(0, SECOND))
                .sampleHandling(SampleHandling.RESAMPLE)
                .resampleMethod(ResampleMethod.LINEAR_INTERPOLATION)
                .targetRateHz(200)
                .maxGapNanos(7_000_000L)
                .build(), src, null);
        assertEquals(AnalysisStatus.FAILED, rejectedGap.status(), rejectedGap.message());
        assertNull(rejectedGap.spectrum());
        assertTrue(rejectedGap.peaks().isEmpty());
        assertTrue(rejectedGap.message().toLowerCase(Locale.ROOT).contains("discontinu"));

        var omittedGap = engine.execute(AdvancedAnalyticsRequest.builder(AdvancedAnalyticsType.FFT_SPECTRUM)
                .signals(List.of(A))
                .window(new TimeWindow(0, SECOND))
                .sampleHandling(SampleHandling.RESAMPLE)
                .resampleMethod(ResampleMethod.LINEAR_INTERPOLATION)
                .targetRateHz(200)
                .build(), src, null);
        assertEquals(AnalysisStatus.FAILED, omittedGap.status(), omittedGap.message());
        assertNull(omittedGap.spectrum());
        assertTrue(omittedGap.message().toLowerCase(Locale.ROOT).contains("discontinu"));

        var wideGap = engine.execute(AdvancedAnalyticsRequest.builder(AdvancedAnalyticsType.FFT_SPECTRUM)
                .signals(List.of(A))
                .window(new TimeWindow(0, SECOND))
                .sampleHandling(SampleHandling.RESAMPLE)
                .resampleMethod(ResampleMethod.LINEAR_INTERPOLATION)
                .targetRateHz(200)
                .maxGapNanos(SECOND)
                .build(), src, null);
        assertEquals(AnalysisStatus.FAILED, wideGap.status(), wideGap.message());
        assertNull(wideGap.spectrum());
        assertTrue(wideGap.message().toLowerCase(Locale.ROOT).contains("discontinu"));
        assertEquals(1_000_000d, src.querySamples(A, 100, 1).getFirst().physicalValue(), 0.0);
        assertEquals(samples.get(100).timestampNanos(), src.querySamples(A, 100, 1).getFirst().timestampNanos());
    }

    @Test
    void missingAndRepeatedDiscontinuitiesDoNotProduceASpectrum() {
        List<SignalSamplePoint> missing = regular(40, 10);
        missing.remove(20);
        InMemoryAnalysisSampleSource src = new InMemoryAnalysisSampleSource();
        src.put(A, missing);
        var omitted = engine.execute(AdvancedAnalyticsRequest.builder(AdvancedAnalyticsType.FFT_SPECTRUM)
                .signals(List.of(A))
                .window(new TimeWindow(0, SECOND))
                .sampleHandling(SampleHandling.RESAMPLE)
                .targetRateHz(200)
                .build(), src, null);
        assertEquals(AnalysisStatus.FAILED, omitted.status(), omitted.message());
        assertNull(omitted.spectrum());
        assertTrue(omitted.message().toLowerCase(Locale.ROOT).contains("explicit maximum"));

        var limited = engine.execute(AdvancedAnalyticsRequest.builder(AdvancedAnalyticsType.FFT_SPECTRUM)
                .signals(List.of(A))
                .window(new TimeWindow(0, SECOND))
                .sampleHandling(SampleHandling.RESAMPLE)
                .targetRateHz(200)
                .maxGapNanos(6_000_000L)
                .build(), src, null);
        assertEquals(AnalysisStatus.FAILED, limited.status(), limited.message());
        assertNull(limited.spectrum());
        assertEquals(missing.size(), src.retainedSampleCount(A));

        List<SignalSamplePoint> broken = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            if (i == 8 || i == 18) {
                broken.add(new SignalSamplePoint(i * 5_000_000L, 255, 255L, "invalid", SignalValidity.INVALID_RAW_CODE));
            } else if (i == 12) {
                broken.add(new SignalSamplePoint(i * 5_000_000L, Double.NaN, 0L, "FALSE", SignalValidity.VALID));
            } else if (i < 4 || i > 26) {
                broken.add(new SignalSamplePoint(i * 5_000_000L, Math.sin(i / 10.0), null, null, SignalValidity.VALID));
            } else {
                broken.add(new SignalSamplePoint(i * 5_000_000L, 42, 42L, null, SignalValidity.UNRECORDED));
            }
        }
        InMemoryAnalysisSampleSource multi = new InMemoryAnalysisSampleSource();
        multi.put(A, broken);
        var many = engine.execute(AdvancedAnalyticsRequest.builder(AdvancedAnalyticsType.COHERENCE)
                .signals(List.of(A, A))
                .window(new TimeWindow(0, SECOND))
                .sampleHandling(SampleHandling.RESAMPLE)
                .targetRateHz(200)
                .maxGapNanos(SECOND)
                .welchSegmentLength(16)
                .welchOverlap(0)
                .build(), multi, null);
        assertEquals(AnalysisStatus.FAILED, many.status(), many.message());
        assertNull(many.coherence());
        assertEquals(255L, multi.querySamples(A, 8, 1).getFirst().rawValue());
        assertEquals("FALSE", multi.querySamples(A, 12, 1).getFirst().textualValue());
    }

    @Test
    void categoricalAndShortSeriesDoNotProduceASpectrumOrCoherence() {
        InMemoryAnalysisSampleSource categorical = new InMemoryAnalysisSampleSource();
        List<SignalSamplePoint> labels = new ArrayList<>();
        for (int i = 0; i < 32; i++) {
            labels.add(new SignalSamplePoint(i * 10_000_000L, Double.NaN, 0L, "FALSE", SignalValidity.VALID));
        }
        categorical.put(A, labels);
        categorical.put(B, labels);
        var fft = engine.execute(fft(A), categorical, null);
        assertEquals(AnalysisStatus.FAILED, fft.status(), fft.message());
        assertNull(fft.spectrum());
        assertTrue(fft.warnings().stream().anyMatch(warning -> warning.toLowerCase(Locale.ROOT).contains("text")));

        var coherence = engine.execute(AdvancedAnalyticsRequest.builder(AdvancedAnalyticsType.COHERENCE)
                .signals(List.of(A, B))
                .window(new TimeWindow(0, SECOND))
                .build(), categorical, null);
        assertEquals(AnalysisStatus.FAILED, coherence.status(), coherence.message());
        assertNull(coherence.coherence());

        InMemoryAnalysisSampleSource shortSeries = new InMemoryAnalysisSampleSource();
        shortSeries.put(A, regular(3, 1));
        shortSeries.put(B, regular(10, 1));
        var tooFew = engine.execute(fft(A), shortSeries, null);
        assertEquals(AnalysisStatus.FAILED, tooFew.status(), tooFew.message());
        assertNull(tooFew.spectrum());
        assertTrue(tooFew.message().contains("at least 4"));

        var tooFewCoherence = engine.execute(AdvancedAnalyticsRequest.builder(AdvancedAnalyticsType.COHERENCE)
                .signals(List.of(A, B))
                .window(new TimeWindow(0, SECOND))
                .welchSegmentLength(16)
                .welchOverlap(0)
                .build(), shortSeries, null);
        assertEquals(AnalysisStatus.FAILED, tooFewCoherence.status(), tooFewCoherence.message());
        assertNull(tooFewCoherence.coherence());
    }

    @Test
    void omittedGapDoesNotResampleAcrossAnInvalidSample() {
        InMemoryAnalysisSampleSource src = new InMemoryAnalysisSampleSource();
        src.put(A, List.of(
                new SignalSamplePoint(0, 0, null, null, SignalValidity.VALID),
                new SignalSamplePoint(5_000_000L, 1_000_000d, 255L, "invalid", SignalValidity.INVALID_RAW_CODE),
                new SignalSamplePoint(10_000_000L, 10, null, null, SignalValidity.VALID)));
        var result = engine.execute(AdvancedAnalyticsRequest.builder(AdvancedAnalyticsType.RESAMPLE)
                .signals(List.of(A))
                .window(new TimeWindow(0, SECOND))
                .targetRateHz(200)
                .resampleMethod(ResampleMethod.LINEAR_INTERPOLATION)
                .build(), src, null);
        assertEquals(AnalysisStatus.FAILED, result.status(), result.message());
        assertNull(result.derived());
        assertTrue(result.message().toLowerCase(Locale.ROOT).contains("explicit maximum"));
        assertEquals(255L, src.querySamples(A, 1, 1).getFirst().rawValue());
    }

    @Test
    void interpolationDoesNotCrossAnInvalidBracket() {
        InMemoryAnalysisSampleSource src = new InMemoryAnalysisSampleSource();
        src.add(A, 5_000_000L, 5);
        src.put(B, List.of(
                new SignalSamplePoint(0, 0, null, null, SignalValidity.VALID),
                new SignalSamplePoint(5_000_000L, 255, 255L, "invalid", SignalValidity.INVALID_RAW_CODE),
                new SignalSamplePoint(10_000_000L, 10, null, null, SignalValidity.VALID)));
        var pairs = SampleAligner.align(src, A, B, new TimeWindow(0, SECOND),
                AlignmentPolicy.OPTIONAL_LINEAR_INTERPOLATION, 0, 10, null);
        assertTrue(pairs.isEmpty());
        assertEquals(255L, src.querySamples(B, 1, 1).getFirst().rawValue());
    }

    @Test
    void irregularTimestampsAreRejectedWithoutASpectrum() {
        InMemoryAnalysisSampleSource src = new InMemoryAnalysisSampleSource();
        double t = 0;
        while (t < 1.0) {
            src.add(A, Math.round(t * SECOND), Math.sin(2 * Math.PI * 10 * t));
            t += 0.004 + 0.006 * (0.5 + 0.5 * Math.sin(30 * t));
        }
        var fft = engine.execute(fft(A), src, null);
        assertEquals(AnalysisStatus.FAILED, fft.status(), fft.message());
        assertNull(fft.spectrum());
        assertTrue(fft.peaks().isEmpty());
    }

    private static AdvancedAnalyticsRequest fft(SignalCatalogKey key) {
        return AdvancedAnalyticsRequest.builder(AdvancedAnalyticsType.FFT_SPECTRUM)
                .signals(List.of(key))
                .window(new TimeWindow(0, SECOND))
                .build();
    }

    private static InMemoryAnalysisSampleSource sine(SignalCatalogKey key, double frequency, double rate, double seconds) {
        InMemoryAnalysisSampleSource src = new InMemoryAnalysisSampleSource();
        sineOnto(src, key, frequency, rate, seconds);
        return src;
    }

    private static void sineOnto(InMemoryAnalysisSampleSource src, SignalCatalogKey key,
                                 double frequency, double rate, double seconds) {
        int n = (int) Math.round(seconds * rate);
        for (int i = 0; i < n; i++) {
            double t = i / rate;
            src.add(key, Math.round(t * SECOND), Math.sin(2 * Math.PI * frequency * t));
        }
    }

    private static List<SignalSamplePoint> regular(int count, double frequency) {
        List<SignalSamplePoint> samples = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            double t = i / 200.0;
            samples.add(new SignalSamplePoint(
                    Math.round(t * SECOND), Math.sin(2 * Math.PI * frequency * t), null, null, SignalValidity.VALID));
        }
        return samples;
    }

    private static double nearest(CoherenceAnalyzer.Result result, double hz) {
        int best = 0;
        double distance = Double.MAX_VALUE;
        for (int i = 0; i < result.frequencyHz().length; i++) {
            double next = Math.abs(result.frequencyHz()[i] - hz);
            if (next < distance) {
                distance = next;
                best = i;
            }
        }
        return result.coherence()[best];
    }

    private static SignalCatalogKey key(String name) {
        return new SignalCatalogKey(name, SignalSourceType.MF4_NATIVE, "MEAS", 0, 0, "synth.mf4", "");
    }
}
