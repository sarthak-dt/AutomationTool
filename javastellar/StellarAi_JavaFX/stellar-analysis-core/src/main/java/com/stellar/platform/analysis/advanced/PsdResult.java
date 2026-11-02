package com.stellar.platform.analysis.advanced;

/**
 * Welch one-sided PSD.
 *
 * <p>Per-segment scaling: {@code P[k] = |X[k]|² / (fs · Σ w²)}.
 * One-sided: multiply by 2 except DC and Nyquist. Segments are averaged.
 * When the source unit {@code U} is known, density unit is {@code (U)²/Hz}.
 */
public record PsdResult(
        double[] frequencyHz,
        double[] powerDensity,
        double sampleRateHz,
        int sampleCount,
        int segmentLength,
        int overlap,
        int segmentCount,
        WindowFunction window,
        double frequencyResolutionHz,
        double nyquistHz,
        long startNanos,
        long endNanos,
        String sourceUnit,
        String densityUnit,
        String method
) {
}
