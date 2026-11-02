package com.stellar.platform.api.dto;

public record ChannelMappingDto(
        String projectId,
        String sourceId,
        int channelId,
        String status,
        String recommendationKind,
        String recommendedDefinitionId,
        String mappedDefinitionId,
        long observedUniqueMessageCount,
        long matchedUniqueMessageCount,
        double coverageRatio
) {
    public ChannelMappingDto {
        if (projectId == null || projectId.isBlank()) {
            throw new IllegalArgumentException("projectId must not be blank");
        }
        if (sourceId == null || sourceId.isBlank()) {
            throw new IllegalArgumentException("sourceId must not be blank");
        }
        if (channelId < 0) {
            throw new IllegalArgumentException("channelId must be >= 0");
        }
        status = status == null ? "" : status;
        recommendationKind = recommendationKind == null ? "" : recommendationKind;
        recommendedDefinitionId = recommendedDefinitionId == null ? "" : recommendedDefinitionId;
        mappedDefinitionId = mappedDefinitionId == null ? "" : mappedDefinitionId;
        observedUniqueMessageCount = Math.max(0L, observedUniqueMessageCount);
        matchedUniqueMessageCount = Math.max(0L, matchedUniqueMessageCount);
        coverageRatio = Math.max(0.0, coverageRatio);
    }
}
