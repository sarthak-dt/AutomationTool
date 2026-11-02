package com.stellar.platform.analysis.advanced;

import com.stellar.platform.analysis.source.AnalysisCancelledException;
import com.stellar.platform.parser.spi.CancellationToken;

public final class FftSpectrumAnalyzer {

    public static final String NORMALIZATION =
            "one-sided amplitude; A[k]=|X[k]|/(N*cg) at DC/Nyquist, 2*|X[k]|/(N*cg) otherwise; "
                    + "X=FFT(x*w); cg=mean(w)";

    private FftSpectrumAnalyzer() {
    }

    public static SpectrumResult compute(
            double[] samples,
            double sampleRateHz,
            WindowFunction window,
            int requestedFftSize,
            long startNanos,
            long endNanos,
            String sourceUnit,
            CancellationToken token
    ) {
        if (samples == null || samples.length < 4) {
            throw new IllegalArgumentException("FFT requires at least 4 finite samples");
        }
        AnalyticsLimits.requireFinite(sampleRateHz, "sampleRateHz");
        if (sampleRateHz <= 0) {
            throw new IllegalArgumentException("sample rate must be > 0");
        }
        int n = samples.length;
        int fftSize = requestedFftSize > 0 ? requestedFftSize : AnalyticsLimits.nextPow2(n);
        if ((fftSize & (fftSize - 1)) != 0) {
            fftSize = AnalyticsLimits.nextPow2(fftSize);
        }
        if (fftSize > AnalyticsLimits.MAX_ANALYSIS_POINTS) {
            throw new IllegalArgumentException(
                    "FFT size " + fftSize + " exceeds safe limit " + AnalyticsLimits.MAX_ANALYSIS_POINTS
                            + ". Narrow the window or request a smaller FFT size.");
        }
        if (n > fftSize) {
            throw new IllegalArgumentException(
                    "Input has " + n + " samples but FFT size is " + fftSize
                            + ". Increase FFT size or narrow the window. No silent downsampling.");
        }
        WindowFunction fn = window == null ? WindowFunction.HANN : window;
        double[] w = WindowFunctions.weights(fn, n);
        double cg = WindowFunctions.coherentGain(w);
        if (cg == 0d) {
            cg = 1d;
        }
        double[] re = new double[fftSize];
        double[] im = new double[fftSize];
        for (int i = 0; i < n; i++) {
            if (token != null && (i & 0x3FF) == 0 && token.isCancellationRequested()) {
                throw new AnalysisCancelledException();
            }
            AnalyticsLimits.requireFinite(samples[i], "sample[" + i + "]");
            re[i] = samples[i] * w[i];
        }
        RealFft.fft(re, im, token);
        int bins = fftSize / 2 + 1;
        double[] freq = new double[bins];
        double[] mag = new double[bins];
        double[] phase = new double[bins];
        double scale = 1d / (fftSize * cg);
        for (int k = 0; k < bins; k++) {
            freq[k] = k * sampleRateHz / fftSize;
            double abs = Math.hypot(re[k], im[k]);
            boolean endpoint = k == 0 || k == fftSize / 2;
            mag[k] = (endpoint ? 1d : 2d) * abs * scale;
            phase[k] = Math.atan2(im[k], re[k]);
        }
        String magUnit = sourceUnit == null || sourceUnit.isBlank() ? "" : sourceUnit;
        return new SpectrumResult(
                freq, mag, phase, sampleRateHz, n, fftSize, fn,
                sampleRateHz / fftSize, sampleRateHz / 2d,
                startNanos, endNanos, magUnit, magUnit, NORMALIZATION, n < fftSize);
    }
}
