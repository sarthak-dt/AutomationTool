package com.stellar.platform.desktop.presentation;

import com.stellar.platform.desktop.project.DefinitionSourceId;
import com.stellar.platform.desktop.project.ProjectSourceId;
import com.stellar.platform.desktop.project.SourceChannelKey;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuickDecodeSelectionModelTest {

    @Test
    void bulkSourceOverrideNotifiesOnceAndKeepsExcludedIntentIndependent() {
        QuickDecodeSelectionModel model = new QuickDecodeSelectionModel();
        SourceChannelKey ch1 = SourceChannelKey.of(ProjectSourceId.of("A"), 1);
        SourceChannelKey ch2 = SourceChannelKey.of(ProjectSourceId.of("A"), 2);
        DefinitionSourceId dbc = DefinitionSourceId.of("dbc-1");
        AtomicInteger changes = new AtomicInteger();
        model.addListener(changes::incrementAndGet);
        model.setIncluded(ch2, false);
        changes.set(0);

        model.setOverrides(List.of(ch1, ch2), dbc);

        assertEquals(1, changes.get());
        assertEquals(dbc, model.overrideFor(ch1).orElseThrow());
        assertEquals(dbc, model.overrideFor(ch2).orElseThrow());
        assertTrue(model.included(ch1));
        assertFalse(model.included(ch2));
    }

    @Test
    void retainChannelsDoesNotDeleteStateForStillLiveButUiFilteredChannel() {
        QuickDecodeSelectionModel model = new QuickDecodeSelectionModel();
        SourceChannelKey visible = SourceChannelKey.of(ProjectSourceId.of("A"), 1);
        SourceChannelKey hiddenBySearch = SourceChannelKey.of(ProjectSourceId.of("A"), 2);
        model.setOverride(hiddenBySearch, DefinitionSourceId.of("dbc-2"));
        model.setIncluded(hiddenBySearch, false);

        // Production QuickDecodePane passes the full unfiltered inventory here.
        model.retainChannels(List.of(visible, hiddenBySearch));

        assertTrue(model.overrideFor(hiddenBySearch).isPresent());
        assertFalse(model.included(hiddenBySearch));
    }

    @Test
    void resetToRecommendationsClearsOverridesAndReincludesChannelsInOneOperation() {
        QuickDecodeSelectionModel model = new QuickDecodeSelectionModel();
        SourceChannelKey ch1 = SourceChannelKey.of(ProjectSourceId.of("A"), 1);
        SourceChannelKey ch2 = SourceChannelKey.of(ProjectSourceId.of("B"), 3);
        model.setOverrides(List.of(ch1, ch2), DefinitionSourceId.of("dbc"));
        model.deselectAll(List.of(ch1, ch2));

        model.resetToRecommendations(List.of(ch1, ch2));

        assertTrue(model.overrideFor(ch1).isEmpty());
        assertTrue(model.overrideFor(ch2).isEmpty());
        assertTrue(model.included(ch1));
        assertTrue(model.included(ch2));
    }
}
