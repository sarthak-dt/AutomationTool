package com.stellar.platform.analysis.advanced;

import com.stellar.platform.analysis.source.AnalysisCancelledException;
import com.stellar.platform.parser.spi.CancellationToken;

/**
 * FFT-based cross-correlation lag search. Not O(N²).
 *
 * <p>Sign convention (used everywhere in Phase 7):
 * {@code +lag} means <b>B follows A</b> by that duration
 * (B is delayed relative to A). Example: {@code +120 ms} ⇒ B(t) ≈ A(t − 120 ms).
 */
public final class FftLagAnalyzer {

    public static final String SIGN_CONVENTION = "+lag means B follows A (B is delayed relative to A)";

    public record Result(
            long bestLagNanos,
            double bestLagSeconds,
            double correlationAtLag,
            int pairCount,
            double sampleRateHz,
            long maxLagNanos,
            String signConvention,
            String quality
    ) {
    }

    private FftLagAnalyzer() {
    }

    public static Result analyze(
            double[] a,
            double[] b,
            double sampleRateHz,
            long maxLagNanos,
            CancellationToken token
    ) {
        if (a == null || b == null || a.length < 8 || b.length < 8) {
            throw new IllegalArgumentException("lag analysis requires at least 8 aligned samples per signal");
        }
        AnalyticsLimits.requireFinite(sampleRateHz, "sampleRateHz");
        if (sampleRateHz <= 0) {
            throw new IllegalArgumentException("sample rate must be > 0");
        }
        if (maxLagNanos <= 0) {
            throw new IllegalArgumentException("maximum lag must be > 0");
        }
        int n = Math.min(a.length, b.length);
        if (n > AnalyticsLimits.MAX_ANALYSIS_POINTS) {
            throw new IllegalArgumentException("lag pair count exceeds " + AnalyticsLimits.MAX_ANALYSIS_POINTS);
        }
        double[] xa = demean(a, n);
        double[] xb = demean(b, n);
        int fftSize = AnalyticsLimits.nextPow2(2 * n);
        if (fftSize > AnalyticsLimits.MAX_ANALYSIS_POINTS * 2) {
            throw new IllegalArgumentException("lag FFT size exceeds safe memory bound");
        }
        double[] ar = new double[fftSize];
        double[] ai = new double[fftSize];
        double[] br = new double[fftSize];
        double[] bi = new double[fftSize];
        System.arraycopy(xa, 0, ar, 0, n);
        System.arraycopy(xb, 0, br, 0, n);
        RealFft.fft(ar, ai, token);
        RealFft.fft(br, bi, token);
        // IFFT(FFT(B) * conj(FFT(A)))  →  peak at +k when B follows A by k samples
        double[] pr = new double[fftSize];
        double[] pi = new double[fftSize];
        for (int i = 0; i < fftSize; i++) {
            if (token != null && (i & 0x3FF) == 0 && token.isCancellationRequested()) {
                throw new AnalysisCancelledException();
            }
            pr[i] = br[i] * ar[i] + bi[i] * ai[i];
            pi[i] = bi[i] * ar[i] - br[i] * ai[i];
        }
        RealFft.ifft(pr, pi, token);
        double na = norm(xa);
        double nb = norm(xb);
        double denom = na * nb;
        if (denom <= 0) {
            throw new IllegalArgumentException("signals have zero energy after mean removal");
        }
        int maxLagSamples = (int) Math.min(n - 1, Math.max(1L, Math.round(maxLagNanos * sampleRateHz / 1e9)));
        int bestK = 0;
        double best = Double.NEGATIVE_INFINITY;
        for (int k = -maxLagSamples; k <= maxLagSamples; k++) {
            int idx = k >= 0 ? k : fftSize + k;
            double corr = pr[idx] / denom;
            if (corr > best) {
                best = corr;
                bestK = k;
            }
        }
        long lagNanos = Math.round(bestK * 1e9 / sampleRateHz);
        String quality = Math.abs(best) >= 0.7d ? "strong"
                : Math.abs(best) >= 0.4d ? "moderate"
                : "weak";
        return new Result(lagNanos, lagNanos / 1e9, best, n, sampleRateHz, maxLagNanos, SIGN_CONVENTION, quality);
    }

    private static double[] demean(double[] x, int n) {
        double m = 0d;
        for (int i = 0; i < n; i++) {
            m += x[i];
        }
        m /= n;
        double[] y = new double[n];
        for (int i = 0; i < n; i++) {
            y[i] = x[i] - m;
        }
        return y;
    }

    private static double norm(double[] x) {
        double s = 0d;
        for (double v : x) {
            s += v * v;
        }
        return Math.sqrt(s);
    }
}
