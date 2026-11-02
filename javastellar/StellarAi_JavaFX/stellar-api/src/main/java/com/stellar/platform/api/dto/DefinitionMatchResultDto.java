package com.stellar.platform.api.dto;

import java.util.List;

public record DefinitionMatchResultDto(
        String projectId,
        String sourceId,
        String definitionAssetId,
        long globalObservedUnique,
        long globalMatchedUnique,
        double coverageRatio,
        List<ChannelMatchDto> channels
) {
    public DefinitionMatchResultDto {
        if (projectId == null || projectId.isBlank()) {
            throw new IllegalArgumentException("projectId must not be blank");
        }
        if (sourceId == null || sourceId.isBlank()) {
            throw new IllegalArgumentException("sourceId must not be blank");
        }
        definitionAssetId = definitionAssetId == null ? "" : definitionAssetId;
        globalObservedUnique = Math.max(0L, globalObservedUnique);
        globalMatchedUnique = Math.max(0L, globalMatchedUnique);
        coverageRatio = Math.max(0.0, coverageRatio);
        channels = channels == null ? List.of() : List.copyOf(channels);
    }
}
