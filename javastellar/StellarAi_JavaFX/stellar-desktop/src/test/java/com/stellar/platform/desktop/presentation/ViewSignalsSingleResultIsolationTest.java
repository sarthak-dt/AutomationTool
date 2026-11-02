package com.stellar.platform.desktop.presentation;

import com.stellar.platform.domain.CanonicalSignal;
import com.stellar.platform.domain.SourceReference;
import com.stellar.platform.processing.catalog.InMemorySignalCatalogStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * View Signals loads exactly one IndependentJobResult catalog — never merges catalogs.
 */
class ViewSignalsSingleResultIsolationTest {

    private SignalsScreenModel model;

    @AfterEach
    void tearDown() {
        if (model != null) {
            model.shutdown();
        }
    }

    @Test
    void replacingCatalogDoesNotMergePriorResultSignals() {
        model = new SignalsScreenModel();
        InMemorySignalCatalogStore storeA = new InMemorySignalCatalogStore("srcA", "a.dbc");
        storeA.accept(List.of(signal("A_Only", 1, 10L, 1.0)));
        model.replaceCatalog(storeA, "A hint", SignalsProcessingContext.of("A", "a.dbc", "Ch1"));
        assertEquals(1, model.catalog().entries().size());
        assertTrue(model.catalog().entries().stream().anyMatch(e -> e.signalName().equals("A_Only")));

        InMemorySignalCatalogStore storeB = new InMemorySignalCatalogStore("srcB", "b.dbc");
        storeB.accept(List.of(signal("B_Only", 2, 20L, 2.0)));
        model.replaceCatalog(storeB, "B hint", SignalsProcessingContext.of("B", "b.dbc", "Ch2"));

        assertEquals(1, model.catalog().entries().size());
        assertTrue(model.catalog().entries().stream().allMatch(e -> e.signalName().equals("B_Only")));
        assertTrue(model.catalog().entries().stream().noneMatch(e -> e.signalName().equals("A_Only")));
        assertEquals("B", model.processingContext().sourceFile());
    }

    private static CanonicalSignal signal(String name, int channel, long ts, double value) {
        return new CanonicalSignal(
                name, "Msg", ts, 0x10, channel, value, null, "u", null,
                SourceReference.ofLine(1)
        );
    }
}
