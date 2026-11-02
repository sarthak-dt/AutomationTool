package com.stellar.platform.api.dto;

import java.util.List;

public record SignalViewportSeriesDto(
        String signalKey,
        String unit,
        long sourceSampleCount,
        boolean fullResolution,
        long viewportStartNanos,
        long viewportEndNanos,
        List<SignalBucketDto> buckets
) {
    public SignalViewportSeriesDto {
        signalKey = signalKey == null ? "" : signalKey;
        unit = unit == null ? "" : unit;
        sourceSampleCount = Math.max(0L, sourceSampleCount);
        buckets = buckets == null ? List.of() : List.copyOf(buckets);
    }
}
