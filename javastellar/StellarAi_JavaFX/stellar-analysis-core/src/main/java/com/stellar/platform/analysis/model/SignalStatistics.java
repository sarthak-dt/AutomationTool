package com.stellar.platform.analysis.model;

import com.stellar.platform.domain.catalog.SignalCatalogKey;

public record SignalStatistics(
        SignalCatalogKey signalKey,
        long sampleCount,
        long validCount,
        long missingCount,
        double min,
        double max,
        double mean,
        double variance,
        double standardDeviation,
        double rms,
        double range,
        double firstValue,
        double lastValue,
        double delta,
        long firstTimestampNanos,
        long lastTimestampNanos,
        double meanAbsRatePerSecond
) {
    public double durationSeconds() {
        if (validCount < 2 || lastTimestampNanos <= firstTimestampNanos) {
            return 0d;
        }
        return (lastTimestampNanos - firstTimestampNanos) / 1_000_000_000.0d;
    }
}
