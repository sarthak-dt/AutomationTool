package com.stellar.platform.desktop.presentation;

import com.stellar.platform.domain.CanonicalSignal;
import com.stellar.platform.domain.SourceReference;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.processing.catalog.InMemorySignalCatalogStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SignalsWorkspaceModelTest {

    private final SignalsScreenModel model = new SignalsScreenModel();

    @AfterEach
    void tearDown() {
        model.shutdown();
    }

    @Test
    void addRemoveClearAndDuplicates() {
        InMemorySignalCatalogStore store = catalogWith(
                can("SPEED", "MSG", 0x100, 1_000L, 18.5, 1L),
                can("RPM", "MSG", 0x200, 1_000L, 800.0, 2L),
                can("TEMP", "MSG", 0x300, 2_000L, 87.0, 3L)
        );
        model.replaceCatalog(store);
        SignalCatalogKey speed = key("SPEED", "MSG", 0x100);
        SignalCatalogKey rpm = key("RPM", "MSG", 0x200);
        SignalCatalogKey temp = key("TEMP", "MSG", 0x300);

        assertTrue(model.workspaceKeys().isEmpty());
        assertTrue(model.addToWorkspace(speed));
        assertFalse(model.addToWorkspace(speed));
        assertEquals(1, model.workspaceKeys().size());
        assertEquals(speed, model.focusedWorkspaceKey().orElseThrow());

        model.addToWorkspace(List.of(rpm, temp, speed));
        assertEquals(List.of(speed, rpm, temp), model.workspaceKeys());
        assertEquals(temp, model.focusedWorkspaceKey().orElseThrow());

        model.setFocusedWorkspaceKey(rpm);
        assertEquals(rpm, model.focusedWorkspaceKey().orElseThrow());
        assertEquals(3, model.workspaceKeys().size());

        assertTrue(model.removeFromWorkspace(rpm));
        assertEquals(List.of(speed, temp), model.workspaceKeys());
        assertEquals(temp, model.focusedWorkspaceKey().orElseThrow());

        model.clearWorkspace();
        assertTrue(model.workspaceKeys().isEmpty());
        assertTrue(model.focusedWorkspaceKey().isEmpty());
    }


    @Test
    void testF_duplicateSignalAdditionDoesNotDuplicateColumns() {
        InMemorySignalCatalogStore store = catalogWith(
                can("SPEED", "MSG", 0x100, 1_000L, 18.5, 1L)
        );
        model.replaceCatalog(store);
        SignalCatalogKey speed = key("SPEED", "MSG", 0x100);
        assertTrue(model.addToWorkspace(speed));
        assertFalse(model.addToWorkspace(speed));
        model.addToWorkspace(List.of(speed, speed, speed));
        assertEquals(1, model.workspaceKeys().size());
        assertEquals(1, model.workspaceEntries().size());
    }

    @Test
    void viewingAnotherSourceClearsActiveWorkspaceAndFocus() {
        InMemorySignalCatalogStore first = catalogWith(
                can("SPEED", "MSG", 0x100, 1_000L, 18.5, 1L));
        model.replaceCatalog(first, "first", SignalsProcessingContext.of("first.mf4", "demo.dbc", "Ch1"), false);
        SignalCatalogKey firstKey = model.catalog().entries().getFirst().key();
        assertTrue(model.addToWorkspace(firstKey));
        assertEquals(1, model.workspaceKeys().size());

        InMemorySignalCatalogStore second = new InMemorySignalCatalogStore("second.mf4", "demo.dbc", 1_000);
        second.accept(List.of(new CanonicalSignal(
                "RPM", "MSG", 2_000L, 0x200, 1, 900.0, 2L, "rpm", null, SourceReference.ofLine(2))));

        model.replaceCatalog(second, "second", SignalsProcessingContext.of("second.mf4", "demo.dbc", "Ch2"), false);

        assertTrue(model.workspaceKeys().isEmpty(),
                "a View Signals source switch must not carry previous MF4 columns");
        assertTrue(model.focusedSignalKey().isEmpty());
    }


    @Test
    @Timeout(15)
    void alignedLoadMarksMissingDistinctFromZero() throws Exception {
        InMemorySignalCatalogStore store = catalogWith(
                can("A", "M", 1L, 1_000L, 0.0, 1L),
                can("A", "M", 1L, 3_000L, 5.0, 2L),
                can("B", "M", 2L, 2_000L, 0.0, 3L),
                can("B", "M", 2L, 3_000L, 9.0, 4L)
        );
        model.replaceCatalog(store);
        model.addToWorkspace(List.of(key("A", "M", 1L), key("B", "M", 2L)));

        AlignedWorkspaceSnapshot snap = awaitAligned();
        assertEquals(2, snap.signals().size());
        assertEquals(3, snap.rows().size());
        assertEquals(0.0, snap.rows().get(0).physicalAt(0));
        assertNull(snap.rows().get(0).physicalAt(1));
        assertNull(snap.rows().get(1).physicalAt(0));
        assertEquals(0.0, snap.rows().get(1).physicalAt(1));
    }

    @Test
    @Timeout(15)
    void emptyWorkspaceReturnsEmptySnapshot() throws Exception {
        InMemorySignalCatalogStore store = catalogWith(can("A", "M", 1L, 1L, 1.0, 1L));
        model.replaceCatalog(store);
        AlignedWorkspaceSnapshot snap = awaitAligned();
        assertTrue(snap.signals().isEmpty());
        assertTrue(snap.rows().isEmpty());
    }

    private AlignedWorkspaceSnapshot awaitAligned() throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<AlignedWorkspaceSnapshot> ref = new AtomicReference<>();
        model.loadWorkspaceAlignedAsync(s -> {
            ref.set(s);
            done.countDown();
        });
        assertTrue(done.await(10, TimeUnit.SECONDS));
        return ref.get();
    }

    private static InMemorySignalCatalogStore catalogWith(CanonicalSignal... signals) {
        InMemorySignalCatalogStore store = new InMemorySignalCatalogStore("demo.mf4", "demo.dbc", 1_000);
        store.accept(List.of(signals));
        return store;
    }

    private static SignalCatalogKey key(String name, String message, long canId) {
        return new SignalCatalogKey(name, com.stellar.platform.domain.catalog.SignalSourceType.DBC_CAN, message, canId, "demo.mf4", "demo.dbc");
    }

    private static CanonicalSignal can(String name, String message, long canId, long ts, double phys, long raw) {
        return new CanonicalSignal(name, message, ts, canId, 0, phys, raw, "u", null, SourceReference.ofLine(1));
    }
}
