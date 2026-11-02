package com.stellar.platform.desktop.project;

import java.util.Objects;
import java.util.Optional;

/** Recommendation + confirmation state for one SourceChannelKey. */
public final class SourceChannelMappingEntry {

    private final SourceChannelKey sourceChannelKey;
    private final ChannelMappingStatus status;
    private final ChannelRecommendationKind recommendationKind;
    private final DefinitionSourceId recommendedDefinitionId;
    private final String recommendedDisplayName;
    private final DefinitionSourceId mappedDefinitionId;
    private final String mappedDisplayName;
    private final long observedUniqueMessageCount;
    private final long matchedUniqueMessageCount;
    private final double coverageRatio;

    public SourceChannelMappingEntry(
            SourceChannelKey sourceChannelKey,
            ChannelMappingStatus status,
            ChannelRecommendationKind recommendationKind,
            DefinitionSourceId recommendedDefinitionId,
            String recommendedDisplayName,
            DefinitionSourceId mappedDefinitionId,
            String mappedDisplayName,
            long observedUniqueMessageCount,
            long matchedUniqueMessageCount,
            double coverageRatio
    ) {
        this.sourceChannelKey = Objects.requireNonNull(sourceChannelKey, "sourceChannelKey");
        this.status = Objects.requireNonNull(status, "status");
        this.recommendationKind = Objects.requireNonNull(recommendationKind, "recommendationKind");
        this.recommendedDefinitionId = recommendedDefinitionId;
        this.recommendedDisplayName = recommendedDisplayName == null ? "" : recommendedDisplayName;
        this.mappedDefinitionId = mappedDefinitionId;
        this.mappedDisplayName = mappedDisplayName == null ? "" : mappedDisplayName;
        this.observedUniqueMessageCount = Math.max(0L, observedUniqueMessageCount);
        this.matchedUniqueMessageCount = Math.max(0L, matchedUniqueMessageCount);
        this.coverageRatio = coverageRatio;
    }

    public SourceChannelKey sourceChannelKey() {
        return sourceChannelKey;
    }

    public ChannelMappingStatus status() {
        return status;
    }

    public ChannelRecommendationKind recommendationKind() {
        return recommendationKind;
    }

    public Optional<DefinitionSourceId> recommendedDefinitionId() {
        return Optional.ofNullable(recommendedDefinitionId);
    }

    public String recommendedDisplayName() {
        return recommendedDisplayName;
    }

    public Optional<DefinitionSourceId> mappedDefinitionId() {
        return Optional.ofNullable(mappedDefinitionId);
    }

    public String mappedDisplayName() {
        return mappedDisplayName;
    }

    public long observedUniqueMessageCount() {
        return observedUniqueMessageCount;
    }

    public long matchedUniqueMessageCount() {
        return matchedUniqueMessageCount;
    }

    public double coverageRatio() {
        return coverageRatio;
    }

    public double coveragePercent() {
        return coverageRatio * 100.0;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof SourceChannelMappingEntry that
                && sourceChannelKey.equals(that.sourceChannelKey)
                && status == that.status
                && recommendationKind == that.recommendationKind
                && Objects.equals(recommendedDefinitionId, that.recommendedDefinitionId)
                && Objects.equals(mappedDefinitionId, that.mappedDefinitionId)
                && observedUniqueMessageCount == that.observedUniqueMessageCount
                && matchedUniqueMessageCount == that.matchedUniqueMessageCount
                && Double.compare(coverageRatio, that.coverageRatio) == 0
                && recommendedDisplayName.equals(that.recommendedDisplayName)
                && mappedDisplayName.equals(that.mappedDisplayName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                sourceChannelKey,
                status,
                recommendationKind,
                recommendedDefinitionId,
                recommendedDisplayName,
                mappedDefinitionId,
                mappedDisplayName,
                observedUniqueMessageCount,
                matchedUniqueMessageCount,
                coverageRatio
        );
    }
}
