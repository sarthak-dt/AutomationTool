package com.stellar.platform.desktop.presentation;

import com.stellar.platform.domain.CanonicalSignal;
import com.stellar.platform.domain.SourceReference;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSourceType;
import com.stellar.platform.processing.catalog.InMemorySignalCatalogStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SignalsInspectorFocusTest {

    private final SignalsScreenModel model = new SignalsScreenModel();

    @AfterEach
    void tearDown() {
        model.shutdown();
    }

    @Test
    void browserFocusDoesNotAddToWorkspace() {
        InMemorySignalCatalogStore store = new InMemorySignalCatalogStore("demo.mf4", "demo.dbc", 1_000);
        store.accept(List.of(
                can("SIG_A", "MSG", 0x100, 1L, 1.0, 1L),
                can("SIG_B", "MSG", 0x200, 2L, 2.0, 2L)
        ));
        model.replaceCatalog(store);
        SignalCatalogKey a = new SignalCatalogKey("SIG_A", SignalSourceType.DBC_CAN, "MSG", 0x100, "demo.mf4", "demo.dbc");
        SignalCatalogKey b = new SignalCatalogKey("SIG_B", SignalSourceType.DBC_CAN, "MSG", 0x200, "demo.mf4", "demo.dbc");

        model.setFocusedSignalKey(a);
        assertEquals(a, model.focusedSignalKey().orElseThrow());
        assertTrue(model.workspaceKeys().isEmpty());

        model.setFocusedSignalKey(b);
        assertEquals(b, model.focusedSignalKey().orElseThrow());
        assertTrue(model.workspaceKeys().isEmpty());

        model.addToWorkspace(a);
        assertEquals(List.of(a), model.workspaceKeys());
        model.setFocusedSignalKey(b);
        assertEquals(List.of(a), model.workspaceKeys());
        assertEquals(b, model.focusedSignalKey().orElseThrow());
    }

    private static CanonicalSignal can(String name, String message, long canId, long ts, double phys, long raw) {
        return new CanonicalSignal(name, message, ts, canId, 0, phys, raw, "u", null, SourceReference.ofLine(1));
    }
}
