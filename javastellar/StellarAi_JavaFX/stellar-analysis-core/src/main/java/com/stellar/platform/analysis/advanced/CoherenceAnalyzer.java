package com.stellar.platform.analysis.advanced;

import com.stellar.platform.analysis.source.AnalysisCancelledException;
import com.stellar.platform.parser.spi.CancellationToken;

/**
 * Magnitude-squared coherence via Welch cross-spectrum.
 * {@code Cxy(f) = |Pxy(f)|² / (Pxx(f) · Pyy(f))}.
 * This is not Pearson correlation.
 */
public final class CoherenceAnalyzer {

    public record Result(double[] frequencyHz, double[] coherence, int segmentLength, int overlap, int segments) {
    }

    private CoherenceAnalyzer() {
    }

    public static Result compute(
            double[] x,
            double[] y,
            double sampleRateHz,
            int segmentLength,
            int overlap,
            WindowFunction window,
            CancellationToken token
    ) {
        if (x == null || y == null) {
            throw new IllegalArgumentException("coherence requires two series");
        }
        int n = Math.min(x.length, y.length);
        if (n < 16) {
            throw new IllegalArgumentException("coherence requires at least 16 aligned samples");
        }
        AnalyticsLimits.requireFinite(sampleRateHz, "sampleRateHz");
        int seg = segmentLength > 0 ? segmentLength : Math.min(256, AnalyticsLimits.nextPow2(n / 4));
        if ((seg & (seg - 1)) != 0) {
            seg = AnalyticsLimits.nextPow2(seg);
        }
        if (overlap < 0 || overlap >= seg) {
            throw new IllegalArgumentException("overlap must satisfy 0 <= overlap < segment length");
        }
        int step = seg - overlap;
        if (step <= 0 || seg > n) {
            throw new IllegalArgumentException("insufficient samples for coherence Welch parameters");
        }
        int segments = 1 + (n - seg) / step;
        WindowFunction fn = window == null ? WindowFunction.HANN : window;
        double[] w = WindowFunctions.weights(fn, seg);
        int bins = seg / 2 + 1;
        double[] pxx = new double[bins];
        double[] pyy = new double[bins];
        double[] pxyR = new double[bins];
        double[] pxyI = new double[bins];
        double[] xr = new double[seg];
        double[] xi = new double[seg];
        double[] yr = new double[seg];
        double[] yi = new double[seg];
        for (int s = 0, off = 0; s < segments; s++, off += step) {
            if (token != null && token.isCancellationRequested()) {
                throw new AnalysisCancelledException();
            }
            java.util.Arrays.fill(xi, 0d);
            java.util.Arrays.fill(yi, 0d);
            for (int i = 0; i < seg; i++) {
                xr[i] = x[off + i] * w[i];
                yr[i] = y[off + i] * w[i];
            }
            RealFft.fft(xr, xi, token);
            RealFft.fft(yr, yi, token);
            for (int k = 0; k < bins; k++) {
                pxx[k] += xr[k] * xr[k] + xi[k] * xi[k];
                pyy[k] += yr[k] * yr[k] + yi[k] * yi[k];
                pxyR[k] += xr[k] * yr[k] + xi[k] * yi[k];
                pxyI[k] += xi[k] * yr[k] - xr[k] * yi[k];
            }
        }
        double[] freq = new double[bins];
        double[] c = new double[bins];
        for (int k = 0; k < bins; k++) {
            freq[k] = k * sampleRateHz / seg;
            double den = pxx[k] * pyy[k];
            c[k] = den <= 0 ? 0d : (pxyR[k] * pxyR[k] + pxyI[k] * pxyI[k]) / den;
            c[k] = Math.min(1d, Math.max(0d, c[k]));
        }
        return new Result(freq, c, seg, overlap, segments);
    }
}
