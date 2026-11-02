package com.stellar.platform.api.dto;

/** Transport form of a graph LOD bucket (first/min/max/last). */
public record SignalBucketDto(
        long firstTimestampNanos,
        double firstValue,
        long minTimestampNanos,
        double minValue,
        long maxTimestampNanos,
        double maxValue,
        long lastTimestampNanos,
        double lastValue,
        long sampleCount
) {
    public SignalBucketDto {
        sampleCount = Math.max(1L, sampleCount);
    }
}
