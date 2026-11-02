package com.stellar.platform.desktop.presentation;

import com.stellar.platform.domain.CanonicalSignal;
import com.stellar.platform.domain.SourceReference;
import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSourceType;
import com.stellar.platform.processing.catalog.InMemorySignalCatalogStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Phase 6: favorites, recent, clear-all, duplicate prevention (UI state only). */
class SignalsProductivityTest {

    private final SignalsScreenModel model = new SignalsScreenModel();

    @AfterEach
    void tearDown() {
        model.shutdown();
    }

    @Test
    void favoritesToggleAndListenerAreSessionOnly() {
        SignalCatalogKey key = key("LONGITUDE", "MSG", 0x7E8L);
        AtomicInteger events = new AtomicInteger();
        model.addFavoritesListener(events::incrementAndGet);

        assertFalse(model.isFavorite(key));
        assertTrue(model.toggleFavorite(key));
        assertTrue(model.isFavorite(key));
        assertEquals(1, events.get());
        assertFalse(model.toggleFavorite(key));
        assertFalse(model.isFavorite(key));
        assertEquals(2, events.get());
    }

    @Test
    void clearWorkspaceLeavesFavoritesIntact() {
        model.replaceCatalog(catalogWith(
                can("SPEED", "MSG", 0x100, 1_000L, 18.5, 1L),
                can("RPM", "MSG", 0x200, 1_000L, 800.0, 2L)
        ));
        SignalCatalogKey key = key("SPEED", "MSG", 0x100);
        model.toggleFavorite(key);
        model.addToWorkspace(key);
        assertEquals(1, model.workspaceKeys().size());
        model.clearWorkspace();
        assertTrue(model.workspaceKeys().isEmpty());
        assertTrue(model.isFavorite(key));
    }

    @Test
    void recentSignalsAreBoundedAndTouchedOnFocusAndAdd() {
        model.replaceCatalog(catalogWith(
                can("A", "M", 1L, 1L, 1.0, 1L),
                can("B", "M", 2L, 1L, 2.0, 2L),
                can("C", "M", 3L, 1L, 3.0, 3L),
                can("D", "M", 4L, 1L, 4.0, 4L),
                can("E", "M", 5L, 1L, 5.0, 5L)
        ));
        List<SignalCatalogKey> keys = model.catalog().entries().stream().map(SignalCatalogEntry::key).toList();
        for (int i = 0; i < 25; i++) {
            model.touchRecent(keys.get(i % keys.size()));
        }
        assertTrue(model.recentSignals().size() <= SignalsScreenModel.MAX_RECENT_SIGNALS);

        SignalCatalogKey a = keys.get(0);
        SignalCatalogKey b = keys.get(1);
        model.setFocusedSignalKey(a);
        model.addToWorkspace(b);
        assertEquals(b, model.recentSignals().getFirst());
        assertTrue(model.recentSignals().contains(a));
    }

    @Test
    void addToWorkspacePreventsDuplicates() {
        model.replaceCatalog(catalogWith(can("SPEED", "MSG", 0x100, 1_000L, 18.5, 1L)));
        SignalCatalogKey key = key("SPEED", "MSG", 0x100);
        assertTrue(model.addToWorkspace(key));
        assertFalse(model.addToWorkspace(key));
        assertEquals(1, model.workspaceKeys().size());
        assertEquals(0, model.addToWorkspace(List.of(key, key)));
        assertEquals(1, model.workspaceKeys().size());
    }

    @Test
    void displayedPhysicalCellMatchesClipboardExpectation() {
        WorkspacePrecision p = WorkspacePrecision.DEC_4;
        assertEquals("67.3398", p.formatPhysical(67.33975));
        assertEquals("17.0000", p.formatPhysical(17.0));
    }

    private static InMemorySignalCatalogStore catalogWith(CanonicalSignal... signals) {
        InMemorySignalCatalogStore store = new InMemorySignalCatalogStore("demo.mf4", "demo.dbc", 1_000);
        store.accept(List.of(signals));
        return store;
    }

    private static SignalCatalogKey key(String name, String message, long canId) {
        return new SignalCatalogKey(name, SignalSourceType.DBC_CAN, message, canId, "demo.mf4", "demo.dbc");
    }

    private static CanonicalSignal can(String name, String message, long canId, long ts, double phys, long raw) {
        return new CanonicalSignal(name, message, ts, canId, 0, phys, raw, "u", null, SourceReference.ofLine(1));
    }
}
