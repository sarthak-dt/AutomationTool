package com.stellar.platform.api.dto;

public record UpdateChannelMappingRequest(
        String status,
        String mappedDefinitionId,
        String recommendationKind,
        String recommendedDefinitionId,
        long observedUniqueMessageCount,
        long matchedUniqueMessageCount,
        double coverageRatio
) {
    public UpdateChannelMappingRequest {
        status = status == null ? "" : status;
        mappedDefinitionId = mappedDefinitionId == null ? "" : mappedDefinitionId;
        recommendationKind = recommendationKind == null ? "" : recommendationKind;
        recommendedDefinitionId = recommendedDefinitionId == null ? "" : recommendedDefinitionId;
        observedUniqueMessageCount = Math.max(0L, observedUniqueMessageCount);
        matchedUniqueMessageCount = Math.max(0L, matchedUniqueMessageCount);
        coverageRatio = Math.max(0.0, coverageRatio);
    }
}
