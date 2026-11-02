package com.stellar.platform.desktop.presentation;

import com.stellar.platform.desktop.project.ChannelMappingStatus;
import com.stellar.platform.desktop.project.ChannelRecommendationKind;
import com.stellar.platform.desktop.project.DefinitionCandidateKind;
import com.stellar.platform.desktop.project.DefinitionSourceId;
import com.stellar.platform.desktop.project.ProjectSourceId;
import com.stellar.platform.desktop.project.SourceChannelDefinitionCandidate;
import com.stellar.platform.desktop.project.SourceChannelDefinitionComparison;
import com.stellar.platform.desktop.project.SourceChannelKey;
import com.stellar.platform.desktop.project.SourceChannelMappingEntry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChannelMappingPreviewTest {

    @Test
    void sameChannelNumberOnTwoSourcesYieldsIndependentMatchedCounts() {
        ProjectSourceId sourceA = ProjectSourceId.generate();
        ProjectSourceId sourceB = ProjectSourceId.generate();
        SourceChannelKey keyA = SourceChannelKey.of(sourceA, 3);
        SourceChannelKey keyB = SourceChannelKey.of(sourceB, 3);
        DefinitionSourceId defId = DefinitionSourceId.generate();

        Optional<ChannelMappingPreview.Snapshot> a = ChannelMappingPreview.resolve(
                keyA, defId, comparison(keyA, defId, 31, 31), null, null);
        Optional<ChannelMappingPreview.Snapshot> b = ChannelMappingPreview.resolve(
                keyB, defId, comparison(keyB, defId, 31, 6), null, null);

        assertEquals(31L, a.orElseThrow().matchedCount());
        assertEquals(6L, b.orElseThrow().matchedCount());
        assertNotEquals(a.get().matchedCount(), b.get().matchedCount());
    }

    @Test
    void lookupRequiresSourceChannelAndDefinitionIdentity() {
        SourceChannelKey keyA = SourceChannelKey.of(ProjectSourceId.generate(), 1);
        SourceChannelKey keyB = SourceChannelKey.of(ProjectSourceId.generate(), 1);
        DefinitionSourceId defA = DefinitionSourceId.generate();
        DefinitionSourceId defB = DefinitionSourceId.generate();
        SourceChannelDefinitionComparison comparisonA = comparison(keyA, defA, 56, 9);

        assertTrue(ChannelMappingPreview.resolve(keyB, defA, comparisonA, null, null).isEmpty(),
                "must not reuse comparison by channel number alone");
        assertTrue(ChannelMappingPreview.resolve(keyA, defB, comparisonA, null, null).isEmpty(),
                "must not reuse comparison by DBC identity mismatch");
        assertEquals(9L, ChannelMappingPreview.resolve(keyA, defA, comparisonA, null, null)
                .orElseThrow().matchedCount());
    }

    @Test
    void comparisonTakesPrecedenceOverMappingCounts() {
        SourceChannelKey key = SourceChannelKey.of(ProjectSourceId.generate(), 2);
        DefinitionSourceId defId = DefinitionSourceId.generate();
        SourceChannelDefinitionComparison comparison = comparison(key, defId, 56, 22);
        SourceChannelMappingEntry mapping = new SourceChannelMappingEntry(
                key, ChannelMappingStatus.RECOMMENDED, ChannelRecommendationKind.CLEAR_BEST_MATCH,
                defId, "3.dbc", null, "", 56, 9, 9.0 / 56.0);

        ChannelMappingPreview.Snapshot snapshot = ChannelMappingPreview.resolve(
                key, defId, comparison, null, mapping).orElseThrow();
        assertEquals(22L, snapshot.matchedCount());
        assertEquals(56L, snapshot.observedCount());
    }

    @Test
    void candidateFallbackWhenComparisonMissing() {
        SourceChannelKey key = SourceChannelKey.of(ProjectSourceId.generate(), 2);
        DefinitionSourceId defId = DefinitionSourceId.generate();
        SourceChannelDefinitionCandidate candidate = new SourceChannelDefinitionCandidate(
                key, defId, "1.dbc", 10, DefinitionCandidateKind.HAS_OVERLAP, 56, 22, 34, 22.0 / 56.0);

        ChannelMappingPreview.Snapshot snapshot = ChannelMappingPreview.resolve(
                key, defId, null, candidate, null).orElseThrow();
        assertEquals(22L, snapshot.matchedCount());
        assertEquals(56L, snapshot.observedCount());
    }

    @Test
    void genuineZeroMatchesIsPresentZero() {
        SourceChannelKey key = SourceChannelKey.of(ProjectSourceId.generate(), 1);
        DefinitionSourceId defId = DefinitionSourceId.generate();
        ChannelMappingPreview.Snapshot snapshot = ChannelMappingPreview.resolve(
                key, defId, comparison(key, defId, 10, 0), null, null).orElseThrow();
        assertEquals(0L, snapshot.matchedCount());
    }

    @Test
    void missingIdentityOrDataIsEmptyNotZero() {
        SourceChannelKey key = SourceChannelKey.of(ProjectSourceId.generate(), 1);
        DefinitionSourceId defId = DefinitionSourceId.generate();
        assertTrue(ChannelMappingPreview.resolve(null, defId, comparison(key, defId, 10, 4), null, null).isEmpty());
        assertTrue(ChannelMappingPreview.resolve(key, null, comparison(key, defId, 10, 4), null, null).isEmpty());
        assertTrue(ChannelMappingPreview.resolve(key, defId, null, null, null).isEmpty());
    }

    private static SourceChannelDefinitionComparison comparison(
            SourceChannelKey key,
            DefinitionSourceId defId,
            long observed,
            long matched
    ) {
        double coverage = observed == 0 ? 0.0 : (double) matched / observed;
        return new SourceChannelDefinitionComparison(
                key, defId, "preview.dbc", 4, DefinitionCandidateKind.HAS_OVERLAP,
                observed, matched, coverage, List.of());
    }
}
