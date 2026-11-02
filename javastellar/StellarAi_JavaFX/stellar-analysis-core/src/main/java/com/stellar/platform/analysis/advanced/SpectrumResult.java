package com.stellar.platform.analysis.advanced;

/**
 * One-sided real spectrum.
 *
 * <p>Normalization (amplitude, window-gain corrected):
 * {@code A[k] = |X[k]| / (N · cg)} for DC and Nyquist,
 * {@code A[k] = 2 · |X[k]| / (N · cg)} otherwise,
 * where {@code X = FFT(x · w)}, {@code cg = mean(w)}, {@code N} is the FFT length.
 *
 * <p>Frequency resolution {@code Δf = fs / N}. Nyquist {@code fs / 2}.
 */
public record SpectrumResult(
        double[] frequencyHz,
        double[] magnitude,
        double[] phaseRadians,
        double sampleRateHz,
        int sampleCount,
        int fftSize,
        WindowFunction window,
        double frequencyResolutionHz,
        double nyquistHz,
        long startNanos,
        long endNanos,
        String sourceUnit,
        String magnitudeUnit,
        String normalization,
        boolean zeroPadded
) {
    public int binCount() {
        return frequencyHz == null ? 0 : frequencyHz.length;
    }
}
