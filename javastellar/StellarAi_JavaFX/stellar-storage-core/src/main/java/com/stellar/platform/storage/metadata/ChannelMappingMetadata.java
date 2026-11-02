package com.stellar.platform.storage.metadata;

/** Persisted DBC decision for one source channel; contains metadata only. */
public record ChannelMappingMetadata(
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
    public ChannelMappingMetadata {
        projectId = requireText(projectId, "projectId");
        sourceId = requireText(sourceId, "sourceId");
        if (channelId < 0) throw new IllegalArgumentException("channelId must be >= 0");
        status = status == null ? "" : status;
        recommendationKind = recommendationKind == null ? "" : recommendationKind;
        observedUniqueMessageCount = Math.max(0L, observedUniqueMessageCount);
        matchedUniqueMessageCount = Math.max(0L, matchedUniqueMessageCount);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value;
    }
}
