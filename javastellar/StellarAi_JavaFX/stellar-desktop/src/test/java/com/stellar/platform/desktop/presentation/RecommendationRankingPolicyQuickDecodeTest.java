package com.stellar.platform.desktop.presentation;

import com.stellar.platform.desktop.project.ChannelRecommendationKind;
import com.stellar.platform.desktop.project.DefinitionCandidateKind;
import com.stellar.platform.desktop.project.DefinitionSourceId;
import com.stellar.platform.desktop.project.ProjectSourceId;
import com.stellar.platform.desktop.project.SourceChannelDefinitionCandidate;
import com.stellar.platform.desktop.project.SourceChannelKey;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class RecommendationRankingPolicyQuickDecodeTest {

    private final RecommendationRankingPolicy policy = new RecommendationRankingPolicy();
    private final SourceChannelKey channel = SourceChannelKey.of(ProjectSourceId.of("source-A"), 2);

    @Test
    void uniqueIdCoverageWinsBeforeHighFrequencyFrameCoverage() {
        SourceChannelDefinitionCandidate widerIds = candidate("dbc-1", "Wide.dbc", 8, 10, 8_000, 10_000);
        SourceChannelDefinitionCandidate fewerIdsButMoreFrames = candidate(
                "dbc-2", "Busy.dbc", 7, 10, 9_900, 10_000);

        var ranked = policy.rank(List.of(fewerIdsButMoreFrames, widerIds));

        assertEquals(ChannelRecommendationKind.CLEAR_BEST_MATCH, ranked.kind());
        assertEquals("dbc-1", ranked.bestOrNull().definitionSourceId().value());
    }

    @Test
    void frameCoverageBreaksTieWhenMatchedIdEvidenceIsEqual() {
        SourceChannelDefinitionCandidate lowFrames = candidate("dbc-1", "A.dbc", 8, 10, 5_000, 10_000);
        SourceChannelDefinitionCandidate highFrames = candidate("dbc-2", "B.dbc", 8, 10, 8_000, 10_000);

        var ranked = policy.rank(List.of(lowFrames, highFrames));

        assertEquals(ChannelRecommendationKind.CLEAR_BEST_MATCH, ranked.kind());
        assertEquals("dbc-2", ranked.bestOrNull().definitionSourceId().value());
    }

    @Test
    void higherMatchedFrameCountOutranksEqualIdCoverage() {
        SourceChannelDefinitionCandidate incompatibleLengthProxy = candidate("dbc-100", "Long.dbc", 4, 4, 10, 1_000);
        SourceChannelDefinitionCandidate compatibleLengthProxy = candidate("dbc-8", "Exact.dbc", 4, 4, 900, 1_000);

        var ranked = policy.rank(List.of(incompatibleLengthProxy, compatibleLengthProxy));

        assertEquals(ChannelRecommendationKind.CLEAR_BEST_MATCH, ranked.kind());
        assertEquals("dbc-8", ranked.bestOrNull().definitionSourceId().value());
    }

    @Test
    void definitionMessageCountDoesNotInventADlcWinner() {
        SourceChannelDefinitionCandidate eightByteVocab = new SourceChannelDefinitionCandidate(
                channel, DefinitionSourceId.of("dbc-8"), "Eight.dbc", 8, DefinitionCandidateKind.HAS_OVERLAP,
                4, 4, 0, 1.0, 1_000, 400, 0.4);
        SourceChannelDefinitionCandidate hundredByteVocab = new SourceChannelDefinitionCandidate(
                channel, DefinitionSourceId.of("dbc-100"), "Hundred.dbc", 100, DefinitionCandidateKind.HAS_OVERLAP,
                4, 4, 0, 1.0, 1_000, 400, 0.4);

        var ranked = policy.rank(List.of(hundredByteVocab, eightByteVocab));

        assertEquals(ChannelRecommendationKind.AMBIGUOUS, ranked.kind());
        assertEquals("Eight.dbc", policy.autoSelect(ranked, null).definitionDisplayName());
    }

    @Test
    void exactEngineeringTieStaysAmbiguousForCustomButAutoSelectsListingOrderHead() {
        SourceChannelDefinitionCandidate later = candidate("dbc-z", "Z.dbc", 8, 10, 8_000, 10_000);
        SourceChannelDefinitionCandidate earlier = candidate("dbc-a", "A.dbc", 8, 10, 8_000, 10_000);

        var ranked = policy.rank(List.of(later, earlier));

        assertEquals(ChannelRecommendationKind.AMBIGUOUS, ranked.kind());
        assertEquals("dbc-a", ranked.bestOrNull().definitionSourceId().value());
        assertEquals("dbc-a", policy.autoSelect(ranked, null).definitionSourceId().value());
        assertEquals("dbc-z", policy.autoSelect(ranked, DefinitionSourceId.of("dbc-z"))
                .definitionSourceId().value());
    }

    @Test
    void zeroOverlapIsNoMatchAndNoObservedIdsIsNotApplicable() {
        SourceChannelDefinitionCandidate zero = candidate("dbc-1", "A.dbc", 0, 10, 0, 100);
        assertEquals(ChannelRecommendationKind.NO_MATCH, policy.rank(List.of(zero)).kind());
        assertNull(policy.rank(List.of(zero)).bestOrNull());

        SourceChannelDefinitionCandidate noTraffic = new SourceChannelDefinitionCandidate(
                channel, DefinitionSourceId.of("dbc-1"), "A.dbc", 10, DefinitionCandidateKind.NO_OVERLAP,
                0, 0, 0, 0.0, 0, 0, 0.0);
        assertEquals(ChannelRecommendationKind.NOT_APPLICABLE, policy.rank(List.of(noTraffic)).kind());
    }

    private SourceChannelDefinitionCandidate candidate(
            String id,
            String display,
            long matchedIds,
            long observedIds,
            long matchedFrames,
            long observedFrames
    ) {
        return new SourceChannelDefinitionCandidate(
                channel,
                DefinitionSourceId.of(id),
                display,
                100,
                matchedIds > 0 ? DefinitionCandidateKind.HAS_OVERLAP : DefinitionCandidateKind.NO_OVERLAP,
                observedIds,
                matchedIds,
                Math.max(0, observedIds - matchedIds),
                observedIds == 0 ? 0.0 : matchedIds / (double) observedIds,
                observedFrames,
                matchedFrames,
                observedFrames == 0 ? 0.0 : matchedFrames / (double) observedFrames
        );
    }
}
