package com.stellar.platform.analysis.advanced;

import com.stellar.platform.analysis.model.AnalysisStatus;
import com.stellar.platform.analysis.model.TimeWindow;
import com.stellar.platform.analysis.source.AnalysisCancelledException;
import com.stellar.platform.analysis.source.AnalysisSampleSource;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.parser.spi.CancellationToken;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Phase 7 facade. No JavaFX, no MF4 types, no desktop controllers.
 * Source samples are never rewritten.
 */
public final class AdvancedSignalAnalytics {

    public AdvancedAnalyticsResult execute(
            AdvancedAnalyticsRequest request,
            AnalysisSampleSource source,
            CancellationToken token
    ) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(source, "source");
        AdvancedAnalyticsResult.Builder out = AdvancedAnalyticsResult.builder(request.analysisId(), request.type())
                .window(request.window())
                .cacheKey(AdvancedCacheKey.of(request).digest());
        try {
            TimeWindow window = request.window();
            if (window == null || window.isUnbounded()) {
                throw new IllegalArgumentException(
                        "Advanced analytics require a finite time window (Analyze Current View or Cursor A→B).");
            }
            if (request.primaryKey() == null) {
                throw new IllegalArgumentException("Select at least one numeric signal");
            }
            out.stage("Reading samples");
            Prepared primary = prepare(request, source, request.primaryKey(), token, out);
            switch (request.type()) {
                case FFT_SPECTRUM, SPECTRAL_PEAKS -> spectrum(request, primary, null, out, token);
                case POWER_SPECTRAL_DENSITY -> psd(request, primary, out, token);
                case RESAMPLE -> resampleOnly(request, primary, out);
                case LOW_PASS_FILTER, HIGH_PASS_FILTER, BAND_PASS_FILTER -> filter(request, primary, out, token);
                case MOVING_AVERAGE -> movingAverage(request, primary, out, token);
                case EXPONENTIAL_SMOOTHING -> expSmooth(request, primary, out, token);
                case ENVELOPE -> envelope(request, primary, out, token);
                case WINDOWED_STATISTICS -> windowed(request, primary, out, token);
                case ZERO_CROSSING_ANALYSIS -> zeroCross(request, primary, out);
                case CYCLE_ANALYSIS -> cycles(request, primary, out);
                case LAG_ANALYSIS -> lag(request, source, primary, out, token);
                case COHERENCE -> coherence(request, source, primary, out, token);
            }
            if (request.signalKeys().size() >= 2
                    && (request.type() == AdvancedAnalyticsType.FFT_SPECTRUM
                    || request.type() == AdvancedAnalyticsType.SPECTRAL_PEAKS)) {
                Prepared second = prepare(request, source, request.signalKeys().get(1), token, out);
                requireContiguous(second, "FFT");
                SpectrumResult cmp = FftSpectrumAnalyzer.compute(
                        second.values, second.rateHz, request.windowFunction(), request.fftSize(),
                        second.start, second.end, request.sourceUnit(), token);
                out.comparisonSpectrum(cmp);
                out.stage("Two-signal spectra");
            }
            out.status(out.build().warnings().isEmpty() ? AnalysisStatus.COMPLETED : AnalysisStatus.COMPLETED_WITH_WARNINGS);
            out.message(request.type().name());
            out.reproducibility(reproducibility(request, primary));
        } catch (AnalysisCancelledException cancelled) {
            return out.status(AnalysisStatus.CANCELLED).completedAt(Instant.now())
                    .message("Cancelled").build();
        } catch (RuntimeException ex) {
            return out.status(AnalysisStatus.FAILED).completedAt(Instant.now())
                    .message(ex.getMessage() == null ? ex.toString() : ex.getMessage())
                    .warn(ex.getMessage() == null ? ex.toString() : ex.getMessage())
                    .build();
        }
        return out.completedAt(Instant.now()).build();
    }

    private static void spectrum(
            AdvancedAnalyticsRequest request,
            Prepared series,
            Prepared secondIgnored,
            AdvancedAnalyticsResult.Builder out,
            CancellationToken token
    ) {
        requireContiguous(series, "FFT");
        out.stage("Windowing");
        out.stage("FFT");
        SpectrumResult spec = FftSpectrumAnalyzer.compute(
                series.values, series.rateHz, request.windowFunction(), request.fftSize(),
                series.start, series.end, request.sourceUnit(), token);
        out.spectrum(spec);
        out.stage("Peak detection");
        double minSep = request.minPeakSeparationHz() > 0
                ? request.minPeakSeparationHz() : spec.frequencyResolutionHz();
        double maxF = Double.isFinite(request.freqMaxHz()) ? request.freqMaxHz() : spec.nyquistHz();
        List<SpectralPeak> peaks = SpectralPeakDetector.detect(
                spec.frequencyHz(), spec.magnitude(), request.minPeakMagnitude(),
                minSep, request.freqMinHz(), maxF, request.maxPeaks());
        peaks = SpectralPeakDetector.withHarmonics(peaks, request.harmonicCount());
        out.peaks(peaks);
    }

    private static void psd(
            AdvancedAnalyticsRequest request,
            Prepared series,
            AdvancedAnalyticsResult.Builder out,
            CancellationToken token
    ) {
        requireContiguous(series, "PSD");
        out.stage("Windowing");
        out.stage("Averaging PSD");
        PsdResult psd = WelchPsdAnalyzer.compute(
                series.values, series.rateHz, request.welchSegmentLength(), request.welchOverlap(),
                request.windowFunction(), series.start, series.end, request.sourceUnit(), token);
        out.psd(psd);
        out.stage("Peak detection");
        double minSep = request.minPeakSeparationHz() > 0
                ? request.minPeakSeparationHz() : psd.frequencyResolutionHz();
        double maxF = Double.isFinite(request.freqMaxHz()) ? request.freqMaxHz() : psd.nyquistHz();
        List<SpectralPeak> peaks = SpectralPeakDetector.detect(
                psd.frequencyHz(), psd.powerDensity(), request.minPeakMagnitude(),
                minSep, request.freqMinHz(), maxF, request.maxPeaks());
        out.peaks(SpectralPeakDetector.withHarmonics(peaks, request.harmonicCount()));
    }

    private static void resampleOnly(AdvancedAnalyticsRequest request, Prepared series, AdvancedAnalyticsResult.Builder out) {
        out.derived(derived(request, series, series.timestamps, series.values, "resample"));
    }

    private static void filter(
            AdvancedAnalyticsRequest request,
            Prepared series,
            AdvancedAnalyticsResult.Builder out,
            CancellationToken token
    ) {
        requireContiguous(series, "filter");
        out.stage("Filtering");
        ButterworthFilter.Result f = ButterworthFilter.apply(
                series.values, series.rateHz, request.type(),
                request.lowCutoffHz(), request.highCutoffHz(),
                request.filterOrder(), request.filterPhaseMode(), token);
        if (f.phaseMode() == FilterPhaseMode.CAUSAL) {
            out.warn(String.format(Locale.ROOT,
                    "Causal Butterworth: estimated group delay ≈ %.4g s. Phase is not zero.",
                    f.estimatedDelaySeconds()));
        } else {
            out.warn("Zero-phase forward–backward Butterworth. Edge transients may remain.");
        }
        String expr = AdvancedDerivedIdentity.filterExpression(request, series.key, series.rateHz);
        out.derived(derived(request, series, series.timestamps, f.values(), expr));
    }

    private static void movingAverage(
            AdvancedAnalyticsRequest request,
            Prepared series,
            AdvancedAnalyticsResult.Builder out,
            CancellationToken token
    ) {
        double[] y = TimeDomainTransforms.movingAverage(
                series.timestamps, series.values, request.smoothWindow(), request.smoothWindowUnit(), token);
        out.derived(derived(request, series, series.timestamps, y,
                "ma:" + request.smoothWindow() + request.smoothWindowUnit()));
    }

    private static void expSmooth(
            AdvancedAnalyticsRequest request,
            Prepared series,
            AdvancedAnalyticsResult.Builder out,
            CancellationToken token
    ) {
        double[] y = request.timeConstantSeconds() > 0
                ? TimeDomainTransforms.exponentialFromTimeConstant(
                series.timestamps, series.values, request.timeConstantSeconds(), token)
                : TimeDomainTransforms.exponentialSmoothing(series.values, request.alpha(), token);
        out.derived(derived(request, series, series.timestamps, y, "ema:" + request.alpha()));
    }

    private static void envelope(
            AdvancedAnalyticsRequest request,
            Prepared series,
            AdvancedAnalyticsResult.Builder out,
            CancellationToken token
    ) {
        double[] y = TimeDomainTransforms.envelope(
                series.timestamps, series.values, request.smoothWindow(), request.smoothWindowUnit(),
                request.envelopeMode(), token);
        out.derived(derived(request, series, series.timestamps, y, "envelope:" + request.envelopeMode()));
    }

    private static void windowed(
            AdvancedAnalyticsRequest request,
            Prepared series,
            AdvancedAnalyticsResult.Builder out,
            CancellationToken token
    ) {
        double[] y = TimeDomainTransforms.windowedStatistic(
                series.timestamps, series.values, request.smoothWindow(), request.smoothWindowUnit(),
                request.windowedStatistic(), token);
        out.derived(derived(request, series, series.timestamps, y, "winstat:" + request.windowedStatistic()));
    }

    private static void zeroCross(AdvancedAnalyticsRequest request, Prepared series, AdvancedAnalyticsResult.Builder out) {
        out.zeroCrossings(ZeroCrossingAnalyzer.analyze(series.timestamps, series.values, request.crossingMode()));
    }

    private static void cycles(AdvancedAnalyticsRequest request, Prepared series, AdvancedAnalyticsResult.Builder out) {
        out.cycles(CycleAnalyzer.fromRisingZeroCrossings(series.timestamps, series.values));
    }

    private static void lag(
            AdvancedAnalyticsRequest request,
            AnalysisSampleSource source,
            Prepared primary,
            AdvancedAnalyticsResult.Builder out,
            CancellationToken token
    ) {
        if (request.signalKeys().size() < 2) {
            throw new IllegalArgumentException("Lag analysis requires two signals");
        }
        Prepared b = prepare(request, source, request.signalKeys().get(1), token, out);
        alignPair(primary, b, request, out, token);
        requireContiguous(primary, "lag");
        requireContiguous(b, "lag");
        out.stage("Lag analysis");
        out.lag(FftLagAnalyzer.analyze(primary.values, b.values, primary.rateHz, request.maxLagNanos(), token));
    }

    private static void coherence(
            AdvancedAnalyticsRequest request,
            AnalysisSampleSource source,
            Prepared primary,
            AdvancedAnalyticsResult.Builder out,
            CancellationToken token
    ) {
        if (request.signalKeys().size() < 2) {
            throw new IllegalArgumentException("Coherence requires two signals");
        }
        Prepared b = prepare(request, source, request.signalKeys().get(1), token, out);
        alignPair(primary, b, request, out, token);
        requireContiguous(primary, "coherence");
        requireContiguous(b, "coherence");
        out.coherence(CoherenceAnalyzer.compute(
                primary.values, b.values, primary.rateHz,
                request.welchSegmentLength(), request.welchOverlap(), request.windowFunction(), token));
    }

    private static void alignPair(
            Prepared a,
            Prepared b,
            AdvancedAnalyticsRequest request,
            AdvancedAnalyticsResult.Builder out,
            CancellationToken token
    ) {
        double rate = request.targetRateHz() > 0
                ? request.targetRateHz()
                : Math.min(a.rateHz > 0 ? a.rateHz : 100, b.rateHz > 0 ? b.rateHz : 100);
        if (!a.uniform || !b.uniform || Math.abs(a.rateHz - b.rateHz) > 0.05 * Math.max(a.rateHz, b.rateHz)) {
            if (request.sampleHandling() != SampleHandling.RESAMPLE && request.targetRateHz() <= 0) {
                throw new IllegalArgumentException(
                        "Signals are not uniformly sampled at the same rate. "
                                + "Select Sample handling = Resample and a target rate.");
            }
            out.stage("Resampling");
            requireExplicitGap(request.maxGapNanos());
            Resampler.Result ra = Resampler.resample(a.timestamps, a.values, rate, request.resampleMethod(),
                    request.maxGapNanos(), request.alignOriginNanos(), request.discreteSignal(), token);
            Resampler.Result rb = Resampler.resample(b.timestamps, b.values, rate, request.resampleMethod(),
                    request.maxGapNanos(), request.alignOriginNanos(), request.discreteSignal(), token);
            if (ra.rejectedGaps() > 0 || rb.rejectedGaps() > 0) {
                throw new IllegalArgumentException(
                        "Resampling left gaps larger than the explicit maximum. "
                                + "Lag and coherence were not applied across those discontinuities.");
            }
            int n = Math.min(ra.values().length, rb.values().length);
            a.replace(copy(ra.timestamps(), n), copy(ra.values(), n), rate, true, request.resampleMethod());
            b.replace(copy(rb.timestamps(), n), copy(rb.values(), n), rate, true, request.resampleMethod());
            out.resampled(true, request.resampleMethod(), rate);
            out.warn("Both signals were resampled to " + rate + " Hz using " + request.resampleMethod());
        }
    }

    private static Prepared prepare(
            AdvancedAnalyticsRequest request,
            AnalysisSampleSource source,
            SignalCatalogKey key,
            CancellationToken token,
            AdvancedAnalyticsResult.Builder out
    ) {
        BoundedSampleBuffer.Series raw = BoundedSampleBuffer.collect(
                source, key, request.window(), request.maxPoints(), token)
                .limitingGaps(request.maxGapNanos());
        if (raw.nanCount() > 0) {
            out.warn(raw.nanCount() + " non-finite samples skipped on " + key.signalName());
        }
        if (raw.textCount() > 0) {
            out.warn(raw.textCount() + " text/enumerated samples skipped (not linearly interpolable)");
        }
        if (raw.excludedCount() > 0) {
            out.warn(raw.excludedCount()
                    + " ineligible samples excluded from numeric analysis on " + key.signalName());
        }
        SamplingQuality quality = SamplingAssessor.assess(raw.timestamps());
        out.sampling(quality);
        if (!quality.timestampValid() || !quality.ordered()) {
            throw new IllegalArgumentException(quality.assessment());
        }
        if (raw.segmentCount() > 1 && request.type() != AdvancedAnalyticsType.RESAMPLE) {
            throw new IllegalArgumentException(
                    raw.segmentCount() + " eligible intervals are separated by a discontinuity. "
                            + "Invalid, inactive, failed, categorical, unrecorded, or missing samples were not bridged.");
        }
        boolean frequency = isFrequency(request.type()) || request.type() == AdvancedAnalyticsType.LAG_ANALYSIS
                || request.type() == AdvancedAnalyticsType.COHERENCE
                || isFilter(request.type());
        boolean resampled = false;
        ResampleMethod method = null;
        long[] t = raw.timestamps();
        double[] v = raw.values();
        double rate = quality.sampleRateHz();
        if (frequency && !quality.sufficientlyUniform()) {
            if (request.sampleHandling() != SampleHandling.RESAMPLE) {
                throw new IllegalArgumentException(
                        "Sampling is irregular. Frequency-domain analysis was not applied to raw timestamps. "
                                + "Reject, or choose Sample handling = Resample. " + quality.assessment());
            }
            double target = request.targetRateHz() > 0 ? request.targetRateHz() : quality.sampleRateHz();
            if (!(target > 0)) {
                throw new IllegalArgumentException("Cannot estimate a target sample rate. Set one explicitly.");
            }
            out.stage("Resampling");
            Resampler.Result rr = resampleEligible(raw, target, request, token);
            if (rr.rejectedGaps() > 0) {
                throw new IllegalArgumentException(
                        rr.rejectedGaps() + " intervals were longer than the explicit maximum gap. "
                                + "The analysis was not applied across those discontinuities.");
            }
            t = rr.timestamps();
            v = rr.values();
            rate = target;
            resampled = true;
            method = request.resampleMethod();
            out.warn("Resampled with " + method + " at " + target + " Hz. Original timestamps were not treated as uniform.");
        } else if (request.type() == AdvancedAnalyticsType.RESAMPLE) {
            double target = request.targetRateHz();
            if (!(target > 0)) {
                throw new IllegalArgumentException("RESAMPLE requires targetRateHz > 0");
            }
            out.stage("Resampling");
            Resampler.Result rr = resampleEligible(raw, target, request, token);
            t = rr.timestamps();
            v = rr.values();
            rate = target;
            resampled = true;
            method = request.resampleMethod();
            if (rr.rejectedGaps() > 0 || raw.segmentCount() > 1) {
                out.warn("Resampling stayed inside eligible intervals and did not bridge "
                        + Math.max(rr.rejectedGaps(), raw.segmentCount() - 1) + " discontinuities");
            }
        }
        out.resampled(resampled, method, rate);
        return new Prepared(key, t, v, rate, quality.sufficientlyUniform() || resampled,
                quality, resampled, method, t.length == 0 ? 0 : t[0], t.length == 0 ? 0 : t[t.length - 1]);
    }

    private static void requireExplicitGap(long maxGapNanos) {
        if (maxGapNanos <= 0) {
            throw new IllegalArgumentException(
                    "Resampling requires an explicit maximum interpolation gap (maxGapNanos > 0). "
                            + "An omitted gap does not mean unlimited interpolation.");
        }
    }

    private static Resampler.Result resampleEligible(
            BoundedSampleBuffer.Series series,
            double targetRateHz,
            AdvancedAnalyticsRequest request,
            CancellationToken token
    ) {
        requireExplicitGap(request.maxGapNanos());
        if (series.segmentCount() <= 1) {
            return Resampler.resample(series.timestamps(), series.values(), targetRateHz, request.resampleMethod(),
                    request.maxGapNanos(), request.alignOriginNanos(), request.discreteSignal(), token);
        }
        java.util.List<long[]> partsT = new java.util.ArrayList<>();
        java.util.List<double[]> partsV = new java.util.ArrayList<>();
        int rejected = 0;
        int start = 0;
        for (int end : series.segmentEnds()) {
            if (end - start >= 2) {
                Resampler.Result part = Resampler.resample(
                        java.util.Arrays.copyOfRange(series.timestamps(), start, end),
                        java.util.Arrays.copyOfRange(series.values(), start, end),
                        targetRateHz, request.resampleMethod(), request.maxGapNanos(),
                        request.alignOriginNanos(), request.discreteSignal(), token);
                partsT.add(part.timestamps());
                partsV.add(part.values());
                rejected += part.rejectedGaps();
            }
            start = end;
        }
        if (partsT.isEmpty()) {
            throw new IllegalArgumentException(
                    "No eligible interval contains at least 2 samples. Resampling was not applied.");
        }
        int n = 0;
        for (long[] part : partsT) {
            n += part.length;
        }
        long[] t = new long[n];
        double[] v = new double[n];
        int cursor = 0;
        for (int i = 0; i < partsT.size(); i++) {
            System.arraycopy(partsT.get(i), 0, t, cursor, partsT.get(i).length);
            System.arraycopy(partsV.get(i), 0, v, cursor, partsV.get(i).length);
            cursor += partsT.get(i).length;
        }
        return new Resampler.Result(t, v, request.resampleMethod(), targetRateHz, true, request.maxGapNanos(), rejected);
    }

    private static void requireContiguous(Prepared series, String op) {
        if (!series.uniform) {
            throw new IllegalArgumentException(op + " requires uniform sampling. Resample explicitly first.");
        }
        if (!(series.rateHz > 0)) {
            throw new IllegalArgumentException(op + " requires a finite sample rate");
        }
        SamplingQuality continuity = SamplingAssessor.assess(series.timestamps);
        if (!continuity.sufficientlyUniform()) {
            throw new IllegalArgumentException(
                    op + " was not applied across a sampling gap. " + continuity.assessment());
        }
    }

    private static boolean isFrequency(AdvancedAnalyticsType type) {
        return type == AdvancedAnalyticsType.FFT_SPECTRUM
                || type == AdvancedAnalyticsType.POWER_SPECTRAL_DENSITY
                || type == AdvancedAnalyticsType.SPECTRAL_PEAKS;
    }

    private static boolean isFilter(AdvancedAnalyticsType type) {
        return type == AdvancedAnalyticsType.LOW_PASS_FILTER
                || type == AdvancedAnalyticsType.HIGH_PASS_FILTER
                || type == AdvancedAnalyticsType.BAND_PASS_FILTER;
    }

    private static DerivedSeries derived(
            AdvancedAnalyticsRequest request,
            Prepared series,
            long[] t,
            double[] v,
            String expression
    ) {
        String fp = AdvancedDerivedIdentity.fingerprint(expression + "|v=" + AdvancedAnalyticsVersion.CURRENT);
        return new DerivedSeries(
                AdvancedDerivedIdentity.catalogKey(expression, fp),
                expression, t, v, series.resampled, series.method, series.rateHz);
    }

    private static String reproducibility(AdvancedAnalyticsRequest request, Prepared series) {
        return "type=" + request.type()
                + " engine=" + AdvancedAnalyticsVersion.CURRENT
                + " signal=" + request.primaryKey()
                + " window=" + request.window().startNanos() + ":" + request.window().endNanos()
                + " fs=" + series.rateHz
                + " resample=" + (series.resampled ? series.method : "none")
                + " windowFn=" + request.windowFunction()
                + " fft=" + request.fftSize()
                + " welch=" + request.welchSegmentLength() + "/" + request.welchOverlap()
                + " filter=" + request.lowCutoffHz() + "/" + request.highCutoffHz() + "/o" + request.filterOrder()
                + " artifactGen=" + request.artifactGeneration();
    }

    private static long[] copy(long[] src, int n) {
        long[] out = new long[n];
        System.arraycopy(src, 0, out, 0, n);
        return out;
    }

    private static double[] copy(double[] src, int n) {
        double[] out = new double[n];
        System.arraycopy(src, 0, out, 0, n);
        return out;
    }

    private static final class Prepared {
        final SignalCatalogKey key;
        long[] timestamps;
        double[] values;
        double rateHz;
        boolean uniform;
        final SamplingQuality quality;
        boolean resampled;
        ResampleMethod method;
        long start;
        long end;

        Prepared(SignalCatalogKey key, long[] timestamps, double[] values, double rateHz, boolean uniform,
                 SamplingQuality quality, boolean resampled, ResampleMethod method, long start, long end) {
            this.key = key;
            this.timestamps = timestamps;
            this.values = values;
            this.rateHz = rateHz;
            this.uniform = uniform;
            this.quality = quality;
            this.resampled = resampled;
            this.method = method;
            this.start = start;
            this.end = end;
        }

        void replace(long[] t, double[] v, double rate, boolean resampledFlag, ResampleMethod m) {
            this.timestamps = t;
            this.values = v;
            this.rateHz = rate;
            this.uniform = true;
            this.resampled = resampledFlag;
            this.method = m;
            this.start = t.length == 0 ? 0 : t[0];
            this.end = t.length == 0 ? 0 : t[t.length - 1];
        }
    }
}
