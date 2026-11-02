package com.stellar.platform.api.dto;

/**
 * Existing AutomotiveAnalysisEngine features over a bounded viewport — not new metrics.
 */
public record SignalFeatureDto(
        String signal,
        String unit,
        long representedSamples,
        double min,
        double max,
        double mean,
        double median,
        double variance,
        double standardDeviation,
        double rms,
        double p05,
        double p95,
        double slopePerSecond,
        double driftPerMinute,
        double meanAbsRatePerSecond,
        int peakCount,
        int valleyCount,
        int stepChangeCount,
        int outlierCount,
        double jitter,
        double oscillationHz
) {
    public SignalFeatureDto {
        signal = signal == null ? "" : signal;
        unit = unit == null ? "" : unit;
        representedSamples = Math.max(0L, representedSamples);
    }
}
