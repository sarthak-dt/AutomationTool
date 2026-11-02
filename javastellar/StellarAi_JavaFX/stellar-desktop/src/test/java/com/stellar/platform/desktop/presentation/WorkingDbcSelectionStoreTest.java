package com.stellar.platform.desktop.presentation;

import com.stellar.platform.desktop.project.ChannelMappingStatus;
import com.stellar.platform.desktop.project.ChannelRecommendationKind;
import com.stellar.platform.desktop.project.DefinitionSourceId;
import com.stellar.platform.desktop.project.ProjectSourceId;
import com.stellar.platform.desktop.project.SourceChannelKey;
import com.stellar.platform.desktop.project.SourceChannelMappingEntry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkingDbcSelectionStoreTest {

    @Test
    void displayedDefinitionPrefersWorkingThenConfirmedThenRecommended() {
        WorkingDbcSelectionStore store = new WorkingDbcSelectionStore();
        SourceChannelKey key = SourceChannelKey.of(ProjectSourceId.generate(), 1);
        DefinitionSourceId recommended = DefinitionSourceId.generate();
        DefinitionSourceId confirmed = DefinitionSourceId.generate();
        DefinitionSourceId working = DefinitionSourceId.generate();
        SourceChannelMappingEntry mapping = new SourceChannelMappingEntry(
                key, ChannelMappingStatus.CONFIRMED, ChannelRecommendationKind.CLEAR_BEST_MATCH,
                recommended, "rec.dbc", confirmed, "ok.dbc", 10, 8, 0.8);

        assertEquals(confirmed, store.displayedDefinition(key, mapping).orElseThrow());
        assertEquals(WorkingDbcSelectionStore.DisplayState.CONFIRMED, store.displayState(key, mapping));

        store.put(key, working);
        assertEquals(working, store.displayedDefinition(key, mapping).orElseThrow());
        assertEquals(WorkingDbcSelectionStore.DisplayState.SELECTED, store.displayState(key, mapping));
        assertEquals(confirmed, mapping.mappedDefinitionId().orElseThrow());
        assertEquals(recommended, mapping.recommendedDefinitionId().orElseThrow());
    }

    @Test
    void twoSourcesWithSameChannelNumberKeepIndependentWorkingChoices() {
        WorkingDbcSelectionStore store = new WorkingDbcSelectionStore();
        SourceChannelKey keyA = SourceChannelKey.of(ProjectSourceId.generate(), 1);
        SourceChannelKey keyB = SourceChannelKey.of(ProjectSourceId.generate(), 1);
        DefinitionSourceId dbcA = DefinitionSourceId.generate();
        DefinitionSourceId dbcB = DefinitionSourceId.generate();
        store.put(keyA, dbcA);
        store.put(keyB, dbcB);
        assertEquals(dbcA, store.get(keyA).orElseThrow());
        assertEquals(dbcB, store.get(keyB).orElseThrow());
    }

    @Test
    void clearAllRemovesTransientWorkingSelections() {
        WorkingDbcSelectionStore store = new WorkingDbcSelectionStore();
        SourceChannelKey key = SourceChannelKey.of(ProjectSourceId.generate(), 2);
        store.put(key, DefinitionSourceId.generate());
        assertTrue(store.get(key).isPresent());
        store.clearAll();
        assertTrue(store.get(key).isEmpty());
        assertEquals(WorkingDbcSelectionStore.DisplayState.RECOMMENDED, store.displayState(key, recommended(key)));
    }

    @Test
    void recommendedDisplayStateWhenWorkingMatchesRecommendation() {
        WorkingDbcSelectionStore store = new WorkingDbcSelectionStore();
        SourceChannelKey key = SourceChannelKey.of(ProjectSourceId.generate(), 3);
        SourceChannelMappingEntry mapping = recommended(key);
        DefinitionSourceId rec = mapping.recommendedDefinitionId().orElseThrow();
        store.put(key, rec);
        assertEquals(WorkingDbcSelectionStore.DisplayState.RECOMMENDED, store.displayState(key, mapping));
        assertEquals(rec, store.displayedDefinition(key, mapping).orElseThrow());
    }

    private static SourceChannelMappingEntry recommended(SourceChannelKey key) {
        DefinitionSourceId rec = DefinitionSourceId.generate();
        return new SourceChannelMappingEntry(
                key, ChannelMappingStatus.RECOMMENDED, ChannelRecommendationKind.CLEAR_BEST_MATCH,
                rec, "rec.dbc", null, "", 10, 4, 0.4);
    }
}
