package com.stellar.platform.desktop.presentation;

import com.stellar.platform.desktop.project.ChannelMappingStatus;
import com.stellar.platform.desktop.project.ChannelRecommendationKind;
import com.stellar.platform.desktop.project.DefinitionCandidateKind;
import com.stellar.platform.desktop.project.DefinitionSourceId;
import com.stellar.platform.desktop.project.ProjectSourceId;
import com.stellar.platform.desktop.project.SourceChannelDefinitionCandidate;
import com.stellar.platform.desktop.project.SourceChannelKey;
import com.stellar.platform.desktop.project.SourceChannelMappingEntry;
import com.stellar.platform.domain.processing.plan.MappingLifecycle;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuickDecodeDecisionResolverTest {

    private final SourceChannelKey key = SourceChannelKey.of(ProjectSourceId.of("source-A"), 2);
    private final ProjectDefinitionMatchingModel matching = new ProjectDefinitionMatchingModel();
    private final QuickDecodeSelectionModel selection = new QuickDecodeSelectionModel();
    private final QuickDecodeDecisionResolver resolver = new QuickDecodeDecisionResolver(matching, selection);

    @Test
    void clearBestMatchIsAutoSelectedAndExecutableWithoutMutatingDetailedMapping() {
        SourceChannelDefinitionCandidate dbc1 = candidate("dbc-1", "DBC1.dbc", 8, 10, 800, 1_000);
        SourceChannelDefinitionCandidate dbc2 = candidate("dbc-2", "DBC2.dbc", 1, 10, 100, 1_000);
        installCandidates(dbc1, dbc2);
        matching.putMapping(mapping(ChannelMappingStatus.RECOMMENDED, ChannelRecommendationKind.CLEAR_BEST_MATCH, dbc1));

        var decision = resolver.resolve(key);

        assertEquals(dbc1, decision.recommendation());
        assertEquals(dbc1, decision.selected());
        assertEquals(QuickDecodeDecisionResolver.SelectionOrigin.AUTO_RECOMMENDATION, decision.origin());
        assertEquals(QuickDecodeDecisionResolver.Status.READY, decision.status());
        assertEquals(MappingLifecycle.RECOMMENDED_FALLBACK, decision.planLifecycle());
        assertTrue(decision.decodable());
        assertEquals(ChannelMappingStatus.RECOMMENDED, matching.mapping(key).orElseThrow().status());
    }

    @Test
    void recommendationSnapshotRemainsReadyIfCandidateIndexRefreshLagsOneNotification() {
        SourceChannelDefinitionCandidate dbc1 = candidate("dbc-1", "DBC1.dbc", 8, 10, 800, 1_000);
        matching.putMapping(mapping(ChannelMappingStatus.RECOMMENDED, ChannelRecommendationKind.CLEAR_BEST_MATCH, dbc1));

        var decision = resolver.resolve(key);

        assertEquals(QuickDecodeDecisionResolver.Status.READY, decision.status());
        assertEquals("dbc-1", decision.selected().definitionSourceId().value());
        assertEquals(8, decision.selected().matchedUniqueMessageCount());
        assertTrue(decision.decodable());
    }

    @Test
    void lowerCoverageManualOverridePreservesRecommendationAndUsesOverrideMetrics() {
        SourceChannelDefinitionCandidate dbc1 = candidate("dbc-1", "DBC1.dbc", 9, 10, 900, 1_000);
        SourceChannelDefinitionCandidate dbc2 = candidate("dbc-2", "DBC2.dbc", 7, 10, 750, 1_000);
        installCandidates(dbc1, dbc2);
        matching.putMapping(mapping(ChannelMappingStatus.RECOMMENDED, ChannelRecommendationKind.CLEAR_BEST_MATCH, dbc1));
        selection.setOverride(key, dbc2.definitionSourceId());

        var decision = resolver.resolve(key);

        assertEquals(dbc1, decision.recommendation());
        assertEquals(dbc2, decision.selected());
        assertEquals(7, decision.selected().matchedUniqueMessageCount());
        assertTrue(decision.manualOverride());
        assertEquals(MappingLifecycle.MANUAL, decision.planLifecycle());
        assertTrue(decision.decodable());
    }

    @Test
    void zeroMatchOverrideIsVisibleButExcludedFromReadyDecodeSet() {
        SourceChannelDefinitionCandidate dbc2 = candidate("dbc-2", "DBC2.dbc", 8, 10, 800, 1_000);
        SourceChannelDefinitionCandidate dbc1 = candidate("dbc-1", "DBC1.dbc", 0, 10, 0, 1_000);
        installCandidates(dbc1, dbc2);
        matching.putMapping(mapping(ChannelMappingStatus.RECOMMENDED, ChannelRecommendationKind.CLEAR_BEST_MATCH, dbc2));
        selection.setOverride(key, dbc1.definitionSourceId());

        var decision = resolver.resolve(key);

        assertEquals(dbc2, decision.recommendation());
        assertEquals(dbc1, decision.selected());
        assertEquals(QuickDecodeDecisionResolver.Status.NO_MATCH, decision.status());
        assertEquals(MappingLifecycle.NO_MATCH, decision.planLifecycle());
        assertFalse(decision.decodable());
        assertTrue(decision.processableWithoutDbc());
        assertTrue(decision.processable());
    }

    @Test
    void ambiguousRecommendationIsAutoSelectedWithWarningUntilEngineerOverrides() {
        SourceChannelDefinitionCandidate a = candidate("dbc-1", "A.dbc", 8, 10, 800, 1_000);
        SourceChannelDefinitionCandidate b = candidate("dbc-2", "B.dbc", 8, 10, 800, 1_000);
        installCandidates(a, b);
        matching.putMapping(new SourceChannelMappingEntry(
                key, ChannelMappingStatus.AMBIGUOUS, ChannelRecommendationKind.AMBIGUOUS,
                null, "", null, "", 10, 8, 0.8));

        var auto = resolver.resolve(key);
        assertEquals(a, auto.recommendation());
        assertEquals(a, auto.selected());
        assertEquals(QuickDecodeDecisionResolver.Status.READY, auto.status());
        assertEquals(QuickDecodeDecisionResolver.SelectionOrigin.AUTO_RECOMMENDATION, auto.origin());
        assertTrue(auto.autoSelectedWithWarning());
        assertTrue(auto.decodable());
        assertEquals(MappingLifecycle.RECOMMENDED_FALLBACK, auto.planLifecycle());

        selection.setOverride(key, b.definitionSourceId());
        var resolved = resolver.resolve(key);
        assertEquals(b, resolved.selected());
        assertEquals(QuickDecodeDecisionResolver.Status.READY, resolved.status());
        assertEquals(MappingLifecycle.MANUAL, resolved.planLifecycle());
        assertFalse(resolved.autoSelectedWithWarning());
        assertTrue(resolved.manualOverride());
    }

    @Test
    void excludedChannelNeverDecodesEvenWithValidRecommendation() {
        SourceChannelDefinitionCandidate dbc1 = candidate("dbc-1", "DBC1.dbc", 10, 10, 1_000, 1_000);
        installCandidates(dbc1);
        matching.putMapping(mapping(ChannelMappingStatus.RECOMMENDED, ChannelRecommendationKind.CLEAR_BEST_MATCH, dbc1));
        selection.setIncluded(key, false);

        var decision = resolver.resolve(key);
        assertEquals(QuickDecodeDecisionResolver.Status.EXCLUDED, decision.status());
        assertFalse(decision.decodable());
        assertFalse(decision.processableWithoutDbc());
        assertFalse(decision.processable());
    }

    @Test
    void includedChannelWithNoDbcIsProcessableNativeOnly() {
        matching.putMapping(new SourceChannelMappingEntry(
                key, ChannelMappingStatus.UNMAPPED, ChannelRecommendationKind.NO_MATCH,
                null, "", null, "", 3, 0, 0.0));

        var decision = resolver.resolve(key);

        assertEquals(QuickDecodeDecisionResolver.Status.NO_MATCH, decision.status());
        assertFalse(decision.decodable());
        assertTrue(decision.processableWithoutDbc());
        assertTrue(decision.processable());
        assertEquals(MappingLifecycle.NO_MATCH, decision.planLifecycle());
    }

    private void installCandidates(SourceChannelDefinitionCandidate... candidates) {
        matching.replaceCandidates(List.of(candidates));
    }

    private SourceChannelMappingEntry mapping(
            ChannelMappingStatus status,
            ChannelRecommendationKind kind,
            SourceChannelDefinitionCandidate recommended
    ) {
        return new SourceChannelMappingEntry(
                key, status, kind,
                recommended.definitionSourceId(), recommended.definitionDisplayName(),
                null, "",
                recommended.observedUniqueMessageCount(),
                recommended.matchedUniqueMessageCount(),
                recommended.coverageRatio());
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
                key, DefinitionSourceId.of(id), display, 100,
                matchedIds > 0 ? DefinitionCandidateKind.HAS_OVERLAP : DefinitionCandidateKind.NO_OVERLAP,
                observedIds, matchedIds, Math.max(0, observedIds - matchedIds),
                observedIds == 0 ? 0 : matchedIds / (double) observedIds,
                observedFrames, matchedFrames,
                observedFrames == 0 ? 0 : matchedFrames / (double) observedFrames);
    }
}
