package com.stellar.platform.analysis.advanced;

import com.stellar.platform.analysis.source.AnalysisCancelledException;
import com.stellar.platform.parser.spi.CancellationToken;

public final class WelchPsdAnalyzer {

    public static final String METHOD =
            "Welch one-sided; P[k]=|FFT(x*w)|²/(fs*sum(w²)); ×2 except DC/Nyquist; averaged segments";

    private WelchPsdAnalyzer() {
    }

    public static PsdResult compute(
            double[] samples,
            double sampleRateHz,
            int segmentLength,
            int overlap,
            WindowFunction window,
            long startNanos,
            long endNanos,
            String sourceUnit,
            CancellationToken token
    ) {
        if (samples == null || samples.length < 8) {
            throw new IllegalArgumentException("Welch PSD requires at least 8 samples");
        }
        AnalyticsLimits.requireFinite(sampleRateHz, "sampleRateHz");
        if (sampleRateHz <= 0 || !Double.isFinite(sampleRateHz)) {
            throw new IllegalArgumentException("sample rate must be finite and > 0");
        }
        int n = samples.length;
        int seg = segmentLength > 0 ? segmentLength : Math.min(256, AnalyticsLimits.nextPow2(n / 4));
        if ((seg & (seg - 1)) != 0) {
            seg = AnalyticsLimits.nextPow2(seg);
        }
        if (seg < 8) {
            throw new IllegalArgumentException("Welch segment length must be >= 8");
        }
        if (seg > n) {
            throw new IllegalArgumentException(
                    "Welch segment length " + seg + " exceeds sample count " + n);
        }
        if (overlap < 0 || overlap >= seg) {
            throw new IllegalArgumentException("overlap must satisfy 0 <= overlap < segment length");
        }
        int step = seg - overlap;
        if (step <= 0) {
            throw new IllegalArgumentException("Welch hop size must be > 0");
        }
        int segments = 1 + (n - seg) / step;
        if (segments < 1) {
            throw new IllegalArgumentException("insufficient samples for requested Welch parameters");
        }
        WindowFunction fn = window == null ? WindowFunction.HANN : window;
        double[] w = WindowFunctions.weights(fn, seg);
        double wss = WindowFunctions.sumSquares(w);
        if (wss <= 0) {
            throw new IllegalArgumentException("window energy is zero");
        }
        int bins = seg / 2 + 1;
        double[] acc = new double[bins];
        double[] re = new double[seg];
        double[] im = new double[seg];
        double scale = 1d / (sampleRateHz * wss);
        for (int s = 0, off = 0; s < segments; s++, off += step) {
            if (token != null && token.isCancellationRequested()) {
                throw new AnalysisCancelledException();
            }
            java.util.Arrays.fill(im, 0d);
            for (int i = 0; i < seg; i++) {
                AnalyticsLimits.requireFinite(samples[off + i], "sample");
                re[i] = samples[off + i] * w[i];
            }
            RealFft.fft(re, im, token);
            for (int k = 0; k < bins; k++) {
                double p = (re[k] * re[k] + im[k] * im[k]) * scale;
                if (k > 0 && k < seg / 2) {
                    p *= 2d;
                }
                acc[k] += p;
            }
        }
        double[] freq = new double[bins];
        double[] psd = new double[bins];
        for (int k = 0; k < bins; k++) {
            freq[k] = k * sampleRateHz / seg;
            psd[k] = acc[k] / segments;
            if (!Double.isFinite(psd[k])) {
                throw new IllegalStateException("non-finite PSD bin " + k);
            }
        }
        String density = densityUnit(sourceUnit);
        return new PsdResult(freq, psd, sampleRateHz, n, seg, overlap, segments, fn,
                sampleRateHz / seg, sampleRateHz / 2d, startNanos, endNanos,
                sourceUnit == null ? "" : sourceUnit, density, METHOD);
    }

    static String densityUnit(String sourceUnit) {
        if (sourceUnit == null || sourceUnit.isBlank()) {
            return "";
        }
        return "(" + sourceUnit + ")^2/Hz";
    }
}
