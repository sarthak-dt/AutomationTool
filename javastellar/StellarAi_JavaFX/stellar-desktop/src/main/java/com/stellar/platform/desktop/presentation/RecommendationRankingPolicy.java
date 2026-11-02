package com.stellar.platform.desktop.presentation;

import com.stellar.platform.desktop.project.ChannelRecommendationKind;
import com.stellar.platform.desktop.project.DefinitionSourceId;
import com.stellar.platform.desktop.project.SourceChannelDefinitionCandidate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Deterministic DBC recommendation ranking for a SourceChannel.
 *
 * <p>Engineering evidence (strongest first; never filesystem order or random):
 * <ol>
 *   <li>higher {@code matchedUniqueMessageCount} (CAN-ID compatibility)</li>
 *   <li>higher {@code matchedFrameCount}</li>
 *   <li>higher unique-ID coverage, then frame coverage</li>
 * </ol>
 *
 * <p>The candidate model has no DLC / message-length field. Length is therefore not a ranking
 * input here — inventing it would change decode semantics. Frame counts already reflect how much
 * observed traffic a DBC can name by CAN ID.</p>
 *
 * <p>{@code AMBIGUOUS} when top candidates are tied on every engineering rank. Custom Decode
 * still requires an engineer choice in that case. Auto Decode may call {@link #autoSelect}
 * which applies this documented product rule:
 * <ol>
 *   <li>reuse a previously applied/confirmed project DBC if it remains in the candidate set
 *       and still has CAN-ID overlap</li>
 *   <li>otherwise choose the stable listing-order head already computed by {@link #rank}:
 *       {@code definitionDisplayName}, then {@code DefinitionSourceId.value}</li>
 * </ol>
 * Listing order is not upload order or filesystem order. It is a stable, explainable last key
 * after all engineering evidence is equal.</p>
 *
 * <p>{@code NO_MATCH} when observed IDs exist but every candidate has zero matched IDs.
 * {@code NOT_APPLICABLE} when no observed IDs exist.</p>
 */
public final class RecommendationRankingPolicy {

    public record RankedRecommendation(
            ChannelRecommendationKind kind,
            SourceChannelDefinitionCandidate bestOrNull,
            List<SourceChannelDefinitionCandidate> ranked
    ) {
    }

    public RankedRecommendation rank(List<SourceChannelDefinitionCandidate> candidates) {
        Objects.requireNonNull(candidates, "candidates");
        List<SourceChannelDefinitionCandidate> ranked = new ArrayList<>(candidates);
        ranked.sort(Comparator
                .comparingLong(SourceChannelDefinitionCandidate::matchedUniqueMessageCount).reversed()
                .thenComparing(Comparator.comparingLong(SourceChannelDefinitionCandidate::matchedFrameCount).reversed())
                .thenComparing(Comparator.comparingDouble(SourceChannelDefinitionCandidate::coverageRatio).reversed())
                .thenComparing(Comparator.comparingDouble(SourceChannelDefinitionCandidate::frameCoverageRatio).reversed())
                .thenComparing(SourceChannelDefinitionCandidate::definitionDisplayName)
                .thenComparing(c -> c.definitionSourceId().value()));

        if (ranked.isEmpty()) {
            return new RankedRecommendation(ChannelRecommendationKind.NOT_APPLICABLE, null, List.of());
        }
        long observed = ranked.getFirst().observedUniqueMessageCount();
        if (observed <= 0L) {
            return new RankedRecommendation(ChannelRecommendationKind.NOT_APPLICABLE, null, List.copyOf(ranked));
        }
        boolean anyMatch = ranked.stream().anyMatch(c -> c.matchedUniqueMessageCount() > 0L);
        if (!anyMatch) {
            return new RankedRecommendation(ChannelRecommendationKind.NO_MATCH, null, List.copyOf(ranked));
        }
        SourceChannelDefinitionCandidate top = ranked.getFirst();
        long tieCount = ranked.stream()
                .filter(c -> sameEngineeringRank(c, top))
                .count();
        if (tieCount > 1L) {
            return new RankedRecommendation(ChannelRecommendationKind.AMBIGUOUS, top, List.copyOf(ranked));
        }
        return new RankedRecommendation(ChannelRecommendationKind.CLEAR_BEST_MATCH, top, List.copyOf(ranked));
    }

    /**
     * Auto Decode selection after ranking. Custom Decode must not use this to skip Review.
     *
     * <p>Never returns a zero-overlap candidate. When {@code previouslyAppliedOrNull} is still
     * compatible it wins even if another DBC now ranks equal or higher — that is how Reprocess
     * reuses a confirmed mapping without asking again.</p>
     */
    public SourceChannelDefinitionCandidate autoSelect(
            RankedRecommendation ranked,
            DefinitionSourceId previouslyAppliedOrNull
    ) {
        if (ranked == null || ranked.bestOrNull() == null) {
            return null;
        }
        if (ranked.kind() == ChannelRecommendationKind.NO_MATCH
                || ranked.kind() == ChannelRecommendationKind.NOT_APPLICABLE) {
            return null;
        }
        if (previouslyAppliedOrNull != null) {
            for (SourceChannelDefinitionCandidate candidate : ranked.ranked()) {
                if (candidate.definitionSourceId().equals(previouslyAppliedOrNull)
                        && candidate.matchedUniqueMessageCount() > 0L) {
                    return candidate;
                }
            }
        }
        SourceChannelDefinitionCandidate best = ranked.bestOrNull();
        return best.matchedUniqueMessageCount() > 0L ? best : null;
    }

    static boolean sameEngineeringRank(
            SourceChannelDefinitionCandidate a,
            SourceChannelDefinitionCandidate b
    ) {
        return a.matchedUniqueMessageCount() == b.matchedUniqueMessageCount()
                && a.matchedFrameCount() == b.matchedFrameCount()
                && Double.compare(a.coverageRatio(), b.coverageRatio()) == 0
                && Double.compare(a.frameCoverageRatio(), b.frameCoverageRatio()) == 0;
    }
}
