package com.stellar.platform.desktop.presentation;

import com.stellar.platform.desktop.project.ChannelMappingStatus;
import com.stellar.platform.desktop.project.ChannelRecommendationKind;
import com.stellar.platform.desktop.project.DefinitionCandidateKind;
import com.stellar.platform.desktop.project.DefinitionSourceId;
import com.stellar.platform.desktop.project.SourceChannelDefinitionCandidate;
import com.stellar.platform.desktop.project.SourceChannelKey;
import com.stellar.platform.desktop.project.SourceChannelMappingEntry;
import com.stellar.platform.domain.processing.plan.MappingLifecycle;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Pure resolver for the Quick Decode processing plan. */
public final class QuickDecodeDecisionResolver {

    public enum Status {
        READY,
        NO_MATCH,
        REVIEW,
        NOT_APPLICABLE,
        EXCLUDED
    }

    public enum SelectionOrigin {
        AUTO_RECOMMENDATION,
        MANUAL_OVERRIDE,
        NONE
    }

    public record Decision(
            SourceChannelKey key,
            boolean included,
            SourceChannelDefinitionCandidate recommendation,
            SourceChannelDefinitionCandidate selected,
            SelectionOrigin origin,
            Status status,
            boolean autoSelectedWithWarning
    ) {
        public Decision(
                SourceChannelKey key,
                boolean included,
                SourceChannelDefinitionCandidate recommendation,
                SourceChannelDefinitionCandidate selected,
                SelectionOrigin origin,
                Status status
        ) {
            this(key, included, recommendation, selected, origin, status, false);
        }

        public boolean decodable() {
            return included && status == Status.READY && selected != null
                    && selected.matchedUniqueMessageCount() > 0L;
        }

        /**
         * Included Auto Decode channel with CAN inventory but no usable DBC overlap.
         * Native/raw processing remains valid; DBC-decoded CAN signals will not be generated.
         */
        public boolean processableWithoutDbc() {
            return included && status == Status.NO_MATCH;
        }

        /** Channel may enter the Auto Decode processing plan. */
        public boolean processable() {
            return decodable() || processableWithoutDbc();
        }

        public boolean manualOverride() {
            return origin == SelectionOrigin.MANUAL_OVERRIDE;
        }

        public MappingLifecycle planLifecycle() {
            if (status == Status.REVIEW) {
                return MappingLifecycle.AMBIGUOUS;
            }
            if (status == Status.NOT_APPLICABLE) {
                return MappingLifecycle.NOT_APPLICABLE;
            }
            if (status == Status.NO_MATCH) {
                return MappingLifecycle.NO_MATCH;
            }
            if (selected == null) {
                return MappingLifecycle.UNMAPPED;
            }
            return manualOverride() ? MappingLifecycle.MANUAL : MappingLifecycle.RECOMMENDED_FALLBACK;
        }
    }

    private final ProjectDefinitionMatchingModel matchingModel;
    private final QuickDecodeSelectionModel selectionModel;
    private final RecommendationRankingPolicy rankingPolicy;

    public QuickDecodeDecisionResolver(
            ProjectDefinitionMatchingModel matchingModel,
            QuickDecodeSelectionModel selectionModel
    ) {
        this(matchingModel, selectionModel, new RecommendationRankingPolicy());
    }

    public QuickDecodeDecisionResolver(
            ProjectDefinitionMatchingModel matchingModel,
            QuickDecodeSelectionModel selectionModel,
            RecommendationRankingPolicy rankingPolicy
    ) {
        this.matchingModel = Objects.requireNonNull(matchingModel, "matchingModel");
        this.selectionModel = Objects.requireNonNull(selectionModel, "selectionModel");
        this.rankingPolicy = Objects.requireNonNull(rankingPolicy, "rankingPolicy");
    }

    public Decision resolve(SourceChannelKey key) {
        Objects.requireNonNull(key, "key");
        boolean included = selectionModel.included(key);
        SourceChannelMappingEntry mapping = matchingModel.mapping(key).orElse(null);
        List<SourceChannelDefinitionCandidate> candidates = matchingModel.candidatesFor(key);
        RecommendationRankingPolicy.RankedRecommendation ranked = rankingPolicy.rank(candidates);

        SourceChannelDefinitionCandidate autoCandidate = autoRecommendation(mapping, candidates, ranked);
        Optional<DefinitionSourceId> override = selectionModel.overrideFor(key);
        SourceChannelDefinitionCandidate selected;
        SelectionOrigin origin;
        boolean autoWarning = false;
        if (override.isPresent()) {
            selected = candidate(candidates, override.orElseThrow()).orElse(null);
            origin = SelectionOrigin.MANUAL_OVERRIDE;
        } else if (mapping != null
                && mapping.status() == ChannelMappingStatus.MANUAL
                && mapping.mappedDefinitionId().isPresent()) {
            selected = candidate(candidates, mapping.mappedDefinitionId().orElseThrow())
                    .or(() -> mappedSnapshot(mapping))
                    .orElse(null);
            origin = selected == null ? SelectionOrigin.NONE : SelectionOrigin.MANUAL_OVERRIDE;
        } else {
            selected = autoCandidate;
            origin = selected == null ? SelectionOrigin.NONE : SelectionOrigin.AUTO_RECOMMENDATION;
            autoWarning = selected != null
                    && ranked.kind() == ChannelRecommendationKind.AMBIGUOUS
                    && (mapping == null || mapping.status() != ChannelMappingStatus.CONFIRMED);
            if (mapping != null
                    && mapping.recommendationKind() == ChannelRecommendationKind.CLEAR_BEST_MATCH
                    && candidates.isEmpty()) {
                autoWarning = false;
            }
        }

        Status status;
        if (!included) {
            status = Status.EXCLUDED;
        } else if (mapping != null && mapping.recommendationKind() == ChannelRecommendationKind.NOT_APPLICABLE) {
            status = Status.NOT_APPLICABLE;
        } else if (selected == null) {
            status = mapping != null && mapping.recommendationKind() == ChannelRecommendationKind.AMBIGUOUS
                    ? Status.REVIEW
                    : Status.NO_MATCH;
        } else if (selected.matchedUniqueMessageCount() <= 0L) {
            status = Status.NO_MATCH;
        } else {
            status = Status.READY;
        }
        return new Decision(
                key, included, autoCandidate, selected, origin, status,
                autoWarning && status == Status.READY);
    }

