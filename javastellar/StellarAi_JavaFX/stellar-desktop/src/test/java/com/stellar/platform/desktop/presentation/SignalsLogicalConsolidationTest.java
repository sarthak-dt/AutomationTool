package com.stellar.platform.desktop.presentation;

import com.stellar.platform.domain.CanonicalSignal;
import com.stellar.platform.domain.SourceReference;
import com.stellar.platform.processing.catalog.CompositeSignalCatalogStore;
import com.stellar.platform.processing.catalog.InMemorySignalCatalogStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Regression for the safe Signal Browser contract: physical channel instances are visible by
 * default; duplicate hiding is an explicit presentation-only view and never removes catalog data.
 */
class SignalsLogicalConsolidationTest {

    private SignalsScreenModel model;

    @AfterEach
    void tearDown() {
        if (model != null) {
            model.shutdown();
        }
    }

    @Test
    void browserShowsPhysicalInstancesByDefaultAndOptionalUniqueViewDoesNotDeleteData() {
        InMemorySignalCatalogStore ch1 = store(1, 10L, 41.0);
        InMemorySignalCatalogStore ch2 = store(2, 20L, 42.0);
        CompositeSignalCatalogStore composite = CompositeSignalCatalogStore.fromMembers(List.of(
                new CompositeSignalCatalogStore.Member(ch1, "src-A", "def-A", "run.mf4", "veh.dbc"),
                new CompositeSignalCatalogStore.Member(ch2, "src-A", "def-A", "run.mf4", "veh.dbc")
        ), "run.mf4", "veh.dbc");

        model = new SignalsScreenModel();
        model.replaceCatalog(composite, "Quick", SignalsProcessingContext.quickDecode("run.mf4", "veh.dbc", "2 Channels"));

        assertEquals(2, model.catalog().size(), "physical catalog remains channel-specific");
        assertEquals(SignalBrowserViewMode.ALL_INSTANCES, model.browserViewMode());
        assertEquals(2, model.applyBrowserFilterNow().size(), "safe default shows both physical instances");
        assertEquals(List.of(1, 2), model.displayedCatalog().stream().map(e -> e.channel()).toList());

        model.setBrowserViewMode(SignalBrowserViewMode.UNIQUE_SIGNAL_NAMES);
        var unique = model.applyBrowserFilterNow();
        assertEquals(1, unique.size(), "optional view hides the later duplicate name only");
        assertEquals(1, unique.getFirst().channel(), "deterministic first channel occurrence is kept");
        assertEquals(2, model.catalog().size(), "view-only duplicate hiding never deletes data");

        model.setBrowserViewMode(SignalBrowserViewMode.ALL_INSTANCES);
        assertEquals(2, model.applyBrowserFilterNow().size(), "all instances are immediately recoverable");
    }

    private static InMemorySignalCatalogStore store(int channel, long ts, double value) {
        InMemorySignalCatalogStore store = new InMemorySignalCatalogStore("run.mf4", "veh.dbc", 50);
        store.accept(List.of(new CanonicalSignal(
                "VehicleSpeed", "VehicleState", ts, 0x123, channel, value, null, "km/h", null,
                SourceReference.ofLine(1))));
        return store;
    }
}
