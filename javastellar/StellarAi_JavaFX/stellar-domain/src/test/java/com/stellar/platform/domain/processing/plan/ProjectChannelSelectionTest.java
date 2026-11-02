package com.stellar.platform.domain.processing.plan;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectChannelSelectionTest {

    @Test
    void duplicateSelectProducesSingleEntry() {
        ProjectChannelSelection selection = new ProjectChannelSelection();
        SourceChannelRef a1 = SourceChannelRef.of("src-a", 1);
        assertTrue(selection.select(a1));
        assertFalse(selection.select(a1));
        assertEquals(1, selection.size());
    }

    @Test
    void sameChannelNumberAcrossSourcesAreDistinct() {
        ProjectChannelSelection selection = new ProjectChannelSelection();
        selection.select(SourceChannelRef.of("src-a", 1));
        selection.select(SourceChannelRef.of("src-b", 1));
        assertEquals(2, selection.size());
        assertTrue(selection.contains("src-a", 1));
        assertTrue(selection.contains("src-b", 1));
    }

    @Test
    void clearEmptiesWithoutSideEffectsBeyondSelection() {
        ProjectChannelSelection selection = new ProjectChannelSelection();
        selection.selectAll(List.of(
                SourceChannelRef.of("src-a", 1),
                SourceChannelRef.of("src-b", 2)));
        selection.clear();
        assertTrue(selection.isEmpty());
    }

    @Test
    void retainSourcesDropsRemovedSourceChannels() {
        ProjectChannelSelection selection = new ProjectChannelSelection();
        selection.select(SourceChannelRef.of("src-a", 1));
        selection.select(SourceChannelRef.of("src-b", 3));
        assertEquals(1, selection.retainSources(List.of("src-a")));
        assertEquals(1, selection.size());
        assertTrue(selection.contains("src-a", 1));
        assertFalse(selection.contains("src-b", 3));
    }
}
