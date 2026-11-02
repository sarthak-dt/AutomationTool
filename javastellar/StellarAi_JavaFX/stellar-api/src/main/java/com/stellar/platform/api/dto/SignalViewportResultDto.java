package com.stellar.platform.api.dto;

import java.util.List;

public record SignalViewportResultDto(
        String projectId,
        long fromNanos,
        long toNanos,
        int pixelWidth,
        List<SignalViewportSeriesDto> series
) {
    public SignalViewportResultDto {
        if (projectId == null || projectId.isBlank()) {
            throw new IllegalArgumentException("projectId must not be blank");
        }
        pixelWidth = Math.max(1, pixelWidth);
        series = series == null ? List.of() : List.copyOf(series);
    }
}
