package com.stellar.platform.api.dto;

import java.util.List;

/** Transport form of {@link com.stellar.platform.domain.processing.plan.ObservedTimingMetadata}. */
public record ObservedTimingDto(
        Long recordingStartNanos,
        Long recordingEndNanos,
        String timeBaseLabel,
        String absoluteTimeMetadata
) {
    public ObservedTimingDto {
        timeBaseLabel = timeBaseLabel == null ? "" : timeBaseLabel;
        absoluteTimeMetadata = absoluteTimeMetadata == null ? "" : absoluteTimeMetadata;
    }
}
