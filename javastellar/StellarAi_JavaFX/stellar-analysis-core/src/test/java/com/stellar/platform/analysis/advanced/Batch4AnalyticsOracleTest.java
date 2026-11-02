package com.stellar.platform.analysis.advanced;

import com.stellar.platform.analysis.AnalysisEngine;
import com.stellar.platform.analysis.derived.DerivedExpression;
import com.stellar.platform.analysis.derived.DerivedSignalEngine;
import com.stellar.platform.analysis.model.AnalysisParameters;
import com.stellar.platform.analysis.model.AnalysisRequest;
import com.stellar.platform.analysis.model.AnalysisResult;
import com.stellar.platform.analysis.model.AnalysisStatus;
import com.stellar.platform.analysis.model.AnalysisType;
import com.stellar.platform.analysis.model.ThresholdOperator;
import com.stellar.platform.analysis.model.TimeWindow;
import com.stellar.platform.analysis.source.InMemoryAnalysisSampleSource;
import com.stellar.platform.domain.SignalValidity;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSamplePoint;
import com.stellar.platform.domain.catalog.SignalSourceType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Batch 4 numeric checks. Expected values are computed from the operation
 * definition (recurrence, Δv/Δt, Nyquist = fs/2, linear midpoint), not from
 * a previous run of this product.
 */
class Batch4AnalyticsOracleTest {

    private static final long SECOND = 1_000_000_000L;
    private static final SignalCatalogKey KEY = key("Ramp");
    private static final List<String> NOTES = new ArrayList<>();
    private final AdvancedSignalAnalytics analytics = new AdvancedSignalAnalytics();
    private final AnalysisEngine engine = new AnalysisEngine();

    @AfterAll
    static void writeEvidence() throws Exception {
        Path out = Path.of("..", "docs", "qa-handoff", "batch4-analytics.txt").toAbsolutePath().normalize();
        Files.createDirectories(out.getParent());
        Files.writeString(out, String.join(System.lineSeparator(), NOTES) + System.lineSeparator());
    }

    @Test
    void lowPassKeepsAConstantAndRejectsTheNyquistBoundary() {
        InMemoryAnalysisSampleSource src = constant(3.0, 7L, 200, 0.05);
        AdvancedAnalyticsResult kept = analytics.execute(filter(AdvancedAnalyticsType.LOW_PASS_FILTER, 1.0, 0, 10 * SECOND), src, null);
        assertTrue(kept.status().successful(), kept.message());
        double[] values = kept.derived().values();
        assertEquals(200, values.length);
        double edgeError = Math.max(Math.abs(values[0] - 3.0), Math.abs(values[values.length - 1] - 3.0));
        double midError = 0;
        for (int i = 80; i < 120; i++) {
            midError = Math.max(midError, Math.abs(values[i] - 3.0));
        }
        note("TC_ASP_007 interior |error|=" + midError + " edge |error|=" + edgeError
                + " v0=" + values[0] + " v100=" + values[100] + " v199=" + values[199]);
        assertTrue(midError < 1e-4, "low-pass interior drifted by " + midError + " v20=" + values[20]);
        assertEquals(3.0, src.querySamples(KEY, 0, 1).getFirst().physicalValue(), 0.0);
        assertEquals(7L, src.querySamples(KEY, 0, 1).getFirst().rawValue());

        AdvancedAnalyticsResult atNyquist = analytics.execute(filter(AdvancedAnalyticsType.LOW_PASS_FILTER, 10.0, 0, 10 * SECOND), src, null);
        AdvancedAnalyticsResult above = analytics.execute(filter(AdvancedAnalyticsType.LOW_PASS_FILTER, 11.0, 0, 10 * SECOND), src, null);
        assertEquals(AnalysisStatus.FAILED, atNyquist.status(), atNyquist.message());
        assertEquals(AnalysisStatus.FAILED, above.status(), above.message());
        assertTrue(atNyquist.message().contains("Nyquist"), atNyquist.message());
        assertTrue(above.message().contains("Nyquist"), above.message());
        assertNull(atNyquist.derived());
        note("TC_ASP_006 PASS cutoff 10 Hz and 11 Hz rejected; fs=20 Hz so Nyquist=10 Hz. " + atNyquist.message());
        note("TC_ASP_007 PASS constant 3.0 low-pass cutoff 1 Hz, interior samples stay at 3; source raw 7 unchanged");
        note("TC_ASP_002 PASS derived length 200 from the constant fixture");
        note("TC_ASP_003 PASS source samples unchanged after the filter");
        note("TC_ASP_005 PASS non-positive cutoff rejected: " + cutoffRejected());
    }

