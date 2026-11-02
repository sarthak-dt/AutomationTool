package com.stellar.platform.analysis.advanced;

/** Bounded-memory ceilings. Frequency analysis never loads a 10 GB series. */
public final class AnalyticsLimits {

    public static final int MAX_ANALYSIS_POINTS = 1_048_576;
    public static final int MAX_RESAMPLE_OUTPUT = 1_048_576;
    public static final int MAX_INTERVAL_SAMPLES = 100_000;
    public static final int MAX_SPECTRUM_DISPLAY_BINS = 2_048;
    public static final double MAX_TARGET_RATE_HZ = 100_000d;
    public static final double MIN_TARGET_RATE_HZ = 1e-6;

    private AnalyticsLimits() {
    }

    public static int nextPow2(int n) {
        if (n <= 1) {
            return 1;
        }
        int p = 1;
        while (p < n) {
            p <<= 1;
            if (p <= 0) {
                throw new IllegalArgumentException("FFT size overflow");
            }
        }
        return p;
    }

    public static void requireFinite(double value, String name) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }
}
