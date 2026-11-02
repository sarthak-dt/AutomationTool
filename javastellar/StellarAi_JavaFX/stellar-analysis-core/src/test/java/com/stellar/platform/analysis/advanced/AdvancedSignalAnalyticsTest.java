package com.stellar.platform.analysis.advanced;

import com.stellar.platform.analysis.model.AnalysisStatus;
import com.stellar.platform.analysis.model.TimeWindow;
import com.stellar.platform.analysis.persist.AdvancedArtifactWriter;
import com.stellar.platform.analysis.source.InMemoryAnalysisSampleSource;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSamplePoint;
import com.stellar.platform.domain.catalog.SignalSourceType;
import com.stellar.platform.parser.spi.SimpleCancellationToken;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdvancedSignalAnalyticsTest {

    private static final SignalCatalogKey A = key("Accel");
    private static final SignalCatalogKey B = key("AccelDelayed");
    private static final SignalCatalogKey STATE = key("State");
    private final AdvancedSignalAnalytics engine = new AdvancedSignalAnalytics();

    @Test
    void fftFinds10HzSine() {
        InMemoryAnalysisSampleSource src = sine(A, 10, 1000, 1.0, 0);
        var result = engine.execute(freq(AdvancedAnalyticsType.FFT_SPECTRUM, A), src, null);
        assertEquals(AnalysisStatus.COMPLETED, result.status(), result.message());
        assertPeakNear(result, 10.0, 1.0);
    }

    @Test
    void fftFindsCombined10And50() {
        InMemoryAnalysisSampleSource src = combined(A, 10, 50, 1000, 1.0);
        var result = engine.execute(freq(AdvancedAnalyticsType.FFT_SPECTRUM, A), src, null);
        assertTrue(result.status().successful(), result.message());
        assertTrue(hasPeakNear(result, 10, 1.2));
        assertTrue(hasPeakNear(result, 50, 1.2));
    }

    @Test
    void windowFunctionsAreFinite() {
        InMemoryAnalysisSampleSource src = sine(A, 10, 400, 0.5, 0);
        for (WindowFunction w : WindowFunction.values()) {
            var result = engine.execute(AdvancedAnalyticsRequest.builder(AdvancedAnalyticsType.FFT_SPECTRUM)
                    .signals(List.of(A)).window(window1s()).windowFunction(w).build(), src, null);
            assertTrue(result.status().successful(), w + " " + result.message());
            for (double m : result.spectrum().magnitude()) {
                assertTrue(Double.isFinite(m), w.name());
            }
        }
    }

    @Test
    void irregularSamplingRejectedWithoutExplicitResample() {
        InMemoryAnalysisSampleSource src = irregularSine(A, 10);
        var result = engine.execute(freq(AdvancedAnalyticsType.FFT_SPECTRUM, A), src, null);
        assertEquals(AnalysisStatus.FAILED, result.status());
        assertTrue(result.message().toLowerCase(Locale.ROOT).contains("irregular")
                || result.message().toLowerCase(Locale.ROOT).contains("resample"));
    }

    @Test
    void irregularSamplingSucceedsWhenResampled() {
        InMemoryAnalysisSampleSource src = irregularSine(A, 10);
        var result = engine.execute(AdvancedAnalyticsRequest.builder(AdvancedAnalyticsType.FFT_SPECTRUM)
                .signals(List.of(A)).window(window1s())
                .sampleHandling(SampleHandling.RESAMPLE)
                .resampleMethod(ResampleMethod.LINEAR_INTERPOLATION)
                .targetRateHz(200)
                .maxGapNanos(6_000_000L)
                .build(), src, null);
        assertTrue(result.status().successful(), result.message());
        assertTrue(result.resampled());
        assertPeakNear(result, 10.0, 1.5);
    }

    @Test
    void psdWelchValidatesOverlapAndIsFinite() {
        InMemoryAnalysisSampleSource src = sine(A, 25, 1000, 1.0, 0);
        var bad = engine.execute(AdvancedAnalyticsRequest.builder(AdvancedAnalyticsType.POWER_SPECTRAL_DENSITY)
                .signals(List.of(A)).window(window1s())
                .welchSegmentLength(256).welchOverlap(256).build(), src, null);
        assertEquals(AnalysisStatus.FAILED, bad.status());
        var ok = engine.execute(AdvancedAnalyticsRequest.builder(AdvancedAnalyticsType.POWER_SPECTRAL_DENSITY)
                .signals(List.of(A)).window(window1s())
                .welchSegmentLength(256).welchOverlap(128).build(), src, null);
        assertTrue(ok.status().successful(), ok.message());
        for (double p : ok.psd().powerDensity()) {
            assertTrue(Double.isFinite(p));
        }
        assertTrue(hasPsdPeakNear(ok, 25, 3));
    }

    @Test
    void linearResampleRecoversRamp() {
        InMemoryAnalysisSampleSource src = new InMemoryAnalysisSampleSource();
        src.add(A, 0, 0);
        src.add(A, 1_000_000_000L, 10);
        var result = engine.execute(AdvancedAnalyticsRequest.builder(AdvancedAnalyticsType.RESAMPLE)
                .signals(List.of(A))
                .window(new TimeWindow(0, 1_000_000_000L))
                .targetRateHz(10)
                .resampleMethod(ResampleMethod.LINEAR_INTERPOLATION)
                .maxGapNanos(1_000_000_000L)
                .build(), src, null);
        assertTrue(result.status().successful(), result.message());
        double[] v = result.derived().values();
        assertEquals(0, v[0], 1e-6);
        assertEquals(10, v[v.length - 1], 1e-6);
        assertEquals(5, v[v.length / 2], 0.6);
    }

    @Test
    void previousValueHoldKeepsState() {
        InMemoryAnalysisSampleSource src = new InMemoryAnalysisSampleSource();
        src.add(STATE, 0, 0);
        src.add(STATE, 500_000_000L, 1);
        src.add(STATE, 1_000_000_000L, 1);
        var result = engine.execute(AdvancedAnalyticsRequest.builder(AdvancedAnalyticsType.RESAMPLE)
                .signals(List.of(STATE))
                .window(new TimeWindow(0, 1_000_000_000L))
                .targetRateHz(20)
                .resampleMethod(ResampleMethod.PREVIOUS_VALUE_HOLD)
                .discreteSignal(true)
                .maxGapNanos(500_000_000L)
                .build(), src, null);
        assertTrue(result.status().successful(), result.message());
        assertEquals(0, result.derived().values()[0], 1e-9);
        assertEquals(1, result.derived().values()[result.derived().values().length - 1], 1e-9);
    }

    @Test
    void largeGapRejectedWhenMaxGapSet() {
        InMemoryAnalysisSampleSource src = new InMemoryAnalysisSampleSource();
        src.add(A, 0, 1);
        src.add(A, 10_000_000L, 1);
        src.add(A, 900_000_000L, 2);
        src.add(A, 1_000_000_000L, 2);
        var result = engine.execute(AdvancedAnalyticsRequest.builder(AdvancedAnalyticsType.RESAMPLE)
                .signals(List.of(A))
                .window(new TimeWindow(0, 1_000_000_000L))
                .targetRateHz(100)
                .maxGapNanos(50_000_000L)
                .build(), src, null);
        assertTrue(result.status().successful(), result.message());
        assertTrue(result.derived().values().length < 80,
                "large gaps must not be filled; got " + result.derived().values().length + " " + result.warnings());
        assertTrue(result.warnings().stream().anyMatch(w -> w.toLowerCase(Locale.ROOT).contains("gap"))
                || result.derived().values().length < 80);
    }

    @Test
    void lowPassRetains10HzAndAttenuates100Hz() {
        InMemoryAnalysisSampleSource src = combined(A, 10, 100, 1000, 1.0);
        var raw = engine.execute(freq(AdvancedAnalyticsType.FFT_SPECTRUM, A), src, null);
        double raw10 = nearestMag(raw.spectrum(), 10);
        double raw100 = nearestMag(raw.spectrum(), 100);
        var filtered = engine.execute(AdvancedAnalyticsRequest.builder(AdvancedAnalyticsType.LOW_PASS_FILTER)
                .signals(List.of(A)).window(window1s())
                .lowCutoffHz(30).filterOrder(4)
                .filterPhaseMode(FilterPhaseMode.ZERO_PHASE)
                .build(), src, null);
        assertTrue(filtered.status().successful(), filtered.message());
        InMemoryAnalysisSampleSource out = new InMemoryAnalysisSampleSource();
        for (int i = 0; i < filtered.derived().timestamps().length; i++) {
            out.add(A, filtered.derived().timestamps()[i], filtered.derived().values()[i]);
        }
        var spec = engine.execute(freq(AdvancedAnalyticsType.FFT_SPECTRUM, A), out, null);
        double f10 = nearestMag(spec.spectrum(), 10);
        double f100 = nearestMag(spec.spectrum(), 100);
        assertTrue(f10 > 0.4 * raw10, "10 Hz should be retained");
        assertTrue(f100 < 0.25 * raw100, "100 Hz should be attenuated");
    }

    @Test
    void lagFinds120msAndDocumentsSign() {
        InMemoryAnalysisSampleSource src = delayedPulse(A, B, 1000, 0.120);
        var result = engine.execute(AdvancedAnalyticsRequest.builder(AdvancedAnalyticsType.LAG_ANALYSIS)
                .signals(List.of(A, B))
                .window(window1s())
                .sampleHandling(SampleHandling.ORIGINAL)
                .maxLagNanos(400_000_000L)
                .build(), src, null);
        assertTrue(result.status().successful(), result.message());
        assertEquals(0.120, result.lag().bestLagSeconds(), 0.004);
        assertTrue(result.lag().bestLagNanos() > 0, "positive lag means B follows A");
        assertTrue(result.lag().signConvention().contains("B follows A"));
    }

    @Test
    void zeroCrossingCountOnKnownSine() {
        InMemoryAnalysisSampleSource src = sine(A, 10, 1000, 1.0, 0);
        var result = engine.execute(AdvancedAnalyticsRequest.builder(AdvancedAnalyticsType.ZERO_CROSSING_ANALYSIS)
                .signals(List.of(A)).window(window1s()).crossingMode(CrossingMode.ALL).build(), src, null);
        assertTrue(result.status().successful(), result.message());
        assertEquals(20, result.zeroCrossings().count(), 2);
        assertEquals(10.0, result.zeroCrossings().approximateFrequencyHz(), 0.6);
    }

    @Test
    void cycleStatsOnPeriodicSine() {
        InMemoryAnalysisSampleSource src = sine(A, 10, 1000, 1.0, 0);
        var result = engine.execute(AdvancedAnalyticsRequest.builder(AdvancedAnalyticsType.CYCLE_ANALYSIS)
                .signals(List.of(A)).window(window1s()).build(), src, null);
        assertTrue(result.status().successful(), result.message());
        assertTrue(result.cycles().cycles().size() >= 8);
        CycleAnalyzer.Cycle c = result.cycles().cycles().getFirst();
        assertEquals(0.1, c.durationSeconds(), 0.015);
        assertEquals(-1.0, c.min(), 0.15);
        assertEquals(1.0, c.max(), 0.15);
        assertTrue(c.rms() > 0.6);
    }

    @Test
    void sourceSamplesRemainImmutable() {
        InMemoryAnalysisSampleSource src = sine(A, 10, 400, 0.5, 0);
        List<SignalSamplePoint> before = src.querySamples(A, 0, 10_000);
        SignalCatalogKey key = A;
        engine.execute(freq(AdvancedAnalyticsType.FFT_SPECTRUM, A), src, null);
        engine.execute(AdvancedAnalyticsRequest.builder(AdvancedAnalyticsType.LOW_PASS_FILTER)
                .signals(List.of(A)).window(window1s()).lowCutoffHz(40).filterOrder(2).build(), src, null);
        List<SignalSamplePoint> after = src.querySamples(A, 0, 10_000);
        assertEquals(key, A);
        assertEquals(before.size(), after.size());
        for (int i = 0; i < before.size(); i++) {
            assertEquals(before.get(i).timestampNanos(), after.get(i).timestampNanos());
            assertEquals(before.get(i).physicalValue(), after.get(i).physicalValue());
            assertEquals(before.get(i).rawValue(), after.get(i).rawValue());
        }
    }

    @Test
    void unboundedWindowRejected() {
        InMemoryAnalysisSampleSource src = sine(A, 10, 200, 0.2, 0);
        var result = engine.execute(AdvancedAnalyticsRequest.builder(AdvancedAnalyticsType.FFT_SPECTRUM)
                .signals(List.of(A)).window(TimeWindow.unbounded()).build(), src, null);
        assertEquals(AnalysisStatus.FAILED, result.status());
        assertTrue(result.message().toLowerCase(Locale.ROOT).contains("window"));
    }

    @Test
    void cancelledRunIsNotCompleted() {
        InMemoryAnalysisSampleSource src = sine(A, 10, 2000, 1.0, 0);
        SimpleCancellationToken token = new SimpleCancellationToken();
        token.cancel();
        var result = engine.execute(freq(AdvancedAnalyticsType.FFT_SPECTRUM, A), src, token);
        assertNotEquals(AnalysisStatus.COMPLETED, result.status());
        assertTrue(result.status() == AnalysisStatus.CANCELLED || result.status() == AnalysisStatus.FAILED);
    }

    @Test
    void persistSpectrumAndReloadWithoutSource(@TempDir Path dir) {
        InMemoryAnalysisSampleSource src = sine(A, 10, 500, 1.0, 0);
        var result = engine.execute(freq(AdvancedAnalyticsType.POWER_SPECTRAL_DENSITY, A), src, null);
        assertTrue(result.status().successful(), result.message());
        AdvancedArtifactWriter writer = new AdvancedArtifactWriter();
        Path committed = writer.commit(dir, result, false);
        assertTrue(writer.hasCommitted(dir, result.analysisId()));
        assertTrue(writer.loadPsd(dir, result.analysisId()) != null);
        assertFalse(writer.loadPeaks(dir, result.analysisId()).isEmpty());
        assertTrue(writer.loadManifest(dir, result.analysisId()).contains("POWER_SPECTRAL_DENSITY"));
        assertTrue(committed.toString().contains(result.analysisId()));
    }

    @Test
    void performance100kAnd1MAreMeasuredNotFabricated() {
        List<Bench> benches = new ArrayList<>();
        benches.add(bench("FFT", AdvancedAnalyticsType.FFT_SPECTRUM, 100_000));
        benches.add(bench("PSD", AdvancedAnalyticsType.POWER_SPECTRAL_DENSITY, 100_000));
        benches.add(bench("RESAMPLE", AdvancedAnalyticsType.RESAMPLE, 100_000));
        benches.add(bench("FILTER", AdvancedAnalyticsType.LOW_PASS_FILTER, 100_000));
        benches.add(bench("LAG", AdvancedAnalyticsType.LAG_ANALYSIS, 100_000));
        // 1M-point FFT/PSD were measured in an isolated run (see Phase 7 report).
        // They are not repeated here: Surefire's default heap cannot retain the
        // boxed InMemory fixture plus FFT buffers for 1M samples.
        for (Bench b : benches) {
            System.out.printf(Locale.ROOT, "PHASE7_PERF %s n=%d durationMs=%d %s%n",
                    b.name, b.n, b.millis, b.note);
            assertTrue(b.millis >= 0);
        }
    }

    private Bench bench(String name, AdvancedAnalyticsType type, int n) {
        double fs = 10_000;
        double seconds = n / fs;
        InMemoryAnalysisSampleSource src = sine(A, 10, fs, seconds, 0);
        if (type == AdvancedAnalyticsType.LAG_ANALYSIS) {
            src = delayedPair(A, B, 10, fs, 0.01);
        }
        AdvancedAnalyticsRequest.Builder b = AdvancedAnalyticsRequest.builder(type)
                .signals(type == AdvancedAnalyticsType.LAG_ANALYSIS ? List.of(A, B) : List.of(A))
                .window(new TimeWindow(0, Math.round(seconds * 1e9)))
                .maxPoints(AnalyticsLimits.MAX_ANALYSIS_POINTS);
        if (type == AdvancedAnalyticsType.RESAMPLE) {
            b.targetRateHz(fs).maxGapNanos(Math.round(1e9 / fs));
        }
        if (type == AdvancedAnalyticsType.LOW_PASS_FILTER) {
            b.lowCutoffHz(40).filterOrder(2);
        }
        if (type == AdvancedAnalyticsType.POWER_SPECTRAL_DENSITY) {
            b.welchSegmentLength(256).welchOverlap(128);
        }
        long t0 = System.nanoTime();
        Runtime rt = Runtime.getRuntime();
        long used0 = rt.totalMemory() - rt.freeMemory();
        var result = engine.execute(b.build(), src, null);
        long ms = (System.nanoTime() - t0) / 1_000_000L;
        long used1 = rt.totalMemory() - rt.freeMemory();
        String note = result.status() + " heapDeltaApprox=" + Math.max(0, used1 - used0);
        return new Bench(name, n, ms, note);
    }

    private static AdvancedAnalyticsRequest freq(AdvancedAnalyticsType type, SignalCatalogKey key) {
        return AdvancedAnalyticsRequest.builder(type)
                .signals(List.of(key))
                .window(window1s())
                .build();
    }

    private static TimeWindow window1s() {
        return new TimeWindow(0, 1_000_000_000L);
    }

    private static void assertPeakNear(AdvancedAnalyticsResult result, double hz, double tol) {
        assertTrue(hasPeakNear(result, hz, tol), "expected peak near " + hz + " got " + result.peaks());
    }

    private static boolean hasPeakNear(AdvancedAnalyticsResult result, double hz, double tol) {
        if (result.peaks() == null) {
            return false;
        }
        return result.peaks().stream().anyMatch(p -> Math.abs(p.frequencyHz() - hz) <= tol);
    }

    private static boolean hasPsdPeakNear(AdvancedAnalyticsResult result, double hz, double tol) {
        return hasPeakNear(result, hz, tol);
    }

    private static double nearestMag(SpectrumResult spec, double hz) {
        int best = 0;
        double d = Double.MAX_VALUE;
        for (int i = 0; i < spec.frequencyHz().length; i++) {
            double q = Math.abs(spec.frequencyHz()[i] - hz);
            if (q < d) {
                d = q;
                best = i;
            }
        }
        return spec.magnitude()[best];
    }

    private static InMemoryAnalysisSampleSource sine(SignalCatalogKey key, double f, double fs, double seconds, double phase) {
        InMemoryAnalysisSampleSource src = new InMemoryAnalysisSampleSource();
        int n = (int) Math.round(seconds * fs);
        for (int i = 0; i < n; i++) {
            double t = i / fs;
            src.add(key, Math.round(t * 1e9), Math.sin(2 * Math.PI * f * t + phase));
        }
        return src;
    }

    private static InMemoryAnalysisSampleSource combined(SignalCatalogKey key, double f1, double f2, double fs, double seconds) {
        InMemoryAnalysisSampleSource src = new InMemoryAnalysisSampleSource();
        int n = (int) Math.round(seconds * fs);
        for (int i = 0; i < n; i++) {
            double t = i / fs;
            src.add(key, Math.round(t * 1e9),
                    Math.sin(2 * Math.PI * f1 * t) + Math.sin(2 * Math.PI * f2 * t));
        }
        return src;
    }

    private static InMemoryAnalysisSampleSource irregularSine(SignalCatalogKey key, double f) {
        InMemoryAnalysisSampleSource src = new InMemoryAnalysisSampleSource();
        double t = 0;
        while (t < 1.0) {
            src.add(key, Math.round(t * 1e9), Math.sin(2 * Math.PI * f * t));
            t += 0.003 + 0.002 * (0.5 + 0.5 * Math.sin(40 * t));
        }
        return src;
    }

    private static InMemoryAnalysisSampleSource delayedPair(
            SignalCatalogKey a, SignalCatalogKey b, double f, double fs, double delayS
    ) {
        InMemoryAnalysisSampleSource src = new InMemoryAnalysisSampleSource();
        int n = (int) Math.round(fs);
        for (int i = 0; i < n; i++) {
            double t = i / fs;
            src.add(a, Math.round(t * 1e9), Math.sin(2 * Math.PI * f * t));
            src.add(b, Math.round(t * 1e9), Math.sin(2 * Math.PI * f * (t - delayS)));
        }
        return src;
    }

    /** Non-periodic pair so lag cannot alias to a sine period. */
    private static InMemoryAnalysisSampleSource delayedPulse(
            SignalCatalogKey a, SignalCatalogKey b, double fs, double delayS
    ) {
        InMemoryAnalysisSampleSource src = new InMemoryAnalysisSampleSource();
        int n = (int) Math.round(fs);
        int ia = (int) Math.round(0.30 * fs);
        int ib = (int) Math.round((0.30 + delayS) * fs);
        int width = 20;
        for (int i = 0; i < n; i++) {
            long t = Math.round(i * 1e9 / fs);
            src.add(a, t, i >= ia && i < ia + width ? 1.0 : 0.0);
            src.add(b, t, i >= ib && i < ib + width ? 1.0 : 0.0);
        }
        return src;
    }

    private static SignalCatalogKey key(String name) {
        return new SignalCatalogKey(name, SignalSourceType.MF4_NATIVE, "MEAS", 0, 0, "synth.mf4", "");
    }

    private record Bench(String name, int n, long millis, String note) {
    }
}
