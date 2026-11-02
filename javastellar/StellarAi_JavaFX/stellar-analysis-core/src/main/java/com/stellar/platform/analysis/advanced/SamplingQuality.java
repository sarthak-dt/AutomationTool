package com.stellar.platform.analysis.advanced;

import java.util.Locale;

public record SamplingQuality(
        int sampleCount,
        long spanNanos,
        long medianIntervalNanos,
        long minIntervalNanos,
        long maxIntervalNanos,
        double sampleRateHz,
        double jitterNanos,
        boolean ordered,
        boolean timestampValid,
        boolean sufficientlyUniform,
        String assessment
) {
    public static SamplingQuality empty(String reason) {
        return new SamplingQuality(0, 0, 0, 0, 0, 0, 0, true, false, false, reason);
    }

    public double medianIntervalMs() {
        return medianIntervalNanos / 1_000_000.0d;
    }

    public String summary() {
        return String.format(Locale.ROOT,
                "Nominal sample rate: %.4g Hz%nMedian interval: %.4g ms%nJitter: ±%.4g ms%n%s",
                sampleRateHz, medianIntervalMs(), jitterNanos / 1_000_000.0d, assessment);
    }
}
