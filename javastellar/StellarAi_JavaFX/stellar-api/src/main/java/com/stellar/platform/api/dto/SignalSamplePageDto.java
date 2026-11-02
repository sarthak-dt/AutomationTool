package com.stellar.platform.api.dto;

import java.util.List;

public record SignalSamplePageDto(
        String projectId,
        String signalKey,
        int offset,
        int limit,
        int total,
        List<SignalSampleDto> samples
) {
    public SignalSamplePageDto {
        if (projectId == null || projectId.isBlank()) {
            throw new IllegalArgumentException("projectId must not be blank");
        }
        if (signalKey == null || signalKey.isBlank()) {
            throw new IllegalArgumentException("signalKey must not be blank");
        }
        offset = Math.max(0, offset);
        limit = Math.max(1, Math.min(limit <= 0 ? 1000 : limit, 5000));
        total = Math.max(0, total);
        samples = samples == null ? List.of() : List.copyOf(samples);
    }
}
