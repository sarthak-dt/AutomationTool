package com.stellar.platform.api.dto;

import java.util.List;

public record SignalStatsResultDto(
        String projectId,
        long fromNanos,
        long toNanos,
        int pixelWidth,
        List<SignalFeatureDto> features
) {
    public SignalStatsResultDto {
        if (projectId == null || projectId.isBlank()) {
            throw new IllegalArgumentException("projectId must not be blank");
        }
        features = features == null ? List.of() : List.copyOf(features);
    }
}