    /**
     * Auto Decode pick: previously applied compatible DBC, else ranking {@code autoSelect}.
     * Custom Decode does not call this resolver to decide Review enablement.
     */
    private SourceChannelDefinitionCandidate autoRecommendation(
            SourceChannelMappingEntry mapping,
            List<SourceChannelDefinitionCandidate> candidates,
            RecommendationRankingPolicy.RankedRecommendation ranked
    ) {
        DefinitionSourceId previouslyApplied = previouslyApplied(mapping);
        if (ranked.kind() == ChannelRecommendationKind.CLEAR_BEST_MATCH
                || ranked.kind() == ChannelRecommendationKind.AMBIGUOUS) {
            SourceChannelDefinitionCandidate picked = rankingPolicy.autoSelect(ranked, previouslyApplied);
            if (picked != null) {
                return picked;
            }
        }
        return clearRecommendation(mapping, candidates)
                .or(() -> recommendationSnapshot(mapping))
                .or(() -> mappedSnapshot(mapping).filter(c -> c.matchedUniqueMessageCount() > 0L))
                .orElse(null);
    }

    private static DefinitionSourceId previouslyApplied(SourceChannelMappingEntry mapping) {
        if (mapping == null) {
            return null;
        }
        if (mapping.mappedDefinitionId().isPresent()) {
            return mapping.mappedDefinitionId().orElseThrow();
        }
        return mapping.recommendedDefinitionId().orElse(null);
    }

    private static Optional<SourceChannelDefinitionCandidate> clearRecommendation(
            SourceChannelMappingEntry mapping,
            List<SourceChannelDefinitionCandidate> candidates
    ) {
        if (mapping == null
                || mapping.recommendationKind() != ChannelRecommendationKind.CLEAR_BEST_MATCH
                || mapping.recommendedDefinitionId().isEmpty()) {
            return Optional.empty();
        }
        return candidate(candidates, mapping.recommendedDefinitionId().orElseThrow());
    }

    /**
     * The matching model normally retains a full candidate row for every uploaded DBC. During
     * asynchronous project refreshes, however, a mapping snapshot can legitimately arrive one
     * notification before the candidate index. Quick Decode must not turn a valid recommendation
     * into a transient NO_MATCH in that window. Use only the evidence already stored on the
     * mapping entry; never invent frame metrics or re-rank by filename.
     */
    private static Optional<SourceChannelDefinitionCandidate> recommendationSnapshot(
            SourceChannelMappingEntry mapping
    ) {
        if (mapping == null
                || mapping.recommendationKind() != ChannelRecommendationKind.CLEAR_BEST_MATCH
                || mapping.recommendedDefinitionId().isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(snapshotFromMapping(
                mapping,
                mapping.recommendedDefinitionId().orElseThrow(),
                mapping.recommendedDisplayName()));
    }

    private static Optional<SourceChannelDefinitionCandidate> mappedSnapshot(
            SourceChannelMappingEntry mapping
    ) {
        if (mapping == null || mapping.mappedDefinitionId().isEmpty()) {
            return Optional.empty();
        }
        if (mapping.status() != ChannelMappingStatus.MANUAL
                && mapping.status() != ChannelMappingStatus.CONFIRMED) {
            return Optional.empty();
        }
        return Optional.of(snapshotFromMapping(
                mapping,
                mapping.mappedDefinitionId().orElseThrow(),
                mapping.mappedDisplayName()));
    }

    private static SourceChannelDefinitionCandidate snapshotFromMapping(
            SourceChannelMappingEntry mapping,
            DefinitionSourceId definitionId,
            String displayName
    ) {
        long observed = mapping.observedUniqueMessageCount();
        long matched = mapping.matchedUniqueMessageCount();
        return new SourceChannelDefinitionCandidate(
                mapping.sourceChannelKey(),
                definitionId,
                displayName,
                0,
                matched > 0L ? DefinitionCandidateKind.HAS_OVERLAP : DefinitionCandidateKind.NO_OVERLAP,
                observed,
                matched,
                Math.max(0L, observed - matched),
                mapping.coverageRatio()
        );
    }

    private static Optional<SourceChannelDefinitionCandidate> candidate(
            List<SourceChannelDefinitionCandidate> candidates,
            DefinitionSourceId id
    ) {
        if (id == null) {
            return Optional.empty();
        }
        return candidates.stream().filter(c -> c.definitionSourceId().equals(id)).findFirst();
    }
}