    @Test
    void highPassAndBandPassRemoveTheConstant() {
        InMemoryAnalysisSampleSource src = constant(3.0, 7L, 200, 0.05);
        AdvancedAnalyticsResult high = analytics.execute(filter(AdvancedAnalyticsType.HIGH_PASS_FILTER, 1.0, 0, 10 * SECOND), src, null);
        AdvancedAnalyticsResult band = analytics.execute(filter(AdvancedAnalyticsType.BAND_PASS_FILTER, 1.0, 4.0, 10 * SECOND), src, null);
        assertTrue(high.status().successful(), high.message());
        assertTrue(band.status().successful(), band.message());
        double highMid = interiorAbs(high.derived().values());
        double bandMid = interiorAbs(band.derived().values());
        note("FILTER-DC high-pass interior |out|=" + highMid + " v100=" + high.derived().values()[100]
                + " band-pass interior |out|=" + bandMid + " v100=" + band.derived().values()[100]);
        assertTrue(highMid < 1e-3, "high-pass interior left DC " + highMid);
        assertTrue(bandMid < 1e-3, "band-pass interior left DC " + bandMid);
        note("TC_ASP_001 PASS low/high/band requests accepted when cutoff is inside (0, Nyquist)");
    }

    @Test
    void categoricalSamplesAreRefusedBeforeAFilter() {
        InMemoryAnalysisSampleSource src = new InMemoryAnalysisSampleSource();
        List<SignalSamplePoint> labels = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            labels.add(new SignalSamplePoint(i * 50_000_000L, Double.NaN, 0L, "FALSE", SignalValidity.VALID));
        }
        src.put(KEY, labels);
        AdvancedAnalyticsResult result = analytics.execute(filter(AdvancedAnalyticsType.LOW_PASS_FILTER, 1.0, 0, 2 * SECOND), src, null);
        assertEquals(AnalysisStatus.FAILED, result.status(), result.message());
        assertNull(result.derived());
        assertEquals("FALSE", src.querySamples(KEY, 0, 1).getFirst().textualValue());
        AdvancedAnalyticsResult empty = analytics.execute(AdvancedAnalyticsRequest.builder(AdvancedAnalyticsType.LOW_PASS_FILTER)
                .window(new TimeWindow(0, SECOND))
                .lowCutoffHz(1)
                .build(), src, null);
        assertEquals(AnalysisStatus.FAILED, empty.status(), empty.message());
        assertTrue(empty.message().contains("Select at least one numeric signal"), empty.message());
        note("TC_ASP_004 PASS no signal selected: " + empty.message());
        note("CATEGORICAL safeguard PASS filter refused text samples and left FALSE in place: " + result.message());
    }

    @Test
    void exponentialSmoothingMatchesTheRecurrence() {
        InMemoryAnalysisSampleSource src = new InMemoryAnalysisSampleSource();
        src.add(KEY, 0, 0);
        src.add(KEY, SECOND, 10);
        src.add(KEY, 2 * SECOND, 10);
        AdvancedAnalyticsResult result = analytics.execute(AdvancedAnalyticsRequest.builder(AdvancedAnalyticsType.EXPONENTIAL_SMOOTHING)
                .signals(List.of(KEY))
                .window(new TimeWindow(0, 3 * SECOND))
                .alpha(0.5)
                .build(), src, null);
        assertTrue(result.status().successful(), result.message());
        double[] out = result.derived().values();
        assertEquals(0.0, out[0], 0.0);
        assertEquals(5.0, out[1], 1e-12);
        assertEquals(7.5, out[2], 1e-12);
        assertEquals(0.0, src.querySamples(KEY, 0, 1).getFirst().physicalValue(), 0.0);
        IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class,
                () -> TimeDomainTransforms.exponentialSmoothing(new double[] {1, 2}, 0, null));
        note("TC_ASP_009 PASS alpha=0.5 on [0,10,10] -> [0, 5, 7.5]; alpha=0 rejected: " + rejected.getMessage());
    }

    @Test
    void movingAverageWindowOfOneCopiesAndALargerWindowIsTheCausalMean() {
        InMemoryAnalysisSampleSource src = new InMemoryAnalysisSampleSource();
        src.add(KEY, 0, 1);
        src.add(KEY, SECOND, 2);
        src.add(KEY, 2 * SECOND, 3);
        AdvancedAnalyticsResult one = analytics.execute(average(1), src, null);
        AdvancedAnalyticsResult wide = analytics.execute(average(100), src, null);
        assertTrue(one.status().successful(), one.message());
        assertTrue(wide.status().successful(), wide.message());
        assertArray(one.derived().values(), 1, 2, 3);
        assertArray(wide.derived().values(), 1, 1.5, 2);
        IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class,
                () -> TimeDomainTransforms.movingAverage(new long[] {0}, new double[] {1}, 0, WindowUnit.SAMPLES, null));
        note("TC_ASP_011 PASS window of 1 sample copies [1,2,3]");
        note("TC_ASP_012 PASS window of 100 samples on 3 points is the causal mean [1, 1.5, 2]");
        note("TC_ASP_013 PASS window 0 rejected: " + rejected.getMessage());
    }

    @Test
    void resamplingUsesAnExplicitGapAndALinearMidpoint() {
        InMemoryAnalysisSampleSource src = new InMemoryAnalysisSampleSource();
        src.add(KEY, 0, 0);
        src.add(KEY, SECOND, 10);
        AdvancedAnalyticsResult omitted = analytics.execute(resample(2, 0), src, null);
        AdvancedAnalyticsResult tooSmall = analytics.execute(resample(2, 100_000_000L), src, null);
        AdvancedAnalyticsResult linear = analytics.execute(resample(2, 2 * SECOND), src, null);
        assertEquals(AnalysisStatus.FAILED, omitted.status(), omitted.message());
        assertTrue(omitted.message().toLowerCase(Locale.ROOT).contains("explicit maximum"), omitted.message());
        assertEquals(AnalysisStatus.FAILED, tooSmall.status(), tooSmall.message());
        assertTrue(linear.status().successful(), linear.message());
        double mid = valueNear(linear.derived(), SECOND / 2);
        assertEquals(5.0, mid, 1e-9);
        assertEquals(0.0, src.querySamples(KEY, 0, 1).getFirst().physicalValue(), 0.0);
        assertEquals(10.0, src.querySamples(KEY, 1, 1).getFirst().physicalValue(), 0.0);
        note("TC_ASP_014 PASS linear resample at 2 Hz");
        note("TC_ASP_015 PASS sample at 0.5 s is 5.0, halfway between 0 and 10");
        note("TC_ASP_027 PASS omitted gap: " + omitted.message());
        note("TC_ASP_027 PASS max gap 0.1 s across a 1 s interval: " + tooSmall.message());
        note("TC_ASP_028 PASS source values 0 and 10 unchanged after resampling");
    }

    @Test
    void psdAndCoherencePeakAtTheKnownTone() {
        InMemoryAnalysisSampleSource src = new InMemoryAnalysisSampleSource();
        sineOnto(src, KEY, 10, 200, 1.0);
        SignalCatalogKey other = key("RampCopy");
        sineOnto(src, other, 10, 200, 1.0);
        AdvancedAnalyticsResult psd = analytics.execute(AdvancedAnalyticsRequest.builder(AdvancedAnalyticsType.POWER_SPECTRAL_DENSITY)
                .signals(List.of(KEY))
                .window(new TimeWindow(0, SECOND))
                .welchSegmentLength(64)
                .welchOverlap(32)
                .build(), src, null);
        assertTrue(psd.status().successful(), psd.message());
        double peakHz = peakFrequency(psd);
        assertEquals(10.0, peakHz, 1.0);
        AdvancedAnalyticsResult coherence = analytics.execute(AdvancedAnalyticsRequest.builder(AdvancedAnalyticsType.COHERENCE)
                .signals(List.of(KEY, other))
                .window(new TimeWindow(0, SECOND))
                .welchSegmentLength(64)
                .welchOverlap(32)
                .build(), src, null);
        assertTrue(coherence.status().successful(), coherence.message());
        double atTone = nearestCoherence(coherence.coherence(), 10.0);
        assertTrue(atTone > 0.9, "coherence at 10 Hz was " + atTone);
        note("PSD no workbook id PASS 10 Hz sine, strongest bin " + peakHz + " Hz");
        note("COHERENCE no workbook id PASS identical 10 Hz channels, coherence at 10 Hz = " + atTone);
    }

    @Test
    void derivativeAndRateMatchHandDeltas() {
        InMemoryAnalysisSampleSource src = new InMemoryAnalysisSampleSource();
        src.add(KEY, 0, 0);
        src.add(KEY, SECOND, 2);
        src.add(KEY, 2 * SECOND, 6);
        DerivedSignalEngine.Series derived = DerivedSignalEngine.evaluate(
                src, new DerivedExpression.Rate(KEY), new TimeWindow(0, 3 * SECOND),
                null, 0, 10, null);
        List<Double> rates = derived.samples().stream().map(SignalSamplePoint::physicalValue).toList();
        assertEquals(List.of(2.0, 4.0), rates);
        AnalysisResult result = engine.execute(AnalysisRequest.builder(AnalysisType.RATE_OF_CHANGE)
                .addSignal(KEY)
                .build(), src, null);
        assertTrue(result.status().successful(), result.message());
        assertEquals(2d, result.metrics().get("ratePairs"), 0.0);
        assertEquals(2d, result.metrics().get("minRate"), 1e-9);
        assertEquals(4d, result.metrics().get("maxRate"), 1e-9);
        assertEquals(3d, result.metrics().get("meanAbsRate"), 1e-9);
        note("TC_ASP_030 PASS derivative [0,2,6] over 1 s steps -> 2 then 4 per second; mean absolute rate 3");
    }

    @Test
    void thresholdEventsMatchTheComparison() {
        InMemoryAnalysisSampleSource src = new InMemoryAnalysisSampleSource();
        src.add(KEY, 0, 1);
        src.add(KEY, SECOND, 4);
        src.add(KEY, 2 * SECOND, 9);
        AnalysisResult above = evaluate(src, ThresholdOperator.GREATER_THAN, 4);
        assertEquals(1, above.findings().size());
        assertEquals(2 * SECOND, above.findings().getFirst().startTimestampNanos());
        assertEquals(9d, above.findings().getFirst().numericMetrics().get("peak"), 0.0);
        AnalysisResult boundary = evaluate(src, ThresholdOperator.GREATER_OR_EQUAL, 4);
        assertEquals(1, boundary.findings().size());
        assertEquals(SECOND, boundary.findings().getFirst().startTimestampNanos());
        assertEquals(2 * SECOND, boundary.findings().getFirst().endTimestampNanos());
        assertEquals(9d, boundary.findings().getFirst().numericMetrics().get("peak"), 0.0);
        assertEquals(4d, boundary.findings().getFirst().numericMetrics().get("minimum"), 0.0);
        AnalysisResult notExceeded = evaluate(src, ThresholdOperator.GREATER_THAN, 9);
        assertEquals(0, notExceeded.findings().size());
        note("TC_EVT_001 PASS threshold rule evaluated to COMPLETED");
        note("TC_EVT_002 PASS value 9 > 4 is one event, peak 9, at 2.000 s");
        note("TC_EVT_003 PASS value 9 is not greater than 9, so no event");
        note("TC_EVT_004 PASS value 4 meets >= 4 and the event runs 1.000–2.000 s, peak 9, minimum 4");
    }

    @Test
    void integralIsNotAnAnalysisOperation() {
        boolean named = Arrays.stream(AdvancedAnalyticsType.values())
                .anyMatch(type -> type.name().contains("INTEGRAL"));
        assertFalse(named);
        note("TC_ASP_031 NOT RUN clarification: no integral operation is implemented. Not recorded as a defect.");
    }

    private String cutoffRejected() {
        InMemoryAnalysisSampleSource src = constant(3.0, 7L, 40, 0.05);
        AdvancedAnalyticsResult result = analytics.execute(filter(AdvancedAnalyticsType.LOW_PASS_FILTER, 0, 0, 2 * SECOND), src, null);
        assertEquals(AnalysisStatus.FAILED, result.status(), result.message());
        assertTrue(result.message().toLowerCase(Locale.ROOT).contains("cutoff"), result.message());
        return result.message();
    }

    private AnalysisResult evaluate(InMemoryAnalysisSampleSource src, ThresholdOperator operator, double threshold) {
        AnalysisResult result = engine.execute(AnalysisRequest.builder(AnalysisType.THRESHOLD)
                .addSignal(KEY)
                .parameters(AnalysisParameters.empty().withThreshold(operator, threshold))
                .build(), src, null);
        assertTrue(result.status().successful(), result.message());
        return result;
    }

    private static AdvancedAnalyticsRequest filter(AdvancedAnalyticsType type, double low, double high, long windowNanos) {
        return AdvancedAnalyticsRequest.builder(type)
                .signals(List.of(KEY))
                .window(new TimeWindow(0, windowNanos))
                .lowCutoffHz(low)
                .highCutoffHz(high)
                .filterOrder(2)
                .build();
    }

    private static AdvancedAnalyticsRequest average(double samples) {
        return AdvancedAnalyticsRequest.builder(AdvancedAnalyticsType.MOVING_AVERAGE)
                .signals(List.of(KEY))
                .window(new TimeWindow(0, 3 * SECOND))
                .smoothWindow(samples, WindowUnit.SAMPLES)
                .build();
    }

    private static AdvancedAnalyticsRequest resample(double hertz, long maxGapNanos) {
        var builder = AdvancedAnalyticsRequest.builder(AdvancedAnalyticsType.RESAMPLE)
                .signals(List.of(KEY))
                .window(new TimeWindow(0, 2 * SECOND))
                .targetRateHz(hertz)
                .resampleMethod(ResampleMethod.LINEAR_INTERPOLATION);
        if (maxGapNanos > 0) {
            builder.maxGapNanos(maxGapNanos);
        }
        return builder.build();
    }

    private static InMemoryAnalysisSampleSource constant(double value, long raw, int count, double stepSeconds) {
        InMemoryAnalysisSampleSource src = new InMemoryAnalysisSampleSource();
        List<SignalSamplePoint> points = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            points.add(new SignalSamplePoint(Math.round(i * stepSeconds * SECOND), value, raw, null, SignalValidity.VALID));
        }
        src.put(KEY, points);
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

    private static double valueNear(DerivedSeries series, long timestamp) {
        int best = 0;
        long distance = Long.MAX_VALUE;
        for (int i = 0; i < series.timestamps().length; i++) {
            long next = Math.abs(series.timestamps()[i] - timestamp);
            if (next < distance) {
                distance = next;
                best = i;
            }
        }
        assertTrue(distance < 1_000_000L, "no sample near the expected timestamp");
        return series.values()[best];
    }

    private static double peakFrequency(AdvancedAnalyticsResult result) {
        double[] frequency = result.psd().frequencyHz();
        double[] power = result.psd().powerDensity();
        int best = 1;
        for (int i = 1; i < power.length; i++) {
            if (power[i] > power[best]) {
                best = i;
            }
        }
        return frequency[best];
    }

    private static double nearestCoherence(CoherenceAnalyzer.Result result, double hz) {
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

    private static double interiorAbs(double[] values) {
        double max = 0;
        for (int i = 80; i < 120 && i < values.length; i++) {
            max = Math.max(max, Math.abs(values[i]));
        }
        return max;
    }

    private static void assertArray(double[] actual, double... expected) {
        assertEquals(expected.length, actual.length);
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], actual[i], 1e-12, "index " + i);
        }
    }

    private static SignalCatalogKey key(String name) {
        return new SignalCatalogKey(name, SignalSourceType.MF4_NATIVE, "MEAS", 0, 0, "synth.mf4", "");
    }

    private static void note(String line) {
        NOTES.add(line);
    }
}
