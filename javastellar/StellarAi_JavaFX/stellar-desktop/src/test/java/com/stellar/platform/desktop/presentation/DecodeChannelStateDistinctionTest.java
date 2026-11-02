package com.stellar.platform.desktop.presentation;

import com.stellar.platform.desktop.project.ChannelMappingStatus;
import com.stellar.platform.desktop.project.ChannelRecommendationKind;
import com.stellar.platform.desktop.project.DefinitionSourceId;
import com.stellar.platform.desktop.project.ProjectSourceId;
import com.stellar.platform.desktop.project.SourceChannelKey;
import com.stellar.platform.desktop.project.SourceChannelMappingEntry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * "Ignored" is a deliberate engineering decision; "unconfigured" is outstanding work. Collapsing
 * the two would either hide real setup gaps or silently process channels the engineer excluded.
 */
class DecodeChannelStateDistinctionTest {

    private final ProjectSourceId source = ProjectSourceId.generate();
    private final ProjectDefinitionMatchingModel matching = new ProjectDefinitionMatchingModel();
    private final QuickDecodeSelectionModel selection = new QuickDecodeSelectionModel();
    private final QuickDecodeDecisionResolver resolver =
            new QuickDecodeDecisionResolver(matching, selection);

    @Test
    void unconfiguredAndIgnoredRemainDistinct() {
        SourceChannelKey unconfigured = SourceChannelKey.of(source, 1);
        SourceChannelKey ignored = SourceChannelKey.of(source, 2);
        selection.setIncluded(ignored, false);

        QuickDecodeDecisionResolver.Status unconfiguredStatus = resolver.resolve(unconfigured).status();
        QuickDecodeDecisionResolver.Status ignoredStatus = resolver.resolve(ignored).status();

        assertEquals(QuickDecodeDecisionResolver.Status.NO_MATCH, unconfiguredStatus);
        assertEquals(QuickDecodeDecisionResolver.Status.EXCLUDED, ignoredStatus);
        assertNotEquals(unconfiguredStatus, ignoredStatus);
    }

    @Test
    void ignoringAConfiguredChannelDoesNotLookLikeMissingSetup() {
        SourceChannelKey key = SourceChannelKey.of(source, 1);
        DefinitionSourceId dbc = DefinitionSourceId.generate();
        matching.putMapping(new SourceChannelMappingEntry(
                key, ChannelMappingStatus.RECOMMENDED, ChannelRecommendationKind.CLEAR_BEST_MATCH,
                dbc, "chassis.dbc", null, "", 20, 20, 1.0));
        assertEquals(QuickDecodeDecisionResolver.Status.READY, resolver.resolve(key).status());

        selection.setIncluded(key, false);

        QuickDecodeDecisionResolver.Decision ignored = resolver.resolve(key);
        assertEquals(QuickDecodeDecisionResolver.Status.EXCLUDED, ignored.status());
        assertFalse(ignored.included());
        assertFalse(ignored.decodable(), "an ignored channel must never be processed silently");
    }

    @Test
    void includingAnIgnoredChannelRestoresItsPreviousConfiguration() {
        SourceChannelKey key = SourceChannelKey.of(source, 1);
        DefinitionSourceId dbc = DefinitionSourceId.generate();
        matching.putMapping(new SourceChannelMappingEntry(
                key, ChannelMappingStatus.RECOMMENDED, ChannelRecommendationKind.CLEAR_BEST_MATCH,
                dbc, "chassis.dbc", null, "", 20, 20, 1.0));
        selection.setIncluded(key, false);

        selection.setIncluded(key, true);

        QuickDecodeDecisionResolver.Decision restored = resolver.resolve(key);
        assertEquals(QuickDecodeDecisionResolver.Status.READY, restored.status());
        assertTrue(restored.decodable(),
                "ignoring then including must not destroy the channel's DBC assignment");
        assertEquals(dbc, restored.selected().definitionSourceId());
    }

    @Test
    void ambiguousChannelIsReviewRatherThanUnconfigured() {
        SourceChannelKey key = SourceChannelKey.of(source, 1);
        matching.putMapping(new SourceChannelMappingEntry(
                key, ChannelMappingStatus.AMBIGUOUS, ChannelRecommendationKind.AMBIGUOUS,
                null, "", null, "", 20, 0, 0.0));

        assertEquals(QuickDecodeDecisionResolver.Status.REVIEW, resolver.resolve(key).status());
    }
}
