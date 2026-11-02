package com.stellar.platform.api.dto;

import java.util.List;

public record ChannelMatchDto(
        int channelId,
        String displayName,
        long observedUniqueMessageCount,
        long matchedUniqueMessageCount,
        double coverageRatio,
        String recommendationKind,
        String recommendedDefinitionId
) {
    public ChannelMatchDto {
        if (channelId < 0) {
            throw new IllegalArgumentException("channelId must be >= 0");
        }
        displayName = displayName == null ? "" : displayName;
        observedUniqueMessageCount = Math.max(0L, observedUniqueMessageCount);
        matchedUniqueMessageCount = Math.max(0L, matchedUniqueMessageCount);
        coverageRatio = Math.max(0.0, coverageRatio);
        recommendationKind = recommendationKind == null ? "" : recommendationKind;
        recommendedDefinitionId = recommendedDefinitionId == null ? "" : recommendedDefinitionId;
    }
}
