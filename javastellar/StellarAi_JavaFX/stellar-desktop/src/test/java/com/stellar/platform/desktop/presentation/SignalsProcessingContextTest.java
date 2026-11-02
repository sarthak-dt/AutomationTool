package com.stellar.platform.desktop.presentation;

import com.stellar.platform.domain.CanonicalSignal;
import com.stellar.platform.domain.SourceReference;
import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalSourceType;
import com.stellar.platform.processing.catalog.InMemorySignalCatalogStore;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SignalsProcessingContextTest {

    @Test
    void completedResultContextIsImmutableAcrossLaterSelectionEdits() {
        SignalsScreenModel model = new SignalsScreenModel();
        InMemorySignalCatalogStore store = new InMemorySignalCatalogStore("run.mf4", "veh.dbc", 20);
        store.accept(List.of(new CanonicalSignal(
                "SigA", "Msg", 1L, 0x10, 3, 1.0, 1L, "", null, new SourceReference(1, 0)
        )));
        SignalsProcessingContext bound = SignalsProcessingContext.of("run.mf4", "veh.dbc", "Channel 3");
        model.replaceCatalog(store, "done", bound);

        assertEquals(bound, model.processingContext());
        assertEquals("Channel 3", model.processingContext().channelsSummary());

        // Simulate Project Files selection change without a new processing run.
        ChannelSelectionModel selection = new ChannelSelectionModel();
        selection.select(1, true);
        selection.select(2, true);
        assertNotEquals(selection.summarize(null, null).channelLabel(), model.processingContext().channelsSummary());
        assertEquals("Channel 3", model.processingContext().channelsSummary());

        model.replaceCatalog(store, "refresh-only", null);
        assertEquals("Channel 3", model.processingContext().channelsSummary());
    }

    @Test
    void catalogEntriesExposeChannelForFutureFiltering() {
        InMemorySignalCatalogStore store = new InMemorySignalCatalogStore("a.mf4", "b.dbc", 10);
        store.accept(List.of(new CanonicalSignal(
                "Sig", "Msg", 1L, 0x20, 1, 2.0, 2L, "u", null, new SourceReference(1, 0)
        )));
        SignalCatalogEntry entry = store.snapshot().entries().getFirst();
        assertEquals(SignalSourceType.DBC_CAN, entry.sourceType());
        assertEquals(1, entry.channel());
        assertEquals("Ch 1", SignalChannelLabels.compact(entry));
        assertEquals("Channel 1", SignalChannelLabels.full(entry));
        assertTrue(entry.key().searchBlob().contains("ch1"));
    }

    @Test
    void nativeSignalChannelLabelIsNeutral() {
        assertEquals("\u2014", SignalChannelLabels.compact(
                new com.stellar.platform.domain.catalog.SignalCatalogKey(
                        "N", SignalSourceType.MF4_NATIVE, "MF4_MEASUREMENT", 0L, 7, "a.mf4", "")));
    }
}