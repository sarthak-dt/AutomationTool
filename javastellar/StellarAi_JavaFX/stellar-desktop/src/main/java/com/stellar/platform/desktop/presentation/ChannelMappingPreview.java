package com.stellar.platform.desktop.presentation;

import com.stellar.platform.desktop.project.ChannelMappingStatus;
import com.stellar.platform.desktop.project.DefinitionSourceId;
import com.stellar.platform.desktop.project.SourceChannelDefinitionCandidate;
import com.stellar.platform.desktop.project.SourceChannelDefinitionComparison;
import com.stellar.platform.desktop.project.SourceChannelKey;
import com.stellar.platform.desktop.project.SourceChannelMappingEntry;

import java.util.Optional;

/**
 * Decision Workspace mapping preview: observed / matched CAN-ID counts for one
 * {@link SourceChannelKey} &times; {@link DefinitionSourceId}.
 * Reads existing comparison (preferred), then candidate, then mapping counts.
 * Never keyed by channel number, filename, or alias alone.
 */
public final class ChannelMappingPreview {

    public record Snapshot(
            SourceChannelKey key,
            DefinitionSourceId definitionId,
            long observedCount,
            long matchedCount,
            double coverageRatio
    ) {
    }

    private ChannelMappingPreview() {
    }

    /**
     * Resolve matched/observed counts. Empty when identity is incomplete or no
     * existing comparison/candidate/mapping applies to that exact pair.
     * {@code matchedCount == 0} is a present snapshot (genuine zero matches).
     */
    public static Optional<Snapshot> resolve(
            SourceChannelKey key,
            DefinitionSourceId definitionId,
            SourceChannelDefinitionComparison comparison,
            SourceChannelDefinitionCandidate candidate,
            SourceChannelMappingEntry mapping
    ) {
        Optional<Snapshot> fromComparison = ofComparison(key, definitionId, comparison);
        if (fromComparison.isPresent()) {
            return fromComparison;
        }
        Optional<Snapshot> fromCandidate = ofCandidate(key, definitionId, candidate);
        if (fromCandidate.isPresent()) {
            return fromCandidate;
        }
        return ofMapping(key, definitionId, mapping);
    }

    public static Optional<Snapshot> ofComparison(
            SourceChannelKey key,
            DefinitionSourceId definitionId,
            SourceChannelDefinitionComparison comparison
    ) {
        if (key == null || definitionId == null || comparison == null) {
            return Optional.empty();
        }
        if (!key.equals(comparison.sourceChannelKey())
                || !definitionId.equals(comparison.definitionSourceId())) {
            return Optional.empty();
        }
        return Optional.of(new Snapshot(
                key,
                definitionId,
                comparison.observedUniqueMessageCount(),
                comparison.matchedUniqueMessageCount(),
                comparison.coverageRatio()));
    }

    public static Optional<Snapshot> ofCandidate(
            SourceChannelKey key,
            DefinitionSourceId definitionId,
            SourceChannelDefinitionCandidate candidate
    ) {
        if (key == null || definitionId == null || candidate == null) {
            return Optional.empty();
        }
        if (!key.equals(candidate.sourceChannelKey())
                || !definitionId.equals(candidate.definitionSourceId())) {
            return Optional.empty();
        }
        return Optional.of(new Snapshot(
                key,
                definitionId,
                candidate.observedUniqueMessageCount(),
                candidate.matchedUniqueMessageCount(),
                candidate.coverageRatio()));
    }

    public static Optional<Snapshot> ofMapping(
            SourceChannelKey key,
            DefinitionSourceId definitionId,
            SourceChannelMappingEntry mapping
    ) {
        if (key == null || definitionId == null || mapping == null) {
            return Optional.empty();
        }
        if (!key.equals(mapping.sourceChannelKey())) {
            return Optional.empty();
        }
        if (!mappingUsesDefinition(mapping, definitionId)) {
            return Optional.empty();
        }
        return Optional.of(new Snapshot(
                key,
                definitionId,
                mapping.observedUniqueMessageCount(),
                mapping.matchedUniqueMessageCount(),
                mapping.coverageRatio()));
    }

    private static boolean mappingUsesDefinition(
            SourceChannelMappingEntry mapping,
            DefinitionSourceId definitionId
    ) {
        ChannelMappingStatus status = mapping.status();
        if (status == ChannelMappingStatus.CONFIRMED || status == ChannelMappingStatus.MANUAL) {
            return mapping.mappedDefinitionId().isPresent()
                    && mapping.mappedDefinitionId().get().equals(definitionId);
        }
        if (status == ChannelMappingStatus.RECOMMENDED) {
            return mapping.recommendedDefinitionId().isPresent()
                    && mapping.recommendedDefinitionId().get().equals(definitionId);
        }
        return false;
    }
}
